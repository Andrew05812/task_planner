package com.taskplanner.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.WorkHistoryRow
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.repository.AdjustmentResult
import com.taskplanner.data.repository.HistorySort
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.data.repository.WorkWindow
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
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
 * The aggregates as SQL, cross-checked against the same figures summed by hand.
 *
 * Every test stores rows through the real DAOs into a real Room database, then recomputes the answer in
 * Kotlin out of the stored rows — the slow, obvious way, with no shared code between the two — and
 * requires the database's figure to be the same. That is the only arrangement that catches the class of
 * mistake this feature can actually make: a total that is quietly wrong, off by the open interval, by a
 * hidden row, by a join that multiplied the rows it summed, or by a day boundary nobody noticed.
 *
 * The planner is read rather than imagined: the period and per-task lookups have to seek an index, since
 * a screen that shows one number cannot afford to walk the account. These run on the JVM's SQLite under
 * Robolectric, which is the same engine family as the device but not the device; §11's benchmark file
 * keeps that distinction in its own report.
 */
@RunWith(RobolectricTestRunner::class)
class WorkStatisticsDaoTest {

    private lateinit var fixture: AppFixture
    private var userId: Long = -1L

    private lateinit var report: Task
    private lateinit var plants: Task
    private lateinit var unclassified: Task

    /** The first of the three local days the fixture spreads its work over. */
    private val day0: Long = LocalDate.of(2026, 6, 1).toEpochDay()

    /** An instant inside the local day [day], mid-morning so no DST boundary falls on it. */
    private fun instantIn(day: Long, hour: Int = 10): Long =
        LocalDate.ofEpochDay(day).atTime(hour, 0).atZone(CalendarMath.systemZone())
            .toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    /**
     * A fixture with three tasks, two categories, four sessions and eight intervals — closed, open,
     * manual, hidden, and one session whose stretches fall on two different days.
     *
     * Deliberately awkward: an easy fixture makes every query agree with the oracle for the wrong
     * reason.
     */
    private suspend fun seed() {
        val user = fixture.signIn()
        userId = user.id
        val work = fixture.addCategory(user.id, "Work")
        val home = fixture.addCategory(user.id, "Home")
        report = addTask(user.id, "Write the report", work)
        plants = addTask(user.id, "Water the plants", home)
        unclassified = addTask(user.id, "Answer the mail", null)

        // S1: report, two stretches on day0 and one on day1.
        session("s1", report, TrackingState.PAUSED, startedAt = instantIn(day0))
        interval("i1", "s1", report, instantIn(day0), 600L)
        interval("i2", "s1", report, instantIn(day0, 14), 300L)
        interval("i3", "s1", report, instantIn(day1), 1200L)
        // S2: report, one closed stretch on day1 and one still open on day2.
        session("s2", report, TrackingState.RUNNING, startedAt = instantIn(day1),
            marker = userId.toString())
        interval("i4", "s2", report, instantIn(day1, 11), 90L)
        openInterval("i5", "s2", report, instantIn(day2))
        // S3: plants, a manually entered stretch on day0.
        session("s3", plants, TrackingState.COMPLETED, startedAt = instantIn(day0))
        interval("i6", "s3", plants, instantIn(day0, 8), 45L, source = WorkSource.MANUAL)
        // S4: unclassified, a stretch the user threw away and one that stayed.
        session("s4", unclassified, TrackingState.COMPLETED, startedAt = instantIn(day2))
        interval("i7", "s4", unclassified, instantIn(day2), 10_000L)
        fixture.workSessionDao.softDelete("i7", instantIn(day2, 23))
        interval("i8", "s4", unclassified, instantIn(day1, 16), 120L)
        // A finished task with a completion instant, and one whose status says finished with no instant.
        fixture.taskDao.insertTask(
            Task(
                uuid = "task-done", title = "Filed the taxes", description = "", userId = userId,
                status = TaskStatus.COMPLETED, completedAt = instantIn(day1, 18)
            )
        )
        fixture.taskDao.insertTask(
            Task(
                uuid = "task-stale", title = "Old resolution", description = "", userId = userId,
                status = TaskStatus.COMPLETED, completedAt = instantIn(day0 - 30, 9)
            )
        )
        fixture.taskDao.insertTask(
            Task(
                uuid = "task-status-only", title = "Marked done, never timed", description = "",
                userId = userId, status = TaskStatus.COMPLETED
            )
        )
    }

