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

    @Volatile private var busy = false

    /** Wall-clock of the last cycle that actually ran. */
    @Volatile var lastRunAt = 0L; private set

    /**
     * Called from the capture thread; returns immediately. [speaking] defers
     * the cycle rather than laying motor noise over a conversation.
     */
    fun tick(nowMs: Long, intervalMs: Long, speaking: Boolean) {
        if (busy) return
        if (nowMs - lastRunAt < intervalMs) return

        if (dueSince == 0L) dueSince = nowMs
        if (speaking && nowMs - dueSince < MAX_DEFER_MS) return

        val deferredMs = nowMs - dueSince
        dueSince = 0L
        busy = true
        // Claim the slot now: the cycle is slow, and a tick arriving while it
        // runs must not queue a second one behind it.
        lastRunAt = nowMs
        worker.execute {
            try {
                run(deferredMs, speaking)
            } finally {
                busy = false
            }
        }
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun run(deferredMs: Long, spokeThrough: Boolean) {
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
            mapOf("count" to taken, "deferred_ms" to deferredMs, "forced" to spokeThrough),
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
