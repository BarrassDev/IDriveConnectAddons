package io.bimmergestalt.idriveconnectaddons.navmirror

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.DestinationParser
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.GeocoderSearcher
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.UrlRedirector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An invisible share target: "Send to car navigation"
 * Accepts shared text and map links from Google Maps, Waze, and any other app
 */
class ShareReceiverActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavMirror.init(applicationContext)

        val query = when (intent?.action) {
            Intent.ACTION_VIEW -> intent?.dataString
            Intent.ACTION_SEND -> intent?.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)
            else -> null
        }
        if (query.isNullOrBlank()) {
            finish()
            return
        }
        Log.i(TAG, "Received shared destination: $query")

        val parser = DestinationParser(GeocoderSearcher(applicationContext), UrlRedirector())
        lifecycleScope.launch {
            val destination = withContext(Dispatchers.IO) {
                try {
                    parser.parse(query)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse shared destination", e)
                    null
                }
            }
            if (destination == null) {
                Toast.makeText(applicationContext, getString(R.string.toast_parse_failed, query.take(80)), Toast.LENGTH_LONG).show()
            } else {
                NavMirror.onDestinationCaptured(destination, NavMirror.CaptureSource.SHARE)
                val message = if (NavMirror.carStatus.value == NavMirror.CarStatus.READY && NavMirror.settings.autoSend) {
                    getString(R.string.toast_sent, destination.displayName)
                } else if (NavMirror.carStatus.value == NavMirror.CarStatus.READY) {
                    getString(R.string.toast_captured, destination.displayName)
                } else {
                    getString(R.string.toast_not_connected)
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
