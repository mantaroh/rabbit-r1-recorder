package com.r1.audioprobe

import java.util.Calendar

/**
 * The day's shape: recording starts itself in the morning and asks to stop at
 * night.
 *
 * Asking rather than stopping is the whole point of the evening half. An
 * archive that stops because nobody was in the room to say otherwise has lost
 * a night it cannot get back, so silence is never taken as consent — the
 * prompt returns every [SNOOZE_MINUTES] until somebody answers it, and if that
 * is never, the recording simply continues. The cost of guessing wrong in that
 * direction is a night of mostly-silent audio that the VAD gate keeps from
 * being transcribed. The cost in the other direction is the night itself.
 *
 * All decisions are pure functions of the clock and the last-fired times, so
 * the service can call [decide] once a minute and act on what comes back.
 */
object Scheduler {

    /** Ask whether to stop, at this hour. */
    const val EVENING_HOUR = 23
    const val EVENING_MINUTE = 0

    /** Start recording again, at this hour. */
    const val MORNING_HOUR = 7
    const val MORNING_MINUTE = 0

    /**
     * How long an unanswered prompt waits before it beeps again.
     *
     * Ten minutes is short enough to catch someone who wandered off mid-answer
     * and long enough not to become the thing that gets muted.
     */
    const val SNOOZE_MINUTES = 10

    enum class Action {
        /** Nothing due. */
        NONE,

        /** Show the stop prompt — first time, or a snooze. */
        ASK_TO_STOP,

        /** Begin the day's recording without asking. */
        START,
    }

    data class State(
        /** Epoch millis the evening prompt was last shown, 0 if never. */
        val promptedAt: Long = 0,
        /** Epoch millis the user last answered a prompt, 0 if never. */
        val answeredAt: Long = 0,
        /** Epoch millis recording was last auto-started, 0 if never. */
        val startedAt: Long = 0,
    )

    /**
     * [recording] is whether audio is currently being written, which decides
     * whether either half of the day has anything to do: there is no point
     * asking to stop something already stopped, or starting something already
     * running.
     */
    fun decide(nowMs: Long, recording: Boolean, state: State): Action {
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }

        if (!recording && isSameDayAtOrAfter(now, MORNING_HOUR, MORNING_MINUTE) &&
            !firedToday(state.startedAt, nowMs) &&
            // The morning start belongs to the morning. Turning recording off
            // at noon should not have it come back an hour later.
            now.get(Calendar.HOUR_OF_DAY) < EVENING_HOUR
        ) {
            return Action.START
        }

        if (!recording) return Action.NONE
        if (!isSameDayAtOrAfter(now, EVENING_HOUR, EVENING_MINUTE)) return Action.NONE

        // Answered already this evening: the user said "keep going", so leave
        // them alone until tomorrow.
        if (firedToday(state.answeredAt, nowMs)) return Action.NONE

        if (state.promptedAt == 0L || !firedToday(state.promptedAt, nowMs)) {
            return Action.ASK_TO_STOP
        }

        val sinceMs = nowMs - state.promptedAt
        return if (sinceMs >= SNOOZE_MINUTES * 60_000L) Action.ASK_TO_STOP else Action.NONE
    }

    /**
     * Whether an evening "stop for today" is still in force.
     *
     * Measured against the last morning start, not against the calendar day.
     * A decision made at 23:00 expires at midnight if you count days, which is
     * one hour later — so a reboot at 03:00 would resume recording that
     * somebody deliberately ended, silently, in an empty house. It should hold
     * until the morning it was meant to end at.
     */
    fun stopStillStands(answeredAt: Long, nowMs: Long): Boolean {
        if (answeredAt == 0L) return false
        return answeredAt >= lastMorningBoundary(nowMs)
    }

    /** The most recent [MORNING_HOUR]:[MORNING_MINUTE] at or before [nowMs]. */
    private fun lastMorningBoundary(nowMs: Long): Long {
        val boundary = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            set(Calendar.MINUTE, MORNING_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Before this morning's start, the boundary that matters is yesterday's.
        if (boundary.timeInMillis > nowMs) boundary.add(Calendar.DAY_OF_YEAR, -1)
        return boundary.timeInMillis
    }

    /** True when [nowMs] is at or past the given wall-clock time today. */
    private fun isSameDayAtOrAfter(now: Calendar, hour: Int, minute: Int): Boolean {
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        return h > hour || (h == hour && m >= minute)
    }

    /**
     * Whether a stored timestamp falls on the same calendar day as now.
     *
     * Day boundaries rather than elapsed hours, because both events are tied
     * to a wall clock: "already started today" must stay true at 23:00 even
     * though sixteen hours have passed.
     */
    fun firedToday(stampMs: Long, nowMs: Long): Boolean {
        if (stampMs == 0L) return false
        val a = Calendar.getInstance().apply { timeInMillis = stampMs }
        val b = Calendar.getInstance().apply { timeInMillis = nowMs }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
