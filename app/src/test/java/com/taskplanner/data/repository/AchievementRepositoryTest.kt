package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.achievement.AchievementFigures
import com.taskplanner.data.achievement.AchievementRule
import com.taskplanner.data.achievement.AchievementRules
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.TaskGroup
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
 * The achievement engine, over real Room.
 *
 * `AchievementRulesTest` already reads each rule as arithmetic, so nothing here re-asks whether a bar is
 * reached. What is proved is the five things only the pass and the database can settle. **A grant is handed
 * out once**: the same condition met twice leaves one row with the first date and the first figure on it,
 * which is §7.1's "no repeats" as a property of the storage rather than of a loop's memory. **A grant that
 * arrives from elsewhere is not handed out a second time either** — a row written by a restore keeps its own
 * date and wording while the pass that finds it reports it as already held, which is §11's import rule
 * settled at the level the pass can see. **A grant is never rewritten underneath the reader**: when the work
 * that earned it shrinks or falls away, the stored figure and the stored sentence stay, while the card's own
 * live progress figure moves — the two numbers are different things and the screen is given both. **The
 * figures are the app's own**: a card is earned by completing a task through [TaskRepository], by measuring
 * an interval through [WorkTrackingRepository], by marking a habit through [HabitRepository] and by reaching
 * a goal through the same [GoalRepository.progressOf] the goal's card runs, never by inserting a row shaped
 * like the answer, so the test would fail the day an achievement started counting something the screens do
 * not count. **Cancelled work cannot pay out, and paid-for work is not taken back** — both directions of the
 * same figure, held apart by the instant a completion is stamped. **And nothing here belongs to anybody
 * else**: two profiles doing the identical thing earn two rows, not one shared one.
 *
 * One exception to "through the repository" is [finish], and it is spelled out there: the task writes that
 * this suite's goal windows depend on are stamped by [TaskRepository.completeAndArchive] from the device
 * clock rather than from the parked one, so those completions name their instant directly through the DAO
 * statement that makes them.
 *
 * The clock is parked at midday Wednesday 3 June 2026 in a zone with a daylight-saving jump behind it, as
 * everywhere in this suite, and a later pass is asked for by moving that clock — two passes in one parked
 * millisecond are one pass, which is the honest reading of a screen that re-subscribes.
 */
