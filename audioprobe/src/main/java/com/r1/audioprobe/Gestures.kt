package com.r1.audioprobe

import android.content.Context
import android.content.Intent
import com.r1.core.Motion

/**
 * What a shake opens.
 *
 * The R1 has one wheel and one button, and both are spoken for — the wheel
 * moves a selection, the button belongs to the launcher. Shaking is the only
 * input left that needs no hand position, which is why it is worth a
 * false-positive risk at all.
 *
 * Only while the screen is on; see the gate below and what a walk did to the
 * version without it.
 *
 * Counts, not names. How many peaks a wrist produces when its owner "shakes it
 * twice" is a fact about the wrist, so the mapping is a range rather than an
 * equality and every gesture is logged with what it actually measured.
 *
 * These ranges are measured, from ten deliberate gestures on 2026-08-17:
 *
 *   "twice"        2, 2, 2, 2, 2
 *   "three times"  3, 4, 4, 5, 4
 *
 * Two shakes produced exactly two peaks every single time and three shakes
 * never produced fewer than three, so the boundary sits in the one place the
 * data leaves for it. Note that the counts are not the shakes: three shakes of
 * the wrist reverse direction four times, not three. Guessing a linear mapping
 * is what put the first of those gestures on the wrong side.
 */
object Gestures {

    /** Exactly two peaks: the camera. */
    private val CAMERA = 2..2

    /** Three or more: the chat. */
    private val CHAT = 3..Int.MAX_VALUE

    /** The standalone R1 Camera, which is a separate app on this device. */
    private const val CAMERA_PACKAGE = "com.r1.camerawrapper"
    private const val CAMERA_ACTIVITY = "com.r1.camerawrapper.MainActivity"

    /**
     * Installed once by the recorder, which is the only component alive for the
     * whole day. [Motion] must already be started.
     */
    fun install(context: Context, metrics: Metrics) {
        Motion.onShake = { peaks, hardest ->
            val placement = Motion.placement

            // The screen has to be on.
            //
            // Fifty-five minutes of walking produced one run of exactly two
            // peaks, in a pocket, with the posture reading UNKNOWN — and it
            // opened the camera and swung the arm. Peak counts cannot separate
            // that from a deliberate gesture, because "shake it twice" is also
            // two peaks.
            //
            // What separates them is that this gesture only means anything when
            // there is a screen to switch. Nobody shakes a device in a bag to
            // change what it is showing. The cost is the case this was first
            // justified by — reaching the camera without waking the device —
            // and one false launch an hour is too much to pay for it.
            val awake = runCatching {
                context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
            }.getOrDefault(true)

            val target = when {
                !awake -> "asleep"
                peaks in CAMERA -> "camera"
                peaks in CHAT -> "chat"
                else -> "none"
            }

            // Logged before acting, and logged whatever happens — a mapping
            // that has to be calibrated needs the rejected gestures too, and
            // the ones that fired while the device was face down in a bag are
            // the interesting ones. `peak_ms2` is what the next threshold will
            // be set from: counts have already been shown not to separate.
            metrics.write(
                "shake",
                mapOf(
                    "peaks" to peaks,
                    "peak_ms2" to hardest.toInt(),
                    "target" to target,
                    "posture" to placement.posture.name,
                    "moving" to placement.moving,
                    "awake" to awake,
                ),
            )

            // A shake is a deliberate act, so it leaves standby the way the
            // side button does rather than opening a screen behind it.
            SignageActivity.dismiss(System.currentTimeMillis())

            when (target) {
                "camera" -> launch(context, cameraIntent())
                "chat" -> launch(context, Intent(context, com.r1.hermes.ChatActivity::class.java))
            }
        }

        Motion.onPlacementChange = { placement ->
            metrics.write(
                "placement",
                mapOf(
                    "posture" to placement.posture.name,
                    "moving" to placement.moving,
                    // The angle the stand is recognised by; kept in the log so
                    // a stand that warps, or a second one printed differently,
                    // shows up as a number rather than as photographs quietly
                    // going back to two a cycle.
                    "tilt_deg" to placement.tiltDeg,
                    "docked" to Motion.inStand(),
                ),
            )
        }
    }

    private fun cameraIntent() = Intent(Intent.ACTION_MAIN)
        .setClassName(CAMERA_PACKAGE, CAMERA_ACTIVITY)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    /**
     * Started from a service, which Android normally refuses. It is allowed
     * here for the same reason the standby display is: this app owns a visible
     * window nearly all the time, and `BAL_ALLOW_VISIBLE_WINDOW` covers it.
     * When it does not — screen genuinely off, nothing of ours in front — the
     * launch is dropped and the shake does nothing, which is the right failure.
     */
    private fun launch(context: Context, intent: Intent) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
