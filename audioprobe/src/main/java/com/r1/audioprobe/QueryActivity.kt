package com.r1.audioprobe

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Feedback while a question is being asked.
 *
 * The gesture is the power key, so pressing it twice dims the screen and
 * nothing appears to happen — the device gives no sign it is listening until
 * an answer arrives seconds later. This is the screen the design asks for:
 * ASK / speak now, then listening, then thinking.
 *
 * It shows state and nothing else. The answer is the chat client's job.
 */
class QueryActivity : Activity() {

    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var dotsView: TextView

    private val ticker = Handler(Looper.getMainLooper())
    private var frame = 0

    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The press just turned the screen off; this has to bring it back and
        // hold it, or the user is talking to a dark panel.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        titleView = TextView(this).apply {
            text = "ASK"
            setTextColor(0xFF7FD1A0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        dotsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
        }
        stateView = TextView(this).apply {
            setTextColor(0xFFB0B0B0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }

        val wide = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        root.addView(titleView, wide)
        root.addView(dotsView, wide)
        root.addView(stateView, wide)
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        frame += 1
        when (QueryController.uiState) {
            QueryController.State.ARMED -> {
                titleView.text = "ASK"
                dotsView.text = "●"
                stateView.text = "Speak now"
            }
            QueryController.State.CAPTURING -> {
                titleView.text = "ASK"
                // Four dots cycling: something has to move, or a held pause
                // looks like the device has stopped listening.
                dotsView.text = (0 until 4).joinToString(" ") { i ->
                    if (i == frame % 4) "●" else "○"
                }
                stateView.text = "Listening"
            }
            QueryController.State.PROCESSING -> {
                titleView.text = "Hermes"
                dotsView.text = if (frame % 2 == 0) "◌" else "◍"
                stateView.text = "Thinking"
            }
            QueryController.State.LIFELOG -> {
                // The chat client takes over from here.
                finish()
            }
        }
    }
}
