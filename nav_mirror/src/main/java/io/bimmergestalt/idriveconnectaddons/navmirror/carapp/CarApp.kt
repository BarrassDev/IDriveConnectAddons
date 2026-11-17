package io.bimmergestalt.idriveconnectaddons.navmirror.carapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Observer
import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import io.bimmergestalt.idriveconnectaddons.navmirror.AndroidResources
import io.bimmergestalt.idriveconnectaddons.navmirror.CarThread
import io.bimmergestalt.idriveconnectaddons.navmirror.L
import io.bimmergestalt.idriveconnectaddons.navmirror.NavMirror
import io.bimmergestalt.idriveconnectaddons.navmirror.R
import io.bimmergestalt.idriveconnectaddons.navmirror.TAG
import io.bimmergestalt.idriveconnectaddons.navmirror.carapp.views.StatusView
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import io.bimmergestalt.idriveconnectkit.IDriveConnection
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

/**
 * The car-side app, running on the CarThread
 * It shows a status screen in the car and starts the car's navigation on request
 */
class CarApp(val iDriveConnectionStatus: IDriveConnectionStatus, securityAccess: SecurityAccess,
             val carAppResources: CarAppResources, val androidResources: AndroidResources,
             val carThread: CarThread): NavMirror.CarNavigationTrigger {
    companion object {
        const val APP_ID = "io.bimmergestalt.idriveconnectaddons.navmirror"
    }

    private val carConnection: BMWRemotingServer
    private var amHandle = -1
    private var carApp: RHMIApplication
    private var statusView: StatusView
    private var navigationTrigger: NavigationTrigger? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshObserver = Observer<Any?> { refresh() }

    init {
        Log.i(TAG, "Starting connecting to car")
        val carappListener = CarAppListener()
        carConnection = IDriveConnection.getEtchConnection(iDriveConnectionStatus.host ?: "127.0.0.1", iDriveConnectionStatus.port ?: 8003, carappListener)
        val appCert = carAppResources.getAppCertificate(iDriveConnectionStatus.brand ?: "")?.readBytes()
        val sas_challenge = carConnection.sas_certificate(appCert)
        val sas_response = securityAccess.signChallenge(challenge = sas_challenge)
        carConnection.sas_login(sas_response)

        createAmApp()

        carApp = createRhmiApp()
        statusView = StatusView(carApp.states.values.firstOrNull { StatusView.fits(it) }
            ?: throw IllegalStateException("No suitable list state in this car app layout"))
        navigationTrigger = try {
            NavigationTrigger(carApp)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Car layout does not support navigation: ${e.message}")
            null
        }

        initWidgets()
        subscribe()

        if (navigationTrigger != null) {
            NavMirror.onCarAppReady(this)
        } else {
            NavMirror.onCarAppMissingNavigate()
        }
        Log.i(TAG, "CarApp running, navigation supported: ${navigationTrigger != null}")
    }

    private fun createAmApp() {
        if (amHandle < 0) {
            val handle = carConnection.am_create("0", "\u0000\u0000\u0000\u0000\u0000\u0002\u0000\u0000".toByteArray())
            carConnection.am_addAppEventHandler(handle, APP_ID)
            amHandle = handle
        }

        val amInfo = mutableMapOf<Int, Any>(
            0 to 145,   // basecore version
            1 to L.CAR_TITLE,  // app name
            2 to androidResources.getRaw(R.raw.ic_carapp), // icon
            3 to "Navigation",   // section
            4 to true,
            5 to 1500,   // weight
            8 to -1  // mainstateId
        )
        // language translations
        for (languageCode in 101..123) {
            amInfo[languageCode] = L.CAR_TITLE
        }
        try {
            carConnection.am_registerApp(amHandle, APP_ID, amInfo)
        } catch (e: BMWRemoting.ServiceException) {
            // this car doesn't accept icons in the Navigation section
            Log.i(TAG, "Failed to register AM icon in the Navigation section, using OnlineServices: $e")
            amInfo[3] = "OnlineServices"
            carConnection.am_registerApp(amHandle, APP_ID, amInfo)
        }
    }

    private fun createRhmiApp(): RHMIApplication {
        // create the app in the car
        val rhmiHandle = carConnection.rhmi_create(null, BMWRemoting.RHMIMetaData(APP_ID, BMWRemoting.VersionInfo(0, 1, 0), APP_ID, "io.bimmergestalt"))
        carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.DESCRIPTION, carAppResources.getUiDescription())
        carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.TEXTDB, carAppResources.getTextsDB("common"))
        carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.IMAGEDB, carAppResources.getImagesDB(iDriveConnectionStatus.brand ?: "common"))
        carConnection.rhmi_initialize(rhmiHandle)

        // register for events from the car
        carConnection.rhmi_addActionEventHandler(rhmiHandle, APP_ID, -1)
        carConnection.rhmi_addHmiEventHandler(rhmiHandle, APP_ID, -1, -1)

        return RHMIApplicationSynchronized(RHMIApplicationIdempotent(
            RHMIApplicationEtch(carConnection, rhmiHandle)
        ), carConnection).apply {
            loadFromXML(carAppResources.getUiDescription()!!.readBytes())
        }
    }

    private fun recreateRhmiApp() {
        synchronized(carConnection) {
            val oldApp = carApp
            if (oldApp is RHMIApplicationWrapper) {
                // tear down
                val etchApp = oldApp.unwrap() as RHMIApplicationEtch
                carConnection.rhmi_dispose(etchApp.rhmiHandle)
                // recreate
                carApp = createRhmiApp()
                statusView = StatusView(carApp.states.values.first { StatusView.fits(it) })
                navigationTrigger = try {
                    NavigationTrigger(carApp)
                } catch (e: IllegalArgumentException) {
                    null
                }
                initWidgets()
                refresh()
            }
        }
    }

    private fun initWidgets() {
        carApp.components.values.filterIsInstance<RHMIComponent.EntryButton>().forEach {
            it.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = statusView.state.id
            it.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionCallback {
                Log.d(TAG, "Entering car app")
            }
        }
        statusView.canSend = navigationTrigger != null
        statusView.onSend = {
            val result = NavMirror.sendToCar()
            Log.i(TAG, "Send requested from the car: $result")
            if (result != NavMirror.SendResult.SENT) {
                statusView.feedback = result.toString()
                statusView.redraw()
            }
        }
        statusView.initWidgets()
    }

    /** Watch the shared state from the main thread, and push changes onto the car thread */
    private fun subscribe() {
        mainHandler.post {
            NavMirror.phoneNav.observeForever(refreshObserver)
            NavMirror.destination.observeForever(refreshObserver)
            NavMirror.carGuidanceActive.observeForever(refreshObserver)
        }
    }

    private fun unsubscribe() {
        mainHandler.post {
            NavMirror.phoneNav.removeObserver(refreshObserver)
            NavMirror.destination.removeObserver(refreshObserver)
            NavMirror.carGuidanceActive.removeObserver(refreshObserver)
        }
    }

    private fun refresh() {
        val nav = NavMirror.phoneNav.value
        val target = NavMirror.destination.value
        val carNavigating = NavMirror.carGuidanceActive.value ?: false
        carThread.post {
            statusView.update(nav, target, carNavigating)
        }
    }

    /** Called from any thread, runs the car command on the car thread */
    override fun navigateTo(destination: Destination): Boolean {
        val trigger = navigationTrigger ?: return false
        carThread.post {
            try {
                Log.i(TAG, "Triggering car navigation to $destination")
                trigger.triggerNavigation(destination)
                statusView.feedback = L.CAR_SENT
            } catch (e: Exception) {
                Log.w(TAG, "Error while starting car navigation", e)
                statusView.feedback = e.message ?: e.toString()
            }
            if (statusView.visible) {
                statusView.redraw()
            }
        }
        return true
    }

    fun onDestroy() {
        unsubscribe()
        NavMirror.onCarAppStopped()
    }

    inner class CarAppListener: BaseBMWRemotingClient() {
        override fun am_onAppEvent(handle: Int?, ident: String?, appId: String?, event: BMWRemoting.AMEvent?) {
            val focusEvent = carApp.events.values.filterIsInstance<RHMIEvent.FocusEvent>().first()
            try {
                focusEvent.triggerEvent(mapOf(0.toByte() to statusView.state.id))
            } catch (e: Exception) {
                Log.i(TAG, "Failed to trigger focus event for AM icon, recreating RHMI and trying again")
                try {
                    recreateRhmiApp()
                    focusEvent.triggerEvent(mapOf(0.toByte() to statusView.state.id))
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
                synchronized(carConnection) {
                    carConnection.rhmi_ackActionEvent(handle, actionId, 1, true)
                }
            } catch (e: RHMIActionAbort) {
                // the callback asked to stay on the current screen
                synchronized(carConnection) {
                    carConnection.rhmi_ackActionEvent(handle, actionId, 1, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while calling onActionEvent handler!", e)
                synchronized(carConnection) {
                    carConnection.rhmi_ackActionEvent(handle, actionId, 1, true)
                }
            }
        }

        override fun rhmi_onHmiEvent(handle: Int?, ident: String?, componentId: Int?, eventId: Int?, args: MutableMap<*, *>?) {
            try {
                // generic event handler
                carApp.states[componentId]?.onHmiEvent(eventId, args)
                carApp.components[componentId]?.onHmiEvent(eventId, args)
            } catch (e: Exception) {
                Log.e(TAG, "Received exception while handling rhmi_onHmiEvent", e)
            }
        }
    }
}
