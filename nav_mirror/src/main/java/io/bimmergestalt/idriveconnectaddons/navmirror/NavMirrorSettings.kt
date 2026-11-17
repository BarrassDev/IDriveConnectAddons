package io.bimmergestalt.idriveconnectaddons.navmirror

import android.content.Context
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination

/** Persisted settings and the captured destination, so a destination survives until the car connects */
class NavMirrorSettings(context: Context) {
    companion object {
        const val PREFS = "navmirror"
        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_GEOCODE_HINTS = "geocode_hints"
        const val KEY_DESTINATION = "destination"
        const val KEY_LAST_SENT = "last_sent"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoSend: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND, value).apply()

    var geocodeHints: Boolean
        get() = prefs.getBoolean(KEY_GEOCODE_HINTS, false)
        set(value) = prefs.edit().putBoolean(KEY_GEOCODE_HINTS, value).apply()

    var destination: Destination?
        get() = Destination.fromJson(prefs.getString(KEY_DESTINATION, null))
        set(value) {
            if (value == null) prefs.edit().remove(KEY_DESTINATION).apply()
            else prefs.edit().putString(KEY_DESTINATION, value.toJson()).apply()
        }

    var lastSent: Destination?
        get() = Destination.fromJson(prefs.getString(KEY_LAST_SENT, null))
        set(value) {
            if (value == null) prefs.edit().remove(KEY_LAST_SENT).apply()
            else prefs.edit().putString(KEY_LAST_SENT, value.toJson()).apply()
        }
}
