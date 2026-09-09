package com.r1.audioprobe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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

    /**
     * Whether this screen wants the whole panel rather than the top of it.
     *
     * The character is the standby display's frame and every other screen sits
     * over it, in the half it does not occupy. A screen with a picture in it
     * has no room to do that, so it asks for the panel and the host puts the
     * character away while it is showing.
     */
    val fullBleed: Boolean get() = false

    /**
     * Handed to the screen before it is shown, so that one whose own views
     * consume touches can still act on a tap.
     *
     * Anything with a scroller in it takes the gesture before the Activity
     * sees it, and the Activity's tap is the only way off the standby display
     * when there is no character to ask for a motion.
     */
    fun onTap(action: () -> Unit) {}
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
 * Takes horizontal drags for itself and lets everything else through.
 *
 * The summary scrolls and the articles step sideways, and both are the same
 * finger on the same panel — there is no other input left. The wheel steps
 * between screens and the side button leaves, which were spoken for before
 * this screen existed.
 *
 * Only a drag is claimed. A tap goes where it would have gone — to [onTap] as
 * well as to whichever child is under it — so the way off the display does
 * not quietly disappear on this screen.
 */
private class SwipeFrame(context: Context) : LinearLayout(context) {

    var onSwipe: ((Int) -> Unit)? = null
    var onTouched: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var claimed = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                claimed = false
                onTouched?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // Sideways has to beat downwards as well as beat the slop.
                // A diagonal on a scroller is somebody scrolling.
                if (abs(dx) > slop && abs(dx) > abs(dy)) claimed = true
            }
        }
        return claimed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dx) > slop * 4) {
                onSwipe?.invoke(if (dx < 0) 1 else -1)
            } else if (abs(dx) < slop && abs(dy) < slop) {
                // Not a drag: this event only reaches onTouchEvent at all when
                // it landed outside every child that would otherwise have
                // claimed it (the picture, not the scroller), and dropping it
                // silently there would make that area of the panel dead —
                // including, when the character is off, the only way out.
                onTap?.invoke()
            }
        }
        return true
    }
}

/**
 * Headlines, one at a time, with the article's picture and as much of its
 * summary as it has.
 *
 * This was a list first, four titles cut to two lines each, and it did not
 * work: at the size that fits four Japanese headlines on a 240 dp panel none of
 * them can be read from across a desk, and every one is truncated mid-sentence.
 * A list of four things you cannot read is worse than one thing you can.
 *
 * Then it was one headline and nothing else, which was readable and still went
 * unread — a line of text on a black panel is not something the eye stops at.
 * So the picture, which is why this screen asks for the whole panel and why
 * the character stands down while it is showing.
 *
 * The summary scrolls, and that changes what this screen is. It was built to
 * be glanced at, which is what the fifteen seconds are for; a screen you can
 * scroll is one somebody is reading. Both are true now, so the rotation stops
 * for a minute whenever the panel is touched and resumes when it is left
 * alone.
 */
private class HeadlinesScreen : SignageScreen {
    override val id = "headlines"
    override val title = "新着"

    override val fullBleed = true

    private lateinit var context: Context
    private lateinit var frame: SwipeFrame
    private lateinit var picture: ImageView
    private lateinit var meta: TextView
    private lateinit var scroller: ScrollView
    private lateinit var heading: TextView
    private lateinit var body: TextView

    private var tapAction: (() -> Unit)? = null

    private var index = 0
    private var advancedAt = 0L
    private var touchedAt = 0L
    private var showing: String? = null

    // paintedBitmap alone cannot tell "never painted" apart from "painted
    // null" — both look like the absence of a bitmap — so picturePainted
    // carries that distinction instead of overloading the reference.
    private var paintedBitmap: android.graphics.Bitmap? = null
    private var picturePainted = false

