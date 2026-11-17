package io.bimmergestalt.idriveconnectaddons.navmirror.nav

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.bimmergestalt.idriveconnectaddons.navmirror.NavMirror
import io.bimmergestalt.idriveconnectaddons.navmirror.model.NavSource
import io.bimmergestalt.idriveconnectaddons.navmirror.model.PhoneNavState

/**
 * Watches the ongoing notifications of Google Maps and Waze
 * and reports the parsed navigation state to NavMirror
 */
class NavNotificationListener: NotificationListenerService() {
    companion object {
        const val TAG = "NavMirrorListener"
        const val THROTTLE_MS = 500L

        /** The running listener, for on-demand refreshes from the phone UI */
        @Volatile
        var instance: NavNotificationListener? = null
            private set

        fun isEnabled(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }

        fun settingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<NavSource, Runnable>()
    /** the notification key that most recently reported active navigation, per app */
    private val activeNavigationKeys = HashMap<NavSource, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        instance = this
        NavMirror.notificationAccess.postValue(true)
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        }
        active?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        NavMirror.notificationAccess.postValue(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    /**
     * Re-reads the navigation apps' notifications right now, skipping the throttle
     * Must be called from the main thread
     * @return how many navigation notifications were found
     */
    fun refreshNow(): Int {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return 0
        val seen = HashSet<NavSource>()
        active.forEach { sbn ->
            val source = NavSource.fromPackage(sbn.packageName) ?: return@forEach
            if (!sbn.isOngoing) return@forEach
            pending.remove(source)?.also { handler.removeCallbacks(it) }
            handle(source, sbn)
            seen.add(source)
        }
        // apps that were navigating but no longer show a notification have stopped
        activeNavigationKeys.keys.filter { it !in seen }.forEach { source ->
            activeNavigationKeys.remove(source)
            NavMirror.onPhoneNav(PhoneNavState.inactive(source))
        }
        return seen.size
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val source = NavSource.fromPackage(sbn.packageName) ?: return
        if (!sbn.isOngoing) return

        // navigation apps update their notification many times a second, so coalesce updates
        pending.remove(source)?.also { handler.removeCallbacks(it) }
        val task = Runnable {
            pending.remove(source)
            handle(source, sbn)
        }
        pending[source] = task
        handler.postDelayed(task, THROTTLE_MS)
    }

    private fun handle(source: NavSource, sbn: StatusBarNotification) {
        val texts = NotificationTextExtractor.extract(this, sbn)
        val state = NavNotificationParser.parse(source, texts)
        Log.d(TAG, "Parsed ${sbn.packageName} notification ${sbn.id}: $state")
        val activeKey = activeNavigationKeys[source]
        if (state.navigating) {
            activeNavigationKeys[source] = sbn.key
            NavMirror.onPhoneNav(state)
        } else if (activeKey == null || activeKey == sbn.key) {
            activeNavigationKeys.remove(source)
            NavMirror.onPhoneNav(state)
        }
        // otherwise this is some other ongoing notification from the same app, while navigation is still active
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val source = NavSource.fromPackage(sbn.packageName) ?: return
        pending.remove(source)?.also { handler.removeCallbacks(it) }
        if (activeNavigationKeys[source] == sbn.key) {
            activeNavigationKeys.remove(source)
            NavMirror.onPhoneNav(PhoneNavState.inactive(source))
        }
    }
}
