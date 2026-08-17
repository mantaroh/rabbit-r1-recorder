package com.r1.audioprobe

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The newest headlines from the feed reader.
 *
 * Fetched from the lifelog Worker, which passes the `/v1/feeds` paths through
 * to a second Worker holding the subscriptions. From here that distinction is
 * invisible and deliberately so: the device has one host, one bearer and one
 * Access token, and gaining a feed reader did not add a credential.
 *
 * The list is not the device's to sort. What arrives is already ordered by the
 * only clock that cannot be wrong — when the crawler first saw each item — so
 * this keeps the order it was given.
 */
object Headlines {

    private const val TAG = "R1AudioProbe"
    private const val TIMEOUT_MS = 8_000

    /** More than a 240 dp panel can show, so the screen can choose. */
    private const val WANT = 12

    data class Item(val title: String, val source: String)

    @Volatile
    var items: List<Item> = emptyList()
        private set

    /** Null until the first fetch resolves; false once one has failed. */
    @Volatile
    var reachable: Boolean? = null
        private set

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "headlines").apply { isDaemon = true }
    }

    @Volatile private var inFlight = false

    fun refresh(settings: UploadSettings) {
        if (inFlight) return
        if (!settings.isConfigured) return

        inFlight = true
        worker.execute {
            try {
                val body = get(settings, "/v1/feeds/latest?limit=$WANT")
                if (body == null) {
                    reachable = false
                } else {
                    items = parse(body)
                    reachable = true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "headline fetch failed", t)
                reachable = false
            } finally {
                inFlight = false
            }
        }
    }

    private fun get(settings: UploadSettings, path: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer " + settings.bearer)
                if (settings.accessClientId.isNotEmpty()) {
                    setRequestProperty("CF-Access-Client-Id", settings.accessClientId)
                    setRequestProperty("CF-Access-Client-Secret", settings.accessClientSecret)
                }
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String): List<Item> {
        val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val title = row.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            Item(title, row.optString("feed_title").trim())
        }
    }
}
