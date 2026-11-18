package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import android.util.Log
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import org.json.JSONObject
import java.io.File

/**
 * Speed cameras for a large area around the car, downloaded from OpenStreetMap
 * and cached on the phone so a drive works without signal once loaded
 */
class CameraStore(private val dir: File) {
    companion object {
        const val AREA_RADIUS = 35_000.0          // meters around the download point
        const val REFRESH_MARGIN = 20_000.0       // re-download when this close to the edge
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        const val RETRY_DELAY_MS = 60_000L
    }

    @Volatile var cameras: List<Camera> = emptyList()
        private set
    @Volatile var center: LatLon? = null
        private set
    @Volatile var fetchedAt: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var loading = false
        private set
    private var lastAttempt = 0L

    private val dataFile = File(dir, "cameras.json")
    private val metaFile = File(dir, "cameras.meta.json")

    fun loadCache() {
        try {
            if (!dataFile.exists() || !metaFile.exists()) return
            val meta = JSONObject(metaFile.readText())
            center = LatLon(meta.getDouble("lat"), meta.getDouble("lon"))
            fetchedAt = meta.getLong("fetchedAt")
            cameras = OverpassParser.parseCameras(dataFile.readText())
            Log.i(TAG, "Loaded ${cameras.size} cached cameras around $center")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load cached cameras", e)
        }
    }

    fun needsRefresh(pos: LatLon, now: Long = System.currentTimeMillis()): Boolean {
        if (loading || now - lastAttempt < RETRY_DELAY_MS) return false
        val c = center ?: return true
        return now - fetchedAt > MAX_AGE_MS || Geo.distance(c, pos) > AREA_RADIUS - REFRESH_MARGIN
    }

    /** Blocking download, call from a background thread */
    fun refresh(pos: LatLon) {
        loading = true
        lastAttempt = System.currentTimeMillis()
        try {
            val north = Geo.destination(pos, 0.0, AREA_RADIUS).lat
            val south = Geo.destination(pos, 180.0, AREA_RADIUS).lat
            val east = Geo.destination(pos, 90.0, AREA_RADIUS).lon
            val west = Geo.destination(pos, 270.0, AREA_RADIUS).lon
            val json = Overpass.query(OverpassParser.cameraQuery(south, west, north, east), 180_000)
            val parsed = OverpassParser.parseCameras(json)
            dataFile.writeText(json)
            fetchedAt = System.currentTimeMillis()
            center = pos
            metaFile.writeText(JSONObject().put("lat", pos.lat).put("lon", pos.lon).put("fetchedAt", fetchedAt).toString())
            cameras = parsed
            lastError = null
            Log.i(TAG, "Downloaded ${parsed.size} cameras around $pos")
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            Log.w(TAG, "Camera download failed", e)
        } finally {
            loading = false
        }
    }

    fun forceRefreshAllowed() { lastAttempt = 0 }

    fun near(pos: LatLon, radius: Double): List<Camera> {
        // a cheap box test first, then the real distance
        val dLat = radius / 111_000.0
        val dLon = radius / (111_000.0 * Math.cos(Math.toRadians(pos.lat)).coerceAtLeast(0.1))
        return cameras.filter {
            Math.abs(it.position.lat - pos.lat) <= dLat && Math.abs(it.position.lon - pos.lon) <= dLon &&
                    Geo.distance(pos, it.position) <= radius
        }
    }
}
