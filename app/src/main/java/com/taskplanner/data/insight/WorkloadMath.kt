package com.taskplanner.data.insight

import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What one day is asked for, as the plan reads it.
 *
 * The three numbers say different things and the screen has to be able to tell them apart. [minutes] is the
 * work the plan puts on that day, in whole minutes. [tasks] is how many separate rows are behind it — a day
 * of nine undated-length deadlines is crowded in a way its minutes cannot show. [approximate] is the §8
 * caveat made into a flag: the minutes are only a measurement when the user stated the hours themselves, and
 * when they came from a deadline's estimate (or a deadline with no estimate at all, where the tasks are the
 * honest figure and the minutes are zero), the reading has to say so instead of sounding measured.
 */
data class DayLoad(
    val epochDay: Long,
    val minutes: Int,
    val tasks: Int,
    val approximate: Boolean = false
)

/**
 * How much of the counted work the reader called important.
 *
 * A load figure alone says how much is on Thursday; the split says whether Thursday matters. It is counted
 * over exactly the rows [WorkloadMath.fold] charged, which is why it comes out of the same pass rather than
 * from a second filter: a panel that named three high-priority tasks beside a day holding two rows would be
 * two readings of one window, and the reader would have no way to notice.
 */
data class PriorityLoad(
    val high: Int = 0,
    val medium: Int = 0,
    val low: Int = 0
) {
    /** How many pieces of work the window holds, counted once however many days one of them covers. */
    val total: Int get() = high + medium + low
}

/**
 * What the fold answers in one pass: the days, and what the counted rows say about themselves.
 *
 * [lengthUnknownTasks] is the count §8's caveat is really about. [DayLoad.approximate] says a day's minutes
 * may be guesses; this says how many rows have no length stated at all, which is the difference between "the
 * hours are rough" and "nobody said how long this takes". A panel that printed only the first would leave the
 * reader to wonder how much of the window is simply unmeasured.
 */
data class WorkloadFold(
    val days: List<DayLoad> = emptyList(),
    val priorities: PriorityLoad = PriorityLoad(),
    val lengthUnknownTasks: Int = 0
)

/**
 * The fold from task rows to per-day load.
 *
 * Pure, and given the zone rather than reading the device's: the day a block is charged to is the day the
 * user saw the clock on, which is why a block running from 23:00 Monday to 01:00 Tuesday is split rather than
 * placed whole on either side, and why the split is made on local midnights rather than on 86 400 000
 * milliseconds — across a daylight-saving shift those two midnights are 23 or 25 hours apart, and the block
 * really did last that long.
 *
 * One priority runs through it: **a stated block beats an estimate**. A task that names its hours and also
 * carries an estimate is one piece of work, not two, and charging both would inflate the day out of thin
 * air — the reading would then disagree with the calendar the reader can open and check.
 */
