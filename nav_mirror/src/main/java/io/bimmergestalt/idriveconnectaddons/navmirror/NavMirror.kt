package io.bimmergestalt.idriveconnectaddons.navmirror

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.bimmergestalt.idriveconnectaddons.lib.CDSLiveData
import io.bimmergestalt.idriveconnectaddons.lib.LiveDataHelpers.map
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import io.bimmergestalt.idriveconnectaddons.navmirror.model.PhoneNavState
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.AddressSearcher
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.GeocoderSearcher
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.NavNotificationListener
import io.bimmergestalt.idriveconnectkit.CDSProperty
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionObserver
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The central state of the addon, shared between the phone UI, the notification listener, and the car app
 *
 * - the notification listener reports what Google Maps or Waze is doing (phoneNav)
 * - shared links and the phone UI capture a destination (destination)
 * - the car app registers itself as a CarNavigationTrigger when connected
 * - a captured destination is pushed to the car as soon as both exist
 */
object NavMirror {
    const val NOTIFICATION_CHANNEL = "NavSync"
    const val NOTIFICATION_ID = 61200

    enum class CarStatus { DISCONNECTED, CONNECTING, READY, NO_NAVIGATE }
    enum class CaptureSource { SHARE, LINK, NOTIFICATION }
    enum class SendResult { SENT, NO_DESTINATION, CAR_NOT_READY, FAILED }

    /** Implemented by the car app, to start the car's native navigation */
    interface CarNavigationTrigger {
        /** Returns whether the request was accepted, the actual car call happens asynchronously */
        fun navigateTo(destination: Destination): Boolean
    }

    private lateinit var appContext: Context
    lateinit var settings: NavMirrorSettings
        private set
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var addressSearcher: AddressSearcher? = null

    val phoneNav = MutableLiveData<PhoneNavState?>(null)
    val destination = MutableLiveData<Destination?>(null)
    val lastSent = MutableLiveData<Destination?>(null)
    val carStatus = MutableLiveData(CarStatus.DISCONNECTED)
    val notificationAccess = MutableLiveData(false)
    lateinit var carGuidanceActive: LiveData<Boolean>
        private set
    lateinit var carDestinationName: LiveData<String>
        private set

    @Volatile
    private var trigger: CarNavigationTrigger? = null
    /** a destination was captured while the car was away, and should be sent on connection */
    @Volatile
    private var pendingSend = false
    private var geocodedHint: String? = null
    @Suppress("unused")
    private val connectionObserver = IDriveConnectionObserver { onConnectionChanged() }

    val isInitialized: Boolean
        get() = ::appContext.isInitialized

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        settings = NavMirrorSettings(appContext)
        addressSearcher = GeocoderSearcher(appContext)
        val savedDestination = settings.destination
        val savedLastSent = settings.lastSent
        destination.value = savedDestination
        lastSent.value = savedLastSent
        pendingSend = savedDestination != null && !savedDestination.isSamePlace(savedLastSent)
        notificationAccess.value = NavNotificationListener.isEnabled(appContext)

        // the car's own navigation status, through AAIdrive's CDS content provider
        carGuidanceActive = CDSLiveData(appContext, CDSProperty.NAVIGATION_GUIDANCESTATUS).map(false) {
            (it["guidanceStatus"] as? Number)?.toInt() == 1
        }
        carDestinationName = CDSLiveData(appContext, CDSProperty.NAVIGATION_NEXTDESTINATION).map("") {
            (it["name"] as? String) ?: ""
        }
        onConnectionChanged()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun onConnectionChanged() {
        if (!IDriveConnectionStatus.isConnected) {
            trigger = null
            carStatus.postValue(CarStatus.DISCONNECTED)
        } else if (trigger == null && carStatus.value != CarStatus.NO_NAVIGATE) {
            carStatus.postValue(CarStatus.CONNECTING)
        }
    }

