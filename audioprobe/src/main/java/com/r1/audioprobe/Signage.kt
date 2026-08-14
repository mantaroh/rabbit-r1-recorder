package com.r1.audioprobe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One screen of the standby display.
 *
 * Implementations own their layout and are asked to refresh on a timer; they
 * are not told how often, and must not assume they are visible when it
 * happens. Adding a screen means writing one of these and putting it in
 * [Signage.ALL] — deliberately the only two steps, because the interesting
 * ones are still to come and none of them should need the host to change.
 */
interface SignageScreen {
    /** Stable across releases: it is what the setting stores. */
    val id: String

    /** Shown in the picker. */
    val title: String

    fun createView(context: Context): View

    /**
     * Called on the main thread, roughly every few seconds while showing.
     * [data] is the shared lifelog snapshot, null until the first fetch lands
     * or if the device is offline — a screen that needs it should say so
     * rather than show a zero.
     */
    fun refresh(data: LifelogSummary.Snapshot?)
}

/** Shared palette, so the screens look like one device rather than four. */
object SignageStyle {
    const val ORANGE = 0xFFFE5000.toInt()
    const val INK = 0xFFF2F2F2.toInt()
    const val DIM = 0xFF7A7A7A.toInt()

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    fun text(
        context: Context,
        size: Float,
        colour: Int = INK,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    fun wide(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

object Signage {

    /** Every screen the device knows how to show, in picker order. */
    val ALL: List<SignageScreen> by lazy {
        listOf(
            ClockScreen(),
            TodayScreen(),
            RecentSpeechScreen(),
            AgentUsageScreen(),
            TasksScreen(),
        )
    }

    fun byId(id: String): SignageScreen? = ALL.firstOrNull { it.id == id }
}

/** Time, date, and whether the thing is currently recording. */
private class ClockScreen : SignageScreen {
    override val id = "clock"
    override val title = "時計"

    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var state: TextView

    private val timeFormat = SimpleDateFormat("H:mm", Locale.US)
    private val dateFormat = SimpleDateFormat("M月d日 (E)", Locale.JAPAN)

    override fun createView(context: Context): View {
        val column = SignageStyle.column(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        time = SignageStyle.text(context, 64f, SignageStyle.INK, bold = true)
        date = SignageStyle.text(context, 15f, SignageStyle.DIM)
        state = SignageStyle.text(context, 13f, SignageStyle.ORANGE, bold = true)
        column.addView(time, SignageStyle.wide())
        column.addView(date, SignageStyle.wide())
        column.addView(state, SignageStyle.wide())
        return column
    }

    override fun refresh(data: LifelogSummary.Snapshot?) {
        val now = Date()
        time.text = timeFormat.format(now)
        date.text = dateFormat.format(now)
        state.text = if (RecorderService.recordingAudio) "● REC" else "○ 停止中"
    }
}

/** What the archive gained today, read back from the server. */
private class TodayScreen : SignageScreen {
    override val id = "today"
    override val title = "今日の記録"

    private lateinit var heading: TextView
    private lateinit var body: TextView

    override fun createView(context: Context): View {
        val column = SignageStyle.column(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        heading = SignageStyle.text(context, 15f, SignageStyle.ORANGE, bold = true).apply {
            text = "今日の記録"
        }
        body = SignageStyle.text(context, 17f).apply {
            setLineSpacing(8f, 1f)
        }
        column.addView(heading, SignageStyle.wide())
        column.addView(body, SignageStyle.wide())
        return column
    }

    override fun refresh(data: LifelogSummary.Snapshot?) {
        body.text = if (data == null) {
            "—"
        } else {
            val hours = data.segments / 60
            val minutes = data.segments % 60
            "録音   ${hours}時間${minutes}分\n発話   ${data.withText}分\n写真   ${data.photos}枚"
        }
    }
}

/** The most recent thing anybody said, so it is obvious what is being heard. */
private class RecentSpeechScreen : SignageScreen {
    override val id = "recent"
    override val title = "直近の発話"

    private lateinit var stamp: TextView
    private lateinit var body: TextView

    override fun createView(context: Context): View {
        val column = SignageStyle.column(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        stamp = SignageStyle.text(context, 13f, SignageStyle.ORANGE, bold = true)
        body = SignageStyle.text(context, 15f).apply {
            setLineSpacing(6f, 1f)
            maxLines = 8
        }
        column.addView(stamp, SignageStyle.wide())
        column.addView(body, SignageStyle.wide())
        return column
    }

    override fun refresh(data: LifelogSummary.Snapshot?) {
        stamp.text = data?.latestAt ?: "直近の発話"
        body.text = data?.latestText?.takeIf { it.isNotBlank() } ?: "—"
    }
}

/**
 * How hard the agent has been working today, per billing provider.
 *
 * Tokens rather than money: the provider here is a subscription, so every
 * session reports a cost of zero and a spend figure would read as "free" no
 * matter how much ran. Token volume is the thing that actually moved.
 */
private class AgentUsageScreen : SignageScreen {
    override val id = "usage"
    override val title = "エージェント稼働"

    private lateinit var heading: TextView
    private lateinit var body: TextView

    override fun createView(context: Context): View {
        val column = SignageStyle.column(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        heading = SignageStyle.text(context, 15f, SignageStyle.ORANGE, bold = true).apply {
            text = "今日のエージェント"
        }
        body = SignageStyle.text(context, 15f).apply { setLineSpacing(7f, 1f) }
        column.addView(heading, SignageStyle.wide())
        column.addView(body, SignageStyle.wide())
        return column
    }

    override fun refresh(data: LifelogSummary.Snapshot?) {
        val agents = LifelogSummary.agents
        body.text = when {
            agents == null -> "—"
            else -> buildString {
                append("Codex\n")
                if (agents.codexPercent != null) {
                    append("  ").append(percent(agents.codexPercent))
                    agents.codexPlan?.let { append("  (").append(it).append(')') }
                    append('\n')
                    agents.codexResetsAt?.let {
                        append("  reset ").append(untilReset(it)).append('\n')
                    }
                } else {
                    append("  —\n")
                }

                append('\n').append("Claude Code  5h")
                agents.claudePlan?.let { append("  (").append(it).append(')') }
                append('\n')
                // No percentage. The plan is known but its limits are
                // published as approximate message counts that vary with model
                // and context, so a proportion built on them would carry tens
                // of percent of error and look authoritative anyway.
                append("  out ").append(tokens(agents.claudeOutputTokens))
                append(" / ").append(agents.claudeMessages).append("msg")

                // Numbers this old are worth doubting, so say how old.
                agents.ageSeconds?.takeIf { it > 900 }?.let {
                    append('\n').append("  ").append(it / 60).append("分前の値")
                }
            }
        }
    }

    private fun percent(value: Double): String =
        if (value >= 10) "${value.toInt()}%" else String.format("%.1f%%", value)

    /** Coarse on purpose: the exact minute of a weekly reset is not news. */
    private fun untilReset(epochSeconds: Long): String {
        val hours = (epochSeconds - System.currentTimeMillis() / 1000) / 3600
        return when {
            hours < 0 -> "まもなく"
            hours < 24 -> "${hours}時間後"
            else -> "${hours / 24}日後"
        }
    }

    private fun tokens(count: Long): String =
        if (count >= 1000) "${count / 1000}k" else count.toString()
}

/** The Kanban board Hermes keeps, summarised to fit. */
private class TasksScreen : SignageScreen {
    override val id = "tasks"
    override val title = "タスク"

    private lateinit var heading: TextView
    private lateinit var body: TextView

    override fun createView(context: Context): View {
        val column = SignageStyle.column(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        heading = SignageStyle.text(context, 15f, SignageStyle.ORANGE, bold = true).apply {
            text = "タスク"
        }
        body = SignageStyle.text(context, 15f).apply {
            setLineSpacing(7f, 1f)
            maxLines = 9
        }
        column.addView(heading, SignageStyle.wide())
        column.addView(body, SignageStyle.wide())
        return column
    }

    override fun refresh(data: LifelogSummary.Snapshot?) {
        val board = HermesStatus.board
        body.text = when {
            board == null -> "—"
            board.total == 0 -> "カードはありません"
            else -> buildString {
                for ((name, count) in board.columns) {
                    append(name).append("  ").append(count).append('\n')
                }
                // What is actually moving matters more than the counts.
                board.running.firstOrNull()?.let {
                    append('\n').append("▶ ").append(it.take(40))
                }
            }
        }
    }
}

/** Formats a Calendar as the local date the server keys days by. */
internal fun todayLocalDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
