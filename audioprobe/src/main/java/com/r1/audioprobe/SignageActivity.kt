package com.r1.audioprobe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The standby display: what the device shows when nobody is using it.
 *
 * Appears after a minute of no interaction and leaves on the first touch or
 * turn of the wheel, so it is never in the way. The wheel also steps between
 * screens without dismissing it, because flicking through them is the one
 * interaction that belongs here.
 *
 * The screen is only held awake while charging. Recording alone costs about
 * 62 mA against a 1010 mAh battery; leaving the panel lit on top of that turns
 * a day of runtime into a few hours, which is the wrong trade for something
 * carried around. On a desk with a cable it is free.
 */
class SignageActivity : Activity() {

    companion object {
        /** How often the visible screen is asked to redraw. */
        private const val TICK_MS = 1_000L

        /** How often the server is asked what today looks like. */
        private const val FETCH_MS = 120_000L

        /**
         * The instance currently on screen, or null.
         *
         * Held so a side-button press — which arrives at the accessibility
         * service, not at any window — can dismiss it. Cleared in onDestroy so
         * a stale reference cannot be told to finish twice.
         */
        @Volatile private var showing: SignageActivity? = null

        /** True if the press was used to leave standby. */
        fun dismiss(): Boolean {
            val activity = showing ?: return false
            activity.runOnUiThread { activity.finish() }
            return true
        }
    }

    private lateinit var frame: FrameLayout
    private lateinit var settings: UploadSettings
    private var screens: List<SignageScreen> = emptyList()
    private var index = 0
    private var lastFetch = 0L

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (now - lastFetch >= FETCH_MS) {
                lastFetch = now
                LifelogSummary.refresh(settings)
                HermesStatus.refresh(this@SignageActivity)
            }
            // The screen only changes when the wheel is turned. A display that
            // rotates on its own is one you have to wait for when you want a
            // particular thing, and this one is often glanced at rather than
            // watched.
            screens.getOrNull(index)?.refresh(LifelogSummary.current)

            ticker.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Unplugging should not leave the panel burning. Charging state is watched
     * rather than read once, because the whole point of this screen is that it
     * is left alone for hours.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = applyKeepAwake()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = UploadSettings(this)

        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(frame)

        screens = settings.signageIds.mapNotNull { Signage.byId(it) }
            .ifEmpty { listOf(Signage.ALL.first()) }
        show(0)

        LifelogSummary.refresh(settings)
        HermesStatus.refresh(this)
        lastFetch = System.currentTimeMillis()

        // The side button is the way out, and it does not reach a focused
        // Activity — the launcher's accessibility service has it first. This
        // is how the press gets here.
        showing = this
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        applyKeepAwake()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        runCatching { unregisterReceiver(powerReceiver) }
        super.onPause()
    }

    private fun show(next: Int) {
        index = next
        val screen = screens.getOrNull(index) ?: return
        frame.removeAllViews()
        frame.addView(
            screen.createView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        screen.refresh(LifelogSummary.current)
    }

    private fun applyKeepAwake() {
        val charging = runCatching {
            getSystemService(BatteryManager::class.java)?.isCharging == true
        }.getOrDefault(false)

        if (charging) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * The wheel steps between screens and never dismisses.
     *
     * Leaving is the side button's job: once to come back to the device, twice
     * to ask a question. Both arrive through KeyService rather than here — the
     * button is taken by the launcher's accessibility service before window
     * focus is consulted, so a focused Activity never sees it.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                show((index - 1 + screens.size) % screens.size)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                show((index + 1) % screens.size)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onUserLeaveHint() {
        finish()
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        if (showing === this) showing = null
        super.onDestroy()
    }
}
