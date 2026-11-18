package io.bimmergestalt.idriveconnectaddons.speedcams.location

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import org.json.JSONObject

enum class FixSource(val label: String) { PHONE("phone GPS"), CAR("car GPS"), SIMULATED("simulated") }

/**
 * One position update
 * @param speed meters per second, null if unknown
 * @param heading degrees clockwise from north, null if unknown
 */
data class LocationFix(
    val position: LatLon,
    val speed: Double?,
    val heading: Double?,
    val accuracy: Float?,
    val time: Long,
    val source: FixSource,
)

/**
 * Parses the car's own GPS from the Car Data Service.
 * The car sends the position and the speed/heading as separate properties.
 */
object CarGpsParser {
    /** {"GPSPosition":{"latitude":51.5,"longitude":-0.1}} */
    fun parsePosition(json: String?): LatLon? {
        val obj = try { JSONObject(json ?: return null).optJSONObject("GPSPosition") } catch (e: Exception) { null } ?: return null
        val lat = obj.optDouble("latitude", Double.NaN)
        val lon = obj.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

    /**
     * {"GPSExtendedInfo":{"altitude":..,"heading":..,"speed":..}}
     * Speed is a signed 16 bit value offset by 32768, in 0.01 m/s.
     * Heading is counter-clockwise from north, so it is mirrored to the usual clockwise compass.
     * Returns speed (m/s) and heading (degrees), either may be null.
     */
    fun parseExtended(json: String?): Pair<Double?, Double?> {
        val obj = try { JSONObject(json ?: return null to null).optJSONObject("GPSExtendedInfo") } catch (e: Exception) { null }
            ?: return null to null
        val rawSpeed = obj.optDouble("speed", Double.NaN)
        val speed = if (!rawSpeed.isNaN() && rawSpeed <= -24434) (rawSpeed + 32768) * 0.01 else null
        val rawHeading = obj.optDouble("heading", Double.NaN)
        val heading = if (!rawHeading.isNaN() && rawHeading in -360.0..360.0) ((360 - rawHeading) % 360 + 360) % 360 else null
        return speed to heading
    }
}
