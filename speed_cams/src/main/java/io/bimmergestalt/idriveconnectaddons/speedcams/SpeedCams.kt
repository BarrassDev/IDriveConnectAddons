package io.bimmergestalt.idriveconnectaddons.speedcams

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AheadCamera
import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AheadFinder
import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AlertSounder
import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AlertTracker
import io.bimmergestalt.idriveconnectaddons.speedcams.alert.PhoneNotifier
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraStore
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.RoadStore
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import io.bimmergestalt.idriveconnectaddons.speedcams.location.CarGpsParser
import io.bimmergestalt.idriveconnectaddons.speedcams.location.FixSource
import io.bimmergestalt.idriveconnectaddons.speedcams.location.LocationFix
import io.bimmergestalt.idriveconnectaddons.speedcams.location.PhoneLocation
import io.bimmergestalt.idriveconnectaddons.speedcams.map.MapRenderer
import io.bimmergestalt.idriveconnectaddons.speedcams.map.Sun
import io.bimmergestalt.idriveconnectaddons.speedcams.map.TileCache
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The shared engine: takes position updates, works out the road and the cameras ahead,
 * raises alerts, and renders the map picture for the car and the phone preview
 */
object SpeedCams {
    /** The car side of the app, implemented by CarApp */
    interface CarOutput {
        val frameWidth: Int
        val frameHeight: Int
        /** whether the map screen is showing in the car */
        val wantsFrames: Boolean
        fun onFrame(jpeg: ByteArray)
        fun showAlert(title: String, line1: String, line2: String)
        fun hideAlert()
    }

    private const val PHONE_FIX_STALE_MS = 3000L
    private const val MOVING_SPEED = 2.0      // m/s, below this the GPS heading is noise
    private const val SPEEDING_MARGIN = 0.45  // m/s, about 1 mph

    lateinit var settings: Settings
        private set
    lateinit var cameras: CameraStore
        private set
    val roads = RoadStore()

    val state = MutableLiveData(DriveState())
    @Volatile var current = DriveState()
        private set
    val preview = MutableLiveData<Bitmap?>(null)

    @Volatile var carOutput: CarOutput? = null
        set(value) {
            field = value
            updateRendering()
        }
    @Volatile var previewActive = false
        set(value) {
            field = value
            updateRendering()
        }

    /** Progress of the experimental car map overlay, shown on the phone */
    @Volatile var overlayStatus: String? = null
    /** Set by the car app, called when the experimental overlay toggle changes */
    @Volatile var onOverlayToggled: ((Boolean) -> Unit)? = null

    private var initialized = false
    private lateinit var engine: Handler
    private lateinit var render: Handler
    private val cameraIo = Executors.newSingleThreadExecutor()
    private val roadIo = Executors.newSingleThreadExecutor()
    private lateinit var phoneLocation: PhoneLocation
    private lateinit var sounder: AlertSounder
    private lateinit var notifier: PhoneNotifier
    private lateinit var renderer: MapRenderer
    private val tracker = AlertTracker()
    private val speedingWarned = HashSet<Long>()

