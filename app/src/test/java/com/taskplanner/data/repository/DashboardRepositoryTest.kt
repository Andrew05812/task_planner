package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.statistics.ChangeDirection
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.statistics.TrendDay
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashboard's one read, against real storage.
 *
 * What is checked here is not that SQLite can add — `WorkStatisticsDaoTest` and
 * `TaskStatisticsDaoTest` already hold each statement up against a hand-folded oracle — but that the
 * figures the screen is given are those answers, for the window the screen named, and that the arithmetic
 * between an answer and a chart neither invents a day nor loses a row. The repository is where two
 * aggregates written for different screens meet, and that is where a dashboard starts disagreeing with
 * itself.
 *
 * Every test anchors the request on a Wednesday of its own and parks the clock at midday of that day, so
 * "overdue", "today", "this week" and a window's two bounds mean the same instants in the test and in the
 * repository.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardRepositoryTest {

    private lateinit var fixture: AppFixture
    private var userId: Long = -1L
    private val zone: ZoneId = CalendarMath.systemZone()

    private val today: LocalDate = LocalDate.of(2026, 6, 3)
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val lastMonth: LocalDate = LocalDate.of(2026, 5, 20)

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The request the screen makes, anchored on [anchor] rather than on the device's date. */
    private fun request(
        period: WorkPeriod = WorkPeriod.THIS_WEEK,
        anchor: LocalDate = today,
        weeks: Int = 5
    ) = DashboardRequest(
        period = period,
        heatmapWeeks = weeks,
        onDay = anchor,
        zone = zone,
        firstDayOfWeek = DayOfWeek.MONDAY
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    /**
     * Four tasks and four measured stretches, spread so that no two figures in the snapshot can be
     * produced by the same window:
     *
     *  * *report* — made Monday, worked Monday in one session of two stretches, finished this morning;
     *  * *plants* — made and finished last month, worked this morning, worked once in early May;
     *  * *mail* — open, late since yesterday, untouched since May, so it is both overdue and stale;
     *  * *brief* — open, made yesterday, due in two days, so it is neither.
     *
     * Monday's work is 30 + 20 minutes, today's is an hour, May's is a minute.
     */
    private suspend fun seed(): User {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.shiftTo(at(today, 12, 0))

        val report = task("report", created = monday)
        val plants = task("plants", created = lastMonth)
        task("mail", created = LocalDate.of(2026, 5, 10), due = at(tuesday, 9, 0),
            updatedAt = at(LocalDate.of(2026, 5, 10), 9, 0))
        task("brief", created = tuesday, due = at(LocalDate.of(2026, 6, 4), 9, 0),
            updatedAt = at(tuesday, 12, 0))

        work("i1", "s1", report, monday, 1_800L)
        work("i2", "s1", report, monday, 1_200L)
        work("i3", "s2", plants, today, 3_600L)
        work("i4", "s3", report, LocalDate.of(2026, 5, 4), 60L)

        fixture.taskDao.updateTask(report.copy(status = TaskStatus.COMPLETED, completedAt = at(today, 9, 0)))
        fixture.taskDao.updateTask(plants.copy(status = TaskStatus.COMPLETED, completedAt = at(lastMonth, 9, 0)))
        return user
    }

    private suspend fun task(
        title: String,
        created: LocalDate,
        due: Long? = null,
        updatedAt: Long = at(created, 12, 0),
        forProfile: Long = userId
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title", title = title, description = "", userId = forProfile,
                dueDate = due, createdAt = at(created, 8, 0), updatedAt = updatedAt
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private suspend fun work(
        uuid: String,
        sessionUuid: String,
        task: Task,
        day: LocalDate,
        durationSeconds: Long
    ) = fixture.workSessionDao.insert(
        WorkSession(
            uuid = uuid,
            userId = task.userId,
            trackingSessionUuid = sessionUuid,
            taskId = task.id,
            taskUuid = task.uuid,
            startedAt = at(day),
            endedAt = at(day) + durationSeconds * 1_000L,
            durationSeconds = durationSeconds,
            localDay = day.toEpochDay(),
            utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(day)),
            source = WorkSource.TRACKER
        )
    )

    @Test
    fun `the period's totals are the aggregates the work screen reads, for the same window`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // Monday's 50 minutes and today's hour are in the week; May's minute is not. The figure is
        // `WorkStatisticsDao`'s, so the dashboard and the history screen cannot report two different
        // amounts of work for one period, and the two stretches of one session stay one session.
        assertEquals(6_600L, snapshot.measured.seconds)
        assertEquals(3, snapshot.measured.intervals)
        assertEquals(2, snapshot.measured.sessions)
        assertEquals(WorkPeriod.THIS_WEEK, snapshot.request.period)
    }

    /**
     * The window the comparison is read over, spelled out in the calendar rather than trusted to a shift.
     *
     * A comparison of two periods is only worth printing if the two windows are the same shape, so this
     * seeds work on one day inside the window before the period, one day inside the period, and one day
     * inside neither — and then asks the same read for the period that *is* the window before. The two
     * answers have to agree, and the month's case is the one that shows what the rule costs: May has a day
     * more than June, so the ground between two months is levelled by length rather than by name.
     */
    @Test
    fun `the comparison reads the window before the period, and never the period itself`() = runBlocking {
        seed()
        // Monday of last week (26 May): two hours measured and a task finished on the 27th.
        val lastWeek = task("last week report", created = LocalDate.of(2026, 5, 26))
        work("i5", "s5", lastWeek, LocalDate.of(2026, 5, 26), 3_600L)
        finished("shut last week", at(LocalDate.of(2026, 5, 27), 9, 0))
        // Yesterday, which is inside this week and outside last one.
        work("i6", "s6", lastWeek, tuesday, 600L)
        // Belongs to neither window: five days before last week's Monday.
        val older = task("early may report", created = LocalDate.of(2026, 5, 6))
        work("i7", "s7", older, LocalDate.of(2026, 5, 6), 1_200L)
        finished("shut in early may", at(LocalDate.of(2026, 5, 6), 18, 0))
        // Inside last month and outside the thirty days before this one, which is where the two months
        // part company.
        val mayDay = task("first of may", created = LocalDate.of(2026, 5, 1))
        work("i8", "s8", mayDay, LocalDate.of(2026, 5, 1), 300L)

        val thisWeek = fixture.dashboardRepository.snapshot(request(WorkPeriod.THIS_WEEK))!!
        val lastWeekRead = fixture.dashboardRepository.snapshot(request(WorkPeriod.LAST_WEEK))!!
        val todayRead = fixture.dashboardRepository.snapshot(request(WorkPeriod.TODAY))!!
        val yesterdayRead = fixture.dashboardRepository.snapshot(request(WorkPeriod.YESTERDAY))!!
        val thisMonth = fixture.dashboardRepository.snapshot(request(WorkPeriod.THIS_MONTH))!!
        val lastMonth = fixture.dashboardRepository.snapshot(request(WorkPeriod.LAST_MONTH))!!

        assertEquals(
            "the period's own figure keeps the week it names: Monday's fifty minutes, yesterday's ten and " +
                "this morning's hour",
            7_200L,
            thisWeek.measured.seconds
        )
        assertEquals(
            "and the comparison is the week before it — 26 May's two hours, with neither this week's work " +
                "nor 6 May's in it",
            3_600L,
            thisWeek.previous.measuredSeconds
        )
        assertEquals(
            "the same read the LAST_WEEK chip gets for its own headline",
            lastWeekRead.measured.seconds,
            thisWeek.previous.measuredSeconds
        )
        assertEquals(
            "one finish, on the 27th, counted the same way the period counts its own",
            lastWeekRead.completed,
            thisWeek.previous.completed
        )
        assertEquals(
            "a day's window is as short as the day it follows",
            yesterdayRead.measured.seconds,
            todayRead.previous.measuredSeconds
        )

        // The month the user would name is May, and May has thirty-one days; the comparison is over thirty,
        // because a percentage between windows of different lengths would be partly about the calendar. The
        // 300 seconds on the first of May are the whole of the difference.
        assertEquals(5_160L, lastMonth.measured.seconds)
        assertEquals(4_860L, thisMonth.previous.measuredSeconds)
        assertEquals(
            "and the month's own figures are still read over the month it names",
            7_200L,
            thisMonth.measured.seconds
        )

        assertEquals(ChangeDirection.UP, thisWeek.workChange.direction)
        assertEquals(
            "7 200 seconds against 3 600 is twice the work, which prints as a hundred percent over it",
            1.0,
            requireNotNull(thisWeek.workChange.fraction),
            1e-9
        )
    }

    /**
     * The false-percentage case the comparison exists to refuse, on a period the seeded account really has
     * work in.
     *
     * Today measured an hour and yesterday measured nothing, so the only honest statement is that there is
     * no base: neither an infinite one nor a hundred-and-ten-percent invented out of a zero. The finished
     * count is the same shape one figure over, and the screen is handed two nulls rather than two lies.
     */
    @Test
    fun `a period that follows an empty one is given no percentage to print`() = runBlocking {
        seed()

        val day = fixture.dashboardRepository.snapshot(request(WorkPeriod.TODAY))!!

        assertEquals(3_600L, day.measured.seconds)
        assertEquals(0L, day.previous.measuredSeconds)
        assertEquals(1, day.completed)
        assertEquals(0, day.previous.completed)

        assertEquals(ChangeDirection.UP, day.workChange.direction)
        assertNull(
            "an increase over nothing is not a percentage, so the read gives the screen the two figures and " +
                "no number to divide them by",
            day.workChange.fraction
        )
        assertEquals(ChangeDirection.UP, day.completionsChange.direction)
        assertNull(day.completionsChange.fraction)

        // The same account one period later has the base it needed: yesterday is empty because the seed
        // never says otherwise, and the week's own change divides a figure that exists.
        val week = fixture.dashboardRepository.snapshot(request(WorkPeriod.THIS_WEEK))!!
        assertEquals(
            "6 600 over Monday's and today's work, against no work at all the day before this one",
            0L,
            week.previous.measuredSeconds
        )
        assertNull(week.workChange.fraction)
    }

    @Test
    fun `the bars carry the days that produced something and no others`() = runBlocking {
        seed()

        val days = fixture.dashboardRepository.snapshot(request())!!.trend

        assertEquals(
            listOf(
                TrendDay(monday.toEpochDay(), 3_000L, 0),
                TrendDay(today.toEpochDay(), 3_600L, 1)
            ),
            days
        )
        // Nothing was measured on Sunday, and no bar is drawn for it: the gap between the two bars is the
        // truth about when the work happened.
        assertFalse(days.any { it.localDay == LocalDate.of(2026, 5, 31).toEpochDay() })
    }

    @Test
    fun `the grid is exactly the days the query was asked for`() = runBlocking {
        seed()

        val cells = fixture.dashboardRepository.snapshot(request(weeks = 5))!!.heatmap.weeks.flatten()

        assertEquals(35, cells.size)
        assertEquals(
            "the window the reader asked for is the window the grid shows",
            DashboardMath.heatmapRange(today, 5, DayOfWeek.MONDAY).toList(),
            cells.map { it.localDay }
        )
        // May the 4th is inside five weeks back and outside the period's totals, so the same work is on
        // the grid and not in the header — the two pictures answer different questions by design.
        assertEquals(
            60L, cells.first { it.localDay == LocalDate.of(2026, 5, 4).toEpochDay() }.seconds
        )
        assertEquals(6_600L + 60L, cells.sumOf { it.seconds })
        assertFalse(cells.first { it.localDay == today.toEpochDay() }.isFuture)
        assertEquals(
            "the rest of this week has not happened yet",
            listOf(1L, 2L, 3L, 4L),
            cells.filter { it.isFuture }.map { it.localDay - today.toEpochDay() }
        )
    }

    @Test
    fun `a streak is read over the grid rather than over the period`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // Worked today, nothing measured on Tuesday: the run is one day, and the period's fifty-some
        // minutes of work have nothing to say about it.
        assertEquals(1, snapshot.streakDays)
    }

    @Test
    fun `the health panel and the completion figures speak about the same rows`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // Two rows are open. One is late and stale, the other due in two days — so the buckets are
        // mutually exclusive and the total is the pair, not their sum doubled.
        assertEquals(2, snapshot.health.open)
        assertEquals(1, snapshot.health.overdue)
        assertEquals(1, snapshot.health.dueThisWeek)
        assertEquals(0, snapshot.health.dueToday)
        assertEquals(1, snapshot.health.stale)
        assertEquals(0, snapshot.health.withoutDeadline)

        // Both finished tasks are in the account's history whatever the panel says about the open pair.
        assertEquals(2, snapshot.completedEver)
        assertEquals(4, snapshot.totalTasks)
        assertEquals(0.5f, snapshot.completionRate!!)
        // One of them was finished inside the week, and two of the rows were made inside it.
        assertEquals(1, snapshot.completed)
        assertEquals(2, snapshot.created)
    }

    @Test
    fun `a period the account has no work in is empty without being an error`() = runBlocking {
        seed()

        val snapshot = fixture.dashboardRepository.snapshot(request(period = WorkPeriod.YESTERDAY))!!

        // Nothing was measured or finished on Tuesday. The screen still has an account to report on, and
        // the grid still spans its own five weeks — the two are different lengths by design, so a period
        // with no work in it reads as that day being quiet rather than as the account being empty.
        assertEquals(0L, snapshot.measured.seconds)
        assertEquals(0, snapshot.measured.intervals)
        assertEquals(emptyList<TrendDay>(), snapshot.trend)
        assertEquals(0, snapshot.completed)
        assertEquals(3_600L, snapshot.heatmap.busiestDaySeconds)
        assertEquals(35, snapshot.heatmap.weeks.flatten().size)
        assertFalse("the screen still has tasks to report on", snapshot.isEmpty)
    }

    @Test
    fun `an account with nothing in it is an empty screen rather than a crash`() = runBlocking {
        fixture.signIn()
        fixture.timeBase.shiftTo(at(today, 12, 0))

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        assertTrue(snapshot.isEmpty)
        assertNull("a share of nothing is no figure at all", snapshot.completionRate)
        assertEquals(0L, snapshot.heatmap.busiestDaySeconds)
        assertTrue(snapshot.heatmap.weeks.flatten().all { it.level == 0 })
        assertEquals(emptyList<TrendDay>(), snapshot.trend)
        assertEquals(0, snapshot.streakDays)
        assertEquals(0, snapshot.health.open)
    }

    @Test
    fun `nobody signed in is no snapshot rather than an empty one`() = runBlocking {
        assertNull(fixture.dashboardRepository.snapshot(request()))
    }

    @Test
    fun `another profile's work and tasks stay in the other profile`() = runBlocking {
        val mine = seed()
        val other = fixture.signIn("Other", "other@example.com")
        fixture.timeBase.shiftTo(at(today, 12, 0))
        val theirs = task("their report", created = today, forProfile = other.id)
        work("i9", "s9", theirs, today, 5_000L)

        val theirSnapshot = fixture.dashboardRepository.snapshot(request())!!
        assertEquals(5_000L, theirSnapshot.measured.seconds)
        assertEquals(1, theirSnapshot.totalTasks)
        assertEquals(0, theirSnapshot.completedEver)

        fixture.switchTo(mine)
        val mySnapshot = fixture.dashboardRepository.snapshot(request())!!
        assertEquals(6_600L, mySnapshot.measured.seconds)
        assertEquals(4, mySnapshot.totalTasks)
        assertEquals(1, mySnapshot.completed)
    }

    @Test
    fun `the charts move when a task is finished while the screen is open`() = runBlocking {
        seed()

        val answers = snapshotAroundWrite {
            val open = fixture.taskDao.getTaskByUuid("task-mail")!!
            fixture.taskDao.updateTask(
                open.copy(status = TaskStatus.COMPLETED, completedAt = at(today, 11, 30))
            )
        }
        val (before, after) = answers

        // No timer and no full list: the write moved the profile's last-change signal, the dashboard
        // re-ran its aggregates, and the figures that describe that row moved together — the period's
        // count up, the panel's open and overdue counts down.
        assertEquals(1, before.completed)
        assertEquals(2, after.completed)
        assertEquals(2, before.health.open)
        assertEquals(1, after.health.open)
        assertEquals(1, before.health.overdue)
        assertEquals(0, after.health.overdue)
        assertEquals(
            "measured work is untouched by finishing a task",
            before.measured.seconds,
            after.measured.seconds
        )
    }

    /**
     * A task finished at exactly [instant], placed to sit on or just off a window's edge.
     *
     * The instant is the whole reason the row exists: both the completion window and the per-day bars read
     * it, so a finish is positioned to the millisecond against a bound rather than to a convenient hour.
     */
    private suspend fun finished(title: String, instant: Long): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "finished-$title", title = title, description = "", userId = userId,
                status = TaskStatus.COMPLETED, completedAt = instant,
                createdAt = at(today.minusDays(1), 8, 0), updatedAt = instant
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    /**
     * An open row for the health panel, with its deadline and last-touch instants named exactly rather
     * than derived, so a boundary task lands on the very bound the SQL compares against.
     */
    private suspend fun openTask(
        title: String,
        due: Long? = null,
        status: TaskStatus = TaskStatus.TODO,
        updatedAt: Long = at(today, 12, 0),
        forProfile: Long = userId
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "health-$title", title = title, description = "", userId = forProfile,
                dueDate = due, status = status,
                createdAt = at(today.minusDays(1), 8, 0), updatedAt = updatedAt
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    /**
     * Completion counting through the one read the screen makes, at the two edges of the window and inside
     * a single day.
     *
     * Written the obvious way — assert the header's number and move on — this would miss the failure it
     * exists to catch: the headline count and the chart's bars telling two different stories about the same
     * finished rows. The instants go on the window's bounds to the millisecond (the lower one kept, the
     * upper one not) and two finishes land on the same local day an ordinary day apart, so a day-bucket that
     * slid onto the neighbouring UTC day, or a header that did not equal the sum of its own bars, fails here
     * rather than looking fine on the screen.
     */
    @Test
    fun `a completion is credited to the day and the window its own instant falls in`() = runBlocking {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.shiftTo(at(today, 12, 0))

        // THIS_WEEK spans Monday 2026-06-01 to Sunday 2026-06-07: the window's `from` is Monday midnight and
        // its `to` the following Monday, so both bounds and the Wednesday they straddle are exact instants.
        val open = at(monday, 0, 0)
        val close = at(monday.plusDays(7), 0, 0)

        val beforeLower = open - 1
        val atLower = open
        val earlyWednesday = at(today, 0, 5)
        val lateWednesday = at(today, 23, 55)
        val beforeUpper = close - 1
        val atUpper = close

        // Two of the six finishes sit on the window's far edges — one a millisecond before it opens, one
        // exactly as it closes — and two share the Wednesday an ordinary day apart, at 00:05 and 23:55.
        finished("before-lower", beforeLower)
        finished("at-lower", atLower)
        finished("early", earlyWednesday)
        finished("late", lateWednesday)
        finished("before-upper", beforeUpper)
        finished("at-upper", atUpper)

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        val inWindow = listOf(atLower, earlyWednesday, lateWednesday, beforeUpper)

        // The header counts only the four rows inside the window; the two placed on its far edges belong to
        // the account's history without touching this period.
        assertEquals(
            "the window's opening instant is kept and its closing instant handed to the next period",
            inWindow.size,
            snapshot.completed
        )
        assertEquals(
            "the two finishes outside the window are still completions of the account, just not of the week",
            6,
            snapshot.completedEver
        )

        // The bars must file each in-window instant under the calendar day the request's zone reads, the
        // same translation the repository applies — the set folded here with DashboardMath.epochDayOf, so
        // a day-bucket that drifted would disagree with the grouped result the chart is drawn from.
        val expectedByDay = inWindow
            .groupingBy { DashboardMath.epochDayOf(it, zone) }
            .eachCount()
        val barsByDay = snapshot.trend.associate { it.localDay to it.completions }
        assertEquals(
            "00:05 and 23:55 of one Wednesday are one bar, and Monday's and Sunday's finishes their own",
            expectedByDay,
            barsByDay
        )

        assertEquals(
            "the headline figure and the bars are one read, not two answers — the count of finished tasks " +
                "equals the sum of its own per-day bars",
            snapshot.completed,
            snapshot.trend.sumOf { it.completions }
        )
    }

    /**
     * Every field of the health read as the repository hands it, checked against the open rows the panel's
     * five deadline buckets are meant to tile.
     *
     * This is the repository's view of the fold `TaskStatisticsDaoTest` holds against the SQL, and what it
     * would miss written the obvious way — a single assert on `open` — is the shape of the partition: two
     * rows crowding one bucket, one row counted twice, an in-progress task quietly dropped from the total.
     * Each boundary is its own seeded row sitting on the bound to the millisecond, so a `<` that should read
     * `<=` (or the reverse) at `now`, `dayEnd`, `weekEnd` or `staleBefore` moves exactly one field and the
     * partition sum then fails against `open`.
     */
    @Test
    fun `the health panel's every field tiles the open rows it was read from`() = runBlocking {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.shiftTo(at(today, 12, 0))

        // The four instants the repository derives for this Wednesday-anchored request, spelled the same way
        // it spells them so each boundary task lands exactly on the bound the SQL compares against.
        val now = at(today, 12, 0)
        val dayEnd = at(today.plusDays(1), 0, 0)
        val weekEnd = at(today.plusDays(7), 0, 0)
        val staleBefore = at(today.minusDays(14), 0, 0)

        // Overdue: a deadline a moment before now. Due today: `>= now`, so a deadline exactly at now is
        // today's rather than already late.
        openTask("late-by-a-moment", due = now - 1)
        openTask("due-exactly-now", due = now)
        // Due this week: `>= dayEnd`, so midnight tonight leaves today and enters the coming seven days.
        openTask("due-exactly-day-end", due = dayEnd)
        // Due later: `>= weekEnd` falls out of this week's bucket into the remainder the panel derives; a
        // deadline a month out is the same remainder, and no deadline at all is its own bucket.
        openTask("due-exactly-week-end", due = weekEnd)
        openTask("due-next-month", due = at(today.plusDays(30), 8, 0))
        openTask("no-deadline")
        // Being worked on and overdue at once, so inProgress is shown to cut across the partition.
        openTask("in-progress-and-late", due = now - 3_600_000L, status = TaskStatus.IN_PROGRESS)
        // Stale boundary: the SQL counts `updated_at < staleBefore`, so a row untouched for exactly 14 days
        // sits on the bound and is not stale, while one a millisecond older is.
        openTask("stale-boundary-exact", due = now - 86_400_000L, updatedAt = staleBefore)
        openTask("stale-by-a-moment", updatedAt = staleBefore - 1)

        // Three rows that must land in no field at all: put away, thrown away, and another profile's.
        val archived = openTask("archived-open", due = now - 86_400_000L)
        fixture.taskDao.updateTask(archived.copy(archived = true, archivedAt = at(today, 11, 0)))
        val deleted = openTask("tombstone", due = now - 86_400_000L)
        fixture.taskDao.softDeleteTasks(listOf(deleted.id), at(today, 12, 0))
        val other = fixture.userDao.insertUser(User(email = "other@example.com", name = "Other"))
        openTask("other-profile", due = now - 86_400_000L, forProfile = other)

        val snapshot = fixture.dashboardRepository.snapshot(request())!!

        // The same figures folded in Kotlin over the rows that were actually stored. getAllTasksOnce already
        // leaves the tombstone and the other profile out; the filter leaves the archived and finished rows.
        val open = fixture.taskDao.getAllTasksOnce(userId).filter {
            !it.archived && it.status != TaskStatus.COMPLETED
        }
        val overdue = open.count { it.dueDate != null && it.dueDate!! < now }
        val dueToday = open.count { it.dueDate != null && it.dueDate!! >= now && it.dueDate!! < dayEnd }
        val dueThisWeek = open.count { it.dueDate != null && it.dueDate!! >= dayEnd && it.dueDate!! < weekEnd }
        val dueLater = open.count { it.dueDate != null && it.dueDate!! >= weekEnd }
        val withoutDeadline = open.count { it.dueDate == null }
        val inProgress = open.count { it.status == TaskStatus.IN_PROGRESS }
        val stale = open.count { it.updatedAt < staleBefore }

        val health = snapshot.health
        assertEquals("the deadline that passed a moment ago is overdue", overdue, health.overdue)
        assertEquals("a deadline exactly at now is due today, not already late", dueToday, health.dueToday)
        assertEquals(
            "a deadline exactly at midnight tonight is this week's, not today's",
            dueThisWeek,
            health.dueThisWeek
        )
        assertEquals("an open row with no deadline is neither late nor on time", withoutDeadline, health.withoutDeadline)
        assertEquals(
            "being worked on is counted across the buckets, never as one of them",
            inProgress,
            health.inProgress
        )
        assertEquals(
            "only the row strictly older than 14 days is stale; the one on the bound is not",
            stale,
            health.stale
        )
        assertEquals(
            "the archived, thrown-away and other-profile rows appear in no field",
            open.size,
            health.open
        )

        // The five deadline buckets tile the open rows exactly once each. `inProgress` and `stale` are
        // deliberately outside this sum: a task that is being worked on, or left untouched, and is overdue
        // is counted once in `open` and again in its own field, never a second time inside the partition.
        assertEquals(
            "overdue + dueToday + dueThisWeek + dueLater + withoutDeadline is the open total, and nothing else",
            health.open,
            overdue + dueToday + dueThisWeek + dueLater + withoutDeadline
        )
    }

    /**
     * Two snapshots, with [write] run only after the first one has been produced.
     *
     * `fixture.awaitIdle()` cannot be used in this test: it blocks the thread the collection runs on, so
     * the watcher would never subscribe and the write would land before anything was listening. Waiting on
     * the first answer is both what makes the test work and the proof it needs — the write is known to
     * have happened while the screen was already watching.
     */
    private suspend fun snapshotAroundWrite(write: suspend () -> Unit): List<DashboardSnapshot> =
        coroutineScope {
            val seen = ArrayList<DashboardSnapshot>()
            val firstAnswer = CompletableDeferred<Unit>()
            val secondAnswer = CompletableDeferred<Unit>()
            val watching = launch {
                fixture.dashboardRepository.observe(request()).take(2).collect { snapshot ->
                    if (snapshot != null) {
                        seen.add(snapshot)
                        if (seen.size == 1) firstAnswer.complete(Unit) else secondAnswer.complete(Unit)
                    }
                }
            }

            withTimeout(10_000L) { firstAnswer.await() }
            write()
            withTimeout(10_000L) { secondAnswer.await() }
            watching.cancel()
            seen.toList()
        }
}
