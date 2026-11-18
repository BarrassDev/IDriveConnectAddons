package io.bimmergestalt.idriveconnectaddons.speedcams

import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.OverpassParser
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.SpeedLimit
import io.bimmergestalt.idriveconnectaddons.speedcams.location.CarGpsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    @Test
    fun speedLimits() {
        assertEquals(SpeedLimit(30, true), SpeedLimit.parse("30 mph"))
        assertEquals(SpeedLimit(50, false), SpeedLimit.parse("50"))
        assertEquals(SpeedLimit(80, false), SpeedLimit.parse("80 km/h"))
        assertEquals(SpeedLimit(60, true), SpeedLimit.parse("national", "GB:nsl_single", "primary"))
        assertEquals(SpeedLimit(70, true), SpeedLimit.parse("national", "GB:nsl_dual", "trunk"))
        assertEquals(SpeedLimit(70, true), SpeedLimit.parse("national", null, "motorway"))
        assertEquals(SpeedLimit(60, true), SpeedLimit.parse(null, "GB:nsl_single"))
        assertNull(SpeedLimit.parse("signals"))
        assertNull(SpeedLimit.parse(null))
        assertEquals(30, SpeedLimit(30, true).display(true))
        assertEquals(48, SpeedLimit(30, true).display(false))
        assertEquals(31, SpeedLimit(50, false).display(true))
    }

    @Test
    fun cameras() {
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":51.5,"lon":-0.1,"tags":{"highway":"speed_camera","maxspeed":"30 mph"}},
          {"type":"node","id":2,"lat":51.6,"lon":-0.2,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":3,"lat":51.7,"lon":-0.3},
          {"type":"node","id":4,"lat":51.8,"lon":-0.4},
          {"type":"node","id":5,"lat":51.9,"lon":-0.5},
          {"type":"relation","id":10,"tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"40 mph"},
            "members":[{"type":"node","ref":2,"role":"device"},{"type":"node","ref":3,"role":"from"}]},
          {"type":"relation","id":11,"tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"50 mph"},
            "members":[{"type":"node","ref":4,"role":"from"},{"type":"node","ref":5,"role":"to"}]},
          {"type":"relation","id":12,"tags":{"type":"enforcement","enforcement":"check"},
            "members":[{"type":"node","ref":5,"role":"device"}]}
        ]}
        """.trimIndent()
        val cameras = OverpassParser.parseCameras(json).associateBy { it.id }
        assertEquals(setOf(1L, 2L, 4L), cameras.keys)
        assertEquals(SpeedLimit(30, true), cameras[1]!!.limit)
        // the enforcement relation fills in the missing limit
        assertEquals(SpeedLimit(40, true), cameras[2]!!.limit)
        assertEquals(CameraType.AVERAGE, cameras[4]!!.type)
        assertEquals(SpeedLimit(50, true), cameras[4]!!.limit)
    }

    @Test
    fun roads() {
        val json = """
        {"elements":[
          {"type":"way","id":7,"geometry":[{"lat":51.0,"lon":0.0},{"lat":51.001,"lon":0.0}],
           "tags":{"highway":"motorway","maxspeed":"70 mph","ref":"M1"}},
          {"type":"way","id":8,"geometry":[{"lat":51.001,"lon":0.0},{"lat":51.002,"lon":0.0}],
           "tags":{"highway":"residential","oneway":"-1","name":"High Street"}}
        ]}
        """.trimIndent()
        val roads = OverpassParser.parseRoads(json)
        assertEquals(2, roads.size)
        assertEquals(1, roads[0].oneway)
        assertEquals("M1", roads[0].name)
        assertEquals(SpeedLimit(70, true), roads[0].limit)
        assertEquals(-1, roads[1].oneway)
        assertEquals("High Street", roads[1].name)
        assertNull(roads[1].limit)
        // the shared coordinate becomes the same node, so the roads connect
        assertEquals(roads[0].nodeIds.last(), roads[1].nodeIds.first())
    }

    @Test
    fun carGps() {
        val pos = CarGpsParser.parsePosition("""{"GPSPosition":{"latitude":51.5012,"longitude":-0.1245}}""")!!
        assertEquals(51.5012, pos.lat, 1e-9)
        assertNull(CarGpsParser.parsePosition("""{"GPSPosition":{"latitude":0,"longitude":0}}"""))
        // 13.41 m/s is 30 mph
        val (speed, heading) = CarGpsParser.parseExtended("""{"GPSExtendedInfo":{"speed":${-32768 + 1341},"heading":90,"altitude":40}}""")
        assertEquals(13.41, speed!!, 1e-6)
        assertEquals(270.0, heading!!, 1e-6)
    }
}
