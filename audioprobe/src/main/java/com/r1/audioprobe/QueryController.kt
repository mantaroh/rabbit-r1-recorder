package com.r1.audioprobe

import android.util.Log

/**
 * Turns a double press into a question.
 *
 *   LIFELOG ──double press──▶ ARMED ──speech starts──▶ CAPTURING
 *      ▲                        │                          │
 *      │                     timeout                   speech ends
 *      │                        │                          ▼
 *      └────────────────────────┴──────────────────── PROCESSING
 *
 * The capture is bounded at both ends by the VAD rather than by a timer: the
 * point of the gesture is to press and then just talk, so the device has to
 * work out where the sentence stopped.
 */
class QueryController(
    private val metrics: Metrics,
    /** Fires with the utterance's wall-clock bounds once it completes. */
    private val onUtterance: (startMs: Long, endMs: Long) -> Unit,
) {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** Arming and then saying nothing should not leave it waiting forever. */
        private const val ARMED_TIMEOUT_MS = 8_000L

        /**
         * Pressing mid-sentence should capture from slightly before the press,
         * so the words already in flight are not clipped off the question.
         */
        private const val PRE_ROLL_MS = 300L

        /** A runaway capture must not run until the disk fills. */
        private const val MAX_UTTERANCE_MS = 30_000L

        /** Read by the feedback screen, which holds no handle on the service. */
        @Volatile var uiState: State = State.LIFELOG
    }

    enum class State { LIFELOG, ARMED, CAPTURING, PROCESSING }

    @Volatile var state = State.LIFELOG
        private set(value) {
            field = value
            uiState = value
        }


    private var armedAt = 0L
    private var captureFrom = 0L

    /** Double press: arm, or cancel if already armed. */
    fun onDoublePress(nowMs: Long) {
        when (state) {
            State.LIFELOG -> {
                state = State.ARMED
                armedAt = nowMs
                captureFrom = nowMs - PRE_ROLL_MS
                Log.i(TAG, "query ARMED")
                metrics.write("query", mapOf("state" to "armed"))
            }
            State.ARMED -> {
                // A second gesture is the natural way to say "never mind".
                state = State.LIFELOG
                Log.i(TAG, "query cancelled")
                metrics.write("query", mapOf("state" to "cancelled"))
            }
            // Mid-capture or mid-answer a press is ignored rather than
            // interrupting a question already on its way.
            else -> Unit
        }
    }

    /**
     * Drives the state machine from the recorder loop.
     *
     * [speaking] is the VAD's current verdict and [utteranceEnded] marks the
     * single buffer on which it fell.
     */
    fun tick(nowMs: Long, speaking: Boolean, utteranceEnded: Boolean, vadStart: Long) {
        when (state) {
            State.ARMED -> {
                if (speaking) {
                    state = State.CAPTURING
                    // Whichever came first: the press, or the speech already
                    // under way when it happened.
                    captureFrom = minOf(captureFrom, vadStart)
                    Log.i(TAG, "query CAPTURING from $captureFrom")
                    metrics.write("query", mapOf("state" to "capturing"))
                } else if (nowMs - armedAt >= ARMED_TIMEOUT_MS) {
                    state = State.LIFELOG
                    Log.i(TAG, "query timed out")
                    metrics.write("query", mapOf("state" to "timeout"))
                }
            }

            State.CAPTURING -> {
                val tooLong = nowMs - captureFrom >= MAX_UTTERANCE_MS
                if (utteranceEnded || tooLong) {
                    state = State.PROCESSING
                    metrics.write(
                        "query",
                        mapOf(
                            "state" to "processing",
                            "duration_ms" to (nowMs - captureFrom),
                            "truncated" to tooLong,
                        ),
                    )
                    onUtterance(captureFrom, nowMs)
                }
            }

            else -> Unit
        }
    }

    /** Called when the answer has been handed off, or the attempt failed. */
    fun finish(ok: Boolean, detail: String? = null) {
        state = State.LIFELOG
        metrics.write("query", mapOf("state" to "done", "ok" to ok, "detail" to detail))
    }

    fun label(): String = when (state) {
        State.LIFELOG -> ""
        State.ARMED -> "ASK — speak now"
        State.CAPTURING -> "ASK — listening"
        State.PROCESSING -> "thinking…"
    }
}
