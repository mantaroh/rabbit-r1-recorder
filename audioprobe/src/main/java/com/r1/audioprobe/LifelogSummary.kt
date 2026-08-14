package com.r1.audioprobe

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * What the archive knows about today, fetched from the Worker.
 *
 * The device could count its own segments, and that was the first instinct,
 * but it would be counting a different thing: a service restarted at noon
 * knows nothing about the morning, and the numbers on the standby screen would
 * quietly disagree with the ones on the web page. The server is where "today"
 * is actually defined, so it is what gets asked.
 *
 * One fetch feeds every screen that needs data. Failures are silent and leave
 * the previous snapshot in place — a standby display is the wrong place to
 * report a network error, and stale numbers are better than a screen that
 * blanks whenever Wi-Fi hiccups.
 */
object LifelogSummary {

    private const val TAG = "R1AudioProbe"
    private const val TIMEOUT_MS = 12_000

    data class Snapshot(
        /** Minutes of audio recorded today. */
        val segments: Int,
        /** Of those, how many carried speech. */
        val withText: Int,
        val photos: Int,
        /** Time of the most recent transcript, "HH:mm". */
        val latestAt: String?,
        val latestText: String?,
    )

    @Volatile var current: Snapshot? = null; private set

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "signage-fetch").apply { isDaemon = true }
    }

    @Volatile private var inFlight = false

    /** Returns immediately; [current] updates when the answer arrives. */
    fun refresh(settings: UploadSettings) {
        if (inFlight || !settings.isConfigured) return
        inFlight = true
        worker.execute {
            try {
                fetch(settings)?.let { current = it }
            } catch (t: Throwable) {
                Log.w(TAG, "signage fetch failed", t)
            } finally {
                inFlight = false
            }
        }
    }

    private fun fetch(settings: UploadSettings): Snapshot? {
        val url = settings.baseUrl.trimEnd('/') +
            "/v1/day?date=" + URLEncoder.encode(todayLocalDate(), "UTF-8")

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
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

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val stats = json.optJSONObject("stats")
            val entries = json.optJSONArray("entries")

            // Latest entry that actually has words in it. Walking backwards
            // because the array is chronological and the interesting end is
            // the recent one.
            var latestAt: String? = null
            var latestText: String? = null
            if (entries != null) {
                for (i in entries.length() - 1 downTo 0) {
                    val entry = entries.optJSONObject(i) ?: continue
                    if (entry.optString("kind") != "audio") continue
                    val text = entry.optString("text").trim()
                    if (text.isEmpty()) continue
                    latestText = text
                    latestAt = entry.optString("at").let { at ->
                        Regex("""T(\d{2}:\d{2})""").find(at)?.groupValues?.get(1)
                    }
                    break
                }
            }

            Snapshot(
                segments = stats?.optInt("segments") ?: 0,
                withText = stats?.optInt("with_text") ?: 0,
                photos = stats?.optInt("photos") ?: 0,
                latestAt = latestAt,
                latestText = latestText,
            )
        } finally {
            connection?.disconnect()
        }
    }
}
