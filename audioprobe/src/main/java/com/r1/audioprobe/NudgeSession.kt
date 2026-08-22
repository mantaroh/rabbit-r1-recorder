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

        /**
         * How long after the speaker falls silent the microphone stays shut.
         *
         * The room is not sent while this device is talking, because the
         * microphone hears the speaker: measured on 2026-08-22, the first
         * check-in ever made had `speech_started` fire one second after it
         * began speaking, transcribed its own question as the reply, answered
         * itself, and hung up. The archive recorded one voice asking the same
         * thing twice and no human at all.
         *
         * The tail covers the estimate being slightly short and the room's own
         * reverberation. It is the reason a person cannot interrupt — which was
         * the stated trade when this was designed, and it costs less in a
         * two-turn check-in than in a conversation.
         */
        const val ECHO_TAIL_MS = 600L
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

    /** Whether the microphone is currently being held shut; see [feed]. */
    @Volatile private var muted = false

    /**
     * A rolling window of what this device has just said, for [looksLikeEcho].
     * Rolling rather than per-turn because an echo arrives within seconds of
     * the words that caused it, and nothing older can be the cause.
     */
    private val saidRecently = StringBuilder()

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

            // What the device is saying, as it says it. Kept as well as shown,
            // because it is what an echo would come back as.
            type.endsWith("audio_transcript.delta") -> {
                val delta = message.optString("delta")
                if (delta.isNotEmpty()) {
                    synchronized(saidRecently) {
                        saidRecently.append(delta)
                        if (saidRecently.length > 400) {
                            saidRecently.delete(0, saidRecently.length - 400)
                        }
                    }
                    onLine(Said(delta))
                }
            }

            // What the person said, once the transcription lands. This is also
            // the only evidence that anybody was there to answer — the presence
            // check said somebody was in front of the camera, which is not the
            // same claim.
            type.endsWith("input_audio_transcription.completed") -> {
                val heard = message.optString("transcript").trim()
                if (heard.isNotEmpty()) {
                    if (looksLikeEcho(heard)) {
                        // Not an answer, and it must not end the session or be
                        // reported as one. The gate in feed() should have made
                        // this unreachable; it is here because the failure it
                        // catches is silent and expensive — a device that
                        // records "somebody answered" when nobody did poisons
                        // the one table this feature is judged from.
                        Log.i(TAG, "nudge: heard its own voice, ignoring")
                    } else {
                        answered = true
                        onLine(Heard(heard))
                    }
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

    /**
     * Whether what the microphone heard is this device's own voice come back.
     *
     * Crude on purpose: an echo is a near-copy, not a paraphrase, so
     * containment either way catches it and a character overlap catches the
     * case where the far side transcribed the two passes slightly differently.
     *
     * Short utterances are taken at face value. "はい" or "まあまあ" is a real
     * answer far more often than it is an echo, and calling one an echo has the
     * worse consequence: the exchange would not end when it should, and the log
     * would say nobody was there.
     */
    private fun looksLikeEcho(heard: String): Boolean {
        val mine = synchronized(saidRecently) { normalise(saidRecently.toString()) }
        val theirs = normalise(heard)
        if (mine.isEmpty() || theirs.length < 6) return false
        if (mine.contains(theirs) || theirs.contains(mine)) return true

        // Character overlap, counted with repetition, against the shorter side.
        val pool = HashMap<Char, Int>()
        for (c in mine) pool[c] = (pool[c] ?: 0) + 1
        var shared = 0
        for (c in theirs) {
            val left = pool[c] ?: 0
            if (left > 0) {
                pool[c] = left - 1
                shared += 1
            }
        }
        return shared.toDouble() / theirs.length >= 0.7
    }

    /** Punctuation and spacing differ between two passes at the same words. */
    private fun normalise(text: String): String =
        text.filter { it.isLetterOrDigit() }

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

    /**
     * Half duplex, and the reason is in [ECHO_TAIL_MS]. While the speaker has
     * anything to play, the microphone is hearing this device rather than the
     * room, so silence of the same length goes up instead.
     */
    private fun feed(buffer: ShortArray, count: Int) {
        if (!running.get()) return
        if (speaker.speaking(ECHO_TAIL_MS)) {
            if (!muted) {
                muted = true
                // Drop whatever fragment was mid-chunk rather than splicing it
                // onto the silence that follows.
                downmix.reset()
            }
            downmix.feedSilence(count)
            return
        }
        muted = false
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
