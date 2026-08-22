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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The check-in itself: a short spoken exchange over OpenAI's realtime API.
 *
 * The credential is already minted and the instructions are already baked into
 * it — see the Worker's `nudge.ts` — so there is nothing to negotiate here. The
 * session connects, asks the model to speak first, and listens.
 *
 * **The device does not decide when it is over; it decides when it has gone on
 * too long.** The model is told to ask one or two things and then end, but a
 * WebSocket has no way for it to hang up, so three things close this: an answer
 * followed by the reply to it, a stretch with nothing happening at all, and the
 * ceiling the Worker sent down. A billed microphone session with no upper bound
 * is the kind of thing discovered on an invoice.
 *
 * Event names are matched by suffix rather than in full. Two of them in the
 * interpreter were wrong until a live session said so, and the realtime API has
 * renamed this family once already — `response.audio.delta` became
 * `response.output_audio.delta` — so anything ending the right way is treated
 * as the thing it looks like, and everything unrecognised is logged once.
 */
class NudgeSession(
    private val context: Context,
    private val url: String,
    private val clientSecret: String,
    private val maxSeconds: Int,
    private val onState: (State) -> Unit,
    private val onLine: (Line) -> Unit,
) {

    /** Who said it. The screen shows both, at different weights. */
    sealed interface Line { val text: String }
    @JvmInline value class Said(override val text: String) : Line
    @JvmInline value class Heard(override val text: String) : Line

    enum class State { CONNECTING, RUNNING, CLOSED, FAILED }

    private companion object {
        const val TAG = "R1AudioProbe"

        /**
         * Nothing at all for this long and the session closes.
         *
         * The case it exists for is a room that answered by walking away: the
         * model has finished speaking, nobody replies, and without this the
         * socket stays open and billed until the ceiling.
         */
        const val IDLE_TIMEOUT_MS = 45_000L

        /**
         * How long to let the speaker drain after the last reply.
         *
         * The audio deltas stop arriving when the response completes, but up to
         * a second of them is still inside the AudioTrack buffer. Closing on
         * the event itself cuts the last word off.
         */
        const val DRAIN_MS = 2_000L
    }

    private val http = Realtime.http()
    private val speaker = Speaker(context)
    private val running = AtomicBoolean(false)
    private var socket: WebSocket? = null

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nudge-send").apply { isDaemon = true }
    }
    private val clock: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nudge-clock").apply { isDaemon = true }
    }

    private val downmix = Downmix { chunk -> worker.execute { send(chunk) } }
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile private var lastEventAt = 0L

    /** Whether the person said anything. Reported back, and the reason to stop. */
    @Volatile var answered = false; private set

    @Volatile private var startedAt = 0L

    /** Seconds the session actually lasted, for the outcome report. */
    val seconds: Int get() =
        if (startedAt == 0L) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt = System.currentTimeMillis()
        lastEventAt = startedAt
        onState(State.CONNECTING)

        // No beta header. The interpreter's session does without one, and
        // asking for `realtime=v1` on a surface that has since gone general
        // would be asking for the old one on purpose.
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $clientSecret")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                speaker.start()
                RecorderService.audioTap = { buffer, count -> feed(buffer, count) }
                onState(State.RUNNING)
                // The device speaks first. That is the whole feature, and it is
                // one message: everything about what to say is already in the
                // session's instructions.
                ws.send(JSONObject().put("type", "response.create").toString())
                armTimers()
            }

            override fun onMessage(ws: WebSocket, text: String) = handle(text)

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "nudge socket failed", t)
                release()
                onState(State.FAILED)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (running.get()) {
                    release()
                    onState(State.CLOSED)
                }
            }
        })
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close(1000, "done") }
        socket = null
        release()
        onState(State.CLOSED)
    }

    private fun release() {
        running.set(false)
        RecorderService.audioTap = null
        speaker.stop()
        runCatching { clock.shutdownNow() }
    }

    // ------------------------------------------------------------- events ---

    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = message.optString("type")
        lastEventAt = System.currentTimeMillis()

        when {
            // Audio out. Both the current name and the one before it.
            type.endsWith("audio.delta") -> {
                val delta = message.optString("delta")
                if (delta.isNotEmpty()) speaker.play(Base64.decode(delta, Base64.NO_WRAP))
            }

            // What the device is saying, as it says it.
            type.endsWith("audio_transcript.delta") -> {
                val delta = message.optString("delta")
                if (delta.isNotEmpty()) onLine(Said(delta))
            }

            // What the person said, once the transcription lands. This is also
            // the only evidence that anybody was there to answer — the presence
            // check said somebody was in front of the camera, which is not the
            // same claim.
            type.endsWith("input_audio_transcription.completed") -> {
                val heard = message.optString("transcript").trim()
                if (heard.isNotEmpty()) {
                    answered = true
                    onLine(Heard(heard))
                }
            }

            // A turn finished. If it was the reply to something the person
            // said, the check-in has done its job and should end rather than
            // sit open waiting for more.
            type == "response.done" -> if (answered) closeSoon()

            type == "error" -> Log.w(TAG, "nudge error: " + message.opt("error"))

            else -> if (seen.add(type)) Log.i(TAG, "nudge event: $type")
        }
    }

    /** Lets the last sentence finish playing, then ends the session. */
    private fun closeSoon() {
        runCatching {
            clock.schedule({ stop() }, DRAIN_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * The two bounds that do not depend on the model behaving: a ceiling on the
     * whole session, and a limit on how long it may sit with nothing happening.
     */
    private fun armTimers() {
        runCatching {
            clock.schedule({ stop() }, maxSeconds.toLong(), TimeUnit.SECONDS)
            clock.scheduleWithFixedDelay(
                {
                    if (running.get() && System.currentTimeMillis() - lastEventAt > IDLE_TIMEOUT_MS) {
                        stop()
                    }
                },
                IDLE_TIMEOUT_MS,
                5_000L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    // -------------------------------------------------------------- audio ---

    private fun feed(buffer: ShortArray, count: Int) {
        if (!running.get()) return
        downmix.feed(buffer, count)
    }

    private fun send(samples: ShortArray) {
        runCatching {
            socket?.send(
                JSONObject().apply {
                    put("type", "input_audio_buffer.append")
                    put("audio", Base64.encodeToString(samples.toPcm16(), Base64.NO_WRAP))
                }.toString(),
            )
        }
    }
}
