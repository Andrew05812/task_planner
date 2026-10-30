package com.taskplanner.data.calendar

import com.taskplanner.ui.util.LocalisedNames
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the calendar draws, computed from the rows one window returned.
 *
 * Pure maths on plain values: no flow, no database, no clock. The screen's date grid goes in, cells
 * come out, so the whole set of rules that make a calendar lie — which day a task belongs to, how
 * long a block may be drawn, what counts as an overlap, when a day is full — is testable against a
 * fixed zone and a fixed instant instead of against whatever the test machine's clock says.
 *
 * Three rules hold the design together.
 *
 * **A task is filed under its own start, never under a length we invented.** Its anchor is
 * `start_time`, or the deadline when the user gave only a deadline, and a block has a length only
 * when the user gave an end or an estimate. Everything else is drawn as a marker in the single
 * minute it points at.
 *
 * **A day that crosses midnight shows on both days.** A block starting on Monday evening and ending
 * after midnight occupies part of Tuesday as well, clamped to each day's edges, because a calendar
 * that hid half of an appointment would be worse than one that repeats it.
 *
 * **A deadline stored at midnight means "that day, no time given".** That is how this app stores a
 * date without a time, so the day view files such tasks in a separate section rather than drawing
 * them as a 00:00 appointment.
 */
object CalendarAggregator {

    const val MINUTES_PER_DAY = 24 * 60
    private const val MINUTE_MILLIS = 60_000L

    /**
     * When a day is announced as full. These are display thresholds chosen so a normal working day
     * stays calm: they describe a colour on a cell, not a judgement about the user, and no data is
     * invented to reach them.
     */
    const val BUSY_MINUTES_OVERLOAD = 6 * 60
    const val TASK_COUNT_OVERLOAD = 8

    data class CategoryChip(
        val categoryId: Long,
        val name: String,
        val color: Int,
        val count: Int
    )

    data class DayCell(
        val date: LocalDate,
        val inDisplayedMonth: Boolean,
        val isToday: Boolean,
        val tasks: List<Task>,
        val categories: List<CategoryChip>,
        val unassignedCount: Int,
        val overdueCount: Int,
        val busyMinutes: Int,
        val overlapCount: Int,
        val overloaded: Boolean
    )

    /**
     * One task on the day column. [endMinute] is null exactly when the user never said how long the
     * task takes; such a block occupies no time and adds nothing to the day's load.
     */
    data class DayBlock(
        val task: Task,
        val startMinute: Int,
        val endMinute: Int?,
        val overlappingTaskIds: List<Long>
    ) {
        val lengthMinutes: Int get() = (endMinute ?: startMinute + 1) - startMinute
    }

    data class DayPlan(
        val date: LocalDate,
        val blocks: List<DayBlock>,
        val noTimeGiven: List<Task>,
        val busyMinutes: Int,
        val overdueCount: Int,
        val overlapCount: Int,
        val overloaded: Boolean
    ) {
        companion object {
            /** An empty day is an answer, not a missing one: the screen draws a column with nothing in it. */
            fun empty(date: LocalDate) = DayPlan(date, emptyList(), emptyList(), 0, 0, 0, false)
        }
    }

    /** The instant the calendar files a task by: when it starts, or when it is due, or when it ends. */
    fun anchorOf(task: Task): Long? = task.startTime ?: task.dueDate ?: task.endTime

    /** The instant a drawn block ends at, or null when the user never gave the task a length. */
    private fun knownEndOf(task: Task): Long? {
        val start = task.startTime ?: task.dueDate ?: return null
        task.endTime?.let { end ->
            // An end that comes before the task begins is nonsense, and nonsense must not take the
            // task off the calendar: it is read as "no length given", the same as an empty field.
            return end.takeIf { it >= start }
        }
        val estimated = task.estimatedMinutes ?: return null
        // No length is a missing value; a zero or negative one is not a length either, and rounding
        // it up to something drawable would be inventing it.
        if (estimated <= 0) return null
        return start + estimated * MINUTE_MILLIS
    }

    /**
     * The local days a task occupies: its start day through its end day, so a block over midnight is
     * visible on both, and a plain deadline appears on exactly one.
     */
    fun daysOf(task: Task, gridStart: LocalDate, gridEnd: LocalDate, zone: ZoneId): List<LocalDate> {
        val anchor = anchorOf(task) ?: return emptyList()
        val first = maxOf(CalendarMath.toLocalDate(anchor, zone), gridStart)
        val last = minOf(
            CalendarMath.toLocalDate(knownEndOf(task) ?: anchor, zone),
            gridEnd
        )
        if (last < first) return emptyList()
        return (0..ChronoUnit.DAYS.between(first, last)).map { first.plusDays(it) }
    }

    /**
     * The task's position inside [date]'s column, in the wall-clock minutes the gridlines are
     * labelled with.
     *
     * A block that began before the day starts at the top of the column and one that runs past
     * midnight reaches the bottom, so crossing days are drawn whole on each of them. On a day whose
     * clock springs forward the skipped hour simply has no column minutes, which is what the user
     * sees on their own clock.
     */
    fun blockOf(task: Task, date: LocalDate, zone: ZoneId): DayBlock? {
        val anchor = anchorOf(task) ?: return null
        if (isAllDay(task, date, zone)) return null
        val window = CalendarMath.dayWindow(date, zone)
        val startMinute = if (anchor <= window.first) 0 else CalendarMath.minutesOfDay(anchor, zone)
        val end = knownEndOf(task)
        val endMinute = when {
            end == null -> null
            end >= window.last + 1 -> MINUTES_PER_DAY
            else -> CalendarMath.minutesOfDay(end, zone).coerceAtLeast(startMinute)
        }
        return DayBlock(task, startMinute.coerceIn(0, MINUTES_PER_DAY), endMinute, emptyList())
    }

