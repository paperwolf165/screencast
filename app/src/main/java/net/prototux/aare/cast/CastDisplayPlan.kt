package net.prototux.aare.cast

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What screen cast mode may change on the phone's display while casting (from the app's settings). */
data class CastOptions(
    /** Resize the phone's display to the head unit's shape. */
    val resize: Boolean,
    /** [CastDisplayPlan.RES_HEAD_UNIT], [CastDisplayPlan.RES_DEVICE] or [CastDisplayPlan.RES_CUSTOM]. */
    val resolution: String,
    val customWidth: Int,
    val customHeight: Int,
    /** Display density while casting; 0 keeps the size of things as on the phone. */
    val dpi: Int,
    /** Hold the display in the head unit's orientation (landscape, for nearly every car). */
    val lockOrientation: Boolean,
    /** Brightness 0 while casting. */
    val blank: Boolean,
    /**
     * The size of everything on screen, in percent of what the phone shows (100: as on the phone).
     * Below 100 the density drops under anything Android's own Display size setting offers, so
     * more fits on the car screen. Not used when [dpi] is set.
     */
    val uiScale: Int = 100,
    /** Text size in percent (Android's font scale); 100 leaves the phone's own setting alone. */
    val fontScale: Int = 100,
) {
    val changesDisplay: Boolean get() = resize || dpi > 0 || lockOrientation || blank || uiScale != 100 || fontScale != 100
}

/** The phone display's state before casting, as [CastDisplayPlan.PROBE] reports it. */
data class DeviceState(
    /** The panel's own size, in its natural (portrait, on a phone) orientation. */
    val physicalWidth: Int,
    val physicalHeight: Int,
    /** A `wm size` override already in place, or null. */
    val overrideSize: Pair<Int, Int>?,
    val physicalDensity: Int,
    /** A `wm density` override already in place, or null. */
    val overrideDensity: Int?,
    val brightness: Int?,
    val brightnessMode: Int?,
    val autoRotate: Int?,
    val userRotation: Int?,
    val ignoreOrientationRequest: Boolean,
    /** The phone's own font scale, as the setting holds it ("1.0", "1.15"); null when it was never changed. */
    val fontScale: String? = null,
)

/** Root shell scripts: [apply] when the cast starts, [restore] to put everything back. */
data class CastPlan(val apply: String, val restore: String, val summary: String)

/**
 * Works out the `wm` / `settings` commands that fit the phone's display to a head unit, and the
 * ones that undo them. No Android code: the scripts run in the root helper, this only writes them.
 */
object CastDisplayPlan {
    /** The phone's display takes the head unit's pixels exactly: the stream is 1:1. */
    const val RES_HEAD_UNIT = "head_unit"
    /** The head unit's shape, as large as the phone's panel allows: sharper source, scaled down for the car. */
    const val RES_DEVICE = "device"
    /** [CastOptions.customWidth] x [CastOptions.customHeight]. */
    const val RES_CUSTOM = "custom"

    /** Prints the display state for [parse]. */
    val PROBE = """
        wm size
        wm density
        echo "brightness=${'$'}(settings get system screen_brightness)"
        echo "brightness_mode=${'$'}(settings get system screen_brightness_mode)"
        echo "auto_rotate=${'$'}(settings get system accelerometer_rotation)"
        echo "user_rotation=${'$'}(settings get system user_rotation)"
        echo "ignore_orientation=${'$'}(cmd window get-ignore-orientation-request 2>/dev/null)"
        echo "font_scale=${'$'}(settings get system font_scale)"
    """.trimIndent()

    private val PHYSICAL_SIZE = Regex("""Physical size:\s*(\d+)x(\d+)""")
    private val OVERRIDE_SIZE = Regex("""Override size:\s*(\d+)x(\d+)""")
    private val PHYSICAL_DENSITY = Regex("""Physical density:\s*(\d+)""")
    private val OVERRIDE_DENSITY = Regex("""Override density:\s*(\d+)""")

