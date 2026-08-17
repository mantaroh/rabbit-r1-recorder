package com.r1.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How the device is being held, and when it is shaken.
 *
 * One accelerometer listener, two derived signals — the same arrangement as the
 * per-second RMS, which feeds both the speech gate and the timelapse. Posture
 * and shake come from the same stream because they are the same measurement
 * split two ways: what the low frequencies say (which way is down) and what the
 * high frequencies say (how hard it is moving).
 *
 * The raw accelerometer rather than the platform's GRAVITY and
 * LINEAR_ACCELERATION sensors, which look like the obvious choice and are not.
 * Both are AOSP fusion sensors on this device, and asking for them can pull the
 * gyroscope into the fusion for a result a two-line filter already gives. One
 * physical sensor, no fusion, no gyro.
 *
 * This device reports `minRate=50.00Hz` on the accelerometer, so there is no
 * slower option to ask for: registering at all means 50 Hz. Against a 117 mA
 * baseline that is expected to be lost in the noise, but it has not been
 * measured, and "expected to be negligible" is how the timelapse's shutter rate
 * got away with tripling itself for 48 minutes.
 */
object Motion {

    private const val TAG = "R1AudioProbe"

    /** Which way is down, from the low-passed acceleration. */
    enum class Posture {
        /** Standing on its base, screen toward the room. The dock position. */
        UPRIGHT,

        /** Standing on its head. */
        INVERTED,

        /** Flat on a table, screen up. */
        FACE_UP,

        /** Flat on a table, screen down — the camera sees nothing. */
        FACE_DOWN,

        /** On its left or right edge. */
        ON_SIDE,

        UNKNOWN,
    }

    /**
     * Posture and motion are orthogonal and are kept that way. An upright
     * device may be docked or in a hand, and collapsing the two into one
     * "docked" state loses the difference exactly when it matters.
     */
    data class Placement(
        val posture: Posture,
        val moving: Boolean,
        /**
         * Degrees from vertical in the screen's own plane; positive leans back.
         *
         * Recorded rather than used. The dock on this device measures about
         * 3.6 degrees — (0.06, 9.58, 0.61) — and standing the device on a desk
         * measures about zero, which is a real difference and far too small a
         * one to separate a dock from a desk that is not quite level. It is
         * logged so that a few weeks of readings can say whether the two ever
         * actually separate, instead of a threshold being invented now.
         */
        val tiltDeg: Int,
    )

    // ---------------------------------------------------------- tuning ---

    /**
     * How much of the previous estimate survives each sample. At 50 Hz this
     * settles the gravity estimate in about a second — slow enough that a
     * shake does not tilt it, fast enough that setting the device down is
     * noticed before anybody looks at the screen.
     */
    private const val GRAVITY_ALPHA = 0.98

    /** How far from 9.81 the dominant axis has to be to name a posture. */
    private const val POSTURE_MIN = 6.5f

    /**
     * Posture has to hold this long before it is reported. Picking the device
     * up sweeps through two or three postures on the way, and every one of
     * them would otherwise be an event.
     */
    private const val POSTURE_SETTLE_MS = 3_000L

    /** Linear acceleration, m/s^2, above which the device counts as moving. */
    private const val MOVING_THRESHOLD = 1.5f

    /** How long after the last disturbance the device is still called moving. */
    private const val MOVING_HOLD_MS = 2_000L

    /**
     * Peak detection is hysteretic: a peak is only counted after the signal has
     * fallen back below [SHAKE_LOW], so one hard jolt cannot ring up a count on
     * its own decay.
     *
     * 14 is provisional. Walking peaks around 3-6 m/s^2 and running can reach
     * low double figures, while a deliberate shake reaches 20-30 — but this
     * device spends its day being carried, and a false trigger swings the
     * camera arm. Every accepted run is logged with its peak count so the
     * number can be set from real gestures rather than from this paragraph.
     */
    private const val SHAKE_HIGH = 14.0f
    private const val SHAKE_LOW = 4.0f

    /**
     * Quiet time before a run of peaks is called finished and reported. This
     * is also what separates one gesture from the next: peaks further apart
     * than this are, by definition, two gestures.
     */
    private const val SHAKE_SETTLE_MS = 450L

    // ----------------------------------------------------------- state ---

    @Volatile
    var placement = Placement(Posture.UNKNOWN, moving = false, tiltDeg = 0)
        private set

    /**
     * Standing still, upright, on power — the state in which the rear camera is
     * pointed at a wall.
     *
     * Named for what it is used for rather than for the dock, because it cannot
     * actually tell a dock from a cable and a desk. The dock's three and a half
     * degrees of tilt does not survive a desk that is not level, and charging
     * says "plugged in", not "seated". What all the members of the set have in
     * common is the thing that matters: the device is not going anywhere, and
     * the view behind it will be identical in fifteen minutes.
     *
     * Being wrong is cheap in one direction and not the other, so it errs
     * toward taking both frames: a missed rear frame of a wall costs nothing,
     * a missed rear frame of a room is gone.
     */
    fun docked(context: Context): Boolean {
        val now = placement
        return now.posture == Posture.UPRIGHT && !now.moving && charging(context)
    }