    private val day1: Long get() = day0 + 1
    private val day2: Long get() = day0 + 2

    private suspend fun addTask(forUserId: Long, title: String, categoryId: Long?): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title", title = title, description = "", userId = forUserId,
                categoryId = categoryId
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private suspend fun session(
        uuid: String,
        task: Task,
        state: TrackingState,
        startedAt: Long,
        marker: String? = null
    ) = fixture.trackingSessionDao.insert(
        TrackingSession(
            uuid = uuid,
            userId = userId,
            taskId = task.id,
            taskUuid = task.uuid,
            taskTitle = task.title,
            state = state,
            startedAt = startedAt,
            stoppedAt = if (state == TrackingState.COMPLETED) startedAt + 60_000 else null,
            zoneId = CalendarMath.systemZone().id,
            runningMarker = marker
        )
    )

    private suspend fun interval(
        uuid: String,
        sessionUuid: String,
        task: Task,
        startedAt: Long,
        durationSeconds: Long,
        source: WorkSource = WorkSource.TRACKER
    ) = fixture.workSessionDao.insert(
        WorkSession(
            uuid = uuid,
            userId = userId,
            trackingSessionUuid = sessionUuid,
            taskId = task.id,
            taskUuid = task.uuid,
            startedAt = startedAt,
            endedAt = startedAt + durationSeconds * 1000L,
            durationSeconds = durationSeconds,
            localDay = WorkTimeMath.epochDayOf(startedAt),
            utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(startedAt),
            source = source
        )
    )

    private suspend fun openInterval(
        uuid: String,
        sessionUuid: String,
        task: Task,
        startedAt: Long
    ) = fixture.workSessionDao.insert(
        WorkSession(
            uuid = uuid,
            userId = userId,
            trackingSessionUuid = sessionUuid,
            taskId = task.id,
            taskUuid = task.uuid,
            startedAt = startedAt,
            localDay = WorkTimeMath.epochDayOf(startedAt),
            utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(startedAt),
            openMarker = sessionUuid
        )
    )

    /** The oracle: every figure summed in Kotlin out of the rows the test stored. */
    private suspend fun visibleIntervals(): List<WorkSession> =
        fixture.workSessionDao.getAllForUser(userId).filter { !it.isDeleted }

    private suspend fun closedInRange(fromDay: Long, toDay: Long): List<WorkSession> =
        visibleIntervals().filter { it.endedAt != null && it.localDay in fromDay until toDay }

    @Test
    fun `the period total equals the same rows summed by hand`() = runTest {
        seed()
        val expected = closedInRange(day0, day2 + 1).sumOf { it.durationSeconds!! }

        val summary = fixture.workStatisticsDao.periodSummary(userId, day0, day2 + 1)

        assertEquals(
            "the open stretch has no length, so it is in no total",
            expected,
            summary.seconds
        )
        assertEquals(600L + 300L + 1200L + 90L + 45L + 120L, expected)
        assertEquals(
            "and the count agrees with the rows too",
            closedInRange(day0, day2 + 1).size,
            summary.intervals
        )
    }

    @Test
    fun `sessions inside a period are counted once per run not once per interval`() = runTest {
        seed()
        val expected = closedInRange(day0, day2 + 1)
            .map { it.trackingSessionUuid }.distinct().size

        assertEquals(expected, fixture.workStatisticsDao.periodSummary(userId, day0, day2 + 1).sessions)
        assertEquals(
            "four runs contributed closed time; s1 spans two days but is one of them",
            4,
            fixture.workStatisticsDao.periodSummary(userId, day0, day2 + 1).sessions
        )
    }

    @Test
    fun `the total for one task equals its rows summed`() = runTest {
        seed()
        val expected = visibleIntervals()
            .filter { it.taskId == report.id && it.endedAt != null }
            .sumOf { it.durationSeconds!! }

        assertEquals(expected, fixture.workStatisticsDao.secondsForTask(userId, report.id))
        assertEquals(600L + 300L + 1200L + 90L, expected)
    }

    @Test
    fun `the total for one task on one day equals that day's rows`() = runTest {
        seed()
        val expected = visibleIntervals()
            .filter { it.taskId == report.id && it.localDay == day0 && it.endedAt != null }
            .sumOf { it.durationSeconds!! }

        assertEquals(expected, fixture.workStatisticsDao.secondsForTaskOnDay(userId, report.id, day0))
        assertEquals(900L, expected)
    }

