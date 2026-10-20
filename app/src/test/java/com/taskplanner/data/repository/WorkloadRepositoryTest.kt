package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.insight.DayLoad
import com.taskplanner.data.insight.PriorityLoad
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
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
 * The overload read (§8): what the days ahead are asked for, against the days behind that were measured.
 *
 * The fold itself is [com.taskplanner.data.insight.WorkloadMathTest]'s business, over a list of rows built in
 * memory. What is proved here is the read around it: that it asks the calendar's own questions of real Room,
 * that it answers them in the reader's days rather than in fixed-length spans, and that the figures it hands
 * the panel are the figures a reading is built from. Three claims hold the class together.
 *
 *  * **Only work that is still ahead is load.** A completed, archived or thrown-away row is not a demand on
 *    Thursday, whatever date it still carries.
 *  * **An unknown length is not invented.** A deadline nobody estimated puts a task on a day and no minutes on
 *    it, and the window says so — §8 asks for the caveat to be part of the figure rather than a footnote.
 *  * **Nothing here touches the work.** Automatic rescheduling and automatic priority changes are forbidden, so
 *    a read that issues no write is the guarantee, and the signature test keeps it that way.
 *
 * The clock is parked at midday Wednesday 3 June 2026 in Europe/Berlin, as everywhere in this suite, so every
 * day the read names is a day the assertions can write out.
 */
