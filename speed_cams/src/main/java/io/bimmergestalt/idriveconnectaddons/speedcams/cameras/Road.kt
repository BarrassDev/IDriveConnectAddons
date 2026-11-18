package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon

/**
 * One OpenStreetMap way
 * @param oneway 1 = only along the node order, -1 = only against it, 0 = both ways
 */
data class Road(
    val id: Long,
    val nodeIds: LongArray,
    val points: List<LatLon>,
    val highway: String,
    val oneway: Int,
    val limit: SpeedLimit?,
    val name: String,
) {
    override fun equals(other: Any?) = other is Road && other.id == id
    override fun hashCode() = id.hashCode()
}