    /** Called by the notification listener with the phone's current navigation state */
    fun onPhoneNav(state: PhoneNavState) = runOnMain {
        phoneNav.value = state
        val hint = state.destinationHint
        if (!state.navigating) {
            geocodedHint = null
        } else if (settings.geocodeHints && hint != null && hint != geocodedHint) {
            // experimental: the notification mentioned a destination name, try to resolve it
            geocodedHint = hint
            scope.launch {
                val found = withContext(Dispatchers.IO) {
                    try {
                        addressSearcher?.search(hint)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (found != null) {
                    Log.i(TAG, "Resolved notification destination '$hint' to $found")
                    onDestinationCaptured(found.copy(label = hint), CaptureSource.NOTIFICATION)
                } else {
                    Log.i(TAG, "Could not resolve notification destination '$hint'")
                }
            }
        }
    }

    /** A destination was shared to the addon or pasted into the phone UI */
    fun onDestinationCaptured(captured: Destination, source: CaptureSource, autoSend: Boolean = settings.autoSend) = runOnMain {
        Log.i(TAG, "Captured destination from $source: $captured")
        destination.value = captured
        settings.destination = captured
        pendingSend = true
        if (autoSend) {
            sendToCar()
        }
    }

    /**
     * Re-reads the phone navigation notifications right now, captures a destination name from them
     * if one is mentioned, and then sends the captured destination to the car
     */
    fun refreshAndSend(onResult: (SendResult, Destination?) -> Unit) = runOnMain {
        NavNotificationListener.instance?.refreshNow()
        val nav = phoneNav.value
        val hint = nav?.destinationHint?.takeIf { nav.navigating }
        val current = destination.value
        if (hint != null && (current == null || current.label != hint)) {
            scope.launch {
                val found = withContext(Dispatchers.IO) {
                    try {
                        addressSearcher?.search(hint)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (found != null) {
                    geocodedHint = hint
                    onDestinationCaptured(found.copy(label = hint), CaptureSource.NOTIFICATION, autoSend = false)
                } else {
                    Log.i(TAG, "Could not resolve notification destination '$hint'")
                }
                onResult(sendToCar(), destination.value)
            }
        } else {
            onResult(sendToCar(), current)
        }
    }

    fun clearDestination() = runOnMain {
        destination.value = null
        settings.destination = null
        pendingSend = false
    }

    /** Sends the captured destination to the car navigation, if the car app is ready */
    fun sendToCar(): SendResult {
        val target = destination.value ?: return SendResult.NO_DESTINATION
        val carTrigger = trigger ?: return SendResult.CAR_NOT_READY
        val accepted = try {
            carTrigger.navigateTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send destination to car", e)
            false
        }
        if (!accepted) return SendResult.FAILED
        pendingSend = false
        runOnMain {
            lastSent.value = target
            settings.lastSent = target
        }
        showSentNotification(target)
        return SendResult.SENT
    }

    /** The car app has connected and found the navigate action */
    fun onCarAppReady(carTrigger: CarNavigationTrigger) {
        trigger = carTrigger
        carStatus.postValue(CarStatus.READY)
        runOnMain {
            if (settings.autoSend && pendingSend && destination.value != null) {
                Log.i(TAG, "Car connected, sending pending destination")
                sendToCar()
            }
        }
    }

    /** The car app connected, but the car layout has no navigate action */
    fun onCarAppMissingNavigate() {
        trigger = null
        carStatus.postValue(CarStatus.NO_NAVIGATE)
    }

    fun onCarAppStopped() {
        trigger = null
        carStatus.postValue(if (IDriveConnectionStatus.isConnected) CarStatus.CONNECTING else CarStatus.DISCONNECTED)
    }

    private fun showSentNotification(target: Destination) {
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = NotificationManagerCompat.from(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL,
                appContext.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        val contentIntent = PendingIntent.getActivity(appContext, 0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(appContext.getString(R.string.notification_sent_title))
            .setContentText(target.displayName)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(60000)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // notification permission revoked
        }
    }
}
