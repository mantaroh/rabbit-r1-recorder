package com.r1.hermes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The chat surface: transcript, streaming, tool activity, and the interaction
 * modals — everything a turn can produce on a 240x320 dp panel.
 */
class ChatActivity : Activity() {

    companion object {
        /** Present to resume an existing conversation; absent to start one. */
        const val EXTRA_SESSION_ID = "session_id"

        /** True when the id came from session.active_list rather than history. */
        const val EXTRA_LIVE = "live"

        /**
         * A question captured elsewhere — the recorder's double-press flow
         * transcribes the utterance and hands the text over rather than
         * re-implementing the chat surface.
         */
        const val EXTRA_PROMPT = "prompt"

        private const val REQUEST_RECORD_AUDIO = 3101
        private const val REQUEST_CAPTURE = 3102

        /** Deltas arrive pre-batched by the server; repaint on a ticker, not per frame. */
        private const val REPAINT_MS = 50L

        private const val COLOUR_USER = 0xFF7FD1A0.toInt()
        private const val COLOUR_ASSISTANT = 0xFFFFFFFF.toInt()
        private const val COLOUR_DIM = 0xFF808080.toInt()
        private const val COLOUR_ERROR = 0xFFEF5350.toInt()
    }

    private lateinit var settings: Settings
    private lateinit var client: HermesClient
    private lateinit var recorder: VoiceRecorder

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var toolStrip: TextView
    private lateinit var primaryButton: Button
    private lateinit var micButton: Button
    private lateinit var cameraButton: Button
    private lateinit var keyboardButton: Button
    private lateinit var composer: EditText
    private lateinit var overlay: FrameLayout

    private val transcript = SpannableStringBuilder()
    private val streamBuffer = StringBuilder()
    private var streamStart = -1
    private var repaintScheduled = false

    private var sessionId: String? = null
    private var model: String = "—"
    private var contextPercent: Int = -1
    private var statusWord: String = "connecting"
    private var running = false
    private var pttActive = false

    /** True when the session is known to be live in the gateway process. */
    private var liveSession = false

    /** Staged photo, uploaded as part of the next submit. */
    private var pendingPhoto: File? = null

    /** Prompt supplied by the caller, submitted once a session is attached. */
    private var pendingPrompt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        client = HermesClient(settings)
        recorder = VoiceRecorder(this)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        liveSession = intent.getBooleanExtra(EXTRA_LIVE, false)
        pendingPrompt = intent.getStringExtra(EXTRA_PROMPT)?.trim()?.ifEmpty { null }

        setContentView(buildUi())
        renderStatus()

        client.setListener(object : HermesClient.Listener {
            override fun onState(state: HermesClient.State, detail: String?) {
                statusWord = when (state) {
                    HermesClient.State.CONNECTED -> "idle"
                    HermesClient.State.CONNECTING -> "connecting"
                    HermesClient.State.RECONNECTING -> "reconnecting"
                    HermesClient.State.FAILED -> "failed"
                    HermesClient.State.IDLE -> "offline"
                }
                if (state == HermesClient.State.FAILED && detail != null) {
                    appendLine("connection failed — $detail", COLOUR_ERROR)
                }
                renderStatus()
            }

            override fun onReady(payload: JSONObject) = openSession()

            override fun onEvent(type: String, payload: JSONObject) = handleEvent(type, payload)

            override fun onLog(line: String) = Unit
        })

