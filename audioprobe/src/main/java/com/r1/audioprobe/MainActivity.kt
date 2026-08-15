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
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen: a list you turn the wheel through and press to choose.
 *
 * Shaped after the R1's own launcher rather than after a settings page. This
 * device has one control — a wheel and a key — and the previous screen was a
 * column of small Android buttons that could only really be used by tapping
 * them, which is the one interaction the R1 is worst at. So: few entries, big
 * type, one lit row at a time, and everything reachable without touching glass.
 *
 * The dials and secrets moved to [SettingsActivity]; what stays here is what
 * gets used daily.
 */
class MainActivity : Activity() {

    companion object {
        /** The R1's orange. Everything else on this screen is monochrome. */
        private const val R1_ORANGE = 0xFFFE5000.toInt()
        private const val INK = 0xFFF2F2F2.toInt()
        private const val DIM = 0xFF7A7A7A.toInt()

        /** Begin recording as soon as the Activity is up; see onCreate. */
        const val EXTRA_AUTOSTART = "autostart"

        /** Run the two-microphone experiment; see MicProbe. */
        const val EXTRA_MIC_PROBE = "micprobe"

        /** Wi-Fi the timelapse may photograph from; empty means anywhere. */
        const val EXTRA_PHOTO_SSID = "photo_ssid"

        /** Show the 23:00 question now, for looking at it. */
        const val EXTRA_SHOW_PROMPT = "showprompt"

        /** Open the chat. Not exported, so a shell has to come through here. */
        const val EXTRA_CHAT = "chat"
    }

    /**
     * One line of the menu. [label] is recomputed on every refresh so an entry
     * can report state — the recording row is a switch, not a destination.
     */
    private class Entry(val label: () -> String, val onPick: () -> Unit)

    private lateinit var clockView: TextView
    private lateinit var statusView: TextView
    private lateinit var footerView: TextView
    private lateinit var metrics: Metrics
    private lateinit var upload: UploadSettings

    private val rows = mutableListOf<Pair<TextView, Entry>>()
    private var selected = 0

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ticker.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        metrics = Metrics(this)
        upload = UploadSettings(this)
        setContentView(buildUi())
        Recorder.ensurePermissions(this)
        refresh()
        rows.firstOrNull()?.first?.requestFocus()

