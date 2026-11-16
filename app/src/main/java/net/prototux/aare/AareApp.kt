package net.prototux.aare

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        recordCrashes()
        logLastExit()
        // A cast cut short (the app killed, a reboot) may have left the display resized, turned or dark.
        net.prototux.aare.cast.CastController.recover(this)
    }

    /**
     * An uncaught exception goes to the connection log before the app dies, so what broke a drive
     * can be read on the phone afterwards, without adb.
     */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                DiagLog.add("CRASH on thread ${thread.name}: $error")
                error.stackTrace.take(12).forEach { DiagLog.add("    at $it") }
                var cause = error.cause
                while (cause != null) {
                    DiagLog.add("  caused by: $cause")
                    cause.stackTrace.take(8).forEach { DiagLog.add("    at $it") }
                    cause = cause.cause
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Why Android says this app's process last ended (a crash, a kill, low memory…): the deaths an
     * exception handler never sees, like a native crash or a kill by the system. Android 11+.
     */
    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
            if (last == null) {
                DiagLog.add("app started")
            } else {
                val reason = EXIT_REASONS[last.reason] ?: "reason ${last.reason}"
                val at = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(last.timestamp))
                DiagLog.add("app started; its last process ended at $at: $reason, status ${last.status}" +
                    (last.description?.let { " ($it)" } ?: ""))
            }
        }
    }

    companion object {
        lateinit var instance: AareApp
            private set

        /** ApplicationExitInfo's reasons, by number (the constants need Android 11). */
        private val EXIT_REASONS = mapOf(
            1 to "exited by itself", 2 to "killed by a signal", 3 to "low memory", 4 to "crashed (exception)",
            5 to "crashed (native)", 6 to "not responding (ANR)", 7 to "failed to start", 8 to "a permission changed",
            9 to "used too many resources", 10 to "the user asked", 11 to "the user stopped it", 12 to "something it depends on died",
            13 to "other", 14 to "frozen", 15 to "its package state changed", 16 to "the app was updated",
        )
    }
}
