package com.r1.hermes

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The small REST surface the client needs outside the WebSocket.
 *
 * `/api/health` is on the gateway's public allowlist, so it answers without a
 * session token. That makes it the one probe that can separate "the edge and
 * the tunnel are fine" from "the credentials are wrong" — every hop in this
 * deployment fails with its own status code.
 */
class HermesHttp(private val settings: Settings) {

    data class Health(val ok: Boolean, val version: String?, val authRequired: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    fun health(callback: (Health?, String?) -> Unit) {
        Thread({
            val outcome = runCatching {
                val builder = Request.Builder().url(settings.httpUrl("/api/health")).get()
                settings.accessHeaders().forEach { (k, v) -> builder.header(k, v) }

                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException(explain(response.code, text))
                    val json = JSONObject(text)
                    Health(
                        ok = json.optBoolean("ok"),
                        version = json.optString("version").ifEmpty { null },
                        authRequired = json.optBoolean("auth_required"),
                    )
                }
            }
            main.post {
                outcome.fold(
                    onSuccess = { callback(it, null) },
                    onFailure = { callback(null, it.message ?: it.javaClass.simpleName) },
                )
            }
        }, "hermes-health").start()
    }

    private fun explain(code: Int, body: String): String = when (code) {
        403 -> "403 — Cloudflare Access rejected the service token"
        302 -> "302 — Access redirected to login; the policy action must be Service Auth, not Allow"
        502 -> "502 — through the tunnel, but nothing is listening on the origin port"
        530, 1033 -> "$code — the tunnel is not connected"
        400 -> "400 — ${JSONObject(body).optString("detail", body.take(120))}"
        404 -> "404 — reached Cloudflare but no route matches this hostname"
        else -> "$code — ${body.take(160)}"
    }
}
