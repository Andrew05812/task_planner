package com.taskplanner.ui.habits

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.habit.HabitMath
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.HabitRejection
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The habit form: what it reads off a stored row, and what each field it was given ends up as on disk.
 *
 * The texts are read by the screen rather than by the layout, and that is exactly what these tests are about.
 * A blank start day, a count typed into a weekly habit and a minute picked out of a dialog all have to become
 * the schedule the card then prints — a start day off by one, a dropped count or a reminder stored as an
 * instant would show up as a habit that quietly means something else, which no amount of pressing the screen
 * would explain. So each test writes through the form and reads the row back out of SQLite.
 *
 * The other half is the pair of answers a save can give. The form's own mistake — text that names no day, no
 * count — writes nothing and has to be told apart from the repository's refusal of a request it *could* read,
 * because the first one belongs to a field and the second one belongs to the habit.
 */
@RunWith(RobolectricTestRunner::class)
class HabitEditorViewModelTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private lateinit var viewModel: HabitEditorViewModel
    private var userId: Long = -1L

    private lateinit var zone: ZoneId
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val wednesday: LocalDate = LocalDate.of(2026, 6, 3)
    private val mayDay: LocalDate = LocalDate.of(2026, 5, 20)
    private val today: LocalDate = wednesday

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        zone = CalendarMath.systemZone()
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn().id }
        runBlocking { fixture.timeBase.shiftTo(at(today, 12, 0)) }
        viewModel = HabitEditorViewModel(fixture.habitRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    private fun state() = viewModel.state.value

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        fixture.awaitIdle()
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun saved(result: HabitWriteResult): String = when (result) {
        is HabitWriteResult.Saved -> result.habitUuid
        is HabitWriteResult.Rejected -> throw AssertionError("expected a saved habit, got ${result.reason}")
    }

    private fun saved(outcome: HabitSaveOutcome): String = when (outcome) {
        is HabitSaveOutcome.Unreadable ->
            throw AssertionError("expected a written habit, got an unreadable ${outcome.field}")
        is HabitSaveOutcome.Written -> saved(outcome.result)
    }

    private fun rejected(outcome: HabitSaveOutcome): HabitRejection = when (outcome) {
        is HabitSaveOutcome.Unreadable ->
            throw AssertionError("expected the repository's refusal, got an unreadable ${outcome.field}")
        is HabitSaveOutcome.Written -> when (val result = outcome.result) {
            is HabitWriteResult.Saved -> throw AssertionError("expected a refusal, got a Saved habit")
            is HabitWriteResult.Rejected -> result.reason
        }
    }

    private fun unreadable(outcome: HabitSaveOutcome): HabitEditorField = when (outcome) {
        is HabitSaveOutcome.Written ->
            throw AssertionError("expected the form's own mistake, got ${outcome.result}")
        is HabitSaveOutcome.Unreadable -> outcome.field
    }

    /** Issues one save and waits for the answer it hands back, since the write is issued rather than done. */
    private fun save(
        title: String = "run",
        frequency: HabitFrequency = HabitFrequency.DAILY,
        days: List<DayOfWeek> = emptyList(),
        times: String? = null,
        start: String? = null,
        end: String? = null
    ): HabitSaveOutcome {
        var answer: HabitSaveOutcome? = null
        viewModel.save(title, frequency, days, times, start, end) { outcome -> answer = outcome }
        awaitUntil("the save to answer") { answer != null }
        return requireNotNull(answer)
    }

    private suspend fun habit(
        title: String = "run",
        frequency: HabitFrequency = HabitFrequency.DAILY,
        start: LocalDate = monday,
        end: LocalDate? = null,
        days: List<DayOfWeek> = emptyList(),
        times: Int? = null,
        remindMinute: Int? = null
    ): Habit {
        val uuid = saved(
            fixture.habitRepository.create(
                title = title,
                frequency = frequency,
                startDay = start.toEpochDay(),
                daysOfWeek = days,
                timesPerWeek = times,
                endDay = end?.toEpochDay(),
                remindMinuteOfDay = remindMinute
            )
        )
        return fixture.habitRepository.habit(uuid)!!
    }

    /** The row as it sits on disk now, so a test reads what the next screen will be handed. */
    private suspend fun reloaded(row: Habit): Habit = fixture.habitRepository.habit(row.uuid)!!

    private suspend fun storedHabits(): List<Habit> = fixture.habitDao.getAllForUser(userId)

    private fun open(row: Habit) {
        viewModel.load(row.id)
        awaitUntil("the row to arrive") { !state().isLoading }
    }

    @Test
    fun `the form opens empty when no habit was named`() {
        viewModel.load(-1L)
        awaitUntil("the empty form") { !state().isLoading }
        assertTrue(state().isNew)
        assertTrue(state().isRemindable)
        assertNull(state().habit)
        assertNull(state().remindMinuteOfDay)
    }

    @Test
    fun `opening on a stored row prefills the fields the row carries`() = runBlocking {
        val row = habit(
            "Stretch",
            frequency = HabitFrequency.DAYS_OF_WEEK,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            end = LocalDate.of(2026, 6, 30),
            remindMinute = 8 * 60
        )

        open(row)
        assertEquals("Stretch", state().habit?.title)
        assertTrue(state().isEditing)
        assertFalse(state().isNew)
        assertEquals(HabitFrequency.DAYS_OF_WEEK, state().habit?.frequency)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), state().habit?.scheduledWeekdays())
        // The minute the button shows is the one the alarm is armed for, read off the row rather than left to
        // whatever the last dialog displayed.
        assertEquals(8 * 60, state().remindMinuteOfDay)
    }

    @Test
    fun `an id no row holds says the habit is gone rather than offering a blank form`() = runBlocking {
        viewModel.load(4242L)
        awaitUntil("the read to answer") { !state().isLoading }
        assertTrue(state().isMissing)
        assertFalse("a gone habit must not be offered as a new one", state().isNew)
        assertNull(state().habit)
    }

    @Test
    fun `another profile's habit is gone to this form and saving it writes nothing there`() = runBlocking {
        val otherId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Another"))
        val theirs = fixture.habitDao.insertHabit(
            Habit(
                uuid = "habit-of-other", userId = otherId, title = "Their routine",
                frequency = HabitFrequency.DAILY, startDay = monday.toEpochDay(),
                createdAt = at(today, 9, 0), updatedAt = at(today, 9, 0)
            )
        )

        viewModel.load(theirs)
        awaitUntil("the read to answer") { !state().isLoading }
        assertTrue(state().isMissing)

        assertEquals(HabitRejection.NOT_FOUND, rejected(save(title = "Renamed from over here")))
        assertEquals("Their routine", fixture.habitDao.getHabit("habit-of-other", otherId)?.title)
        assertTrue(storedHabits().isEmpty())
    }

    @Test
    fun `a blank start day is the day the habit was chosen rather than an absent one`() = runBlocking {
        saved(save(title = "Run", start = "  "))
        val stored = storedHabits().single()
        assertEquals(today.toEpochDay(), stored.startDay)
        assertNull(stored.endDay)
        assertEquals(HabitFrequency.DAILY, stored.frequency)
        assertEquals(0, stored.daysMask)
    }

    @Test
    fun `a typed start day is stored as the day the user named, not as today`() = runBlocking {
        saved(save(title = "Run", start = "2026-05-20"))
        assertEquals(mayDay.toEpochDay(), storedHabits().single().startDay)
    }

    @Test
    fun `text that names no day is the form's mistake and nothing is written`() = runBlocking {
        val field = unreadable(save(title = "Run", start = "someday"))
        assertEquals(HabitEditorField.START_DAY, field)
        assertTrue("a save the form could not read leaves no row behind", storedHabits().isEmpty())
    }

    @Test
    fun `every field is read before anything is written`() = runBlocking {
        val row = habit("Run", start = mayDay)
        open(row)

        // The title is perfectly readable and the end day is not: a form that wrote field by field would
        // leave the new name stored against a schedule it never finished checking.
        val outcome = save(title = "Renamed", start = "2026-05-20", end = "whenever")
        assertEquals(HabitEditorField.END_DAY, unreadable(outcome))
        assertEquals("Run", reloaded(row).title)
    }

    @Test
    fun `a weekly habit without a usable count names its own field`() = runBlocking {
        listOf(null, "", " ", "0", "8", "eight").forEach { text ->
            val outcome = save(title = "Run", frequency = HabitFrequency.TIMES_PER_WEEK, times = text)
            assertEquals("count $text cannot be read", HabitEditorField.TIMES_PER_WEEK, unreadable(outcome))
        }
        assertTrue(storedHabits().isEmpty())
    }

    @Test
    fun `a weekly habit stores the count the form was given`() = runBlocking {
        saved(save(title = "Run", frequency = HabitFrequency.TIMES_PER_WEEK, times = "3"))
        val stored = storedHabits().single()
        assertEquals(3, stored.timesPerWeek)
        // The weekday mask belongs to the other frequency and stays empty, so the schedule has one author.
        assertEquals(0, stored.daysMask)
    }

    @Test
    fun `a weekday habit stores the days it was shown and no weekly count`() = runBlocking {
        saved(
            save(
                title = "Stretch", frequency = HabitFrequency.DAYS_OF_WEEK,
                days = listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
            )
        )
        val stored = storedHabits().single()
        assertEquals(HabitMath.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)), stored.daysMask)
        assertNull(stored.timesPerWeek)
    }

    @Test
    fun `editing a stored habit rewrites the schedule and leaves the marks where they were`() = runBlocking {
        val row = habit("Run")
        fixture.habitRepository.mark(row.uuid, monday.toEpochDay())
        open(row)

        saved(
            save(
                title = "Stretch", frequency = HabitFrequency.DAYS_OF_WEEK,
                days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
            )
        )
        val stored = reloaded(row)
        assertEquals(1, storedHabits().size)
        assertEquals("Stretch", stored.title)
        assertEquals(HabitFrequency.DAYS_OF_WEEK, stored.frequency)
        assertEquals(HabitMath.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)), stored.daysMask)
        // The editor moves the schedule, never the days the user already stood behind: the mark made while the
        // habit was daily is still the mark it was.
        assertTrue(fixture.habitRepository.isMarked(row.uuid, monday.toEpochDay()))
    }

    @Test
    fun `the picked reminder minute is held by the form and written with the save`() = runBlocking {
        viewModel.load(-1L)
        awaitUntil("the empty form") { !state().isLoading }

        viewModel.setReminder(7 * 60 + 15)
        // The minute lives in the screen rather than in the dialog that produced it, so a rotation cannot
        // drop the time the user just chose.
        assertEquals(7 * 60 + 15, state().remindMinuteOfDay)

        saved(save(title = "Run"))
        assertEquals(7 * 60 + 15, storedHabits().single().remindMinuteOfDay)
    }

    @Test
    fun `clearing the reminder stores no minute on a habit that had one`() = runBlocking {
        val row = habit("Run", remindMinute = 8 * 60)
        open(row)
        assertEquals(8 * 60, state().remindMinuteOfDay)

        viewModel.clearReminder()
        assertNull(state().remindMinuteOfDay)
        saved(save(title = "Run"))
        assertNull(reloaded(row).remindMinuteOfDay)
    }

    @Test
    fun `a habit put away is not offered a reminder from this form`() = runBlocking {
        val row = habit("Run")
        fixture.habitRepository.archive(row.uuid)
        open(reloaded(row))
        assertFalse("a retired habit asks for nothing, so there is nothing to be reminded of", state().isRemindable)
    }

    @Test
    fun `a blank title is the repository's refusal, told apart from the form's own mistake`() = runBlocking {
        val outcome = save(title = "   ")
        assertEquals(HabitRejection.BLANK_TITLE, rejected(outcome))
        assertTrue("a refused title stored no row", storedHabits().isEmpty())
    }

    @Test
    fun `a window that ends before it begins is refused with the habit's own reason`() = runBlocking {
        val outcome = save(title = "Run", start = "2026-06-05", end = "2026-06-02")
        assertEquals(HabitRejection.WINDOW_ENDS_BEFORE_START, rejected(outcome))
        assertTrue(storedHabits().isEmpty())
    }

    @Test
    fun `the empty form refuses to write over an id that is gone`() = runBlocking {
        viewModel.load(4242L)
        awaitUntil("the read to answer") { !state().isLoading }

        val outcome = save(title = "Run")
        assertEquals(HabitRejection.NOT_FOUND, rejected(outcome))
        assertTrue(storedHabits().isEmpty())
    }

    @Test
    fun `the form reads a stored row once, so a re-open does not lose what was typed`() = runBlocking {
        val row = habit("Run", remindMinute = 8 * 60)
        open(row)
        viewModel.setReminder(6 * 60)

        // A configuration change calls load again with the same id the graph handed over. The minute the user
        // just picked stays, because the row is not read a second time and put back over it.
        viewModel.load(row.id)
        fixture.awaitIdle()
        assertEquals(6 * 60, state().remindMinuteOfDay)
        assertEquals("Run", state().habit?.title)
    }
}
