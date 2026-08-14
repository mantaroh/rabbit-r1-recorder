package com.r1.audioprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Shall we stop for today?"
 *
 * Shown at 23:00 and then every ten minutes until answered. It has to work
 * from across a room and from a device lying face down on a table, so it is
 * loud, bright, and readable at a glance — and it never decides for itself:
 * dismissing it, walking away from it, or sleeping through it all leave the
 * recording running.
 *
 * Styled after the R1's own interface rather than Android's: black ground,
 * one hot orange, big soft rectangles, nothing that looks like a settings
 * dialog. This is the screen most likely to be seen by someone who is not
 * looking for it, so it should look like it belongs to the object.
 */
class StopPromptActivity : Activity() {

    companion object {
        /** The R1's orange. Everything else on this screen is monochrome. */
        private const val R1_ORANGE = 0xFFFE5000.toInt()
        private const val R1_ORANGE_DIM = 0xFF7A2800.toInt()
        private const val INK = 0xFFF2F2F2.toInt()
        private const val DIM = 0xFF8A8A8A.toInt()

        /** Which button the wheel is currently on. */
        private const val CHOICE_KEEP = 0
        private const val CHOICE_STOP = 1
    }

    private lateinit var keepButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var clockView: TextView

    private var choice = CHOICE_KEEP
    private val ticker = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("H:mm", Locale.US).format(Date())
            ticker.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The prompt fires while the device is asleep on a table; without
        // these it lands behind a locked, dark screen and is never seen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(buildUi())
        render()
        // Start on "keep going": the answer that costs nothing if the wheel
        // is nudged by accident.
        keepButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        ticker.post(clockTick)
    }

    override fun onPause() {
        ticker.removeCallbacks(clockTick)
        super.onPause()
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        clockView = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        column.addView(clockView, wide())

        column.addView(TextView(this).apply {
            text = "おつかれさま"
            setTextColor(R1_ORANGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }, wide())

        column.addView(TextView(this).apply {
            text = "今日の記録、\nここで止めますか？"
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(10))
        }, wide())

        keepButton = choiceButton("まだ続ける", CHOICE_KEEP) { answer(stop = false) }
        column.addView(keepButton, wide())

        stopButton = choiceButton("今日はここまで", CHOICE_STOP) { answer(stop = true) }
        column.addView(stopButton, wide())

        column.addView(TextView(this).apply {
            text = "答えないと10分後にまた聞きます\n録音は止まりません"
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }, wide())

        return column
    }

    /**
     * The wheel moves between the answers and the centre key picks one.
     *
     * Driven by Android's own focus system rather than by intercepting keys at
     * the Activity. A clickable view is focusable, so it swallows the D-pad
     * before the Activity ever sees it — the first version overrode onKeyDown
     * and the wheel simply did nothing. Going with the grain also means the
     * centre key fires the click listener for free.
     */
    private fun choiceButton(label: String, index: Int, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true
            isFocusable = true
            // The platform's grey focus rectangle fights the painted state
            // below; the fill and outline are the affordance here.
            defaultFocusHighlightEnabled = false
            setOnClickListener { onTap() }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    choice = index
                    render()
                }
            }
        }

    private fun render() {
        paint(keepButton, selected = choice == CHOICE_KEEP, accent = true)
        paint(stopButton, selected = choice == CHOICE_STOP, accent = false)
    }

    /**
     * Selected is filled, unselected is outlined. "Keep going" carries the
     * orange because it is the answer that costs nothing to get wrong.
     */
    private fun paint(view: TextView, selected: Boolean, accent: Boolean) {
        val fill = if (accent) R1_ORANGE else 0xFF2A2A2A.toInt()
        val edge = if (accent) R1_ORANGE else 0xFF4A4A4A.toInt()
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (selected) {
                setColor(fill)
                setStroke(dp(2), if (accent) R1_ORANGE else INK)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), if (accent) R1_ORANGE_DIM else edge)
            }
        }
        view.setTextColor(
            when {
                selected && accent -> Color.BLACK
                selected -> INK
                accent -> R1_ORANGE
                else -> DIM
            },
        )
    }

    private fun answer(stop: Boolean) {
        startService(
            Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_PROMPT_ANSWER)
                .putExtra(RecorderService.EXTRA_STOP_RECORDING, stop),
        )
        finishAndRemoveTask()
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(6) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
