package com.r1.audioprobe

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ships finished segments to the lifelog Worker.
 *
 * The contract is deliberately blunt about durability: a local file is deleted
 * only after the server has acknowledged it. The segment id is the filename,
 * which is also the R2 object key, so a retry after a half-finished upload
 * overwrites itself instead of duplicating a transcript.
 *
 * No HTTP library — `HttpURLConnection` streams a file body fine, and this
 * module exists to measure the platform, not to carry dependencies.
 */
/**
 * [network] is supplied by the caller rather than read from
 * `ConnectivityManager.getActiveNetwork()`. On the R1 that call returns null
 * even with Wi-Fi connected and validated — the documented behaviour is that it
 * yields null both when there is no default network *and* when the app is not
 * allowed to use it, and it cannot be told which. The `NetworkCallback` the
 * service already registers reports the same link correctly, so state comes
 * from there.
 */
class Uploader(
    private val settings: UploadSettings,
    private val metrics: Metrics,
    private val network: () -> NetworkState,
) {

    /** Last known link state, as reported by the service's NetworkCallback. */
    data class NetworkState(val available: Boolean, val unmetered: Boolean)

    companion object {
        private const val TAG = "R1AudioProbe"

        /** Matches the retry ladder in the design: quick, then patient. */
        private val BACKOFF_MS = longArrayOf(
            1_000, 5_000, 15_000, 60_000, 300_000, 900_000, 1_800_000,
        )

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
    }

    @Volatile var uploaded = 0; private set
    @Volatile var failures = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastUploadAt = 0L; private set

    private var consecutiveFailures = 0
    private var nextAttemptAt = 0L

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    /** Why an upload pass is not running right now, or null when it may run. */
    fun blockedReason(): String? {
        if (!settings.enabled) return "disabled"
        if (!settings.isConfigured) return "not configured"
        if (System.currentTimeMillis() < nextAttemptAt) return "backoff"

        val state = network()
        if (!state.available) return "offline"
        if (settings.unmeteredOnly && !state.unmetered) return "metered"
        return null
    }

    /**
     * Uploads at most [limit] segments. [skip] is the file currently being
     * written — uploading a partial WAV would store a truncated segment under
     * an id that then looks complete.
     */
    fun pump(dir: File, skip: File?, limit: Int = 4) {
        if (blockedReason() != null) return

        val pending = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            .orEmpty()
            .filter { it != skip && it.length() > 44 }
            .sortedBy { it.name }
            .take(limit)

        for (file in pending) {
            if (blockedReason() != null) return
            if (!upload(file)) return // one failure backs the whole pass off
        }
    }

    private fun upload(file: File): Boolean {
        val segmentId = file.nameWithoutExtension
        // The recorder names files seg_yyyyMMdd_HHmmss; that instant is the
        // start of the segment and must survive an upload deferred by days.
        val startedAt = startedAtFrom(segmentId) ?: run {
            metrics.write("upload_skip", mapOf("file" to file.name, "why" to "unparsable name"))
            return true
        }

        val url = buildString {
            append(settings.baseUrl)
            append("/v1/segments/")
            append(segmentId)
            append("?device_id=").append(enc(settings.deviceId))
            append("&started_at=").append(enc(startedAt))
            append("&kind=lifelog&codec=wav&sample_rate=16000")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "audio/wav")
                setRequestProperty("Authorization", "Bearer " + settings.bearer)
                if (settings.accessClientId.isNotEmpty()) {
                    setRequestProperty("CF-Access-Client-Id", settings.accessClientId)
                    setRequestProperty("CF-Access-Client-Secret", settings.accessClientSecret)
                }
                setFixedLengthStreamingMode(file.length())
            }

            FileInputStream(file).use { input ->
                connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
            }

            val code = connection.responseCode
            when {
                code in 200..299 -> {
                    // Acknowledged: only now is it safe to lose the local copy.
                    val bytes = file.length()
                    file.delete()
                    uploaded += 1
                    lastUploadAt = System.currentTimeMillis()
                    consecutiveFailures = 0
                    nextAttemptAt = 0
                    lastError = null
                    metrics.write(
                        "upload_ok",
                        mapOf("segment" to segmentId, "bytes" to bytes, "code" to code),
                    )
                    true
                }

                // 4xx other than 429 will not fix itself; keep the file but do
                // not hammer the endpoint over it.
                code in 400..499 && code != 429 -> {
                    failWith("$code ${connection.responseMessage}", segmentId, fatal = true)
                    false
                }

                else -> {
                    failWith("$code ${connection.responseMessage}", segmentId, fatal = false)
                    false
                }
            }
        } catch (t: Throwable) {
            failWith(t.message ?: t.javaClass.simpleName, segmentId, fatal = false)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun failWith(reason: String, segmentId: String, fatal: Boolean) {
        failures += 1
        lastError = reason
        val index = consecutiveFailures.coerceAtMost(BACKOFF_MS.lastIndex)
        val delay = BACKOFF_MS[index]
        consecutiveFailures += 1
        nextAttemptAt = System.currentTimeMillis() + delay
        Log.w(TAG, "upload failed $segmentId: $reason (retry in ${delay / 1000}s)")
        metrics.write(
            "upload_fail",
            mapOf(
                "segment" to segmentId,
                "error" to reason.take(200),
                "retry_in_s" to delay / 1000,
                "client_error" to fatal,
            ),
        )
    }

    private fun startedAtFrom(segmentId: String): String? {
        val match = Regex("""^seg_(\d{8})_(\d{6})$""").find(segmentId) ?: return null
        val parser = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val date = runCatching {
            parser.parse(match.groupValues[1] + match.groupValues[2])
        }.getOrNull() ?: return null
        return stamp.format(date)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun queueDepth(dir: File, skip: File?): Int =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            .orEmpty()
            .count { it != skip }
}
