package io.bimmergestalt.idriveconnectaddons.speedcams.car

import android.util.Log
import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import io.bimmergestalt.idriveconnectaddons.speedcams.AndroidResources
import io.bimmergestalt.idriveconnectaddons.speedcams.CarThread
import io.bimmergestalt.idriveconnectaddons.speedcams.L
import io.bimmergestalt.idriveconnectaddons.speedcams.R
import io.bimmergestalt.idriveconnectaddons.speedcams.SpeedCams
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectkit.CDS
import io.bimmergestalt.idriveconnectkit.IDriveConnection
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.RHMIUtils.rhmi_setResourceCached
import io.bimmergestalt.idriveconnectkit.android.CarAppResources
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionStatus
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIActionCallback
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplication
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplicationEtch
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplicationIdempotent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplicationSynchronized
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplicationWrapper
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIComponent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIEvent
import io.bimmergestalt.idriveconnectkit.rhmi.deserialization.loadFromXML
import java.util.concurrent.atomic.AtomicReference

/**
 * The car-side app, running on the CarThread:
 * a full-screen map page, a popup for warnings, and the car's GPS as a backup position source
 */
class CarApp(val iDriveConnectionStatus: IDriveConnectionStatus, securityAccess: SecurityAccess,
             val carAppResources: CarAppResources, val androidResources: AndroidResources,
             val carThread: CarThread): SpeedCams.CarOutput {
    companion object {
        const val APP_ID = "io.bimmergestalt.idriveconnectaddons.speedcams"
        const val POPUP_DURATION_MS = 8000L
    }

    private val carConnection: BMWRemotingServer
    private val dims: RHMIDimensions
    private var amHandle = -1
    private var carApp: RHMIApplication
    private lateinit var mapScreen: MapScreen
    private var popup: AlertPopup? = null
    private val pendingFrame = AtomicReference<ByteArray?>(null)
    private val hidePopup = Runnable { popup?.hide() }
    private var overlay: MapOverlayExperiment? = null

    init {
        Log.i(TAG, "Starting connecting to car")
        carConnection = IDriveConnection.getEtchConnection(iDriveConnectionStatus.host ?: "127.0.0.1", iDriveConnectionStatus.port ?: 8003, CarAppListener())
        val appCert = carAppResources.getAppCertificate(iDriveConnectionStatus.brand ?: "")?.readBytes()
        val challenge = carConnection.sas_certificate(appCert)
        carConnection.sas_login(securityAccess.signChallenge(challenge = challenge))

        val capabilities = carConnection.rhmi_getCapabilities("", 255)
            .map { it.key.toString() to it.value?.toString() }.toMap()
        dims = RHMIDimensions.create(capabilities)
        Log.i(TAG, "Car ${capabilities["hmi.type"]} ${capabilities["hmi.display-width"]}x${capabilities["hmi.display-height"]}, " +
                "map picture ${dims.visibleWidth}x${dims.visibleHeight}")
        probeMapOverlay()

        createAmApp()
        carApp = createRhmiApp()
        initWidgets()
        subscribeCarGps()

        SpeedCams.carOutput = this
        SpeedCams.acquire()
        SpeedCams.onOverlayToggled = { enabled -> setOverlay(enabled) }
        setOverlay(SpeedCams.settings.carMapOverlay)
        Log.i(TAG, "CarApp running")
    }

    /**
     * Checks whether this head unit offers the undocumented map overlay service.
     * Only logged, the import format is unknown so nothing is imported.
     */
    private fun probeMapOverlay() {
        try {
            val handle = carConnection.map_create()
            Log.i(TAG, "map_create succeeded with handle $handle, the map overlay service exists on this car")
            carConnection.map_dispose(handle)
        } catch (e: Exception) {
            Log.i(TAG, "map_create is not available: $e")
        }
    }

    /** Starts or stops the experimental car map overlay, from the toggle on the phone */
    @Synchronized
    private fun setOverlay(enabled: Boolean) {
        if (enabled && overlay == null) {
            val file = java.io.File(androidResources.context.filesDir, "map_overlay.txt")
            try { file.appendText("--- ${java.util.Date()}\n") } catch (e: Exception) {}
            overlay = MapOverlayExperiment(carConnection, file).also { it.start() }
        } else if (!enabled && overlay != null) {
            overlay?.stop()
            overlay = null
            SpeedCams.overlayStatus = null
        }
    }

    private fun createAmApp() {
        if (amHandle < 0) {
            val handle = carConnection.am_create("0", "\u0000\u0000\u0000\u0000\u0000\u0002\u0000\u0000".toByteArray())
            carConnection.am_addAppEventHandler(handle, APP_ID)
            amHandle = handle
        }
        val amInfo = mutableMapOf<Int, Any>(
            0 to 145,   // basecore version
            1 to L.CAR_TITLE,
            2 to androidResources.getRaw(R.raw.ic_carapp),
            3 to "Navigation",
            4 to true,
            5 to 1600,   // weight
            8 to -1      // mainstateId
        )
        for (languageCode in 101..123) {
            amInfo[languageCode] = L.CAR_TITLE
        }
        try {
            carConnection.am_registerApp(amHandle, APP_ID, amInfo)
        } catch (e: BMWRemoting.ServiceException) {
            Log.i(TAG, "Failed to register in the Navigation section, using OnlineServices: $e")
            amInfo[3] = "OnlineServices"
            carConnection.am_registerApp(amHandle, APP_ID, amInfo)
        }
    }

    private fun createRhmiApp(): RHMIApplication {
        val rhmiHandle = carConnection.rhmi_create(null, BMWRemoting.RHMIMetaData(APP_ID, BMWRemoting.VersionInfo(0, 1, 0), APP_ID, "io.bimmergestalt"))
        carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.DESCRIPTION, carAppResources.getUiDescription())
        carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.TEXTDB, carAppResources.getTextsDB("common"))
        carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.IMAGEDB, carAppResources.getImagesDB(iDriveConnectionStatus.brand ?: "common"))
        carConnection.rhmi_initialize(rhmiHandle)
        carConnection.rhmi_addActionEventHandler(rhmiHandle, APP_ID, -1)
        carConnection.rhmi_addHmiEventHandler(rhmiHandle, APP_ID, -1, -1)
        return RHMIApplicationSynchronized(RHMIApplicationIdempotent(
            RHMIApplicationEtch(carConnection, rhmiHandle)
        ), carConnection).apply {
            loadFromXML(carAppResources.getUiDescription()!!.readBytes())
        }
    }

    private fun initWidgets() {
        mapScreen = MapScreen(carApp.states.values.first { MapScreen.fits(it) }, dims) {
            SpeedCams.onCarVisibilityChanged()
        }
        popup = carApp.states.values.firstOrNull { AlertPopup.fits(it) }?.let { AlertPopup(it) }
        if (popup == null) Log.w(TAG, "No popup state in this layout, car alerts will only show on the map")

        carApp.components.values.filterIsInstance<RHMIComponent.EntryButton>().forEach {
            it.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = mapScreen.state.id
            it.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionCallback {
                Log.d(TAG, "Entering the map screen")
            }
        }
        mapScreen.initWidgets()
    }

    private fun recreateRhmiApp() {
        synchronized(carConnection) {
            val oldApp = carApp
            if (oldApp is RHMIApplicationWrapper) {
                val etchApp = oldApp.unwrap() as RHMIApplicationEtch
                carConnection.rhmi_dispose(etchApp.rhmiHandle)
                carApp = createRhmiApp()
                initWidgets()
            }
        }
    }

    private fun subscribeCarGps() {
        try {
            val cdsHandle = carConnection.cds_create()
            for (property in listOf(CDS.NAVIGATION.GPSPOSITION, CDS.NAVIGATION.GPSEXTENDEDINFO)) {
                carConnection.cds_addPropertyChangedEventHandler(cdsHandle, property.ident.toString(), property.propertyName, 500)
                carConnection.cds_getPropertyAsync(cdsHandle, property.ident.toString(), property.propertyName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not subscribe to the car's GPS", e)
        }
    }

    // ---- SpeedCams.CarOutput, called from the engine threads ----

    override val frameWidth: Int get() = dims.visibleWidth
    override val frameHeight: Int get() = dims.visibleHeight
    override val wantsFrames: Boolean get() = mapScreen.visible

    override fun onFrame(jpeg: ByteArray) {
        // only keep the newest frame if the car link is slow
        if (pendingFrame.getAndSet(jpeg) == null) {
            carThread.post {
                val frame = pendingFrame.getAndSet(null) ?: return@post
                try {
                    mapScreen.showFrame(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not send the map frame", e)
                }
            }
        }
    }

    override fun showAlert(title: String, line1: String, line2: String) {
        carThread.post {
            popup?.show(title, line1, line2)
            carThread.handler?.removeCallbacks(hidePopup)
            carThread.handler?.postDelayed(hidePopup, POPUP_DURATION_MS)
        }
    }

    override fun hideAlert() {
        carThread.post {
            carThread.handler?.removeCallbacks(hidePopup)
            popup?.hide()
        }
    }

    private var destroyed = false

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        setOverlay(false)
        SpeedCams.onOverlayToggled = null
        if (SpeedCams.carOutput === this) SpeedCams.carOutput = null
        SpeedCams.release()
    }

    inner class CarAppListener: BaseBMWRemotingClient() {
        override fun am_onAppEvent(handle: Int?, ident: String?, appId: String?, event: BMWRemoting.AMEvent?) {
            val focusEvent = carApp.events.values.filterIsInstance<RHMIEvent.FocusEvent>().first()
            try {
                focusEvent.triggerEvent(mapOf(0.toByte() to mapScreen.state.id))
            } catch (e: Exception) {
                Log.i(TAG, "Failed to focus the map screen, recreating RHMI and trying again")
                try {
                    recreateRhmiApp()
                    focusEvent.triggerEvent(mapOf(0.toByte() to mapScreen.state.id))
                } catch (e: Exception) {
                    Log.e(TAG, "Received exception while handling am_onAppEvent", e)
                }
            }
            Thread.sleep(200)
            createAmApp()
        }

        override fun rhmi_onActionEvent(handle: Int?, ident: String?, actionId: Int?, args: MutableMap<*, *>?) {
            try {
                carApp.actions[actionId]?.asRAAction()?.rhmiActionCallback?.onActionEvent(args)
            } catch (e: Exception) {
                Log.e(TAG, "Exception while calling onActionEvent handler!", e)
            }
            synchronized(carConnection) {
                carConnection.rhmi_ackActionEvent(handle, actionId, 1, true)
            }
        }

        override fun rhmi_onHmiEvent(handle: Int?, ident: String?, componentId: Int?, eventId: Int?, args: MutableMap<*, *>?) {
            try {
                carApp.states[componentId]?.onHmiEvent(eventId, args)
                carApp.components[componentId]?.onHmiEvent(eventId, args)
            } catch (e: Exception) {
                Log.e(TAG, "Received exception while handling rhmi_onHmiEvent", e)
            }
        }

        override fun map_onEvent(handle: Int?, transferId: Int?, event: BMWRemoting.MapEvent?) {
            overlay?.onEvent(handle, transferId, event)
        }

        override fun cds_onPropertyChangedEvent(handle: Int?, ident: String?, propertyName: String?, propertyValue: String?) {
            SpeedCams.onCarProperty(propertyName, propertyValue)
        }
    }
}
