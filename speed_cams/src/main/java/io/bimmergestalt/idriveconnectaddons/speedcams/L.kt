package io.bimmergestalt.idriveconnectaddons.speedcams

import android.content.Context

const val TAG = "SpeedCams"

/** Strings shown inside the car, loaded from Android resources */
object L {
    var CAR_TITLE = "Speed Cams"

    fun loadResources(context: Context) {
        CAR_TITLE = context.getString(R.string.CAR_TITLE)
    }
}
