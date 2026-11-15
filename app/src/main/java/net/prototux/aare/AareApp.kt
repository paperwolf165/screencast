package net.prototux.aare

import android.app.Application

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        // A cast cut short (the app killed, a reboot) may have left the display resized, turned or dark.
        net.prototux.aare.cast.CastController.recover(this)
    }

    companion object {
        lateinit var instance: AareApp
            private set
    }
}
