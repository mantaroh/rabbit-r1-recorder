package com.r1.audioprobe

import android.content.Context
import android.content.Intent
import com.r1.core.Motion

/**
 * What a shake opens.
 *
 * The R1 has one wheel and one button, and both are spoken for — the wheel
 * moves a selection, the button belongs to the launcher. Shaking is the only
 * input left that needs no hand position and works with the screen dark, which
 * is why it is worth the false-positive risk at all.
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
        Motion.onShake = { peaks ->
            val placement = Motion.placement
            val target = when (peaks) {
                in CAMERA -> "camera"
                in CHAT -> "chat"
                else -> "none"
            }

            // Logged before acting, and logged whatever happens — a mapping
            // that has to be calibrated needs the rejected gestures too, and
            // the ones that fired while the device was face down in a bag are
            // the interesting ones.
            metrics.write(
                "shake",
                mapOf(
                    "peaks" to peaks,
                    "target" to target,
                    "posture" to placement.posture.name,
                    "moving" to placement.moving,
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
                mapOf("posture" to placement.posture.name, "moving" to placement.moving),
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
