package com.r1.audioprobe

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 声かけ — the half of it that runs on the device.
 *
 * Which is deliberately the smaller half. Everything that decides whether the
 * device may speak is in the Worker: the hour, the caps, whether there was
 * already a conversation, whether anybody is in front of the camera. This class
 * reports the three facts only the device holds — the Wi-Fi, whether it is
 * recording, and how the room has sounded for the last two minutes — takes a
 * photograph when asked for one, and opens the screen when the answer is yes.
 *
 * The order matters and it is the reason the poll is cheap. The arm that
 * carries the lens is audible in this device's own recording, so nothing moves
 * it until the Worker has already said a slot is due; a poll that comes back
 * `due: null`, which is almost all of them, costs one request and no motor.
 */
class Nudge(
    private val context: Context,
    private val settings: UploadSettings,
    private val metrics: Metrics,
    /**
     * Fetched rather than held, because both are built after this class is and
     * either can be absent — photography can be switched off at launch, and the
     * uploader does not exist until capture has started.
     */
    private val timelapse: () -> Timelapse?,
    private val uploader: () -> Uploader?,
) {

    private companion object {
        const val TAG = "R1AudioProbe"

        /**
         * How often the Worker is asked.
         *
         * Two minutes rather than the thirty seconds the day's schedule runs
         * at: the slots have two-hour windows and nothing here is worth being
         * punctual about, while a request a minute for a year is 500,000
         * requests to be told that nothing is due.
         */
        const val POLL_INTERVAL_MS = 120_000L

        /**
         * Seconds of loudness sent with the confirmation.
         *
         * The same measurement the archive stores beside every segment — raw
         * RMS shifted down four bits, one byte a second — so the Worker is
         * applying the same test to fresher data rather than a different test.
         * Two minutes because that is the window in which "somebody is already
         * talking" is a fact about now rather than about earlier.
         */
        const val ENVELOPE_SECONDS = 120
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * One poll at a time, off the capture thread.
     *
     * A whole cycle — request, motor, shutter, upload, second request — is
     * seconds long, and the thread that reads AudioRecord is the one thing in
     * this app that cannot be late.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-nudge").apply { isDaemon = true }
    }

    private val busy = AtomicBoolean(false)
    private var lastPollAt = 0L

    /** Only log a verdict when it changes; otherwise this fills the log with "no". */
    private var lastWhy: String? = null

    // ------------------------------------------------------------ envelope ---

    private val envelope = IntArray(ENVELOPE_SECONDS)
    private var envelopeAt = 0
    private var envelopeSeen = 0

    /**
     * Called once per second of captured audio, from the capture thread, with
     * the same byte the segment sidecar records.
     */
    @Synchronized
    fun noteSecond(rms: Int) {
        envelope[envelopeAt] = rms.coerceIn(0, 255)
        envelopeAt = (envelopeAt + 1) % ENVELOPE_SECONDS
        if (envelopeSeen < ENVELOPE_SECONDS) envelopeSeen += 1
    }

    /** The window in order, oldest first, base64 of one byte a second. */
    @Synchronized
    private fun envelopeBase64(): String {
        if (envelopeSeen == 0) return ""
        val bytes = ByteArray(envelopeSeen)
        // Walk backwards from the write cursor so a partially filled buffer is
        // still in order rather than starting in the middle of the zeros.
        for (i in 0 until envelopeSeen) {
            val index = ((envelopeAt - envelopeSeen + i) % ENVELOPE_SECONDS + ENVELOPE_SECONDS) %
                ENVELOPE_SECONDS
            bytes[i] = envelope[index].toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- poll ---

    /** Called from the capture thread; returns immediately. */
    fun tick(nowMs: Long, recording: Boolean) {
        if (!settings.nudge) return
        if (!settings.enabled || settings.baseUrl.isEmpty()) return
        if (nowMs - lastPollAt < POLL_INTERVAL_MS) return
        lastPollAt = nowMs
        if (!busy.compareAndSet(false, true)) return

        worker.execute {
            try {
                runCatching { poll(recording) }
                    .onFailure { Log.w(TAG, "nudge poll failed", it) }
            } finally {
                busy.set(false)
            }
        }
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun poll(recording: Boolean) {
        // The same verdict the timelapse uses, from the same pair of functions,
        // so "at home" means one thing on this device. An empty setting still
        // means everywhere — which for this feature reads as "the network is
        // not being used as a fence", and is the state a fresh install is in.
        val home = Timelapse.isHome(Timelapse.currentSsid(context), settings.photoSsid)

        val due = get(
            "/v1/nudge?home=" + (if (home) "1" else "0") +
                "&recording=" + (if (recording) "1" else "0"),
        ) ?: return

        val slot = due.optString("due")
        if (slot.isEmpty() || slot == "null") {
            val why = due.optString("why", "?")
            if (why != lastWhy) {
                lastWhy = why
                metrics.write("nudge", mapOf("due" to false, "why" to why))
            }
            return
        }
        lastWhy = null
        metrics.write("nudge", mapOf("due" to true, "slot" to slot))

        // Now, and only now, the arm moves.
        val frame = timelapse()?.captureFront()
        if (frame == null) {
            metrics.write("nudge", mapOf("slot" to slot, "why" to "no_frame"))
            return
        }
        // Held before the upload: a successful upload deletes the file, and the
        // id is the name it went up under.
        val photoId = frame.nameWithoutExtension
        if (uploader()?.uploadNow(frame) != true) {
            metrics.write("nudge", mapOf("slot" to slot, "why" to "frame_upload_failed"))
            return
        }

        val body = JSONObject().apply {
            put("slot", slot)
            put("photo_id", photoId)
            put("envelope", envelopeBase64())
            // Recorded rather than acted on: nothing refuses a nudge for being
            // face down today, but a fortnight of verdicts beside the posture
            // they were taken in is what would say whether it should.
            put("posture", com.r1.core.Motion.placement.posture.name)
        }

        val verdict = post("/v1/nudge/confirm", body) ?: return
        if (!verdict.optBoolean("ok")) {
            metrics.write(
                "nudge",
                mapOf(
                    "slot" to slot,
                    "spoke" to false,
                    "why" to verdict.optString("why", "?"),
                    "presence" to verdict.optString("presence", "").take(120),
                ),
            )
            return
        }

        metrics.write("nudge", mapOf("slot" to slot, "spoke" to true, "id" to verdict.optInt("id")))
        speak(verdict, slot)
    }

    /**
     * Hands the minted session to the screen.
     *
     * The Activity owns everything after this point — the socket, the speaker,
     * and reporting back whether anybody answered — for the same reason the
     * interpreter does: this costs money by the minute and listens to a room
     * with intent, so it runs while a screen is up and ends when it is not.
     */
    private fun speak(verdict: JSONObject, slot: String) {
        val intent = Intent(context, NudgeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(NudgeActivity.EXTRA_ID, verdict.optInt("id"))
            .putExtra(NudgeActivity.EXTRA_SLOT, slot)
            .putExtra(NudgeActivity.EXTRA_OPENING, verdict.optString("opening"))
            .putExtra(NudgeActivity.EXTRA_SECRET, verdict.optString("client_secret"))
            .putExtra(NudgeActivity.EXTRA_URL, verdict.optString("url"))
            .putExtra(NudgeActivity.EXTRA_MAX_SECONDS, verdict.optInt("max_seconds", 180))

        runCatching { context.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "nudge screen could not be shown", it)
                metrics.write("nudge", mapOf("slot" to slot, "why" to "no_screen"))
            }
    }

    // ---------------------------------------------------------------- http ---

    private fun get(path: String): JSONObject? = call(
        Request.Builder().url(settings.baseUrl + path).get(),
    )

    private fun post(path: String, body: JSONObject): JSONObject? = call(
        Request.Builder()
            .url(settings.baseUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())),
    )

    private fun call(builder: Request.Builder): JSONObject? {
        builder.addHeader("Authorization", "Bearer " + settings.bearer)
        if (settings.accessClientId.isNotEmpty()) {
            builder.addHeader("CF-Access-Client-Id", settings.accessClientId)
            builder.addHeader("CF-Access-Client-Secret", settings.accessClientSecret)
        }

        return runCatching {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    metrics.write(
                        "nudge_http",
                        mapOf("code" to response.code, "body" to text.take(160)),
                    )
                    null
                } else {
                    JSONObject(text)
                }
            }
        }.getOrElse {
            metrics.write("nudge_http", mapOf("error" to (it.message ?: "?").take(120)))
            null
        }
    }
}
