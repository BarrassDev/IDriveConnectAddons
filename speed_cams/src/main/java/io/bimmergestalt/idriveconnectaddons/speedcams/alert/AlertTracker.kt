package io.bimmergestalt.idriveconnectaddons.speedcams.alert

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon

/**
 * Makes sure each camera is announced once per approach:
 * a camera alerts when it first comes within the alert distance ahead,
 * and is re-armed once the car has left it well behind
 */
class AlertTracker(private val rearmDistance: Double = 300.0) {
    private val alerted = HashMap<Long, LatLon>()

    /** Returns the cameras that should alert now */
    fun update(pos: LatLon, ahead: List<AheadCamera>, alertDistance: Double): List<AheadCamera> {
        val fresh = ahead.filter { it.distance <= alertDistance && it.camera.id !in alerted }
        fresh.forEach { alerted[it.camera.id] = it.camera.position }

        // forget cameras that are no longer ahead and are far enough away
        val aheadIds = ahead.mapTo(HashSet()) { it.camera.id }
        alerted.entries.removeAll { (id, camPos) ->
            id !in aheadIds && Geo.distance(pos, camPos) > alertDistance + rearmDistance
        }
        return fresh
    }

    fun reset() = alerted.clear()
}