@RunWith(RobolectricTestRunner::class)
class WorkloadRepositoryTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private lateinit var zone: ZoneId
    private var userId: Long = -1L

    private val repository get() = fixture.workloadRepository

    private val wednesday = LocalDate.of(2026, 6, 3)
    private val thursday = LocalDate.of(2026, 6, 4)
    private val friday = LocalDate.of(2026, 6, 5)
    private val saturday = LocalDate.of(2026, 6, 6)
    private val today get() = wednesday.toEpochDay()

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

    /** Signs the profile in and parks the clock on the day every window below is counted from. */
    private suspend fun seed() {
        userId = fixture.signIn().id
        fixture.timeBase.shiftTo(at(wednesday, 12, 0))
    }

    private suspend fun blockTask(
        title: String,
        start: LocalDate,
        end: LocalDate = start,
        startHour: Int = 10,
        endHour: Int = 12,
        priority: Priority = Priority.MEDIUM
    ): Long = fixture.taskDao.insertTask(
        Task(
            title = title,
            description = "",
            userId = userId,
            startTime = at(start, startHour),
            endTime = at(end, endHour),
            priority = priority
        )
    )

    private suspend fun deadlineTask(
        title: String,
        due: LocalDate,
        estimate: Int? = null,
        priority: Priority = Priority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO,
        completedAt: Long? = null,
        archived: Boolean = false,
        isDeleted: Boolean = false
    ): Long = fixture.taskDao.insertTask(
        Task(
            title = title,
            description = "",
            userId = userId,
            dueDate = at(due, 17, 0),
            estimatedMinutes = estimate,
            priority = priority,
            status = status,
            completedAt = completedAt,
            archived = archived,
            isDeleted = isDeleted
        )
    )

    /**
     * A measured stretch of work on one of the days behind the window.
     *
     * It hangs off its own undated task, because an interval has to belong to a row and the day being measured
     * must not also arrive as load — the baseline and the window are two different questions.
     */
    private suspend fun worked(day: LocalDate, minutes: Long) {
        val taskId = fixture.taskDao.insertTask(
            Task(title = "Logged on $day", description = "", userId = userId)
        )
        val task = fixture.taskDao.getTaskById(taskId)!!
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = "interval-$day-$minutes",
                userId = userId,
                trackingSessionUuid = "track-$day",
                taskId = task.id,
                taskUuid = task.uuid,
                startedAt = at(day, 9),
                endedAt = at(day, 9) + minutes * 60_000L,
                durationSeconds = minutes * 60L,
                localDay = day.toEpochDay(),
                utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(day, 9)),
                source = WorkSource.TRACKER
            )
        )
    }

    /** Every row the read could be accused of changing, as one string. */
    private suspend fun workSignature(): String = with(fixture) {
        val tasks = taskDao.getAllRowsOnce(userId).joinToString(";") { task ->
            "${task.dueDate}/${task.startTime}/${task.endTime}/${task.priority}/${task.status}" +
                "/${task.estimatedMinutes}/${task.updatedAt}/${task.completedAt}"
        }
        val intervals = workSessionDao.getAllForUser(userId).joinToString(";") { interval ->
            "${interval.startedAt}/${interval.endedAt}/${interval.durationSeconds}"
        }
        "tasks=$tasks|intervals=$intervals"
    }

    private suspend fun read(from: LocalDate, to: LocalDate): WorkloadRead =
        repository.read(from.toEpochDay(), to.toEpochDay()).also { fixture.awaitIdle() }

    private suspend fun upcoming(): WorkloadRead =
        repository.upcoming().also { fixture.awaitIdle() }

    // -- what the window holds ------------------------------------------------------------------------

    @Test
    fun `the read folds the window's dated work onto its days and totals it`() = runBlocking {
        seed()
        blockTask("Draft", thursday)
        deadlineTask("Review", friday, estimate = 45)

        val result = read(wednesday, saturday)

        assertEquals(today, result.fromDay)
        assertEquals(saturday.toEpochDay(), result.toDay)
        assertEquals(
            "one row per day the window holds, oldest first",
            listOf(thursday.toEpochDay(), friday.toEpochDay()),
            result.days.map { it.epochDay }
        )
        assertEquals(listOf(120, 45), result.days.map { it.minutes })
        assertEquals(listOf(1, 1), result.days.map { it.tasks })
        assertEquals(165, result.totalMinutes)
        assertEquals(2, result.totalTasks)
    }

    @Test
    fun `a window of stated blocks says its hours are measured and its days are not approximate`() =
        runBlocking {
            seed()
            blockTask("Draft", thursday, endHour = 11)
            blockTask("Call", friday, startHour = 14, endHour = 15)

            val result = read(wednesday, saturday)

            assertFalse("every minute here is one the reader typed out", result.approximate)
            assertEquals(0, result.lengthUnknownTasks)
            assertFalse(result.days.any { it.approximate })
        }

    @Test
    fun `one estimated deadline is enough to make the whole window an estimate`() = runBlocking {
        seed()
        blockTask("Draft", thursday, endHour = 11)
        deadlineTask("Review", friday, estimate = 30)

        assertTrue(
            "the panel has to say its hours are approximate, and one guessed length in the window is enough",
            read(wednesday, saturday).approximate
        )
    }

    @Test
    fun `a deadline nobody estimated is a day asked for without a claimed length`() = runBlocking {
        seed()
        deadlineTask("Review", friday)

        val result = read(wednesday, saturday)

        assertEquals(
            "the row is on the day, and nothing is said about how long it takes",
            listOf(DayLoad(friday.toEpochDay(), minutes = 0, tasks = 1, approximate = true)),
            result.days
        )
        assertEquals(1, result.lengthUnknownTasks)
        assertEquals("a task with no minutes is still a task", 1, result.totalTasks)
        assertEquals(0, result.totalMinutes)
    }

    @Test
    fun `the priority split counts the rows the window holds and none of the others`() = runBlocking {
        seed()
        deadlineTask("Urgent", thursday, priority = Priority.HIGH)
        blockTask("Draft", thursday, endHour = 11)
        deadlineTask("Someday", wednesday.plusDays(40), priority = Priority.LOW)

        val result = read(wednesday, saturday)

        assertEquals(PriorityLoad(high = 1, medium = 1, low = 0), result.priorities)
        assertEquals(2, result.totalTasks)
    }

    @Test
    fun `work that is finished, archived or thrown away is not a demand on the days ahead`() = runBlocking {
        seed()
        deadlineTask("Done", thursday, status = TaskStatus.COMPLETED, completedAt = at(wednesday, 9))
        deadlineTask("Filed", friday, archived = true)
        deadlineTask("Gone", saturday, isDeleted = true)

        val result = read(wednesday, saturday)

        assertTrue("the window holds nothing that still asks for time: ${result.days}", result.days.isEmpty())
        assertEquals(0, result.totalTasks)
        assertEquals(PriorityLoad(), result.priorities)
    }

    @Test
    fun `the read stops at the bound it was given`() = runBlocking {
        seed()
        deadlineTask("Inside", friday)
        deadlineTask("Later", wednesday.plusDays(20))

        val result = read(wednesday, saturday)

        assertEquals(listOf(friday.toEpochDay()), result.days.map { it.epochDay })
        assertEquals("the row past the bound is neither load nor a task here", 1, result.totalTasks)
    }

    @Test
    fun `upcoming starts on the profile's today and reaches a fortnight ahead`() = runBlocking {
        seed()
        deadlineTask("Today", wednesday)
        deadlineTask("On the bound", wednesday.plusDays(14))
        deadlineTask("Beyond it", wednesday.plusDays(20))

        val result = upcoming()

        assertEquals(today, result.fromDay)
        assertEquals(today + 14, result.toDay)
        assertEquals(
            listOf(wednesday.toEpochDay(), wednesday.plusDays(14).toEpochDay()),
            result.days.map { it.epochDay }
        )
        assertEquals(2, result.totalTasks)
    }

    // -- the days behind that the window is judged against ---------------------------------------------

    @Test
    fun `the baseline is the average of the days the profile was actually measured on`() = runBlocking {
        seed()
        worked(wednesday.minusDays(1), 60)
        worked(wednesday.minusDays(3), 120)
        worked(wednesday.minusDays(10), 90)

        val result = read(wednesday, saturday)

        assertEquals("three measured days", 3, result.measuredDays)
        assertEquals(
            "270 minutes over three days, and the empty days between them are not part of the average",
            90,
            result.baselineDailyMinutes
        )
    }

    @Test
    fun `a profile with no measured work has no baseline to be heavy against`() = runBlocking {
        seed()
        deadlineTask("Review", friday, estimate = 300)

        val result = read(wednesday, saturday)

        assertEquals(0, result.measuredDays)
        assertEquals(
            "no measured day means no average, and a read that invented one would be guessing twice",
            0,
            result.baselineDailyMinutes
        )
    }

    @Test
    fun `the baseline stops at the window's first day, so the days ahead are never their own comparison`() =
        runBlocking {
            seed()
            worked(wednesday.minusDays(1), 60)
            worked(thursday, 600)

            val result = read(wednesday, saturday)

            assertEquals(
                "the six hundred hours planned for Thursday are load, not a record of work done",
                1,
                result.measuredDays
            )
            assertEquals(60, result.baselineDailyMinutes)
        }

    @Test
    fun `a read with no profile signed in holds nothing and names the window it was asked for`() = runBlocking {
        fixture.timeBase.shiftTo(at(wednesday, 12, 0))

        val result = repository.upcoming()

        assertEquals(today, result.fromDay)
        assertEquals(today + 14, result.toDay)
        assertTrue(result.days.isEmpty())
        assertEquals(PriorityLoad(), result.priorities)
        assertEquals(0, result.baselineDailyMinutes)
    }

    // -- what the read must never do -------------------------------------------------------------------

    @Test
    fun `the read changes no deadline, no priority and no status`() = runBlocking {
        seed()
        deadlineTask("Urgent", thursday, priority = Priority.HIGH)
        blockTask("Draft", friday, endHour = 20)
        worked(wednesday.minusDays(1), 60)
        val before = workSignature()

        upcoming()
        fixture.insightRepository.refresh()
        fixture.awaitIdle()

        assertEquals(
            "an app that notices a heavy day is not an app that moves the work off it",
            before,
            workSignature()
        )
    }

    @Test
    fun `the panel's heaviest day and the insight's heavy day are the same day, from the same figures`() =
        runBlocking {
            seed()
            worked(wednesday.minusDays(1), 60)
            worked(wednesday.minusDays(2), 60)
            blockTask("Conference", friday, startHour = 8, endHour = 18)

            val result = upcoming()
            val heaviest = requireNotNull(result.heaviestDay)
            fixture.insightRepository.refresh()
            fixture.awaitIdle()
            val insight = fixture.insightDao.allOf(userId)
                .single { it.ruleKey.startsWith("heavy-day-") }

            assertEquals(
                "one day is the crowded one, in both readings",
                heaviest.epochDay.toString(),
                insight.actionRef
            )
            assertEquals(InsightAction.DAY, insight.actionType)
            assertEquals(InsightKind.HYPOTHESIS, insight.kind)
            assertEquals(
                "and the day the panel names holds the minutes the insight states",
                heaviest.minutes,
                result.days.single { it.epochDay == friday.toEpochDay() }.minutes
            )
            assertEquals(600, heaviest.minutes)
            assertEquals(2, result.measuredDays)
            assertEquals(60, result.baselineDailyMinutes)
        }

    @Test
    fun `a window with nothing in it has no heaviest day`() = runBlocking {
        seed()

        assertNull(read(wednesday, saturday).heaviestDay)
    }
}
