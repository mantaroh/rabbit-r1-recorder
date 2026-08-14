package com.r1.audioprobe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Answers one question: are the two built-in microphones actually separate?
 *
 * The device advertises `Built-In Mic` and `Built-In Back Mic`, and the primary
 * input accepts `AUDIO_CHANNEL_IN_STEREO`. Neither fact promises that opening a
 * stereo stream yields two physically distinct capsules — plenty of Android
 * devices hand back a processed or duplicated stereo image, and a duplicated
 * one carries no direction at all.
 *
 * Distinguishing front from back does not need phase or time-of-arrival, which
 * this hardware is far too small for: at 48 kHz a 50–80 mm baseline is only
 * 7–11 samples of delay, and a real room's reflections swamp that. It needs
 * only the body of the device to shadow each capsule from the opposite side,
 * which shows up as a level difference. So this measures levels.
 *
 * Run it, speak from in front, then from behind, and read the two columns.
 * Identical columns mean the stereo is synthetic and direction is not
 * available; a consistent sign flip between the two positions means it is.
 */
object MicProbe {

    private const val TAG = "R1AudioProbe"
    private const val WINDOW_MS = 250L

    /**
     * Blocks for [seconds], writing one metrics line per window. Runs on the
     * capture thread with the mono recorder released, because two AudioRecords
     * on one device is a good way to be handed silence.
     */
    @SuppressLint("MissingPermission")
    fun run(sampleRate: Int, audioSource: Int, seconds: Int, metrics: Metrics) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            metrics.write("mic_probe", mapOf("error" to "stereo unsupported at $sampleRate"))
            return
        }

        val record = runCatching {
            AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            metrics.write("mic_probe", mapOf("error" to "stereo AudioRecord failed"))
            return
        }

        metrics.write(
            "mic_probe",
            mapOf("state" to "start", "rate" to sampleRate, "channels" to record.channelCount),
        )

        try {
            record.startRecording()
            // Interleaved L,R,L,R — one window of each per read.
            val frames = (sampleRate * WINDOW_MS / 1000).toInt()
            val buffer = ShortArray(frames * 2)
            val until = System.currentTimeMillis() + seconds * 1000L

            while (System.currentTimeMillis() < until) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sumL = 0.0
                var sumR = 0.0
                var identical = true
                var i = 0
                while (i + 1 < read) {
                    val l = buffer[i].toDouble()
                    val r = buffer[i + 1].toDouble()
                    if (identical && abs(l - r) > 0.5) identical = false
                    sumL += l * l
                    sumR += r * r
                    i += 2
                }
                val pairs = read / 2
                if (pairs == 0) continue

                val rmsL = sqrt(sumL / pairs)
                val rmsR = sqrt(sumR / pairs)
                metrics.write(
                    "mic_probe",
                    mapOf(
                        // Raw RMS per channel, and the ratio in dB — the sign of
                        // which is the whole experiment.
                        "l" to rmsL.toInt(),
                        "r" to rmsR.toInt(),
                        "db" to if (rmsR > 1 && rmsL > 1) {
                            String.format("%.1f", 20 * kotlin.math.log10(rmsL / rmsR))
                        } else "n/a",
                        // Bit-identical channels mean one capsule duplicated.
                        "identical" to identical,
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mic probe failed", t)
            metrics.write("mic_probe", mapOf("error" to (t.message ?: "?").take(120)))
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            metrics.write("mic_probe", mapOf("state" to "done"))
        }
    }
}