    @Test
    fun `a day's total equals every row filed on it`() = runTest {
        seed()
        val expected = visibleIntervals()
            .filter { it.localDay == day1 && it.endedAt != null }
            .sumOf { it.durationSeconds!! }

        assertEquals(expected, fixture.workStatisticsDao.secondsOnDay(userId, day1))
        assertEquals(1200L + 90L + 120L, expected)
    }

    @Test
    fun `a session measured on two days is filed under the day each stretch happened on`() = runTest {
        seed()

        assertEquals(945L, fixture.workStatisticsDao.secondsOnDay(userId, day0))
        assertEquals(1410L, fixture.workStatisticsDao.secondsOnDay(userId, day1))
        assertEquals(
            "the run that spans both days is counted in neither day twice",
            945L + 1410L,
            fixture.workStatisticsDao.periodSummary(userId, day0, day1 + 1).seconds
        )
    }

    @Test
    fun `time per task inside a period matches the rows and stops at the limit`() = runTest {
        seed()
        val from = day0
        val to = day2 + 1
        val expected = closedInRange(from, to)
            .groupBy { it.taskId }
            .mapValues { entry -> entry.value.sumOf { it.durationSeconds!! } }

        val byTask = fixture.workStatisticsDao.workByTask(userId, from, to, 10)

        assertEquals(expected.keys, byTask.map { it.taskId }.toSet())
        assertEquals(
            "each task's total is its own rows summed",
            expected,
            byTask.associate { it.taskId to it.seconds }
        )
        assertEquals(
            "most work first",
            byTask.map { it.seconds }.sortedDescending(),
            byTask.map { it.seconds }
        )
        assertEquals("Write the report", byTask.first().title)
        assertEquals(
            "the limit cuts the list, not the totals",
            listOf(byTask.first().taskId),
            fixture.workStatisticsDao.workByTask(userId, from, to, 1).map { it.taskId }
        )
    }

    @Test
    fun `a join on the session table would multiply the sum and the category total proves it does not`() = runTest {
        seed()
        // s1 has three intervals and one session row; the per-category total must be the intervals.
        val expected = closedInRange(day0, day2 + 1)
            .filter { it.taskId == report.id }
            .sumOf { it.durationSeconds!! }

        val work = fixture.workStatisticsDao.workByCategory(userId, day0, day2 + 1)
            .single { it.categoryName == "Work" }

        assertEquals(expected, work.seconds)
        assertEquals(
            "one session row joined three times would read 2100",
            4,
            work.intervals
        )
    }

    @Test
    fun `work on an unclassified task is grouped under no name instead of being lost`() = runTest {
        seed()

        val categories = fixture.workStatisticsDao.workByCategory(userId, day0, day2 + 1)

        assertEquals(
            "the hidden row is out, so the named categories and the ungrouped bucket remain",
            setOf("Work", "Home", null),
            categories.map { it.categoryName }.toSet()
        )
        assertEquals(
            "work nobody classified is shown as its own bucket instead of vanishing",
            closedInRange(day0, day2 + 1).filter { it.taskId == unclassified.id }
                .sumOf { it.durationSeconds!! },
            categories.single { it.categoryName == null }.seconds
        )
    }

    @Test
    fun `the daily trend has one point per day actually worked`() = runTest {
        seed()
        val expected = closedInRange(day0, day2 + 1)
            .groupBy { it.localDay }
            .mapValues { entry -> entry.value.sumOf { it.durationSeconds!! } }

        val series = fixture.workStatisticsDao.dailySeries(userId, day0, day2 + 1)

        assertEquals(
            "day2 holds only an open stretch, so the trend has no point for it",
            expected,
            series.associate { it.localDay to it.seconds }
        )
        assertEquals(
            "in calendar order",
            series.map { it.localDay }.sorted(),
            series.map { it.localDay }
        )
    }

    @Test
    fun `the average stretch length is taken over stretches that ended`() = runTest {
        seed()
        val closed = closedInRange(day0, day2 + 1)
        val expected = closed.map { it.durationSeconds!!.toDouble() }.average()

        assertEquals(
            "SQLite's running average over the same rows",
            expected,
            fixture.workStatisticsDao.averageIntervalSeconds(userId, day0, day2 + 1)!!,
            0.0001
        )
    }

