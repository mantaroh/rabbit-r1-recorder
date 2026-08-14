package com.r1.core

import android.app.Activity
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * One idle timer for the whole app.
 *
 * Shared rather than per-screen because moving between the chat and the
 * settings is not idleness, and per-screen timers would each conclude it was.
 * Activities call [touch] from `onUserInteraction`, which the platform already
 * invokes for every tap and key.
 *
 * Lives in :core so the chat — whose source both apps compile — can arm it
 * without knowing what happens when it fires. [onIdle] is what happens, and an
 * app that sets nothing simply never goes to standby, which is the right
 * behaviour for the standalone chat client.
 */
object Idle {

    /** How long without input before the device is considered idle. */
    private const val TIMEOUT_MS = 60_000L

    /**
     * What to do when the timer expires. Set once at application start.
     *
     * Only called while charging: whatever this shows will hold the panel
     * awake, and doing that on battery turns a day of recording into an
     * afternoon.
     */
    @Volatile var onIdle: ((Activity) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var armed: Activity? = null

    private val fire = Runnable {
        val activity = armed
        val charging = activity?.let { charging(it) } ?: false
        // Every reason this can decline to fire, in one line. It failed
        // silently three ways in a row otherwise.
        android.util.Log.i(
            "R1AudioProbe",
            "idle fired: armed=${activity?.javaClass?.simpleName} " +
                "charging=$charging handler=${onIdle != null}",
        )
        if (activity == null) return@Runnable
        if (activity.isFinishing || activity.isDestroyed) return@Runnable
        if (!charging) return@Runnable
        onIdle?.invoke(activity)
    }

    /**
     * Call from onResume.
     *
     * Also holds the panel awake while charging, and that is not decoration:
     * without it the display sleeps first, the Activity pauses, the timer is
     * released, and standby never arrives — which is exactly what happened
     * the first time this was tried.
     */
    fun watch(activity: Activity) {
        armed = activity
        keepAwakeWhileCharging(activity)
        touch()
    }

    /**
     * Screen stays lit on mains and follows the system timeout on battery.
     * Recording alone is about 62 mA against 1010 mAh; a lit panel on top of
     * that turns a day of runtime into an afternoon.
     */
    fun keepAwakeWhileCharging(activity: Activity) {
        if (charging(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Call from onPause, so a finished screen cannot be revived. */
    fun release(activity: Activity) {
        if (armed === activity) armed = null
        handler.removeCallbacks(fire)
    }

    /** Resets the countdown. Cheap enough for every touch. */
    fun touch() {
        handler.removeCallbacks(fire)
        if (armed != null) handler.postDelayed(fire, TIMEOUT_MS)
    }

    private fun charging(activity: Activity): Boolean = runCatching {
        activity.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)
}
