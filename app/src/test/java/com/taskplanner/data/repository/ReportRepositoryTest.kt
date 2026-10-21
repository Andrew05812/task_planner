package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.achievement.AchievementRule
import com.taskplanner.data.achievement.AchievementRules
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.report.GoalMeasure
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportPeriodRead
import com.taskplanner.data.statistics.ChangeDirection
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The report, over real Room.
 *
 * `ReportPeriodTest` already proves the calendar half, so nothing here re-asks what a week costs in
 * milliseconds. What is proved is what only the storage and the pass can settle: which rows a section is
 * allowed to count, which it must refuse, and what the report says when the honest answer is that the schema
 * does not hold the figure.
 *
 * Two conventions the suite inherits from the stage it reports on. The clock is parked at midday on Wednesday
 * 3 June 2026 in Europe/Berlin, so "today" is a day the assertions can name; and a completion whose day matters
 * is written through the dao with its own instant, because [TaskRepository.completeAndArchive] stamps
 * `completed_at` from the device clock rather than from the parked `TimeBase` — Stage-1 code that predates that
 * seam. A period figure read through that write would be measured against the machine's own October rather than
 * against the June being reported.
 */
@RunWith(RobolectricTestRunner::class)
class ReportRepositoryTest {

    private lateinit var fixture: AppFixture
    private lateinit var zone: ZoneId
    private lateinit var user: User
    private var defaultZone: TimeZone = TimeZone.getDefault()
    private var defaultLocale: Locale = Locale.getDefault()

    private val wednesday = LocalDate.of(2026, 6, 3)
    private val tuesday = wednesday.minusDays(1)
    private val monday = wednesday.minusDays(2)
    private val thursday = wednesday.plusDays(1)
    private val userId get() = user.id
    private val repository get() = fixture.reportRepository

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        zone = CalendarMath.systemZone()
        fixture = AppFixture(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    /** Signs the profile in and parks the clock so "today" is the day the assertions name. */
    private suspend fun seed(): User {
        val signed = fixture.signIn()
        user = signed
        fixture.timeBase.shiftTo(at(wednesday, 12, 0))
        return signed
    }

    /**
     * A task with its own created instant and, when the test asks, its own finished one.
     *
     * Naming a finish instant writes the row the way the app's own completion write does — status
     * `COMPLETED`, `completed_at` filled, `archived` and `archived_at` set — because [TaskRepository] finishes
     * and files a task in one statement, and a helper that left the status at `TODO` would be reporting a row
     * that exists nowhere. The exceptions this suite needs are named: a legacy finish with no date on it is
     * asked for by passing the status and no instant, and a row filed away unfinished by passing `archived`
     * with `status = TODO`.
     *
     * The instants themselves are named because a report is a question about a window: a row whose
     * `created_at` came from the machine clock would answer whichever month the build ran in rather than the
     * June being reported.
     */
    private suspend fun task(
        title: String,
        createdOn: LocalDate = wednesday,
        finishedOn: LocalDate? = null,
        finishedAt: Long? = null,
        status: TaskStatus? = null,
        archived: Boolean? = null,
        categoryId: Long? = null,
        groupId: String? = null,
        seriesUuid: String? = null
    ): Task {
        val finished = finishedAt ?: finishedOn?.let { at(it, 11, 0) }
        val effectiveStatus = status ?: if (finished != null) TaskStatus.COMPLETED else TaskStatus.TODO
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title",
                title = title,
                description = "",
                userId = userId,
                status = effectiveStatus,
                archived = archived ?: (finished != null),
                archivedAt = if (archived ?: (finished != null)) finished ?: at(createdOn, 9, 0) else null,
                completedAt = finished,
                categoryId = categoryId,
                groupId = groupId,
                seriesUuid = seriesUuid,
                createdAt = at(createdOn, 8, 0),
                updatedAt = finished ?: at(createdOn, 8, 0)
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private suspend fun work(
        uuid: String,
        on: Task,
        day: LocalDate,
        seconds: Long,
        startedAt: Long = at(day),
        session: String = "session-$uuid"
    ) {
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = uuid,
                userId = on.userId,
                trackingSessionUuid = session,
                taskId = on.id,
                taskUuid = on.uuid,
                startedAt = startedAt,
                endedAt = startedAt + seconds * 1_000L,
                durationSeconds = seconds,
                localDay = day.toEpochDay(),
                utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(startedAt, zone),
                source = WorkSource.TRACKER
            )
        )
    }

