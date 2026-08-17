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
            HeadlinesScreen(),
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
 * Headlines, one at a time.
 *
 * This was a list first, four titles cut to two lines each, and it did not
 * work: at the size that fits four Japanese headlines on a 240 dp panel none of
 * them can be read from across a desk, and every one is truncated mid-sentence.
 * A list of four things you cannot read is worse than one thing you can.
 *
 * So one headline, large, held for six seconds and then faded to the next. The
 * standby display deliberately never rotates between *screens* on its own —
 * a display you have to wait for is one that is in your way when you want a
 * particular thing — but rotating within a screen is the opposite case: there
 * is nothing to wait for here, because every item is the same kind of thing and
 * you did not come to this screen for a specific one.
 */
private class HeadlinesScreen : SignageScreen {
    override val id = "headlines"
    override val title = "新着"

    private lateinit var meta: TextView
    private lateinit var body: TextView

    private var index = 0
    private var advancedAt = 0L
    private var showing: String? = null

    private companion object {
        /**
         * Fifteen seconds. Six was tried and was too quick, and the reason is
         * that this is a glanced-at screen rather than a read one: the clock
         * that matters is not how long a headline takes to read, it is how
         * long after looking up you start. A headline that changes while you
         * are halfway through it is worse than one you have already finished.
         *
         * Ten items makes the loop two and a half minutes, which is about how
         * often the list is refetched anyway.
         */
        const val DWELL_MS = 15_000L

        /** Slow enough not to snap, short enough not to be a thing happening. */
        const val FADE_MS = 400L
    }

    override fun createView(context: Context): View {
        val column = SignageStyle.column(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        meta = SignageStyle.text(context, 12f, SignageStyle.ORANGE, bold = true).apply {
            // One line, and the position comes first so it is the part that
            // survives. Feed titles run to "ITmedia PC・AV・スマートフォン 最新
            // 記事一覧", which wrapped onto a second line and pushed the
            // counter down with it.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        body = SignageStyle.text(context, 17f).apply {
            setLineSpacing(7f, 1f)
            setPadding(0, (8 * context.resources.displayMetrics.density).toInt(), 0, 0)
            // One headline has room to be shown whole. Nothing is truncated at
            // this size until a title runs past eight lines, which is a press
            // release rather than a headline.
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(meta, SignageStyle.wide())
        column.addView(body, SignageStyle.wide())

        // The view is rebuilt every time the wheel lands here, while this
        // object and its position survive. Forgetting what is on screen makes
        // the first frame after arriving get painted rather than faded into.
        showing = null
        return column
    }

    /**
     * [data] is the lifelog snapshot and has nothing to do with feeds; the
     * headlines arrive on their own schedule from [Headlines]. The parameter is
     * the interface's, and ignoring it is the honest thing to do rather than
     * inventing a use.
     */
    override fun refresh(data: LifelogSummary.Snapshot?) {
        val items = Headlines.items
        if (items.isEmpty()) {
            // Never "nothing new". The endpoint returns the latest ten whether
            // they arrived a minute ago or on Tuesday, so an empty list here
            // means the crawler has not run or cannot be reached — and those
            // two are worth telling apart.
            meta.text = ""
            body.text = when (Headlines.reachable) {
                null -> "読み込み中…"
                false -> "取得できません"
                true -> "記事がありません"
            }
            return
        }

        val now = System.currentTimeMillis()
        if (advancedAt == 0L) {
            advancedAt = now
        } else if (now - advancedAt >= DWELL_MS) {
            advancedAt = now
            index += 1
        }
        // Modulo at use rather than at increment: the list is replaced under
        // this screen every couple of minutes and can come back shorter.
        val item = items[index % items.size]

        if (item.title == showing) return
        val first = showing == null
        showing = item.title

        val paint = {
            meta.text = buildString {
                append(index % items.size + 1).append(" / ").append(items.size)
                append("   ")
                append(item.source.ifEmpty { "新着" })
            }
            body.text = item.title
        }

        if (first) {
            paint()
            body.alpha = 1f
        } else {
            body.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                paint()
                body.animate().alpha(1f).setDuration(FADE_MS).start()
            }.start()
        }
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

                append('\n').append("Claude Code")
                agents.claudePlan?.let { append("  (").append(it).append(')') }
                append('\n')

                // Anthropic's own utilisation, the same kind of figure as the
                // Codex one above rather than a proportion of a guessed quota.
                if (agents.claudeFiveHourPercent != null) {
                    append("  5h  ").append(percent(agents.claudeFiveHourPercent))
                    agents.claudeSevenDayPercent?.let {
                        append("   7d  ").append(percent(it))
                    }
                } else {
                    // Token counts survive an expired token; the percentage
                    // does not, and a stale one would be worse than none.
                    append("  out ").append(tokens(agents.claudeOutputTokens))
                    append(" / ").append(agents.claudeMessages).append("msg")
                }

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
