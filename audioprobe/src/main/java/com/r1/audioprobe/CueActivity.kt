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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

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
        private const val DIM = 0xFF8A8A8A.toInt()
    }

    private lateinit var status: TextView
    private lateinit var cue: Cue
    private val main = Handler(Looper.getMainLooper())

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
        cue.onEvent = { line -> main.post { status.text = line } }
        status.text = if (cue.ready) "準備中…" else "音声がありません"
    }

    override fun onResume() {
        super.onResume()
        // Set before the poll starts, so a cue that arrives in the first two
        // seconds cannot race a timelapse cycle for the arm.
        RecorderService.onStage = true
        cue.watch()
    }

    override fun onPause() {
        RecorderService.onStage = false
        cue.stop()
        super.onPause()
        if (!isFinishing) finish()
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

    // ------------------------------------------------------------------ ui ---

    private fun buildUi(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        setPadding(dp(16), dp(20), dp(16), dp(16))
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)

        addView(label("一言", 13f, DIM))
        addView(space(dp(10)))

        status = TextView(this@CueActivity).apply {
            setTextColor(R1_ORANGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
        }
        addView(status)

        addView(
            label("「Hello, World.\n素晴らしい世界が見えるようになりました。」", 14f, INK).apply {
                gravity = Gravity.CENTER
            },
        )

        // Pushes the exit to the bottom of the panel, out of reach of a thumb
        // that is holding the device up.
        addView(
            View(this@CueActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            },
        )

        addView(label("サイドボタンで鳴らす", 13f, DIM).apply { gravity = Gravity.CENTER })
        addView(space(dp(12)))

        // Tapped, never focused. The side button activates whatever view has
        // focus, so a focusable exit here would mean the button closed the
        // screen instead of saying the line — which is the one failure this
        // screen cannot recover from.
        addView(
            label("閉じる", 15f, DIM).apply {
                gravity = Gravity.CENTER
                isFocusable = false
                isFocusableInTouchMode = false
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { finish() }
            },
        )
    }

    private fun label(text: String, sp: Float, colour: Int) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, height)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