    private suspend fun openWork(uuid: String, on: Task, day: LocalDate) {
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = uuid,
                userId = on.userId,
                trackingSessionUuid = "session-$uuid",
                taskId = on.id,
                taskUuid = on.uuid,
                startedAt = at(day),
                endedAt = null,
                durationSeconds = null,
                openMarker = uuid,
                localDay = day.toEpochDay(),
                utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(day), zone),
                source = WorkSource.TRACKER
            )
        )
    }

    private suspend fun createHabit(
        title: String,
        start: LocalDate,
        frequency: HabitFrequency = HabitFrequency.DAILY
    ): String {
        val result = fixture.habitRepository.create(title, frequency, start.toEpochDay())
        assertTrue("$title was not created: $result", result is HabitWriteResult.Saved)
        return (result as HabitWriteResult.Saved).habitUuid
    }

    private suspend fun keep(habitUuid: String, day: LocalDate) {
        val result = fixture.habitRepository.mark(habitUuid, day.toEpochDay())
        assertTrue("$habitUuid on $day was not kept: $result", result is HabitWriteResult.Saved)
    }

    private suspend fun createGoal(
        title: String,
        type: GoalType,
        target: Long,
        from: LocalDate? = monday,
        to: LocalDate? = wednesday,
        taskUuids: List<String> = emptyList()
    ): String {
        val result = fixture.goalRepository.create(
            title = title,
            type = type,
            targetValue = target,
            windowFromDay = from?.toEpochDay(),
            windowToDay = to?.toEpochDay(),
            taskUuids = taskUuids
        )
        assertTrue("$title was not created: $result", result is GoalWriteResult.Saved)
        return (result as GoalWriteResult.Saved).goalUuid
    }

    /** A reading with its own window, written straight to the table the report reads. */
    private suspend fun finding(
        ruleKey: String,
        from: LocalDate,
        to: LocalDate,
        feedback: InsightFeedback? = null,
        current: Boolean = true,
        kind: InsightKind = InsightKind.FACT
    ) {
        fixture.insightDao.insert(
            Insight(
                uuid = Insight.uuidFor(userId, ruleKey, from.toEpochDay(), to.toEpochDay()),
                userId = userId,
                ruleKey = ruleKey,
                kind = kind,
                title = "Title of $ruleKey",
                body = "Body of $ruleKey",
                basis = "3 of 4 in $ruleKey",
                periodFromDay = from.toEpochDay(),
                periodToDay = to.toEpochDay(),
                sampleSize = 4,
                confidence = 60,
                firstSeenAt = at(to, 9, 0),
                lastSeenAt = at(to, 9, 0),
                isCurrent = current,
                feedback = feedback
            )
        )
    }

    /** A grant on the table before the report reads it, with the edition and figures it was written with. */
    private suspend fun grantRow(
        key: String,
        onDay: LocalDate,
        ruleVersion: Int,
        target: Long,
        progress: Long,
        title: String,
        description: String
    ) {
        val owner: AchievementRule = AchievementRules.ALL.first { it.key == key }
        fixture.achievementDao.insertOrIgnore(
            Achievement(
                uuid = Achievement.uuidFor(userId, key),
                userId = userId,
                achievementKey = key,
                category = owner.category,
                title = title,
                description = description,
                unit = owner.unit,
                targetValue = target,
                progressValue = progress,
                earnedDay = onDay.toEpochDay(),
                earnedAt = at(onDay, 12, 0),
                ruleVersion = ruleVersion
            )
        )
    }

    private suspend fun report(
        period: ReportPeriod,
        of: Long = userId,
        inZone: ZoneId = zone
    ) = (repository.reportFor(of, period, inZone) ?: failWith("no report for $of")).also {
        fixture.awaitIdle()
    }

    private fun failWith(message: String): Nothing = throw AssertionError(message)

    /**
     * A range the tests know is well-formed, unwrapped once.
     *
     * [ReportPeriod.range] answers with the reason a range is impossible as readily as with the period, which
     * is what a date picker needs; a test that asks for 2 June to 3 June has no such worry and fails here,
     * loudly, if it ever gets one.
     */
    private fun between(from: LocalDate, to: LocalDate): ReportPeriod {
        val read = ReportPeriod.range(from, to)
        return (read as ReportPeriodRead.Accepted).period
    }

    // -- what a period takes and what it leaves ----------------------------------------------------

    @Test
    fun `a day report takes its first instant and hands the next day its own`() = runBlocking {
        seed()
        task("edge-before", finishedAt = at(tuesday, 23, 59))
        task("edge-start", finishedAt = at(wednesday, 0, 0))
        task("middle", finishedAt = at(wednesday, 13, 0))
        task("edge-end", finishedAt = at(thursday, 0, 0))

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(
            "the day opens at its own midnight and closes at the next, so the two inside belong here",
            2,
            report.completedTasks.count
        )
        assertEquals(listOf(wednesday.toEpochDay()), report.completedTasks.perDay.map { it.localDay })
        assertEquals(2, report.completedTasks.perDay.first().count)
        assertEquals(1, report.dynamics.size)
        assertFalse(
            "the instant the day ends at is the next report's first instant",
            report.window.containsInstant(at(thursday, 0, 0))
        )
        assertEquals(4, report.summary.createdTasks)
        assertEquals(4, report.summary.standing.totalTasks)
    }

    @Test
    fun `the spring-forward day is reported as the twenty-three hours it was`() = runBlocking {
        seed()
        val shift = LocalDate.of(2026, 3, 29)
        task("before the jump", finishedAt = at(shift, 1, 30))
        task("after the jump", finishedAt = at(shift, 3, 30))
        task("last minute", finishedAt = at(shift, 23, 59))

        val report = report(ReportPeriod.Day(shift))

        assertEquals(3, report.completedTasks.count)
        assertEquals(
            "the day the clock jumped is one day long and twenty-three hours short",
            23L * 60 * 60 * 1000,
            report.window.toMillis - report.window.fromMillis
        )
        assertEquals(1, report.window.days)
    }

    @Test
    fun `the fall-back day keeps the hour it lived twice`() = runBlocking {
        seed()
        val shift = LocalDate.of(2026, 10, 25)
        // 01:30 arrives twice that morning — once on summer time, once on winter — and both are inside one day.
        val first = shift.atTime(1, 30).atZone(ZoneOffset.ofTotalSeconds(7_200)).toInstant().toEpochMilli()
        val second = shift.atTime(1, 30).atZone(ZoneOffset.ofTotalSeconds(3_600)).toInstant().toEpochMilli()
        task("first 01:30", finishedAt = first)
        task("second 01:30", finishedAt = second)

        val report = report(ReportPeriod.Day(shift))

        assertTrue("the two instants really are different", second > first)
        assertEquals(
            "and both belong to the 25th, which is twenty-five hours long",
            2,
            report.completedTasks.count
        )
        assertEquals(25L * 60 * 60 * 1000, report.window.toMillis - report.window.fromMillis)
    }

    @Test
    fun `a week across the shift is seven days of unequal hours`() = runBlocking {
        seed()
        val report = report(ReportPeriod.Week(LocalDate.of(2026, 3, 25), DayOfWeek.MONDAY))

        assertEquals(7, report.window.days)
        assertEquals(
            "one dynamics row per day the reader lived, whatever those days measured",
            7,
            report.dynamics.size
        )
        assertEquals(LocalDate.of(2026, 3, 23), report.dynamics.first().date)
        assertEquals(LocalDate.of(2026, 3, 29), report.dynamics.last().date)
    }

    @Test
    fun `the same milliseconds are Wednesday in Berlin and Tuesday in UTC`() = runBlocking {
        seed()
        val shiftDay = LocalDate.of(2026, 3, 28)
        val instant = shiftDay.atTime(23, 30).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        task("late in UTC, early in Berlin", finishedAt = instant)

        assertEquals(
            "Berlin's 29th holds it: the city is still on UTC+1 that minute, and has crossed into the new day",
            1,
            report(ReportPeriod.Day(shiftDay.plusDays(1))).completedTasks.count
        )
        assertEquals(
            "UTC's 28th holds the same milliseconds",
            1,
            report(ReportPeriod.Day(shiftDay), inZone = ZoneOffset.UTC).completedTasks.count
        )
        assertEquals(
            "and UTC's 29th does not hold them at all",
            0,
            report(ReportPeriod.Day(shiftDay.plusDays(1)), inZone = ZoneOffset.UTC).completedTasks.count
        )
    }

    @Test
    fun `a range that names one day is that day`() = runBlocking {
        seed()
        task("on the day", finishedOn = wednesday)
        task("the day before", finishedOn = tuesday)

        val report = report(between(wednesday, wednesday))

        assertEquals(1, report.window.days)
        assertEquals(1, report.completedTasks.count)
        assertEquals(1, report.dynamics.size)
    }

    // -- what the storage can and cannot say -------------------------------------------------------

    @Test
    fun `an empty profile answers with zeroes and with nothing where nothing was measured`() = runBlocking {
        seed()
        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(0, report.completedTasks.count)
        assertEquals(0, report.summary.createdTasks)
        assertEquals(0L, report.workedTime.seconds)
        assertEquals(0, report.workedTime.sessions)
        assertNull(
            "no sessions is not the same claim as sessions of no length",
            report.workedTime.averageSessionSeconds
        )
        assertNull(
            "an account with no tasks has not finished none of anything",
            report.summary.standing.lifetimeCompletionRate
        )
        assertEquals(0, report.summary.activeDays)
        assertEquals(0.0, report.summary.completionsPerDay, 0.0)
        assertTrue(report.categoryActivity.isEmpty())
        assertTrue(report.achievements.grants.isEmpty())
        assertTrue(report.findings.isEmpty())
        assertTrue(report.habits.rows.isEmpty())
        assertTrue(report.goals.rows.isEmpty())
        assertEquals(1, report.dynamics.size)
        assertEquals(0, report.dynamics.first().completions)
        assertEquals(ChangeDirection.FLAT, report.comparison.completions.direction)
        assertNull(report.comparison.completions.fraction)
    }

    @Test
    fun `putting a task away unfinished is not a finished task`() = runBlocking {
        seed()
        task("filed but not done", archived = true, status = TaskStatus.TODO)

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(
            "archiving is not completion, and the report counts the column that says finished",
            0,
            report.completedTasks.count
        )
        assertEquals(1, report.summary.standing.archivedTasks)
        assertEquals(
            "and a filed row is not open work either: it left both figures on its own terms",
            0,
            report.summary.standing.openTasks
        )
        assertEquals(1, report.summary.standing.totalTasks)
    }

    @Test
    fun `a finish taken back leaves the period it was counted in`() = runBlocking {
        seed()
        val row = task("taken back", finishedOn = wednesday)
        assertEquals(1, report(ReportPeriod.Day(wednesday)).completedTasks.count)

        fixture.taskRepository.restoreTask(row.id)
        val after = report(ReportPeriod.Day(wednesday))

        assertEquals(
            "un-finishing erases the instant, and with it the period's claim that this happened",
            0,
            after.completedTasks.count
        )
        assertEquals(1, after.summary.standing.openTasks)
        assertEquals(0, after.summary.standing.archivedTasks)
    }

    @Test
    fun `work deleted out of the account is not work the report counts`() = runBlocking {
        seed()
        task("kept", finishedOn = wednesday)
        val gone = task("deleted", finishedOn = wednesday)
        fixture.taskRepository.softDeleteTasks(listOf(gone.id))

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(1, report.completedTasks.count)
        assertEquals(1, report.summary.standing.totalTasks)
        assertEquals(1, report.summary.standing.archivedTasks)
        assertEquals(1, report.completedTasks.perDay.first().count)
    }

    @Test
    fun `a finish nobody can date is stated instead of dropped`() = runBlocking {
        seed()
        task("legacy finish", status = TaskStatus.COMPLETED)

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(
            "no instant means no day, so it cannot be counted inside this one",
            0,
            report.completedTasks.count
        )
        assertEquals(1, report.completedTasks.undatable)
        assertEquals(1, report.summary.undatedCompletions)
        assertEquals(
            "and the account's own standing still knows the work was finished",
            1.0f,
            report.summary.standing.lifetimeCompletionRate!!,
            0.0f
        )
    }

    @Test
    fun `two instances of one series are two pieces of work`() = runBlocking {
        seed()
        task("tue instance", finishedOn = tuesday, seriesUuid = "series-daily")
        task("wed instance", finishedOn = wednesday, seriesUuid = "series-daily")

        val report = report(between(tuesday, wednesday))

        assertEquals(2, report.completedTasks.count)
        assertEquals(
            "each day keeps its own instance: the trend is drawn by day, not deduplicated by series",
            listOf(1, 1),
            report.dynamics.map { it.completions }
        )
    }

    @Test
    fun `a task inside a group counts once, wherever the group puts it`() = runBlocking {
        seed()
        val group = fixture.addGroup(userId, "Thesis")
        task("chapter one", finishedOn = wednesday, groupId = group.uuid)
        task("chapter two", finishedOn = wednesday, groupId = group.uuid)

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(2, report.completedTasks.count)
        assertEquals(2, report.summary.createdTasks)
        assertEquals(
            "grouping is a listing, not a doubling: the category section sees the same two finishes",
            2,
            report.categoryActivity.sumOf { it.completions }
        )
    }

    // -- measured time ------------------------------------------------------------------------------

    @Test
    fun `a stretch still running contributes no time to a report`() = runBlocking {
        seed()
        val row = task("tracked")
        work("closed", row, wednesday, 1_800)
        openWork("running", row, wednesday)

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(1_800L, report.workedTime.seconds)
        assertEquals(
            "the open interval has no length yet, and a total that guessed one would change value every second",
            1,
            report.workedTime.intervals
        )
        assertEquals(1, report.workedTime.sessions)
    }

    @Test
    fun `a paused session is one session, and the average says so`() = runBlocking {
        seed()
        val row = task("paused twice")
        work("first half", row, wednesday, 1_200, session = "one-session")
        work("second half", row, wednesday, 600, session = "one-session")

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(1_800L, report.workedTime.seconds)
        assertEquals(2, report.workedTime.intervals)
        assertEquals(1, report.workedTime.sessions)
        assertEquals(
            "the average is over stretches of working, not over the rows a pause produced",
            1_800.0,
            report.workedTime.averageSessionSeconds!!,
            0.0
        )
    }

    @Test
    fun `time is counted on the day it was measured, not the day it ended`() = runBlocking {
        seed()
        val row = task("overnight", createdOn = tuesday)
        // Measured 23:50 Tuesday into 00:20 Wednesday: the interval names Tuesday as its day, at measurement.
        work(
            "overnight-interval", row, day = tuesday, seconds = 1_800,
            startedAt = tuesday.atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        )

        assertEquals(1_800L, report(ReportPeriod.Day(tuesday)).workedTime.seconds)
        assertEquals(
            "the part past midnight belongs to the day the stretch started on, which is what the row says",
            0L,
            report(ReportPeriod.Day(wednesday)).workedTime.seconds
        )
    }

    @Test
    fun `tracked time and finished work are different figures for one category`() = runBlocking {
        seed()
        val tracked = fixture.addCategory(userId, "Research")
        val finished = fixture.addCategory(userId, "Admin")
        val reading = task("read a paper", categoryId = tracked)
        work("reading", reading, wednesday, 3_600)
        task("file a form", finishedOn = wednesday, categoryId = finished)
        task("answer mail", finishedOn = wednesday, categoryId = finished)

        val rows = report(ReportPeriod.Day(wednesday)).categoryActivity

        assertEquals(2, rows.size)
        assertEquals("Research", rows.first().categoryName)
        assertEquals(3_600L, rows.first().seconds)
        assertEquals(
            "time tracked here, nothing finished here: the zero is a fact, not a gap",
            0,
            rows.first().completions
        )
        assertEquals(0L, rows.last().seconds)
        assertEquals(2, rows.last().completions)
        assertEquals(2, rows.sumOf { it.completions })
    }

    // -- goals --------------------------------------------------------------------------------------

    @Test
    fun `a goal's reached figure is the standing one, and it can move back`() = runBlocking {
        seed()
        val one = task("goal work one", finishedOn = wednesday)
        task("goal work two", finishedOn = wednesday)
        createGoal("Two things", GoalType.TASK_COUNT, target = 2)

        val row = report(ReportPeriod.Day(wednesday)).goals.rows.first()

        assertTrue(row.standing.reached)
        assertEquals(1, report(ReportPeriod.Day(wednesday)).goals.reachedStanding)
        assertEquals(2L, row.movedInWindow)
        assertEquals(GoalMeasure.TASKS, row.measure)

        fixture.taskRepository.restoreTask(one.id)
        val after = report(ReportPeriod.Day(wednesday)).goals.rows.first()

        assertFalse(
            "the schema records no event for reaching an aim, so the figure is recomputed and can fall",
            after.standing.reached
        )
        assertEquals(1L, after.movedInWindow)
        assertEquals(0, report(ReportPeriod.Day(wednesday)).goals.reachedStanding)
    }

    @Test
    fun `a goal whose window closed before the period moved nothing inside it`() = runBlocking {
        seed()
        task("monday work", finishedOn = monday)
        createGoal("Monday only", GoalType.TASK_COUNT, target = 1, from = monday, to = monday)

        val row = report(ReportPeriod.Day(wednesday)).goals.rows.first()

        assertTrue("the aim stands where its own window left it", row.standing.reached)
        assertEquals(
            "and none of that work happened inside the day being reported",
            0L,
            row.movedInWindow
        )
    }

    @Test
    fun `a time goal states its movement in seconds`() = runBlocking {
        seed()
        val row = task("tracked for the aim")
        work("stretch", row, wednesday, 900)
        createGoal("Fifteen minutes", GoalType.TIME_SPENT, target = 900)

        val goal = report(ReportPeriod.Day(wednesday)).goals.rows.first()

        assertEquals(GoalMeasure.SECONDS, goal.measure)
        assertEquals(900L, goal.movedInWindow)
        assertEquals(900L, goal.standing.counted)
    }

    @Test
    fun `a set goal has no in-window figure the schema can state`() = runBlocking {
        seed()
        val done = task("linked and done", finishedOn = wednesday)
        val open = task("linked and open")
        createGoal("The named pair", GoalType.TASK_SET, target = 2, taskUuids = listOf(done.uuid, open.uuid))

        val goal = report(ReportPeriod.Day(wednesday)).goals.rows.first()

        assertEquals(GoalMeasure.SET_STATE, goal.measure)
        assertNull(
            "a link's membership is not dated against the finish, so the period has no honest number here",
            goal.movedInWindow
        )
        assertEquals(1, goal.standing.completedLinks)
    }

    @Test
    fun `a goal the reader put away still stands in the report`() = runBlocking {
        seed()
        task("archived aim work", finishedOn = wednesday)
        val goal = createGoal("Finished with", GoalType.TASK_COUNT, target = 1)
        fixture.goalRepository.archive(goal)

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(1, report.goals.rows.size)
        assertTrue("archiving is not deleting: the aim and its figure stay", report.goals.rows.first().standing.reached)
        assertEquals(1, report.goals.standing)
    }

    // -- habits --------------------------------------------------------------------------------------

    @Test
    fun `a habit never kept inside the period is absent rather than a row of zeroes`() = runBlocking {
        seed()
        val active = createHabit("Run", wednesday.minusDays(10))
        createHabit("Never kept", wednesday.minusDays(10))
        keep(active, wednesday)

        val report = report(ReportPeriod.Day(wednesday))

        assertEquals(1, report.habits.rows.size)
        assertEquals("Run", report.habits.rows.first().habit.title)
        assertEquals(1, report.habits.keptCount)
        assertEquals(
            "the profile's whole habit list travels with it, so the page can say one of two",
            2,
            report.habits.standing
        )
        assertEquals(1, report.habits.marks)
        assertEquals(1, report.dynamics.first().habitMarks)
    }

    @Test
    fun `a habit's series is only as long as the days the report was given`() = runBlocking {
        seed()
        val habit = createHabit("Stretch", wednesday.minusDays(9))
        (0L..6L).forEach { back -> keep(habit, wednesday.minusDays(back)) }

        val week = report(between(wednesday.minusDays(6), wednesday))
            .habits.rows.first().figures
        val three = report(between(wednesday.minusDays(2), wednesday))
            .habits.rows.first().figures

        assertEquals(7, week.marks)
        assertEquals(7, week.streak.current)
        assertEquals(
            "a window shorter than the run cannot see the days before it, so it states what it holds",
            3,
            three.marks
        )
        assertEquals(3, three.streak.current)
        assertEquals(3, three.periodDays)
    }

    @Test
    fun `a mark taken back leaves the period that counted it`() = runBlocking {
        seed()
        val habit = createHabit("Walk", wednesday.minusDays(4))
        keep(habit, wednesday)
        assertEquals(1, report(ReportPeriod.Day(wednesday)).habits.marks)

        fixture.habitRepository.takeBack(habit, wednesday.toEpochDay())
        val after = report(ReportPeriod.Day(wednesday))

        assertEquals(0, after.habits.marks)
        assertTrue(
            "and with no mark inside the window the habit has no row at all",
            after.habits.rows.isEmpty()
        )
    }

    @Test
    fun `a report about a month that has passed states the series that month ended on`() = runBlocking {
        seed()
        val habit = createHabit("April run", LocalDate.of(2026, 3, 1))
        (23L..30L).forEach { day -> keep(habit, LocalDate.of(2026, 4, day.toInt())) }

        val figures = report(ReportPeriod.Month(YearMonth.of(2026, 4))).habits.rows.first().figures

        // Read in June with the series anchored on the June day, this row would state a run of nought: the
        // walk back starts on the day it is given, and nobody kept 3 June. The report gives it the period's
        // last day instead, which is the day the reader is asking about.
        assertEquals(LocalDate.of(2026, 4, 30).toEpochDay(), figures.today)
        assertEquals(8, figures.marks)
        assertEquals(8, figures.streak.current)
        assertEquals(8, figures.streak.best)
        assertEquals(8, figures.period.kept)
        assertEquals(30, figures.period.asked)
    }

    @Test
    fun `a habit kept only before its own start day is not a habit kept in the period`() = runBlocking {
        seed()
        // A mark made while the habit began earlier, then the start moved forward past it: the card stops
        // counting that day, and the report has to stop counting it too.
        val habit = createHabit("Moved start", LocalDate.of(2026, 5, 20))
        keep(habit, LocalDate.of(2026, 5, 25))
        fixture.habitRepository.edit(
            habitUuid = habit,
            title = "Moved start",
            frequency = HabitFrequency.DAILY,
            startDay = LocalDate.of(2026, 6, 2).toEpochDay(),
            daysOfWeek = emptyList(),
            timesPerWeek = null,
            endDay = null,
            remindMinuteOfDay = null
        )

        val report = report(between(LocalDate.of(2026, 5, 20), wednesday))

        assertTrue(
            "the one mark in the window predates the habit's own start, so the report lists nothing",
            report.habits.rows.isEmpty()
        )
        assertEquals(0, report.habits.marks)
        assertEquals(1, report.habits.standing)
        assertEquals(0, report.dynamics.sumOf { it.habitMarks })
    }

    @Test
    fun `work whose category row is gone falls in the same bucket as its measured time`() = runBlocking {
        seed()
        val categoryId = fixture.addCategory(userId, "Gone")
        val row = task("orphan finish", finishedOn = wednesday, categoryId = categoryId)
        work("orphan stretch", row, wednesday, 600)
        // The category itself is gone while the task keeps pointing at it — the state the merged section has
        // to place once, not twice.
        fixture.database.openHelper.writableDatabase
            .execSQL("DELETE FROM categories WHERE id = $categoryId")

        val rows = report(ReportPeriod.Day(wednesday)).categoryActivity

        assertEquals(
            "one bucket, not a half-empty pair: both reads group on the joined category id",
            listOf(null),
            rows.map { it.categoryId }
        )
        assertEquals(600L, rows.first().seconds)
        assertEquals(1, rows.first().completions)
        assertNull(rows.first().categoryName)
    }

    // -- achievements ----------------------------------------------------------------------------------

    @Test
    fun `an achievement in the report is the row that was written at the time`() = runBlocking {
        seed()
        // A grant earned under an older edition of the rule, with the wording and figures that edition used.
        grantRow(
            key = "tasks_10", onDay = wednesday, ruleVersion = 1, target = 8, progress = 8,
            title = "Eight done", description = "Earned with eight finished."
        )
        (1..10).map { task("task $it") }
        fixture.achievementRepository.refresh()

        val grants = report(ReportPeriod.Day(wednesday)).achievements.grants

        val stored = grants.first { it.achievementKey == "tasks_10" }
        assertEquals(
            "the report prints the memory, not today's arithmetic",
            listOf(1, 8L, 8L, "Eight done", "Earned with eight finished."),
            listOf(
                stored.ruleVersion, stored.targetValue, stored.progressValue,
                stored.title, stored.description
            )
        )
        assertEquals(
            "and the pass that ran afterwards still refuses to write a second one",
            1,
            grants.count { it.achievementKey == "tasks_10" }
        )
        assertEquals(
            "the section's own count is the rows it lists",
            grants.size,
            report(ReportPeriod.Day(wednesday)).achievements.granted
        )
    }

    @Test
    fun `a grant on the day before is not a grant in this report`() = runBlocking {
        seed()
        grantRow(
            key = "first_task", onDay = tuesday, ruleVersion = 2, target = 1, progress = 1,
            title = "First done", description = "Earned with one finished."
        )

        assertEquals(0, report(ReportPeriod.Day(wednesday)).achievements.granted)
        val range = report(between(tuesday, wednesday)).achievements
        assertEquals(1, range.granted)
        assertEquals(tuesday.toEpochDay(), range.grants.first().earnedDay)
    }

    // -- findings -------------------------------------------------------------------------------------

    @Test
    fun `findings are the readings whose own window closed inside the period`() = runBlocking {
        seed()
        finding("closes_today", from = monday, to = wednesday)
        finding("closed_before", from = monday, to = tuesday)
        finding("rejected_today", from = tuesday, to = wednesday, feedback = InsightFeedback.REJECTED)
        finding("retired_today", from = monday, to = wednesday, current = false)
        finding("hidden_today", from = tuesday, to = wednesday, feedback = InsightFeedback.HIDDEN)

        val findings = report(ReportPeriod.Day(wednesday)).findings

        assertEquals(
            "what closed inside the period, and nothing the reader already told the app was wrong",
            listOf("closes_today", "retired_today"),
            findings.map { it.ruleKey }
        )
        assertEquals(
            "a retired reading still counts: the report is about the period, not about today's list",
            listOf(true, false),
            findings.map { it.current }
        )
        assertEquals(monday.toEpochDay(), findings.first().periodFromDay)
        assertEquals(4, findings.first().sampleSize)
        assertEquals(60, findings.first().confidence)
        assertEquals(InsightKind.FACT, findings.first().kind)
        assertEquals("Body of closes_today", findings.first().body)
    }

    @Test
    fun `a month report holds the weekly readings that closed in it`() = runBlocking {
        seed()
        finding("closes_first", from = wednesday.minusDays(10), to = wednesday.minusDays(2))
        finding("closes_second", from = wednesday.minusDays(6), to = wednesday)
        finding("closed_in_may", from = wednesday.minusDays(15), to = wednesday.minusDays(11))

        val findings = report(ReportPeriod.Month(YearMonth.of(2026, 6))).findings

        assertEquals(
            "two readings closed in June; the one that closed in May is a May report's business",
            listOf("closes_first", "closes_second"),
            findings.map { it.ruleKey }
        )
    }

    // -- comparison -------------------------------------------------------------------------------------

    @Test
    fun `the comparison reads the period before and states both numbers`() = runBlocking {
        seed()
        task("tuesday one", finishedOn = tuesday)
        task("wednesday one", finishedOn = wednesday)
        task("wednesday two", finishedOn = wednesday)

        val comparison = report(ReportPeriod.Day(wednesday)).comparison

        assertEquals(tuesday, comparison.window.lastDate)
        assertEquals(2L, comparison.completions.current)
        assertEquals(1L, comparison.completions.previous)
        assertEquals(ChangeDirection.UP, comparison.completions.direction)
        assertEquals(1.0, comparison.completions.fraction!!, 0.0)
    }

    @Test
    fun `an increase over an empty period has no percentage to print`() = runBlocking {
        seed()
        task("first ever finish", finishedOn = wednesday)

        val comparison = report(ReportPeriod.Day(wednesday)).comparison

        assertEquals(1L, comparison.completions.current)
        assertEquals(0L, comparison.completions.previous)
        assertNull(
            "any rise over zero is infinite, so the honest answer is that there is no base",
            comparison.completions.fraction
        )
        assertEquals(ChangeDirection.UP, comparison.completions.direction)
    }

    @Test
    fun `the comparison counts measured time, marks and grants too`() = runBlocking {
        seed()
        val yesterday = task("yesterday's work", createdOn = tuesday)
        work("yesterday stretch", yesterday, tuesday, 3_000)
        val today = task("today's work")
        work("today stretch", today, wednesday, 1_500)
        val habit = createHabit("Read", wednesday.minusDays(20))
        keep(habit, tuesday)
        keep(habit, wednesday)
        grantRow(
            key = "first_task", onDay = tuesday, ruleVersion = 2, target = 1, progress = 1,
            title = "First done", description = "Earned with one finished."
        )

        val comparison = report(ReportPeriod.Day(wednesday)).comparison

        assertEquals(1_500L, comparison.workedSeconds.current)
        assertEquals(3_000L, comparison.workedSeconds.previous)
        assertEquals(ChangeDirection.DOWN, comparison.workedSeconds.direction)
        assertEquals(-0.5, comparison.workedSeconds.fraction!!, 0.0)
        assertEquals(1L, comparison.habitMarks.current)
        assertEquals(1L, comparison.habitMarks.previous)
        // The grant the report is asked about landed on Tuesday, so the day it covers holds none and the day
        // before it holds one: a fall to zero has a figure, and it is minus a hundred percent.
        assertEquals(0L, comparison.achievements.current)
        assertEquals(1L, comparison.achievements.previous)
        assertEquals(ChangeDirection.DOWN, comparison.achievements.direction)
        assertEquals(-1.0, comparison.achievements.fraction!!, 0.0)
    }

    // -- one profile, one snapshot ---------------------------------------------------------------------

    @Test
    fun `a report names one profile's work and nothing else`() = runBlocking {
        seed()
        (1..3).map { task("mine $it", finishedOn = wednesday) }
        val other = fixture.signInAs(User(email = "other@example.com", name = "Other"))
        (1..5).map { number ->
            fixture.taskDao.insertTask(
                Task(
                    uuid = "theirs-$number", title = "theirs $number", description = "", userId = other.id,
                    status = TaskStatus.COMPLETED, completedAt = at(wednesday, 11, 0),
                    createdAt = at(wednesday, 8, 0), updatedAt = at(wednesday, 11, 0)
                )
            )
        }
        val owner = AchievementRules.ALL.first { it.key == "tasks_10" }
        fixture.achievementDao.insertOrIgnore(
            Achievement(
                uuid = Achievement.uuidFor(other.id, "tasks_10"),
                userId = other.id, achievementKey = "tasks_10",
                category = owner.category, title = "Theirs", description = "Not mine.",
                unit = owner.unit, targetValue = 10, progressValue = 10,
                earnedDay = wednesday.toEpochDay(), earnedAt = at(wednesday, 12, 0)
            )
        )

        val mine = report(ReportPeriod.Day(wednesday), of = userId)
        val theirs = report(ReportPeriod.Day(wednesday), of = other.id)

        assertEquals(3, mine.completedTasks.count)
        assertEquals(5, theirs.completedTasks.count)
        assertTrue("the other profile's grant does not appear here", mine.achievements.grants.isEmpty())
        assertEquals(1, theirs.achievements.granted)
        assertEquals(3, mine.summary.standing.totalTasks)
        assertEquals(5, theirs.summary.standing.totalTasks)
    }

    @Test
    fun `switching the account cannot mix two profiles into one report`() = runBlocking {
        seed()
        task("mine", finishedOn = wednesday)
        val other = fixture.signInAs(User(email = "second@example.com", name = "Second"))
        fixture.taskDao.insertTask(
            Task(
                uuid = "theirs", title = "theirs", description = "", userId = other.id,
                status = TaskStatus.COMPLETED,
                completedAt = at(wednesday, 11, 0), createdAt = at(wednesday, 8, 0),
                updatedAt = at(wednesday, 11, 0)
            )
        )
        // The signed-in account is now the second one, while the report is asked for the first.
        assertEquals(other.id, fixture.sessionRepository.currentUserId())

        val report = report(ReportPeriod.Day(wednesday), of = userId)

        assertEquals(
            "every section resolves through the id it was given: none reaches for the account on screen",
            listOf(1L, 1L, 1L, 1L),
            listOf(
                report.summary.createdTasks.toLong(),
                report.summary.completedTasks.toLong(),
                report.summary.standing.totalTasks.toLong(),
                report.dynamics.sumOf { it.completions }.toLong()
            )
        )
    }

    @Test
    fun `the same question twice gives back the same report`() = runBlocking {
        seed()
        (1..4).map { task("task $it") }
        val row = task("tracked")
        work("stretch", row, wednesday, 600)
        val habit = createHabit("Read", wednesday.minusDays(3))
        keep(habit, wednesday)
        finding("week", from = wednesday.minusDays(6), to = wednesday)
        fixture.achievementRepository.refresh()

        val period = ReportPeriod.Day(wednesday)
        val first = report(period)
        val again = report(period)

        assertEquals(
            "identical data, identical parameters, identical report — sections, rows and order alike",
            first,
            again
        )
        assertEquals(first.dynamics.map { it.date }, again.dynamics.map { it.date })
        assertEquals(first.findings.map { it.ruleKey }, again.findings.map { it.ruleKey })
        assertEquals(
            "the report's own instant is carried, so a re-read says when it was assembled",
            at(wednesday, 12, 0),
            first.window.generatedAt
        )
    }

    @Test
    fun `a report without a signed-in profile is not invented`() = runBlocking {
        seed()
        task("mine")
        fixture.sessionRepository.endSession()

        assertNull(repository.report(ReportPeriod.Day(wednesday), zone))
    }

    @Test
    fun `an open report re-reads itself when the work moves underneath it`() = runBlocking {
        seed()
        val emissions = Collections.synchronizedList(mutableListOf<Int>())
        val collecting = launch {
            repository.observe(ReportPeriod.Day(wednesday), zone)
                .filterNotNull()
                .collect { emissions.add(it.completedTasks.count) }
        }
        awaitUntil { emissions.isNotEmpty() }
        assertEquals("the first emission answers the empty day", listOf(0), snapshot(emissions))

        task("finished while the report was open", finishedOn = wednesday)
        fixture.awaitIdle()
        awaitUntil { emissions.size >= 2 }

        assertEquals(
            "§10.2 forbids a report that keeps showing yesterday's answer after a completion",
            listOf(0, 1),
            snapshot(emissions)
        )
        collecting.cancel()
    }

    /** Waits for the flow's own threads to deliver, with a ceiling rather than a fixed sleep. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun snapshot(values: List<Int>): List<Int> = synchronized(values) { values.toList() }
}
