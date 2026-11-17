package io.bimmergestalt.idriveconnectaddons.navmirror

import android.app.Application

class NavMirrorApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        NavMirror.init(this)
    }
}
