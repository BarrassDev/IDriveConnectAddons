package io.bimmergestalt.idriveconnectaddons.speedcams.car

import android.util.Log
import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import io.bimmergestalt.idriveconnectaddons.speedcams.SpeedCams
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.METERS_PER_MILE
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL: puts the nearby cameras onto the car's own navigation map
 * through the undocumented map overlay service (map_create / map_initializeImport / map_showOverlay).
 *
 * The import format is unknown, so the first run tries each [PoiFormat] with the cameras around the car
 * and keeps the first one the car reports as imported. After that, the cameras within
 * [LOAD_RADIUS] are re-imported whenever the car has moved [RELOAD_DISTANCE].
 *
 * Every step is written to the log (tag SpeedCams) and to files/map_overlay.txt.
 */
class MapOverlayExperiment(private val carConnection: BMWRemotingServer, private val logFile: File) {
    companion object {
        const val LOAD_RADIUS = 3 * METERS_PER_MILE
        const val RELOAD_DISTANCE = 1 * METERS_PER_MILE
        const val MAX_POIS = 60
        const val CHUNK = 8192
        const val EVENT_TIMEOUT_S = 8L
        const val OVERLAY_ID: Short = 1
    }

    /** Camera icons drawn once, in the style of the phone map markers */
    private val icons: Map<CameraType, PoiIcons> by lazy {
        CameraType.values().associateWith { PoiIcons(PoiIconRenderer.render(it, 40), PoiIconRenderer.render(it, 52)) }
    }

    private data class Event(val transferId: Int?, val event: BMWRemoting.MapEvent?)

    private val events = LinkedBlockingQueue<Event>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var handle: Int? = null
    private var format: PoiFormat? = null
    private var loadedAt: LatLon? = null
    private var loadedFile: String? = null
    private var nextTransfer = 1
    private var flip = false

    /** Called from the car connection listener */
    fun onEvent(handle: Int?, transferId: Int?, event: BMWRemoting.MapEvent?) {
        log("map_onEvent handle=$handle transfer=$transferId event=$event")
        events.offer(Event(transferId, event))
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "SpeedCamsMapOverlay").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        SpeedCams.overlayStatus = "Car map overlay: starting"
        try {
            handle = call { carConnection.map_create() }
            log("map_create -> $handle")
            waitFor(setOf(BMWRemoting.MapEvent.MAP_READY), 3)   // may never come, carry on regardless
            while (running) {
                val pos = SpeedCams.current.fix?.position
                if (pos != null && (loadedAt == null || Geo.distance(loadedAt!!, pos) > RELOAD_DISTANCE)) {
                    val cameras = SpeedCams.cameras.near(pos, LOAD_RADIUS)
                        .sortedBy { Geo.distance(pos, it.position) }.take(MAX_POIS)
                    if (cameras.isNotEmpty()) load(pos, cameras)
                }
                Thread.sleep(10_000)
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            log("Experiment stopped: $e")
            SpeedCams.overlayStatus = "Car map overlay: not supported ($e)"
        } finally {
            cleanup()
        }
    }

    private enum class Result { DONE, SILENT, REJECTED }

    private fun load(pos: LatLon, cameras: List<Camera>) {
        val known = format
        if (known != null) {
            if (import(known, cameras) != Result.REJECTED) loadedAt = pos
            return
        }
        // first time: find a format the car accepts, preferring one it confirms
        var firstSilent: PoiFormat? = null
        for (candidate in PoiFormat.values()) {
            if (!running) return
            when (import(candidate, cameras)) {
                Result.DONE -> {
                    format = candidate
                    loadedAt = pos
                    log("The car confirmed ${candidate.name}")
                    return
                }
                Result.SILENT -> if (firstSilent == null) firstSilent = candidate
                Result.REJECTED -> {}
            }
        }
        if (firstSilent != null) {
            format = firstSilent
            loadedAt = pos
            log("No format was confirmed, using ${firstSilent.name} which the car did not reject")
            import(firstSilent, cameras)
            return
        }
        log("The car rejected every format: ${PoiFormat.values().joinToString { it.name }}")
        SpeedCams.overlayStatus = "Car map overlay: the car rejected every format tried"
        running = false
    }

    /** Imports one file, shows it, and removes the previous one */
    private fun import(format: PoiFormat, cameras: List<Camera>): Result {
        val h = handle ?: return Result.REJECTED
        flip = !flip
        val filename = "speedcams_${if (flip) "a" else "b"}.${format.extension}"
        val data = format.encode(cameras, icons)
        val requested = nextTransfer++
        events.clear()
        return try {
            val returned = call { carConnection.map_initializeImport(h, filename, requested, data.size) }
            val transfer = if (returned != null && returned > 0) returned else requested
            log("map_initializeImport $filename ${data.size} bytes, ${cameras.size} cameras -> $returned")
            waitFor(setOf(BMWRemoting.MapEvent.MAP_IMPORT_READY), 3)
            var seq = 0
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK, data.size)
                call { carConnection.map_importData(h, transfer, seq, data.copyOfRange(offset, end)) }
                seq += 1
                offset = end
            }
            call { carConnection.map_finalizeImport(h, transfer) }
            val result = waitFor(setOf(BMWRemoting.MapEvent.MAP_IMPORT_DONE, BMWRemoting.MapEvent.MAP_IMPORT_ABORT), EVENT_TIMEOUT_S)
            log("Import of $filename finished with ${result ?: "no answer"}")
            if (result == BMWRemoting.MapEvent.MAP_IMPORT_ABORT) return Result.REJECTED
            // the car may not report completion at all, so show it anyway and let the driver look
            call { carConnection.map_showOverlay(h, filename, OVERLAY_ID) }
            call { carConnection.map_setMode(h, BMWRemoting.MapMode.MAP_MODE_ALL_LOCATIONS_WITH_CCP) }
            log("map_showOverlay $filename")
            loadedFile?.takeIf { it != filename }?.let { old ->
                try { call { carConnection.map_remove(h, old) } } catch (e: Exception) { log("map_remove $old failed: $e") }
            }
            loadedFile = filename
            SpeedCams.overlayStatus = "Car map overlay: ${cameras.size} cameras sent as ${format.name}" +
                    (if (result == null) " (car did not confirm)" else "")
            if (result == BMWRemoting.MapEvent.MAP_IMPORT_DONE) Result.DONE else Result.SILENT
        } catch (e: Exception) {
            log("Import of $filename failed: $e")
            try { call { carConnection.map_abortImport(h, requested, 0) } } catch (_: Exception) {}
            Result.REJECTED
        }
    }

    private fun waitFor(wanted: Set<BMWRemoting.MapEvent>, seconds: Long): BMWRemoting.MapEvent? {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (e.event in wanted || e.event == BMWRemoting.MapEvent.MAP_IMPORT_ABORT) return e.event
        }
    }

    private fun cleanup() {
        val h = handle ?: return
        try {
            loadedFile?.let { call { carConnection.map_remove(h, it) } }
            call { carConnection.map_dispose(h) }
        } catch (e: Exception) {}
        handle = null
        loadedFile = null
    }

    private fun <T> call(block: () -> T): T = synchronized(carConnection) { block() }

    private fun log(message: String) {
        Log.i(TAG, "MapOverlay: $message")
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            logFile.appendText("$time $message\n")
        } catch (e: Exception) {}
    }
}
