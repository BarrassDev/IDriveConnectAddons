package io.bimmergestalt.idriveconnectaddons.navmirror

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import io.bimmergestalt.idriveconnectaddons.lib.LiveDataHelpers.combine
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.NavNotificationListener

/** Like LiveDataHelpers.map, but also maps null values */
private fun <T, R> LiveData<T>.mapAll(block: (T?) -> R): LiveData<R> {
    val result = MediatorLiveData<R>()
    result.addSource(this) { result.value = block(it) }
    return result
}

class MainModel(app: Application): AndroidViewModel(app) {
    val notificationAccess = NavMirror.notificationAccess
    val notificationPermission = MutableLiveData(false)
    val autoSend = MutableLiveData(NavMirror.settings.autoSend)
    val geocodeHints = MutableLiveData(NavMirror.settings.geocodeHints)
    val linkInput = MutableLiveData("")

    val carStatusText: LiveData<Context.() -> String> = NavMirror.carStatus.combine(NavMirror.carGuidanceActive) { status, navigating ->
        {
            val base = when (status) {
                NavMirror.CarStatus.DISCONNECTED -> getString(R.string.lbl_car_disconnected)
                NavMirror.CarStatus.CONNECTING -> getString(R.string.lbl_car_connected)
                NavMirror.CarStatus.READY -> getString(R.string.lbl_car_ready)
                NavMirror.CarStatus.NO_NAVIGATE -> getString(R.string.lbl_car_no_navigate)
            }
            if (navigating) "$base\n${getString(R.string.lbl_car_navigating)}" else base
        }
    }

    val phoneNavText: LiveData<Context.() -> String> = NavMirror.phoneNav.mapAll { nav ->
        if (nav != null && nav.navigating) {
            { listOf(nav.source.label, nav.nextLine, nav.etaLine, nav.destinationHint ?: "").filter { it.isNotBlank() }.joinToString("\n") }
        } else {
            { getString(R.string.lbl_phone_nav_inactive) }
        }
    }

    val destinationText: LiveData<Context.() -> String> = NavMirror.destination.mapAll { target ->
        if (target != null) {
            { target.displayName }
        } else {
            { getString(R.string.lbl_none) }
        }
    }
    val hasDestination: LiveData<Boolean> = NavMirror.destination.mapAll { it != null }

    val lastSentText: LiveData<Context.() -> String> = NavMirror.lastSent.mapAll { target ->
        if (target != null) {
            { target.displayName }
        } else {
            { getString(R.string.lbl_none) }
        }
    }

    fun update(context: Context) {
        notificationPermission.value = Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationAccess.value = NavNotificationListener.isEnabled(context)
        autoSend.value = NavMirror.settings.autoSend
        geocodeHints.value = NavMirror.settings.geocodeHints
    }
}
