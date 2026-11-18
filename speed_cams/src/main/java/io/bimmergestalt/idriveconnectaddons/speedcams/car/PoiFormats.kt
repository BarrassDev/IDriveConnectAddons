package io.bimmergestalt.idriveconnectaddons.speedcams.car

import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Normal and highlighted PNG icons for one camera type */
class PoiIcons(val normal: ByteArray, val highlight: ByteArray)

/**
 * Candidate file formats for the car's map overlay import, tried in this order.
 *
 * [BMW_KMZ] reproduces com.bmwgroup.connected.util.kmz.KmzHelper from the BMW Connected Classic 1.8 SDK,
 * which ships next to the map_* overlay calls: a zip of doc.kml plus normal_N.png / highlight_N.png icons,
 * a Folder named "a4a", a Style and StyleMap per POI, and Placemarks with ids "id0", "id1"...
 * The others are generic fallbacks in case this head unit wants something else.
 */
enum class PoiFormat(val extension: String) {
    BMW_KMZ("kmz"),
    KML("kml"),
    KMZ("kmz"),
    GPX("gpx"),
    OV2("ov2"),
    CSV("csv");

    fun encode(cameras: List<Camera>, icons: Map<CameraType, PoiIcons> = emptyMap()): ByteArray = when (this) {
        BMW_KMZ -> bmwKmz(cameras, icons)
        KML -> kml(cameras).toByteArray()
        KMZ -> zip("doc.kml", kml(cameras).toByteArray())
        GPX -> gpx(cameras).toByteArray()
        OV2 -> ov2(cameras)
        CSV -> csv(cameras).toByteArray()
    }

    companion object {
        fun label(camera: Camera): String {
            val limit = camera.limit?.let { " ${it.value}${if (it.mph) "mph" else ""}" } ?: ""
            return camera.type.label + limit
        }

        /** The KMZ layout written by BMW's KmzHelper */
        fun bmwKml(cameras: List<Camera>, icons: Map<CameraType, PoiIcons>): String = buildString {
            append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
            append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\" ")
            append("xmlns:kml=\"http://www.opengis.net/kml/2.2\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n")
            append("<Folder>\n<name>a4a</name>\n")
            cameras.forEachIndexed { i, camera ->
                val icon = icons[camera.type]
                if (icon != null) {
                    append("<Style id=\"normal_$i\"><IconStyle><Icon><href>normal_$i.png</href></Icon></IconStyle></Style>\n")
                    append("<Style id=\"highlight_$i\"><IconStyle><Icon><href>highlight_$i.png</href></Icon></IconStyle></Style>\n")
                    append("<StyleMap id=\"style_$i\">")
                    append("<Pair><key>normal</key><styleUrl>#normal_$i</styleUrl></Pair>")
                    append("<Pair><key>highlight</key><styleUrl>#highlight_$i</styleUrl></Pair>")
                    append("</StyleMap>\n")
                }
                append("<Placemark id=\"id$i\">")
                append("<name>").append(escape(label(camera))).append("</name>")
                append("<address></address><phoneNumber></phoneNumber>")
                if (icon != null) append("<styleUrl>#style_$i</styleUrl>")
                // BMW writes the two doubles straight from the Poi fields, longitude first as in standard KML
                append("<Point><coordinates>").append(camera.position.lon).append(',').append(camera.position.lat)
                append("</coordinates></Point></Placemark>\n")
            }
            append("</Folder>\n</kml>")
        }

        fun bmwKmz(cameras: List<Camera>, icons: Map<CameraType, PoiIcons>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("doc.kml"))
                zip.write(bmwKml(cameras, icons).toByteArray())
                cameras.forEachIndexed { i, camera ->
                    val icon = icons[camera.type] ?: return@forEachIndexed
                    zip.putNextEntry(ZipEntry("normal_$i.png"))
                    zip.write(icon.normal)
                    zip.putNextEntry(ZipEntry("highlight_$i.png"))
                    zip.write(icon.highlight)
                }
                zip.closeEntry()
            }
            return out.toByteArray()
        }

        private fun coord(v: Double) = String.format(Locale.ROOT, "%.6f", v)

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun kml(cameras: List<Camera>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Speed cameras</name>\n")
            cameras.forEach {
                append("<Placemark><name>").append(escape(label(it))).append("</name><Point><coordinates>")
                append(coord(it.position.lon)).append(',').append(coord(it.position.lat)).append(",0</coordinates></Point></Placemark>\n")
            }
            append("</Document></kml>\n")
        }

        fun gpx(cameras: List<Camera>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"SpeedCams\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            cameras.forEach {
                append("<wpt lat=\"").append(coord(it.position.lat)).append("\" lon=\"").append(coord(it.position.lon)).append("\">")
                append("<name>").append(escape(label(it))).append("</name><sym>Speed camera</sym></wpt>\n")
            }
            append("</gpx>\n")
        }

        fun csv(cameras: List<Camera>): String = buildString {
            cameras.forEach {
                append(coord(it.position.lon)).append(',').append(coord(it.position.lat)).append(",\"")
                append(label(it).replace("\"", "")).append("\"\n")
            }
        }

        /** TomTom OV2: type 2 records, little endian, coordinates in 1e-5 degrees */
        fun ov2(cameras: List<Camera>): ByteArray {
            val out = ByteArrayOutputStream()
            cameras.forEach {
                val name = label(it).toByteArray(Charsets.ISO_8859_1)
                val length = 1 + 4 + 4 + 4 + name.size + 1
                val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                buf.put(2)
                buf.putInt(length)
                buf.putInt(Math.round(it.position.lon * 100_000).toInt())
                buf.putInt(Math.round(it.position.lat * 100_000).toInt())
                buf.put(name)
                buf.put(0)
                out.write(buf.array())
            }
            return out.toByteArray()
        }

        private fun zip(entry: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(data)
                zip.closeEntry()
            }
            return out.toByteArray()
        }
    }
}
