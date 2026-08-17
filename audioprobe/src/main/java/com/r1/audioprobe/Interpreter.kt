package com.r1.audioprobe

import android.content.Context
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

        /** Base64 PCM16 at 24 kHz, which is what the session takes and returns. */
        private const val WIRE_RATE = 24_000

        /** Roughly 100 ms. Latency is the product here, so nothing is hoarded. */
        private const val SEND_SAMPLES = 2_400
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        // The session is long-lived and mostly quiet in one direction; a read
        // timeout would close it during a pause in the conversation.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "interpret").apply { isDaemon = true }
    }

    /** Separate, so a blocking write to the speaker cannot delay a send. */
    private val player = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "interpret-play").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var track: AudioTrack? = null
    private var previousVolume: Int? = null

    /** Buffered between the capture thread and the socket; see [feed]. */
    private val pending = ShortArray(SEND_SAMPLES)
    private var pendingCount = 0

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
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        releaseVolume()
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
                claimVolume()
                openTrack()
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
                play(Base64.decode(delta, Base64.NO_WRAP))

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
     * Called on the capture thread with interleaved 48 kHz stereo, doing as
     * little there as possible.
     *
     * Downmix and decimate in one pass: two channels average to mono and 48 kHz
     * to 24 is an exact 2:1, so a frame is two stereo pairs collapsed to one
     * sample. No filter, because the microphone has nothing above 12 kHz worth
     * aliasing down that speech will notice.
     *
     * Everything goes up, including the quiet. The pauses are how the model
     * finds the ends of sentences.
     */
    private fun feed(buffer: ShortArray, count: Int) {
        if (!running.get()) return

        var index = 0
        while (index + 3 < count) {
            pending[pendingCount++] =
                ((buffer[index] + buffer[index + 1] + buffer[index + 2] + buffer[index + 3]) / 4)
                    .toShort()
            index += 4

            if (pendingCount == SEND_SAMPLES) {
                val payload = ShortArray(SEND_SAMPLES)
                System.arraycopy(pending, 0, payload, 0, SEND_SAMPLES)
                pendingCount = 0
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

    /**
     * Queued straight to the speaker as it arrives.
     *
     * On its own thread: the caller is the WebSocket reader, and a blocking
     * write there stalls every message behind it, including the transcript for
     * the audio being played.
     */
    private fun play(pcm: ByteArray) {
        player.execute {
            runCatching { track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
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
                    // Speech for a person in the room, not a notification: this
                    // must not duck under, or be ducked by, anything else.
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
            // Eight times the reported minimum, not two seconds. A buffer sixty
            // times larger than asked for was accepted, reported as playing,
            // and never started: the play head sat at zero and nothing was ever
            // audible.
            .setBufferSizeInBytes(minBuffer * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(AudioTrack.getMaxVolume())
                it.play()
            }
    }

    // ------------------------------------------------------------- volume ---

    /**
     * Turns the media volume up for the session and puts it back afterwards.
     *
     * The device sits at 5 of 15 by default, which is too quiet to be the point
     * of the room. Raising the track's own gain does not help — that is already
     * at maximum — because the stream is what is turned down. Restored on stop,
     * since silently leaving somebody's volume at maximum is the kind of thing
     * discovered by the next notification.
     */
    private fun claimVolume() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        }
    }

    private fun releaseVolume() {
        val restore = previousVolume ?: return
        previousVolume = null
        runCatching {
            context.getSystemService(AudioManager::class.java)
                ?.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }
    }

    private fun move(next: State) {
        if (state == next) return
        state = next
        onState(next)
    }
}
