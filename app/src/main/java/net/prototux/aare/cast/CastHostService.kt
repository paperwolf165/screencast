package net.prototux.aare.cast

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The root side of screen cast mode (see [ICastHost]); runs in the process [CastMain] starts.
 *
 * Root is what makes the cast silent: a root process may mirror the display without the
 * screen-capture consent dialog, inject real multi-touch input without an accessibility service,
 * and run `wm` / `settings` to fit the phone's display to the car's.
 *
 * The hidden framework calls (the mirror display, input injection, the USB default) are reached by
 * reflection and differ between Android versions, so each one tries the known shapes in turn and
 * fails with a log line instead of a crash.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi") // the hidden calls are the point of a root helper
class CastHostService(private val allowedUid: Int, private val context: Context) : ICastHost.Stub() {
    private val displayLock = Any()
    private var display: VirtualDisplay? = null
    @Volatile private var cleanup: String? = null
    private var client: IBinder? = null

    /** The app took the binder ([setCleanup] is its first call). */
    @Volatile var claimed = false
        private set

    /** Only the app that started this helper (and the helper itself) may use it. */
    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid != allowedUid && uid != Process.myUid()) throw SecurityException("uid $uid may not use the cast helper")
    }

    override fun mirror(surface: Surface?, width: Int, height: Int): Boolean {
        enforceCaller()
        if (surface == null || width <= 0 || height <= 0) return false
        val id = Binder.clearCallingIdentity()
        try {
            return synchronized(displayLock) {
                releaseDisplay()
                display = createMirror(surface, width, height)
                display != null
            }
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun unmirror() {
        enforceCaller()
        synchronized(displayLock) { releaseDisplay() }
    }

    override fun touch(downTime: Long, eventTime: Long, action: Int, ids: IntArray?, xs: FloatArray?, ys: FloatArray?) {
        enforceCaller()
        if (ids == null || xs == null || ys == null) return
        val n = ids.size
        if (n == 0 || xs.size != n || ys.size != n) return
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = 1f
                size = 1f
            }
        }
        val ev = MotionEvent.obtain(downTime, eventTime, action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject(ev)
        ev.recycle()
    }

    override fun key(action: Int, keyCode: Int) {
        enforceCaller()
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }

    override fun shell(script: String?): String {
        enforceCaller()
        return run(script ?: return "")
    }

    override fun setCleanup(client: IBinder?, script: String?) {
        enforceCaller()
        claimed = true
        cleanup = script?.takeIf { it.isNotBlank() }
        if (client == null) return
        synchronized(displayLock) {
            if (this.client != null) return
            this.client = client
        }
        // The app is already gone if this throws: clean up right away.
        runCatching { client.linkToDeath(IBinder.DeathRecipient { onClientDied() }, 0) }.onFailure { onClientDied() }
    }

    override fun usbDefault(packageName: String?, uid: Int): String {
        enforceCaller()
        if (packageName == null) return "no package"
        val id = Binder.clearCallingIdentity()
        try {
            return setUsbDefault(packageName, uid)
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun exit() {
        enforceCaller()
        synchronized(displayLock) { releaseDisplay() }
        if (panelOff) setPanelPower(true)
        exitProcess(0)
    }

    override fun panel(on: Boolean): Boolean {
        enforceCaller()
        val id = Binder.clearCallingIdentity()
        try {
            handler.removeCallbacks(keepPanelOff)
            val ok = setPanelPower(on)
            panelOff = !on && ok
            if (panelOff) handler.postDelayed(keepPanelOff, PANEL_REAPPLY_MS)
            return ok
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun lock() {
        enforceCaller()
        val id = Binder.clearCallingIdentity()
        try {
            runCatching {
                val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
                val options: Bundle? = null
                wm.javaClass.getMethod("lockNow", Bundle::class.java).invoke(wm, options)
            }.onFailure { Log.w(TAG, "lockNow failed: ${it.cause ?: it}") }
            // The screen off as well; with a lock set, this alone locks on most phones.
            pressKey(KeyEvent.KEYCODE_SLEEP)
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun unlock(credential: String?): String {
        enforceCaller()
        val id = Binder.clearCallingIdentity()
        try {
            val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return "no keyguard service"
            pressKey(KeyEvent.KEYCODE_WAKEUP)
            Thread.sleep(WAKE_SETTLE_MS)
            if (!keyguard.isKeyguardLocked) return UNLOCKED
            // Without a PIN this is the whole unlock; with one it brings up the PIN pad.
            run("wm dismiss-keyguard")
            if (awaitUnlocked(keyguard, BOUNCER_WAIT_MS)) return UNLOCKED
            if (credential.isNullOrEmpty()) return "the lock screen asks for a code and none is saved"
            typeText(credential)
            pressKey(KeyEvent.KEYCODE_ENTER)
            return if (awaitUnlocked(keyguard, UNLOCK_WAIT_MS)) UNLOCKED else "still locked after typing the saved code"
        } catch (e: Throwable) {
            Log.w(TAG, "unlock failed", e)
            return "error: $e"
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun hotspot(on: Boolean): String {
        enforceCaller()
        val id = Binder.clearCallingIdentity()
        try {
            return if (on) startHotspot() else stopHotspot()
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ---------------------------------------------------------------- privileged plumbing

    private fun onClientDied() {
        Log.i(TAG, "the app is gone: restoring the phone's display")
        synchronized(displayLock) { releaseDisplay() }
        handler.removeCallbacks(keepPanelOff)
        if (panelOff) setPanelPower(true)
        if (hotspotStarted) stopHotspot()
        cleanup?.let { run(it) }
        exitProcess(0)
    }

    private fun releaseDisplay() {
        display?.let { d -> runCatching { d.release() } }
        display = null
    }

    /**
     * A virtual display that shows what the phone's main display shows, drawn into [surface].
     * First the platform's own "mirror this display" entry (Android 11+, what scrcpy uses), then
     * the public call with the auto-mirror flag, which root may use without a MediaProjection.
     */
    private fun createMirror(surface: Surface, w: Int, h: Int): VirtualDisplay? {
        runCatching {
            val int = Int::class.javaPrimitiveType
            val m = DisplayManager::class.java.getMethod("createVirtualDisplay", String::class.java, int, int, int, Surface::class.java)
            val vd = m.invoke(null, NAME, w, h, Display.DEFAULT_DISPLAY, surface) as? VirtualDisplay
            if (vd != null) return vd
        }.onFailure { Log.i(TAG, "no direct mirror display here (${it.cause ?: it}); trying the auto-mirror flag") }
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        return runCatching {
            // Density doesn't matter: the display only mirrors.
            dm.createVirtualDisplay(NAME, w, h, 160, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        }.onFailure { Log.w(TAG, "Android refused the mirror display", it) }.getOrNull()
    }

    /** InputManagerGlobal from Android 14, InputManager before; both have injectInputEvent(InputEvent, int). */
    private val injector: Pair<Any, Method>? by lazy {
        val manager = sequenceOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")
            .mapNotNull { name -> runCatching { Class.forName(name).getMethod("getInstance").invoke(null) }.getOrNull() }
            .firstOrNull()
            ?: context.getSystemService(Context.INPUT_SERVICE)
        if (manager == null) {
            Log.w(TAG, "no input manager")
            return@lazy null
        }
        runCatching {
            manager to manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.onFailure { Log.w(TAG, "no injectInputEvent on ${manager.javaClass.name}", it) }.getOrNull()
    }

    private fun inject(event: InputEvent) {
        val (manager, method) = injector ?: return
        runCatching { method.invoke(manager, event, INJECT_MODE_ASYNC) }
            .onFailure { Log.w(TAG, "injectInputEvent failed: ${it.cause ?: it}") }
    }

    private fun pressKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
    }

    /** Types [text] as key presses: a lock screen's PIN pad and password field both take them. */
    private fun typeText(text: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events == null) {
            Log.w(TAG, "the unlock code has characters with no key")
            return
        }
        for (e in events) inject(e)
    }

    private fun awaitUnlocked(keyguard: KeyguardManager, timeoutMs: Long): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            if (!keyguard.isKeyguardLocked) return true
            Thread.sleep(100)
        }
        return !keyguard.isKeyguardLocked
    }

    // ---------------------------------------------------------------- the panel

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var panelOff = false

    /**
     * Android puts the panel back on whenever it changes the display's state by itself (a
     * rotation, a wake-up): while the cast wants it off, it is switched off again. Setting a mode
     * the panel already has does nothing.
     */
    private val keepPanelOff = object : Runnable {
        override fun run() {
            if (!panelOff) return
            setPanelPower(false)
            handler.postDelayed(this, PANEL_REAPPLY_MS)
        }
    }

    /** The physical displays' tokens, as SurfaceFlinger knows them (more than one on a foldable). */
    private val displayTokens: List<IBinder> by lazy { findDisplayTokens() }

    /**
     * The display's power mode in SurfaceFlinger, under Android's own idea of the screen: the
     * system keeps the phone awake and keeps drawing (so the mirror runs), with the panel dark.
     * scrcpy's "turn screen off".
     */
    private fun setPanelPower(on: Boolean): Boolean {
        val tokens = displayTokens
        if (tokens.isEmpty()) return false
        return runCatching {
            val set = Class.forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            for (t in tokens) set.invoke(null, t, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
            true
        }.onFailure { Log.w(TAG, "setDisplayPowerMode failed: ${it.cause ?: it}") }.getOrDefault(false)
    }

    private fun findDisplayTokens(): List<IBinder> {
        fun tokensOf(holder: Class<*>): List<IBinder> {
            val ids = holder.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val get = holder.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            return ids.toList().mapNotNull { id -> get.invoke(null, id) as? IBinder }
        }
        // Android 14+: the calls moved to the system server's DisplayControl.
        runCatching { tokensOf(displayControlClass()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val sc = runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull() ?: return emptyList()
        // Android 10 to 13.
        runCatching { tokensOf(sc) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { sc.getMethod("getInternalDisplayToken").invoke(null) as? IBinder }.getOrNull()?.let { return listOf(it) }
        // Android 8 and 9.
        runCatching { sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0) as? IBinder }.getOrNull()?.let { return listOf(it) }
        Log.w(TAG, "no display token: the panel can't be switched off on this Android")
        return emptyList()
    }

    /** DisplayControl lives in services.jar, with its native half in libandroid_servers (as scrcpy loads it). */
    private fun displayControlClass(): Class<*> {
        val create = Class.forName("com.android.internal.os.ClassLoaderFactory").getDeclaredMethod(
            "createClassLoader", String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
        val loader = create.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val control = loader.loadClass("com.android.server.display.DisplayControl")
        val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        load.isAccessible = true
        load.invoke(Runtime.getRuntime(), control, "android_servers")
        return control
    }

    // ---------------------------------------------------------------- the hotspot

    @Volatile private var hotspotStarted = false

    /** The phone's own Wi-Fi hotspot (tethering, with the name and password set in Android's settings). Android 11+. */
    private fun startHotspot(): String = runCatching {
        if (wifiApEnabled()) return@runCatching "the hotspot was already on; it is left as it is"
        val tm = context.getSystemService("tethering") ?: return@runCatching "this Android has no tethering service (needs Android 11)"
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "AareCastTetheringCallback"
                else -> {
                    Log.i(TAG, "hotspot: ${method.name} ${args?.joinToString() ?: ""}")
                    if (method.name == "onTetheringFailed") hotspotStarted = false
                    null
                }
            }
        }
        tm.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackClass)
            .invoke(tm, TETHERING_WIFI, Executor { it.run() }, callback)
        hotspotStarted = true
        "hotspot turned on"
    }.getOrElse { "could not turn the hotspot on: ${it.cause ?: it}" }

    private fun stopHotspot(): String {
        if (!hotspotStarted) return "the hotspot was not turned on by the cast; it is left as it is"
        hotspotStarted = false
        return runCatching {
            val tm = context.getSystemService("tethering") ?: return@runCatching "no tethering service"
            tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
            "hotspot turned off"
        }.getOrElse { "could not turn the hotspot off: ${it.cause ?: it}" }
    }

    private fun wifiApEnabled(): Boolean = runCatching {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) ?: return@runCatching false
        wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
    }.getOrDefault(false)

    private fun run(script: String): String = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(SHELL_TIMEOUT_S, TimeUnit.SECONDS)) p.destroy()
        out
    }.getOrElse { "error: $it" }

    /**
     * What ticking "always" on Android's "open with" dialog does: the head unit's accessory opens
     * this app from now on, and the app may talk to it, without a dialog.
     */
    @SuppressLint("PrivateApi")
    private fun setUsbDefault(packageName: String, uid: Int): String = runCatching {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "usb") as IBinder
        val usb = Class.forName("android.hardware.usb.IUsbManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: return@runCatching "no USB service"
        val accessory = usb.javaClass.getMethod("getCurrentAccessory").invoke(usb) ?: return@runCatching "no USB accessory attached"
        val methods = usb.javaClass.methods
        methods.first { it.name == "grantAccessoryPermission" && it.parameterTypes.size == 2 }.invoke(usb, accessory, uid)
        methods.first { it.name == "setAccessoryPackage" && it.parameterTypes.size == 3 }.invoke(usb, accessory, packageName, uid / PER_USER_RANGE)
        "this head unit now opens Wingmirror without asking"
    }.getOrElse { "could not set the USB default: ${it.cause ?: it}" }

    private companion object {
        const val TAG = "AareCastHost"
        const val NAME = "aare-cast"
        const val INJECT_MODE_ASYNC = 0
        const val SHELL_TIMEOUT_S = 15L
        const val PER_USER_RANGE = 100_000
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
        const val PANEL_REAPPLY_MS = 2_000L
        const val TETHERING_WIFI = 0
        const val WAKE_SETTLE_MS = 500L
        const val BOUNCER_WAIT_MS = 1_200L
        const val UNLOCK_WAIT_MS = 3_000L
        const val UNLOCKED = "unlocked"
    }
}
