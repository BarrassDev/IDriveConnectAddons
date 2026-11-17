package io.bimmergestalt.idriveconnectaddons.navmirror

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.DestinationParser
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.GeocoderSearcher
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.NavNotificationListener
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.UrlRedirector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainController(val activity: AppCompatActivity, val viewModel: MainModel) {

    companion object {
        const val REQUEST_POST_NOTIFICATIONS = 60
    }

    private fun tryOpenActivity(intent: Intent): Boolean {
        if (activity.packageManager.resolveActivity(intent, 0) != null) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
            } catch (e: IllegalArgumentException) {}
        }
        return false
    }

    fun openApplicationPermissions(packageName: String) {
        run {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.data = Uri.fromParts("package", packageName, null)
            if (tryOpenActivity(intent)) return
        }
        run {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (tryOpenActivity(intent)) return
        }
    }

    fun promptNotificationAccess() {
        if (!tryOpenActivity(NavNotificationListener.settingsIntent())) {
            openApplicationPermissions(activity.packageName)
        }
    }

    fun promptPostNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.applicationInfo.targetSdkVersion >= 33) {       // Android 13+
            ActivityCompat.requestPermissions(activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS)
        } else {
            openApplicationPermissions(activity.packageName)
        }
    }

    fun setAutoSend(enabled: Boolean) {
        NavMirror.settings.autoSend = enabled
    }

    fun setGeocodeHints(enabled: Boolean) {
        NavMirror.settings.geocodeHints = enabled
    }

    private fun describe(result: NavMirror.SendResult, destination: io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination?): String {
        return when (result) {
            NavMirror.SendResult.SENT -> activity.getString(R.string.toast_sent, destination?.displayName ?: "")
            NavMirror.SendResult.NO_DESTINATION -> activity.getString(R.string.toast_no_destination)
            NavMirror.SendResult.CAR_NOT_READY -> activity.getString(R.string.toast_car_not_ready)
            NavMirror.SendResult.FAILED -> activity.getString(R.string.lbl_car_no_navigate)
        }
    }

    fun sendNow() {
        val message = describe(NavMirror.sendToCar(), NavMirror.destination.value)
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /** Re-reads the phone navigation, captures its destination if possible, and sends it to the car */
    fun refreshAndSend() {
        NavMirror.refreshAndSend { result, destination ->
            Toast.makeText(activity, describe(result, destination), Toast.LENGTH_SHORT).show()
        }
    }

    fun clearDestination() {
        NavMirror.clearDestination()
    }

    /** Parses a link or address typed into the phone UI */
    fun useLink() {
        val text = viewModel.linkInput.value?.trim()
        if (text.isNullOrBlank()) return
        val parser = DestinationParser(GeocoderSearcher(activity.applicationContext), UrlRedirector())
        activity.lifecycleScope.launch {
            val destination = withContext(Dispatchers.IO) {
                try {
                    parser.parse(text)
                } catch (e: Exception) {
                    null
                }
            }
            if (destination == null) {
                Toast.makeText(activity, activity.getString(R.string.toast_parse_failed, text.take(80)), Toast.LENGTH_LONG).show()
            } else {
                NavMirror.onDestinationCaptured(destination, NavMirror.CaptureSource.LINK)
                viewModel.linkInput.value = ""
                Toast.makeText(activity, activity.getString(R.string.toast_captured, destination.displayName), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