    @Test
    fun `a period with no work at all answers zero rather than nothing`() = runTest {
        seed()

        val empty = fixture.workStatisticsDao.periodSummary(userId, day0 + 90, day0 + 91)

        assertEquals(WorkPeriodSummary(0L, 0, 0), empty)
        assertEquals(
            0L,
            fixture.workStatisticsDao.secondsForTaskOnDay(userId, report.id, day0 + 90)
        )
        assertEquals(
            "a day with no closed work answers zero, not null and not an absent row",
            0L,
            fixture.workStatisticsDao.secondsOnDay(userId, day0 + 90)
        )
        assertTrue(fixture.workStatisticsDao.workByCategory(userId, day0 + 90, day0 + 91).isEmpty())
        assertTrue(fixture.workStatisticsDao.dailySeries(userId, day0 + 90, day0 + 91).isEmpty())
        assertNull(fixture.workStatisticsDao.averageIntervalSeconds(userId, day0 + 90, day0 + 91))
    }

    @Test
    fun `runs started inside a period are counted from their start instant`() = runTest {
        seed()
        val from = CalendarMath.startOfDay(LocalDate.ofEpochDay(day1))
        val to = CalendarMath.startOfDay(LocalDate.ofEpochDay(day2))
        val expected = fixture.trackingSessionDao.getAllForUser(userId)
            .filter { !it.isDeleted && it.startedAt >= from && it.startedAt < to }
            .size

        assertEquals(expected, fixture.workStatisticsDao.sessionCount(userId, from, to))
        assertEquals("only the run that began on day1", 1, expected)
    }