    /** "That day, no time given": the deadline is the day's own first instant and no start was entered. */
    fun isAllDay(task: Task, date: LocalDate, zone: ZoneId): Boolean =
        task.startTime == null && task.endTime == null &&
            task.dueDate != null && task.dueDate == CalendarMath.startOfDay(date, zone)

    /**
     * The cells of [grid]. Tasks outside the grid are ignored rather than loaded, which is the whole
     * point of asking the database for a bounded window.
     */
    fun cells(
        grid: List<LocalDate>,
        displayedMonth: YearMonth?,
        tasks: List<Task>,
        categories: List<Category>,
        today: LocalDate,
        now: Long,
        zone: ZoneId = CalendarMath.systemZone()
    ): List<DayCell> {
        if (grid.isEmpty()) return emptyList()
        val gridStart = grid.first()
        val gridEnd = grid.last()
        val byDay = LinkedHashMap<LocalDate, MutableList<Task>>()
        tasks.forEach { task ->
            daysOf(task, gridStart, gridEnd, zone).forEach { day ->
                byDay.getOrPut(day) { mutableListOf() }.add(task)
            }
        }
        val categoriesById = categories.associateBy { it.id }

        return grid.map { date ->
            val dayTasks = byDay[date] ?: emptyList()
            val plan = planFrom(date, dayTasks, now, zone)
            DayCell(
                date = date,
                inDisplayedMonth = displayedMonth?.let { it.atDay(1) <= date && date <= it.atEndOfMonth() } ?: true,
                isToday = date == today,
                tasks = dayTasks.sortedBy { anchorOf(it) ?: Long.MAX_VALUE },
                categories = chipsFor(dayTasks, categoriesById),
                unassignedCount = dayTasks.count { it.categoryId == null },
                overdueCount = plan.overdueCount,
                busyMinutes = plan.busyMinutes,
                overlapCount = plan.overlapCount,
                overloaded = plan.overloaded
            )
        }
    }

    /** The day view: the column of blocks, plus the tasks that have a day but no time. */
    fun dayPlan(
        date: LocalDate,
        tasks: List<Task>,
        now: Long,
        zone: ZoneId = CalendarMath.systemZone()
    ): DayPlan = planFrom(date, tasks.filter { date in daysOf(it, date, date, zone) }, now, zone)

    private fun planFrom(date: LocalDate, dayTasks: List<Task>, now: Long, zone: ZoneId): DayPlan {
        val placed = dayTasks.mapNotNull { blockOf(it, date, zone) }
        val withOverlaps = withOverlaps(placed)
        val noTime = dayTasks.filter { isAllDay(it, date, zone) }
        val busy = withOverlaps.sumOf { if (it.endMinute == null) 0 else it.lengthMinutes }
        return DayPlan(
            date = date,
            blocks = withOverlaps.sortedWith(compareBy({ it.startMinute }, { it.task.title })),
            noTimeGiven = noTime.sortedBy { it.dueDate ?: Long.MAX_VALUE },
            busyMinutes = busy.coerceAtLeast(0),
            overdueCount = dayTasks.count { isOverdue(it, now) },
            // Each colliding pair counted once, by only looking at the partner with the larger row id.
            overlapCount = withOverlaps.sumOf { block ->
                block.overlappingTaskIds.count { it > block.task.id }
            },
            overloaded = withOverlaps.size >= TASK_COUNT_OVERLOAD || busy >= BUSY_MINUTES_OVERLOAD
        )
    }

    /**
     * Fills in each block's collisions.
     *
     * A block whose length the user gave overlaps another when the two share more than a boundary;
     * a marker with no length covers the single minute it points at, which is what makes "two tasks
     * due at nine" and "a deadline in the middle of a meeting" both come out as collisions without
     * pretending to know how long either task runs.
     */
    private fun withOverlaps(blocks: List<DayBlock>): List<DayBlock> {
        fun upperOf(block: DayBlock) = block.endMinute ?: block.startMinute + 1
        return blocks.map { block ->
            val mineEnd = upperOf(block)
            val colliding = blocks.filter { other ->
                other !== block &&
                    maxOf(block.startMinute, other.startMinute) < minOf(mineEnd, upperOf(other))
            }.map { it.task.id }
            block.copy(overlappingTaskIds = colliding)
        }
    }

    private fun chipsFor(tasks: List<Task>, categoriesById: Map<Long, Category>): List<CategoryChip> =
        tasks.filter { it.categoryId != null }
            .groupBy { it.categoryId!! }
            .mapNotNull { (categoryId, group) ->
                val category = categoriesById[categoryId] ?: return@mapNotNull null
                CategoryChip(categoryId, category.name, category.color, group.size)
            }
            // The legend's own order is the reader's alphabet, with the id behind it so two categories a
            // collator calls equal still arrive in a stable order.
            .sortedWith(LocalisedNames.byKey<CategoryChip> { it.name }.thenBy { it.categoryId })

    /**
     * Past due. Only a deadline can be passed: a task the user planned by a start time alone has
     * nothing to be late against, so it is never counted as overdue.
     */
    private fun isOverdue(task: Task, now: Long): Boolean {
        if (task.status == TaskStatus.COMPLETED) return false
        val due = task.dueDate ?: return false
        return due < now
    }
}