    private fun charging(context: Context): Boolean = runCatching {
        context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    /**
     * Fired when the **posture** changes and has held for [POSTURE_SETTLE_MS].
     *
     * Deliberately not fired for `moving`, which flips every couple of seconds
     * in a pocket and would turn the metrics log into a pedometer. Read
     * [placement] for the live value.
     */
    @Volatile
    var onPlacementChange: ((Placement) -> Unit)? = null

    /**
     * A completed shake, carrying how many peaks it contained.
     *
     * The raw count rather than a gesture name, because how many peaks a person
     * produces when they "shake it twice" is a fact about the person and the
     * wrist, not about the code. Whoever maps counts to actions can see what
     * the hand actually did; see the mapping in the app, and the `shake` events
     * in the metrics log for calibrating it.
     */
    @Volatile
    var onShake: ((Int) -> Unit)? = null

    private var manager: SensorManager? = null
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0
    private var primed = false

    private var lastDisturbanceAt = 0L
    private var candidate = Posture.UNKNOWN
    private var candidateSince = 0L

    private var armed = true
    private var peaks = 0
    private var lastPeakAt = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = consume(event)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(context: Context) {
        if (manager != null) return
        val sensors = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "Motion: no accelerometer")
            return
        }
        // SENSOR_DELAY_GAME is 50 Hz, which is also this sensor's floor. No
        // batching: a batched gesture arrives after the moment it was meant to
        // act on.
        sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        manager = sensors
        Log.i(TAG, "Motion: watching accelerometer")
    }

    fun stop() {
        manager?.unregisterListener(listener)
        manager = null
        primed = false
        peaks = 0
    }

    private fun consume(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])

        if (!primed) {
            gravityX = x.toDouble(); gravityY = y.toDouble(); gravityZ = z.toDouble()
            primed = true
            return
        }

        gravityX = GRAVITY_ALPHA * gravityX + (1 - GRAVITY_ALPHA) * x
        gravityY = GRAVITY_ALPHA * gravityY + (1 - GRAVITY_ALPHA) * y
        gravityZ = GRAVITY_ALPHA * gravityZ + (1 - GRAVITY_ALPHA) * z

        val linear = sqrt(
            (x - gravityX) * (x - gravityX) +
                (y - gravityY) * (y - gravityY) +
                (z - gravityZ) * (z - gravityZ),
        ).toFloat()

        detectShake(linear, now)
        classify(linear, now)
    }

    private fun detectShake(linear: Float, now: Long) {
        if (armed && linear > SHAKE_HIGH) {
            peaks += 1
            lastPeakAt = now
            armed = false
        } else if (!armed && linear < SHAKE_LOW) {
            armed = true
        }

        // Reported once the hand has stopped, not on the way — otherwise a
        // three-shake fires the two-shake action first and lands somewhere
        // nobody asked for.
        if (peaks > 0 && now - lastPeakAt > SHAKE_SETTLE_MS) {
            val count = peaks
            peaks = 0
            // A single peak is a knock, a set-down, or a pocket. Two is the
            // smallest thing a person can mean.
            if (count >= 2) {
                runCatching { onShake?.invoke(count) }
                    .onFailure { Log.e(TAG, "shake handler failed", it) }
            }
        }
    }

    private fun classify(linear: Float, now: Long) {
        if (linear > MOVING_THRESHOLD) lastDisturbanceAt = now
        val moving = now - lastDisturbanceAt < MOVING_HOLD_MS

        val posture = when {
            gravityY > POSTURE_MIN -> Posture.UPRIGHT
            gravityY < -POSTURE_MIN -> Posture.INVERTED
            gravityZ > POSTURE_MIN -> Posture.FACE_UP
            gravityZ < -POSTURE_MIN -> Posture.FACE_DOWN
            abs(gravityX) > POSTURE_MIN -> Posture.ON_SIDE
            else -> Posture.UNKNOWN
        }

        // Signed by Z so leaning back reads positive; taken in the screen's own
        // plane, which is the axis a stand tilts around.
        val tilt = Math.toDegrees(kotlin.math.atan2(gravityZ, gravityY)).toInt()

        // The live value tracks motion continuously; only the posture half is
        // debounced, and only the posture half is announced.
        val settled = posture == candidate && now - candidateSince >= POSTURE_SETTLE_MS
        val shown = if (settled) posture else placement.posture
        val previous = placement
        placement = Placement(shown, moving, tilt)

        if (posture != candidate) {
            candidate = posture
            candidateSince = now
            return
        }
        if (shown == previous.posture) return
        runCatching { onPlacementChange?.invoke(placement) }
            .onFailure { Log.e(TAG, "placement handler failed", it) }
    }
}
