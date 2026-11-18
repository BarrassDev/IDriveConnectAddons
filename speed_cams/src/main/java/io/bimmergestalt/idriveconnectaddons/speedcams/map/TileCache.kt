package io.bimmergestalt.idriveconnectaddons.speedcams.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Overpass
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Raster map tiles from the OpenStreetMap standard tile server,
 * kept in memory and on disk so repeated drives need little data
 */
class TileCache(private val cacheDir: File, private val onTileLoaded: () -> Unit) {
    companion object {
        const val TILE_SIZE = 256
        const val DISK_MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private val SUBDOMAINS = listOf("a", "b", "c")
    }

    // OpenStreetMap standard tiles need no API key. There is no dark variant,
    // so the renderer darkens tiles in code for night mode, and both styles share one cache entry.
    enum class Style { LIGHT, DARK }

    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failedAt = Collections.synchronizedMap(HashMap<String, Long>())
    private val executor = Executors.newFixedThreadPool(4)

    // key does not depend on style: the same OSM tile serves light and dark
    private fun key(style: Style, z: Int, x: Int, y: Int) = "osm/$z/$x/$y"

    /** Returns the tile if it is ready, otherwise starts loading it and returns null */
    fun get(style: Style, z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val wx = ((x % max) + max) % max
        val key = key(style, z, wx, y)
        memory.get(key)?.let { return it }
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < 30_000) return null
        if (pending.add(key)) {
            executor.execute { load(style, z, wx, y, key) }
        }
        return null
    }

    /** A cached lower zoom tile covering this one, for drawing while the real one loads */
    fun getParent(style: Style, z: Int, x: Int, y: Int, levels: Int): Bitmap? {
        if (z - levels < 0) return null
        val max = 1 shl z
        val wx = ((x % max) + max) % max
        return memory.get(key(style, z - levels, wx shr levels, y shr levels))
    }

    private fun load(style: Style, z: Int, x: Int, y: Int, key: String) {
        try {
            val file = File(cacheDir, "tiles/$key.png")
            var bitmap: Bitmap? = null
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < DISK_MAX_AGE_MS) {
                bitmap = BitmapFactory.decodeFile(file.absolutePath)
            }
            if (bitmap == null) {
                val sub = SUBDOMAINS[(x + y) % SUBDOMAINS.size]
                val url = URL("https://$sub.tile.openstreetmap.org/$z/$x/$y.png")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", Overpass.USER_AGENT)
                try {
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            if (bitmap != null) {
                memory.put(key, bitmap)
                failedAt.remove(key)
                onTileLoaded()
            } else {
                failedAt[key] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            failedAt[key] = System.currentTimeMillis()
            Log.d(TAG, "Tile $key failed: $e")
        } finally {
            pending.remove(key)
        }
    }
}
