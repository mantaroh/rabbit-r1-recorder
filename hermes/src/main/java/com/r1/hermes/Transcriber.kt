package com.r1.hermes

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text via the Hermes host, which holds the provider key and picks
 * the model from the profile's `stt` config (`r1` → OpenAI `gpt-transcribe`).
 *
 * The R1 never holds an OpenAI credential: on CarrotOS any installed app can
 * read another's files through the root shell on :1337, so a billable key on
 * the device would be readable by any other APK.
 */
class Transcriber(private val settings: Settings) {

    companion object {
        /** The endpoint rejects anything larger. */
        const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** An empty transcript is success, not failure — it means silence. */
    data class Result(val transcript: String, val provider: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // a cold provider round-trip is slow
        .build()

    private val main = Handler(Looper.getMainLooper())

    fun transcribe(
        audio: File,
        mimeType: String = "audio/mp4",
        callback: (Result?, String?) -> Unit,
    ) {
        Thread({
            val outcome = runCatching { post(audio, mimeType) }
            main.post {
                outcome.fold(
                    onSuccess = { callback(it, null) },
                    onFailure = { callback(null, it.message ?: it.javaClass.simpleName) },
                )
            }
        }, "hermes-transcribe").start()
    }

    private fun post(audio: File, mimeType: String): Result {
        val bytes = audio.readBytes()
        if (bytes.isEmpty()) throw IllegalArgumentException("recording is empty")
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw IllegalArgumentException("recording is ${bytes.size / 1024 / 1024} MiB, limit is 25 MiB")
        }

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = JSONObject()
            .put("data_url", "data:$mimeType;base64,$encoded")
            .put("mime_type", mimeType)
            .toString()
            .toRequestBody(JSON)

        // profile is what selects gpt-transcribe; omitting it silently falls
        // back to the launch profile, which still runs local whisper.
        val builder = Request.Builder()
            .url(settings.httpUrl("/api/audio/transcribe", "profile=" + settings.profile))
            .post(body)
            .header("X-Hermes-Session-Token", settings.sessionToken)
        settings.accessHeaders().forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(describeFailure(response.code, text))
            }
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("detail", "transcription failed"))
            }
            // Hermes reports no provider on the empty-transcript path. org.json
            // turns a JSON null into the literal string "null" via optString,
            // so check isNull first rather than shipping "null" to the UI.
            return Result(
                transcript = json.optString("transcript", ""),
                provider = if (json.isNull("provider")) null
                else json.optString("provider").ifEmpty { null },
            )
        }
    }

    /** Each hop fails with its own status; saying which one saves a lot of guessing. */
    private fun describeFailure(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
        return when (code) {
            403 -> "403 — blocked by Cloudflare Access; check the service token"
            401 -> "401 — Hermes rejected the session token"
            400 -> if (detail.contains("Host", ignoreCase = true)) {
                "400 — Host header rejected; set the tunnel's HTTP Host Header to localhost"
            } else {
                "400 — $detail"
            }
            413 -> "413 — recording exceeds the 25 MiB limit"
            502 -> "502 — tunnel reached Cloudflare but hermes serve is not running"
            else -> "$code — ${detail.ifEmpty { body.take(160) }}"
        }
    }
}
