package com.r1.audioprobe

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Control surface for the probe. Deliberately thin — the answers live in the
 * JSONL the service writes, not on this screen; this is just enough to start a
 * run, confirm it is alive, and stop it.
 */
class SettingsActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var baseUrlField: EditText
    private lateinit var bearerField: EditText
    private lateinit var accessIdField: EditText
    private lateinit var accessSecretField: EditText
    private lateinit var deviceIdField: EditText
    private lateinit var photoSsidField: EditText
    private lateinit var metrics: Metrics
    private lateinit var upload: UploadSettings

    /** The R1's orange, as on the home menu and the evening prompt. */
    private val R1_ORANGE = 0xFFFE5000.toInt()

    private val ticker = Handler(Looper.getMainLooper())

    // Backed by storage rather than by this screen. Held as fields they
    // reverted to their defaults on every launch, which for the recording flag
    // meant an evening "stop for today" could be undone by opening a settings
    // page.
    private var useWakeLock: Boolean
        get() = upload.wakeLock
        set(v) { upload.wakeLock = v }

    private var useVoiceRecognition: Boolean
        get() = upload.voiceRecognition
        set(v) { upload.voiceRecognition = v }

    private var writeAudio: Boolean
        get() = upload.recording
        set(v) { upload.recording = v }

    private var useOpus: Boolean
        get() = upload.opus
        set(v) { upload.opus = v }

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
        loadUploadFields()
        ensurePermissions()

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

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        column.addView(TextView(this).apply {
            text = "設定"
            setTextColor(0xFF7FD1A0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
        }, wide())

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }
        column.addView(statusView, wide())

        column.addView(button("Start") { start() }, wide())
        column.addView(button("Stop") { stop() }, wide())
        column.addView(toggle("Lifelog: OFF") {
            writeAudio = !writeAudio
            (it as Button).text = if (writeAudio) "Lifelog: ON" else "Lifelog: OFF"
        }.also { it.text = if (writeAudio) "Lifelog: ON" else "Lifelog: OFF" }, wide())

        column.addView(toggle("Wake lock: ON") {
            useWakeLock = !useWakeLock
            (it as Button).text = if (useWakeLock) "Wake lock: ON" else "Wake lock: OFF"
        }.also { it.text = if (useWakeLock) "Wake lock: ON" else "Wake lock: OFF" }, wide())

        column.addView(toggle("Source: MIC") {
            useVoiceRecognition = !useVoiceRecognition
            (it as Button).text =
                if (useVoiceRecognition) "Source: VOICE_RECOGNITION" else "Source: MIC"
        }.also {
            it.text = if (useVoiceRecognition) "Source: VOICE_RECOGNITION" else "Source: MIC"
        }, wide())

        column.addView(toggle("Codec: Opus") {
            useOpus = !useOpus
            (it as Button).text = if (useOpus) "Codec: Opus" else "Codec: WAV"
        }.also { it.text = if (useOpus) "Codec: Opus" else "Codec: WAV" }, wide())

        column.addView(heading("Upload target"))
        baseUrlField = field(column, "Base URL", masked = false)
        bearerField = field(column, "Bearer token", masked = true)
        accessIdField = field(column, "CF-Access-Client-Id", masked = false)
        accessSecretField = field(column, "CF-Access-Client-Secret", masked = true)
        deviceIdField = field(column, "Device id", masked = false)
        // Empty means photograph anywhere; see UploadSettings.photoSsid.
        photoSsidField = field(column, "Photo Wi-Fi SSID", masked = false)
        column.addView(button("Save target") { saveUploadFields() }, wide())

        column.addView(toggle("Upload: OFF") {
            upload.enabled = !upload.enabled
            (it as Button).text = if (upload.enabled) "Upload: ON" else "Upload: OFF"
        }.also { it.text = if (upload.enabled) "Upload: ON" else "Upload: OFF" }, wide())

        column.addView(toggle("Unmetered only: ON") {
            upload.unmeteredOnly = !upload.unmeteredOnly
            (it as Button).text =
                if (upload.unmeteredOnly) "Unmetered only: ON" else "Unmetered only: OFF"
        }.also {
            it.text = if (upload.unmeteredOnly) "Unmetered only: ON" else "Unmetered only: OFF"
        }, wide())

        // Cycles ja → en → auto. Three options do not justify a spinner on a
        // 240 dp panel, and the label always shows the current value.
        column.addView(toggle("Language: ja") {
            val order = UploadSettings.LANGUAGES
            upload.language = order[(order.indexOf(upload.language) + 1) % order.size]
            (it as Button).text = "Language: ${upload.language}"
        }.also { it.text = "Language: ${upload.language}" }, wide())

        column.addView(button("戻る") { finish() }, wide())

        infoView = TextView(this).apply {
            setTextColor(0xFF909090.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(infoView, wide())

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
            isFocusable = false
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF7FD1A0.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setPadding(0, dp(8), 0, dp(2))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Typing a 64-character secret on a 240 dp panel is miserable, so these
     * are normally provisioned over adb. They exist so the probe still works
     * on a device that is not plugged into a laptop.
     */
    private fun field(parent: LinearLayout, label: String, masked: Boolean): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(0xFF909090.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setPadding(0, dp(3), 0, 0)
        }, wide())

        val edit = EditText(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (masked) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
            setSingleLine(true)
            // After setSingleLine: it re-applies the input type and drops any
            // transformation set before it, leaving the secret in clear.
            if (masked) transformationMethod = PasswordTransformationMethod.getInstance()
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(Color.rgb(32, 32, 32))
                        setStroke(dp(2), R1_ORANGE)
                    },
                )
                addState(intArrayOf(), pill(Color.rgb(32, 32, 32), dp(4)))
            }
        }
        parent.addView(edit, wide())
        return edit
    }

    private fun loadUploadFields() {
        baseUrlField.setText(upload.baseUrl)
        bearerField.setText(upload.bearer)
        accessIdField.setText(upload.accessClientId)
        accessSecretField.setText(upload.accessClientSecret)
        deviceIdField.setText(upload.deviceId)
        photoSsidField.setText(upload.photoSsid)
    }

    private fun saveUploadFields() {
        upload.baseUrl = baseUrlField.text.toString()
        upload.bearer = bearerField.text.toString()
        upload.accessClientId = accessIdField.text.toString()
        upload.accessClientSecret = accessSecretField.text.toString()
        upload.deviceId = deviceIdField.text.toString()
        upload.photoSsid = photoSsidField.text.toString()
        refresh()
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4) }

    /**
     * Painted for the wheel, not for a finger.
     *
     * A custom background replaces the platform's state list, which is where
     * the focus highlight lived — so without these the wheel moved through the
     * page with nothing on screen saying where it was, and the centre key was
     * a guess. The focused state is the R1's orange, matching the home menu.
     */
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(focusedFirst(Color.BLACK, Color.WHITE))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        isAllCaps = false
        minimumHeight = 0; minHeight = dp(30)
        stateListAnimator = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(R1_ORANGE, dp(6)))
            addState(intArrayOf(), pill(Color.rgb(58, 58, 58), dp(6)))
        }
        setOnClickListener { onClick() }
    }

    private fun pill(fill: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(fill)
    }

    private fun focusedFirst(focused: Int, rest: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(focused, rest),
    )

    private fun toggle(label: String, onClick: (View) -> Unit) =
        button(label) {}.apply { setOnClickListener { onClick(this) } }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ control ---

    private fun ensurePermissions() = Recorder.ensurePermissions(this)

    private fun start() = Recorder.start(this)

    private fun stop() = Recorder.stop(this)

    private fun sample() {
        startService(
            Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_SAMPLE)
        )
    }

    private fun refresh() {
        statusView.text = (if (RecorderService.running) "● " else "○ ") + RecorderService.snapshot

        val caps = runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
        }.getOrNull()

        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false

        infoView.text = buildString {
            append("net: $transport, unmetered=$unmetered\n")
            append("upload: ")
            append(if (upload.enabled) "on" else "off")
            append(if (upload.unmeteredOnly) ", unmetered only" else ", any network")
            append(if (upload.isConfigured) "" else ", NOT CONFIGURED")
            append("\n")
            append(upload.baseUrl).append("\n")
            append("log: ${metrics.sizeBytes() / 1024} KiB")
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
