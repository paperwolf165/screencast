package net.prototux.aare.cast

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.IBinder
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
    private val lock = Any()
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
            return synchronized(lock) {
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
        synchronized(lock) { releaseDisplay() }
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
        synchronized(lock) {
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
        synchronized(lock) { releaseDisplay() }
        exitProcess(0)
    }

    // ---------------------------------------------------------------- privileged plumbing

    private fun onClientDied() {
        Log.i(TAG, "the app is gone: restoring the phone's display")
        synchronized(lock) { releaseDisplay() }
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
        "this head unit now opens AARE without asking"
    }.getOrElse { "could not set the USB default: ${it.cause ?: it}" }

    private companion object {
        const val TAG = "AareCastHost"
        const val NAME = "aare-cast"
        const val INJECT_MODE_ASYNC = 0
        const val SHELL_TIMEOUT_S = 15L
        const val PER_USER_RANGE = 100_000
    }
}