    /** Null when the output has no panel size or density (the probe didn't run as expected). */
    fun parse(output: String): DeviceState? {
        val size = PHYSICAL_SIZE.find(output) ?: return null
        val density = PHYSICAL_DENSITY.find(output) ?: return null
        fun setting(key: String) = Regex("""^$key=(-?\d+)\s*$""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)?.toIntOrNull()
        return DeviceState(
            physicalWidth = size.groupValues[1].toInt(),
            physicalHeight = size.groupValues[2].toInt(),
            overrideSize = OVERRIDE_SIZE.find(output)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() },
            physicalDensity = density.groupValues[1].toInt(),
            overrideDensity = OVERRIDE_DENSITY.find(output)?.groupValues?.get(1)?.toIntOrNull(),
            brightness = setting("brightness"),
            brightnessMode = setting("brightness_mode"),
            autoRotate = setting("auto_rotate"),
            userRotation = setting("user_rotation"),
            ignoreOrientationRequest = Regex("""^ignore_orientation=.*\btrue\b""", RegexOption.MULTILINE).containsMatchIn(output),
            fontScale = Regex("""^font_scale=(\d+(?:\.\d+)?)\s*$""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1),
        )
    }

    /**
     * The display size to cast at, in the head unit's orientation ([targetWidth] x [targetHeight]
     * is the part of the car screen the cast fills). Null when the display isn't resized.
     */
    fun castSize(state: DeviceState, options: CastOptions, targetWidth: Int, targetHeight: Int): Pair<Int, Int>? {
        if (!options.resize || targetWidth <= 0 || targetHeight <= 0) return null
        return when (options.resolution) {
            RES_CUSTOM -> if (options.customWidth > 0 && options.customHeight > 0) options.customWidth to options.customHeight
                else targetWidth to targetHeight
            RES_DEVICE -> {
                // The panel, turned the way the head unit is, and the largest box of its shape that fits in it.
                val long = max(state.physicalWidth, state.physicalHeight)
                val short = min(state.physicalWidth, state.physicalHeight)
                val (panelW, panelH) = if (targetWidth >= targetHeight) long to short else short to long
                val scale = min(panelW.toFloat() / targetWidth, panelH.toFloat() / targetHeight)
                even(targetWidth * scale) to even(targetHeight * scale)
            }
            else -> targetWidth to targetHeight
        }
    }

    fun plan(state: DeviceState, options: CastOptions, targetWidth: Int, targetHeight: Int): CastPlan {
        val apply = ArrayList<String>()
        val restore = ArrayList<String>()
        val summary = ArrayList<String>()
        val landscape = targetWidth >= targetHeight

        // Restores run in this order too: light first, so a phone left dark is the first thing fixed.
        if (options.blank) {
            apply += "settings put system screen_brightness_mode 0"
            apply += "settings put system screen_brightness 0"
            apply += "cmd display set-brightness 0 $QUIET"
            restore += "settings put system screen_brightness ${state.brightness ?: DEFAULT_BRIGHTNESS}"
            restore += "settings put system screen_brightness_mode ${state.brightnessMode ?: 0}"
            summary += "brightness 0"
        }

        if (options.lockOrientation) {
            apply += "settings put system accelerometer_rotation 0"
            apply += "settings put system user_rotation ${if (landscape) 1 else 0}"
            // Apps that ask for portrait neither turn the display back nor get letterboxed.
            // (Android 10 named the first one differently.)
            apply += "cmd window fixed-to-user-rotation enabled $QUIET || cmd window set-fix-to-user-rotation enabled $QUIET"
            apply += "cmd window set-ignore-orientation-request true $QUIET"
            restore += "cmd window fixed-to-user-rotation default $QUIET || cmd window set-fix-to-user-rotation disabled $QUIET"
            restore += "cmd window set-ignore-orientation-request ${state.ignoreOrientationRequest} $QUIET"
            restore += "settings put system user_rotation ${state.userRotation ?: 0}"
            restore += "settings put system accelerometer_rotation ${state.autoRotate ?: 1}"
            summary += if (landscape) "landscape" else "portrait"
        }

        val size = castSize(state, options, targetWidth, targetHeight)
        if (size != null) {
            // `wm size` is given in the display's natural orientation. With the rotation held, that is
            // the phone's portrait and the rotation turns it; without, the display itself takes the
            // head unit's shape.
            val (w, h) = if (options.lockOrientation) min(size.first, size.second) to max(size.first, size.second) else size
            apply += "wm size ${w}x$h"
            restore += state.overrideSize?.let { "wm size ${it.first}x${it.second}" } ?: "wm size reset"
            summary += "${size.first}x${size.second}"
        }

        val scale = options.uiScale.coerceIn(MIN_SCALE, MAX_SCALE) / 100f
        val dpi = when {
            options.dpi > 0 -> options.dpi
            // Things keep the size they have on the phone (the density follows the short side),
            // times the size asked for.
            size != null -> (state.physicalDensity.toFloat() * min(size.first, size.second) /
                min(state.physicalWidth, state.physicalHeight) * scale).roundToInt()
            options.uiScale != 100 -> ((state.overrideDensity ?: state.physicalDensity) * scale).roundToInt()
            else -> null
        }?.coerceIn(MIN_DPI, MAX_DPI)
        if (dpi != null) {
            apply += "wm density $dpi"
            restore += state.overrideDensity?.let { "wm density $it" } ?: "wm density reset"
            summary += "$dpi dpi"
        }

        if (options.fontScale != 100) {
            val font = options.fontScale.coerceIn(MIN_SCALE, MAX_SCALE)
            apply += "settings put system font_scale ${font / 100}.${(font % 100).toString().padStart(2, '0')}"
            restore += "settings put system font_scale ${state.fontScale ?: "1.0"}"
            summary += "text $font%"
        }

        if (apply.isEmpty()) return CastPlan("", "", "phone display unchanged")
        // The mirror shows nothing of a sleeping phone.
        apply.add(0, "input keyevent KEYCODE_WAKEUP")
        return CastPlan(apply.joinToString("\n"), restore.joinToString("\n"), summary.joinToString(", "))
    }

    private fun even(v: Float): Int = (v.toInt() / 2 * 2).coerceAtLeast(2)

    private const val QUIET = ">/dev/null 2>&1"
    private const val DEFAULT_BRIGHTNESS = 128
    private const val MIN_DPI = 72
    private const val MAX_DPI = 960
    private const val MIN_SCALE = 40
    private const val MAX_SCALE = 200
}
