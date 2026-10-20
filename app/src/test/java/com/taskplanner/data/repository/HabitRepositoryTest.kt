package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.habit.HabitAdherence
import com.taskplanner.data.habit.HabitMath
import com.taskplanner.data.habit.StreakUnit
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheck
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.User
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/**
 * Habits over real storage: the schedule the user asked for, the marks they made, and the figures those
 * two produce together.
 *
 * What is proved here is that a habit stores no counts. The series, the "3 of 4", and the percentage are
 * read off the mark rows every time — so §6's "исправление отметки с корректной историей" is checked as the
 * case that would break a stored figure: a mark taken back or moved has to change the series in the same
 * watch that redraws the grid, and the account of the correction has to stay readable while the grid stops
 * counting the day. The unique index on `(habit_uuid, day_marker)` is held up against SQLite itself rather
 * than against the repository's own check, because the double tap this refuses is a race, and only the
 * index is a rule.
 *
 * Two settings are pinned for the whole class, because both belong to the *reader* and the rules under
 * test are the arithmetic: the week is Monday-first, so "this week" is the same seven days wherever the
 * suite runs, and the zone is one with a daylight-saving jump inside the year, so a mark's day can be shown
 * to be resolved on that day's own clock.
 */
@RunWith(RobolectricTestRunner::class)
class HabitRepositoryTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var fixture: AppFixture
    private val repository get() = fixture.habitRepository
    private val habitDao get() = fixture.habitDao
    private var userId: Long = -1L

    /**
     * The zone this suite runs in, read after [setUp] has installed it.
     *
     * Not a property initializer: the class field would be filled from whatever zone the host happens to
     * have, and the tests below would then build their instants in one zone while the repository resolved
     * its marks in another — a difference that only shows up as an offset that is off by the gap between
     * the two, which is exactly what the daylight-saving test is looking for.
     */
    private lateinit var zone: ZoneId

    private val previousMonday: LocalDate = LocalDate.of(2026, 5, 25)
    private val previousWednesday: LocalDate = LocalDate.of(2026, 5, 27)
    private val previousFriday: LocalDate = LocalDate.of(2026, 5, 29)
    private val previousSunday: LocalDate = LocalDate.of(2026, 5, 31)
    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val wednesday: LocalDate = LocalDate.of(2026, 6, 3)
    private val thursday: LocalDate = LocalDate.of(2026, 6, 4)
    private val friday: LocalDate = LocalDate.of(2026, 6, 5)
    private val sunday: LocalDate = LocalDate.of(2026, 6, 7)
    private val today: LocalDate = wednesday

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        // A zone that jumps: the last Sunday of March 2026 springs forward here, so a day before the jump
        // and the clock reading it are measurably different offsets.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        zone = CalendarMath.systemZone()
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    /**
     * Signs the profile in and parks the clock at midday Wednesday, so the day the screen reads as "today"
     * is the day the test names and every derived figure is anchored on the same instant.
     */
    private suspend fun seed(): User {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.shiftTo(at(wednesday, 12, 0))
        return user
    }

    private suspend fun saved(result: HabitWriteResult): String = when (result) {
        is HabitWriteResult.Saved -> result.habitUuid
        is HabitWriteResult.Rejected -> throw AssertionError("expected a saved habit, got ${result.reason}")
    }

    private fun rejected(result: HabitWriteResult): HabitRejection = when (result) {
        is HabitWriteResult.Saved -> throw AssertionError("expected a refusal, got a Saved habit")
        is HabitWriteResult.Rejected -> result.reason
    }

    /** Creates a habit and hands back the stored row, so a test reads figures off what is on disk. */
    private suspend fun habit(
        title: String = "run",
        frequency: HabitFrequency = HabitFrequency.DAILY,
        start: LocalDate = monday,
        days: List<DayOfWeek> = emptyList(),
        times: Int? = null,
        end: LocalDate? = null,
        remindMinute: Int? = null
    ): Habit {
        val uuid = saved(
            repository.create(
                title = title,
                frequency = frequency,
                startDay = start.toEpochDay(),
                daysOfWeek = days,
                timesPerWeek = times,
                endDay = end?.toEpochDay(),
                remindMinuteOfDay = remindMinute
            )
        )
        return repository.habit(uuid)!!
    }

    private suspend fun marked(habit: Habit, vararg days: LocalDate) {
        days.forEach { day ->
            val result = repository.mark(habit.uuid, day.toEpochDay())
            if (result is HabitWriteResult.Rejected) {
                throw AssertionError("marking $day refused: ${result.reason}")
            }
            // Two marks made in one parked millisecond would be indistinguishable in any order-sensitive
            // read, and the clock is parked for the whole test.
            fixture.timeBase.advance(1_000L)
        }
    }

    /** The figures of the week the tests are anchored on, read the way the card reads them. */
    private suspend fun figures(
        habit: Habit,
        from: LocalDate = monday,
        to: LocalDate = sunday
    ): HabitFigures = repository.figuresOf(habit, from.toEpochDay(), to.toEpochDay())

    private suspend fun liveDays(habit: Habit, from: LocalDate = monday, to: LocalDate = sunday) =
        habitDao.liveDays(habit.uuid, userId, from.toEpochDay(), to.toEpochDay())

    /**
     * The frames one watch of [source] drew around [write], up to and including the one that matches a
     * plain read of the same storage.
     *
     * The write is issued only after the watch has answered once, which is what makes this the proof it
     * claims to be: the figure moved because the screen was watching the mark rows, not because anything
     * re-subscribed. [expected] is asked of the storage directly, so a watch that stopped drawing cannot
     * pass by agreeing with itself.
     */
    private suspend fun <T : Any> rebuiltBy(
        source: Flow<T>,
        write: suspend () -> Unit,
        expected: suspend () -> T
    ): List<T> = coroutineScope {
        val frames = CopyOnWriteArrayList<T>()
        val watching = launch { source.collect { frames.add(it) } }
        withTimeout(TIMEOUT_MILLIS) { while (frames.isEmpty()) delay(POLL_MILLIS) }
        write()
        val wanted = expected()
        withTimeout(TIMEOUT_MILLIS) { while (frames.lastOrNull() != wanted) delay(POLL_MILLIS) }
        watching.cancel()
        frames.toList()
    }

    // -------------------------------------------------------------- the schedule the row stores

    @Test
    fun `a daily habit stores its schedule and no counts`() = runBlocking {
        seed()
        val stored = habit(title = "Run every day", times = 3)

        assertEquals("Run every day", stored.title)
        assertEquals(HabitFrequency.DAILY, stored.frequency)
        assertEquals(monday.toEpochDay(), stored.startDay)
        assertNull(stored.endDay)
        assertNull(stored.remindMinuteOfDay)
        // The weekly count belongs to a counted habit only, so a daily row cannot carry one and the
        // figures have exactly one reading of the row.
        assertEquals(0, stored.daysMask)
        assertNull(stored.timesPerWeek)
        assertTrue(stored.isOpen)
    }

    @Test
    fun `a weekday habit names its days in monday-first bits`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )

        // Monday is the lowest bit and Friday the fifth, so the three days read 10101b rather than a mask
        // whose meaning depends on which weekday the host's calendar happens to open on.
        assertEquals(0b10101, stored.daysMask)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            stored.scheduledWeekdays()
        )
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), HabitMath.daysOf(stored.daysMask))
        assertNull(stored.timesPerWeek)
    }

    @Test
    fun `a counted habit keeps the count and no days`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.TIMES_PER_WEEK,
            times = 3,
            // A weekday list sent along with a counted habit: which days carry the three marks is the
            // user's choice, so the mask is emptied rather than stored for a frequency that ignores it.
            days = listOf(DayOfWeek.TUESDAY)
        )

        assertEquals(3, stored.timesPerWeek)
        assertEquals(0, stored.daysMask)
    }

    @Test
    fun `an impossible schedule is refused before it is written`() = runBlocking {
        seed()
        val start = monday.toEpochDay()

        assertEquals(
            HabitRejection.BLANK_TITLE,
            rejected(repository.create("   ", HabitFrequency.DAILY, start))
        )
        assertEquals(
            HabitRejection.EMPTY_WEEKDAY_SET,
            rejected(repository.create("read", HabitFrequency.DAYS_OF_WEEK, start, emptyList()))
        )
        // A week has seven days in it; a zero asks for nothing and a fifteen cannot be kept.
        assertEquals(
            HabitRejection.WEEKLY_COUNT_OUT_OF_RANGE,
            rejected(repository.create("swim", HabitFrequency.TIMES_PER_WEEK, start, emptyList(), null))
        )
        assertEquals(
            HabitRejection.WEEKLY_COUNT_OUT_OF_RANGE,
            rejected(repository.create("swim", HabitFrequency.TIMES_PER_WEEK, start, emptyList(), 0))
        )
        assertEquals(
            HabitRejection.WEEKLY_COUNT_OUT_OF_RANGE,
            rejected(
                repository.create("swim", HabitFrequency.TIMES_PER_WEEK, start, emptyList(), 8)
            )
        )
        assertEquals(
            HabitRejection.WINDOW_ENDS_BEFORE_START,
            rejected(repository.create("read", HabitFrequency.DAILY, start, endDay = start - 1))
        )
        assertEquals(
            HabitRejection.BAD_REMINDER_MINUTE,
            rejected(repository.create("read", HabitFrequency.DAILY, start, remindMinuteOfDay = 24 * 60))
        )
        assertEquals(
            HabitRejection.BAD_REMINDER_MINUTE,
            rejected(repository.create("read", HabitFrequency.DAILY, start, remindMinuteOfDay = -1))
        )

        // Nothing half-written: every one of those attempts left the profile with no rows at all.
        assertEquals(emptyList<Habit>(), habitDao.getAllForUser(userId))
    }

    @Test
    fun `no profile means no habit can be started, kept or read`() = runBlocking {
        assertEquals(
            HabitRejection.NO_PROFILE,
            rejected(repository.create("run", HabitFrequency.DAILY, monday.toEpochDay()))
        )
        val orphan = Habit(userId = -1L, uuid = "orphan", title = "run", startDay = monday.toEpochDay())
        assertEquals(HabitRejection.NO_PROFILE, rejected(repository.mark(orphan.uuid, wednesday.toEpochDay())))
        assertEquals(HabitRejection.NO_PROFILE, rejected(repository.takeBack(orphan.uuid, wednesday.toEpochDay())))
        assertNull(repository.habit(orphan.uuid))

        // A week that a daily habit covers always asks for seven days, even with nobody signed in to keep
        // them: the schedule is the row's, and only the marks are missing.
        val empty = figuresOfNothing()
        assertEquals(0, empty.marks)
        assertEquals(0, empty.streak.current)
        assertFalse(empty.hasAnyMark)
        assertEquals(7, empty.period.asked)
        assertEquals(0, empty.period.kept)
        assertEquals(0, empty.period.percent)
        assertEquals(emptyList<Any>(), repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()).first())
    }

    /** The same read the card makes, with nobody signed in to answer it. */
    private suspend fun figuresOfNothing(): HabitFigures =
        repository.figuresOf(
            Habit(userId = -1L, uuid = "orphan", title = "run", startDay = monday.toEpochDay()),
            monday.toEpochDay(),
            sunday.toEpochDay()
        )

    @Test
    fun `one schedule lives in the row at a time`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        )
        assertEquals(0b11, stored.daysMask)

        // The user decides that the habit is daily after all. The mask goes away rather than sitting in the
        // row unread, so no later read can wonder which of the two schedules the row meant.
        repository.edit(
            habitUuid = stored.uuid,
            title = stored.title,
            frequency = HabitFrequency.DAILY,
            startDay = stored.startDay
        )
        val daily = repository.habit(stored.uuid)!!
        assertEquals(0, daily.daysMask)
        assertNull(daily.timesPerWeek)
        // and the figures ask about every day now: the week's four days become its seven.
        assertEquals(7, figures(daily).thisWeek.asked)
    }

    // ---------------------------------------------------------------- making and refusing a mark

    @Test
    fun `a mark stands on the day it was made for`() = runBlocking {
        seed()
        val stored = habit()
        assertTrue(repository.mark(stored.uuid, wednesday.toEpochDay()) is HabitWriteResult.Saved)

        val check = habitDao.liveMark(stored.uuid, userId, wednesday.toEpochDay())
        assertNotNull(check)
        assertEquals(wednesday.toEpochDay(), check!!.localDay)
        // The marker column is the day while the mark stands, which is what the unique index holds on.
        assertEquals(wednesday.toEpochDay(), check.dayMarker)
        assertTrue(check.isLive)
        assertEquals(stored.uuid, check.habitUuid)
        assertEquals(userId, check.userId)
        assertEquals(listOf(wednesday.toEpochDay()), liveDays(stored))
        assertTrue(repository.isMarked(stored.uuid, wednesday.toEpochDay()))
    }

    @Test
    fun `a backdated mark keeps the day it was resolved at, not the clock of the tap`() = runBlocking {
        seed()
        // Berlin springs forward on the last Sunday of March 2026, so Saturday is on +01:00 and the Monday
        // the user taps on is already on +02:00.
        val beforeTheJump = LocalDate.of(2026, 3, 28)
        val todayInTest = LocalDate.of(2026, 3, 30)
        fixture.timeBase.shiftTo(at(todayInTest, 12, 0))

        val stored = habit(start = beforeTheJump)
        assertTrue(repository.mark(stored.uuid, beforeTheJump.toEpochDay()) is HabitWriteResult.Saved)

        val check = habitDao.liveMark(stored.uuid, userId, beforeTheJump.toEpochDay())!!
        assertEquals(60, check.utcOffsetMinutes)
        assertEquals(120, WorkTimeMath.utcOffsetMinutesOf(fixture.timeBase.wall, zone))
        assertEquals(WorkTimeMath.epochDayOf(at(beforeTheJump, 12, 0), zone), check.localDay)
    }

    @Test
    fun `the same day cannot be marked twice, and the index is what says so`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, wednesday)

        assertEquals(
            HabitRejection.ALREADY_MARKED,
            rejected(repository.mark(stored.uuid, wednesday.toEpochDay()))
        )
        assertEquals(1, habitDao.countLive(stored.uuid, userId, monday.toEpochDay(), sunday.toEpochDay()))

        // The refusal is SQLite's, not a check the repository could be raced past: a second live mark for
        // the same day is written straight to the DAO and aborted by the unique index.
        val raced = runCatching {
            habitDao.insertCheck(
                HabitCheck(
                    uuid = "second-tap", habitUuid = stored.uuid, userId = userId,
                    localDay = wednesday.toEpochDay(), utcOffsetMinutes = 60,
                    dayMarker = wednesday.toEpochDay()
                )
            )
        }
        assertTrue("the unique index must refuse a second live mark", raced.isFailure)

        // Retired rows carry no marker, and SQLite treats nulls as distinct inside a unique index — which
        // is what lets the same day be marked, unmarked and marked again without losing either story.
        habitDao.retireMark(stored.uuid, userId, wednesday.toEpochDay(), fixture.timeBase.wall)
        val retiredAgain = runCatching {
            habitDao.insertCheck(
                HabitCheck(
                    uuid = "twice-retired", habitUuid = stored.uuid, userId = userId,
                    localDay = wednesday.toEpochDay(), utcOffsetMinutes = 60, dayMarker = null,
                    isDeleted = true
                )
            )
        }
        assertTrue("a retired mark must not hold the day it left", retiredAgain.isSuccess)
        // ...and no figure counts it: the day is free again for a live mark.
        assertEquals(0, habitDao.countLive(stored.uuid, userId, wednesday.toEpochDay(), wednesday.toEpochDay()))
    }

    @Test
    fun `a day outside the habit's own window cannot be marked`() = runBlocking {
        seed()
        val stored = habit(start = monday, end = tuesday)

        // Before the habit began: nothing there can count, so a backdated mark would be an aim the user
        // never had.
        assertEquals(
            HabitRejection.DAY_OUTSIDE_WINDOW,
            rejected(repository.mark(stored.uuid, previousSunday.toEpochDay()))
        )
        // After it stopped.
        assertEquals(
            HabitRejection.DAY_OUTSIDE_WINDOW,
            rejected(repository.mark(stored.uuid, wednesday.toEpochDay()))
        )
        // Inside it, and the last day is as markable as the first.
        assertTrue(repository.mark(stored.uuid, tuesday.toEpochDay()) is HabitWriteResult.Saved)
        assertEquals(listOf(tuesday.toEpochDay()), liveDays(stored))
    }

    @Test
    fun `a mark for a day that has not arrived is refused`() = runBlocking {
        seed()
        val stored = habit()

        // The clock is parked at midday Wednesday, so Thursday is a promise rather than a fact.
        assertEquals(HabitRejection.DAY_IN_FUTURE, rejected(repository.mark(stored.uuid, thursday.toEpochDay())))
        assertEquals(HabitRejection.DAY_IN_FUTURE, rejected(repository.mark(stored.uuid, sunday.toEpochDay())))
        // Today is allowed: the day is half over and the user knows whether they ran.
        assertTrue(repository.mark(stored.uuid, wednesday.toEpochDay()) is HabitWriteResult.Saved)
    }

    @Test
    fun `a day the schedule never named is kept work and not an answer`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        marked(stored, monday, tuesday)

        // Tuesday is not one of the days this habit asks for, so it cannot raise the share kept — and it is
        // still a mark, so the grid shows it and the count of marks includes it.
        val figures = figures(stored)
        assertEquals(HabitAdherence(1, 3), figures.period)
        assertEquals(33, figures.period.percent)
        assertEquals(2, figures.marks)
        // It neither extends nor breaks the series: the walk only ever steps over asked days.
        assertEquals(1, figures.streak.current)
        assertEquals(1, figures.streak.best)
        assertEquals(listOf(monday.toEpochDay(), tuesday.toEpochDay()), liveDays(stored))
    }

    // ------------------------------------------------------------------------- the series

    @Test
    fun `a daily series counts the days the habit asked for`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday, wednesday)

        val streak = figures(stored).streak
        assertEquals(3, streak.current)
        assertEquals(3, streak.best)
        assertEquals(StreakUnit.DAYS, streak.unit)
        assertTrue(streak.isAlive)
    }

    @Test
    fun `a habit with no marks has no series to report`() = runBlocking {
        seed()
        val stored = habit()

        val streak = figures(stored).streak
        assertEquals(0, streak.current)
        assertEquals(0, streak.best)
        assertFalse(streak.isAlive)
        assertFalse(figures(stored).hasAnyMark)
    }

    @Test
    fun `today still open does not break the series`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday)

        // Wednesday is asked for and empty, and the day has nine hours left in it: a card that read zero
        // would be reporting a failure the user has not had the chance to avoid.
        assertEquals(2, figures(stored).streak.current)

        // Once Wednesday is over, the same empty day is the break.
        fixture.timeBase.shiftTo(at(thursday, 12, 0))
        val after = figures(stored, to = thursday)
        assertEquals(0, after.streak.current)
        assertEquals(thursday.toEpochDay(), after.today)
        // and the best figure keeps what the run was.
        assertEquals(2, after.streak.best)
    }

    @Test
    fun `a day between the ones it names is skipped rather than a gap`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            start = previousMonday,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        marked(stored, previousMonday, LocalDate.of(2026, 5, 27), previousFriday, monday, wednesday)

        // Five asked days kept over two weeks, with every weekend and every Tuesday and Thursday between
        // them: the series is about this habit's schedule, not about the calendar's days.
        val streak = figures(stored, from = previousMonday).streak
        assertEquals(5, streak.current)
        assertEquals(5, streak.best)
    }

    @Test
    fun `a series breaks where a mark was taken back`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday, wednesday)
        assertEquals(3, figures(stored).streak.current)

        assertTrue(repository.takeBack(stored.uuid, tuesday.toEpochDay()) is HabitWriteResult.Saved)

        val after = figures(stored)
        // Wednesday still stands and the day before it does not, so the run is one day long again — and
        // the best figure cannot remember the three that were.
        assertEquals(1, after.streak.current)
        assertEquals(1, after.streak.best)
        assertEquals(HabitAdherence(2, 7), after.period)
    }

    @Test
    fun `a weekly series counts weeks and not days`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.TIMES_PER_WEEK,
            start = previousMonday,
            times = 3
        )
        // The week that has ended holds its three marks; this one holds two so far.
        marked(stored, previousMonday, LocalDate.of(2026, 5, 26), previousFriday, monday, tuesday)

        val streak = figures(stored, from = previousMonday).streak
        // The current week is unfinished rather than missed, so the run is the one week behind it.
        assertEquals(1, streak.current)
        assertEquals(1, streak.best)
        assertEquals(StreakUnit.WEEKS, streak.unit)

        // The third mark lands this week, and both figures move with it in the same read.
        assertTrue(repository.mark(stored.uuid, wednesday.toEpochDay()) is HabitWriteResult.Saved)
        val kept = figures(stored, from = previousMonday).streak
        assertEquals(2, kept.current)
        assertEquals(2, kept.best)
    }

    @Test
    fun `a weekly habit does not ask about the days between its marks`() = runBlocking {
        seed()
        // The week is read after it has closed, because a mark can only stand on a day that has arrived:
        // here the three are Monday, Tuesday and Friday, with the two days between them empty.
        fixture.timeBase.shiftTo(at(sunday, 12, 0))
        val stored = habit(frequency = HabitFrequency.TIMES_PER_WEEK, start = monday, times = 3)
        marked(stored, monday, tuesday, friday)

        // Three marks spread across one week with a gap of two days: kept, and the day-shaped reading would
        // have called the same marks a 3 of 7.
        val figures = figures(stored)
        // The window is one week long and the week reached its count, so the share is in weeks: one of one.
        assertEquals(HabitAdherence(1, 1), figures.period)
        assertEquals(100, figures.period.percent)
        // The week's own sentence stays in marks, which is what the card prints beside the streak.
        assertEquals(HabitAdherence(3, 3), figures.thisWeek)
        assertEquals(3, figures.marks)
    }

    // --------------------------------------------------------------------- the percentage

    @Test
    fun `the percentage is of what the schedule asked for`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday, wednesday)

        val figures = figures(stored)
        // A week the habit is daily in asks for seven days, and three were kept.
        assertEquals(HabitAdherence(3, 7), figures.period)
        assertEquals(42, figures.period.percent)
        assertEquals(3, figures.marks)
        assertTrue(figures.markedToday)
    }

    @Test
    fun `a week the habit did not exist in is not asked for`() = runBlocking {
        seed()
        // The habit begins on Wednesday of a Monday-first week: the two days before it cannot be owed.
        val stored = habit(start = wednesday)
        marked(stored, wednesday)

        val figures = figures(stored)
        assertEquals(HabitAdherence(1, 5), figures.period)
        assertEquals(HabitAdherence(1, 5), figures.thisWeek)
        assertEquals(20, figures.period.percent)
    }

    @Test
    fun `a window that asked for nothing has no percentage`() = runBlocking {
        seed()
        val stored = habit(start = wednesday)
        marked(stored, wednesday)

        // The read is Monday and Tuesday only, and the habit began Wednesday: there is no share of nothing.
        val figures = figures(stored, from = monday, to = tuesday)
        assertEquals(0, figures.period.asked)
        assertNull(figures.period.percent)
        assertEquals(0, figures.marks)
    }

    @Test
    fun `this week says how many of what the week asked`() = runBlocking {
        seed()
        val stored = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            start = previousMonday,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        marked(stored, monday, tuesday, wednesday)

        val figures = figures(stored, from = previousMonday)
        assertEquals(HabitAdherence(3, 4), figures.thisWeek)
        assertEquals(75, figures.thisWeek.percent)
        // Two weeks of the same four named days with only this week's marks: the period is the whole read,
        // and the week the habit had not started asking in still owed its four days.
        assertEquals(HabitAdherence(3, 8), figures.period)
    }

    @Test
    fun `the figures are of the window the read named`() = runBlocking {
        seed()
        val stored = habit(start = previousMonday)
        marked(stored, previousSunday, monday, tuesday, wednesday)

        // A read over one week sees one week: the marks, the share and the days it returns are the window's,
        // which is what keeps a long habit from loading its whole life to print a card.
        val week = figures(stored, from = monday, to = sunday)
        assertEquals(3, week.marks)
        assertEquals(HabitAdherence(3, 7), week.period)
        assertEquals(3, week.streak.current)
        assertEquals(
            listOf(monday.toEpochDay(), tuesday.toEpochDay(), wednesday.toEpochDay()),
            liveDays(stored)
        )
        assertEquals(4, repository.markedDays(stored.uuid, previousMonday.toEpochDay(), sunday.toEpochDay()).size)

        // The same run read a week further back is one day longer, because the walk only ever steps over the
        // marks the read was handed and stops at the edge of them: a short window says "at least", never "no
        // more than".
        val longer = figures(stored, from = previousMonday, to = sunday)
        assertEquals(4, longer.marks)
        assertEquals(HabitAdherence(4, 14), longer.period)
        assertEquals(4, longer.streak.current)
        assertEquals(4, longer.streak.best)
    }

    // ---------------------------------------------------------------- figures that move by themselves

    @Test
    fun `a mark moves the series with no reload`() = runBlocking {
        seed()
        val stored = habit()

        val frames = rebuiltBy(
            source = repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()),
            write = { repository.mark(stored.uuid, wednesday.toEpochDay()) },
            expected = {
                val latest = repository.habit(stored.uuid)!!
                listOf(HabitWithFigures(latest, repository.figuresOf(latest, monday.toEpochDay(), sunday.toEpochDay())))
            }
        )

        assertEquals(0, frames.first().single().figures.streak.current)
        assertFalse(frames.first().single().figures.markedToday)
        val last = frames.last().single()
        assertEquals(1, last.figures.streak.current)
        assertTrue(last.figures.markedToday)
        assertEquals(HabitAdherence(1, 7), last.figures.period)
        // The watch was never restarted between the two frames, so the second one is the invalidation's
        // doing and not a re-subscription.
        assertTrue(frames.size >= 2)
    }

    @Test
    fun `taking a mark back moves every figure that mentions it`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday)

        val frames = rebuiltBy(
            source = repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()),
            write = { repository.takeBack(stored.uuid, monday.toEpochDay()) },
            expected = {
                val latest = repository.habit(stored.uuid)!!
                listOf(HabitWithFigures(latest, repository.figuresOf(latest, monday.toEpochDay(), sunday.toEpochDay())))
            }
        )

        assertEquals(2, frames.first().single().figures.streak.current)
        assertEquals(1, frames.last().single().figures.streak.current)
        // Nothing stored had to be adjusted for the number to fall: the series was never a column.
        assertEquals(HabitAdherence(1, 7), frames.last().single().figures.period)
    }

    @Test
    fun `a habit put away is not asked for and keeps its record`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday, wednesday)
        val other = habit(title = "read", start = monday)

        assertTrue(repository.archive(stored.uuid) is HabitWriteResult.Saved)

        val open = repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()).first()
        assertEquals(listOf(other.uuid), open.map { it.habit.uuid })
        val archived = repository.observeArchivedHabits(monday.toEpochDay(), sunday.toEpochDay()).first()
        assertEquals(1, archived.size)
        // The marks the user made are still the record of what they did: a figure that fell to zero on
        // archive would read as though the work had.
        assertEquals(3, archived.single().figures.streak.current)
        assertEquals(HabitAdherence(3, 7), archived.single().figures.period)
        assertEquals(3, habitDao.countLive(stored.uuid, userId, monday.toEpochDay(), sunday.toEpochDay()))

        // Asking for it again is refused — the screen that still holds the card is stale — while a
        // correction to a mark it already holds is allowed.
        assertEquals(HabitRejection.ARCHIVED, rejected(repository.mark(stored.uuid, thursday.toEpochDay())))
        assertTrue(repository.takeBack(stored.uuid, wednesday.toEpochDay()) is HabitWriteResult.Saved)

        // Bringing it back asks about it again, with the record it kept while it was put away still on it.
        assertTrue(repository.unarchive(stored.uuid) is HabitWriteResult.Saved)
        val openAgain = repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()).first()
        assertEquals(
            listOf(other.uuid, stored.uuid).sorted(),
            openAgain.map { it.habit.uuid }.sorted()
        )
        val restored = openAgain.single { it.habit.uuid == stored.uuid }.figures
        assertEquals(2, restored.streak.current)
        assertEquals(HabitAdherence(2, 7), restored.period)
    }

    // ------------------------------------------------------------------ corrections and their history

    @Test
    fun `taking a mark back records the day it stood on`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday, wednesday)
        val tuesdayMark = habitDao.liveMark(stored.uuid, userId, tuesday.toEpochDay())!!

        repository.takeBack(stored.uuid, tuesday.toEpochDay(), reason = "it was the wrong day")

        val change = habitDao.checkChangesOf(stored.uuid, userId).single()
        assertEquals(tuesdayMark.uuid, change.checkUuid)
        assertEquals(stored.uuid, change.habitUuid)
        assertEquals(userId, change.userId)
        assertEquals(tuesday.toEpochDay(), change.oldLocalDay)
        // null on the new side is the sentence "taken back", not "moved nowhere".
        assertNull(change.newLocalDay)
        assertEquals("it was the wrong day", change.reason)

        // The row itself is retired rather than gone, and keeps the day it was made for.
        val retired = habitDao.getCheck(tuesdayMark.uuid, userId)!!
        assertTrue(retired.isDeleted)
        assertNull(retired.dayMarker)
        assertEquals(tuesday.toEpochDay(), retired.localDay)
        assertFalse(retired.isLive)
        assertEquals(listOf(monday.toEpochDay(), wednesday.toEpochDay()), liveDays(stored))

        // The freed marker makes the day markable again, and the second mark is a new row — the history of
        // the first is not overwritten by it.
        assertTrue(repository.mark(stored.uuid, tuesday.toEpochDay()) is HabitWriteResult.Saved)
        assertEquals(listOf(monday.toEpochDay(), tuesday.toEpochDay(), wednesday.toEpochDay()), liveDays(stored))
        assertEquals(1, habitDao.checkChangesOf(stored.uuid, userId).size)
        assertEquals(2, habitDao.getAllChecksForUser(userId).count { it.localDay == tuesday.toEpochDay() })
    }

    @Test
    fun `a reason left out stays left out`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, wednesday)

        repository.takeBack(stored.uuid, wednesday.toEpochDay())

        // The words on a correction are the user's, and nothing is invented for them.
        assertNull(habitDao.checkChangesOf(stored.uuid, userId).single().reason)
    }

    @Test
    fun `a move writes the day it left and the day it arrived at`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday)
        val old = habitDao.liveMark(stored.uuid, userId, monday.toEpochDay())!!

        assertTrue(repository.moveMark(stored.uuid, monday.toEpochDay(), tuesday.toEpochDay(), "it was Tuesday") is HabitWriteResult.Saved)

        val live = habitDao.liveDays(stored.uuid, userId, monday.toEpochDay(), sunday.toEpochDay())
        assertEquals(listOf(tuesday.toEpochDay()), live)
        // Two rows, never one edited in place: the day the user stood behind first is still there, retired.
        val retired = habitDao.getCheck(old.uuid, userId)!!
        assertTrue(retired.isDeleted)
        assertNull(retired.dayMarker)
        assertEquals(monday.toEpochDay(), retired.localDay)

        val change = habitDao.checkChangesOf(stored.uuid, userId).single()
        assertEquals(old.uuid, change.checkUuid)
        assertEquals(monday.toEpochDay(), change.oldLocalDay)
        assertEquals(tuesday.toEpochDay(), change.newLocalDay)
        assertEquals("it was Tuesday", change.reason)

        val figures = figures(stored)
        assertEquals(HabitAdherence(1, 7), figures.period)
        assertFalse(figures.markedToday)
    }

    @Test
    fun `a move onto a held day is refused and leaves both days alone`() = runBlocking {
        seed()
        val stored = habit()
        marked(stored, monday, tuesday)

        assertEquals(
            HabitRejection.TARGET_DAY_TAKEN,
            rejected(repository.moveMark(stored.uuid, monday.toEpochDay(), tuesday.toEpochDay()))
        )

        // A swap that would have quietly destroyed one of the two marks rolled back whole: both days still
        // stand, and the account of the attempt was not written.
        assertEquals(listOf(monday.toEpochDay(), tuesday.toEpochDay()), liveDays(stored))
        assertEquals(emptyList<Any>(), habitDao.checkChangesOf(stored.uuid, userId))
    }

    @Test
    fun `a move cannot put a mark in a day the habit never had`() = runBlocking {
        seed()
        val stored = habit(start = monday, end = wednesday)
        marked(stored, monday)
        val open = habit(title = "stretch", start = monday)
        marked(open, monday)

        // Past the last day the habit was ever asked for.
        assertEquals(
            HabitRejection.DAY_OUTSIDE_WINDOW,
            rejected(repository.moveMark(stored.uuid, monday.toEpochDay(), thursday.toEpochDay()))
        )
        // Into a day that has not arrived: with the window still open it is the second rule that speaks, and
        // the order of the two is the order the screen can name them in.
        assertEquals(
            HabitRejection.DAY_IN_FUTURE,
            rejected(repository.moveMark(open.uuid, monday.toEpochDay(), thursday.toEpochDay()))
        )
        // Neither refusal moved anything.
        assertEquals(listOf(monday.toEpochDay()), liveDays(stored))
        assertEquals(listOf(monday.toEpochDay()), liveDays(open))
        // A move to the day it is already on is the same assertion, not a correction.
        assertTrue(repository.moveMark(stored.uuid, monday.toEpochDay(), monday.toEpochDay()) is HabitWriteResult.Saved)
        assertEquals(emptyList<Any>(), habitDao.checkChangesOf(stored.uuid, userId))
    }

    @Test
    fun `a mark that is not there cannot be taken back or moved`() = runBlocking {
        seed()
        val stored = habit()

        assertEquals(HabitRejection.NO_SUCH_MARK, rejected(repository.takeBack(stored.uuid, tuesday.toEpochDay())))
        assertEquals(
            HabitRejection.NO_SUCH_MARK,
            rejected(repository.moveMark(stored.uuid, tuesday.toEpochDay(), wednesday.toEpochDay()))
        )
        marked(stored, tuesday)
        assertTrue(repository.takeBack(stored.uuid, tuesday.toEpochDay()) is HabitWriteResult.Saved)
        // The second tap of the same button took back nothing, and wrote no correction for it.
        assertEquals(HabitRejection.NO_SUCH_MARK, rejected(repository.takeBack(stored.uuid, tuesday.toEpochDay())))
        assertEquals(1, habitDao.checkChangesOf(stored.uuid, userId).size)
        assertEquals(0, habitDao.countLive(stored.uuid, userId, monday.toEpochDay(), sunday.toEpochDay()))
    }

    @Test
    fun `the corrections of one habit name only that habit's days`() = runBlocking {
        seed()
        val mine = habit(title = "run")
        marked(mine, monday)
        fixture.timeBase.advance(60_000L)
        repository.takeBack(mine.uuid, monday.toEpochDay(), reason = "mine")

        val other = habit(title = "read")
        marked(other, tuesday)
        fixture.timeBase.advance(60_000L)
        repository.takeBack(other.uuid, tuesday.toEpochDay(), reason = "theirs")

        // One habit's account names only its own days, in the order they happened.
        val account = habitDao.checkChangesOf(mine.uuid, userId)
        assertEquals(1, account.size)
        assertEquals("mine", account.single().reason)
        assertEquals(monday.toEpochDay(), account.single().oldLocalDay)

        // and the profile's feed is newest first, cut where the caller asked.
        val feed = repository.recentCheckChanges(5)
        assertEquals(listOf("theirs", "mine"), feed.map { it.reason })
        assertEquals(listOf("theirs"), repository.recentCheckChanges(1).map { it.reason })
    }

    // --------------------------------------------------------------------------- profile isolation

    @Test
    fun `another profile's habit cannot be kept, corrected or changed`() = runBlocking {
        val mine = seed()
        val stored = habit(title = "run")
        marked(stored, monday)

        val theirs = fixture.signIn(name = "Other", email = "other@example.com")
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.mark(stored.uuid, wednesday.toEpochDay())))
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.takeBack(stored.uuid, monday.toEpochDay())))
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.moveMark(stored.uuid, monday.toEpochDay(), wednesday.toEpochDay())))
        assertEquals(
            HabitRejection.NOT_FOUND,
            rejected(repository.edit(stored.uuid, "renamed", HabitFrequency.DAILY, monday.toEpochDay()))
        )
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.archive(stored.uuid)))
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.remove(stored.uuid)))
        assertNull(repository.habit(stored.uuid))
        assertEquals(emptyList<Any>(), repository.markedDays(stored.uuid, monday.toEpochDay(), sunday.toEpochDay()))
        assertEquals(emptyList<Any>(), repository.checkChangesOf(stored.uuid))
        assertEquals(emptyList<Any>(), repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()).first())

        // Nothing the second profile tried touched the first one's rows.
        fixture.switchTo(mine)
        assertEquals(listOf(monday.toEpochDay()), liveDays(stored))
        assertTrue(repository.habit(stored.uuid)!!.isOpen)
        assertEquals(emptyList<Any>(), habitDao.checkChangesOf(stored.uuid, theirs.id))
        assertEquals(mine.id, stored.userId)
        assertEquals(mine.id, fixture.sessionRepository.currentUserId())
    }

    @Test
    fun `a habit uuid belongs to one profile and cannot be handed to another`() = runBlocking {
        val mine = seed()
        val theirs = fixture.signIn(name = "Other", email = "other@example.com")
        val shared = "shared-habit-uuid"
        habitDao.insertHabit(
            Habit(uuid = shared, userId = mine.id, title = "run", startDay = monday.toEpochDay())
        )

        // The unique index on habits.uuid is what makes the identity one account's: a second profile cannot
        // write a habit of its own under the same uuid, so the marks of the two can never be counted
        // together by an import that guessed wrong.
        val raced = runCatching {
            habitDao.insertHabit(
                Habit(uuid = shared, userId = theirs.id, title = "run", startDay = monday.toEpochDay())
            )
        }
        assertTrue("the unique index must refuse a uuid two profiles both claim", raced.isFailure)

        // Which is why the profile guard is about a screen that has gone stale rather than about a row two
        // accounts share: the other profile reads nothing of it, and cannot change it.
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.mark(shared, wednesday.toEpochDay())))
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.archive(shared)))
        assertEquals(HabitRejection.NOT_FOUND, rejected(repository.remove(shared)))
        assertNull(repository.habit(shared))
        assertEquals(emptyList<Any>(), repository.markedDays(shared, monday.toEpochDay(), sunday.toEpochDay()))
        assertEquals(emptyList<Any>(), repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()).first())

        fixture.switchTo(mine)
        assertTrue(repository.mark(shared, wednesday.toEpochDay()) is HabitWriteResult.Saved)
        assertEquals(listOf(wednesday.toEpochDay()), repository.markedDays(shared, monday.toEpochDay(), sunday.toEpochDay()))
        assertEquals(HabitAdherence(1, 7), figures(repository.habit(shared)!!).period)
    }

    @Test
    fun `deleting a habit retires its marks and keeps its account`() = runBlocking {
        val owner = seed()
        val doomed = habit(title = "run")
        val kept = habit(title = "read")
        marked(doomed, monday, tuesday)
        marked(kept, monday)
        val doomedTuesday = habitDao.liveMark(doomed.uuid, userId, tuesday.toEpochDay())!!
        repository.takeBack(doomed.uuid, tuesday.toEpochDay(), reason = "wrong")

        assertTrue(repository.remove(doomed.uuid) is HabitWriteResult.Saved)

        assertNull(repository.habit(doomed.uuid))
        val tombstone = habitDao.getAllForUser(owner.id).single { it.uuid == doomed.uuid }
        assertTrue(tombstone.isDeleted)
        // A deleted habit's marks stop standing — they would otherwise keep a habit the user threw away in
        // the profile's marked days — while the rows and the correction of one of them stay readable.
        assertEquals(0, habitDao.countLive(doomed.uuid, owner.id, monday.toEpochDay(), sunday.toEpochDay()))
        assertEquals(0, habitDao.liveDays(doomed.uuid, owner.id, monday.toEpochDay(), sunday.toEpochDay()).size)
        assertTrue(habitDao.getCheck(doomedTuesday.uuid, owner.id)!!.isDeleted)
        assertEquals(1, habitDao.checkChangesOf(doomed.uuid, owner.id).size)
        // The other habit of the same profile is untouched: the retirement is keyed by habit, not by user.
        assertEquals(listOf(monday.toEpochDay()), habitDao.liveDays(kept.uuid, owner.id, monday.toEpochDay(), sunday.toEpochDay()))
        assertEquals(emptyList<Any>(), repository.observeOpenHabits(monday.toEpochDay(), sunday.toEpochDay()).first().filter { it.habit.uuid == doomed.uuid })
    }

    @Test
    fun `an edit leaves the marks where the user put them`() = runBlocking {
        seed()
        val stored = habit(frequency = HabitFrequency.DAYS_OF_WEEK, days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        marked(stored, monday, wednesday)

        assertTrue(
            repository.edit(
                habitUuid = stored.uuid,
                title = "moved to weekends",
                frequency = HabitFrequency.DAYS_OF_WEEK,
                startDay = stored.startDay,
                daysOfWeek = listOf(DayOfWeek.SATURDAY),
                remindMinuteOfDay = 8 * 60
            ) is HabitWriteResult.Saved
        )

        val updated = repository.habit(stored.uuid)!!
        assertEquals("moved to weekends", updated.title)
        assertEquals(8 * 60, updated.remindMinuteOfDay)
        // The marks are not adjusted to the new schedule: they are what the user did, and the figures now
        // answer the new question about them.
        assertEquals(listOf(monday.toEpochDay(), wednesday.toEpochDay()), liveDays(stored))
        val figures = figures(updated)
        assertEquals(2, figures.marks)
        assertEquals(0, figures.period.kept)
        assertEquals(1, figures.period.asked)
        assertEquals(0, figures.streak.current)
        assertTrue(figures.hasAnyMark)
    }

    companion object {
        /** How long a reactive test waits for a frame Room has not drawn yet. */
        private const val TIMEOUT_MILLIS = 10_000L
        private const val POLL_MILLIS = 10L
    }
}
