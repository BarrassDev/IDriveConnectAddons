package io.bimmergestalt.idriveconnectaddons.navmirror.nav

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Pulls every piece of text out of a notification
 * Google Maps uses a custom RemoteViews layout with empty extras, so the layout is inflated
 * (with the posting app's resources) and its TextViews are read by resource name
 */
object NotificationTextExtractor {
    private const val TAG = "NavMirrorExtract"

    /** Must be called from the main thread, because it inflates views */
    fun extract(context: Context, sbn: StatusBarNotification): NotificationTexts {
        val notification = sbn.notification
        val extras = notification.extras
        fun str(key: String): String? = try {
            extras?.getCharSequence(key)?.toString()
        } catch (e: Exception) {
            null
        }
        val lines = try {
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
        } catch (e: Exception) {
            null
        } ?: emptyList()

        val viewTexts = ArrayList<Pair<String, String>>()
        try {
            val views = inflate(context, notification)
            if (views != null) {
                collectTexts(views, viewTexts)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Unable to inflate notification layout from ${sbn.packageName}: $e")
        }

        return NotificationTexts(
            title = str(Notification.EXTRA_TITLE),
            text = str(Notification.EXTRA_TEXT),
            subText = str(Notification.EXTRA_SUB_TEXT),
            bigText = str(Notification.EXTRA_BIG_TEXT),
            infoText = str(Notification.EXTRA_INFO_TEXT),
            lines = lines,
            viewTexts = viewTexts,
        )
    }

    private fun inflate(context: Context, notification: Notification): View? {
        val builder = Notification.Builder.recoverBuilder(context, notification)
        val remoteViews = tryCreate { builder.createBigContentView() }
            ?: tryCreate { builder.createContentView() }
            ?: return null
        return remoteViews.apply(context, FrameLayout(context))
    }

    private inline fun tryCreate(block: () -> RemoteViews?): RemoteViews? {
        return try {
            block()
        } catch (e: Exception) {
            null
        }
    }

    private fun collectTexts(view: View, out: MutableList<Pair<String, String>>) {
        // action buttons ("Switch off", "Exit navigation") are not navigation data
        if (view is android.widget.Button) return
        if (view is TextView) {
            val text = view.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                out.add(entryName(view) to text)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTexts(view.getChildAt(i), out)
            }
        }
    }

    private fun entryName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (e: Resources.NotFoundException) {
            ""
        }
    }
}
