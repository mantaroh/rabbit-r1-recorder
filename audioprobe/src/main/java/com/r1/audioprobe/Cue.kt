package com.r1.audioprobe

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一言 — one prepared line, said out loud, on a cue.
 *
 * Written for a talk: the speaker reaches the last slide, says "Hello, World."
 * to the room, and this device answers. It gets one attempt, in front of an
 * audience, on a network nobody here controls, which is the reason for every
 * choice below.
 *
 * **The audio is compiled in.** No speech API is called and no text is sent.
 * `res/raw/cue_hello.wav` is mono 16-bit PCM at [Realtime.WIRE_RATE], which is
 * both what [Speaker] wants and what VOICEVOX produces by default, so nothing
 * is resampled and nothing is decoded — the bytes go from the APK to the
 * speaker. The only thing that crosses the network is a number.
 *
 * **Two ways in, one way out.** The Worker's counter is the intended trigger,
 * because a hand reaching for the device on stage is visible and this should
 * not be. The side button does the same thing with no network at all, and is
 * there for the evening the venue's Wi-Fi turns out to be a captive portal.
 * Both land in [speak], which refuses to overlap with itself.
 */
class Cue(
    private val context: Context,
    private val settings: UploadSettings,
    private val metrics: Metrics,
) {

    companion object {
        private const val TAG = "R1AudioProbe"

        /**
         * How often the Worker is asked.
         *
         * Two seconds, against the two *minutes* 声かけ polls at. That feature
         * has two-hour windows and nothing to be punctual about; this one has
         * a gap in a sentence. The cost is bounded by the screen rather than
         * by the interval: this poll runs only while the stage screen is in
         * front, which is minutes a year rather than continuously.
         */
        const val POLL_INTERVAL_MS = 2_000L

        /** Let the last sample clear the buffer before the track is released. */
        private const val TAIL_MS = 400L
    }

    /** Called on a background thread when something worth showing happens. */
    @Volatile var onEvent: ((String) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val player = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-cue-play").apply { isDaemon = true }
    }

    private val speaking = AtomicBoolean(false)
    private var watcher: Thread? = null

    /**
     * The number last seen. Null until the first answer arrives.
     *
     * The first answer is adopted rather than acted on. Otherwise opening the
     * screen after a rehearsal would fire immediately, on a counter that was
     * advanced an hour ago.
     */
    private var lastSeq: Int? = null

    /**
     * The line, as the samples the speaker takes.
     *
     * Read at construction rather than at the moment of use: a missing or
     * mis-encoded asset should be found when the screen is opened — which is
     * before the talk — and not in the silence where the answer was meant to
     * be.
     */
    private val pcm: ByteArray? = loadPcm()

    /** Whether there is anything to say. Shown on the screen. */
    val ready: Boolean get() = pcm != null

    // --------------------------------------------------------------- speak ---

    /**
     * Say it, unless it is already being said.
     *
     * [why] is recorded rather than acted on: afterwards, the one thing worth
     * knowing is whether the cue that fired came from the phone or from the
     * button pressed because the phone did not work.
     */
    fun speak(why: String) {
        val samples = pcm
        if (samples == null) {
            onEvent?.invoke("音声がありません")
            return
        }
        // A second press while the first is still audible is a nervous hand,
        // not a second cue.
        if (!speaking.compareAndSet(false, true)) return

        player.execute {
            onEvent?.invoke("鳴らしています")
            metrics.write("cue", mapOf("why" to why, "bytes" to samples.size))
            val speaker = Speaker(context)
            try {
                speaker.start()
                speaker.play(samples)
                // Speaker books the instant the last sample will be audible
                // before it dispatches the write, so this waits on the audio
                // rather than on the buffer.
                while (speaker.speaking(TAIL_MS)) Thread.sleep(50)
            } catch (error: Throwable) {
                Log.w(TAG, "cue playback failed", error)
                metrics.write("cue", mapOf("why" to why, "error" to (error.message ?: "?")))
                onEvent?.invoke("再生に失敗しました")
            } finally {
                runCatching { speaker.stop() }
                speaking.set(false)
                onEvent?.invoke("待機中")
            }
        }
    }

    // ---------------------------------------------------------------- poll ---

    /** Start watching the Worker's counter. Safe to call twice. */
    fun watch() {
        if (watcher != null) return
        if (!settings.enabled || settings.baseUrl.isEmpty()) {
            onEvent?.invoke("サイドボタンのみ")
            return
        }
        watcher = Thread({ loop() }, "probe-cue").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        watcher?.interrupt()
        watcher = null
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val seq = fetchSeq()
            when {
                seq == null -> onEvent?.invoke("サーバーに繋がりません")
                lastSeq == null -> {
                    lastSeq = seq
                    onEvent?.invoke("待機中")
                }
                seq != lastSeq -> {
                    lastSeq = seq
                    speak("poll")
                }
                else -> if (!speaking.get()) onEvent?.invoke("待機中")
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (stopped: InterruptedException) {
                return
            }
        }
    }

    private fun fetchSeq(): Int? {
        val builder = Request.Builder().url(settings.baseUrl + "/v1/cue").get()
        builder.addHeader("Authorization", "Bearer " + settings.bearer)
        if (settings.accessClientId.isNotEmpty()) {
            builder.addHeader("CF-Access-Client-Id", settings.accessClientId)
            builder.addHeader("CF-Access-Client-Secret", settings.accessClientSecret)
        }

        return runCatching {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
                    .optInt("seq", -1)
                    .takeIf { it >= 0 }
            }
        }.getOrElse {
            // Not written to metrics: this runs every two seconds, and a venue
            // with no usable network would otherwise fill the log with one
            // line per poll for the length of the talk.
            Log.w(TAG, "cue poll failed: " + (it.message ?: "?"))
            null
        }
    }

    // ----------------------------------------------------------------- wav ---

    private fun loadPcm(): ByteArray? = runCatching {
        val bytes = context.resources.openRawResource(R.raw.cue_hello)
            .use { it.readBytes() }
        dataChunk(bytes)
    }.getOrElse {
        Log.w(TAG, "cue asset unusable", it)
        metrics.write("cue", mapOf("asset" to (it.message ?: "?")))
        null
    }

    /**
     * The samples out of a RIFF file, with the format checked rather than
     * assumed.
     *
     * Checked because the failure is silent and total: a 44.1 kHz file handed
     * to a 24 kHz track plays slow and deep, and it would be discovered by an
     * audience. Better to have no audio at all when the screen is opened.
     */
    private fun dataChunk(bytes: ByteArray): ByteArray {
        require(bytes.size > 44) { "file is too short to be a wav" }
        require(ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WAVE") { "not a wav" }

        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var data: ByteArray? = null

        while (at + 8 <= bytes.size) {
            val id = ascii(bytes, at)
            val size = le32(bytes, at + 4)
            val body = at + 8
            if (size < 0 || body + size > bytes.size) break
            when (id) {
                "fmt " -> {
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> data = bytes.copyOfRange(body, body + size)
            }
            // Chunks are word aligned; an odd size carries a pad byte.
            at = body + size + (size and 1)
        }

        require(channels == 1 && rate == Realtime.WIRE_RATE && bits == 16) {
            "want mono ${Realtime.WIRE_RATE} Hz 16-bit, got ${channels}ch ${rate}Hz ${bits}bit"
        }
        return data ?: throw IllegalStateException("wav has no data chunk")
    }

    private fun ascii(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int) =
        le16(bytes, at) or (le16(bytes, at + 2) shl 16)
}
