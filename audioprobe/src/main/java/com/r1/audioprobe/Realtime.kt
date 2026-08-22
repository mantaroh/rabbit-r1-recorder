package com.r1.audioprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The plumbing shared by the two features that hold a live OpenAI session:
 * [Interpreter], which translates a room, and [NudgeSession], which asks how
 * the day is going.
 *
 * They differ in what they say and in who starts. They do not differ in how
 * audio reaches the socket or how it comes back out of the speaker, and every
 * one of the lessons in here was paid for once already — the buffer eight times
 * the reported minimum that actually starts playing, the media volume claimed
 * and put back, the 2:1 decimation that is exact because the capture is 48 kHz
 * and the wire is 24. Copying them into a second file would mean finding them
 * again the next time one of them is wrong.
 */
object Realtime {

    /** Base64 PCM16 at 24 kHz, which is what these sessions take and return. */
    const val WIRE_RATE = 24_000

    /** Roughly 100 ms. Latency is the product here, so nothing is hoarded. */
    const val SEND_SAMPLES = 2_400

    /**
     * A client for a socket that is long-lived and mostly quiet in one
     * direction. The read timeout has to be off: the default would close the
     * session during a pause in the conversation, which is the one thing a
     * conversation is guaranteed to contain.
     */
    fun http(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

/**
 * The speaker, for the duration of a session.
 *
 * Turns the media volume up and puts it back afterwards. The device sits at 5
 * of 15 by default, which is too quiet to be the point of the room, and raising
 * the track's own gain does not help — that is already at maximum, and the
 * stream is what is turned down. Restored on [stop], since silently leaving
 * somebody's volume at maximum is the kind of thing discovered by the next
 * notification.
 */
class Speaker(private val context: Context) {

    private val player = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "realtime-play").apply { isDaemon = true }
    }

    private var track: AudioTrack? = null
    private var previousVolume: Int? = null

    /**
     * Wall-clock instant at which everything queued so far will have finished
     * playing, or 0 when nothing is queued.
     *
     * Updated as audio is handed in rather than by watching the play head: the
     * caller needs to know the speaker is *about* to be busy before the first
     * sample reaches it, and the play head is behind by whatever is in the
     * buffer. It accumulates duration, so a burst of deltas arriving faster
     * than real time — which is how these models send — still reports the
     * instant the last of them will be audible.
     */
    @Volatile var speakingUntilMs = 0L; private set

    /** Whether the room is currently hearing this device, plus a tail. */
    fun speaking(tailMs: Long = 0): Boolean =
        System.currentTimeMillis() < speakingUntilMs + tailMs

    fun start() {
        claimVolume()
        val minBuffer = AudioTrack.getMinBufferSize(
            Realtime.WIRE_RATE,
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
                    .setSampleRate(Realtime.WIRE_RATE)
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

    /**
     * Queued straight to the speaker as it arrives, on its own thread: the
     * caller is the WebSocket reader, and a blocking write there stalls every
     * message behind it, including the transcript for the audio being played.
     */
    fun play(pcm: ByteArray) {
        // Booked before the write is dispatched, not inside it: whoever is
        // deciding whether the microphone is hearing this device needs the
        // answer to be yes from the moment the audio exists.
        val durationMs = pcm.size.toLong() * 1000 / (2 * Realtime.WIRE_RATE)
        val now = System.currentTimeMillis()
        speakingUntilMs = maxOf(now, speakingUntilMs) + durationMs

        player.execute {
            runCatching { track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
        }
    }

    fun stop() {
        speakingUntilMs = 0
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        releaseVolume()
    }

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
}

/**
 * The capture stream, on the way to a session.
 *
 * Called on the capture thread with interleaved 48 kHz stereo, doing as little
 * there as possible: two channels average to mono and 48 kHz to 24 is an exact
 * 2:1, so a frame is two stereo pairs collapsed to one sample. No filter,
 * because the microphone has nothing above 12 kHz worth aliasing down that
 * speech will notice.
 *
 * Everything goes up, including the quiet. The pauses are how these models find
 * the ends of sentences, and an earlier interpreter that gated them on a local
 * voice-activity detector broke exactly that.
 */
class Downmix(private val onChunk: (ShortArray) -> Unit) {

    private val pending = ShortArray(Realtime.SEND_SAMPLES)
    private var pendingCount = 0

    fun feed(buffer: ShortArray, count: Int) {
        var index = 0
        while (index + 3 < count) {
            emit(
                ((buffer[index] + buffer[index + 1] + buffer[index + 2] + buffer[index + 3]) / 4)
                    .toShort(),
            )
            index += 4
        }
    }

    /**
     * The same length of stream, with nothing in it.
     *
     * For the half-duplex case: while the speaker is talking, the microphone is
     * hearing this device, and the room must not be sent. Sending silence
     * rather than sending nothing keeps the stream continuous, so the far side
     * measures the pause before a person answers as the pause it actually was.
     */
    fun feedSilence(count: Int) {
        var index = 0
        while (index + 3 < count) {
            emit(0)
            index += 4
        }
    }

    /** Drops the part-filled chunk, for a caller that is about to change what
     * it is sending and does not want the two spliced together. */
    fun reset() {
        pendingCount = 0
    }

    private fun emit(sample: Short) {
        pending[pendingCount++] = sample
        if (pendingCount == Realtime.SEND_SAMPLES) {
            val payload = ShortArray(Realtime.SEND_SAMPLES)
            System.arraycopy(pending, 0, payload, 0, Realtime.SEND_SAMPLES)
            pendingCount = 0
            onChunk(payload)
        }
    }
}

/** Little-endian PCM16 bytes, which is what both sessions put on the wire. */
fun ShortArray.toPcm16(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        bytes[i * 2] = (this[i].toInt() and 0xFF).toByte()
        bytes[i * 2 + 1] = ((this[i].toInt() shr 8) and 0xFF).toByte()
    }
    return bytes
}