    private companion object {
        /**
         * Fifteen seconds. Six was tried and was too quick, and the reason is
         * that this is a glanced-at screen rather than a read one: the clock
         * that matters is not how long a headline takes to read, it is how
         * long after looking up you start. A headline that changes while you
         * are halfway through it is worse than one you have already finished.
         *
         * Fifty items makes the loop twelve and a half minutes, which is long
         * enough that the tail of the list is never reached by waiting. That
         * is the intended arrangement rather than a cost of it: the rotation
         * is what an unattended panel does, and a swipe is how somebody who
         * wants a particular story gets to it. Shortening this to make the
         * loop fit would trade the one number here that was arrived at by
         * trying it against a number nobody reads.
         */
        const val DWELL_MS = 15_000L

        /**
         * How long a touch stops the rotation for.
         *
         * Long enough to finish reading something that scrolls, short enough
         * that a screen brushed in passing is back to normal before anybody
         * comes back to it.
         */
        const val HOLD_MS = 60_000L

        /** Slow enough not to snap, short enough not to be a thing happening. */
        const val FADE_MS = 400L

        /** How much of the panel the picture takes. */
        const val PICTURE_WEIGHT = 0.45f
    }

    override fun onTap(action: () -> Unit) {
        tapAction = action
    }

    override fun createView(context: Context): View {
        this.context = context.applicationContext
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        frame = SwipeFrame(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            onSwipe = { by -> stepBy(by) }
            onTouched = { touchedAt = System.currentTimeMillis() }
            onTap = { tapAction?.invoke() }
        }

        picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Blank until there is something. An empty box is not a failure
            // worth drawing a frame around.
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(
            picture,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, PICTURE_WEIGHT),
        )

        meta = SignageStyle.text(context, 12f, SignageStyle.ORANGE, bold = true).apply {
            // One line, and the position comes first so it is the part that
            // survives. Feed titles run to "ITmedia PC・AV・スマートフォン 最新
            // 記事一覧", which wrapped onto a second line and pushed the
            // counter down with it.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(pad, pad, pad, 0)
        }
        frame.addView(meta, SignageStyle.wide())

        heading = SignageStyle.text(context, 17f, SignageStyle.INK, bold = true).apply {
            setLineSpacing(6f, 1f)
            setPadding(pad, (6 * density).toInt(), pad, 0)
        }
        body = SignageStyle.text(context, 13f, SignageStyle.DIM).apply {
            setLineSpacing(6f, 1f)
            setPadding(pad, (10 * density).toInt(), pad, pad)
        }

