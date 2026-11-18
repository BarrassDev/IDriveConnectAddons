package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import android.util.Log
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon

/**
 * The drivable roads within a couple of km of the car,
 * re-downloaded as the car approaches the edge of the loaded patch
 */
class RoadStore {
    companion object {
        const val RADIUS = 1500
        const val EDGE_MARGIN = 700.0
        const val RETRY_DELAY_MS = 20_000L
    }

    @Volatile var network: RoadNetwork? = null
        private set
    @Volatile var loading = false
        private set
    @Volatile var lastError: String? = null
        private set
    private var lastAttempt = 0L

    fun needsRefresh(pos: LatLon, now: Long = System.currentTimeMillis()): Boolean {
        if (loading || now - lastAttempt < RETRY_DELAY_MS) return false
        val n = network ?: return true
        return !n.covers(pos, EDGE_MARGIN)
    }

    /** Blocking download, call from a background thread */
    fun refresh(pos: LatLon) {
        loading = true
        lastAttempt = System.currentTimeMillis()
        try {
            val json = Overpass.query(OverpassParser.roadQuery(pos.lat, pos.lon, RADIUS), 80_000)
            val roads = OverpassParser.parseRoads(json)
            network = RoadNetwork(roads, pos, RADIUS.toDouble())
            lastError = null
            Log.i(TAG, "Loaded ${roads.size} roads around $pos")
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            Log.w(TAG, "Road download failed", e)
        } finally {
            loading = false
        }
    }
}