        client.connect()
    }

    /**
     * The idle timer decides when the device drops to a standby display. What
     * that display is belongs to whichever app is hosting this screen — the
     * standalone client sets nothing and never goes to standby, which is right
     * for something only ever open because somebody is using it.
     */
    override fun onResume() {
        super.onResume()
        com.r1.core.Idle.watch(this)
    }

    override fun onPause() {
        com.r1.core.Idle.release(this)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        com.r1.core.Idle.touch()
    }

    override fun onDestroy() {
        recorder.cancel()
        client.setListener(null)
        client.disconnect()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI ----

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // The R1 has no Back key and no back gesture, so every screen needs a
        // visible way out or it traps the user.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            setTextColor(COLOUR_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        statusRow.addView(statusView, LinearLayout.LayoutParams(0, WRAP, 1f))
        statusRow.addView(
            TextView(this).apply {
                text = "✕"
                setTextColor(COLOUR_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )
        column.addView(statusRow, matchWidth())

        transcriptView = TextView(this).apply {
            setTextColor(COLOUR_ASSISTANT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setTextIsSelectable(false)
        }
        transcriptScroll = ScrollView(this).apply { addView(transcriptView) }
        column.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        toolStrip = TextView(this).apply {
            setTextColor(0xFFFFBF00.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = View.GONE
            setSingleLine(true)
        }
        column.addView(toolStrip, matchWidth())

        composer = EditText(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            hint = "hold 🎙 to talk — sends on release"
            setHintTextColor(COLOUR_DIM)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.rgb(28, 28, 28))
            }
        }
        column.addView(composer, matchWidth().apply { topMargin = dp(2) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(3), dp(4), dp(4))
        }

        // Voice is the primary composer, so it gets an always-visible control
        // rather than living only on the hardware side button — and the IME,
        // which eats half of a 320dp panel, stays a deliberate fallback.
        micButton = flatButton("🎙 Hold") { }.apply {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.isPressed = true; startPtt(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false; stopPtt(); true
                    }
                    else -> false
                }
            }
        }
        primaryButton = flatButton("Send") { onPrimary() }
        keyboardButton = flatButton("⌨") { toggleKeyboard() }

        cameraButton = flatButton("📷") { openCamera() }
        actions.addView(micButton, LinearLayout.LayoutParams(0, dp(34), 1.3f))
        actions.addView(
            cameraButton,
            LinearLayout.LayoutParams(dp(36), dp(34)).apply { marginStart = dp(4) }
        )
        actions.addView(
            primaryButton,
            LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(4) }
        )
        actions.addView(
            keyboardButton,
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(4) }
        )
        column.addView(actions, matchWidth())

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xF2000000.toInt())
            visibility = View.GONE
            isClickable = true // swallow taps meant for the transcript beneath
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        return root
    }

    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun matchWidth() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun flatButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        isAllCaps = false
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        setPadding(dp(2), 0, dp(2), 0)
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.rgb(58, 58, 58))
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderStatus() {
        val ctx = if (contextPercent >= 0) " · ctx $contextPercent%" else ""
        val photo = if (pendingPhoto != null) " · 📷" else ""
        statusView.text = "$model · $statusWord$ctx$photo"
        primaryButton.text = if (running) "■ Stop" else "Send"
        micButton.text = if (pttActive) "● REC" else "🎙 Hold"
        (micButton.background as? GradientDrawable)?.setColor(
            if (pttActive) 0xFFB33A3A.toInt() else Color.rgb(58, 58, 58)
        )
    }

    // ----------------------------------------------------------- session ----

    /**
     * Reattaching takes two different calls and picking the wrong one fails.
     *
     * `session.activate` attaches to a session still live in the gateway
     * process — it reads the in-memory table, no database involved.
     * `session.resume` rehydrates one from the profile's `state.db`. A live
     * session that has not been written out yet only answers to the first; an
     * old one only to the second. Try the likely one and fall back, rather
     * than making the caller be right.
     */
    private fun openSession() {
        statusWord = "starting"
        renderStatus()

        val existing = sessionId
        when {
            existing == null -> attach("session.create", null, fallback = null)
            liveSession -> attach("session.activate", existing, "session.resume")
            else -> attach("session.resume", existing, "session.activate")
        }
    }

    private fun attach(method: String, id: String?, fallback: String?) {
        val params = JSONObject()
        if (id != null) params.put("session_id", id)
        // activate takes neither: it reattaches to a session that already has
        // its width and profile fixed.
        if (method != "session.activate") {
            params.put("cols", Settings.COLS).put("profile", settings.profile)
        }

        client.call(method, params) { result, error ->
            if (error != null || result == null) {
                if (fallback != null) {
                    attach(fallback, id, null)
                    return@call
                }
                appendLine("$method failed — ${error ?: "no result"}", COLOUR_ERROR)
                statusWord = "failed"
                renderStatus()
                return@call
            }
            applyAttachResult(result)
        }
    }

    /**
     * A second question, asked while this screen already exists.
     *
     * Without this the audio probe's hand-off silently did nothing on every
     * question after the first: `startActivity` was allowed and the task came
     * to the front, but a standard-launchMode activity that is already running
     * is never handed the new Intent, so the transcript was dropped and the
     * user saw the previous conversation. The manifest asks for singleTask so
     * the Intent arrives here instead of creating a second instance.
     */
    override fun onNewIntent(incoming: Intent) {
        super.onNewIntent(incoming)
        setIntent(incoming)

        val prompt = incoming.getStringExtra(EXTRA_PROMPT)?.trim()?.ifEmpty { null } ?: return
        // Submit straight away when there is a session to submit to; otherwise
        // hold it for applyAttachResult, exactly as a cold start does.
        if (sessionId != null && !running) submit(prompt) else pendingPrompt = prompt
    }

    private fun applyAttachResult(result: JSONObject) {
        sessionId = result.optString("session_id").ifEmpty { sessionId }
        // Once attached the session is live in the gateway, so a reconnect
        // should activate rather than go back to the database.
        liveSession = true

        result.optJSONObject("info")?.let { applyInfo(it) }
        result.optJSONArray("messages")?.let { replay(it) }
        result.optJSONObject("inflight")?.let { turn ->
            val partial = turn.optString("assistant")
            if (partial.isNotEmpty()) appendRole("hermes", partial, COLOUR_ASSISTANT)
        }

        running = result.optBoolean("running", false)
        statusWord = result.optString("status").ifEmpty { "idle" }
        renderStatus()

        // A prompt handed in from outside is sent once the session exists,
        // not on arrival — there is nothing to submit to before then.
        pendingPrompt?.let { text ->
            pendingPrompt = null
            submit(text)
        }
    }

    private fun replay(messages: JSONArray) {
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val text = m.optString("text")
            if (text.isBlank()) continue
            when (m.optString("role")) {
                "user" -> appendRole("you", text, COLOUR_USER)
                "assistant" -> appendRole("hermes", text, COLOUR_ASSISTANT)
                else -> Unit // tool/system output is noise on this panel
            }
        }
    }

    private fun applyInfo(info: JSONObject) {
        info.optString("model").takeIf { it.isNotEmpty() }?.let { model = it }
        info.optJSONObject("usage")?.let { usage ->
            if (usage.has("context_percent") && !usage.isNull("context_percent")) {
                contextPercent = usage.optDouble("context_percent", -1.0).toInt()
            }
        }
        renderStatus()
    }

    // ------------------------------------------------------------ events ----

    private fun handleEvent(type: String, payload: JSONObject) {
        when (type) {
            "session.info" -> applyInfo(payload)

            "status.update" -> {
                statusWord = payload.optString("text").ifEmpty { payload.optString("kind") }
                renderStatus()
            }

            "message.start" -> beginStream()
            "message.delta" -> appendStream(payload.optString("text"))
            "message.complete" -> {
                val full = payload.optString("text")
                endStream(if (full.isNotEmpty()) full else null)
                running = false
                statusWord = "idle"
                renderStatus()
            }

            "reasoning.delta", "thinking.delta" -> {
                // No room for a thinking panel; the status line shows it is alive.
                statusWord = "thinking"
                renderStatus()
            }

            "tool.start", "tool.generating" -> showTool(payload, running = true)
            "tool.progress" -> showTool(payload, running = true)
            "tool.complete" -> showTool(payload, running = false)

            "approval.request" -> showApproval(payload)
            "clarify.request" -> showClarify(payload)
            "sudo.request" -> showTextPrompt("sudo password", payload, "sudo.respond", "password")
            "secret.request" -> showTextPrompt(
                payload.optString("prompt").ifEmpty { payload.optString("env_var") },
                payload, "secret.respond", "value"
            )
            "clarify.expire", "sudo.expire", "secret.expire" -> hideOverlay()

            "notification.show" -> appendLine(payload.optString("text"), COLOUR_DIM)
            "gateway.stderr", "gateway.protocol_error" ->
                appendLine(payload.optString("text").ifEmpty { type }, COLOUR_ERROR)

            "error" -> {
                appendLine(payload.optString("message").ifEmpty { "error" }, COLOUR_ERROR)
                running = false
                renderStatus()
            }
        }
    }

    private fun showTool(payload: JSONObject, running: Boolean) {
        val name = payload.optString("name").ifEmpty { "tool" }
        val context = payload.optString("context")
        val glyph = if (running) "▶" else "✓"
        toolStrip.text = "$glyph $name" + (if (context.isNotEmpty()) "  $context" else "")
        toolStrip.visibility = View.VISIBLE
        if (!running) {
            toolStrip.postDelayed({
                if (toolStrip.text.startsWith("✓")) toolStrip.visibility = View.GONE
            }, 2_000)
        }
    }

    // --------------------------------------------------------- transcript ---

    private fun appendRole(role: String, text: String, colour: Int) {
        val start = transcript.length
        transcript.append(role).append('\n')
        transcript.setSpan(
            ForegroundColorSpan(COLOUR_DIM), start, transcript.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        transcript.setSpan(
            StyleSpan(Typeface.BOLD), start, transcript.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val bodyStart = transcript.length
        transcript.append(text).append("\n\n")
        transcript.setSpan(
            ForegroundColorSpan(colour), bodyStart, transcript.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        paint()
    }

    private fun appendLine(text: String, colour: Int) {
        if (text.isBlank()) return
        val start = transcript.length
        transcript.append(text).append('\n')
        transcript.setSpan(
            ForegroundColorSpan(colour), start, transcript.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        paint()
    }

    private fun beginStream() {
        streamBuffer.setLength(0)
        appendRole("hermes", "", COLOUR_ASSISTANT)
        // The body sits just before the two trailing newlines appendRole added.
        streamStart = transcript.length - 2
    }

    private fun appendStream(text: String) {
        if (text.isEmpty()) return
        if (streamStart < 0) beginStream()
        streamBuffer.append(text)
        scheduleRepaint()
    }

    private fun scheduleRepaint() {
        if (repaintScheduled) return
        repaintScheduled = true
        transcriptView.postDelayed({
            repaintScheduled = false
            flushStream()
        }, REPAINT_MS)
    }

    private fun flushStream() {
        if (streamStart < 0) return
        val end = transcript.length - 2
        if (end >= streamStart) transcript.replace(streamStart, end, streamBuffer)
        transcript.setSpan(
            ForegroundColorSpan(COLOUR_ASSISTANT), streamStart,
            streamStart + streamBuffer.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        paint()
    }

    private fun endStream(finalText: String?) {
        if (streamStart < 0) {
            if (finalText != null) appendRole("hermes", finalText, COLOUR_ASSISTANT)
            return
        }
        if (finalText != null) {
            streamBuffer.setLength(0)
            streamBuffer.append(finalText)
        }
        flushStream()
        streamStart = -1
        streamBuffer.setLength(0)
    }

    /** Only follow the tail when already at it — never yank a reader back down. */
    private fun paint() {
        val atBottom = !transcriptScroll.canScrollVertically(1)
        // A copy, not the live builder: handing TextView the same Spannable
        // instance it already holds lets it treat the call as a no-op, so the
        // appended text never reaches the screen.
        transcriptView.setText(SpannableStringBuilder(transcript), TextView.BufferType.SPANNABLE)
        if (atBottom) {
            transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------ compose ---

    private fun onPrimary() {
        if (running) {
            interrupt()
            return
        }
        val text = composer.text.toString().trim()
        if (text.isEmpty()) return
        submit(text)
    }

    /**
     * A photo is a separate upload that queues on the session; the next
     * `prompt.submit` consumes whatever is queued. So attach first, then send —
     * and only send once the attach lands, or the turn goes out without it.
     */
    private fun submit(text: String) {
        val sid = sessionId ?: run {
            appendLine("no session yet", COLOUR_ERROR)
            return
        }

        val photo = pendingPhoto
        if (photo == null) {
            send(sid, text)
            return
        }

        pendingPhoto = null
        statusWord = "uploading photo"
        renderStatus()

        val encoded = runCatching {
            Base64.encodeToString(photo.readBytes(), Base64.NO_WRAP)
        }.getOrNull()

        if (encoded == null) {
            appendLine("could not read the photo", COLOUR_ERROR)
            send(sid, text)
            return
        }

        client.call(
            "image.attach_bytes",
            JSONObject()
                .put("session_id", sid)
                .put("content_base64", "data:image/jpeg;base64,$encoded")
                .put("filename", "r1.jpg")
        ) { result, error ->
            if (error != null) {
                appendLine("photo upload failed — $error", COLOUR_ERROR)
                send(sid, text)
                return@call
            }
            appendLine("📷 attached (${(result?.optInt("bytes") ?: 0) / 1024} KiB)", COLOUR_DIM)
            // With no words of their own, use the caption the gateway itself
            // proposes for the attachment rather than inventing a prompt.
            val body = text.ifEmpty {
                result?.optString("text").orEmpty().ifEmpty { "[User attached an image]" }
            }
            send(sid, body)
        }
    }

    private fun send(sid: String, text: String) {
        appendRole("you", text, COLOUR_USER)
        composer.setText("")
        running = true
        statusWord = "working"
        renderStatus()

        client.call(
            "prompt.submit",
            JSONObject().put("session_id", sid).put("text", text)
        ) { _, error ->
            if (error != null) {
                appendLine("prompt.submit failed — $error", COLOUR_ERROR)
                running = false
                renderStatus()
            }
        }
    }

    private fun openCamera() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, CaptureActivity::class.java),
            REQUEST_CAPTURE
        )
    }

    @Deprecated("Deprecated in Android API, kept to stay dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE || resultCode != RESULT_OK) return

        val path = data?.getStringExtra(CaptureActivity.EXTRA_PHOTO_PATH) ?: return
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return

        pendingPhoto = file
        appendLine("📷 photo ready (${file.length() / 1024} KiB) — add a message", COLOUR_DIM)
        renderStatus()
    }

    private fun interrupt() {
        val sid = sessionId ?: return
        statusWord = "stopping"
        renderStatus()
        // One call: it also denies outstanding approvals and clears the queue.
        client.call("session.interrupt", JSONObject().put("session_id", sid)) { _, error ->
            running = false
            statusWord = if (error != null) "idle" else "interrupted"
            hideOverlay()
            renderStatus()
        }
    }

    private fun toggleKeyboard() {
        composer.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
    }

    // -------------------------------------------------------------- voice ---

    private fun startPtt() {
        if (pttActive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        val failure = recorder.start()
        if (failure != null) {
            appendLine("microphone unavailable — $failure", COLOUR_ERROR)
            return
        }
        pttActive = true
        statusWord = "listening"
        renderStatus()
    }

    private fun stopPtt() {
        if (!pttActive) return
        pttActive = false
        val file = recorder.stop()
        statusWord = if (running) "working" else "idle"
        renderStatus()

        if (file == null) return // too short to finalise; treat as a stray tap

        statusWord = "transcribing"
        renderStatus()
        Transcriber(settings).transcribe(file, "audio/mp4") { result, error ->
            statusWord = if (running) "working" else "idle"
            renderStatus()
            when {
                error != null -> appendLine("transcription failed — $error", COLOUR_ERROR)
                result == null || result.transcript.isEmpty() -> Unit // silence
                else -> {
                    // Speak-and-go: the transcript is submitted directly. A
                    // misheard prompt is recoverable — the turn shows in the
                    // transcript and Stop interrupts it.
                    submit(result.transcript)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            appendLine("microphone permission denied", COLOUR_ERROR)
        }
    }

    // -------------------------------------------------------------- input ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // A modal owns the wheel while it is up: choosing beats scrolling.
        if (overlayVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { moveOverlaySelection(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveOverlaySelection(1); true }
                KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event?.repeatCount == 0) {
                        overlayChoices.getOrNull(overlaySelected)?.performClick()
                    }
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            // Measured on the device: the wheel reports d-pad keys, not rotary motion.
            KeyEvent.KEYCODE_DPAD_UP -> { scrollTranscript(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { scrollTranscript(1); true }

            // The side button auto-repeats while held; only the first press counts.
            KeyEvent.KEYCODE_BUTTON_1 -> {
                if (event != null && event.repeatCount == 0) {
                    if (running) interrupt() else startPtt()
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_1) {
            if (overlayVisible) return true
            stopPtt()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun scrollTranscript(direction: Int) {
        val step = transcriptView.lineHeight * 3
        transcriptScroll.smoothScrollBy(0, step * direction)
    }

    // ------------------------------------------------------------ modals ----

    /**
     * Modal buttons are wheel-navigable. Android's focus system does nothing in
     * touch mode, so the overlay keeps its own cursor over the choices it
     * registered.
     */
    private val overlayChoices = mutableListOf<Button>()
    private var overlaySelected = 0

    private fun registerChoice(button: Button): Button {
        overlayChoices.add(button)
        return button
    }

    private fun applyOverlaySelection(scroll: Boolean) {
        overlayChoices.forEachIndexed { index, button ->
            (button.background as? GradientDrawable)?.apply {
                setColor(if (index == overlaySelected) Color.rgb(44, 60, 52) else Color.rgb(58, 58, 58))
                setStroke(
                    dp(1),
                    if (index == overlaySelected) 0xFF7FD1A0.toInt() else Color.TRANSPARENT
                )
            }
        }
        if (scroll) overlayChoices.getOrNull(overlaySelected)?.requestRectangleOnScreen(
            android.graphics.Rect(0, 0, 1, overlayChoices[overlaySelected].height)
        )
    }

    private fun moveOverlaySelection(delta: Int) {
        if (overlayChoices.isEmpty()) return
        overlaySelected = (overlaySelected + delta).coerceIn(0, overlayChoices.size - 1)
        applyOverlaySelection(scroll = true)
    }

    private val overlayVisible: Boolean get() = overlay.visibility == View.VISIBLE

    private fun hideOverlay() {
        overlay.removeAllViews()
        overlay.visibility = View.GONE
        overlayChoices.clear()
        overlaySelected = 0
    }

    private fun overlayColumn(): LinearLayout {
        overlay.removeAllViews()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        overlay.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.visibility = View.VISIBLE
        return column
    }

    private fun overlayTitle(text: String, colour: Int) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(null, Typeface.BOLD)
    }

    private fun showApproval(payload: JSONObject) {
        val sid = sessionId ?: return
        val column = overlayColumn()

        column.addView(overlayTitle("⚠ Approval required", 0xFFFFA726.toInt()), matchWidth())

        val description = payload.optString("description")
        if (description.isNotEmpty()) {
            column.addView(TextView(this).apply {
                text = description
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, dp(4), 0, dp(4))
            }, matchWidth())
        }

        // The command is the thing being authorised — it scrolls, never elides.
        val commandView = TextView(this).apply {
            text = payload.optString("command")
            setTextColor(0xFFFFD180.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.rgb(24, 24, 24))
            }
        }
        column.addView(
            ScrollView(this).apply { addView(commandView) },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )

        fun respond(choice: String) {
            hideOverlay()
            client.call(
                "approval.respond",
                JSONObject().put("session_id", sid).put("choice", choice)
            ) { _, error ->
                if (error != null) appendLine("approval.respond failed — $error", COLOUR_ERROR)
            }
        }

        // Valid choices are once|session|always|deny — anything else is silently
        // mapped to deny by the backend, so do not invent an "allow".
        column.addView(registerChoice(flatButton("Allow once") { respond("once") }), buttonRow())
        column.addView(
            registerChoice(flatButton("Allow this session") { respond("session") }),
            buttonRow()
        )
        if (payload.optBoolean("allowPermanent", true) &&
            payload.optBoolean("allow_permanent", true)
        ) {
            column.addView(
                registerChoice(flatButton("Always allow") { respond("always") }),
                buttonRow()
            )
        }
        column.addView(registerChoice(flatButton("Deny") { respond("deny") }), buttonRow())

        // Deny is the safe landing spot for an accidental confirm.
        overlaySelected = overlayChoices.size - 1
        applyOverlaySelection(scroll = false)
    }

    private fun buttonRow() = LinearLayout.LayoutParams(MATCH, dp(30))
        .apply { topMargin = dp(4) }

    private fun showClarify(payload: JSONObject) {
        val requestId = payload.optString("request_id")
        val column = overlayColumn()

        column.addView(overlayTitle("? " + payload.optString("question"), 0xFF7FD1A0.toInt()),
            matchWidth())

        fun respond(answer: String) {
            hideOverlay()
            client.call(
                "clarify.respond",
                JSONObject().put("request_id", requestId).put("answer", answer)
            ) { _, error ->
                if (error != null) appendLine("clarify.respond failed — $error", COLOUR_ERROR)
            }
        }

        val choices = payload.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (i in 0 until choices.length()) {
                val choice = choices.optString(i)
                list.addView(registerChoice(flatButton(choice) { respond(choice) }), buttonRow())
            }
            column.addView(
                ScrollView(this).apply { addView(list) },
                LinearLayout.LayoutParams(MATCH, 0, 1f)
            )
            applyOverlaySelection(scroll = false)
        } else {
            val input = overlayInput(masked = false)
            column.addView(input, matchWidth().apply { topMargin = dp(6) })
            column.addView(
                flatButton("Answer") { respond(input.text.toString()) },
                buttonRow()
            )
        }
    }

    private fun showTextPrompt(
        title: String,
        payload: JSONObject,
        method: String,
        answerKey: String,
    ) {
        val requestId = payload.optString("request_id")
        val column = overlayColumn()
        column.addView(overlayTitle(title.ifEmpty { "input required" }, 0xFFFFA726.toInt()),
            matchWidth())

        val input = overlayInput(masked = true)
        column.addView(input, matchWidth().apply { topMargin = dp(6) })
        column.addView(flatButton("Send") {
            hideOverlay()
            client.call(
                method,
                JSONObject().put("request_id", requestId).put(answerKey, input.text.toString())
            ) { _, error ->
                if (error != null) appendLine("$method failed — $error", COLOUR_ERROR)
            }
        }, buttonRow())
    }

    private fun overlayInput(masked: Boolean) = EditText(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        inputType = InputType.TYPE_CLASS_TEXT or
            if (masked) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
        setSingleLine(true)
        gravity = Gravity.START
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.rgb(28, 28, 28))
        }
    }
}
