package com.taskplanner.ui.calendar

import com.taskplanner.data.calendar.CalendarAggregator.DayBlock
import com.taskplanner.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the day view puts its blocks, tested as arithmetic before it is tested as pixels.
 *
 * Two promises decide a whole screen and neither of them is about drawing: a block is as tall as the
 * time the user gave it and no taller, and tasks that share a minute are still each readable inside it.
 * Both are answers a test can demand in minutes — a marker that came out 40 pixels tall would be a
 * length nobody planned, and two blocks in one lane would be an appointment the user cannot find.
 */
class CalendarDayLayoutTest {

    private companion object {
        const val HOUR = 60
    }

    private var nextId = 1L

    private fun task(title: String) = Task(
        id = nextId++,
        uuid = "task-$title",
        title = title,
        description = "",
        userId = 1L
    )

    private fun block(title: String, start: Int, end: Int? = null) =
        DayBlock(task(title), start, end, emptyList())

    private fun titles(cluster: CalendarDayLayout.Cluster) =
        cluster.lanes.flatten().map { it.task.title }

    @Test
    fun `a block sits at its own minute and a column is twenty-four hours tall`() {
        assertEquals(0, CalendarDayLayout.minuteToPx(0, HOUR))
        assertEquals(15, CalendarDayLayout.minuteToPx(15, HOUR))
        assertEquals(60, CalendarDayLayout.minuteToPx(60, HOUR))
        assertEquals(720, CalendarDayLayout.minuteToPx(720, HOUR))
        assertEquals(
            "the day ends where the gridlines stop, not one pixel past them",
            24 * HOUR,
            CalendarDayLayout.columnHeightPx(HOUR)
        )
    }

    @Test
    fun `a block is as tall as the time the user named and no taller`() {
        assertEquals(
            "an hour and a half at sixty pixels an hour is ninety pixels, not a rounded hour",
            90,
            CalendarDayLayout.heightPx(block("Long meeting", 540, 630), HOUR)
        )
        assertEquals(
            "a task that starts and ends inside one minute still has to be findable",
            1,
            CalendarDayLayout.heightPx(block("Handover", 540, 541), HOUR)
        )
        assertEquals(
            "a marker claims no height at all: it is the line its own view draws",
            0,
            CalendarDayLayout.heightPx(block("Deadline", 540), HOUR)
        )
        assertEquals(
            "and a block that runs into the next day reaches the bottom of this one",
            HOUR,
            CalendarDayLayout.heightPx(block("Night shift", 1380, 1440), HOUR)
        )
    }

    @Test
    fun `tasks that collide share the column and tasks that do not keep it whole`() {
        val clusters = CalendarDayLayout.clusters(
            listOf(
                block("Standup", 540, 570),
                block("Review", 550, 610),
                block("Lunch", 720, 780)
            )
        )

        assertEquals(
            "the morning pair competes for the same minutes and the afternoon meal is on its own",
            2,
            clusters.size
        )
        assertEquals(
            "two lanes for the pair, the whole width for the quiet hour",
            listOf(2, 1),
            clusters.map { it.lanes.size }
        )
        assertEquals(
            "the earlier start gets the left lane, whoever was typed first",
            listOf(listOf("Standup", "Review"), listOf("Lunch")),
            clusters.map { titles(it) }
        )
    }

    @Test
    fun `a marker collides with the meeting it falls inside`() {
        val cluster = CalendarDayLayout.clusters(
            listOf(
                block("Design session", 540, 660),
                block("Call the bank", 600)
            )
        ).single()

        assertEquals(
            "a deadline in the middle of a meeting is exactly the collision a day view has to show",
            2,
            cluster.lanes.size
        )
        assertEquals("Design session", cluster.lanes.first().single().task.title)
        assertEquals("Call the bank", cluster.lanes.last().single().task.title)
    }

    @Test
    fun `three tasks at the same minute take three lanes`() {
        val cluster = CalendarDayLayout.clusters(
            listOf(block("Nine a", 540, 600), block("Nine b", 540, 600), block("Nine c", 540, 600))
        ).single()

        assertEquals(3, cluster.lanes.size)
        assertEquals(
            "each one alone in its lane, so none of them is hidden",
            listOf(1, 1, 1),
            cluster.lanes.map { it.size }
        )
    }

    @Test
    fun `a cluster that ends where the next begins is not an overlap`() {
        val clusters = CalendarDayLayout.clusters(
            listOf(block("Ends at ten", 540, 600), block("Starts at ten", 600, 660))
        )

        assertEquals(
            "sharing a boundary is a schedule, not a collision",
            listOf(1, 1),
            clusters.map { it.lanes.size }
        )
    }

    @Test
    fun `a cluster spans the minutes it claims and offsets each block inside itself`() {
        val cluster = CalendarDayLayout.clusters(
            listOf(block("Standup", 540, 570), block("Review", 550, 610))
        ).single()

        assertEquals("it begins at the earliest minute on show", 540, cluster.startMinute)
        assertEquals("and reaches the last minute any of its blocks claims", 610, cluster.endMinute)
        assertEquals(
            "the later task starts ten minutes into the row it shares, not ten minutes down the day",
            10,
            CalendarDayLayout.offsetPx(cluster.lanes.last().single(), cluster, HOUR)
        )
        assertEquals(
            "and the task that leads the row sits at its top",
            0,
            CalendarDayLayout.offsetPx(cluster.lanes.first().single(), cluster, HOUR)
        )
    }

    @Test
    fun `a block that runs past midnight is clamped to the day it belongs to`() {
        val cluster = CalendarDayLayout.clusters(listOf(block("Late work", 1400, 1500))).single()

        assertEquals(
            "the minutes after midnight are drawn by the next day's own column",
            1440,
            cluster.endMinute
        )
    }

    @Test
    fun `an empty day places nothing and claims nothing`() {
        assertEquals(emptyList<CalendarDayLayout.Cluster>(), CalendarDayLayout.clusters(emptyList()))
    }
}
