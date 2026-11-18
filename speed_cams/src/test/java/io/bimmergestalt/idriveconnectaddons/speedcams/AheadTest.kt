package io.bimmergestalt.idriveconnectaddons.speedcams

import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AheadFinder
import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AlertTracker
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Road
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.RoadNetwork
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.METERS_PER_MILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AheadTest {
    private val origin = LatLon(51.5, -0.12)
    private fun at(north: Double, east: Double): LatLon =
        Geo.destination(Geo.destination(origin, 0.0, north), 90.0, east)
    private fun camera(id: Long, p: LatLon) = Camera(id, p, CameraType.SPEED, null)

    /**
     *   A road running north from the origin for 1 km, which then forks:
     *   a gentle bend to the north-east continues ("Main Road"), a sharp left goes west.
     *   A side road runs parallel 150 m to the east.
     */
    private val main1 = Road(1, longArrayOf(1, 2, 3), listOf(at(0.0, 0.0), at(500.0, 0.0), at(1000.0, 0.0)), "primary", 0, null, "Main Road")
    private val main2 = Road(2, longArrayOf(3, 4), listOf(at(1000.0, 0.0), at(1400.0, 150.0)), "primary", 0, null, "Main Road")
    private val left = Road(3, longArrayOf(3, 5), listOf(at(1000.0, 0.0), at(1050.0, -400.0)), "residential", 0, null, "Side Lane")
    private val parallel = Road(4, longArrayOf(6, 7), listOf(at(0.0, 150.0), at(1500.0, 150.0)), "residential", 0, null, "Back Street")
    private val network = RoadNetwork(listOf(main1, main2, left, parallel), origin, 3000.0)

    @Test
    fun matchesTheRoadInTheDrivingDirection() {
        val match = network.match(at(200.0, 5.0), 2.0)!!
        assertEquals(1L, match.road.id)
        assertEquals(1, match.direction)
        val back = network.match(at(200.0, 5.0), 182.0)!!
        assertEquals(-1, back.direction)
        // a heading across the road matches nothing
        assertNull(network.match(at(200.0, 5.0), 90.0))
    }

    @Test
    fun oneWayRoadsAreOnlyMatchedTheLegalWay() {
        val oneway = RoadNetwork(listOf(main1.copy(oneway = 1)), origin, 3000.0)
        assertNotNull(oneway.match(at(200.0, 0.0), 0.0))
        assertNull(oneway.match(at(200.0, 0.0), 180.0))
    }

    @Test
    fun walksAheadThroughTheStraightestJunction() {
        val match = network.match(at(800.0, 0.0), 0.0)!!
        val path = network.walkAhead(match, 500.0)
        // past the fork at 1000 m the path follows Main Road to the north-east, not the sharp left
        val end = path.last()
        assertTrue("path should continue east of the main road, ended at $end", Geo.distance(end, at(1400.0, 150.0)) < 1.0)
    }

    @Test
    fun findsCamerasOnThePredictedRoadOnly() {
        val pos = at(700.0, 0.0)
        val match = network.match(pos, 0.0)!!
        val alertMeters = 0.3 * METERS_PER_MILE
        val path = network.walkAhead(match, alertMeters + 150)
        val onMain = camera(1, at(1200.0, 80.0))           // on the bend, ~520 m along the road
        val onMainSoon = camera(2, at(900.0, 12.0))        // 12 m beside the road, 200 m ahead
        val onSideLane = camera(3, at(1020.0, -200.0))      // down the sharp left turn
        val onParallel = camera(4, at(900.0, 150.0))        // the parallel street
        val behind = camera(5, at(500.0, 5.0))
        val ahead = AheadFinder.onPath(path, listOf(onMain, onMainSoon, onSideLane, onParallel, behind), alertMeters + 150)
        assertEquals(listOf(2L, 1L), ahead.map { it.camera.id })
        assertEquals(200.0, ahead[0].distance, 5.0)
    }

    @Test
    fun corridorFallback() {
        val cams = listOf(camera(1, at(400.0, 10.0)), camera(2, at(400.0, 200.0)), camera(3, at(-100.0, 0.0)))
        val ahead = AheadFinder.inCorridor(origin, 0.0, cams, 0.3 * METERS_PER_MILE)
        assertEquals(listOf(1L), ahead.map { it.camera.id })
    }

    @Test
    fun alertsOncePerApproach() {
        val tracker = AlertTracker()
        val alertMeters = 0.3 * METERS_PER_MILE
        val cam = camera(1, at(1000.0, 0.0))
        fun step(north: Double): Int {
            val pos = at(north, 0.0)
            val ahead = AheadFinder.inCorridor(pos, 0.0, listOf(cam), alertMeters + 150)
            return tracker.update(pos, ahead, alertMeters).size
        }
        assertEquals(0, step(300.0))        // 700 m away, too far
        assertEquals(1, step(530.0))        // 470 m, inside 0.3 mi
        assertEquals(0, step(700.0))        // still approaching, already announced
        assertEquals(0, step(1100.0))       // passed
        assertEquals(0, step(1900.0))       // far beyond, re-armed
        assertEquals(1, step(600.0))        // a second approach alerts again
    }
}
