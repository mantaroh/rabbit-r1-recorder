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
        /** How long a screen holds before the next one. */
        private const val ROTATE_MS = 20_000L

        /** How often the visible screen is asked to redraw. */
        private const val TICK_MS = 1_000L

        /** How often the server is asked what today looks like. */
        private const val FETCH_MS = 120_000L
    }

    private lateinit var frame: FrameLayout
    private lateinit var settings: UploadSettings
    private var screens: List<SignageScreen> = emptyList()
    private var index = 0
    private var lastRotate = 0L
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
            if (screens.size > 1 && now - lastRotate >= ROTATE_MS) {
                lastRotate = now
                show((index + 1) % screens.size)
            }
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

        lastRotate = System.currentTimeMillis()
        LifelogSummary.refresh(settings)
        HermesStatus.refresh(this)
        lastFetch = System.currentTimeMillis()
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

    /** The wheel steps between screens; it does not dismiss. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                lastRotate = System.currentTimeMillis()
                show((index - 1 + screens.size) % screens.size)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                lastRotate = System.currentTimeMillis()
                show((index + 1) % screens.size)
                return true
            }
        }
        // Anything else is someone wanting the device back.
        finish()
        return true
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
}