        // `am start -n … --ez autostart true` begins recording without anyone
        // touching the screen. A microphone foreground service cannot be
        // started from the background on Android 14, so anything that wants
        // recording to resume — a boot receiver, a shell, a person in a hurry —
        // has to come through this Activity.
        applyLaunchExtras(intent)
    }

    /**
     * A second `am start` while this Activity is already up.
     *
     * Without this the extras are silently dropped — Android delivers the
     * Intent nowhere and simply brings the task forward, so the command looks
     * like it worked and does nothing. Exactly the failure the chat client had
     * with handed-over prompts.
     */
    override fun onNewIntent(incoming: Intent?) {
        super.onNewIntent(incoming)
        setIntent(incoming)
        applyLaunchExtras(incoming)
    }

    private fun applyLaunchExtras(source: Intent?) {
        // Typing an SSID on a 240 dp panel is its own small punishment, and
        // this is the kind of setting that gets changed from a laptop.
        source?.getStringExtra(EXTRA_PHOTO_SSID)?.let {
            upload.photoSsid = it
            metrics.write("photo_ssid_set", mapOf("ssid" to upload.photoSsid))
        }

        // The evening prompt is not exported, and waiting until 23:00 to see
        // whether it looks right is a poor way to work on it.
        if (source?.getBooleanExtra(EXTRA_SHOW_PROMPT, false) == true) {
            startActivity(Intent(this, StopPromptActivity::class.java))
        }

        if (source?.getBooleanExtra(EXTRA_CHAT, false) == true) {
            startActivity(Intent(this, com.r1.hermes.ChatActivity::class.java))
        }

        if (source?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) Recorder.start(this)

        // The service is not exported, so a shell cannot address it directly;
        // this Activity is the only door in. Same reason as autostart.
        if (source?.getBooleanExtra(EXTRA_MIC_PROBE, false) == true) {
            startService(
                Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_MIC_PROBE),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
        com.r1.core.Idle.watch(this)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        com.r1.core.Idle.release(this)
        super.onPause()
    }

    /** Every tap and key comes through here; it is what resets the timer. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        com.r1.core.Idle.touch()
    }

    // --------------------------------------------------------------- view ---

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        clockView = TextView(this).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD)
        }
        column.addView(clockView, wide(0))

        statusView = TextView(this).apply {
            setTextColor(R1_ORANGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(1), 0, dp(8))
        }
        column.addView(statusView, wide(0))

        entries().forEach { entry ->
            val row = row(entry)
            rows.add(row to entry)
            column.addView(row, wide(2))
        }

        footerView = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(footerView, wide(0))

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            addView(column)
        }
    }

    /**
     * Five, deliberately. The wheel is a slow way to travel and this is the
     * screen you land on every time the standby display is dismissed; anything
     * that is not used most days belongs behind 設定.
     */
    private fun entries() = listOf(
        Entry({ "話す" }) {
            startActivity(Intent(this, com.r1.hermes.ChatActivity::class.java))
        },
        Entry({ if (RecorderService.recordingAudio) "記録を止める" else "記録を始める" }) {
            toggleRecording()
        },
        Entry({ "待受" }) {
            startActivity(Intent(this, SignageActivity::class.java))
        },
        Entry({ "設定" }) {
            startActivity(Intent(this, SettingsActivity::class.java))
        },
        Entry({ "Hermes" }) {
            startActivity(Intent(this, com.r1.hermes.MainActivity::class.java))
        },
    )

    /**
     * The wheel moves between rows and the centre key picks one.
     *
     * Driven by Android's own focus system rather than by intercepting keys at
     * the Activity. A clickable view is focusable, so it swallows the D-pad
     * before the Activity ever sees it — overriding onKeyDown here means the
     * wheel does nothing at all. Going with the grain also gets the centre key,
     * and scrolling the selection into view, for free.
     */
    private fun row(entry: Entry): TextView = TextView(this).apply {
        text = entry.label()
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isClickable = true
        isFocusable = true
        // The platform's grey focus rectangle fights the painted state below;
        // the fill is the affordance here.
        defaultFocusHighlightEnabled = false
        setOnClickListener { entry.onPick() }
        setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) selected = rows.indexOfFirst { it.first === view }
            paint(this, hasFocus)
        }
        paint(this, selected = false)
    }

    /** Lit row is filled orange; the rest are unlit text on black. */
    private fun paint(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (selected) R1_ORANGE else Color.TRANSPARENT)
        }
        view.setTextColor(if (selected) Color.BLACK else DIM)
    }

    private fun wide(topMarginDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topMarginDp) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ control ---

    /**
     * Starts the service if it is not up, otherwise flips the archive without
     * restarting capture — a restart drops the segment in flight, and the point
     * of this row is to be able to pause for a private conversation and resume
     * without losing the minute around it.
     */
    private fun toggleRecording() {
        if (!RecorderService.running) {
            Recorder.start(this, writeAudio = true)
            return
        }
        startService(
            Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_SET_RECORDING)
                .putExtra(RecorderService.EXTRA_WRITE_AUDIO, !RecorderService.recordingAudio),
        )
    }

    private fun refresh() {
        clockView.text = SimpleDateFormat("H:mm", Locale.US).format(Date())

        statusView.text = when {
            !RecorderService.running -> "停止中"
            RecorderService.recordingAudio -> "記録中"
            else -> "待機中（記録なし）"
        }
        statusView.setTextColor(if (RecorderService.recordingAudio) R1_ORANGE else DIM)

        rows.forEach { (view, entry) -> view.text = entry.label() }

        footerView.text = buildString {
            append(if (upload.enabled) "送信 on" else "送信 off")
            if (!upload.isConfigured) append(" · 未設定")
            append(" · log ${metrics.sizeBytes() / 1024} KiB")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
