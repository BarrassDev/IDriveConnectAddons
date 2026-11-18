package io.bimmergestalt.idriveconnectaddons.speedcams.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon

/** 1 Hz phone GPS updates, delivered on the given looper */
class PhoneLocation(private val context: Context, private val onFix: (LocationFix) -> Unit) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var running = false
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location.toFix())
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(looper: Looper) {
        if (running) return
        if (!hasPermission()) {
            Log.w(TAG, "No location permission, relying on the car's GPS")
            return
        }
        try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onFix(it.toFix()) }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, looper)
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                // a quick rough fix while GPS warms up, ignored once GPS fixes arrive
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, listener, looper)
            }
            running = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start phone location", e)
        }
    }

    fun stop() {
        if (!running) return
        manager.removeUpdates(listener)
        running = false
    }

    private fun Location.toFix() = LocationFix(
        LatLon(latitude, longitude),
        if (hasSpeed()) speed.toDouble() else null,
        if (hasBearing()) bearing.toDouble() else null,
        if (hasAccuracy()) accuracy else null,
        System.currentTimeMillis(),
        FixSource.PHONE,
    )
}
