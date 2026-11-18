package io.bimmergestalt.idriveconnectaddons.speedcams.map

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/** Rough solar elevation, good enough to pick a day or night map */
object Sun {
    fun elevation(pos: LatLon, timeMs: Long): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val hours = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val declination = -23.44 * cos(2 * PI / 365 * (day + 10)) * PI / 180
        val solarTime = hours + pos.lon / 15.0
        val hourAngle = (solarTime - 12) * 15 * PI / 180
        val lat = pos.lat * PI / 180
        return asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)) * 180 / PI
    }

    fun isNight(pos: LatLon, timeMs: Long = System.currentTimeMillis()) = elevation(pos, timeMs) < -3.0
}
