package io.bimmergestalt.idriveconnectaddons.speedcams

import android.content.Context
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.METERS_PER_MILE

enum class MapStyle { AUTO, LIGHT, DARK }

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Alert distance in miles: 0.2, 0.3 or 0.5 */
    var alertMiles: Float
        get() = prefs.getFloat("alertMiles", 0.3f)
        set(value) = prefs.edit().putFloat("alertMiles", value).apply()
    val alertMeters: Double get() = alertMiles * METERS_PER_MILE

    var voice: Boolean
        get() = prefs.getBoolean("voice", true)
        set(value) = prefs.edit().putBoolean("voice", value).apply()

    var headingUp: Boolean
        get() = prefs.getBoolean("headingUp", true)
        set(value) = prefs.edit().putBoolean("headingUp", value).apply()

    var showKmh: Boolean
        get() = prefs.getBoolean("showKmh", false)
        set(value) = prefs.edit().putBoolean("showKmh", value).apply()
    val showMph: Boolean get() = !showKmh

    /** Experimental: send nearby cameras to the car's own navigation map */
    var carMapOverlay: Boolean
        get() = prefs.getBoolean("carMapOverlay", false)
        set(value) = prefs.edit().putBoolean("carMapOverlay", value).apply()

    var mapStyle: MapStyle
        get() = try { MapStyle.valueOf(prefs.getString("mapStyle", MapStyle.AUTO.name)!!) } catch (e: Exception) { MapStyle.AUTO }
        set(value) = prefs.edit().putString("mapStyle", value.name).apply()
}
