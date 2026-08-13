package com.r1.audioprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.KeyEvent
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
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 4001

        /** Begin recording as soon as the Activity is up; see onCreate. */
        const val EXTRA_AUTOSTART = "autostart"
    }

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var baseUrlField: EditText
    private lateinit var bearerField: EditText
    private lateinit var accessIdField: EditText
    private lateinit var accessSecretField: EditText
    private lateinit var deviceIdField: EditText
    private lateinit var metrics: Metrics
    private lateinit var upload: UploadSettings

    private val ticker = Handler(Looper.getMainLooper())
    private var useWakeLock = true
    private var useVoiceRecognition = false
    // On by default. The recording is the artefact this device exists to
    // produce, and an hour not captured is an hour that no later decision can
    // recover — so the failure mode of forgetting to press a button has to be
    // "recorded something", not "recorded nothing".
    private var writeAudio = true
    private var useOpus = false

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

        // `am start -n … --ez autostart true` begins recording without anyone
        // touching the screen. A microphone foreground service cannot be
        // started from the background on Android 14, so anything that wants
        // recording to resume — a boot receiver, a shell, a person in a hurry —
        // has to come through this Activity.
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) start()
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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        column.addView(TextView(this).apply {
            text = "R1 Audio Probe"
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
        }, wide())

        column.addView(toggle("Source: MIC") {
            useVoiceRecognition = !useVoiceRecognition
            (it as Button).text =
                if (useVoiceRecognition) "Source: VOICE_RECOGNITION" else "Source: MIC"
        }, wide())

        column.addView(toggle("Codec: WAV") {
            useOpus = !useOpus
            (it as Button).text = if (useOpus) "Codec: Opus" else "Codec: WAV"
        }, wide())

        column.addView(heading("Upload target"))
        baseUrlField = field(column, "Base URL", masked = false)
        bearerField = field(column, "Bearer token", masked = true)
        accessIdField = field(column, "CF-Access-Client-Id", masked = false)
        accessSecretField = field(column, "CF-Access-Client-Secret", masked = true)
        deviceIdField = field(column, "Device id", masked = false)
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

        column.addView(button("Quit") { finishAndRemoveTask() }, wide())

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
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.rgb(32, 32, 32))
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
    }

    private fun saveUploadFields() {
        upload.baseUrl = baseUrlField.text.toString()
        upload.bearer = bearerField.text.toString()
        upload.accessClientId = accessIdField.text.toString()
        upload.accessClientSecret = accessSecretField.text.toString()
        upload.deviceId = deviceIdField.text.toString()
        refresh()
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        isAllCaps = false
        minimumHeight = 0; minHeight = dp(30)
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.rgb(58, 58, 58))
        }
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, onClick: (View) -> Unit) =
        button(label) {}.apply { setOnClickListener { onClick(this) } }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ control ---

    private fun ensurePermissions() {
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ensurePermissions()
            return
        }
        val intent = Intent(this, RecorderService::class.java)
            .putExtra(RecorderService.EXTRA_WAKELOCK, useWakeLock)
            .putExtra(
                RecorderService.EXTRA_SOURCE,
                if (useVoiceRecognition) MediaRecorder.AudioSource.VOICE_RECOGNITION
                else MediaRecorder.AudioSource.MIC
            )
            .putExtra(RecorderService.EXTRA_WRITE_AUDIO, writeAudio)
            .putExtra(RecorderService.EXTRA_OPUS, useOpus)
        // Started from a visible Activity: Android 14 blocks starting a
        // microphone foreground service from the background.
        startForegroundService(intent)
    }

    private fun stop() {
        stopService(Intent(this, RecorderService::class.java))
    }

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

    /** Wheel scrolls; the R1 has no Back key so Quit is the only way out. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
        else -> super.onKeyDown(keyCode, event)
    }
}