@RunWith(RobolectricTestRunner::class)
class AchievementRepositoryTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private lateinit var zone: ZoneId
    private var userId: Long = -1L

    private val repository get() = fixture.achievementRepository
    private val dao get() = fixture.achievementDao

    private val wednesday = LocalDate.of(2026, 6, 3)
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

    /**
     * Finishes a task at an instant the suite chose, through the write that files it away.
     *
     * [TaskRepository.completeAndArchive] stamps `completed_at` from the device clock rather than from the
     * parked [com.taskplanner.data.tracking.TimeBase]: it is Stage-1 code and predates that seam. A test
     * that only asks whether the column is filled cannot see the difference, and most of this suite does
     * exactly that. A goal can: its window is a pair of days, so a task "finished in October" while the
     * clock is held at June is not inside it, and the figure the goal's card prints would be a figure the
     * card never showed. The window-sensitive completions therefore name their own instant, which is how
     * `TaskStatisticsDaoTest` already puts rows inside a period.
     */
    private suspend fun finish(taskId: Long, instant: Long = at(wednesday, 11, 0)) =
        fixture.taskDao.archiveTask(taskId, instant)

    private suspend fun pass(): AchievementPass = repository.refresh().also { fixture.awaitIdle() }

    private suspend fun keys(of: Long = userId): List<String> =
        dao.earnedKeys(of).also { fixture.awaitIdle() }.sorted()

    /** The one grant a named rule left behind, with the assertion that it is the only one already made. */
    private suspend fun grant(key: String, of: Long = userId): Achievement =
        dao.byKey(of, key).also { fixture.awaitIdle() }.let {
            assertNotNull("no grant for $key", it)
            it!!
        }

    private suspend fun rule(key: String): AchievementRule = AchievementRules.ALL.first { it.key == key }

    // -- one pass, and what it leaves behind ----------------------------------------------------------

    @Test
    fun `a profile with nothing in it is measured rather than rewarded`() = runBlocking {
        seed()
        val result = pass()
        assertEquals(emptyList<String>(), keys())
        assertEquals(0, result.granted)
        assertEquals(0, result.met)
        // The rules with a figure to judge are the ones whose counter is not null over an empty profile; a
        // streak rule with no habits has nothing to be short of, and counting it as "considered" would make
        // the screen report a failure the reader never had.
        val expected = AchievementRules.ALL.count { it.counter(AchievementFigures()) != null }
        assertEquals(expected, result.considered)
    }

    @Test
    fun `finishing one task hands out the first card with the figure behind it`() = runBlocking {
        seed()
        val id = fixture.addTask(userId, "Write the report")
        fixture.taskRepository.completeAndArchive(id)
        val result = pass()

        assertEquals(listOf("first_task"), keys())
        assertEquals(1, result.granted)
        val row = grant("first_task")
        assertEquals(AchievementCategory.BEGINNING, row.category)
        assertEquals(AchievementUnit.TASKS, row.unit)
        assertEquals(1L, row.targetValue)
        assertEquals(1L, row.progressValue)
        // The day and the instant are the reader's, not the pass's: the grant lands on the day the completion
        // was stamped, in the zone the reader lives in.
        assertEquals(today, row.earnedDay)
        assertEquals(at(wednesday, 12, 0), row.earnedAt)
        assertEquals("First one done", row.title)
        assertEquals("Earned with 1 task.", row.description)
        // §7.2: the edition of the rule that handed this out, so a bar retuned later can be told apart.
        assertEquals(rule("first_task").version, row.ruleVersion)
        assertEquals(1, row.ruleVersion)
        assertEquals(Achievement.uuidFor(userId, "first_task"), row.uuid)
    }

    @Test
    fun `the same condition met again leaves one grant with its original date`() = runBlocking {
        seed()
        val first = fixture.addTask(userId, "one")
        fixture.taskRepository.completeAndArchive(first)
        pass()
        val earned = grant("first_task")

        // A week of further work, and three passes over it.
        fixture.timeBase.advance(7L * 24 * 3_600_000L)
        repeat(3) { day ->
            val task = fixture.addTask(userId, "task ${2 + day}")
            fixture.taskRepository.completeAndArchive(task)
            pass()
        }

        val rows = dao.observeEarned(userId).first()
        assertEquals("one row per achievement, whatever the count", rows.size, rows.distinctBy { it.uuid }.size)
        assertEquals(
            "a grant is a memory: neither its date nor its figure moves when the work grows",
            earned,
            grant("first_task")
        )
    }

    @Test
    fun `a pass over work already paid for grants nothing and says how much was met`() = runBlocking {
        seed()
        val ids = (1..12).map { fixture.addTask(userId, "task $it") }
        ids.forEach { fixture.taskRepository.completeAndArchive(it) }
        val first = pass()
        assertEquals(2, first.granted)

        val again = pass()
        assertEquals(0, again.granted)
        // The conditions are still met — the reader did the work — so the pass reports them as met and
        // already held rather than as nothing. That is the number a screen needs when the reader asks why
        // nothing happened today.
        assertEquals(first.met, again.met)
        assertEquals(first.met, again.alreadyEarned)
    }

    @Test
    fun `a card past its bar is earned with the whole figure behind it`() = runBlocking {
        seed()
        // Eleven tasks in one day: the ten-task card reads eleven, because that is what the reader did, and
        // the history states it rather than clamping it to the bar it crossed.
        (1..11).map { fixture.addTask(userId, "task $it") }
            .forEach { fixture.taskRepository.completeAndArchive(it) }
        pass()

        val row = grant("tasks_10")
        assertEquals(10L, row.targetValue)
        assertEquals(11L, row.progressValue)
        assertEquals("Ten done", row.title)
        assertEquals("Earned with 11 tasks.", row.description)
        assertFalse("eleven tasks is not fifty", keys().contains("tasks_50"))
    }

    @Test
    fun `taking a completion back does not take the achievement back`() = runBlocking {
        seed()
        val ids = (1..10).map { fixture.addTask(userId, "task $it") }
        ids.forEach { fixture.taskRepository.completeAndArchive(it) }
        pass()
        val earned = grant("tasks_10")

        // Every one of them un-completed: the work figure falls to zero, and the grant stays exactly as it was
        // written. An achievement that went back when the reader changed their mind would make the whole idea
        // of a record conditional on the future.
        ids.forEach { fixture.taskRepository.restoreTask(it) }
        pass()

        assertEquals(listOf("tasks_10"), keys().filter { it.startsWith("tasks_") })
        assertEquals(earned, grant("tasks_10"))
        assertEquals(0L, fixture.achievementStatsDao.completedTasksEver(userId))
    }

    @Test
    fun `a grant keeps the figure and the sentence it was earned with after the work shrinks`() = runBlocking {
        seed()
        val ids = (1..11).map { fixture.addTask(userId, "task $it") }
        ids.forEach { fixture.taskRepository.completeAndArchive(it) }
        pass()
        val earned = grant("tasks_10")
        assertEquals(11L, earned.progressValue)
        assertEquals("Earned with 11 tasks.", earned.description)

        // Eight of them thrown away, so the live figure falls below the bar the card was handed out over. The
        // history still says what was true on the day it was written: §7.2 asks that a grant never vanish, and
        // a grant that quietly re-wrote its own number and its own sentence would be that vanishing done
        // politely — the reader would have no way to see that the record had been edited.
        fixture.taskRepository.softDeleteTasks(ids.take(8))
        pass()

        assertEquals("the stored row is untouched, figure and wording included", earned, grant("tasks_10"))
        assertEquals(3L, fixture.achievementStatsDao.completedTasksEver(userId))

        // And the screen can still tell the two numbers apart: the grant's figure is the memory, and the card's
        // own progress figure is what the profile has reached now.
        val card = repository.observeOverview(zone).first().first { it.key == "tasks_10" }
        assertTrue(card.isEarned)
        assertEquals(3L, card.progress)
        assertEquals(11L, card.earned?.progressValue)
    }

    @Test
    fun `a grant that arrived from outside the app keeps its own date and is not handed out again`() = runBlocking {
        seed()
        // A restore from a backup puts a grant on the table that this app never computed, under a day it never
        // saw. §11's "достижения не должны дублироваться после импорта" therefore has to be a property of the
        // storage rather than of a pass that remembers: the imported row is there before the next pass runs,
        // and the pass reads it as granted rather than as work worth measuring again.
        val restored = Achievement(
            uuid = Achievement.uuidFor(userId, "tasks_10"),
            userId = userId,
            achievementKey = "tasks_10",
            category = AchievementCategory.VOLUME,
            title = "Ten done",
            description = "Earned with 10 tasks.",
            unit = AchievementUnit.TASKS,
            targetValue = 10L,
            progressValue = 10L,
            earnedDay = 1L,
            earnedAt = 1L
        )
        assertTrue(dao.insertOrIgnore(restored) > 0L)
        fixture.awaitIdle()
        val asArrived = grant("tasks_10")

        (1..12).map { fixture.addTask(userId, "task $it") }
            .forEach { fixture.taskRepository.completeAndArchive(it) }
        val result = pass()

        // Twelve finished tasks meet two cards; one of them was already on the table.
        assertEquals(2, result.met)
        assertEquals("only the card nobody held is written", 1, result.granted)
        assertEquals(1, result.alreadyEarned)
        assertEquals(listOf("first_task", "tasks_10"), keys())
        assertEquals(
            "the imported grant is the one that stands, date and figure and wording and all",
            asArrived,
            grant("tasks_10")
        )
    }

    @Test
    fun `work deleted out of the account is not work that was ever done`() = runBlocking {
        seed()
        val ids = (1..10).map { fixture.addTask(userId, "task $it") }
        ids.forEach { fixture.taskRepository.completeAndArchive(it) }
        // One of the ten was a task that never existed: the reader deleted it, tombstone and all. Nine remain,
        // and nine is not ten.
        fixture.taskRepository.softDeleteTasks(listOf(ids.first()))
        val result = pass()

        // Nine remain, and nine is not ten: the bar is measured against the work the account still stands
        // behind. The first-task card is met and granted, because one of those nine was finished.
        assertEquals(emptyList<String>(), keys().filter { it.startsWith("tasks_") })
        assertEquals(listOf("first_task"), keys())
        assertEquals(1, result.granted)
    }

    // -- the figures the pass reads -------------------------------------------------------------------

    @Test
    fun `a measured interval is a first session even when it measures less than an hour`() = runBlocking {
        seed()
        val id = fixture.addTask(userId, "Read the spec")
        val task = fixture.taskRepository.getTaskById(id)!!
        fixture.workTrackingRepository.recordManualInterval(
            task, at(wednesday, 9, 0), at(wednesday, 9, 1)
        )
        pass()

        // One interval on one day: the session card is met, and nothing else in the catalogue is.
        assertEquals(listOf("first_session"), keys())
        val row = grant("first_session")
        assertEquals(AchievementUnit.SESSIONS, row.unit)
        assertEquals(1L, row.progressValue)
        assertEquals("Earned with 1 measured session.", row.description)
        // Sixty minutes is a real first time and nothing like ten hours measured in hours: the two figures are
        // read apart, so the hour cards stay unearned instead of rounding a stretch up.
        assertNull(grantOrNone("hours_10"))
    }

    @Test
    fun `hours are counted as whole hours of measured work`() = runBlocking {
        seed()
        val id = fixture.addTask(userId, "Write the chapter")
        val task = fixture.taskRepository.getTaskById(id)!!
        fixture.workTrackingRepository.recordManualInterval(
            task, at(wednesday, 8, 0), at(wednesday, 18, 0)
        )
        pass()

        val row = grant("hours_10")
        assertEquals(10L, row.progressValue)
        assertEquals("Earned with 10 hours of measured work.", row.description)
        assertNull(grantOrNone("hours_50"))
    }

    @Test
    fun `a day of use counts once however many intervals it holds`() = runBlocking {
        seed()
        val id = fixture.addTask(userId, "Two sittings")
        val task = fixture.taskRepository.getTaskById(id)!!
        fixture.workTrackingRepository.recordManualInterval(task, at(wednesday, 9, 0), at(wednesday, 10, 0))
        fixture.workTrackingRepository.recordManualInterval(task, at(wednesday, 14, 0), at(wednesday, 15, 0))
        pass()
        assertEquals(emptyList<String>(), keys().filter { it.startsWith("active_days_") })

        // The same reader, two more days: three days is the bar, and the second interval on the first of them
        // never counted twice.
        val monday = wednesday.minusDays(2)
        fixture.workTrackingRepository.recordManualInterval(task, at(monday, 9, 0), at(monday, 10, 0))
        val tuesday = wednesday.minusDays(1)
        fixture.workTrackingRepository.recordManualInterval(task, at(tuesday, 9, 0), at(tuesday, 10, 0))
        pass()

        val row = grant("active_days_3")
        assertEquals(AchievementUnit.ACTIVE_DAYS, row.unit)
        assertEquals(3L, row.progressValue)
        assertEquals("Earned with 3 days of use.", row.description)
    }

    @Test
    fun `a habit kept for a week earns the week, and a run in the past still counts`() = runBlocking {
        seed()
        val habit = createHabit("Run", HabitFrequency.DAILY, wednesday.minusDays(40))
        // A week kept and then let go: the series that earns the card is the one the reader actually ran, not
        // whichever one happens to be standing today.
        (1L..7L).forEach { offset -> keep(habit, wednesday.minusDays(offset)) }
        pass()

        val row = grant("habit_series_7")
        assertEquals(7L, row.progressValue)
        assertEquals("Earned with 7 days in a row.", row.description)
        assertNull("a week is not a month", grantOrNone("habit_series_30"))
    }

    @Test
    fun `a habit that counts weeks contributes no day series`() = runBlocking {
        seed()
        // TIMES_PER_WEEK asks for marks on any days of a week, so its streak is stated in weeks; "seven days in
        // a row" is a different claim, and converting one into the other would hand out an achievement the
        // reader's own habit card never says they earned.
        val habit = createHabit("Gym", HabitFrequency.TIMES_PER_WEEK, wednesday.minusDays(60), timesPerWeek = 3)
        (1L..21L).forEach { offset ->
            keep(habit, wednesday.minusDays(offset))
        }
        pass()

        assertEquals(emptyList<String>(), keys().filter { it.startsWith("habit_series") })
        assertEquals(emptyList<String>(), keys().filter { it.startsWith("marks_") })

        val overview = repository.observeOverview(zone).first()
        assertNull(
            "a weekly habit leaves no day figure at all, which is not the same claim as a run of nought",
            overview.first { it.key == "habit_series_7" }.progress
        )
        // The marks themselves are real work and are counted where they are counted: twenty-one of them, on a
        // habit whose schedule names weeks, still stand against the hundred-mark card.
        assertEquals(21L, overview.first { it.key == "marks_100" }.progress)
    }

    @Test
    fun `holding three habits for a week each is a different card from the longest run`() = runBlocking {
        seed()
        listOf("Run", "Read", "Write").forEach { title ->
            val habit = createHabit(title, HabitFrequency.DAILY, wednesday.minusDays(40))
            (1L..8L).forEach { offset ->
                keep(habit, wednesday.minusDays(offset))
            }
        }
        pass()

        val row = grant("habits_kept_3")
        assertEquals(AchievementUnit.HABITS, row.unit)
        assertEquals(3L, row.progressValue)
        assertEquals("Earned with 3 habits kept for a week.", row.description)
        // Each habit ran eight days, so the week card is met at its longest run rather than summed across them.
        assertEquals(8L, grant("habit_series_7").progressValue)
    }

    @Test
    fun `marks are counted over every habit the profile has`() = runBlocking {
        seed()
        listOf("Run", "Read").forEach { title ->
            val habit = createHabit(title, HabitFrequency.DAILY, wednesday.minusDays(60))
            (10L..59L).forEach { offset ->
                keep(habit, wednesday.minusDays(offset))
            }
        }
        pass()

        val row = grant("marks_100")
        assertEquals(AchievementUnit.MARKS, row.unit)
        assertEquals(100L, row.progressValue)
        assertEquals("Earned with 100 habit days.", row.description)
    }

    @Test
    fun `a mark of a habit deleted out of the account stops counting`() = runBlocking {
        seed()
        val kept = createHabit("Run", HabitFrequency.DAILY, wednesday.minusDays(40))
        val dropped = createHabit("Juice", HabitFrequency.DAILY, wednesday.minusDays(40))
        (1L..9L).forEach { offset ->
            keep(kept, wednesday.minusDays(offset))
            keep(dropped, wednesday.minusDays(offset))
        }
        // The reader takes one habit away entirely. Its marks were real, but the work they were marks of is
        // gone from the account, so they are not a hundred days kept and not three habits held.
        fixture.habitRepository.remove(dropped)
        pass()

        assertEquals(9L, fixture.achievementStatsDao.habitMarks(userId))
        assertEquals(emptyList<String>(), keys().filter { it.startsWith("marks_") })
        assertEquals(emptyList<String>(), keys().filter { it.startsWith("habits_kept") })
        // The habit that stayed still answers its own question.
        assertEquals(9L, grant("habit_series_7").progressValue)
    }

    @Test
    fun `a goal is reached the way the goal's own card reaches it`() = runBlocking {
        seed()
        val goal = fixture.goalRepository.create(
            title = "Ship the draft",
            type = GoalType.TASK_COUNT,
            targetValue = 2L,
            windowFromDay = today,
            windowToDay = today
        )
        assertTrue(goal is GoalWriteResult.Saved)
        (1..3).map { fixture.addTask(userId, "step $it") }.forEach { finish(it) }

        // The card's own read, before the pass is allowed to agree or disagree with it.
        val goalRow = fixture.goalDao.getAllForUser(userId)
            .single { it.uuid == (goal as GoalWriteResult.Saved).goalUuid }
        val progress = fixture.goalRepository.progressOf(goalRow, wednesday, zone)
        assertEquals(3L, progress.counted)
        assertTrue(progress.reached)

        pass()

        val earned = grant("first_goal")
        assertEquals(AchievementUnit.GOALS, earned.unit)
        assertEquals("the grant counts reached goals, not the tasks under them", 1L, earned.progressValue)
        assertEquals("First goal reached", earned.title)
        assertNull(grantOrNone("goals_5"))
    }

    @Test
    fun `a goal reached before it was put away stays reached`() = runBlocking {
        seed()
        val goal = fixture.goalRepository.create(
            title = "Finish the chapter",
            type = GoalType.TASK_COUNT,
            targetValue = 2L,
            windowFromDay = today,
            windowToDay = today
        )
        assertTrue(goal is GoalWriteResult.Saved)
        (1..2).map { fixture.addTask(userId, "step $it") }.forEach { finish(it) }
        // Carried through, then filed: the reader did reach it, and putting the card away does not take the
        // reaching back. §7 asks that a grant never vanish, and this is the case where a window read could.
        assertTrue(fixture.goalRepository.archive((goal as GoalWriteResult.Saved).goalUuid) is GoalWriteResult.Saved)
        pass()

        assertEquals(1L, grant("first_goal").progressValue)
    }

    @Test
    fun `a goal that was never reached is not counted because it was archived`() = runBlocking {
        seed()
        val goal = fixture.goalRepository.create(
            title = "Someday",
            type = GoalType.TASK_COUNT,
            targetValue = 5L,
            windowFromDay = today,
            windowToDay = today
        )
        assertTrue(goal is GoalWriteResult.Saved)
        (1..3).map { fixture.addTask(userId, "step $it") }.forEach { finish(it) }
        val goalRow = fixture.goalDao.getAllForUser(userId)
            .single { it.uuid == (goal as GoalWriteResult.Saved).goalUuid }
        // Three of five is short, and the card says so before the pass is asked anything.
        val progress = fixture.goalRepository.progressOf(goalRow, wednesday, zone)
        assertEquals(3L, progress.counted)
        assertFalse(progress.reached)
        assertTrue(fixture.goalRepository.archive((goal as GoalWriteResult.Saved).goalUuid) is GoalWriteResult.Saved)
        pass()

        assertEquals(emptyList<String>(), keys().filter { it.startsWith("goals") || it == "first_goal" })
    }

    @Test
    fun `a project is closed when every task in it was finished`() = runBlocking {
        seed()
        val group: TaskGroup = fixture.addGroup(userId, "Thesis")
        (1..2).map { fixture.addTask(userId, "chapter $it", groupId = group.uuid) }
            .forEach { fixture.taskRepository.completeAndArchive(it) }
        pass()

        val row = grant("first_project")
        assertEquals(AchievementUnit.PROJECTS, row.unit)
        assertEquals(1L, row.progressValue)
        assertEquals("A project closed", row.title)
    }

    @Test
    fun `an emptied project is not a closed one`() = runBlocking {
        seed()
        val group = fixture.addGroup(userId, "Someday")
        val id = fixture.addTask(userId, "the only one", groupId = group.uuid)
        fixture.taskRepository.completeAndArchive(id)
        // The reader finishes the task and then deletes it: what is left is a project with no work in it, which
        // is a thing emptied rather than a thing carried through.
        fixture.taskRepository.softDeleteTasks(listOf(id))
        pass()

        assertEquals(emptyList<String>(), keys().filter { it.endsWith("project") })
    }

    // -- whose grants these are -----------------------------------------------------------------------

    @Test
    fun `two profiles doing the identical work earn two grants, not one shared`() = runBlocking {
        val first = seed()
        val mine = fixture.addTask(userId, "one")
        fixture.taskRepository.completeAndArchive(mine)
        pass()

        val second = fixture.signInAs(User(email = "other@example.com", name = "Other"))
        val theirs = fixture.addTask(second.id, "one")
        fixture.taskRepository.completeAndArchive(theirs)
        pass()

        assertEquals(listOf("first_task"), keys(first.id))
        assertEquals(listOf("first_task"), keys(second.id))
        assertNotEquals(
            "a grant is addressed to a profile, so two accounts cannot share one row",
            Achievement.uuidFor(first.id, "first_task"),
            Achievement.uuidFor(second.id, "first_task")
        )
        assertEquals(second.id, grant("first_task", of = second.id).userId)
        assertEquals(listOf("first_task"), dao.observeEarned(second.id).first().map { it.achievementKey })
        assertEquals(listOf("first_task"), dao.observeEarned(first.id).first().map { it.achievementKey })
    }

    @Test
    fun `with no profile signed in a pass does nothing rather than guessing`() = runBlocking {
        // Nothing signed in: no rows belong to anybody, so there is no set to count and no history to write
        // into. A pass that granted to user 0 would put a stranger's badge in the next reader's list.
        val result = repository.refresh()
        fixture.awaitIdle()
        assertEquals(AchievementPass(), result)
        assertEquals(emptyList<Achievement>(), repository.observeAchievements().first())

        // And the screen that signs in afterwards finds an empty history rather than the one the pass would
        // have had to invent for nobody.
        seed()
        assertEquals(emptyList<String>(), keys())
        assertEquals(0, repository.observeOverview(zone).first().count { it.isEarned })
    }

    // -- the read the screen is built on ----------------------------------------------------------------

    @Test
    fun `the overview states the bar, the figure and the grant for every achievement`() = runBlocking {
        seed()
        val id = fixture.addTask(userId, "one")
        fixture.taskRepository.completeAndArchive(id)
        pass()

        val overview = repository.observeOverview(zone).first()
        assertEquals(AchievementRules.ALL.size, overview.size)
        AchievementRules.ALL.forEach { rule ->
            val card = overview.first { it.key == rule.key }
            assertEquals(rule.category, card.category)
            assertEquals(rule.unit, card.unit)
            assertEquals(rule.target, card.target)
            assertEquals(rule.titleRes, card.titleRes)
            assertEquals(rule.conditionRes, card.conditionRes)
        }

        val earned = overview.first { it.key == "first_task" }
        assertTrue(earned.isEarned)
        assertEquals(1L, earned.progress)
        assertEquals("First one done", earned.earned?.title)

        // A locked card carries the real figure rather than a percentage of nothing, and a card with no figure
        // behind it says so instead of showing a zero the reader never had the chance to reach.
        val locked = overview.first { it.key == "tasks_50" }
        assertFalse(locked.isEarned)
        assertEquals(1L, locked.progress)
        assertNull(
            "no habits, so no series to be seven days short of",
            overview.first { it.key == "habit_series_7" }.progress
        )
    }

    @Test
    fun `a grant reaches the overview in the same frame it reached the table`() = runBlocking {
        seed()
        val before = repository.observeOverview(zone).first()
        assertEquals(0, before.count { it.isEarned })

        val id = fixture.addTask(userId, "one")
        fixture.taskRepository.completeAndArchive(id)
        pass()
        val after = repository.observeOverview(zone).first()
        assertEquals(1, after.count { it.isEarned })
        assertEquals("first_task", after.first { it.isEarned }.key)
    }

    // -- helpers --------------------------------------------------------------------------------------

    private suspend fun createHabit(
        title: String,
        frequency: HabitFrequency,
        start: LocalDate,
        timesPerWeek: Int? = null
    ): String {
        val result = fixture.habitRepository.create(
            title = title,
            frequency = frequency,
            startDay = start.toEpochDay(),
            timesPerWeek = timesPerWeek
        )
        assertTrue("$title was not created: $result", result is HabitWriteResult.Saved)
        return (result as HabitWriteResult.Saved).habitUuid
    }

    /**
     * Keeps a habit on one day.
     *
     * The refusal is checked rather than ignored: a mark the habit's own schedule turned down would leave the
     * series short of the run the assertions name, and a grant that did not arrive would read like an engine
     * bug rather than like the empty window it actually came from.
     */
    private suspend fun keep(habitUuid: String, day: LocalDate) {
        val result = fixture.habitRepository.mark(habitUuid, day.toEpochDay())
        assertTrue("$habitUuid on $day was not kept: $result", result is HabitWriteResult.Saved)
    }

    /** A grant that may not exist, for the assertions about what was *not* handed out. */
    private suspend fun grantOrNone(key: String): Achievement? =
        dao.byKey(userId, key).also { fixture.awaitIdle() }
}
