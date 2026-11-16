package net.prototux.aare.cast

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.mirror.MirrorTouchService
import net.prototux.aare.mirror.PhoneMirror
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One car session's screen cast: owns the root helper ([CastLink]), fits the phone's display to
 * the head unit while the cast runs ([CastDisplayPlan]) and puts it back afterwards, feeds the
 * mirror into the car screen's surface ([CastScreen]) and sends the car's touches to the phone.
 *
 * Everything that waits for root runs on one worker thread, in order. [start], [attach], [detach]
 * and [stop] are called on the main thread; [touch] and [key] from any thread.
 */
class CastController(
    private val context: Context,
    private val settings: Settings,
    /** The screen-share consent, for the cast without root; null until the user gives it. */
    private val projection: () -> MediaProjection? = { null },
) {
    enum class Status { STARTING, MIRRORING, NO_ROOT, FAILED, NEEDS_SHARE }

    /**
     * Without root: the mirror comes from Android's screen share ([projection]) and nothing else
     * is done: no helper, no touch, no change to the phone's display.
     */
    val lite: Boolean = settings.castLite

    /** Lite: the screen share is there and the mirror runs (main thread). The session starts its sound on it. */
    var onShared: (() -> Unit)? = null

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
    /** The session got as far as casting ([start] was called): only then does its end pause, lock and so on. */
    @Volatile private var began = false

    init {
        synchronized(stateLock) { active++ }
    }

    /**
     * The car's video started: fit the phone's display to the [targetWidth] x [targetHeight] pixels
     * the cast fills on the car screen. Called again when the car changes its layout.
     */
    fun start(targetWidth: Int, targetHeight: Int) {
        if (stopped) return
        began = true
        acquireWakeLock()
        if (lite) return
        val options = CastOptions(
            resize = settings.castResize,
            resolution = settings.castResolution,
            customWidth = settings.castCustomWidth,
            customHeight = settings.castCustomHeight,
            dpi = settings.castDpi,
            lockOrientation = settings.castLandscape,
            blank = settings.castBlankScreen,
            uiScale = settings.castUiScale,
            fontScale = settings.castFontScale,
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
        if (lite) {
            val ok = PhoneMirror.attach(projection(), s, width, height, context.resources.displayMetrics.densityDpi)
            _status.value = if (ok) Status.MIRRORING else Status.NEEDS_SHARE
            if (ok) onShared?.invoke()
            return
        }
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
        if (lite) {
            PhoneMirror.detach()
            return
        }
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
        if (lite) {
            if (touchEnabled) liteTouch(ev)
            ev.recycle()
            return
        }
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

    // ---- lite: touch without root

    private val main = Handler(Looper.getMainLooper())
    /** The finger's path on the phone's display since it went down (the session's input thread). */
    private val stroke = ArrayList<Pair<Float, Float>>()
    private var strokeStart = 0L
    private var strokePointer = -1

    /**
     * Without root a touch can't be injected as it happens. The first finger's path is collected
     * and, when it lifts, replayed on the phone by the accessibility service ([MirrorTouchService],
     * if the user turned it on): a tap, a long press or a swipe. One finger only, and the phone
     * reacts when the finger leaves the car screen, not while it moves.
     */
    private fun liteTouch(ev: MotionEvent) {
        val r = view ?: return
        if (r.width() <= 0 || r.height() <= 0) return
        val p = phone
        fun point(index: Int) = ((ev.getX(index) - r.left) * p.x / r.width()).coerceIn(0f, (p.x - 1).coerceAtLeast(0).toFloat()) to
            ((ev.getY(index) - r.top) * p.y / r.height()).coerceIn(0f, (p.y - 1).coerceAtLeast(0).toFloat())
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stroke.clear()
                strokePointer = ev.getPointerId(0)
                strokeStart = ev.eventTime
                stroke += point(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(strokePointer)
                if (index >= 0 && stroke.isNotEmpty()) stroke += point(index)
            }
            MotionEvent.ACTION_UP -> {
                val index = ev.findPointerIndex(strokePointer)
                if (index >= 0 && stroke.isNotEmpty()) stroke += point(index)
                val points = ArrayList(stroke)
                val duration = ev.eventTime - strokeStart
                stroke.clear()
                strokePointer = -1
                if (points.isEmpty()) return
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val (x0, y0) = points.first()
                val moved = points.any { (x, y) -> kotlin.math.hypot(x - x0, y - y0) > slop }
                main.post {
                    val service = MirrorTouchService.instance ?: return@post
                    when {
                        // Every third point is plenty for a path, and keeps the gesture small.
                        moved -> service.swipe(points.filterIndexed { i, _ -> i % 3 == 0 || i == points.lastIndex }, duration)
                        duration > LONG_PRESS_MS -> service.swipe(listOf(points.first()), duration)
                        else -> service.tap(x0, y0)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                stroke.clear()
                strokePointer = -1
            }
        }
    }

    /** A car key (Back, Home, media keys, a rotary step): pressed on the phone. */
    fun key(keyCode: Int, down: Boolean) {
        // Android Auto's own key codes (its Media, Navigation, Phone buttons) mean nothing to the phone.
        if (!touchEnabled || keyCode <= 0 || keyCode > KeyEvent.getMaxKeyCode()) return
        if (lite) {
            // An accessibility service can't press keys: Back and Home are what it has.
            if (down) return
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> main.post { MirrorTouchService.instance?.back() }
                KeyEvent.KEYCODE_HOME -> main.post { MirrorTouchService.instance?.home() }
            }
            return
        }
        link?.key(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
    }

    /**
     * The session ended: the phone's display goes back to what it was.
     *
     * A head unit often drops and re-opens its USB link several times before a session holds, and
     * some attempts end before any picture. So the rest is measured: a session that never got to
     * cast changes nothing; one that did pauses the media at once, but only locks the phone and
     * turns the hotspot off if the car hasn't come back within [END_GRACE_MS]. Locking the phone
     * in the middle of the car's reconnection is what kept it from reconnecting.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        releaseWakeLock()
        if (lite) {
            PhoneMirror.detach()
            synchronized(stateLock) { active-- }
            worker.shutdown()
            return
        }
        synchronized(surfaceLock) {
            surface = null
            link?.unmirror()
        }
        worker.execute {
            synchronized(stateLock) {
                val l = link?.takeIf { it.alive }
                val script = settings.castRestoreScript
                if (script != null) {
                    // Through the helper when it still runs (no new su process), else straight through su.
                    val done = (l?.shell(script) ?: RootShell.run(script)) != null
                    if (done) settings.castRestoreScript = null
                    DiagLog.add(if (done) "screen cast: phone display restored" else "screen cast: could not restore the phone display; it is retried when the app next starts")
                }
                link = null
                active--
                if (l == null) return@synchronized
                l.setCleanup(onDeath(""))
                l.panel(true)
                if (!began) {
                    l.release()
                    return@synchronized
                }
                // Whatever was playing stops with the car: a media Pause key, which does nothing
                // when nothing plays. Otherwise the music would carry on from the phone's speaker.
                l.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                l.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                DiagLog.add("screen cast: media paused")
                val lock = settings.castAutoLock
                val hotspot = settings.castHotspot
                if (!lock && !hotspot) {
                    l.release()
                    return@synchronized
                }
                // The helper is kept: it locks, and it knows whether the hotspot is its own.
                val end = PendingEnd(l, lock, hotspot)
                pendingEnd?.let { finish(it, carBack = false) }
                pendingEnd = end
                val later = Runnable { synchronized(stateLock) { if (pendingEnd === end) finish(end, carBack = false) } }
                end.timer = endTimer.schedule(later, END_GRACE_MS, TimeUnit.MILLISECONDS)
                DiagLog.add("screen cast: waiting ${END_GRACE_MS / 1000} s for the car to come back before " +
                    listOfNotNull("locking the phone".takeIf { lock }, "turning the hotspot off".takeIf { hotspot }).joinToString(" and "))
            }
        }
        worker.shutdown()
    }

    // ------------------------------------------------------------------ worker thread

    private fun ensureLink(): CastLink? {
        link?.takeIf { it.alive }?.let { return it }
        // The car came back within the grace time of the last session: its helper carries on, the
        // phone still unlocked and the hotspot still on.
        synchronized(stateLock) {
            val kept = pendingEnd
            if (kept != null) {
                kept.timer?.cancel(false)
                pendingEnd = null
                if (kept.link.alive) {
                    link = kept.link
                    applied = null
                    DiagLog.add("screen cast: the car is back, carrying on")
                    return kept.link
                }
            }
        }
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
        private const val LONG_PRESS_MS = 500L
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L

        /** Serialises display changes and their recovery; [active] counts the casts that own the display. */
        private val stateLock = Any()
        private var active = 0

        /** How long the car has to come back after a session ends before the phone is locked and the hotspot turned off. */
        private const val END_GRACE_MS = 30_000L

        /** A finished session's helper, kept for [END_GRACE_MS] to lock the phone and turn the hotspot off. */
        private class PendingEnd(val link: CastLink, val lock: Boolean, val hotspot: Boolean) {
            var timer: ScheduledFuture<*>? = null
        }
        /** Guarded by [stateLock]. */
        private var pendingEnd: PendingEnd? = null
        private val endTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aare-cast-end").apply { isDaemon = true } }

        /** The grace time is over (or another session's end replaces this one): do what was put off. Holds [stateLock]. */
        private fun finish(end: PendingEnd, carBack: Boolean) {
            end.timer?.cancel(false)
            if (pendingEnd === end) pendingEnd = null
            if (!carBack && end.link.alive) {
                if (end.hotspot) end.link.hotspot(false)?.let { DiagLog.add("screen cast: $it") }
                if (end.lock) {
                    end.link.lock()
                    DiagLog.add("screen cast: phone locked")
                }
            }
            end.link.release()
        }

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