    @Test
    fun `completed tasks are counted by when they were completed, not by a status that stays`() = runTest {
        seed()
        val from = CalendarMath.startOfDay(LocalDate.ofEpochDay(day1))
        val to = CalendarMath.startOfDay(LocalDate.ofEpochDay(day2))

        assertEquals(
            "only the task completed inside the window",
            1,
            fixture.workStatisticsDao.completedTasksInPeriod(userId, from, to)
        )
        assertEquals(
            "a task completed a month ago is not this week's work",
            1,
            fixture.workStatisticsDao.completedTasksInPeriod(
                userId,
                CalendarMath.startOfDay(LocalDate.ofEpochDay(day0 - 30)),
                CalendarMath.startOfDay(LocalDate.ofEpochDay(day0 - 29))
            )
        )
        assertEquals(
            "and a row whose status says done with no instant never counts",
            2,
            fixture.workStatisticsDao.completedTasksInPeriod(
                userId,
                CalendarMath.startOfDay(LocalDate.ofEpochDay(day0 - 40)),
                Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `the history list groups runs by session and totals them in one statement`() = runTest {
        seed()
        val now = instantIn(day2, 12)
        val from = CalendarMath.startOfDay(LocalDate.ofEpochDay(day0))
        val to = CalendarMath.startOfDay(LocalDate.ofEpochDay(day2)) + 86_400_000L

        val rows = fixture.workSessionDao.historyByDate(userId, from, to, now, -1L, -1L)
        val sessions = fixture.trackingSessionDao.getAllForUser(userId).filter { !it.isDeleted }

        assertEquals(
            "one row per session that started in the window",
            sessions.filter { it.startedAt in from until to }.map { it.uuid }.toSet(),
            rows.map { it.uuid }.toSet()
        )
        sessions.filter { it.startedAt in from until to }.forEach { session ->
            val row = rows.single { it.uuid == session.uuid }
            val intervals = visibleIntervals().filter { it.trackingSessionUuid == session.uuid }
            val closed = intervals.filter { it.endedAt != null }.sumOf { it.durationSeconds!! }
            val open = intervals.filter { it.endedAt == null }
            assertEquals("${session.uuid}: closed work", closed, row.closedSeconds)
            assertEquals(
                "${session.uuid}: the running stretch measured against the clock of the read",
                open.sumOf { (now - it.startedAt) / 1000L },
                row.openSeconds
            )
            assertEquals("${session.uuid}: intervals", intervals.size, row.intervals)
            assertEquals(session.taskTitle, row.taskTitle)
        }
        assertEquals(
            "newest run first",
            rows.map { it.startedAt }.sortedDescending(),
            rows.map { it.startedAt }
        )
    }

    @Test
    fun `the history filter narrows by task and by category without changing any total`() = runTest {
        seed()
        val now = instantIn(day2, 12)
        val from = CalendarMath.startOfDay(LocalDate.ofEpochDay(day0))
        val to = CalendarMath.startOfDay(LocalDate.ofEpochDay(day2)) + 86_400_000L
        val all = fixture.workSessionDao.historyByDate(userId, from, to, now, -1L, -1L)
        val homeCategoryId = fixture.categoryDao.getAllCategoriesOnce(userId).first { it.name == "Home" }.id

        val byTask = fixture.workSessionDao.historyByDate(userId, from, to, now, plants.id, -1L)
        val byCategory = fixture.workSessionDao.historyByDate(userId, from, to, now, -1L, homeCategoryId)

        assertEquals(listOf("s3"), byTask.map { it.uuid })
        assertEquals(listOf("s3"), byCategory.map { it.uuid })
        assertEquals(
            "the same session's total in both views",
            all.single { it.uuid == "s3" }.closedSeconds,
            byTask.single().closedSeconds
        )
        assertEquals(45L, byTask.single().closedSeconds)
    }

    @Test
    fun `a period aggregate seeks the day index instead of walking the profile`() {
        val plan = planOf(
            "SELECT COALESCE(SUM(w.duration_seconds), 0), COUNT(w.id) FROM work_sessions w " +
                "WHERE w.user_id = 1 AND w.local_day >= $day0 AND w.local_day < ${day0 + 3} " +
                "AND w.ended_at IS NOT NULL AND w.is_deleted = 0"
        )

        assertTrue(
            "expected the local-day index in the plan, got: $plan",
            plan.any { it.contains("index_work_sessions_user_id_local_day") }
        )
        assertFalse(
            "a total that walks every row the profile has would not survive an account with history: $plan",
            plan.any { it.contains("SCAN work_sessions") }
        )
    }

    @Test
    fun `a per-task total seeks the task index instead of walking the profile`() {
        val plan = planOf(
            "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
                "WHERE user_id = 1 AND task_id = 1 AND ended_at IS NOT NULL AND is_deleted = 0"
        )

        assertTrue(
            "expected the task index in the plan, got: $plan",
            plan.any { it.contains("index_work_sessions_user_id_task_id") }
        )
        assertFalse(
            "expected no walk of the table, got: $plan",
            plan.any { it.contains("SCAN work_sessions") }
        )
    }

    @Test
    fun `the open interval lookup reads through an index instead of walking the profile`() {
        val plan = planOf(
            "SELECT * FROM work_sessions WHERE user_id = 1 AND open_marker IS NOT NULL AND " +
                "is_deleted = 0 LIMIT 1"
        )

        assertTrue(
            "the ticker's read happens on every screen, so it cannot be a walk: $plan",
            plan.any { it.contains("SEARCH TABLE work_sessions USING") }
        )
        assertFalse(
            "expected no walk of the table, got: $plan",
            plan.any { it.contains("SCAN work_sessions") }
        )
    }

    @Test
    fun `the live figure is kept apart from every stored total`() = runTest {
        seed()

        val totals = fixture.workStatisticsRepository.totalsOnTask(report)
        val stored = fixture.workStatisticsDao.secondsForTask(userId, report.id)

        assertEquals("the closed figure is the database's own", stored, totals.closedSeconds)
        assertEquals(
            "the running stretch is measured against the clock of the read, from the stored start",
            (fixture.timeBase.wall - instantIn(day2)) / 1000L,
            totals.liveSeconds
        )
        assertTrue(
            "and it is in the total the ticker shows but in no sum any aggregate reads",
            totals.isLive && totals.totalSeconds > totals.closedSeconds
        )
        assertEquals(
            "every closed stretch counts, and only the open one is left out",
            6,
            fixture.workStatisticsDao.periodSummary(userId, day0, day2 + 1).intervals
        )
    }

    /**
     * The ticker is the one figure read rather than stored, so it is where a clock change shows up
     * before anywhere else. The live stretch follows the monotonic line, exactly as the row will be
     * measured when it closes: the screen and the database must not disagree about the same interval.
     */
    @Test
    fun `the live figure follows the monotonic line when the clock moves during a run`() = runTest {
        val user = fixture.signIn()
        val drafted = addTask(user.id, "Review the draft", null)

        fixture.timeBase.advance(1_000L)
        fixture.workTrackingRepository.start(drafted)
        fixture.timeBase.shiftWall(60 * 60_000L)
        fixture.timeBase.advance(5 * 60_000L)

        val totals = fixture.workStatisticsRepository.totalsOnTask(drafted)

        assertEquals("nothing is stored until the stretch ends", 0L, totals.closedSeconds)
        assertEquals("the ticker counts the five minutes worked, not the hour the clock invented",
            300L, totals.liveSeconds)
        assertEquals(300L, totals.totalSeconds)
        assertTrue(totals.isLive)
    }

    @Test
    fun `period ranges name the days the user means`() {
        val thursday = LocalDate.of(2026, 6, 18)

        assertEquals(WorkTimeMath.dayRange(thursday), WorkPeriod.TODAY.dayRange(thursday))
        assertEquals(WorkTimeMath.dayRange(thursday.minusDays(1)), WorkPeriod.YESTERDAY.dayRange(thursday))
        assertEquals(WorkTimeMath.weekDayRange(thursday), WorkPeriod.THIS_WEEK.dayRange(thursday))
        assertEquals(
            WorkTimeMath.weekDayRange(thursday.minusDays(7)),
            WorkPeriod.LAST_WEEK.dayRange(thursday)
        )
        assertEquals(WorkTimeMath.monthDayRange(thursday), WorkPeriod.THIS_MONTH.dayRange(thursday))
        assertEquals(
            "the month before, not the month the previous day fell in",
            WorkTimeMath.monthDayRange(LocalDate.of(2026, 5, 1)),
            WorkPeriod.LAST_MONTH.dayRange(thursday)
        )
        assertEquals(
            "and on the first of a month, last month is the whole month behind it",
            WorkTimeMath.monthDayRange(LocalDate.of(2026, 5, 1)),
            WorkPeriod.LAST_MONTH.dayRange(LocalDate.of(2026, 6, 1))
        )
    }

    @Test
    fun `a window carries both the day range the totals group on and the instants the counts need`() {
        val week = fixture.workStatisticsRepository.window(WorkPeriod.THIS_WEEK, LocalDate.of(2026, 6, 18))

        assertEquals(LocalDate.of(2026, 6, 15).toEpochDay(), week.fromDay)
        assertEquals(LocalDate.of(2026, 6, 22).toEpochDay(), week.toDay)
        assertEquals(7L, week.days.last - week.days.first + 1L)
        assertEquals(CalendarMath.startOfDay(LocalDate.of(2026, 6, 15)), week.fromMillis)
        assertEquals(
            "half-open, so consecutive windows partition the time line",
            CalendarMath.startOfDay(LocalDate.of(2026, 6, 22)),
            week.toMillis
        )
        assertTrue(week.fromMillis < week.toMillis)
    }

    // ---------------------------------------------------------------- the history screen's own reads

    @Test
    fun `the history orders by the run's own total when the screen asks for duration`() = runTest {
        seed()
        val window = fixture.workStatisticsRepository.window(day0..day2)

        val rows = fixture.workStatisticsRepository.history(window, HistorySort.DURATION)

        assertEquals(listOf("s1", "s4", "s2", "s3"), rows.map { it.uuid })
        assertEquals(
            "s1 carries three stretches, s4 the visible hour only, s2 the closed minute of a run still going",
            listOf(2_100L, 120L, 90L, 45L),
            rows.map { it.closedSeconds }
        )
        assertEquals(
            "the open stretch is measured against the clock of the read, and is in no total",
            (fixture.timeBase.wall - instantIn(day2)) / 1_000L,
            rows.single { it.uuid == "s2" }.openSeconds
        )
    }

    @Test
    fun `the history groups runs of one task together when the screen asks for the task`() = runTest {
        seed()
        val window = fixture.workStatisticsRepository.window(day0..day2)

        val rows = fixture.workStatisticsRepository.history(window, HistorySort.TASK)

        assertEquals(
            "one task's runs sit next to each other, newest first inside it",
            listOf("s4", "s3", "s2", "s1"),
            rows.map { it.uuid }
        )
        assertEquals(
            listOf("Answer the mail", "Water the plants", "Write the report", "Write the report"),
            rows.map { it.taskTitle }
        )
        assertTrue(
            "and the two runs of the report task keep their own totals",
            rows.filter { it.taskTitle == "Write the report" }.map { it.closedSeconds }
                .containsAll(listOf(2_100L, 90L))
        )
    }

    @Test
    fun `an entered stretch counts in its run's total and is labelled as entered`() = runTest {
        seed()
        val window = fixture.workStatisticsRepository.window(day0..day2)

        val entered = fixture.workStatisticsRepository.history(window, HistorySort.DATE)
            .single { it.uuid == "s3" }

        assertEquals(45L, entered.closedSeconds)
        assertEquals(1, entered.intervals)
        assertEquals("the list can say which figure was typed in", 1, entered.manual)
        assertEquals(
            "and every measured run says it holds none",
            listOf(0, 0, 0),
            fixture.workStatisticsRepository.history(window, HistorySort.DATE)
                .filter { it.uuid != "s3" }.map { it.manual }
        )
    }

    @Test
    fun `a run whose stretches were all thrown away stays in the history with nothing in it`() = runTest {
        seed()
        fixture.workSessionDao.softDelete("i8", instantIn(day2, 23))
        val window = fixture.workStatisticsRepository.window(day0..day2)

        val row = fixture.workStatisticsRepository.history(window, HistorySort.DATE)
            .single { it.uuid == "s4" }

        assertEquals(
            "the run happened, so the list still shows it — with what is left of it",
            0L,
            row.closedSeconds
        )
        assertEquals(0, row.intervals)
        assertEquals(
            "and the period total is the smaller for it",
            600L + 300L + 1_200L + 90L + 45L,
            fixture.workStatisticsDao.periodSummary(userId, day0, day2 + 1).seconds
        )
    }

    @Test
    fun `the repository's history reads the same rows the dao answers with, filters included`() = runTest {
        seed()
        val window = fixture.workStatisticsRepository.window(day0..day2)
        val now = fixture.timeBase.wall
        val homeId = fixture.categoryDao.getAllCategoriesOnce(userId).first { it.name == "Home" }.id

        val throughRepository = fixture.workStatisticsRepository.history(window, HistorySort.DATE)
        val throughDao = fixture.workSessionDao.historyByDate(userId, window.fromMillis, window.toMillis, now, -1L, -1L)
        val byCategory = fixture.workStatisticsRepository.history(
            window, HistorySort.DATE, categoryId = homeId
        )
        val byTask = fixture.workStatisticsRepository.history(window, HistorySort.DATE, taskId = plants.id)

        assertEquals(throughDao, throughRepository)
        assertEquals(listOf("s3"), byCategory.map { it.uuid })
        assertEquals(listOf("s3"), byTask.map { it.uuid })
        assertEquals(45L, byCategory.single().closedSeconds)
        assertEquals(
            "a filter narrows the list and changes no figure in it",
            throughRepository.single { it.uuid == "s3" }.intervals,
            byCategory.single().intervals
        )
    }

    @Test
    fun `no profile means no history rather than somebody else's`() = runTest {
        seed()
        fixture.sessionRepository.endSession()
        val window = fixture.workStatisticsRepository.window(day0..day2)

        assertTrue(fixture.workStatisticsRepository.history(window, HistorySort.DATE).isEmpty())
        assertTrue(fixture.workStatisticsRepository.intervalsOf("s1").isEmpty())
    }

    @Test
    fun `an expanded entry lists its stretches oldest first and leaves the thrown-away one out`() = runTest {
        seed()

        val expanded = fixture.workStatisticsRepository.intervalsOf("s1")
        val partlyDiscarded = fixture.workStatisticsRepository.intervalsOf("s4")

        assertEquals(listOf("i1", "i2", "i3"), expanded.map { it.uuid })
        assertEquals(listOf(600L, 300L, 1_200L), expanded.map { it.durationSeconds })
        assertEquals(
            "an expansion says which of these the timer measured",
            listOf("TRACKER", "TRACKER", "TRACKER"),
            expanded.map { it.source }
        )
        assertEquals(listOf("i8"), partlyDiscarded.map { it.uuid })
        assertTrue(
            "the deleted stretch is not hidden from the database, only from this list",
            fixture.workSessionDao.findIncludingDeleted("i7")!!.isDeleted
        )
    }

    @Test
    fun `a corrected stretch keeps the figure it was changed from, oldest change first`() = runTest {
        seed()
        val firstStart = instantIn(day0, 9)
        val firstEnd = firstStart + 1_800_000L
        assertEquals(
            AdjustmentResult.Accepted("i2"),
            fixture.workTrackingRepository.correctInterval("i2", firstStart, firstEnd, "measured late")
        )

        // Real time between the two writes, so the correction history has an order to be read in.
        fixture.timeBase.advance(1_000L)
        val secondEnd = firstStart + 2_400_000L
        fixture.workTrackingRepository.correctInterval("i2", firstStart, secondEnd, "ended early")

        val corrections = fixture.workStatisticsRepository.correctionsOf("i2")
        assertEquals(2, corrections.size)
        assertEquals(listOf(firstEnd, secondEnd), corrections.map { it.newEndedAt })
        assertEquals(
            "the second change records what the first one left behind, not what the timer said",
            corrections.first().newEndedAt,
            corrections.last().oldEndedAt
        )
        assertEquals(instantIn(day0, 14), corrections.first().oldStartedAt)
        val row = fixture.workSessionDao.getByUuid("i2")!!
        assertEquals(2_400L, row.durationSeconds)
        assertEquals(WorkSource.TRACKER, row.source)
        assertEquals(
            "and every figure the screens read is built on the corrected numbers",
            closedInRange(day0, day2 + 1).sumOf { it.durationSeconds!! },
            fixture.workStatisticsDao.periodSummary(userId, day0, day2 + 1).seconds
        )
    }

    @Test
    /**
     * The list is a `GROUP BY` over two tables, so Room cannot infer a Flow from its own result and the
     * screen watches the two change signals instead. This is the test that the signal is enough: a write
     * anywhere in the history reaches the list with nothing polling it.
     */
    fun `the history list rebuilds itself when a write lands, without a timer`() = runBlocking {
        seed()
        val window = fixture.workStatisticsRepository.window(day0..day2)

        val lists = rebuildAround(window) {
            fixture.workTrackingRepository.recordManualInterval(
                unclassified, instantIn(day0, 20), instantIn(day0, 20) + 300_000L
            )
        }

        assertEquals(2, lists.size)
        assertEquals(listOf("s4", "s2", "s3", "s1"), lists.first().map { it.uuid })
        val rebuilt = lists.last()
        val newEntry = rebuilt.single { it.intervals == 1 && it.manual == 1 && it.uuid != "s3" }
        assertEquals(
            "the entered run sits where the order the screen asked for puts it, by the clock instant",
            listOf("s4", "s2", newEntry.uuid, "s3", "s1"),
            rebuilt.map { it.uuid }
        )
        assertEquals(300L, newEntry.closedSeconds)
        assertEquals(
            "nothing that was already in the list changed its figure",
            mapOf("s1" to 2_100L, "s2" to 90L, "s3" to 45L, "s4" to 120L),
            rebuilt.filter { it.uuid != newEntry.uuid }
                .associate { it.uuid to it.closedSeconds }
        )
    }

    /**
     * The first two answers of the history Flow, with [write] run only after the list has answered once.
     *
     * `fixture.awaitIdle()` cannot be used in this test: it blocks the thread the collection runs on, so
     * the watcher would never subscribe and the write would land before anything was listening. Waiting on
     * the first answer is both what makes the test work and the proof it needs — the write is known to
     * have happened while the screen was already watching the list.
     */
    private suspend fun rebuildAround(
        window: WorkWindow,
        write: suspend () -> Unit
    ): List<List<WorkHistoryRow>> = coroutineScope {
        val seen = ArrayList<List<WorkHistoryRow>>()
        val firstAnswer = CompletableDeferred<List<WorkHistoryRow>>()
        val twoAnswers = CompletableDeferred<Unit>()
        val watching = launch {
            fixture.workStatisticsRepository.observeHistory(window, HistorySort.DATE)
                .take(2)
                .collect { rows ->
                    seen.add(rows)
                    if (seen.size == 1) firstAnswer.complete(rows) else twoAnswers.complete(Unit)
                }
        }

        withTimeout(5_000L) { firstAnswer.await() }
        write()
        withTimeout(5_000L) { twoAnswers.await() }
        watching.cancel()
        seen.toList()
    }

    /** `EXPLAIN QUERY PLAN` for one statement, as the text SQLite reports it. */
    private fun planOf(sql: String): List<String> {
        val cursor = fixture.database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql")
        return cursor.use { rows ->
            val index = rows.getColumnIndexOrThrow("detail")
            val out = ArrayList<String>()
            while (rows.moveToNext()) out.add(rows.getString(index))
            out
        }
    }
}
