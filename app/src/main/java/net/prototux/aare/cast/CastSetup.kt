package net.prototux.aare.cast

import android.content.Context
import android.os.Build
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings

/**
 * The one-time setup of screen cast mode, done with root so nothing has to be tapped through:
 * asking for root is the only prompt (the root manager's own, once), and root then grants what
 * Android would otherwise ask for one dialog at a time.
 *
 * Nothing here gives AARE more than a cast needs: the permissions are the ones for talking to the
 * head unit (notification for the connection, Bluetooth and Wi-Fi for wireless), not contacts,
 * calls, location or the microphone, which stay with the classic mode's own setup.
 */
object CastSetup {
    data class Result(val root: Boolean, val audio: Boolean)

    /** Runtime permissions a cast session uses; those this Android version doesn't have are skipped by `pm`. */
    private val PERMISSIONS = listOf(
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.ACCESS_LOCAL_NETWORK",
    )

    /** Whether all system audio can go to the car through root on this Android (the capture needs Android 12). */
    val audioSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Asks for root and, once granted, sets everything up. Blocks (the root prompt): not for the main thread. */
    fun run(context: Context, settings: Settings): Result {
        if (!RootShell.granted()) {
            settings.castRootReady = false
            return Result(root = false, audio = false)
        }
        settings.castRootReady = true
        // System audio through root: the existing car-audio capture, allowed without its own setup step.
        settings.rootAudio = true
        val pkg = context.packageName
        val script = buildString {
            PERMISSIONS.forEach { appendLine("pm grant $pkg $it >/dev/null 2>&1") }
            // Android must not put the connection to sleep in the background.
            appendLine("dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1")
            // The car's GPS as the phone's location: what choosing a "mock location app" in the Developer options does.
            appendLine("appops set $pkg android:mock_location allow >/dev/null 2>&1")
            appendLine("echo ok")
        }
        val done = RootShell.run(script)?.contains("ok") == true
        DiagLog.add("screen cast setup: root granted" + if (done) ", permissions set" else ", but the permission commands failed")
        return Result(root = true, audio = audioSupported)
    }
}
