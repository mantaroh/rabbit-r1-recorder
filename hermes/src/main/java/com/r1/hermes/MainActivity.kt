package com.r1.hermes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * Settings and connection diagnostics.
 *
 * This is deliberately the first screen built: bringing the deployment up
 * showed that every hop fails with its own status — 403 at Cloudflare Access,
 * 401 at the Hermes session gate, 400 on a Host mismatch, 502 when the origin
 * is down — and that a generic "cannot connect" hides all of it. The staged
 * test names the hop that failed.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 3001
        private const val RECORD_MS = 4_000L
    }

    private lateinit var settings: Settings
    private lateinit var client: HermesClient

    private lateinit var baseUrlField: EditText
    private lateinit var accessIdField: EditText
    private lateinit var accessSecretField: EditText
    private lateinit var sessionTokenField: EditText
    private lateinit var profileField: EditText
    private lateinit var logView: TextView
    private lateinit var scroller: ScrollView

    private val lines = ArrayDeque<String>()
    private var recorder: MediaRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        client = HermesClient(settings)
        setContentView(buildUi())
        loadFields()

        client.setListener(object : HermesClient.Listener {
            override fun onState(state: HermesClient.State, detail: String?) {
                log("  state: $state" + (detail?.let { " — $it" } ?: ""))
            }

            override fun onReady(payload: JSONObject) {
                log("  ✓ gateway.ready received — WebSocket OK")
                client.call("session.active_list") { result, error ->
                    if (error != null) {
                        log("  ✗ session.active_list: $error")
                    } else {
                        val n = result?.optJSONArray("sessions")?.length() ?: 0
                        log("  ✓ session.active_list: $n live session(s)")
                    }
                    client.disconnect()
                    log("connection test finished")
                }
            }

            override fun onEvent(type: String, payload: JSONObject) {
                if (type != "gateway.ready") log("  event: $type")
            }

            override fun onLog(line: String) = log("  $line")
        })
    }

    override fun onDestroy() {
        client.setListener(null)
        client.disconnect()
        stopRecorder()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI ----

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Reached from the sessions list, on a device with no Back key.
        root.addView(button("← Back") { saveFields(); finish() })

        root.addView(heading("Hermes connection"))

        baseUrlField = field(root, "Base URL", InputType.TYPE_TEXT_VARIATION_URI)
        accessIdField = field(root, "CF-Access-Client-Id", InputType.TYPE_CLASS_TEXT)
        // Masked: these are the credentials themselves, and this screen is the
        // one thing anyone glancing at (or screenshotting) the device will see.
        accessSecretField = field(root, "CF-Access-Client-Secret", InputType.TYPE_CLASS_TEXT, secret = true)
        sessionTokenField = field(root, "X-Hermes-Session-Token", InputType.TYPE_CLASS_TEXT, secret = true)
        profileField = field(root, "Profile", InputType.TYPE_CLASS_TEXT)

        root.addView(button("Save") { saveFields(); log("saved") })
        root.addView(button("Test connection") { saveFields(); testConnection() })
        root.addView(button("Test voice → text") { saveFields(); testTranscription() })
        root.addView(button("Input probe") {
            startActivity(Intent(this, ProbeActivity::class.java))
        })

        root.addView(heading("Log"))
        logView = TextView(this).apply {
            setTextColor(Color.rgb(200, 200, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
        }
        root.addView(
            logView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        scroller = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        }
        return scroller
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(120, 220, 160))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        inputType: Int,
        secret: Boolean = false,
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(150, 150, 150))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(0, dp(4), 0, 0)
        })
        val edit = EditText(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.inputType = inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
            setSingleLine(true)
            // After setSingleLine: it re-applies the input type and drops any
            // transformation method set before it, leaving the value in clear.
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.rgb(32, 32, 32))
            }
        }
        parent.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return edit
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        isAllCaps = false
        minimumHeight = 0
        minHeight = dp(30)
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.rgb(58, 58, 58))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun log(line: String) {
        lines.addLast(line)
        while (lines.size > 200) lines.removeFirst()
        logView.text = lines.joinToString("\n")
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------- settings ----

    private fun loadFields() {
        baseUrlField.setText(settings.baseUrl)
        accessIdField.setText(settings.accessClientId)
        accessSecretField.setText(settings.accessClientSecret)
        sessionTokenField.setText(settings.sessionToken)
        profileField.setText(settings.profile)
    }

    private fun saveFields() {
        settings.baseUrl = baseUrlField.text.toString()
        settings.accessClientId = accessIdField.text.toString()
        settings.accessClientSecret = accessSecretField.text.toString()
        settings.sessionToken = sessionTokenField.text.toString()
        settings.profile = profileField.text.toString()
    }

    // ------------------------------------------------------ diagnostics ----

    /** Stage 1 is the public health route, stage 2 the credential-gated socket. */
    private fun testConnection() {
        log("--- connection test ---")
        log("1/2 GET /api/health")

        HermesHttp(settings).health { health, error ->
            if (error != null) {
                log("  ✗ $error")
                log("connection test failed at the HTTP hop")
                return@health
            }
            log("  ✓ hermes ${health?.version ?: "?"} (auth_required=${health?.authRequired})")
            log("2/2 WSS /api/ws")
            client.connect()
        }
    }

    private fun testTranscription() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        val target = File(cacheDir, "probe.m4a")
        log("--- transcription test ---")
        log("recording ${RECORD_MS / 1000}s — speak now")

        val rec = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setOutputFile(target.absolutePath)
        }
        recorder = rec

        try {
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            log("  ✗ recorder failed: ${t.message}")
            stopRecorder()
            return
        }

        logView.postDelayed({
            stopRecorder()
            log("uploading ${target.length() / 1024} KiB to profile=${settings.profile}")
            Transcriber(settings).transcribe(target, "audio/mp4") { result, error ->
                when {
                    error != null -> log("  ✗ $error")
                    result == null -> log("  ✗ no result")
                    result.transcript.isEmpty() ->
                        log("  ✓ round trip OK — empty transcript (silence)")
                    else -> {
                        log("  ✓ ${result.provider ?: "?"}: \"${result.transcript}\"")
                    }
                }
            }
        }, RECORD_MS)
    }

    private fun stopRecorder() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    /** The wheel scrolls the form — this screen is a form, not a menu. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> { scroller.smoothScrollBy(0, -dp(56)); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { scroller.smoothScrollBy(0, dp(56)); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                testTranscription()
            } else {
                log("microphone permission denied")
            }
        }
    }
}
