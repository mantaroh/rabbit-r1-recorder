package com.r1.audioprobe

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
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
    /**
     * Read at upload time rather than captured once: the recorder negotiates
     * its rate against the device and may not get the one it asked for, and a
     * segment mislabelled here is a segment a future decoder resamples wrongly.
     */
    private val sampleRate: () -> Int,
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

        val pending = dir.listFiles { f -> f.isFile && isSegment(f.name) }
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

        val opus = file.extension.equals("opus", ignoreCase = true)
        val url = buildString {
            append(settings.baseUrl)
            append("/v1/segments/")
            append(segmentId)
            append("?device_id=").append(enc(settings.deviceId))
            append("&started_at=").append(enc(startedAt))
            append("&kind=lifelog&sample_rate=").append(sampleRate())
            append("&codec=").append(if (opus) "opus" else "wav")
            append("&language=").append(enc(settings.language))
            // Lets the server skip Whisper on a silent minute. Absent for
            // segments written before this existed, which the server then
            // transcribes unconditionally rather than guessing.
            envelopeFile(file).takeIf { it.exists() }?.let {
                append("&rms=").append(enc(it.readText().trim()))
            }
            sha256(file)?.let { append("&sha256=").append(it) }
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", if (opus) "audio/ogg" else "audio/wav")
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
                    envelopeFile(file).delete()
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

    /**
     * Uploads a question and waits for its text.
     *
     * Uses `sync=1` so transcription happens in the request instead of behind
     * whatever backlog is queued — a question is worthless by the time a
     * Wi-Fi-only lifelog queue drains. Ignores the unmetered rule for the same
     * reason: this is the one upload worth paying cellular data for.
     */
    fun uploadQuery(
        file: File,
        segmentId: String,
        startedAtMs: Long,
        kind: String = "query",
    ): String? {
        if (!settings.isConfigured) return null

        val url = buildString {
            append(settings.baseUrl)
            append("/v1/segments/").append(segmentId)
            append("?device_id=").append(enc(settings.deviceId))
            append("&started_at=").append(enc(stamp.format(Date(startedAtMs))))
            append("&kind=").append(kind)
            append("&codec=wav&sample_rate=").append(sampleRate())
            append("&language=").append(enc(settings.language))
            sha256(file)?.let { append("&sha256=").append(it) }
            append("&sync=1")
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
            if (code !in 200..299) {
                metrics.write("query_upload_fail", mapOf("code" to code))
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // Small, fixed shape — not worth a JSON parser on this path.
            Regex("\"transcript\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", " ")
                ?.replace("\\\\", "\\")
                ?.trim()
        } catch (t: Throwable) {
            metrics.write("query_upload_fail", mapOf("error" to (t.message ?: "?").take(200)))
            null
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

    /**
     * Digest of the bytes as they sit on disk, so the server can reject a
     * segment that changed on the way rather than storing the damage.
     *
     * Read separately from the upload rather than hashed while streaming: the
     * point is to describe what is *on disk*, and a single extra pass over
     * 270 KB is nothing next to the round trip that follows.
     */
    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Sidecar holding the per-second loudness written when the segment closed. */
    private fun envelopeFile(audio: File) =
        File(audio.parentFile, audio.nameWithoutExtension + ".rms")

    private fun isSegment(name: String) =
        name.endsWith(".wav", true) || name.endsWith(".opus", true)

    fun queueDepth(dir: File, skip: File?): Int =
        dir.listFiles { f -> f.isFile && isSegment(f.name) }
            .orEmpty()
            .count { it != skip }
}
