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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.EMPTY_REQUEST
import java.util.concurrent.TimeUnit

/**
 * The device talking, on screen.
 *
 * It arrives unasked — that is the point of the feature — so it has to be
 * obvious what is happening and obvious how to end it. The opening line is on
 * the panel before the speaker has finished saying it, because the thing being
 * spoken to may be a person who did not hear the first three words, and the
 * exit is a single key.
 *
 * Everything on this screen was already decided elsewhere: the Worker chose the
 * moment and wrote the question, and [NudgeSession] runs the conversation. What
 * is left here is showing it, ending it, and reporting whether anybody answered
 * — which is the one fact about a check-in that cannot be recovered from the
 * archive later, since a minute of the owner talking looks the same whether it
 * was a reply or a phone call that happened to start.
 */
class NudgeActivity : Activity() {

    companion object {
        const val EXTRA_ID = "nudge_id"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_OPENING = "opening"
        const val EXTRA_SECRET = "client_secret"
        const val EXTRA_URL = "url"
        const val EXTRA_MAX_SECONDS = "max_seconds"

        private const val R1_ORANGE = 0xFFFE5000.toInt()
        private const val INK = 0xFFF2F2F2.toInt()
        private const val DIM = 0xFF8A8A8A.toInt()

        private val SLOT_LABELS = mapOf(
            "morning" to "朝の声かけ",
            "midday" to "昼の声かけ",
            "evening" to "夕方の声かけ",
            "idle" to "声かけ",
        )
    }

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var said: TextView
    private lateinit var heard: TextView

    private var session: NudgeSession? = null
    private val saidText = StringBuilder()
    private val main = Handler(Looper.getMainLooper())

    /** Reported exactly once, from whichever of the two exits happens first. */
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The same pair the evening prompt uses, and for the same reason: this
        // fires at a device asleep on a desk, and without them it lands behind
        // a dark screen while the speaker talks to nobody.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Held for the session. It is three minutes at the outside, and a panel
        // that sleeps mid-sentence takes the conversation down with it — which
        // is what happened to the interpreter the first time.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildUi())

        val slot = intent.getStringExtra(EXTRA_SLOT).orEmpty()
        title.text = SLOT_LABELS[slot] ?: "声かけ"
        // On screen before it is spoken, not after. Somebody who looks up at
        // the third word should still be able to read the question.
        said.text = intent.getStringExtra(EXTRA_OPENING).orEmpty()

        begin()
    }

    override fun onPause() {
        // Leaving the screen ends the session, as it does for the interpreter:
        // a billed microphone session that outlives the screen showing it is
        // the kind of thing discovered on an invoice.
        finishSession()
        super.onPause()
        if (!isFinishing) finish()
    }

    private fun begin() {
        val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (secret.isEmpty() || url.isEmpty()) {
            status.text = "セッションがありません"
            return
        }

        session = NudgeSession(
            context = this,
            url = url,
            clientSecret = secret,
            maxSeconds = intent.getIntExtra(EXTRA_MAX_SECONDS, 180),
            onState = { state -> main.post { render(state) } },
            onLine = { line ->
                main.post {
                    when (line) {
                        // The device's own words arrive a fragment at a time and
                        // are appended; the opening line is already there, so
                        // the first delta continues it rather than replacing it.
                        is NudgeSession.Said -> {
                            saidText.append(line.text)
                            if (saidText.length > 200) saidText.delete(0, saidText.length - 200)
                            said.text = saidText.toString()
                        }
                        // The person's reply lands whole, once transcribed.
                        is NudgeSession.Heard -> heard.text = line.text
                    }
                }
            },
        ).also { it.start() }
    }

    /**
     * Ends the session and tells the Worker how it went.
     *
     * The report is best-effort and off the main thread. Losing one is losing a
     * row in a table that exists to be read in a fortnight, which is not worth
     * blocking a screen from closing.
     */
    private fun finishSession() {
        val live = session ?: return
        session = null
        live.stop()

        if (reported) return
        reported = true

        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (id <= 0) return
        val answered = live.answered
        val seconds = live.seconds
        val settings = UploadSettings(this)

        Thread({
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(
                    settings.baseUrl + "/v1/nudge/outcome?id=" + id +
                        "&answered=" + (if (answered) "1" else "0") +
                        "&seconds=" + seconds,
                )
                .post(EMPTY_REQUEST)
                .apply {
                    addHeader("Authorization", "Bearer " + settings.bearer)
                    if (settings.accessClientId.isNotEmpty()) {
                        addHeader("CF-Access-Client-Id", settings.accessClientId)
                        addHeader("CF-Access-Client-Secret", settings.accessClientSecret)
                    }
                }
                .build()
            runCatching { client.newCall(request).execute().close() }
        }, "nudge-outcome").start()
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }

        title = TextView(this).apply {
            setTextColor(R1_ORANGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(8))
        }
        said = TextView(this).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(3).toFloat(), 1f)
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        // Dimmer, and below: it is a check that the room was heard, not the
        // point of the screen.
        heard = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.START
        }

        column.addView(title, wide())
        column.addView(status, wide())
        column.addView(said, wide())
        column.addView(heard, wide())
        column.addView(TextView(this).apply {
            text = "中央キーで終了"
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }, wide())
        return column
    }

    private fun render(state: NudgeSession.State) {
        status.text = when (state) {
            NudgeSession.State.CONNECTING -> "接続中…"
            NudgeSession.State.RUNNING -> "聞いています"
            NudgeSession.State.CLOSED -> "終了"
            NudgeSession.State.FAILED -> "接続できません"
        }
        status.setTextColor(if (state == NudgeSession.State.RUNNING) R1_ORANGE else DIM)

        // The session ends itself once the exchange is over. Closing the screen
        // behind it means the device goes back to whatever it was showing
        // rather than leaving a finished conversation lit on the desk.
        if (state == NudgeSession.State.CLOSED || state == NudgeSession.State.FAILED) {
            main.postDelayed({ if (!isFinishing) finish() }, 2_500)
        }
    }

    /**
     * The centre key ends it. There is no back key on this device, so a screen
     * that arrives on its own has to carry its own exit.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_1 -> {
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
