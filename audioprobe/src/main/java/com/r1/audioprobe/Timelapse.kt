package com.r1.audioprobe

import android.content.Context
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
    context: Context,
    private val metrics: Metrics,
    private val dir: File,
) {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** Long edge of the stored JPEG. Enough to see a room, not a face across it. */
        private const val MAX_EDGE = 640

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
    }

    private val camera = StillCamera(context)
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

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

    @Volatile private var busy = false

    /** Wall-clock of the last cycle that actually ran. */
    @Volatile var lastRunAt = 0L; private set

    /**
     * Called from the capture thread; returns immediately. [speaking] defers
     * the cycle rather than laying motor noise over a conversation.
     */
    fun tick(nowMs: Long, intervalMs: Long, speaking: Boolean) {
        if (busy) return

        // A freshly started service has heard nothing yet, so there is no
        // baseline to compare against and no window to judge. Returning
        // without touching the clock matters: consuming the interval here
        // would push the first real photograph five minutes past every
        // restart, and installs are frequent.
        if (secondsNoted < WARMUP_SECONDS) return

        if (nowMs - lastRunAt < intervalMs) return

        // Nothing happened since the last pair, so the pair would be identical
        // to it. Reset the window and wait — including the clock, so the next
        // event is photographed promptly rather than at the next multiple of
        // the interval.
        if (!interesting()) {
            metrics.write(
                "photo_idle",
                mapOf("peak" to windowPeak, "baseline" to baseline.toInt()),
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

        for ((label, angle) in listOf("front" to R1Motor.MOTOR_FACE, "rear" to R1Motor.MOTOR_BACK)) {
            if (!moveAndWait(angle)) {
                metrics.write("photo_skip", mapOf("why" to "motor", "angle" to angle))
                continue
            }
            Thread.sleep(SETTLE_MS)

            val bytes = camera.capture(MAX_EDGE)
            if (bytes == null) {
                metrics.write("photo_skip", mapOf("why" to "capture", "angle" to angle))
                continue
            }

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
                // Why this cycle was judged worth taking, so a run of empty
                // frames can be traced back to the trigger that caused them.
                "peak" to peak,
                "baseline" to baseline.toInt(),
                "speech" to spoke,
            ),
        )
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
