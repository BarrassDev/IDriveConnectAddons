package io.bimmergestalt.idriveconnectaddons.speedcams.alert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.bimmergestalt.idriveconnectaddons.speedcams.MainActivity
import io.bimmergestalt.idriveconnectaddons.speedcams.R

/** A heads-up notification on the phone that removes itself after a few seconds */
class PhoneNotifier(private val context: Context) {
    companion object {
        const val CHANNEL = "camera_alerts"
        const val ID = 1
        const val TIMEOUT_MS = 12_000L
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alerts_description)
                // the sound comes from AlertSounder, so it can duck music and use the navigation audio route
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun show(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, notification)
        } catch (e: SecurityException) {}
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
