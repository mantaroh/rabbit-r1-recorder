package com.r1.hermes

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launch screen: pick up a conversation or start one.
 *
 * Two sources are merged. `session.active_list` is the live set and is the only
 * one carrying `status`, so a session left mid-turn is visible as such;
 * `session.list` is the persisted history. Live entries sort first because a
 * `working` session is the one you most likely walked away from.
 */
class SessionsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var client: HermesClient

    private lateinit var statusView: TextView
    private lateinit var listColumn: LinearLayout
    private lateinit var scroller: ScrollView

    private val liveById = LinkedHashMap<String, JSONObject>()
    private val storedById = LinkedHashMap<String, JSONObject>()
    private var pendingCalls = 0

    /**
     * Wheel navigation needs a selection model of its own: Android's focus
     * system stays out of the way in touch mode, so nothing moves when the
     * wheel emits d-pad keys.
     */
    private val rows = mutableListOf<Row>()
    private var selected = 0

    private class Row(val view: View, val activate: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        client = HermesClient(settings)
        setContentView(buildUi())

        client.setListener(object : HermesClient.Listener {
            override fun onState(state: HermesClient.State, detail: String?) {
                statusView.text = when (state) {
                    HermesClient.State.CONNECTED -> "● online"
                    HermesClient.State.CONNECTING -> "◦ connecting"
                    HermesClient.State.RECONNECTING -> "◦ reconnecting"
                    HermesClient.State.FAILED -> "✕ " + (detail ?: "failed")
                    HermesClient.State.IDLE -> "offline"
                }
            }

            override fun onReady(payload: JSONObject) = loadSessions()
            override fun onEvent(type: String, payload: JSONObject) = Unit
            override fun onLog(line: String) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        // Returning from a chat should show its new state, so reconnect and
        // re-read rather than trusting the list we drew on the way out.
        client.connect()
    }

    override fun onPause() {
        client.disconnect()
        super.onPause()
    }

    override fun onDestroy() {
        client.setListener(null)
        client.disconnect()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        header.addView(
            TextView(this).apply {
                text = "Hermes"
                setTextColor(0xFF7FD1A0.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )
        statusView = TextView(this).apply {
            text = "connecting"
            setTextColor(0xFF808080.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.END
        }
        header.addView(statusView, LinearLayout.LayoutParams(0, WRAP, 1f))
        // A visible gear rather than the long-press the spec sketched: with no
        // Back key and diagnostics living behind it, settings must stay
        // reachable when the connection itself is what is broken.
        header.addView(
            TextView(this).apply {
                text = "⚙"
                setTextColor(0xFF808080.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener {
                    startActivity(Intent(this@SessionsActivity, MainActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )
        // This is the root activity on a device with no Back key: without an
        // explicit quit there is no way out of the app at all.
        header.addView(
            TextView(this).apply {
                // Plain text, not a power glyph: U+23FB and friends are not in
                // the system font here and render as tofu.
                text = "Quit"
                setTextColor(0xFFEF5350.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(6), dp(2), dp(2), dp(2))
                setOnClickListener { quit() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        listColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        scroller = ScrollView(this).apply { addView(listColumn) }
        root.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        return root
    }

    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- data ----

    private fun loadSessions() {
        liveById.clear()
        storedById.clear()
        pendingCalls = 2

        // active_list is the gateway's in-memory table and takes no profile;
        // list is a database browser and must be scoped, or it shows the launch
        // profile's history instead of this client's.
        client.call("session.active_list") { result, error ->
            collect(result?.optJSONArray("sessions"), liveById, error)
        }
        client.call("session.list", JSONObject().put("profile", settings.profile)) { result, error ->
            collect(result?.optJSONArray("sessions"), storedById, error)
        }
    }

    private fun collect(
        array: JSONArray?,
        into: MutableMap<String, JSONObject>,
        error: String?,
    ) {
        if (error == null && array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isNotEmpty()) into[id] = item
            }
        }
        pendingCalls -= 1
        if (pendingCalls <= 0) render()
    }

    private fun render() {
        listColumn.removeAllViews()
        rows.clear()

        listColumn.addView(row("＋ New session", null, 0xFF7FD1A0.toInt()) {
            open(null, live = false)
        })

        val seen = HashSet<String>()

        liveById.values
            .sortedByDescending { it.optDouble("last_active", 0.0) }
            .forEach { s ->
                val id = s.optString("id")
                seen.add(id)
                listColumn.addView(row(titleOf(s), subtitleOf(s, live = true), Color.WHITE) {
                    open(id, live = true)
                })
            }

        storedById.values
            .filter { it.optString("id") !in seen }
            .sortedByDescending { it.optDouble("started_at", 0.0) }
            .forEach { s ->
                listColumn.addView(row(titleOf(s), subtitleOf(s, live = false), 0xFFCCCCCC.toInt()) {
                    open(s.optString("id"), live = false)
                })
            }

        if (liveById.isEmpty() && storedById.isEmpty()) {
            listColumn.addView(TextView(this).apply {
                text = "no sessions yet"
                setTextColor(0xFF808080.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(2), dp(8), dp(2), dp(2))
            })
        }

        selected = selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        applySelection(scroll = false)
    }

    private fun applySelection(scroll: Boolean) {
        rows.forEachIndexed { index, row ->
            (row.view.background as? GradientDrawable)?.apply {
                setColor(if (index == selected) Color.rgb(44, 60, 52) else Color.rgb(26, 26, 26))
                setStroke(
                    dp(1),
                    if (index == selected) 0xFF7FD1A0.toInt() else Color.TRANSPARENT
                )
            }
        }
        if (scroll) {
            rows.getOrNull(selected)?.view?.let { view ->
                scroller.post {
                    // Keep a row of context above the selection rather than
                    // pinning it to the very edge of the viewport.
                    scroller.smoothScrollTo(0, (view.top - dp(40)).coerceAtLeast(0))
                }
            }
        }
    }

    private fun moveSelection(delta: Int) {
        if (rows.isEmpty()) return
        selected = (selected + delta).coerceIn(0, rows.size - 1)
        applySelection(scroll = true)
    }

    /** Root activity: leaving means leaving the app, not popping a screen. */
    private fun quit() {
        client.disconnect()
        finishAndRemoveTask()
    }

    private fun titleOf(s: JSONObject): String {
        val title = s.optString("title").trim()
        if (title.isNotEmpty()) return title
        val preview = s.optString("preview").trim()
        return if (preview.isNotEmpty()) preview.take(60) else "untitled"
    }

    /** Status glyph first so state reads without relying on colour. */
    private fun subtitleOf(s: JSONObject, live: Boolean): String {
        val parts = mutableListOf<String>()
        if (live) {
            val status = s.optString("status").ifEmpty { "idle" }
            parts.add(glyphFor(status) + " " + status)
        }
        val count = s.optInt("message_count", -1)
        if (count >= 0) parts.add("$count msgs")

        val stamp = if (live) s.optDouble("last_active", 0.0) else s.optDouble("started_at", 0.0)
        relative(stamp)?.let { parts.add(it) }

        return parts.joinToString(" · ")
    }

    private fun glyphFor(status: String) = when (status) {
        "working" -> "●"
        "waiting" -> "?"
        "starting" -> "◦"
        else -> "·"
    }

    private fun relative(epochSeconds: Double): String? {
        if (epochSeconds <= 0) return null
        val deltaSec = (System.currentTimeMillis() / 1000.0) - epochSeconds
        if (deltaSec < 0) return null
        return when {
            deltaSec < 60 -> "just now"
            deltaSec < 3600 -> "${(deltaSec / 60).toInt()}m ago"
            deltaSec < 86_400 -> "${(deltaSec / 3600).toInt()}h ago"
            else -> "${(deltaSec / 86_400).toInt()}d ago"
        }
    }

    private fun row(
        title: String,
        subtitle: String?,
        titleColour: Int,
        onClick: () -> Unit,
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.rgb(26, 26, 26))
            }
            isClickable = true
            setOnClickListener {
                // A tap also moves the wheel cursor, so the two input methods
                // never disagree about where you are in the list.
                selected = rows.indexOfFirst { it.view === this }.coerceAtLeast(0)
                applySelection(scroll = false)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) }
        }
        rows.add(Row(cell, onClick))

        cell.addView(TextView(this).apply {
            text = title
            setTextColor(titleColour)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 2
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        if (!subtitle.isNullOrEmpty()) {
            cell.addView(TextView(this).apply {
                text = subtitle
                setTextColor(0xFF808080.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                setSingleLine(true)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        return cell
    }

    private fun open(sessionId: String?, live: Boolean) {
        val intent = Intent(this, ChatActivity::class.java)
        if (sessionId != null) {
            intent.putExtra(ChatActivity.EXTRA_SESSION_ID, sessionId)
            intent.putExtra(ChatActivity.EXTRA_LIVE, live)
        }
        startActivity(intent)
    }

    /** Wheel moves the selection; the side button opens what is selected. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> { moveSelection(-1); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { moveSelection(1); true }
        KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (event?.repeatCount == 0) rows.getOrNull(selected)?.activate?.invoke()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }
}
