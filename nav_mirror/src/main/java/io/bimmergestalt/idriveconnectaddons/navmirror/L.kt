package io.bimmergestalt.idriveconnectaddons.navmirror

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

const val TAG = "NavMirror"

/** Strings shown inside the car, loaded from Android resources */
object L {
    var APP_NAME = "Nav Mirror"
    var CAR_TITLE = "Nav Mirror"
    var CAR_PHONE_NAV = "Phone navigation"
    var CAR_NOT_NAVIGATING = "Phone is not navigating"
    var CAR_NEXT = "Next"
    var CAR_ETA = "ETA"
    var CAR_DESTINATION = "Destination"
    var CAR_NO_DESTINATION = "No destination captured yet"
    var CAR_SEND = "Start car navigation to this destination"
    var CAR_SENT = "Car navigation started"
    var CAR_NO_NAVIGATE = "This car layout does not support starting navigation"
    var CAR_NAV_ACTIVE = "Car navigation active"

    fun loadResources(context: Context, locale: Locale? = null) {
        val thisContext = if (locale == null) { context } else {
            val origConf = context.resources.configuration
            val localeConf = Configuration(origConf)
            localeConf.setLocale(locale)
            context.createConfigurationContext(localeConf)
        }

        APP_NAME = context.getString(R.string.app_name)
        CAR_TITLE = thisContext.getString(R.string.CAR_TITLE)
        CAR_PHONE_NAV = thisContext.getString(R.string.CAR_PHONE_NAV)
        CAR_NOT_NAVIGATING = thisContext.getString(R.string.CAR_NOT_NAVIGATING)
        CAR_NEXT = thisContext.getString(R.string.CAR_NEXT)
        CAR_ETA = thisContext.getString(R.string.CAR_ETA)
        CAR_DESTINATION = thisContext.getString(R.string.CAR_DESTINATION)
        CAR_NO_DESTINATION = thisContext.getString(R.string.CAR_NO_DESTINATION)
        CAR_SEND = thisContext.getString(R.string.CAR_SEND)
        CAR_SENT = thisContext.getString(R.string.CAR_SENT)
        CAR_NO_NAVIGATE = thisContext.getString(R.string.CAR_NO_NAVIGATE)
        CAR_NAV_ACTIVE = thisContext.getString(R.string.CAR_NAV_ACTIVE)
    }
}
