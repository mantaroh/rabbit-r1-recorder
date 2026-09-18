package com.r1.audioprobe

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * 消灯: the panel off on purpose, without stopping anything behind it.
 *
 * The device is a recorder that happens to have a screen, and at night the
 * screen is the only part in the way. On a cable every other screen holds the
 * panel awake deliberately — see `Idle.keepAwakeWhileCharging` — so left alone
 * the standby display glows all night next to a bed. Nothing here stops the
 * recording: the recorder is a foreground service and does not care whether
 * anything is lit.
 *
 * Two things happen and they are not the same thing. The window goes black at
 * the lowest brightness an app may ask for, which is immediate and needs no
 * permission; then [KeyService] is asked to turn the panel off for real, which
 * is what actually saves the light and the milliamps. If the second cannot
 * happen the first still stands, and the device's own sixty-second timeout
 * finishes the job.
 *
 * Deliberately never calls `Idle.watch`, like [CueActivity]: arming that timer
 * is also what holds the panel awake while charging, which is the exact thing
 * this screen exists to stop. It also means the standby display cannot appear
 * over the top of a screen that was asked to go dark.
 */
class DarkActivity : Activity() {

    private companion object {
        private const val TAG = "R1AudioProbe"
    }

    /**
     * Whether the panel has already been asked to go off.
     *
     * The way back is this flag. Turning the screen off pauses this Activity,
     * so the next resume is the device coming back — and it must not go dark
     * again in answer to being woken, which is what a screen that turned
     * itself off every time you looked at it would do. It finishes instead,
     * and the screen underneath is the one you left.
     */
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })

        // Per-window, so nothing has to be put back afterwards and no other
        // screen is affected: this is not the system brightness setting, which
        // an app cannot write without being granted WRITE_SETTINGS by hand.
        window.attributes = window.attributes.apply { screenBrightness = 0f }
    }

    override fun onResume() {
        super.onResume()
        if (asked) {
            finish()
            return
        }
        asked = true

        // On the next frame, so the panel is already black when it goes off
        // rather than showing whatever was there for the last few milliseconds
        // of being lit.
        window.decorView.post {
            if (isFinishing) return@post
            val off = KeyService.darken()
            android.util.Log.i(TAG, "dark: lock screen accepted=$off")
            Metrics(this).write("dark", mapOf("locked" to off))
        }
    }

    /**
     * Any touch brings it back.
     *
     * For the moment between asking and the panel going off, and for a device
     * whose accessibility service is not connected — there the screen is only
     * dark, and a dark screen with no way out is indistinguishable from one
     * that has crashed.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    /** So does the wheel, for the same reason. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
