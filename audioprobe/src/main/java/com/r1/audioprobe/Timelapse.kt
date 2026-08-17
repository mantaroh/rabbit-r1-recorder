package com.r1.audioprobe

import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.util.Log
import com.r1.core.R1Motor
import com.r1.core.StillCamera
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A photograph in each direction, every few minutes.
 *
 * The lens sits on a motorised arm with one sensor on it, so "front and back"
 * means physically rotating between two shots. That arm is audible, and this
 * device exists to record audio — so a cycle is postponed while anybody is
 * speaking rather than laying motor noise over a conversation. Quiet minutes
 * are exactly the ones with nothing to lose.
 *
 * The cost of that choice, stated plainly: the times when people are around
 * are the times least likely to be photographed. This produces a timelapse of
 * an empty room punctuated by whatever happens in the gaps between
 * conversations. If that turns out to be the wrong trade, the fix is to drop
 * [waitForQuiet] and accept the noise.
 */
class Timelapse(
    private val context: Context,
    private val metrics: Metrics,
    private val settings: UploadSettings,
    private val dir: File,
) {

    companion object {
        private const val TAG = "R1AudioProbe"

        /**
         * The Wi-Fi name, or empty when there is none or the platform is
         * withholding it.
         *
         * Reading the SSID needs location permission on Android 10 and above;
         * without it the platform returns the literal `<unknown ssid>` rather
         * than failing. Treating that as "not home" means an un-granted install
         * quietly behaves as though it were out, which is the safer way round
         * for the timelapse and the honest one for the position log.
         */
        fun currentSsid(context: Context): String {
            val wifi = runCatching {
                context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            }.getOrNull()
            // The platform wraps the name in quotes.
            val ssid = wifi?.ssid.orEmpty().trim('"')
            return if (ssid == "<unknown ssid>") "" else ssid
        }

        /**
         * Whether [ssid] is the network named in settings. An empty setting
         * means everywhere counts as home.
         *
         * Shared with [Positions], which needs the same verdict for a different
         * reason: the timelapse photographs less often at home because the room
         * does not change, and the position log fixes less often at home
         * because there is no sky. The logging of changes stays with the
         * timelapse so the verdict is recorded once, not twice.
         */
        fun isHome(ssid: String, wanted: String): Boolean =
            wanted.isEmpty() || (ssid.isNotEmpty() && ssid.equals(wanted, ignoreCase = true))

        /** Long edge of the stored JPEG. Enough to see a room, not a face across it. */
        private const val MAX_EDGE = 640

        /**
         * Mean luma, 0-255, below which the room counts as dark.
         *
         * A lit room sits well above this; a bedroom at night sits far below.
         * 18 leaves a dim room on the lit side of the line, which is the safer
         * direction to err — the rule it feeds stops photography altogether.
         */
        private const val MIN_LUMA = 18

        /** At home the room is mostly the same room. */
        private const val HOME_INTERVAL_MS = 15 * 60_000L

        /** Out, every frame is one that will never recur. */
        private const val AWAY_INTERVAL_MS = 5 * 60_000L

        /** How long a cycle may be deferred before it is skipped outright. */
        private const val MAX_DEFER_MS = 120_000L

        /**
         * How far above the room's own baseline a sound has to rise before it
         * counts as something happening. On the envelope's scale — raw RMS
         * shifted down four bits — a quiet room measures 6–16 and speech
         * 152–221, so 16 is comfortably outside the room's own wobble and far
         * below anything worth photographing.
         */
        private const val CHANGE_DELTA = 16

        /**
         * Weight of each new second in the baseline. At one sample a second
         * this averages over roughly ten minutes, which is slow enough that a
         * conversation does not drag the baseline up behind itself, and fast
         * enough to settle after moving to a different room.
         */
        private const val BASELINE_ALPHA = 0.0017

        /** Seconds of audio to hear before any cycle is judged. */
        private const val WARMUP_SECONDS = 30

        /** Let the arm settle before asking the sensor for a frame. */
        private const val SETTLE_MS = 400L

        private const val MOTOR_TIMEOUT_MS = 5_000L

        /**
         * Correction per arm position, added to the sensor's own orientation
         * (90 on this device), giving JPEG_ORIENTATION 270 in front and 90
         * behind.
         *
         * The sensor rides on the arm, so the two positions are 180 degrees
         * apart — which is exactly the pair the camera app has carried since
         * it was calibrated by hand. Setting both to zero here, on the theory
         * that the observed error looked identical from both sides, put the
         * front frame upside down: head at the bottom, lettering mirrored.
         * The original values were right; the observation was made on frames
         * whose content gave no reliable clue which way up they belonged.
         */
        private const val ROTATION_FACE = 180
        private const val ROTATION_BACK = 0
    }

    private val camera = StillCamera(context)
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * A cycle takes seconds: two motor moves, two sensor opens, a settle in
     * between. It must never run on the capture thread — that thread reads
     * AudioRecord, and it is the one thing in this service that cannot be
     * late. Same reasoning as the upload thread.
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-timelapse").apply { isDaemon = true }
    }

    /** When the current cycle first became due, or 0 when none is pending. */
    private var dueSince = 0L

    /**
     * Whether anything has happened since the last photograph.
     *
     * A room that nobody enters looks the same in every frame, and a room with
     * a fan or a television running looks the same *and* sounds the same — the
     * level is high but nothing about it changes. Neither is worth a pair of
     * photographs every five minutes, so the soundscape decides.
     *
     * [baseline] tracks what the room usually sounds like. A sound only counts
     * as an event if it rises meaningfully above that, which is what separates
     * "the television is still on" from "somebody just came in". Speech counts
     * regardless: it is the thing this device exists for.
     */
    private var baseline = -1.0
    private var windowPeak = 0
    private var speechSeen = false
    private var secondsNoted = 0

    /**
     * Brightness of the last front-facing frame, or -1 before the first one.
     *
     * -1 deliberately fails the darkness test, so a freshly started service
     * photographs once rather than deciding the house is asleep on no
     * evidence.
     */
    @Volatile private var lastLuma = -1

    /** Only log the network verdict when it changes, not every cycle. */
    private var lastHomeVerdict: Boolean? = null

    /** Called once per second of captured audio, from the capture thread. */
    fun noteSecond(rms: Int) {
        secondsNoted += 1
        if (rms > windowPeak) windowPeak = rms
        baseline = if (baseline < 0) rms.toDouble() else baseline + BASELINE_ALPHA * (rms - baseline)
    }

    /** Called while the VAD reports speech. */
    fun noteSpeech() {
        speechSeen = true
    }

    /**
     * Whether the device is on the Wi-Fi it is allowed to photograph from.
     *
     * An empty setting means anywhere, which is what every install before this
     * did. Reading the SSID needs location permission on Android 10 and above;
     * without it the platform returns `<unknown ssid>` rather than failing, so
     * an un-granted install quietly stops taking photographs instead of
     * quietly taking them everywhere. That is the right way round.
     */
    private fun onHomeNetwork(): Boolean {
        val ssid = currentSsid(context)
        val home = isHome(ssid, settings.photoSsid)

        // Logged because this decision is invisible in its effects: getting it
        // wrong does not fail, it quietly triples the shutter rate and turns
        // off the quiet-and-dark rule, which reads as "the timelapse is busy"
        // rather than as a fault.
        if (home != lastHomeVerdict) {
            lastHomeVerdict = home
            metrics.write("photo_network", mapOf("ssid" to ssid, "home" to home))
        }
        return home
    }

    @Volatile private var busy = false

    /** Wall-clock of the last cycle that actually ran. */
    @Volatile var lastRunAt = 0L; private set

    /**
     * Called from the capture thread; returns immediately. [speaking] defers
     * the cycle rather than laying motor noise over a conversation.
     */
    fun tick(nowMs: Long, speaking: Boolean) {
        if (busy) return

        // A freshly started service has heard nothing yet, so there is no
        // baseline to compare against and no window to judge. Returning
        // without touching the clock matters: consuming the interval here
        // would push the first real photograph past every restart, and
        // installs are frequent.
        if (secondsNoted < WARMUP_SECONDS) return

        // Nothing can be due before the shorter of the two intervals, and this
        // runs ten times a second — asking WifiManager that often to answer a
        // question that cannot matter yet is pure waste.
        if (nowMs - lastRunAt < AWAY_INTERVAL_MS) return

        // Away from home the scene changes constantly and every frame is one
        // that will never recur, so the shutter runs faster. At home the room
        // is mostly the same room, and a slower cadence says as much with a
        // third of the frames.
        val home = onHomeNetwork()
        val intervalMs = if (home) HOME_INTERVAL_MS else AWAY_INTERVAL_MS
        if (nowMs - lastRunAt < intervalMs) return

        // Asleep: at home, nothing has made a sound, and the last look at the
        // room came back dark. All three together, because any one alone is
        // ordinary — a quiet lit room is someone reading, a dark noisy one is
        // a film. Only the conjunction means the house has gone to bed.
        //
        // Darkness is taken from the previous cycle because it cannot be known
        // before the shutter fires, and a room that was dark fifteen minutes
        // ago is almost certainly still dark. The cost of being wrong is one
        // pair of frames, late.
        if (home && !interesting() && lastLuma in 0 until MIN_LUMA) {
            metrics.write(
                "photo_idle",
                mapOf(
                    "why" to "asleep",
                    "peak" to windowPeak,
                    "baseline" to baseline.toInt(),
                    "luma" to lastLuma,
                ),
            )
            resetWindow()
            lastRunAt = nowMs
            dueSince = 0L
            return
        }

        if (dueSince == 0L) dueSince = nowMs
        if (speaking && nowMs - dueSince < MAX_DEFER_MS) return

        val deferredMs = nowMs - dueSince
        dueSince = 0L
        busy = true
        // Claim the slot now: the cycle is slow, and a tick arriving while it
        // runs must not queue a second one behind it.
        lastRunAt = nowMs
        val peak = windowPeak
        val spoke = speechSeen
        resetWindow()

        worker.execute {
            try {
                run(deferredMs, speaking, peak, spoke)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Speech, or a sound that stands out from what the room normally sounds
     * like. A television left on raises the baseline along with the peak and
     * therefore stops counting, which is the intent: it is the same room, the
     * same scene, minute after minute.
     */
    private fun interesting(): Boolean =
        speechSeen || (baseline >= 0 && windowPeak > baseline + CHANGE_DELTA)

    private fun resetWindow() {
        windowPeak = 0
        speechSeen = false
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun run(deferredMs: Long, spokeThrough: Boolean, peak: Int, spoke: Boolean) {
        val restoreTo = R1Motor.currentAngle
        val at = stamp.format(Date())
        var taken = 0

        // Sitting on power, upright and still: the rear frame is a wall, and
        // will be the same wall in fifteen minutes. Skipping it saves an arm
        // swing on a device whose arm is audible, halves the cycle, and loses
        // nothing that was not already in the last hundred frames.
        //
        // The front frame is never the one skipped. It is the one with the room
        // and the people in it, and it is also where the darkness measurement
        // comes from — dropping it would quietly disable the asleep rule.
        val docked = com.r1.core.Motion.docked(context)

        val positions = if (docked) {
            listOf(Triple("front", R1Motor.MOTOR_FACE, ROTATION_FACE))
        } else {
            listOf(
                Triple("front", R1Motor.MOTOR_FACE, ROTATION_FACE),
                Triple("rear", R1Motor.MOTOR_BACK, ROTATION_BACK),
            )
        }
        for ((label, angle, rotation) in positions) {
            if (!moveAndWait(angle)) {
                metrics.write("photo_skip", mapOf("why" to "motor", "angle" to angle))
                continue
            }
            Thread.sleep(SETTLE_MS)

            val bytes = camera.capture(MAX_EDGE, rotation)
            if (bytes == null) {
                metrics.write("photo_skip", mapOf("why" to "capture", "angle" to angle))
                continue
            }

            // Measured and remembered, not acted on here: the darkness rule is
            // a conjunction with silence, decided before the next shutter.
            // A dark frame with something happening in it is worth keeping —
            // a conversation with the lights off is still a conversation.
            meanLuma(bytes)?.let { if (label == "front") lastLuma = it }

            val file = File(dir, "img_${at}_$label.jpg")
            runCatching { file.writeBytes(bytes) }
                .onSuccess { taken += 1 }
                .onFailure { metrics.write("photo_skip", mapOf("why" to "write")) }
        }

        // Put the arm back where it was: the user may have parked it
        // deliberately, and leaving it staring at the ceiling is rude.
        moveAndWait(restoreTo)

        Log.i(TAG, "timelapse: $taken photo(s)")
        metrics.write(
            "photo",
            mapOf(
                "count" to taken,
                "deferred_ms" to deferredMs,
                "forced" to spokeThrough,
                // Both recorded: `docked` says why only one frame came back,
                // and the tilt is the measurement that might one day let the
                // dock be told from a desk rather than inferred from power.
                "docked" to docked,
                "tilt_deg" to com.r1.core.Motion.placement.tiltDeg,
                // Why this cycle was judged worth taking, so a run of empty
                // frames can be traced back to the trigger that caused them.
                "peak" to peak,
                "baseline" to baseline.toInt(),
                "speech" to spoke,
            ),
        )
    }

    /**
     * Mean brightness of the frame, 0-255, or null if it will not decode.
     *
     * This device has no ambient light sensor — accelerometer, gyroscope and
     * orientation, nothing else — so the picture has to answer the question
     * about itself. Decoding a 640x480 JPEG subsampled by 8 costs a couple of
     * milliseconds on the timelapse thread, which is doing nothing else.
     */
    private fun meanLuma(jpeg: ByteArray): Int? {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        }.getOrNull() ?: return null

        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width == 0 || height == 0) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var total = 0L
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Rec. 601 luma, integer arithmetic; precision beyond this is
                // meaningless against a threshold set by eye.
                total += (r * 299 + g * 587 + b * 114) / 1000
            }
            (total / pixels.size).toInt()
        } finally {
            bitmap.recycle()
        }
    }

    private fun moveAndWait(angle: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        R1Motor.moveTo(angle) { success ->
            ok = success
            latch.countDown()
        }
        latch.await(MOTOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ok
    }
}
