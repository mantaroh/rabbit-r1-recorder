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
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    }

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var metrics: Metrics
    private lateinit var upload: UploadSettings

    private val ticker = Handler(Looper.getMainLooper())
    private var useWakeLock = true
    private var useVoiceRecognition = false
    private var writeAudio = true

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
        ensurePermissions()
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
        column.addView(toggle("Write audio: ON") {
            writeAudio = !writeAudio
            (it as Button).text = if (writeAudio) "Write audio: ON" else "Write audio: OFF"
        }, wide())

        column.addView(toggle("Wake lock: ON") {
            useWakeLock = !useWakeLock
            (it as Button).text = if (useWakeLock) "Wake lock: ON" else "Wake lock: OFF"
        }, wide())

        column.addView(toggle("Source: MIC") {
            useVoiceRecognition = !useVoiceRecognition
            (it as Button).text =
                if (useVoiceRecognition) "Source: VOICE_RECOGNITION" else "Source: MIC"
        }, wide())

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
