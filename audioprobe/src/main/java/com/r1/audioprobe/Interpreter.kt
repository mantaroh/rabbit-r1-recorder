package com.r1.audioprobe

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live interpretation, through OpenAI's realtime translation model.
 *
 * **Simultaneous, not consecutive, because that is what the API is.** The whole
 * event surface is four messages in and three out:
 *
 *   in    session.output_audio.delta, session.output_transcript.delta,
 *         session.input_transcript.delta, session.closed
 *   out   session.update, session.input_audio_buffer.append, session.close
 *
 * There is no commit, no response.create, no turn-started or turn-done. The
 * model is not taking turns: audio goes in continuously and translated audio
 * comes out continuously, and it decides for itself where sentences are.
 *
 * An earlier version of this file did not believe that. It gated the input on a
 * local voice-activity detector, muted the microphone while the speaker was
 * busy, tracked the playback head to decide when to unmute, and drove a
 * listening/speaking state machine off the result. Every one of those was an
 * invention, each one broke the thing the model needed — the pauses it reads
 * sentence boundaries from — and the last of them could wedge the input shut
 * for good. None of it is here now.
 *
 * Audio goes from this device straight to OpenAI, because interpretation is
 * only usable if the reply lands while the sentence is still in the room and an
 * extra hop spends exactly that budget. The Worker holds the API key and hands
 * out an `ek_…` that expires, because every app on this device can open a root
 * shell and a key kept here would not be a secret.
 *
 * What is *not* solved: the translation plays out of the speaker so that both
 * people can hear it, and the microphone hears it too. The session is asked for
 * far-field noise reduction, which is what it is for, and beyond that this
 * relies on the model not finding its own output worth translating. An earphone
 * removes the question entirely and also removes the second listener, which is
 * the point of the feature.
 */
class Interpreter(
    private val context: Context,
    private val settings: UploadSettings,
    private val onState: (State) -> Unit,
    private val onTranscript: (Line) -> Unit,
) {

    /**
     * A line of text from the session. Which side it came from matters on
     * screen: one is a check that the room was heard correctly, the other is
     * what the other person is listening to.
     */
    sealed interface Line { val text: String }
    @JvmInline value class Heard(override val text: String) : Line
    @JvmInline value class Translated(override val text: String) : Line

    /**
     * Four states, and none of them is about whose turn it is.
     *
     * A simultaneous interpreter is listening and speaking at the same time, so
     * there is nothing to report there and the previous attempt to do so is
     * what got stuck.
     */
    enum class State { IDLE, CONNECTING, RUNNING, CLOSED, FAILED }

    companion object {
        private const val TAG = "R1AudioProbe"
    }

    private val http = Realtime.http()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "interpret").apply { isDaemon = true }
    }

    /** The speaker, the volume, and the thread that writes to both. */
    private val speaker = Speaker(context)

    /** Buffered between the capture thread and the socket; see [feed]. */
    private val downmix = Downmix { chunk -> worker.execute { send(chunk) } }

    private val running = AtomicBoolean(false)
    private var socket: WebSocket? = null

    @Volatile private var state = State.IDLE
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val currentState: State get() = state

    // ---------------------------------------------------------- lifecycle ---

    fun start(target: String) {
        if (!running.compareAndSet(false, true)) return
        move(State.CONNECTING)
        worker.execute {
            val session = runCatching { mint(target) }.getOrNull()
            if (session == null) {
                move(State.FAILED)
                running.set(false)
                return@execute
            }
            openSocket(session, target)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        RecorderService.audioTap = null
        // The documented way to end one of these: it also asks for whatever is
        // still in flight to be flushed.
        runCatching { socket?.send(JSONObject().put("type", "session.close").toString()) }
        runCatching { socket?.close(1000, "done") }
        socket = null
        speaker.stop()
        move(State.IDLE)
    }

    /** The Worker mints the credential; this device never sees the API key. */
    private fun mint(target: String): JSONObject? {
        val request = Request.Builder()
            .url(settings.baseUrl + "/v1/interpret/session?target=" + target)
            .post(okhttp3.internal.EMPTY_REQUEST)
            .apply {
                addHeader("Authorization", "Bearer " + settings.bearer)
                if (settings.accessClientId.isNotEmpty()) {
                    addHeader("CF-Access-Client-Id", settings.accessClientId)
                    addHeader("CF-Access-Client-Secret", settings.accessClientSecret)
                }
            }
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "interpret mint failed ${response.code}: ${body.take(200)}")
                return null
            }
            val json = JSONObject(body)
            return if (json.optString("client_secret").isNotEmpty()) json else null
        }
    }

    private fun openSocket(session: JSONObject, target: String) {
        val request = Request.Builder()
            .url(session.getString("url"))
            .addHeader("Authorization", "Bearer " + session.getString("client_secret"))
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(
                    JSONObject().apply {
                        put("type", "session.update")
                        put(
                            "session",
                            JSONObject().put(
                                "audio",
                                JSONObject().put("output", JSONObject().put("language", target)),
                            ),
                        )
                    }.toString(),
                )
                speaker.start()
                RecorderService.audioTap = { buffer, count -> feed(buffer, count) }
                move(State.RUNNING)
            }

            override fun onMessage(ws: WebSocket, text: String) = handle(text)

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "interpret socket failed", t)
                RecorderService.audioTap = null
                move(State.FAILED)
                running.set(false)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                RecorderService.audioTap = null
                if (running.get()) move(State.CLOSED)
            }
        })
    }

    // ------------------------------------------------------------- events ---

    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = message.optString("type")
        val delta = message.optString("delta")

        when {
            type == "session.output_audio.delta" && delta.isNotEmpty() ->
                speaker.play(Base64.decode(delta, Base64.NO_WRAP))

            type == "session.input_transcript.delta" && delta.isNotEmpty() ->
                onTranscript(Heard(delta))

            type == "session.output_transcript.delta" && delta.isNotEmpty() ->
                onTranscript(Translated(delta))

            type == "session.closed" -> {
                RecorderService.audioTap = null
                move(State.CLOSED)
            }

            type == "error" -> Log.w(TAG, "interpret error: " + message.opt("error"))

            // Once each. Two event names in this file were wrong before a
            // running session said so, and both were found this way.
            else -> if (seen.add(type)) Log.i(TAG, "interpret event: $type")
        }
    }

    // -------------------------------------------------------------- audio ---

    /**
     * Called on the capture thread with interleaved 48 kHz stereo. The downmix
     * and the exact 2:1 decimation live in [Downmix], shared with the other
     * live session on this device; what is left here is the guard.
     */
    private fun feed(buffer: ShortArray, count: Int) {
        if (!running.get()) return
        downmix.feed(buffer, count)
    }

    private fun send(samples: ShortArray) {
        runCatching {
            socket?.send(
                JSONObject().apply {
                    put("type", "session.input_audio_buffer.append")
                    put("audio", Base64.encodeToString(samples.toPcm16(), Base64.NO_WRAP))
                }.toString(),
            )
        }
    }

    private fun move(next: State) {
        if (state == next) return
        state = next
        onState(next)
    }
}
