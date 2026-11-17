package io.bimmergestalt.idriveconnectaddons.navmirror.model

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * A navigation destination captured from the phone
 * Only latitude/longitude are required, the car does its own routing to this point
 */
data class Destination(
    val latitude: Double,
    val longitude: Double,
    val label: String = "",
    val street: String = "",
    val houseNumber: String = "",
    val postalCode: String = "",
    val locality: String = "",
    val countryCode: String = "",
) {
    companion object {
        fun fromJson(json: String?): Destination? {
            json ?: return null
            return try {
                val obj = JSONObject(json)
                Destination(
                    obj.getDouble("latitude"), obj.getDouble("longitude"),
                    obj.optString("label"), obj.optString("street"), obj.optString("houseNumber"),
                    obj.optString("postalCode"), obj.optString("locality"), obj.optString("countryCode")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** A human-readable name for this destination */
    val displayName: String
        get() {
            if (label.isNotBlank()) return label
            val address = listOf("$houseNumber $street".trim(), locality, postalCode).filter { it.isNotBlank() }.joinToString(", ")
            if (address.isNotBlank()) return address
            return String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
        }

    /** Whether the two destinations point to effectively the same place */
    fun isSamePlace(other: Destination?): Boolean {
        other ?: return false
        return abs(latitude - other.latitude) < 0.0001 && abs(longitude - other.longitude) < 0.0001
    }

    /**
     * Formats this destination for the car's `navigate` linkAction
     * [lastName];[firstName];[street];[houseNumber];[zipCode];[city];[country];[latitude];[longitude];[poiName]
     * Latitude and longitude are scaled to a 32bit integer range
     * This matches the format used by AAIdrive's NavigationTriggerApp
     */
    fun toRhmiNavigation(): String {
        val lat = (latitude / 360 * Int.MAX_VALUE * 2).toBigDecimal()
        val lng = (longitude / 360 * Int.MAX_VALUE * 2).toBigDecimal()
        fun clean(s: String) = s.replace(';', ' ')
        return ";;${clean(street)};${clean(houseNumber)};${clean(postalCode)};${clean(locality)};${clean(countryCode)};$lat;$lng;${clean(label)}"
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("label", label)
            put("street", street)
            put("houseNumber", houseNumber)
            put("postalCode", postalCode)
            put("locality", locality)
            put("countryCode", countryCode)
        }.toString()
    }
}
