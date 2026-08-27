package com.r1.audioprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本番 — the screen the device is left on while a talk is happening.
 *
 * It does two jobs, and the second is the one that is easy to forget. The
 * obvious one is to wait for the cue and say the line. The other is to make
 * the device stop doing everything it normally does: away from the home Wi-Fi
 * the timelapse fires every five minutes, and the arm that carries the lens is
 * audible in a quiet room. A five-minute talk would almost certainly have had
 * a motor run through it.
 *
 * So the two are tied together deliberately. While this screen is in front,
 * [RecorderService.onStage] is set and neither the timelapse nor 声かけ runs;
 * leaving the screen puts both back. "Being on stage" is a state with one
 * visible representation rather than a setting somebody has to remember to
 * turn off afterwards.
 *
 * Recording itself is untouched. The talk is worth keeping, and the microphone
 * makes no noise.
 *
 * **While it waits it is a clock, and nothing else.** No title, no status, no
 * preview of the line. The device is in the room with the audience, and a
 * panel captioned with the sentence it is about to say gives the ending away
 * to anyone close enough to read it; a clock on a desk is furniture. The one
 * concession is the colour: the clock is red instead of white when there is no
 * audio to play, which says nothing to a stranger and everything to the person
 * who has to know before they start talking.
 *
 * **This is for one talk and is meant to be deleted afterwards.** It is not a
 * feature the device is gaining; it is a prop. What has to go with it: this
 * Activity, [Cue], `res/raw/cue_hello.wav`, the entry in 設定, the manifest
 * entry, [RecorderService.onStage] and its two gates, and the Worker's cue
 * routes, table and page. Nothing else refers to any of them.
 *
 * Note what this screen does *not* do: it never calls `Idle.watch`, so the
 * standby display cannot take it over after a minute of nobody touching the
 * device. That is the whole point — the device is sitting on a lectern being
 * ignored, which is exactly what the idle timer exists to notice.
 */
class CueActivity : Activity() {

    companion object {
        private const val R1_ORANGE = 0xFFFE5000.toInt()
        private const val INK = 0xFFF2F2F2.toInt()

        /** Only ever seen by the person running the talk; see the class note. */
        private const val ALARM = 0xFFD24A2A.toInt()

        /**
         * How long the panel has to be held before the screen closes.
         *
         * Android's own long press is around half a second, which is what a
         * sleeve resting on a lectern does. Two seconds is a decision.
         */
        private const val HOLD_TO_CLOSE_MS = 2_000L
    }

    private lateinit var clock: TextView
    private lateinit var greeting: View
    private lateinit var cue: Cue

    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("H:mm", Locale.JAPAN)

    private val tick = object : Runnable {
        override fun run() {
            clock.text = stamp.format(Date())
            main.postDelayed(this, 1_000)
        }
    }

    private val closeAfterHold = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The panel must not sleep during the talk: a dark screen is a device
        // that has to be woken before it can be told to speak, and the moment
        // will have passed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContentView(buildUi())

        cue = Cue(this, UploadSettings(this), Metrics(this))
        cue.onSpeaking = { speaking -> main.post { showGreeting(speaking) } }

        clock.setTextColor(if (cue.ready) INK else ALARM)
        showGreeting(false)
    }

    override fun onResume() {
        super.onResume()
        // Set before the poll starts, so a cue that arrives in the first two
        // seconds cannot race a timelapse cycle for the arm.
        RecorderService.onStage = true
        cue.watch()
        tick.run()
    }

    override fun onPause() {
        RecorderService.onStage = false
        cue.stop()
        main.removeCallbacks(tick)
        main.removeCallbacks(closeAfterHold)
        super.onPause()
        if (!isFinishing) finish()
    }

    private fun showGreeting(speaking: Boolean) {
        clock.visibility = if (speaking) View.GONE else View.VISIBLE
        greeting.visibility = if (speaking) View.VISIBLE else View.GONE
    }

    /**
     * The side button says it. Nothing else does.
     *
     * `repeatCount` matters: this key auto-repeats while held, so without the
     * check a firm press would queue several. [Cue.speak] would refuse the
     * extras anyway, but refusing them here means the log records one press.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        -> {
            if (event?.repeatCount == 0) cue.speak("button")
            true
        }
        // The wheel is swallowed rather than passed on. There is nothing to
        // scroll here, and a stray turn should not move focus onto something
        // the side button would then activate instead of speaking.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
        else -> super.onKeyDown(keyCode, event)
    }

    /**
     * Held for two seconds to leave.
     *
     * There is no exit on the panel because there is nothing on the panel, so
     * the gesture has to be one nothing else performs. A tap would be the
     * device brushing a lectern; a long hold is a hand deciding.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> main.postDelayed(closeAfterHold, HOLD_TO_CLOSE_MS)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> main.removeCallbacks(closeAfterHold)
        }
        return true
    }

    // ------------------------------------------------------------------ ui ---

    private fun buildUi(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)

        clock = TextView(this@CueActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        addView(clock)

        greeting = buildGreeting()
        addView(greeting)
    }

    /**
     * What the room sees for the six seconds the device is talking.
     *
     * The words are on the panel as well as in the air because the line is the
     * last thing said in the talk and a device speaking Japanese through a
     * small speaker is not always caught first time.
     */
    private fun buildGreeting(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)

        addView(
            TextView(this@CueActivity).apply {
                text = "Hello, World."
                setTextColor(R1_ORANGE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            },
        )
        addView(
            TextView(this@CueActivity).apply {
                text = "素晴らしい世界が\n見えるようになりました。"
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(14)
                }
            },
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
