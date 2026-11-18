package io.bimmergestalt.idriveconnectaddons.speedcams.car

import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Candidate file formats for the car's undocumented map overlay import.
 * Nobody has documented which one the car expects, so the experiment tries each in turn
 * and keeps the first one the car reports as imported.
 */
enum class PoiFormat(val extension: String) {
    KML("kml"),
    KMZ("kmz"),
    GPX("gpx"),
    OV2("ov2"),
    CSV("csv");

    fun encode(cameras: List<Camera>): ByteArray = when (this) {
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
