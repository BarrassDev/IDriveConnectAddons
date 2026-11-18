package io.bimmergestalt.idriveconnectaddons.speedcams

import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.SpeedLimit
import io.bimmergestalt.idriveconnectaddons.speedcams.car.PoiFormat
import io.bimmergestalt.idriveconnectaddons.speedcams.car.PoiIcons
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class PoiFormatTest {
    private val cameras = listOf(
        Camera(1, LatLon(51.501, -0.1246), CameraType.SPEED, SpeedLimit(30, true)),
        Camera(2, LatLon(51.6, 0.05), CameraType.RED_LIGHT, null),
    )

    @Test
    fun bmwKmzMatchesKmzHelper() {
        val icons = mapOf(CameraType.SPEED to PoiIcons(byteArrayOf(1, 2), byteArrayOf(3, 4)))
        val zip = ZipInputStream(ByteArrayInputStream(PoiFormat.BMW_KMZ.encode(cameras, icons)))
        val entries = LinkedHashMap<String, ByteArray>()
        while (true) {
            val e = zip.nextEntry ?: break
            entries[e.name] = zip.readBytes()
        }
        // only the speed camera has an icon in this test
        assertEquals(listOf("doc.kml", "normal_0.png", "highlight_0.png"), entries.keys.toList())
        assertEquals(listOf<Byte>(3, 4), entries["highlight_0.png"]!!.toList())
        val kml = String(entries["doc.kml"]!!)
        assertTrue(kml.contains("<Folder>\n<name>a4a</name>"))
        assertTrue(kml.contains("<StyleMap id=\"style_0\"><Pair><key>normal</key><styleUrl>#normal_0</styleUrl></Pair>"))
        assertTrue(kml.contains("<Placemark id=\"id0\"><name>Speed camera 30mph</name><address></address><phoneNumber></phoneNumber><styleUrl>#style_0</styleUrl><Point><coordinates>-0.1246,51.501</coordinates>"))
        assertTrue(kml.contains("<Placemark id=\"id1\"><name>Red light camera</name><address></address><phoneNumber></phoneNumber><Point>"))
    }

    @Test
    fun kml() {
        val kml = String(PoiFormat.KML.encode(cameras))
        assertTrue(kml.contains("<coordinates>-0.124600,51.501000,0</coordinates>"))
        assertTrue(kml.contains("<name>Speed camera 30mph</name>"))
        assertEquals(2, Regex("<Placemark>").findAll(kml).count())
    }

    @Test
    fun kmzHoldsTheKml() {
        val zip = ZipInputStream(ByteArrayInputStream(PoiFormat.KMZ.encode(cameras)))
        assertEquals("doc.kml", zip.nextEntry!!.name)
        assertEquals(PoiFormat.kml(cameras), String(zip.readBytes()))
    }

    @Test
    fun gpx() {
        val gpx = String(PoiFormat.GPX.encode(cameras))
        assertTrue(gpx.contains("<wpt lat=\"51.501000\" lon=\"-0.124600\"><name>Speed camera 30mph</name>"))
    }

    @Test
    fun ov2Records() {
        val data = ByteBuffer.wrap(PoiFormat.OV2.encode(cameras)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, data.get().toInt())
        val length = data.getInt()
        assertEquals(1 + 4 + 4 + 4 + "Speed camera 30mph".length + 1, length)
        assertEquals(-12460, data.getInt())
        assertEquals(5150100, data.getInt())
        data.position(length)
        assertEquals(2, data.get().toInt())
    }
}