    private var users = 0
    private var lastPhoneFixAt = 0L
    private var lastGoodPhoneFixAt = 0L
    private var lastFix: LocationFix? = null
    private var heading: Double? = null
    private var carPosition: LatLon? = null
    private var carSpeed: Double? = null
    private var carHeading: Double? = null
    @Volatile var simulating = false
        private set
    private var rendering = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        settings = Settings(app)
        cameras = CameraStore(app.filesDir)
        engine = Handler(HandlerThread("SpeedCamsEngine").apply { start() }.looper)
        render = Handler(HandlerThread("SpeedCamsRender").apply { start() }.looper)
        phoneLocation = PhoneLocation(app) { onPhoneFix(it) }
        sounder = AlertSounder(app)
        notifier = PhoneNotifier(app)
        renderer = MapRenderer(TileCache(app.cacheDir) {})
        cameraIo.execute {
            cameras.loadCache()
            engine.post { publish(current.copy(cameraCount = cameras.cameras.size, status = status())) }
        }
    }

    /** Someone needs location: the car connection or the phone screen */
    fun acquire() = engine.post {
        users += 1
        phoneLocation.start(engine.looper)
    }

    fun release() = engine.post {
        users = max(0, users - 1)
        if (users == 0) phoneLocation.stop()
    }

    /** Try phone location again, after the permission was granted */
    fun restartLocation() = engine.post {
        phoneLocation.stop()
        if (users > 0) phoneLocation.start(engine.looper)
    }

    fun forceCameraRefresh() = engine.post {
        val pos = lastFix?.position ?: cameras.center ?: return@post
        cameras.forceRefreshAllowed()
        cameraIo.execute {
            cameras.refresh(pos)
            engine.post { lastFix?.let { process(it) } ?: publish(current.copy(cameraCount = cameras.cameras.size, status = status())) }
        }
        publish(current.copy(status = status()))
    }

    // ---- position input ----

    private fun onPhoneFix(fix: LocationFix) {
        if (simulating) return
        val now = System.currentTimeMillis()
        val accurate = fix.accuracy == null || fix.accuracy <= 60f
        // ignore coarse network fixes while good GPS fixes are arriving
        if (!accurate && now - lastGoodPhoneFixAt < 10_000) return
        if (accurate) lastGoodPhoneFixAt = now
        lastPhoneFixAt = now
        process(fix)
    }

    /** Car Data Service updates from the car connection, any thread */
    fun onCarProperty(propertyName: String?, value: String?) = engine.post {
        when (propertyName) {
            "navigation.GPSPosition" -> carPosition = CarGpsParser.parsePosition(value) ?: return@post
            "navigation.GPSExtendedInfo" -> {
                val (speed, heading) = CarGpsParser.parseExtended(value)
                carSpeed = speed
                carHeading = heading
                return@post
            }
            else -> return@post
        }
        val now = System.currentTimeMillis()
        if (simulating || now - lastPhoneFixAt < PHONE_FIX_STALE_MS) return@post
        val pos = carPosition ?: return@post
        process(LocationFix(pos, carSpeed, carHeading, null, now, FixSource.CAR))
    }

    // ---- the main update ----

    private fun process(fix: LocationFix) {
        val previous = lastFix
        lastFix = fix
        val pos = fix.position
        val speed = fix.speed ?: previous?.let { p ->
            val dt = (fix.time - p.time) / 1000.0
            if (dt in 0.5..5.0) Geo.distance(p.position, pos) / dt else null
        }
        val moving = (speed ?: 0.0) >= MOVING_SPEED
        if (moving) {
            val measured = fix.heading ?: previous?.let { p ->
                if (Geo.distance(p.position, pos) > 5) Geo.bearing(p.position, pos) else null
            }
            if (measured != null) {
                val h = heading
                // light smoothing, along the shortest way round
                heading = if (h == null) measured else Geo.normalize(h + Geo.angleDiff(h, measured) * 0.7)
            }
        }

        if (cameras.needsRefresh(pos)) {
            cameraIo.execute {
                cameras.refresh(pos)
                engine.post { lastFix?.let { process(it) } }
            }
        }
        val useRoads = fix.source != FixSource.SIMULATED
        if (useRoads && roads.needsRefresh(pos)) {
            roadIo.execute { roads.refresh(pos) }
        }

        val alertMeters = settings.alertMeters
        val lookahead = alertMeters + 150
        val network = if (useRoads) roads.network?.takeIf { it.covers(pos, 100.0) } else null
        val match = network?.match(pos, if (moving) heading else null)
        val path = if (network != null && match != null && moving) network.walkAhead(match, lookahead) else emptyList()
        val candidates = cameras.near(pos, max(3000.0, lookahead + 300))
        val h = heading
        val ahead: List<AheadCamera> = when {
            !moving -> emptyList()
            path.size >= 2 -> {
                val onRoad = AheadFinder.onPath(path, candidates, lookahead)
                // a dead end in the road data: fall back to the straight corridor
                if (pathLength(path) < lookahead * 0.5 && h != null) {
                    (onRoad + AheadFinder.inCorridor(pos, h, candidates, lookahead)).distinctBy { it.camera.id }.sortedBy { it.distance }
                } else onRoad
            }
            h != null -> AheadFinder.inCorridor(pos, h, candidates, lookahead)
            else -> emptyList()
        }

        val fresh = tracker.update(pos, ahead, alertMeters)
        val alert = ahead.firstOrNull { it.distance <= alertMeters }
        val hadAlert = current.alert != null
        fresh.firstOrNull()?.let { fireAlert(it, speed) }
        if (alert != null) checkSpeeding(alert, speed)
        if (alert == null && hadAlert) {
            carOutput?.hideAlert()
            notifier.cancel()
        }

        publish(DriveState(
            fix = fix,
            heading = heading,
            speed = speed,
            limit = match?.road?.limit,
            roadName = match?.road?.name,
            path = path,
            ahead = ahead,
            nearby = candidates.filter { Geo.distance(pos, it.position) < 2500 },
            alert = alert,
            cameraCount = cameras.cameras.size,
            status = status(),
        ))
    }

    private fun pathLength(path: List<LatLon>): Double {
        var d = 0.0
        for (i in 0 until path.size - 1) d += Geo.distance(path[i], path[i + 1])
        return d
    }

    private fun fireAlert(ahead: AheadCamera, speed: Double?) {
        val showMph = settings.showMph
        val unit = if (showMph) "mph" else "km/h"
        val limit = ahead.camera.limit
        val title = ahead.camera.type.label
        val line1 = "${MapRenderer.formatDistance(ahead.distance, showMph)} ahead"
        val line2 = limit?.let { "Limit ${it.display(showMph)} $unit" } ?: "Limit not mapped"
        val spoken = buildString {
            append(ahead.camera.type.spoken).append(" ahead, ")
            append(MapRenderer.spokenDistance(ahead.distance, showMph))
            limit?.let { append(". Limit ").append(it.display(showMph)) }
        }
        val speeding = limit != null && speed != null && speed > limit.metersPerSecond + SPEEDING_MARGIN
        if (speeding) speedingWarned.add(ahead.camera.id)
        Log.i(TAG, "Alert: $title $line1, $line2")
        sounder.alert(if (settings.voice) spoken else null, urgent = speeding)
        notifier.show(title, "$line1 · $line2")
        carOutput?.showAlert(title, line1, line2)
    }

    /** One urgent chime if the driver is over the camera's limit inside the alert zone */
    private fun checkSpeeding(alert: AheadCamera, speed: Double?) {
        val limit = alert.camera.limit ?: return
        if (speed == null || speed <= limit.metersPerSecond + SPEEDING_MARGIN) return
        if (speedingWarned.add(alert.camera.id)) sounder.alert(null, urgent = true)
        if (speedingWarned.size > 200) speedingWarned.clear()
    }

    private fun status(): String {
        val lines = ArrayList<String>()
        lines += when {
            cameras.loading -> "Downloading speed cameras…"
            cameras.cameras.isNotEmpty() -> "${cameras.cameras.size} cameras loaded" +
                    (cameras.center?.let { " within %.0f km of %.4f, %.4f".format(CameraStore.AREA_RADIUS / 1000, it.lat, it.lon) } ?: "")
            cameras.lastError != null -> "Camera download failed: ${cameras.lastError}"
            else -> "No cameras yet, waiting for a position"
        }
        lines += when {
            roads.loading -> "Loading roads and speed limits…"
            roads.network != null -> "${roads.network!!.roads.size} roads loaded nearby"
            roads.lastError != null -> "Road download failed: ${roads.lastError}"
            else -> "Roads not loaded yet"
        }
        lines += when {
            simulating -> "Simulating a drive"
            !phoneLocation.hasPermission() -> "No phone location permission, using car GPS only"
            else -> ""
        }
        overlayStatus?.let { lines += it }
        return lines.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun publish(s: DriveState) {
        current = s
        state.postValue(s)
    }

    // ---- simulation, to test alerts without driving ----

    /** Drives a straight line at 35 mph over the nearest camera. Returns a description, or null if there are no cameras. */
    fun simulateApproach(): String? {
        if (simulating) return "Already simulating"
        val base = lastFix?.position ?: cameras.center ?: return null
        val camera = cameras.near(base, 50_000.0).minByOrNull { Geo.distance(base, it.position) } ?: return null
        val speed = 35 / 2.236936
        val bearing = 0.0
        val start = Geo.destination(camera.position, Geo.normalize(bearing + 180), 900.0)
        val steps = ((900 + 250) / speed).toInt()
        engine.post {
            simulating = true
            tracker.reset()
            speedingWarned.clear()
            heading = null
            lastFix = null
        }
        for (i in 0..steps) {
            engine.postDelayed({
                val fix = LocationFix(Geo.destination(start, bearing, i * speed), speed, bearing, 5f,
                    System.currentTimeMillis(), FixSource.SIMULATED)
                process(fix)
                if (i == steps) {
                    simulating = false
                    lastFix = null
                    publish(current.copy(status = status()))
                }
            }, 300L + i * 1000L)
        }
        val km = Geo.distance(base, camera.position) / 1000
        return "Approaching the ${camera.type.label.lowercase()} %.1f km away at 35 mph".format(km)
    }

    fun testAlert() {
        sounder.alert(if (settings.voice) "Speed camera ahead, 0.3 miles. Limit 30" else null)
        notifier.show("Speed camera", "0.3 mi ahead · Limit 30 mph")
        carOutput?.showAlert("Speed camera", "0.3 mi ahead", "Limit 30 mph")
    }

    // ---- rendering ----

    private val renderLoop = object : Runnable {
        override fun run() {
            renderOnce()
            if (rendering) render.postDelayed(this, 1000)
        }
    }

    private fun updateRendering() {
        if (!initialized) return
        val want = previewActive || carOutput?.wantsFrames == true
        render.post {
            if (want && !rendering) {
                rendering = true
                render.post(renderLoop)
            } else if (!want) {
                rendering = false
                render.removeCallbacks(renderLoop)
            }
        }
    }

    /** Called by the car app when the map screen opens or closes */
    fun onCarVisibilityChanged() = updateRendering()

    private fun renderOnce() {
        try {
            val car = carOutput
            val toCar = car?.wantsFrames == true
            val w = car?.frameWidth ?: 1211
            val h = car?.frameHeight ?: 480
            val s = current
            val pos = s.fix?.position
            val dark = when (settings.mapStyle) {
                MapStyle.DARK -> true
                MapStyle.LIGHT -> false
                MapStyle.AUTO -> pos != null && Sun.isNight(pos)
            }
            val options = MapRenderer.Options(settings.headingUp, settings.showMph, dark, settings.alertMeters)
            val bmp = renderer.render(s, w, h, options)
            if (toCar) {
                val out = ByteArrayOutputStream(96 * 1024)
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                car?.onFrame(out.toByteArray())
            }
            if (previewActive) {
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                Handler(Looper.getMainLooper()).post { preview.value = copy }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Render failed", e)
        }
    }
}
