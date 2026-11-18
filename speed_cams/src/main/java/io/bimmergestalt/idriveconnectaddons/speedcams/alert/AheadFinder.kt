package io.bimmergestalt.idriveconnectaddons.speedcams.alert

import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A camera on the road ahead, [distance] meters away along the predicted path */
data class AheadCamera(val camera: Camera, val distance: Double)

/**
 * Decides which cameras are on the road ahead of the car
 */
object AheadFinder {
    /** How far beside the predicted road a camera may stand and still count */
    const val PATH_TOLERANCE = 30.0
    /** Without road data: how wide the corridor straight ahead is */
    const val CORRIDOR_HALF_WIDTH = 40.0
    const val CORRIDOR_MAX_ANGLE = 20.0

    /**
     * Cameras within [lookahead] meters along [path], which starts at the car
     */
    fun onPath(path: List<LatLon>, cameras: List<Camera>, lookahead: Double): List<AheadCamera> {
        if (path.size < 2) return emptyList()
        val origin = path.first()
        val proj = Geo.LocalProjection(origin)
        val xs = path.map { proj.x(it) }
        val ys = path.map { proj.y(it) }
        val result = ArrayList<AheadCamera>()
        for (camera in cameras) {
            if (Geo.distance(origin, camera.position) > lookahead + PATH_TOLERANCE + 50) continue
            val cx = proj.x(camera.position)
            val cy = proj.y(camera.position)
            var along = 0.0
            var bestLateral = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in 0 until path.size - 1) {
                val hit = Geo.pointToSegment(cx, cy, xs[i], ys[i], xs[i + 1], ys[i + 1])
                val segLen = Math.hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
                if (hit.distance < bestLateral) {
                    bestLateral = hit.distance
                    bestAlong = along + hit.fraction * segLen
                }
                along += segLen
            }
            // a camera level with the car (fraction 0 on the first segment) has just been passed
            if (bestLateral <= PATH_TOLERANCE && bestAlong in 1.0..lookahead) {
                result.add(AheadCamera(camera, bestAlong))
            }
        }
        return result.sortedBy { it.distance }
    }

    /**
     * Fallback when the road is unknown: cameras in a narrow corridor straight ahead
     */
    fun inCorridor(pos: LatLon, heading: Double, cameras: List<Camera>, lookahead: Double): List<AheadCamera> {
        val result = ArrayList<AheadCamera>()
        for (camera in cameras) {
            val d = Geo.distance(pos, camera.position)
            if (d > lookahead || d < 1) continue
            val diff = Geo.angleDiff(heading, Geo.bearing(pos, camera.position))
            val rad = Geo.toRad(diff)
            val along = d * cos(rad)
            val cross = abs(d * sin(rad))
            if (along > 0 && (abs(diff) <= CORRIDOR_MAX_ANGLE || cross <= CORRIDOR_HALF_WIDTH)) {
                result.add(AheadCamera(camera, along))
            }
        }
        return result.sortedBy { it.distance }
    }
}
