package io.bimmergestalt.idriveconnectaddons.navmirror.nav

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Resolves short links (maps.app.goo.gl, waze.com/ul/h...) to their full URL, one hop at a time */
open class UrlRedirector {
    open fun tryRedirect(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) NavMirror")
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (code in 300..399 && !location.isNullOrBlank()) {
                // resolve relative redirects against the original url
                URL(URL(url), location).toString()
            } else {
                null
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
