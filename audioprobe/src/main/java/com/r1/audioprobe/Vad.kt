package com.r1.audioprobe

import kotlin.math.sqrt

/**
 * Energy voice-activity detection.
 *
 * Deliberately simple. A model-based VAD would be more accurate, but the
 * measured gap between this room's noise floor and speech is an order of
 * magnitude — quiet RMS ran 0.003–0.005 against 0.10–0.12 while people talked —
 * so a threshold tracking the floor is enough to tell them apart.
 *
 * Two jobs, and they want different behaviour:
 *
 *  - deciding whether a whole segment is worth uploading, which only needs the
 *    fraction of frames containing speech;
 *  - deciding when an utterance has ended, which needs hysteresis so a pause
 *    between words does not look like the end of a sentence.
 */
class Vad(
    /** Speech must exceed the noise floor by this factor. */
    private val triggerMultiple: Double = 4.0,
    /** Never trigger below this, however quiet the room gets. */
    private val absoluteFloor: Double = 0.006,
    /** Silence this long ends an utterance. */
    private val hangoverMs: Long = 1_200,
) {

    /** Rises fast, falls slowly: a burst of speech must not drag the floor up. */
    private var noiseFloor = 0.01
    private var speaking = false
    private var lastVoiceAt = 0L
    private var utteranceStartedAt = 0L

    private var framesTotal = 0L
    private var framesVoiced = 0L

    val isSpeaking: Boolean get() = speaking
    val utteranceStart: Long get() = utteranceStartedAt

    /** Fraction of frames since [resetCounts] that contained speech. */
    val voicedFraction: Double
        get() = if (framesTotal == 0L) 0.0 else framesVoiced.toDouble() / framesTotal

    fun resetCounts() {
        framesTotal = 0
        framesVoiced = 0
    }

    /** What the noise floor has settled to; useful for tuning against real rooms. */
    fun floor(): Double = noiseFloor

    /**
     * Feeds one buffer. Returns true when this buffer ended an utterance, so
     * the caller can act on the boundary exactly once.
     */
    fun accept(samples: ShortArray, count: Int, nowMs: Long): Boolean {
        if (count <= 0) return false

        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble() / Short.MAX_VALUE
            sum += v * v
        }
        val rms = sqrt(sum / count)

        val threshold = maxOf(absoluteFloor, noiseFloor * triggerMultiple)
        val voiced = rms > threshold

        // Track the floor only while quiet, and let it climb slowly so a room
        // that gets noisier is followed without speech pulling it up.
        noiseFloor = if (voiced) {
            noiseFloor * 0.999 + rms * 0.001
        } else {
            noiseFloor * 0.95 + rms * 0.05
        }

        framesTotal += 1
        if (voiced) framesVoiced += 1

        if (voiced) {
            if (!speaking) {
                speaking = true
                utteranceStartedAt = nowMs
            }
            lastVoiceAt = nowMs
            return false
        }

        if (speaking && nowMs - lastVoiceAt >= hangoverMs) {
            speaking = false
            return true
        }
        return false
    }
}
