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
 * **The device decides when it is over, and then says so.** A WebSocket gives
 * the model no way to hang up, and the first version had the device do it
 * silently: it cut the socket on the event that completed the model's own
 * follow-up question, so the room got "それで今日は何を作ってるんですか?" and
 * then nothing. Ending a conversation is something you say.
 *
 * So the exchange runs until the person has answered [MAX_EXCHANGES] times or
 * has stopped answering for [QUIET_AFTER_REPLY_MS], at which point the model is
 * asked for a closing remark and the session ends once that has actually been
 * heard. Underneath sit two bounds that do not depend on anything behaving: a
 * stretch with nothing happening at all, and the ceiling the Worker sent down.
 * A billed microphone session with no upper bound is the kind of thing
 * discovered on an invoice.
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
    /** What to tell the model when it is time to stop; the Worker's words. */
    private val farewell: String,
    private val onState: (State) -> Unit,
    private val onLine: (Line) -> Unit,
) {

    /** Who said it. The screen shows both, at different weights. */
    sealed interface Line { val text: String }
    @JvmInline value class Said(override val text: String) : Line
    @JvmInline value class Heard(override val text: String) : Line

    enum class State { CONNECTING, RUNNING, CLOSED, FAILED }

    companion object {
        private const val TAG = "R1AudioProbe"

        /**
         * Nothing at all for this long and the session closes.
         *
         * The case it exists for is a room that answered by walking away: the
         * model has finished speaking, nobody replies, and without this the
         * socket stays open and billed until the ceiling.
         */
        private const val IDLE_TIMEOUT_MS = 45_000L

        /**
         * The pause after the speaker has actually finished, before closing.
         *
         * **`response.done` means generated, not spoken.** These models emit a
         * reply far faster than it can be played: measured 2026-08-23, a whole
         * response was generated 1.2 s after it was asked for. Treating that
         * event as the end of the sentence closed the session while four
         * seconds of a longer reply were still queued, and the last words were
         * never heard — twice, before the difference was noticed.
         *
         * So the wait is however long the speaker still has to play, and this
         * on the end of it.
         */
        private const val DRAIN_MS = 700L

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
        private const val ECHO_TAIL_MS = 600L

        /**
         * How many times the person may answer before the device wraps up.
         *
         * Three, and the number is a compromise rather than a finding. One was
         * what the first version did and it was wrong in a way worth
         * describing: the model would answer, then ask a follow-up — "それで今日
         * は何を作ってるんですか?" — and the session closed on the same event
         * that finished the question. The device asked something and hung up on
         * the answer.
         */
        private const val MAX_EXCHANGES = 3

        /**
         * Silence for this long after the device stops speaking, and the
         * check-in is over.
         *
         * Long enough to think about the question, short enough that a person
         * who has gone back to work is not being listened to for a minute.
         */
        private const val QUIET_AFTER_REPLY_MS = 20_000L

        /**
         * How long the closing remark is waited for before the session is shut
         * anyway. It is a courtesy, not a reason to stay connected.
         */
        private const val FAREWELL_TIMEOUT_MS = 8_000L

        /**
         * What to ask for when it is time to stop, if the Worker did not say.
         *
         * The words belong to the Worker along with the question that opened
         * the session, and this is the fallback for a device talking to a
         * version of it that predates them.
         */
        const val FAREWELL_FALLBACK =
            "会話を締めくくってください。質問はせず、一言で終わりを告げてください。"
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

    /** How many times they have answered, which is what bounds the exchange. */
    @Volatile private var exchanges = 0

    /** Set once the closing remark has been asked for; it is asked for once. */
    private val closing = AtomicBoolean(false)

    /** True between speech_started and speech_stopped: somebody is mid-sentence. */
    @Volatile private var personSpeaking = false

    /**
     * When the device last finished a turn, or 0 before the first one.
     *
     * The clock for "nobody is answering" starts here rather than at the last
     * event, because the last event of a reply arrives while the reply is still
     * being played.
     */
    @Volatile private var turnEndedAt = 0L

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
                        exchanges += 1
                        onLine(Heard(heard))
                    }
                }
            }

            // Somebody is mid-sentence. Nothing is decided while that is true —
            // in particular the silence that ends the check-in cannot start
            // counting in the middle of an answer.
            type.endsWith("input_audio_buffer.speech_started") -> personSpeaking = true
            type.endsWith("input_audio_buffer.speech_stopped") -> personSpeaking = false

            // A turn finished — generated, not spoken. If this was the closing
            // remark, the session ends once it has actually been heard;
            // otherwise the device waits to see whether anybody replies.
            type == "response.done" -> {
                turnEndedAt = maxOf(System.currentTimeMillis(), speaker.speakingUntilMs)
                if (closing.get()) closeSoon()
            }

            type == "error" -> Log.w(TAG, "nudge error: " + message.opt("error"))

            else -> if (seen.add(type)) Log.i(TAG, "nudge event: $type")
        }
    }

    /**
     * Whether what the microphone heard is this device's own voice come back.
     *
     * Containment, and nothing cleverer. An echo is a near-copy of what was
     * just said, not a paraphrase of it, so one string being inside the other
     * catches it.
     *
     * **A character-overlap ratio used to sit here as well, and it was wrong.**
     * Japanese written without punctuation shares kana between sentences that
     * have nothing to do with each other: on 2026-08-22 a real answer about
     * being busy scored over 70% against a question about being busy, was
     * called an echo, and the log recorded that nobody had answered. Half
     * duplex is the defence against echo; this is only the backstop, and a
     * backstop that fires on ordinary answers is worse than no backstop.
     *
     * Short utterances are taken at face value for the same reason. "はい" is an
     * answer far more often than it is an echo.
     */
    private fun looksLikeEcho(heard: String): Boolean {
        val mine = synchronized(saidRecently) { normalise(saidRecently.toString()) }
        val theirs = normalise(heard)
        if (mine.isEmpty() || theirs.length < 8) return false
        return mine.contains(theirs) || theirs.contains(mine)
    }

    /** Punctuation and spacing differ between two passes at the same words. */
    private fun normalise(text: String): String =
        text.filter { it.isLetterOrDigit() }

    /** Lets the last sentence finish playing, then ends the session. */
    private fun closeSoon() {
        val stillToPlay = maxOf(0L, speaker.speakingUntilMs - System.currentTimeMillis())
        runCatching {
            clock.schedule({ stop() }, stillToPlay + DRAIN_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * The two bounds that do not depend on the model behaving: a ceiling on the
     * whole session, and a limit on how long it may sit with nothing happening.
     */
    private fun armTimers() {
        runCatching {
            // The ceiling. No farewell here: at three minutes the thing to do
            // is stop, not spend more of them being polite about it.
            clock.schedule({ stop() }, maxSeconds.toLong(), TimeUnit.SECONDS)
            clock.scheduleWithFixedDelay({ watch() }, 2_000L, 2_000L, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Decides, twice a second-and-a-half, whether the check-in is over.
     *
     * Nothing is decided while the device is talking or while the person is
     * mid-sentence, which between them account for most of a session. What is
     * left is the pause after a turn, and that pause is the only thing this
     * feature can read as "we are done".
     */
    private fun watch() {
        if (!running.get() || closing.get()) return

        // Still speaking, in either direction. A session with the speaker going
        // is not idle for the same reason `response.done` is not the end of a
        // sentence: every event of a long reply arrives in the first second or
        // two of playing it.
        if (speaker.speaking() || personSpeaking) return

        val now = System.currentTimeMillis()

        // Nothing whatsoever, in either direction. This is the room that was
        // never there — the presence check said somebody was in front of the
        // camera, and either they left or it was wrong.
        if (turnEndedAt == 0L && now - lastEventAt > IDLE_TIMEOUT_MS) {
            stop()
            return
        }
        if (turnEndedAt == 0L) return

        // Answered as many times as this is allowed to ask, or answered
        // nothing for long enough that the question has been let go.
        if (exchanges >= MAX_EXCHANGES || now - turnEndedAt > QUIET_AFTER_REPLY_MS) {
            wrapUp()
        }
    }

    /**
     * Asks for a closing remark, then ends once it has been spoken.
     *
     * The alternative — cutting the socket at the moment the device decides it
     * is finished — is what the first version did, and it ends the exchange
     * mid-air: the model asks "それで今日は何を作ってるんですか?" and the room
     * goes quiet on it. Whatever else a device that talks to people should do,
     * it should not walk off in the middle of its own question.
     */
    private fun wrapUp() {
        if (!closing.compareAndSet(false, true)) return
        Log.i(TAG, "nudge: wrapping up after $exchanges exchange(s)")

        val asked = runCatching {
            socket?.send(
                JSONObject().apply {
                    put("type", "response.create")
                    put("response", JSONObject().put("instructions", farewell))
                }.toString(),
            )
        }.getOrDefault(false) == true

        if (!asked) {
            stop()
            return
        }

        // If the closing remark never arrives, the session still ends. It is a
        // courtesy, not a reason to stay connected and billed.
        runCatching {
            clock.schedule(
                { if (!speaker.speaking()) stop() },
                FAREWELL_TIMEOUT_MS,
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
