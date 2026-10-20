package com.taskplanner.data.insight

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
     * Load per day over the inclusive epoch-day range `[fromDay, toDay]`, oldest day first.
     *
     * Days with nothing on them get no row: an absent day and a day measured at zero minutes are different
     * claims, and a screen drawing a week fills the gaps from its own calendar. Completed, archived and
     * thrown-away tasks are left out because the reading is about the work that is still ahead.
     */
    fun dailyLoad(tasks: List<Task>, fromDay: Long, toDay: Long, zone: ZoneId): List<DayLoad> {
        if (fromDay > toDay) return emptyList()
        val minutes = linkedMapOf<Long, Int>()
        val counts = linkedMapOf<Long, Int>()
        val guessed = sortedSetOf<Long>()
        // Insertion order is row order, not day order, so the days are put in order at the end rather than
        // assumed from a query whose ORDER BY belongs to a different question.
        tasks.asSequence()
            .filter { !it.isDeleted && !it.archived && it.status != TaskStatus.COMPLETED }
            .forEach { task ->
                val start = task.startTime
                val end = task.endTime
                if (start != null && end != null && end > start) {
                    chargeBlock(start, end, fromDay, toDay, zone, minutes, counts)
                } else if (task.dueDate != null) {
                    chargeDeadline(task, task.dueDate, fromDay, toDay, zone, minutes, counts, guessed)
                } else if (start != null) {
                    // A start with no stated end is a day the work belongs to, with nothing known about
                    // its length — the same position a bare deadline puts the reader in.
                    val day = epochDayOf(start, zone)
                    if (day in fromDay..toDay) {
                        counts[day] = (counts[day] ?: 0) + 1
                        guessed.add(day)
                    }
                }
            }
        return counts.keys
            .sorted()
            .map { day ->
                DayLoad(
                    epochDay = day,
                    minutes = minutes[day] ?: 0,
                    tasks = counts[day] ?: 0,
                    approximate = day in guessed
                )
            }
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
    ) {
        val firstDay = maxOf(epochDayOf(start, zone), fromDay)
        val lastDay = minOf(epochDayOf(end, zone), toDay)
        var day = firstDay
        while (day <= lastDay) {
            val dayStart = startOfDay(day, zone)
            val dayEnd = startOfDay(day + 1, zone)
            val overlap = minOf(end, dayEnd) - maxOf(start, dayStart)
            if (overlap > 0L) {
                minutes[day] = (minutes[day] ?: 0) + (overlap / MILLIS_PER_MINUTE).toInt()
                counts[day] = (counts[day] ?: 0) + 1
            }
            day++
        }
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
    ) {
        val day = epochDayOf(dueDate, zone)
        if (day !in fromDay..toDay) return
        counts[day] = (counts[day] ?: 0) + 1
        guessed.add(day)
        val estimate = task.estimatedMinutes
        if (estimate != null && estimate > 0) {
            minutes[day] = (minutes[day] ?: 0) + estimate
        }
    }

    private fun epochDayOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun startOfDay(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
}
