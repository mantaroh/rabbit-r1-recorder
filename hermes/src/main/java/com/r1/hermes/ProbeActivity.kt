package com.r1.hermes

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hardware input probe.
 *
 * CarrotOS does not document what the scroll wheel and side button report, and
 * the camera app's `DPAD_UP`/`DPAD_DOWN` mapping is still an unverified guess.
 * Turning the wheel or pressing a button here prints the raw event so the real
 * bindings can be written against measured key codes.
 */
class ProbeActivity : Activity() {

    companion object {
        private const val TAG = "R1Hermes"
        private const val MAX_LINES = 40
    }

    private lateinit var eventLog: TextView
    private lateinit var scroller: ScrollView
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        log("Turn the wheel or press a button.")
        log("Tap Close to leave — the R1 has no Back key.")
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val m = resources.displayMetrics
        root.addView(
            TextView(this).apply {
                setTextColor(Color.rgb(120, 220, 160))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                text = "Input probe · ${m.widthPixels}x${m.heightPixels} px · " +
                    "${(m.widthPixels / m.density).toInt()}x${(m.heightPixels / m.density).toInt()} dp"
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        eventLog = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
        }
        scroller = ScrollView(this).apply { addView(eventLog) }
        root.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Touch is the only exit that cannot be swallowed by this screen's own
        // key handling — and the R1 turns out to have no Back key at all.
        root.addView(
            Button(this).apply {
                text = "Close"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isAllCaps = false
                minimumHeight = 0
                minHeight = dp(34)
                stateListAnimator = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.rgb(58, 58, 58))
                }
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun log(message: String) {
        Log.i(TAG, message)
        lines.addLast(message)
        while (lines.size > MAX_LINES) lines.removeFirst()
        eventLog.text = lines.joinToString("\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        log("KEY_DOWN ${KeyEvent.keyCodeToString(keyCode)} ($keyCode) src=${event?.source}")
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        log("KEY_UP   ${KeyEvent.keyCodeToString(keyCode)} ($keyCode)")
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) ||
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f || vscroll != 0f) {
                log("MOTION scroll=$scroll vscroll=$vscroll src=${event.source}")
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
