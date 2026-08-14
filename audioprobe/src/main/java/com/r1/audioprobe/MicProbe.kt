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
     * Where shadowing starts to work.
     *
     * A broadband measurement found nothing — 0.2 dB between speaking in front
     * and speaking behind — and the reason is diffraction, not the device.
     * Speech energy sits between roughly 100 Hz and 4 kHz, which is 8.6 cm to
     * 3.4 m of wavelength against a body about 8 cm across; sound whose
     * wavelength exceeds the obstacle simply passes around it.
     *
     * Above 4 kHz the wavelength drops below the device and it can finally
     * cast an acoustic shadow. Fricatives and sibilance live there. So the
     * high band is measured separately, alongside the broadband figure that is
     * known not to work, because the comparison is the whole point.
     */
    private const val HIGHPASS_HZ = 4000.0

    /** One-pole sections cascaded; 4 gives 24 dB/octave, ~48 dB down at 1 kHz. */
    private const val HIGHPASS_POLES = 4

    /**
     * Widest inter-channel delay worth searching, in samples.
     *
     * Level differences failed because sound diffracts around an obstacle
     * smaller than its wavelength. Delay does not: however the wave gets
     * there, it reaches the near capsule first. Two capsules 50-80 mm apart
     * are 7-11 samples apart at 48 kHz, so ±24 covers the geometry with room
     * for a wrong guess about the spacing.
     *
     * The sign is the whole experiment. Magnitude would give an angle, which
     * this baseline is too short to resolve reliably; sign only needs the
     * correlation peak to land on the correct side of zero.
     */
    private const val MAX_LAG = 24

    /**
     * Cascaded one-pole high-pass. Not a good filter, but a predictable one,
     * and steep enough that what reaches the accumulator is the band the
     * experiment is about.
     */
    /**
     * Lag, in samples, at which the two channels best line up.
     *
     * Positive means [right] arrived later — the source was nearer the capsule
     * feeding the left channel. Returns null when neither channel carries
     * enough energy for the peak to mean anything, so silence does not vote.
     *
     * The channels are high-passed first. Low frequencies have wavelengths
     * many times the baseline, so they contribute a broad, flat correlation
     * peak that buries the sharp one the delay actually produces.
     */
    private fun bestLag(left: DoubleArray, right: DoubleArray, n: Int): Pair<Int, Double>? {
        var energy = 0.0
        for (i in 0 until n) energy += left[i] * left[i] + right[i] * right[i]
        if (energy < n * 4.0) return null

        var bestLag = 0
        var bestScore = -Double.MAX_VALUE
        for (lag in -MAX_LAG..MAX_LAG) {
            var sum = 0.0
            val from = maxOf(0, -lag)
            val to = minOf(n, n - lag)
            for (i in from until to) sum += left[i] * right[i + lag]
            if (sum > bestScore) {
                bestScore = sum
                bestLag = lag
            }
        }
        return bestLag to bestScore / energy
    }

    private class HighPass(cutoffHz: Double, sampleRate: Int, poles: Int) {
        private val alpha: Double = run {
            val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
            val dt = 1.0 / sampleRate
            rc / (rc + dt)
        }
        private val lastIn = DoubleArray(poles)
        private val lastOut = DoubleArray(poles)

        fun process(sample: Double): Double {
            var x = sample
            for (i in lastIn.indices) {
                val y = alpha * (lastOut[i] + x - lastIn[i])
                lastIn[i] = x
                lastOut[i] = y
                x = y
            }
            return x
        }
    }

    /**
     * Blocks for [seconds], writing one metrics line per window. Runs on the
     * capture thread with the mono recorder released, because two AudioRecords
     * on one device is a good way to be handed silence.
     */
    /**
     * [cue] is buzzed with a count so the person in the room knows which half
     * they are in. Telling them over a chat window does not work: by the time
     * the message is read the window has already started, and the whole
     * experiment depends on knowing which side each measurement came from.
     * One buzz — speak from the front. Two — move behind. Three — done.
     */
    @SuppressLint("MissingPermission")
    fun run(
        sampleRate: Int,
        audioSource: Int,
        seconds: Int,
        metrics: Metrics,
        cue: (Int) -> Unit,
    ) {
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
            val started = System.currentTimeMillis()
            val until = started + seconds * 1000L
            val halfway = started + seconds * 500L
            var switched = false

            // State has to survive across reads, so the filters live outside
            // the loop; restarting them every window would ring at every
            // boundary and put energy back into the band being measured.
            val hpL = HighPass(HIGHPASS_HZ, sampleRate, HIGHPASS_POLES)
            val hpR = HighPass(HIGHPASS_HZ, sampleRate, HIGHPASS_POLES)

            // Deinterleaved, high-passed copies for the correlation.
            val chanL = DoubleArray(frames)
            val chanR = DoubleArray(frames)

            cue(1)
            while (System.currentTimeMillis() < until) {
                if (!switched && System.currentTimeMillis() >= halfway) {
                    switched = true
                    cue(2)
                    metrics.write("mic_probe", mapOf("state" to "switch"))
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sumL = 0.0
                var sumR = 0.0
                var highL = 0.0
                var highR = 0.0
                var identical = true
                var i = 0
                while (i + 1 < read) {
                    val l = buffer[i].toDouble()
                    val r = buffer[i + 1].toDouble()
                    if (identical && abs(l - r) > 0.5) identical = false
                    sumL += l * l
                    sumR += r * r
                    val fl = hpL.process(l)
                    val fr = hpR.process(r)
                    highL += fl * fl
                    highR += fr * fr
                    val frame = i / 2
                    if (frame < frames) {
                        chanL[frame] = fl
                        chanR[frame] = fr
                    }
                    i += 2
                }
                val pairs = read / 2
                if (pairs == 0) continue

                val rmsL = sqrt(sumL / pairs)
                val rmsR = sqrt(sumR / pairs)
                val hiL = sqrt(highL / pairs)
                val hiR = sqrt(highR / pairs)
                val lag = bestLag(chanL, chanR, minOf(pairs, frames))
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
                        // The band where the body can actually shadow.
                        "hl" to hiL.toInt(),
                        "hr" to hiR.toInt(),
                        "hdb" to if (hiR > 1 && hiL > 1) {
                            String.format("%.1f", 20 * kotlin.math.log10(hiL / hiR))
                        } else "n/a",
                        // Bit-identical channels mean one capsule duplicated.
                        "identical" to identical,
                        // Which half this window belongs to, so the two
                        // populations can be compared without matching
                        // timestamps by hand.
                        "half" to if (switched) "behind" else "front",
                        // Inter-channel delay in samples, and how sharply the
                        // correlation picked it. This is the measurement that
                        // matters: diffraction erases the level difference but
                        // not the time of flight.
                        "lag" to (lag?.first ?: "n/a"),
                        "corr" to (lag?.second?.let { String.format("%.3f", it) } ?: "n/a"),
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mic probe failed", t)
            metrics.write("mic_probe", mapOf("error" to (t.message ?: "?").take(120)))
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            cue(3)
            metrics.write("mic_probe", mapOf("state" to "done"))
        }
    }
}
