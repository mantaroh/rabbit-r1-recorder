package com.r1.audioprobe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live interpretation, through OpenAI's realtime translation model.
 *
 * Audio goes from this device straight to OpenAI. It has to: interpretation is
 * only usable if the reply lands while the sentence is still in the room, and
 * an extra hop through a Worker spends exactly the budget that makes it work.
 * The Worker's job is to hold the API key and hand out an `ek_…` that expires,
 * because every app on this device can open a root shell and a key kept here
 * would not be a secret.
 *
 * **Half duplex, and the reason is the speaker.** Both people have to hear the
 * interpretation, so it plays out loud, so the microphone hears it too — and a
 * translator that can hear itself translates its own output. The input is
 * therefore muted while the device is speaking. That makes this consecutive
 * interpretation rather than simultaneous: say a sentence, wait, hear it. It is
 * how a human interpreter works and nobody finds that strange, but it is not
 * what the model is capable of and the difference is the room, not the model.
 */
class Interpreter(
    private val settings: UploadSettings,
    private val onState: (State) -> Unit,
    private val onTranscript: (Line) -> Unit,
) {

    /**
     * A line of text from the session. Which side it came from matters on
     * screen: one is a check that the room was heard correctly, the other is
     * the thing the other person is listening to.
     */
    sealed interface Line { val text: String }
    @JvmInline value class Heard(override val text: String) : Line
    @JvmInline value class Translated(override val text: String) : Line

    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING, FAILED }

    companion object {
        private const val TAG = "R1AudioProbe"

        /** What the API wants, and what AudioTrack is opened at. */
        private const val WIRE_RATE = 24_000

        /**
         * How long after the last audio chunk the room is called quiet again.
         *
         * AudioTrack reports its buffer drained before the speaker has finished
         * moving air, and the tail of a sentence arriving back through the
         * microphone is enough to start a translation of it.
         */
        private const val PLAYBACK_TAIL_MS = 350L

        /**
         * Roughly 100 ms at the wire rate. Sent as it arrives rather than
         * accumulated: latency here is the product.
         */
        private const val SEND_SAMPLES = 2_400
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "interpret").apply { isDaemon = true }
    }

    /** Separate from [worker] so a blocking speaker write cannot delay a send. */
    private val player = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "interpret-play").apply { isDaemon = true }
    }

    /** Rearmed on every chunk; see play(). */
    private val gate = android.os.Handler(android.os.Looper.getMainLooper())

    private var socket: WebSocket? = null
    private var track: AudioTrack? = null

    private val running = AtomicBoolean(false)
    @Volatile private var speakingUntil = 0L
    @Volatile private var state = State.IDLE

    /** Buffered between the capture thread and the socket; see [feed]. */
    private val pending = ShortArray(SEND_SAMPLES)
    private var pendingCount = 0

    val currentState: State get() = state

    // ------------------------------------------------------------ session ---

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
        runCatching { socket?.close(1000, "done") }
        socket = null
        gate.removeCallbacksAndMessages(null)
        speakingUntil = 0L
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        move(State.IDLE)
    }

    /**
     * The Worker mints the credential; this device never sees the API key.
     * Same door as everything else, so it needs no extra secret of its own.
     */
    private fun mint(target: String): JSONObject? {
        val url = settings.baseUrl + "/v1/interpret/session?target=" + target
        val request = Request.Builder().url(url).post(okhttp3.internal.EMPTY_REQUEST).apply {
            addHeader("Authorization", "Bearer " + settings.bearer)
            if (settings.accessClientId.isNotEmpty()) {
                addHeader("CF-Access-Client-Id", settings.accessClientId)
                addHeader("CF-Access-Client-Secret", settings.accessClientSecret)
            }
        }.build()

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
        val secret = session.getString("client_secret")
        val request = Request.Builder()
            .url(session.getString("url"))
            .addHeader("Authorization", "Bearer $secret")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // The target is already baked into the minted session; sent
                // again because the session that arrives is the server's, and
                // agreeing about the output language is cheaper than assuming.
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
                openTrack()
                RecorderService.audioTap = { buffer, count -> feed(buffer, count) }
                move(State.LISTENING)
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
                if (running.get()) move(State.IDLE)
            }
        })
    }

    /**
     * Matched on shape rather than on exact names.
     *
     * The names in the documentation did not survive contact with a running
     * session — the transcript arrives as `session.input_transcript.delta`,
     * not the `output_transcript` the guide describes, and the audio event has
     * been spelled three different ways across this API's versions. Keying on
     * "does it contain audio", "is it an input transcript", "is it an output
     * transcript" costs nothing and does not break the next time a prefix
     * changes. Anything unrecognised still gets logged once.
     */
    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = message.optString("type")
        val delta = message.optString("delta")

        when {
            type.endsWith("audio.delta") && delta.isNotEmpty() ->
                play(Base64.decode(delta, Base64.NO_WRAP))

            type.contains("input_transcript") && delta.isNotEmpty() ->
                onTranscript(Heard(delta))

            type.contains("output_transcript") && delta.isNotEmpty() ->
                onTranscript(Translated(delta))

            type == "error" -> Log.w(TAG, "interpret error: " + message.optJSONObject("error"))
            // Once each, so a name that drifts says so instead of being
            // dropped in silence. That is how the transcript event above was
            // found to be spelled differently from the documentation.
            else -> if (seen.add(type)) Log.i(TAG, "interpret event: $type")
        }
    }

    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // -------------------------------------------------------------- audio ---

    /**
     * Called on the capture thread with interleaved 48 kHz stereo, and doing as
     * little as possible there on purpose.
     *
     * Downmix and decimate in one pass: the two channels average to mono and
     * 48 kHz to 24 is an exact 2:1, so a frame is two stereo pairs collapsed to
     * one sample. No filter, because the microphone has nothing above 12 kHz
     * worth aliasing down that speech will notice.
     */
    private fun feed(buffer: ShortArray, count: Int) {
        if (!running.get()) return
        // Muted while the device is talking. This is the half-duplex rule, and
        // without it the translator hears its own voice and translates that.
        if (System.currentTimeMillis() < speakingUntil) return

        var index = 0
        while (index + 3 < count) {
            val mono = (buffer[index] + buffer[index + 1] + buffer[index + 2] + buffer[index + 3]) / 4
            pending[pendingCount++] = mono.toShort()
            index += 4
            if (pendingCount == SEND_SAMPLES) {
                val payload = ShortArray(SEND_SAMPLES)
                System.arraycopy(pending, 0, payload, 0, SEND_SAMPLES)
                pendingCount = 0
                // Off the capture thread the moment there is enough to send.
                worker.execute { send(payload) }
            }
        }
    }

    private fun send(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        runCatching {
            socket?.send(
                JSONObject().apply {
                    put("type", "session.input_audio_buffer.append")
                    put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }.toString(),
            )
        }
    }

    private fun openTrack() {
        val minBuffer = AudioTrack.getMinBufferSize(
            WIRE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Spoken content for a person in the room, not a
                    // notification: this must not duck under, or be ducked by,
                    // anything else.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(WIRE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, WIRE_RATE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /**
     * Queues a chunk for the speaker and holds the microphone shut until it has
     * finished playing.
     *
     * The gate is extended *before* the write, because the microphone is live
     * right now and a gate that closes once the audio is already out was open
     * for the first syllable.
     *
     * The end time accumulates rather than being recomputed from the clock.
     * Chunks arrive faster than they play — the model is not speaking in real
     * time, it is sending as fast as the socket allows — so "now plus this
     * chunk" would put the gate in the past while a second of speech was still
     * queued ahead of it.
     */
    private fun play(pcm: ByteArray) {
        if (track == null) return
        val now = System.currentTimeMillis()
        val queuedUntil = maxOf(speakingUntil - PLAYBACK_TAIL_MS, now)
        speakingUntil = queuedUntil + (pcm.size * 1000L / (WIRE_RATE * 2)) + PLAYBACK_TAIL_MS
        move(State.SPEAKING)

        // Written on its own thread. The caller is the WebSocket reader, and a
        // blocking write there stalls every message behind it — including the
        // transcript for the audio being played.
        player.execute {
            runCatching { track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
        }

        // One pending check, rearmed by each chunk. The first version slept for
        // the tail and then tested against an end time further out, so anything
        // longer than 350 ms never re-opened the microphone and the session was
        // deaf from its first reply onwards.
        gate.removeCallbacksAndMessages(null)
        gate.postDelayed({
            if (running.get() && System.currentTimeMillis() >= speakingUntil) {
                move(State.LISTENING)
            }
        }, maxOf(0L, speakingUntil - now))
    }

    private fun move(next: State) {
        if (state == next) return
        state = next
        onState(next)
    }
}
