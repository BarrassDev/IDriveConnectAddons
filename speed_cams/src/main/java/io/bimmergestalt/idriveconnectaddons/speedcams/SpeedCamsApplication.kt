package io.bimmergestalt.idriveconnectaddons.speedcams

import android.app.Application

class SpeedCamsApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        SpeedCams.init(this)
    }
}