        // Nothing is cut. There is no line count at which a summary stops
        // being worth reading, and the panel scrolls now.
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading, SignageStyle.wide())
            addView(body, SignageStyle.wide())
        }
        val scrollSlop = ViewConfiguration.get(context).scaledTouchSlop
        var scrollDownX = 0f
        var scrollDownY = 0f
        scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(column)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchedAt = System.currentTimeMillis()
                        scrollDownX = event.x
                        scrollDownY = event.y
                    }
                    MotionEvent.ACTION_UP -> {
                        // A scroll ends in an ACTION_UP the same as a tap
                        // does; whether the finger moved is the only thing
                        // that tells them apart. Calling performClick() here
                        // unconditionally fired the exit tap on every scroll
                        // that happened to end with the finger lifted over
                        // this view — which is the normal way to finish
                        // reading a summary that scrolled off the bottom.
                        if (abs(event.x - scrollDownX) < scrollSlop &&
                            abs(event.y - scrollDownY) < scrollSlop
                        ) {
                            view.performClick()
                        }
                    }
                }
                false
            }
            // Restores the way off the display, which the scroller would
            // otherwise swallow along with the rest of the gesture.
            setOnClickListener { tapAction?.invoke() }
        }
        frame.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f - PICTURE_WEIGHT),
        )

        // The view is rebuilt every time the wheel lands here, while this
        // object and its position survive. Forgetting what is on screen makes
        // the first frame after arriving get painted rather than faded into.
        // picturePainted resets with it: the ImageView above is a new
        // instance that has never actually had the remembered bitmap set on
        // it, whatever this object last painted onto the previous one.
        showing = null
        picturePainted = false
        paintedBitmap = null
        return frame
    }

    /** A swipe. Moves now and puts the timer back to the start. */
    private fun stepBy(by: Int) {
        val size = Headlines.items.size
        if (size == 0) return
        index = (index + by + size) % size
        advancedAt = System.currentTimeMillis()
        touchedAt = advancedAt
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
            picture.visibility = View.GONE
            // Bypasses paintPicture, so its identity cache has to be told
            // separately: otherwise the list coming back with the same
            // cached picture as before this gap would make paintPicture see
            // its own remembered bitmap and skip, leaving the picture GONE
            // under a headline that has one.
            picturePainted = false
            meta.text = ""
            heading.text = when (Headlines.reachable) {
                null -> "読み込み中…"
                false -> "取得できません"
                true -> "記事がありません"
            }
            body.visibility = View.GONE
            // Otherwise the list coming back with the same first article as
            // before this gap leaves this message on screen forever: the
            // early return below fires on a title equal to what was already
            // showing, and it was never cleared to notice the gap happened.
            showing = null
            return
        }

        val now = System.currentTimeMillis()
        val held = now - touchedAt < HOLD_MS
        if (advancedAt == 0L) {
            advancedAt = now
        } else if (!held && now - advancedAt >= DWELL_MS) {
            advancedAt = now
            index += 1
        }
        // Modulo at use rather than at increment: the list is replaced under
        // this screen every couple of minutes and can come back shorter.
        val position = index % items.size
        val item = items[position]

        // Asked for on every tick rather than once on arrival, because the
        // fetch finishes on another thread and there is no callback to wait
        // for. Both calls are a map lookup when there is nothing to do.
        Thumbnails.prefetch(context, item.imageUrl)
        Thumbnails.prefetch(context, items[(position + 1) % items.size].imageUrl)
        val bitmap = Thumbnails.get(item.imageUrl)

        if (item.title == showing) {
            // The picture can land seconds after the text it belongs to, so
            // this one thing is kept up to date without a repaint.
            paintPicture(bitmap)
            return
        }
        val first = showing == null
        showing = item.title

        val paint = {
            // The counter and the feed name. A headline whose source is
            // nowhere is worse than a crowded line.
            meta.text = buildString {
                append(position + 1).append(" / ").append(items.size)
                append("   ").append(item.source.ifEmpty { "新着" })
            }
            heading.text = item.title
            body.visibility = if (item.summary.isEmpty()) View.GONE else View.VISIBLE
            body.text = item.summary
            paintPicture(bitmap)
            // A new article starts at its own beginning.
            scroller.scrollTo(0, 0)
        }

        if (first) {
            paint()
            frame.alpha = 1f
        } else {
            frame.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                paint()
                frame.animate().alpha(1f).setDuration(FADE_MS).start()
            }.start()
        }
    }

    /**
     * The picture, or the room it would have taken.
     *
     * Gone rather than blank: a third of what this list carries has no picture
     * at all — Hatena and Publickey have none between them — and a black
     * rectangle above every one of those reads as a screen that is failing to
     * load something.
     *
     * Called every tick regardless of whether the article changed, since the
     * fetch can land seconds after the text does. Most of those calls are the
     * same bitmap reference as last time, and invalidating the ImageView for
     * one of those every second is work with nothing to show for it, so a
     * repeat is skipped by identity rather than repainted.
     */
    private fun paintPicture(bitmap: android.graphics.Bitmap?) {
        if (picturePainted && bitmap === paintedBitmap) return
        picturePainted = true
        paintedBitmap = bitmap
        if (bitmap == null) {
            picture.setImageDrawable(null)
            picture.visibility = View.GONE
        } else {
            picture.setImageBitmap(bitmap)
            picture.visibility = View.VISIBLE
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
        val cards = HermesStatus.board
        body.text = when {
            cards == null -> "—"
            cards.total == 0 -> "カードはありません"
            else -> buildString {
                for ((name, count) in cards.columns) {
                    append(name).append("  ").append(count).append('\n')
                }
                // What is actually moving matters more than the counts.
                cards.running.firstOrNull()?.let {
                    append('\n').append("▶ ").append(it.take(40))
                }
            }
        }
    }
}

/** Formats a Calendar as the local date the server keys days by. */
internal fun todayLocalDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