object WorkloadMath {

    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * The days, oldest first, with the priority split and the unknown lengths over the inclusive epoch-day
     * range `[fromDay, toDay]`.
     *
     * Days with nothing on them get no row: an absent day and a day measured at zero minutes are different
     * claims, and a screen drawing a week fills the gaps from its own calendar. Completed, archived and
     * thrown-away tasks are left out because the reading is about the work that is still ahead.
     *
     * The three answers come out of one walk over one filtered list on purpose. A split counted over a second
     * filter would be a second reading of the same window, and the reader looking at a panel that named three
     * important tasks beside a day holding two rows would have no way to tell which of the two was wrong.
     */
    fun fold(tasks: List<Task>, fromDay: Long, toDay: Long, zone: ZoneId): WorkloadFold {
        if (fromDay > toDay) return WorkloadFold()
        val minutes = mutableMapOf<Long, Int>()
        val counts = mutableMapOf<Long, Int>()
        val guessed = mutableSetOf<Long>()
        // A row charged to two days is still one row, so what the split and the unknown-length count are
        // measured over is the list of rows that landed somewhere, not the sum of what the days hold.
        val charged = mutableListOf<Task>()
        // Insertion order is row order, not day order, so the days are put in order at the end rather than
        // assumed from a query whose ORDER BY belongs to a different question.
        tasks.asSequence()
            .filter { !it.isDeleted && !it.archived && it.status != TaskStatus.COMPLETED }
            .forEach { task ->
                val start = task.startTime
                val end = task.endTime
                val dueDate = task.dueDate
                val landed = when {
                    start != null && end != null && end > start ->
                        chargeBlock(start, end, fromDay, toDay, zone, minutes, counts) > 0
                    dueDate != null ->
                        chargeDeadline(task, dueDate, fromDay, toDay, zone, minutes, counts, guessed)
                    start != null -> {
                        // A start with no stated end is a day the work belongs to, with nothing known about
                        // its length — the same position a bare deadline puts the reader in.
                        val day = epochDayOf(start, zone)
                        if (day in fromDay..toDay) {
                            counts[day] = (counts[day] ?: 0) + 1
                            guessed.add(day)
                            true
                        } else false
                    }
                    else -> false
                }
                if (landed) charged.add(task)
            }
        return WorkloadFold(
            days = counts.keys.sorted().map { day ->
                DayLoad(
                    epochDay = day,
                    minutes = minutes[day] ?: 0,
                    tasks = counts[day] ?: 0,
                    approximate = day in guessed
                )
            },
            priorities = PriorityLoad(
                high = charged.count { it.priority == Priority.HIGH },
                medium = charged.count { it.priority == Priority.MEDIUM },
                low = charged.count { it.priority == Priority.LOW }
            ),
            lengthUnknownTasks = charged.count { lengthIsUnknown(it) }
        )
    }

    /**
     * Whether the plan says nothing about how long this row takes.
     *
     * A stated block is a length the reader typed, an estimate is a length they guessed, and neither is the
     * same as an absence. Only the absence makes a window unreadable in minutes, and it is what §8's
     * "the duration is unknown" refers to.
     */
    private fun lengthIsUnknown(task: Task): Boolean =
        !hasStatedBlock(task) && (task.estimatedMinutes ?: 0) <= 0

    private fun hasStatedBlock(task: Task): Boolean {
        val start = task.startTime ?: return false
        val end = task.endTime ?: return false
        return end > start
    }

    /** A block the user typed: its minutes are measured, and they are charged to each day it covered. */
    private fun chargeBlock(
        start: Long,
        end: Long,
        fromDay: Long,
        toDay: Long,
        zone: ZoneId,
        minutes: MutableMap<Long, Int>,
        counts: MutableMap<Long, Int>
    ): Int {
        val firstDay = maxOf(epochDayOf(start, zone), fromDay)
        val lastDay = minOf(epochDayOf(end, zone), toDay)
        var day = firstDay
        var charged = 0
        while (day <= lastDay) {
            val dayStart = startOfDay(day, zone)
            val dayEnd = startOfDay(day + 1, zone)
            val overlap = minOf(end, dayEnd) - maxOf(start, dayStart)
            if (overlap > 0L) {
                minutes[day] = (minutes[day] ?: 0) + (overlap / MILLIS_PER_MINUTE).toInt()
                counts[day] = (counts[day] ?: 0) + 1
                charged++
            }
            day++
        }
        return charged
    }

    /**
     * A deadline: the task is certainly on that day, and its length is only known if it was estimated.
     *
     * An estimate is charged as the plan the user wrote, and the day is marked approximate because those
     * minutes are a guess about the future rather than a measurement of the past. With no estimate the count
     * goes up and the minutes do not, which is the honest version of "there is work here and nobody said how
     * long it takes".
     */
    private fun chargeDeadline(
        task: Task,
        dueDate: Long,
        fromDay: Long,
        toDay: Long,
        zone: ZoneId,
        minutes: MutableMap<Long, Int>,
        counts: MutableMap<Long, Int>,
        guessed: MutableSet<Long>
    ): Boolean {
        val day = epochDayOf(dueDate, zone)
        if (day !in fromDay..toDay) return false
        counts[day] = (counts[day] ?: 0) + 1
        guessed.add(day)
        val estimate = task.estimatedMinutes
        if (estimate != null && estimate > 0) {
            minutes[day] = (minutes[day] ?: 0) + estimate
        }
        return true
    }

    private fun epochDayOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun startOfDay(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
}
