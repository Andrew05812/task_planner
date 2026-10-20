package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The notebook of readings, over real Room.
 *
 * What is proved here is not what the rules compute — `InsightRulesTest` covers that as plain arithmetic —
 * but the four things only a database can settle. That a rule counting one window leaves *one* row, so §7's
 * "no repeats" is a property of the storage rather than of a screen's memory. That a refresh keeps the two
 * facts the pass cannot rebuild, when the thing was first said and what the reader said about it. That the
 * reader's four answers change what comes back, and that "not true of me" is answered by silence rather than
 * by the same card next week. And that the whole exercise writes nothing to the work it reads: an app that
 * notices an overload is not an app that moves the deadline, which §8 forbids and a test can hold.
 *
 * The clock is parked at midday Wednesday 3 June 2026 in a zone with a daylight-saving jump behind it, as
 * everywhere else in this suite, and a pass is asked to run again by moving that clock forward — two passes
 * in one parked millisecond are one pass, which is the honest reading of a screen that re-subscribes.
 */
@RunWith(RobolectricTestRunner::class)
class InsightRepositoryTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private lateinit var zone: ZoneId
    private var userId: Long = -1L

    private val repository get() = fixture.insightRepository
    private val insightDao get() = fixture.insightDao

    private val wednesday = LocalDate.of(2026, 6, 3)
    private val thursday = LocalDate.of(2026, 6, 4)
    private val tuesday = LocalDate.of(2026, 6, 2)
    private val monday = LocalDate.of(2026, 6, 1)
    private val weekFrom = LocalDate.of(2026, 5, 28)
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

    /** Signs the profile in and parks the clock so "today" is the day the assertions name. */
    private suspend fun seed(): User {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.shiftTo(at(wednesday, 12, 0))
        return user
    }

    /** An open, high-priority task whose deadline has already passed. */
    private suspend fun overdueHighPriority(title: String, due: LocalDate = tuesday): Long =
        fixture.taskDao.insertTask(
            Task(
                title = title,
                description = "",
                userId = userId,
                priority = Priority.HIGH,
                dueDate = at(due, 17, 0)
            )
        )

    /** Three of them: the smallest pile the overload rule is willing to call a pile. */
    private suspend fun overloadProfile() {
        overdueHighPriority("late one")
        overdueHighPriority("late two")
        overdueHighPriority("late three")
    }

    private suspend fun rows(): List<Insight> {
        fixture.awaitIdle()
        return insightDao.allOf(userId)
    }

    private suspend fun offered(): List<Insight> {
        fixture.awaitIdle()
        return repository.observeCurrent().first()
    }

    private suspend fun history(): List<Insight> {
        fixture.awaitIdle()
        return repository.observeHistory().first()
    }

    /** The one row a named rule left behind, with the assertion that it is the only one already made. */
    private suspend fun rowFor(ruleKey: String): Insight =
        rows().filter { it.ruleKey == ruleKey }.also {
            assertEquals("exactly one row per rule and window", 1, it.size)
        }.first()

    private suspend fun pass(): InsightPass = repository.refresh().also { fixture.awaitIdle() }

    /** Moves the clock an hour ahead, which is what makes the next pass a later pass. */
    private fun later(hours: Long = 1) {
        fixture.timeBase.advance(hours * 3_600_000L)
    }

    /** The profile's own rows, as one string a test can compare across a pass. */
    private suspend fun workSignature(): String = with(fixture) {
        val tasks = taskDao.getAllRowsOnce(userId)
        val goals = goalDao.getAllForUser(userId)
        val habits = habitDao.getAllForUser(userId)
        val marks = habitDao.getAllChecksForUser(userId)
        "tasks=${tasks.size}/${tasks.maxOfOrNull { it.updatedAt } ?: 0}/${tasks.sumOf { it.status.name.length }}" +
            ",goals=${goals.size}/${goals.maxOfOrNull { it.updatedAt } ?: 0}" +
            ",habits=${habits.size}/${habits.maxOfOrNull { it.updatedAt } ?: 0}" +
            ",marks=${marks.size}/${marks.maxOfOrNull { it.updatedAt } ?: 0}"
    }

    // -- what one pass leaves behind -----------------------------------------------------------------

    @Test
    fun `a pass leaves one row per reading and words it from the numbers it counted`() = runBlocking {
        seed()
        overloadProfile()
        val habit = fixture.habitRepository.create(
            title = "Run",
            frequency = HabitFrequency.DAILY,
            startDay = weekFrom.toEpochDay()
        )
        assertTrue(habit is HabitWriteResult.Saved)
        val habitUuid = (habit as HabitWriteResult.Saved).habitUuid
        // Kept on five of the seven days the week asked for, which is two missed days.
        listOf(weekFrom, weekFrom.plusDays(1), weekFrom.plusDays(2), weekFrom.plusDays(3), monday)
            .forEach { fixture.habitRepository.mark(habitUuid, it.toEpochDay()) }

        val result = pass()

        assertEquals(2, result.raised)
        assertEquals(0, result.refreshed)
        val rows = rows()
        assertEquals(2, rows.size)

        val overload = rowFor("high-priority-overload")
        assertEquals(InsightKind.FACT, overload.kind)
        assertEquals(today, overload.periodFromDay)
        assertEquals(today, overload.periodToDay)
        assertEquals(3, overload.sampleSize)
        assertTrue("a count is stated confidently, and still not as certainty", overload.confidence < 100)
        assertEquals(InsightAction.CALENDAR_WEEK, overload.actionType)
        assertEquals(today.toString(), overload.actionRef)
        assertTrue("the body carries the figure: ${overload.body}", overload.body.contains("3"))
        assertTrue("and so does the evidence: ${overload.basis}", overload.basis.contains("3"))
        assertNotNull(overload.firstSeenAt)
        assertNull("nobody has answered it yet", overload.feedback)

        val missed = rowFor("habit-miss-1")
        assertEquals(InsightKind.FACT, missed.kind)
        assertEquals(InsightAction.HABIT, missed.actionType)
        assertEquals("the reading leads to the habit row the reader can open", "1", missed.actionRef)
        assertTrue("the habit's own name is in the words: ${missed.title}", missed.title.contains("Run"))
        assertTrue("the claim states what was skipped: ${missed.body}", missed.body.contains("2"))
        assertTrue("and the evidence states the span it was skipped from: ${missed.basis}",
            missed.basis.contains("7"))

        assertEquals(
            "offered, newest first, and everything the pass found",
            listOf(missed.ruleKey, overload.ruleKey).toSet(),
            offered().map { it.ruleKey }.toSet()
        )
        assertTrue(history().isEmpty())
    }

    @Test
    fun `a pass over a profile that has nothing to read about leaves nothing behind`() = runBlocking {
        seed()

        val result = pass()

        assertEquals(0, result.raised)
        assertEquals(0, result.refreshed)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a pass with no profile signed in writes nothing at all`() = runBlocking {
        fixture.timeBase.shiftTo(at(wednesday, 12, 0))

        val result = pass()

        assertEquals(0, result.considered)
        assertTrue(insightDao.allOf(1L).isEmpty())
    }

    // -- the same window, said again -----------------------------------------------------------------

    @Test
    fun `a second pass over the same window refreshes the row instead of adding one`() = runBlocking {
        seed()
        overloadProfile()
        pass()
        val first = rowFor("high-priority-overload")

        later()
        overdueHighPriority("late four")
        val result = pass()

        assertEquals("the same reading, re-counted", 0, result.raised)
        assertEquals(1, result.refreshed)
        val refreshed = rowFor("high-priority-overload")

        assertEquals("one rule and one window is one row", first.id, refreshed.id)
        assertEquals("and the uuid the pass derives on its own is how it found it", first.uuid, refreshed.uuid)
        assertEquals(
            "when the thing was first said belongs to the reader, not to the newest pass",
            first.firstSeenAt,
            refreshed.firstSeenAt
        )
        assertTrue("the pass that still finds it says so", refreshed.lastSeenAt > first.lastSeenAt)
        assertEquals(
            "and the words state what this pass counted",
            4,
            (refreshed.sampleSize)
        )
        assertTrue(refreshed.body.contains("4"))
        assertTrue("the reading is still being stood behind", refreshed.isCurrent)
    }

    @Test
    fun `a reading the rules stop finding becomes history and keeps its words`() = runBlocking {
        seed()
        overloadProfile()
        pass()
        val raised = rowFor("high-priority-overload")

        later()
        // The pile is gone: the tasks are finished, so nothing is overdue to be said about them.
        fixture.taskDao.getAllRowsOnce(userId).filter { it.dueDate != null }.forEach { task ->
            fixture.taskDao.updateTask(
                task.copy(status = TaskStatus.COMPLETED, completedAt = at(wednesday, 11, 0))
            )
        }
        val result = pass()

        assertEquals("the pass retired the reading it no longer finds", 1, result.retired)
        val retired = rowFor("high-priority-overload")
        assertFalse(retired.isCurrent)
        assertEquals("its words are the ones it arrived with", raised.title, retired.title)
        assertEquals(raised.basis, retired.basis)
        assertEquals(listOf(retired.ruleKey), history().map { it.ruleKey })
        assertTrue("and it is off the offered list", offered().isEmpty())
    }

    // -- what the reader says back -------------------------------------------------------------------

    @Test
    fun `a rejected reading leaves the offered list the moment it is said`() = runBlocking {
        seed()
        overloadProfile()
        pass()
        val raised = rowFor("high-priority-overload")

        assertTrue(repository.giveFeedback(raised.uuid, InsightFeedback.REJECTED))

        assertEquals(InsightFeedback.REJECTED, rowFor("high-priority-overload").feedback)
        assertTrue(offered().isEmpty())
    }

    @Test
    fun `accepted and useful are both answers that keep the reading on the list`() = runBlocking {
        seed()
        overloadProfile()
        val habit = fixture.habitRepository.create(
            title = "Run", frequency = HabitFrequency.DAILY, startDay = weekFrom.toEpochDay()
        ) as HabitWriteResult.Saved
        listOf(weekFrom, weekFrom.plusDays(1), weekFrom.plusDays(2)).forEach {
            fixture.habitRepository.mark(habit.habitUuid, it.toEpochDay())
        }
        pass()

        val kept = rowFor("habit-miss-1").uuid
        assertTrue(repository.giveFeedback(kept, InsightFeedback.ACCEPTED))
        assertTrue(repository.giveFeedback(rowFor("high-priority-overload").uuid, InsightFeedback.USEFUL))

        assertEquals(
            listOf("habit-miss-1", "high-priority-overload").toSet(),
            offered().map { it.ruleKey }.toSet()
        )
    }

    @Test
    fun `a hidden reading is not offered again for the window it was counted over`() = runBlocking {
        seed()
        overloadProfile()
        pass()
        val hidden = rowFor("high-priority-overload")
        assertTrue(repository.giveFeedback(hidden.uuid, InsightFeedback.HIDDEN))

        later()
        val result = pass()

        assertEquals("the rule still fires, but the row the reader hid is not raised again", 0, result.raised)
        assertEquals("and it was not refreshed either", 0, result.refreshed)
        val row = rowFor("high-priority-overload")
        assertEquals("the same row, not a second copy for the same window", hidden.id, row.id)
        assertEquals(
            "a pass that re-stated it would undo the answer the reader gave",
            hidden.lastSeenAt,
            row.lastSeenAt
        )
        assertEquals(InsightFeedback.HIDDEN, row.feedback)
        assertTrue(offered().isEmpty())
    }

    @Test
    fun `a rejected rule stays quiet about the window after the one it was rejected in`() = runBlocking {
        seed()
        overloadProfile()
        pass()
        val rejected = rowFor("high-priority-overload")
        assertTrue(repository.giveFeedback(rejected.uuid, InsightFeedback.REJECTED))

        // The next day, with the same work still overdue: the rule would raise a new window if it were
        // asked, and the reader's answer is what stops it.
        fixture.timeBase.shiftTo(at(thursday, 12, 0))
        pass()
        assertEquals(
            "one row for the rule, and it is still the rejected one",
            listOf(rejected.uuid),
            rows().filter { it.ruleKey == "high-priority-overload" }.map { it.uuid }
        )
        assertTrue(offered().isEmpty())

        // The silence has a term. A month on, the same pile of work is worth saying out loud again.
        fixture.timeBase.shiftTo(at(thursday.plusDays(31), 12, 0))
        val result = pass()

        assertEquals(1, result.raised)
        val again = rows().single { it.ruleKey == "high-priority-overload" && it.id != rejected.id }
        assertEquals(thursday.plusDays(31).toEpochDay(), again.periodFromDay)
        assertTrue("the old answer stays where it was given", again.feedback == null)
    }

    @Test
    fun `feedback cannot land on a reading the other profile holds`() = runBlocking {
        val owner = seed()
        overloadProfile()
        pass()
        val theirs = rowFor("high-priority-overload")

        // A second profile, under its own address: `users.email` is unique and its insert replaces, so a
        // second signIn on the default email would delete the first profile rather than stand beside it.
        fixture.signIn(name = "Other", email = "other@example.com")
        val given = repository.giveFeedback(theirs.uuid, InsightFeedback.REJECTED)

        assertFalse("the tap was not for this account's row", given)
        fixture.switchTo(owner)
        assertNull("and the reading is untouched", rowFor("high-priority-overload").feedback)
    }

    @Test
    fun `one profile's pass cannot retire another profile's readings`() = runBlocking {
        val owner = seed()
        overloadProfile()
        pass()
        val theirs = rowFor("high-priority-overload")

        val other = fixture.signIn(name = "Other", email = "other@example.com")
        userId = other.id
        overloadProfile()
        val result = pass()
        assertTrue("the second account's pass found its own pile: $result", result.raised > 0)
        assertTrue(offered().isNotEmpty())

        fixture.switchTo(owner)
        userId = owner.id
        val untouched = rowFor("high-priority-overload")
        assertEquals(theirs.uuid, untouched.uuid)
        assertTrue("and left this one standing", untouched.isCurrent)
        assertEquals(InsightKind.FACT, untouched.kind)
        // Read the other account's notebook from its own rows: `rows()` is scoped to the signed-in profile,
        // and the claim being proved here is about the two accounts holding *different* rows.
        val otherRows = insightDao.allOf(other.id)
        assertTrue("the second account has its own readings to hold", otherRows.isNotEmpty())
        assertNotEquals(
            "two accounts told the same thing about their own rows hold different rows",
            theirs.uuid,
            otherRows.first().uuid
        )
    }

    // -- how long the notebook keeps things -----------------------------------------------------------

    @Test
    fun `retention forgets untouched history and keeps whatever the reader answered`() = runBlocking {
        seed()
        val old = today - 200L
        insightDao.insert(
            Insight(
                uuid = Insight.uuidFor(userId, "quiet-rule", old, old + 6L),
                userId = userId,
                ruleKey = "quiet-rule",
                kind = InsightKind.PATTERN,
                title = "An old reading",
                body = "Nothing of it survives in the rows any more.",
                basis = "3 of 12 measured sessions.",
                periodFromDay = old,
                periodToDay = old + 6L,
                sampleSize = 12,
                confidence = 60,
                firstSeenAt = at(wednesday) - 90L * 24 * 3_600_000L,
                lastSeenAt = at(wednesday) - 80L * 24 * 3_600_000L,
                isCurrent = false
            )
        )
        insightDao.insert(
            Insight(
                uuid = Insight.uuidFor(userId, "answered-rule", old, old + 6L),
                userId = userId,
                ruleKey = "answered-rule",
                kind = InsightKind.PATTERN,
                title = "A reading the reader answered",
                body = "Not true of me.",
                basis = "2 of 9 finished tasks.",
                periodFromDay = old,
                periodToDay = old + 6L,
                sampleSize = 9,
                confidence = 55,
                firstSeenAt = at(wednesday) - 90L * 24 * 3_600_000L,
                lastSeenAt = at(wednesday) - 80L * 24 * 3_600_000L,
                isCurrent = false,
                feedback = InsightFeedback.REJECTED,
                feedbackAt = at(wednesday) - 70L * 24 * 3_600_000L
            )
        )

        val result = pass()

        assertEquals(1, result.forgotten)
        val kept = rows()
        assertEquals(
            "an answer the reader gave is their record, not the notebook's to discard",
            listOf("answered-rule"),
            kept.map { it.ruleKey }
        )
    }

    // -- §8: a reading does not act on the work it read ----------------------------------------------

    @Test
    fun `a pass writes nothing to the tasks, goals, habits, or marks it read`() = runBlocking {
        seed()
        overloadProfile()
        overdueHighPriority("also overdue", due = monday)
        val habit = fixture.habitRepository.create(
            title = "Run", frequency = HabitFrequency.DAILY, startDay = weekFrom.toEpochDay()
        ) as HabitWriteResult.Saved
        listOf(weekFrom, weekFrom.plusDays(1)).forEach {
            fixture.habitRepository.mark(habit.habitUuid, it.toEpochDay())
        }
        fixture.goalRepository.create(
            title = "Ship the report",
            type = com.taskplanner.data.model.GoalType.TASK_COUNT,
            targetValue = 20L,
            deadlineAt = at(thursday.plusDays(5), 18, 0)
        )
        val before = workSignature()

        pass()
        later()
        val second = pass()

        assertTrue("the pass had readings to make: $second", second.raised + second.refreshed > 0)
        assertEquals(
            "nothing on the work moved: an insight that could reschedule or reprioritise would fail here",
            before,
            workSignature()
        )
        fixture.taskDao.getAllRowsOnce(userId).forEach { task ->
            assertEquals(Priority.HIGH, task.priority)
            assertEquals(TaskStatus.TODO, task.status)
            assertNull(task.completedAt)
            assertFalse(task.archived)
        }
    }
}
