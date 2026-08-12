package com.r1.hermes

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JSON-RPC client for the Hermes gateway over `/api/ws`.
 *
 * The wire protocol is newline-delimited JSON — identical to the stdio
 * transport the Ink TUI uses. Three consequences shape this class:
 *
 * - **One WS frame can hold several messages.** The server coalesces
 *   high-frequency `*.delta` events into ~33 ms batches, so every inbound
 *   payload is split on newlines before parsing.
 * - **Events are notifications**, shaped
 *   `{"method":"event","params":{"type":…,"payload":…}}`; replies carry an
 *   `id` with `result` or `error`.
 * - **The socket will drop** — this is a handheld on Wi-Fi — so reconnect is
 *   built in, and the owner re-issues `session.resume` from [Listener.onReady].
 */
class HermesClient(private val settings: Settings) {

    companion object {
        private const val TAG = "R1Hermes"
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 60_000L

        /** Close codes the gateway uses to reject an upgrade. */
        private val FATAL_CLOSE_CODES = mapOf(
            4401 to "authentication rejected (session token)",
            4403 to "refused by host/origin guard, or embedded chat disabled",
            4404 to "embedded chat disabled",
            4408 to "client guard",
        )
    }

    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    interface Listener {
        fun onState(state: State, detail: String?)
        /** Fired once per successful `gateway.ready`; re-issue session.resume here. */
        fun onReady(payload: JSONObject)
        fun onEvent(type: String, payload: JSONObject)
        fun onLog(line: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a WS read blocks indefinitely by design
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject?, String?) -> Unit>()

    private var socket: WebSocket? = null
    private var listener: Listener? = null

    @Volatile private var state = State.IDLE
    @Volatile private var wantConnection = false
    @Volatile private var backoffMs = BACKOFF_MIN_MS

    fun setListener(l: Listener?) { listener = l }

    fun currentState(): State = state

    fun connect() {
        wantConnection = true
        backoffMs = BACKOFF_MIN_MS
        openSocket()
    }

    fun disconnect() {
        wantConnection = false
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, "client closing")
        socket = null
        failAllPending("disconnected")
        setState(State.IDLE, null)
    }

    private fun openSocket() {
        if (!settings.isConfigured) {
            setState(State.FAILED, "base URL or session token not set")
            return
        }

        setState(if (state == State.IDLE) State.CONNECTING else State.RECONNECTING, null)

        val builder = Request.Builder().url(settings.webSocketUrl())
        // Native clients can set headers on the upgrade, which is how the
        // Cloudflare Access service token reaches the edge.
        settings.accessHeaders().forEach { (k, v) -> builder.header(k, v) }

        socket = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                backoffMs = BACKOFF_MIN_MS
                setState(State.CONNECTED, null)
                log("ws open (${response.code})")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // A single frame may carry a coalesced batch.
                text.split('\n').forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) dispatch(trimmed)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                log("ws closing $code ${reason.ifEmpty { "-" }}")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDrop(code, reason, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDrop(response?.code ?: -1, response?.message ?: "", t)
            }
        })
    }

    private fun handleDrop(code: Int, reason: String, t: Throwable?) {
        failAllPending("connection lost")

        val fatal = FATAL_CLOSE_CODES[code]
        val detail = when {
            fatal != null -> "$code: $fatal"
            // 403 on the upgrade is Access, not Hermes — the token never reached the gateway.
            code == 403 -> "403: rejected by Cloudflare Access (service token)"
            t != null -> t.message ?: t.javaClass.simpleName
            reason.isNotEmpty() -> "$code: $reason"
            else -> "closed ($code)"
        }
        log("ws down — $detail")

        if (fatal != null || !wantConnection) {
            setState(if (wantConnection) State.FAILED else State.IDLE, detail)
            return
        }

        setState(State.RECONNECTING, detail)
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        main.postDelayed({ if (wantConnection) openSocket() }, delay)
    }

    private fun dispatch(line: String) {
        val obj = try {
            JSONObject(line)
        } catch (e: Exception) {
            log("unparseable frame: ${line.take(120)}")
            return
        }

        if (obj.has("id") && !obj.isNull("id") && !obj.has("method")) {
            val id = obj.optInt("id", -1)
            val cb = pending.remove(id) ?: return
            val error = obj.optJSONObject("error")
            if (error != null) {
                cb(null, error.optString("message", "rpc error ${error.optInt("code")}"))
            } else {
                cb(obj.optJSONObject("result") ?: JSONObject(), null)
            }
            return
        }

        if (obj.optString("method") == "event") {
            val params = obj.optJSONObject("params") ?: return
            val type = params.optString("type")
            val payload = params.optJSONObject("payload") ?: JSONObject()
            if (type == "gateway.ready") {
                main.post { listener?.onReady(payload) }
            }
            main.post { listener?.onEvent(type, payload) }
        }
    }

    /**
     * Issues a JSON-RPC request. [callback] runs on the main thread with either
     * a result object or an error string, exactly once.
     */
    fun call(
        method: String,
        params: JSONObject = JSONObject(),
        callback: (JSONObject?, String?) -> Unit,
    ) {
        val ws = socket
        if (ws == null || state != State.CONNECTED) {
            main.post { callback(null, "not connected") }
            return
        }

        val id = nextId.getAndIncrement()
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        var settled = false
        val once: (JSONObject?, String?) -> Unit = { result, error ->
            if (!settled) {
                settled = true
                main.post { callback(result, error) }
            }
        }
        pending[id] = once
        main.postDelayed({
            if (pending.remove(id) != null) once(null, "timed out after ${CALL_TIMEOUT_MS / 1000}s")
        }, CALL_TIMEOUT_MS)

        if (!ws.send(request.toString() + "\n")) {
            pending.remove(id)
            once(null, "send failed (socket full or closed)")
        }
    }

    private fun failAllPending(reason: String) {
        val snapshot = pending.keys.toList()
        snapshot.forEach { id -> pending.remove(id)?.invoke(null, reason) }
    }

    private fun setState(next: State, detail: String?) {
        state = next
        main.post { listener?.onState(next, detail) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        main.post { listener?.onLog(line) }
    }
}
