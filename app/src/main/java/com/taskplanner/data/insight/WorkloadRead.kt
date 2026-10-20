package com.taskplanner.data.insight

/**
 * What the days ahead are asked for, over one named window, against the days that were measured.
 *
 * This is the figure the overload panel reads and the figure the heavy-day rule reads, from one object, so the
 * two cannot disagree. [WorkloadFold] answers what a window holds; a read adds the two things a screen cannot
 * derive for itself — the stretch of calendar it was counted over, and the pace the profile has actually been
 * measured on behind it. A day named "heavy" is heavy *against* something, and the something has to travel with
 * the number rather than be recomputed by whoever displays it.
 *
 * Every derived value here is a fold over [days] or [priorities], never a second read: a panel that asked the
 * database its own question would be one window measured twice, and the reader would have no way to notice the
 * two answers differing.
 */
data class WorkloadRead(
    /** The inclusive epoch-day bounds the read was counted over. */
    val fromDay: Long,
    val toDay: Long,

    /** The days that hold work, oldest first; a day with nothing on it has no row. */
    val days: List<DayLoad> = emptyList(),

    /** How the rows behind those days were rated by the reader. */
    val priorities: PriorityLoad = PriorityLoad(),

    /** How many of those rows state no length at all — the count §8's caveat is really about. */
    val lengthUnknownTasks: Int = 0,

    /** The average minutes per day over the days behind the window that carry measurements. */
    val baselineDailyMinutes: Int = 0,

    /** How many days that average was taken over, because two is not thirty. */
    val measuredDays: Int = 0
) {
    /** The minutes the window asks for, including the ones it cannot yet name. */
    val totalMinutes: Int get() = days.sumOf { it.minutes }

    /** How many separate pieces of work the window holds, counted once per row. */
    val totalTasks: Int get() = priorities.total

    /**
     * Whether any part of this window's minutes are guesses.
     *
     * One estimated deadline among stated blocks is enough, because the panel states one total: it would be
     * dishonest to call 165 minutes measured because 120 of them were typed out.
     */
    val approximate: Boolean get() = days.any { it.approximate }

    /** The most-asked day, or null when the window holds no work at all. */
    val heaviestDay: DayLoad? get() = days.maxByOrNull { it.minutes }

    companion object {
        /** A window nobody is signed in to: it still names itself, so a screen can show the right days empty. */
        fun empty(fromDay: Long, toDay: Long): WorkloadRead = WorkloadRead(fromDay = fromDay, toDay = toDay)
    }
}
