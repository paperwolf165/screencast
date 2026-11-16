package net.prototux.aare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms wireless listening after a reboot, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // The app's start (AareApp) has just checked that Android Auto is still off, if it is to stay off:
        // an update that enabled it again is followed by a reboot more often than not.
        ProjectionService.startIdleIfEnabled(context)
    }
}
