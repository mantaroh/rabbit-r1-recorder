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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The interpreter, on screen.
 *
 * Two people and a device between them. One of them speaks, the device says it
 * in the other language out loud, and the wheel swaps which language that is
 * for the reply. There is no automatic direction: the model detects the source
 * language perfectly well but the *target* is a session setting, so a session
 * translates one way, and which way is a thing a person decides by knowing
 * whose turn it is.
 *
 * Deliberately not the standby display and not a background service. This costs
 * money by the minute and it listens to a room with intent, so it runs only
 * while somebody is looking at it, and leaving the screen ends it.
 */
class InterpretActivity : Activity() {

    private companion object {
        const val ORANGE = 0xFFFE5000.toInt()
        const val INK = 0xFFF2F2F2.toInt()
        const val DIM = 0xFF7A7A7A.toInt()

        /**
         * The pair the wheel swaps between. Everything the Worker allows is in
         * `/v1/interpret/targets`, but a conversation has two sides and picking
         * from seven mid-sentence is not a thing anyone will do.
         */
        val PAIR = listOf("ja" to "日本語", "en" to "English")
    }

    private lateinit var target: TextView
    private lateinit var status: TextView
    private lateinit var transcript: TextView

    private var interpreter: Interpreter? = null
    private var index = 0
    private var line = StringBuilder()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        index = PAIR.indexOfFirst { it.first == UploadSettings(this).interpretTarget }
            .coerceAtLeast(0)
        render(Interpreter.State.IDLE)
        begin()
    }

    override fun onPause() {
        // Ends on leaving, rather than running on behind whatever comes next.
        // A billed microphone session that outlives the screen showing it is
        // the kind of thing that is discovered on an invoice.
        interpreter?.stop()
        interpreter = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (interpreter == null) begin()
    }

    private fun begin() {
        val settings = UploadSettings(this)
        val code = PAIR[index].first
        settings.interpretTarget = code

        interpreter = Interpreter(
            settings = settings,
            onState = { state -> main.post { render(state) } },
            onTranscript = { piece ->
                main.post {
                    line.append(piece)
                    // Kept short on purpose: this is a confirmation that the
                    // right thing was heard, not a transcript to read. The
                    // audio is the output.
                    if (line.length > 220) line.delete(0, line.length - 220)
                    transcript.text = line.toString()
                }
            },
        ).also { it.start(code) }
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }

        target = TextView(this).apply {
            setTextColor(ORANGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(10))
        }
        transcript = TextView(this).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(5f, 1f)
            maxLines = 9
            ellipsize = android.text.TextUtils.TruncateAt.START
        }

        column.addView(target, wide())
        column.addView(status, wide())
        column.addView(transcript, wide())
        column.addView(TextView(this).apply {
            text = "ホイールで訳す言語を切替\n中央キーで終了"
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }, wide())
        return column
    }

    private fun render(state: Interpreter.State) {
        target.text = "→ " + PAIR[index].second
        status.text = when (state) {
            Interpreter.State.IDLE -> "停止"
            Interpreter.State.CONNECTING -> "接続中…"
            // Named for what the device is doing, because with one speaker and
            // half duplex those two states are also the instruction: while it
            // is talking, the microphone is closed and talking over it is lost.
            Interpreter.State.LISTENING -> "聞いています"
            Interpreter.State.SPEAKING -> "話しています（マイクは止まっています）"
            Interpreter.State.FAILED -> "接続できません"
        }
        status.setTextColor(if (state == Interpreter.State.SPEAKING) ORANGE else DIM)
    }

    /**
     * The wheel swaps the direction, and swapping means a new session: the
     * output language is fixed when the session is minted, so this tears down
     * and reconnects rather than pretending it can be changed in place.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            index = (index + 1) % PAIR.size
            line.setLength(0)
            transcript.text = ""
            interpreter?.stop()
            interpreter = null
            render(Interpreter.State.CONNECTING)
            begin()
            true
        }

        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            finish()
            true
        }

        else -> super.onKeyDown(keyCode, event)
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
