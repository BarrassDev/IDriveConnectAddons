package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Overpass API client that tries a second public mirror if the first one is busy */
object Overpass {
    const val USER_AGENT = "SpeedCams-IDriveConnectAddon/1.0"
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    @Throws(IOException::class)
    fun query(query: String, timeoutMs: Int = 60_000): String {
        var lastError: IOException? = null
        for (endpoint in ENDPOINTS) {
            try {
                return post(endpoint, query, timeoutMs)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No Overpass endpoint")
    }

    private fun post(endpoint: String, query: String, timeoutMs: Int): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw IOException("Overpass $endpoint returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
