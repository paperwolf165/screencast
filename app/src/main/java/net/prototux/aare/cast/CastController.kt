package net.prototux.aare.cast

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import java.util.concurrent.Executors

/**
 * One car session's screen cast: owns the root helper ([CastLink]), fits the phone's display to
 * the head unit while the cast runs ([CastDisplayPlan]) and puts it back afterwards, feeds the
 * mirror into the car screen's surface ([CastScreen]) and sends the car's touches to the phone.
 *
 * Everything that waits for root runs on one worker thread, in order. [start], [attach], [detach]
 * and [stop] are called on the main thread; [touch] and [key] from any thread.
 */
class CastController(private val context: Context, private val settings: Settings) {
    enum class Status { STARTING, MIRRORING, NO_ROOT, FAILED }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "aare-cast").apply { isDaemon = true } }
    private val touchEnabled = settings.castTouch

    @Volatile private var link: CastLink? = null
    /** When the helper last failed to start (elapsedRealtime), so a missing root isn't retried in a loop. */
    private var linkFailedAt = 0L

    /** The car screen surface the mirror draws into; guarded by [surfaceLock]. */
    private val surfaceLock = Any()
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Where the mirror sits in the video frame, and the phone display's size: what a car touch is mapped with. */
    @Volatile private var view: Rect? = null
    @Volatile private var phone = Point(1, 1)

    /** What the display was last fitted to (worker thread). */
    private var applied: Triple<Int, Int, CastOptions>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false

    init {
        synchronized(stateLock) { active++ }
    }

    /**
     * The car's video started: fit the phone's display to the [targetWidth] x [targetHeight] pixels
     * the cast fills on the car screen. Called again when the car changes its layout.
     */
    fun start(targetWidth: Int, targetHeight: Int) {
        if (stopped) return
        acquireWakeLock()
        val options = CastOptions(
            resize = settings.castResize,
            resolution = settings.castResolution,
            customWidth = settings.castCustomWidth,
            customHeight = settings.castCustomHeight,
            dpi = settings.castDpi,
            lockOrientation = settings.castLandscape,
            blank = settings.castBlankScreen,
        )
        worker.execute {
            if (stopped) return@execute
            val l = ensureLink() ?: return@execute
            val key = Triple(targetWidth, targetHeight, options)
            if (applied == key) return@execute
            applyDisplay(l, targetWidth, targetHeight, options)
            applied = key
            if (settings.castPanelOff) {
                val off = l.panel(false)
                DiagLog.add(if (off) "screen cast: phone panel off" else "screen cast: this Android won't switch the panel off; it stays at brightness 0")
            }
        }
    }

    /** The car screen has a surface for the mirror ([width] x [height] pixels), or it changed size. */
    fun attach(s: Surface, width: Int, height: Int) {
        if (stopped) return
        synchronized(surfaceLock) {
            surface = s
            surfaceWidth = width
            surfaceHeight = height
        }
        worker.execute {
            if (stopped) return@execute
            val l = ensureLink() ?: return@execute
            synchronized(surfaceLock) {
                // Replaced or gone while waiting for root: the newer call mirrors.
                if (surface !== s || surfaceWidth != width || surfaceHeight != height) return@execute
                val ok = l.mirror(s, width, height)
                _status.value = if (ok) Status.MIRRORING else Status.FAILED
                DiagLog.add(if (ok) "screen cast: mirroring the phone at ${width}x$height" else "screen cast: Android refused to mirror the screen")
            }
        }
    }

    /** The surface is going away: stop drawing into it before it is released. */
    fun detach(s: Surface) {
        synchronized(surfaceLock) {
            if (surface !== s) return
            surface = null
            link?.unmirror()
        }
    }

    /** Where the mirror is laid out in the video frame, and the phone display's current size (pixels). */
    fun layout(viewInFrame: Rect, phoneSize: Point) {
        view = viewInFrame
        phone = Point(phoneSize)
    }

    /** A car touch in video frame pixels: mapped into the phone's display and injected. Recycles [ev]. */
    fun touch(ev: MotionEvent) {
        val l = link
        val r = view
        if (touchEnabled && l != null && r != null && r.width() > 0 && r.height() > 0) {
            val p = phone
            val n = ev.pointerCount
            val ids = IntArray(n) { ev.getPointerId(it) }
            val xs = FloatArray(n) { ((ev.getX(it) - r.left) * p.x / r.width()).coerceIn(0f, (p.x - 1).coerceAtLeast(0).toFloat()) }
            val ys = FloatArray(n) { ((ev.getY(it) - r.top) * p.y / r.height()).coerceIn(0f, (p.y - 1).coerceAtLeast(0).toFloat()) }
            l.touch(ev.downTime, ev.eventTime, ev.action, ids, xs, ys)
        }
        ev.recycle()
    }

    /** A car key (Back, Home, media keys, a rotary step): pressed on the phone. */
    fun key(keyCode: Int, down: Boolean) {
        // Android Auto's own key codes (its Media, Navigation, Phone buttons) mean nothing to the phone.
        if (!touchEnabled || keyCode <= 0 || keyCode > KeyEvent.getMaxKeyCode()) return
        link?.key(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
    }

    /** The session ended: the phone's display goes back to what it was and the helper exits. */
    fun stop() {
        if (stopped) return
        stopped = true
        releaseWakeLock()
        synchronized(surfaceLock) {
            surface = null
            link?.unmirror()
        }
        worker.execute {
            synchronized(stateLock) {
                val l = link
                val script = settings.castRestoreScript
                if (script != null) {
                    // Through the helper when it still runs (no new su process), else straight through su.
                    val done = (l?.takeIf { it.alive }?.shell(script) ?: RootShell.run(script)) != null
                    if (done) settings.castRestoreScript = null
                    DiagLog.add(if (done) "screen cast: phone display restored" else "screen cast: could not restore the phone display; it is retried when the app next starts")
                }
                // Whatever was playing stops with the car: a media Pause key, which does nothing when
                // nothing plays. Otherwise the music would carry on from the phone's own speaker.
                if (l != null && l.alive) {
                    l.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    l.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                } else {
                    RootShell.run("input keyevent KEYCODE_MEDIA_PAUSE")
                }
                DiagLog.add("screen cast: media paused")
                if (l != null && l.alive) {
                    l.panel(true)
                    if (settings.castHotspot) l.hotspot(false)?.let { DiagLog.add("screen cast: $it") }
                    if (settings.castAutoLock) {
                        l.lock()
                        DiagLog.add("screen cast: phone locked")
                    }
                } else if (settings.castAutoLock) {
                    RootShell.run("input keyevent KEYCODE_SLEEP")
                }
                l?.release()
                link = null
                active--
            }
        }
        worker.shutdown()
    }

    // ------------------------------------------------------------------ worker thread

    private fun ensureLink(): CastLink? {
        link?.takeIf { it.alive }?.let { return it }
        if (linkFailedAt != 0L && SystemClock.elapsedRealtime() - linkFailedAt < RETRY_AFTER_MS) return null
        val l = CastLink(context)
        if (!l.connect()) {
            l.release()
            linkFailedAt = SystemClock.elapsedRealtime()
            _status.value = Status.NO_ROOT
            DiagLog.add("screen cast: no root helper (is Wingmirror allowed in your root manager?)")
            return null
        }
        linkFailedAt = 0L
        link = l
        applied = null
        DiagLog.add("screen cast: root helper running")
        // From the next time on, plugging into this head unit asks nothing.
        l.usbDefault()?.let { DiagLog.add("screen cast: $it") }
        // If the app dies before any display change is made, the helper still locks the phone.
        l.setCleanup(onDeath(""))
        connected(l)
        return l
    }

    /** Once per helper, as the car connects: what has to be in place before the picture. */
    private fun connected(l: CastLink) {
        // The car's GPS replaces the phone's own (car/PhoneLocationFeed): Android only lets the
        // "mock location app" do that, which root makes this app without the Developer options.
        l.shell("appops set ${context.packageName} android:mock_location allow")
        if (settings.castAutoUnlock) {
            val result = l.unlock(settings.castUnlockCode)
            DiagLog.add(if (result == "unlocked") "screen cast: phone unlocked" else "screen cast: could not unlock the phone: $result")
        }
        if (settings.castHotspot) l.hotspot(true)?.let { DiagLog.add("screen cast: $it") }
    }

    /** What the helper runs if the app dies mid-cast: [restore], then the lock if the session's end locks. */
    private fun onDeath(restore: String): String =
        if (settings.castAutoLock) (restore + "\ninput keyevent KEYCODE_SLEEP").trim() else restore

    private fun applyDisplay(l: CastLink, targetWidth: Int, targetHeight: Int, options: CastOptions) = synchronized(stateLock) {
        // What an earlier fit changed comes off first, so the state probed below is the phone's own.
        settings.castRestoreScript?.let { earlier ->
            if (l.shell(earlier) == null) return@synchronized
            settings.castRestoreScript = null
            l.setCleanup(onDeath(""))
        }
        if (!options.changesDisplay) return@synchronized
        val state = l.shell(CastDisplayPlan.PROBE)?.let(CastDisplayPlan::parse)
        if (state == null) {
            DiagLog.add("screen cast: could not read the phone's display state; leaving it as it is")
            return@synchronized
        }
        val plan = CastDisplayPlan.plan(state, options, targetWidth, targetHeight)
        if (plan.apply.isBlank()) return@synchronized
        // The way back is saved, and given to the helper, before anything changes.
        settings.castRestoreScript = plan.restore
        l.setCleanup(onDeath(plan.restore))
        l.shell(plan.apply)
        DiagLog.add("screen cast: phone display set to ${plan.summary}")
    }

    @Suppress("DEPRECATION") // the screen has to stay on for the mirror to show anything
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "aare:cast").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_MAX_MS)
                }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    companion object {
        private const val RETRY_AFTER_MS = 30_000L
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L

        /** Serialises display changes and their recovery; [active] counts the casts that own the display. */
        private val stateLock = Any()
        private var active = 0

        /**
         * Undoes display changes a cast left behind (the app was killed and the helper with it, or
         * the phone rebooted mid-cast). Called when the app starts; does nothing while a cast runs.
         */
        fun recover(context: Context) {
            val settings = Settings(context)
            if (settings.castRestoreScript == null) return
            val task = Runnable {
                synchronized(stateLock) {
                    val script = settings.castRestoreScript
                    if (active == 0 && script != null && RootShell.run(script) != null) {
                        settings.castRestoreScript = null
                        DiagLog.add("screen cast: restored the phone display left changed by an interrupted cast")
                    }
                }
            }
            Thread(task, "aare-cast-recover").apply { isDaemon = true }.start()
        }

        /** Puts the display back now, whatever a cast changed (the settings page's button). Blocks. */
        fun restoreNow(context: Context): Boolean = synchronized(stateLock) {
            val settings = Settings(context)
            val script = settings.castRestoreScript ?: RESET_ALL
            val ok = RootShell.run(script) != null
            if (ok) settings.castRestoreScript = null
            ok
        }

        /** The stock state, for when there is no saved way back: no size or density override, rotation free, screen lit. */
        private const val RESET_ALL = "settings put system screen_brightness 128\nwm size reset\nwm density reset\n" +
            "cmd window fixed-to-user-rotation default >/dev/null 2>&1\n" +
            "cmd window set-ignore-orientation-request false >/dev/null 2>&1\n" +
            "settings put system accelerometer_rotation 1"
    }
}
