package com.r1.audioprobe

import android.content.Context
import android.util.Log
import com.r1.hermes.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * What the agent has been doing, read from the Hermes dashboard's REST API.
 *
 * Two things, one fetch each, because they answer different questions and are
 * separate endpoints:
 *
 *  - `/api/sessions` — every session with its token counts and which provider
 *    billed them. This is the closest thing to a usage figure that exists:
 *    there is no `/api/usage`, and cost comes back as zero because the
 *    provider is a subscription. Tokens and session counts are what is real.
 *  - `/api/plugins/kanban/board` — the task board, columns and cards.
 *
 * Credentials come from the chat client's own settings, which the merged app
 * already carries. Nothing new to configure.
 */
object HermesStatus {

    private const val TAG = "R1AudioProbe"
    private const val TIMEOUT_MS = 12_000

    data class Provider(
        val name: String,
        val sessions: Int,
        val inputTokens: Long,
        val outputTokens: Long,
    )

    data class Usage(
        /** Busiest provider first, so the screen shows the interesting row. */
        val providers: List<Provider>,
        val toolCalls: Int,
    )

    data class Board(
        /** Column name to card count, in board order, empty columns dropped. */
        val columns: List<Pair<String, Int>>,
        val total: Int,
        /** Titles of whatever is in the running column. */
        val running: List<String>,
    )

    @Volatile var usage: Usage? = null; private set
    @Volatile var board: Board? = null; private set

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hermes-status").apply { isDaemon = true }
    }

    @Volatile private var inFlight = false

    fun refresh(context: Context) {
        if (inFlight) return
        val settings = Settings(context)
        if (!settings.isConfigured) return

        inFlight = true
        worker.execute {
            try {
                get(settings, "/api/sessions")?.let { usage = parseUsage(it) }
                get(settings, "/api/plugins/kanban/board")?.let { board = parseBoard(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "hermes status fetch failed", t)
            } finally {
                inFlight = false
            }
        }
    }

    private fun get(settings: Settings, path: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(settings.baseUrl.trimEnd('/') + path).openConnection()
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                if (settings.sessionToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer " + settings.sessionToken)
                }
                if (settings.accessClientId.isNotEmpty()) {
                    setRequestProperty("CF-Access-Client-Id", settings.accessClientId)
                    setRequestProperty("CF-Access-Client-Secret", settings.accessClientSecret)
                }
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    /** Sessions that began today, grouped by whoever billed them. */
    private fun parseUsage(body: String): Usage {
        val sessions = JSONObject(body).optJSONArray("sessions") ?: JSONArray()

        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000.0

        var toolCalls = 0
        val byProvider = HashMap<String, Provider>()

        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            if (s.optDouble("started_at", 0.0) < midnight) continue

            toolCalls += s.optInt("tool_call_count")
            val name = s.optString("billing_provider").ifEmpty { "unknown" }
            val previous = byProvider[name]
            byProvider[name] = Provider(
                name = name,
                sessions = (previous?.sessions ?: 0) + 1,
                inputTokens = (previous?.inputTokens ?: 0) + s.optLong("input_tokens"),
                outputTokens = (previous?.outputTokens ?: 0) + s.optLong("output_tokens"),
            )
        }

        return Usage(
            providers = byProvider.values.sortedByDescending { it.inputTokens + it.outputTokens },
            toolCalls = toolCalls,
        )
    }

    private fun parseBoard(body: String): Board {
        val columns = JSONObject(body).optJSONArray("columns") ?: JSONArray()
        val counts = ArrayList<Pair<String, Int>>()
        val running = ArrayList<String>()
        var total = 0

        for (i in 0 until columns.length()) {
            val column = columns.optJSONObject(i) ?: continue
            val name = column.optString("name")
            val tasks = column.optJSONArray("tasks") ?: JSONArray()
            if (tasks.length() > 0) counts.add(name to tasks.length())
            total += tasks.length()

            if (name == "running") {
                for (j in 0 until tasks.length()) {
                    tasks.optJSONObject(j)?.optString("title")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { running.add(it) }
                }
            }
        }
        return Board(counts, total, running)
    }
}
