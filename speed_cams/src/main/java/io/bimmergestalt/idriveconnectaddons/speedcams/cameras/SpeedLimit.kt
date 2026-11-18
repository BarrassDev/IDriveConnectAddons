package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import kotlin.math.roundToInt

/** A posted speed limit, kept in the units it was signed in so 30 mph stays exactly 30 mph */
data class SpeedLimit(val value: Int, val mph: Boolean) {
    val kmh: Double get() = if (mph) value * 1.609344 else value.toDouble()
    val metersPerSecond: Double get() = kmh / 3.6

    fun display(showMph: Boolean): Int = when {
        showMph == mph -> value
        showMph -> (value / 1.609344).roundToInt()
        else -> (value * 1.609344).roundToInt()
    }

    companion object {
        /**
         * Parses OpenStreetMap maxspeed tags, including the UK national speed limit conventions.
         * Returns null for unknown, variable or unlimited values.
         */
        fun parse(maxspeed: String?, maxspeedType: String? = null, highway: String? = null): SpeedLimit? {
            val raw = maxspeed?.trim()?.lowercase()
            if (raw != null) {
                Regex("^(\\d+)\\s*mph$").find(raw)?.let { return SpeedLimit(it.groupValues[1].toInt(), true) }
                Regex("^(\\d+)(\\s*km/h|\\s*kmh|\\s*kph)?$").find(raw)?.let { return SpeedLimit(it.groupValues[1].toInt(), false) }
                Regex("^(\\d+)\\s*;").find(raw)?.let { return SpeedLimit(it.groupValues[1].toInt(), raw.contains("mph")) }
            }
            val type = (maxspeedType ?: raw)?.lowercase() ?: return null
            return when {
                type.contains("gb:motorway") -> SpeedLimit(70, true)
                type.contains("gb:nsl_dual") -> SpeedLimit(70, true)
                type.contains("gb:nsl_single") -> SpeedLimit(60, true)
                type.contains("gb:nsl_restricted") -> SpeedLimit(30, true)
                type == "national" || type.contains("gb:national") -> when (highway) {
                    "motorway", "motorway_link" -> SpeedLimit(70, true)
                    else -> SpeedLimit(60, true)
                }
                else -> null
            }
        }
    }
}
