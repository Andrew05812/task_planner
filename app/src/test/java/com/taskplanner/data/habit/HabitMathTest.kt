package com.taskplanner.data.habit

import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The habit rules on their own, with no database under them.
 *
 * [HabitRepositoryTest] proves the same arithmetic against real SQLite, but it can only reach the rules by
 * writing rows — and three of them cannot be written at all: the weekday mask's bit order, the week
 * boundary a user in Sunday-first America gets, and the instant a reminder lands on when the minute the
 * user chose does not exist on that day. Those are the subject of this file. They are also the rules that
 * would be silently wrong rather than loudly wrong: a habit would keep counting, and only the number it
 * printed would be a different one.
 *
 * Every call here passes its zone and its first weekday explicitly. That is not ceremony — the point of
 * these tests is that the same row means the same thing to two readers whose calendars open on different
 * days and whose clocks jumped last month, which a test that let either default would be unable to show.
 */
class HabitMathTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    private val monday = day(2026, 6, 1)
    private val tuesday = day(2026, 6, 2)
    private val wednesday = day(2026, 6, 3)
    private val thursday = day(2026, 6, 4)
    private val friday = day(2026, 6, 5)
    private val saturday = day(2026, 6, 6)
    private val sunday = day(2026, 6, 7)

    /** A row shaped by hand, because none of these rules needs a repository to state them. */
    private fun habit(
        frequency: HabitFrequency = HabitFrequency.DAILY,
        mask: Int = 0,
        times: Int? = null,
        start: Long = monday,
        end: Long? = null,
        remindMinute: Int? = null,
        archivedAt: Long? = null,
        isDeleted: Boolean = false
    ): Habit = Habit(
        uuid = "h",
        userId = 1L,
        title = "run",
        frequency = frequency,
        daysMask = mask,
        timesPerWeek = times,
        startDay = start,
        endDay = end,
        remindMinuteOfDay = remindMinute,
        archivedAt = archivedAt,
        isDeleted = isDeleted
    )

    /** The instant [localDateTime] means in [zone], for reading a reminder's target back. */
    private fun instantOf(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int): Long =
        LocalDate.of(year, month, dayOfMonth).atTime(hour, minute).atZone(berlin).toInstant().toEpochMilli()

    // ------------------------------------------------------------------------ the stored weekday mask

    @Test
    fun `the mask puts monday in the lowest bit and sunday in the highest`() {
        assertEquals(1, HabitMath.maskOf(listOf(DayOfWeek.MONDAY)))
        assertEquals(0b1000000, HabitMath.maskOf(listOf(DayOfWeek.SUNDAY)))
        assertEquals(0b10101, HabitMath.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        assertEquals(0, HabitMath.maskOf(emptyList()))

        // Bit order is the whole difference between a mask that means the same week everywhere and one that
        // means the reader's own week: Sunday is the seventh bit, not the first.
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            HabitMath.daysOf(0b10101)
        )
        assertEquals(listOf(DayOfWeek.SUNDAY), HabitMath.daysOf(0b1000000))
    }

    @Test
    fun `a mask and its days read back the same whatever order they were given in`() {
        val given = listOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY)
        val mask = HabitMath.maskOf(given)

        // The stored form is a set, so the days the screen shows come back in calendar order rather than in
        // the order the editor happened to tap them.
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY),
            HabitMath.daysOf(mask)
        )
        assertEquals(given.toSet(), HabitMath.daysOf(mask).toSet())
        // June 2026 opens on a Monday, so the weekday's own number is its day of the month and each of the
        // four named days is asked for and the other three are not.
        for (offset in 1..7) {
            val weekday = DayOfWeek.of(offset)
            val juneDay = LocalDate.of(2026, 6, offset)
            assertEquals(juneDay.toString(), weekday in given, HabitMath.isWeekdayIn(mask, juneDay.toEpochDay()))
        }
    }

    // -------------------------------------------------------------------------------- the week boundary

    @Test
    fun `the week starts where the reader's calendar starts`() {
        // June 3 2026 is a Wednesday: a Monday-first grid began it on the 1st, an American one on the 31st.
        assertEquals(monday, HabitMath.weekStart(wednesday, DayOfWeek.MONDAY))
        assertEquals(day(2026, 5, 31), HabitMath.weekStart(wednesday, DayOfWeek.SUNDAY))
        // A week's first day is its own anchor: the start does not move when the anchor is the boundary.
        assertEquals(monday, HabitMath.weekStart(monday, DayOfWeek.MONDAY))
        assertEquals(monday, HabitMath.weekStart(sunday, DayOfWeek.MONDAY))
        assertEquals(sunday, HabitMath.weekStart(sunday, DayOfWeek.SUNDAY))

        assertEquals(monday..sunday, HabitMath.weekRange(wednesday, DayOfWeek.MONDAY))
        assertEquals(7, HabitMath.weekRange(wednesday, DayOfWeek.SUNDAY).count())
    }

    @Test
    fun `a week counts the marks it holds and no others`() {
        val marked = setOf(day(2026, 5, 31), monday, wednesday, day(2026, 6, 8))

        assertEquals(2, HabitMath.marksInWeek(marked, wednesday, DayOfWeek.MONDAY))
        // The same marks read by a Sunday-first calendar: May 31 opens its week, so it joins June 1's, and
        // June 8 leaves June 3's — the count of the week moves with the boundary, not with the marks.
        assertEquals(3, HabitMath.marksInWeek(marked, wednesday, DayOfWeek.SUNDAY))
    }

    // ------------------------------------------------------------------- what a schedule asks about a day

    @Test
    fun `each frequency asks about a day in its own way`() {
        val daily = habit()
        assertTrue(HabitMath.isAskedOn(daily, wednesday))
        assertFalse("nothing before the habit began can be owed", HabitMath.isAskedOn(daily, monday - 1))

        val weekend = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            mask = HabitMath.maskOf(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        )
        assertTrue(HabitMath.isAskedOn(weekend, saturday))
        assertTrue(HabitMath.isAskedOn(weekend, sunday))
        assertFalse(HabitMath.isAskedOn(weekend, wednesday))

        // A counted habit asks for its days all the same — which ones carry the marks is the user's choice —
        // but it stops asking the day its window ends.
        val counted = habit(frequency = HabitFrequency.TIMES_PER_WEEK, times = 3, start = monday, end = friday)
        assertTrue(HabitMath.isAskedOn(counted, wednesday))
        assertTrue(HabitMath.isAskedOn(counted, friday))
        assertFalse(HabitMath.isAskedOn(counted, saturday))
    }

    @Test
    fun `the share kept is counted in the unit the frequency uses`() {
        // Three marks in the first week of a three-a-week, and none in the second.
        val counted = habit(frequency = HabitFrequency.TIMES_PER_WEEK, times = 3, start = monday)
        val marked = setOf(monday, tuesday, wednesday)

        // Two weeks asked, one kept — in weeks, because a counted habit has no day series to keep.
        assertEquals(HabitAdherence(1, 2), HabitMath.adherence(counted, marked, monday, sunday + 7, DayOfWeek.MONDAY))
        // The same row read as days by the day-shaped rule would say three of fourteen: same marks, same
        // window, and a number that means something else entirely.
        assertEquals(
            HabitAdherence(3, 14),
            HabitMath.adherenceByDay(counted, marked, monday, sunday + 7)
        )

        val daily = habit(start = monday)
        assertEquals(HabitAdherence(3, 7), HabitMath.adherence(daily, marked, monday, sunday, DayOfWeek.MONDAY))
        assertEquals(HabitAdherence(3, 7), HabitMath.adherence(daily, marked, monday, sunday, DayOfWeek.SUNDAY))
    }

    @Test
    fun `a window that asks for nothing has no percentage to report`() {
        val late = habit(start = wednesday)
        val none = HabitMath.adherence(late, emptySet(), monday, tuesday, DayOfWeek.MONDAY)

        assertEquals(0, none.asked)
        assertEquals(0, none.kept)
        // Dividing by nothing is not zero per cent, and a card that printed 0% would be reporting a habit the
        // user failed rather than one they had not started.
        assertNull(none.percent)

        val kept = HabitMath.adherence(late, setOf(wednesday), wednesday, wednesday, DayOfWeek.MONDAY)
        assertEquals(100, kept.percent)
    }

    @Test
    fun `a counted habit with no count has no week series to run`() {
        // A row a newer build left without its count (a schema read that fell back to daily for the
        // frequency keeps this impossible, so the maths has to answer it rather than assume it away).
        val broken = habit(frequency = HabitFrequency.TIMES_PER_WEEK, times = null)

        assertEquals(HabitStreak(0, 0, StreakUnit.WEEKS), HabitMath.weekStreak(broken, setOf(monday), wednesday, DayOfWeek.MONDAY))
        assertEquals(0, HabitMath.adherenceByWeek(broken, setOf(monday), monday, sunday, DayOfWeek.MONDAY).asked)
    }

    @Test
    fun `a streak counts days for the day-shaped rules and weeks for the counted one`() {
        val daily = habit(start = day(2026, 5, 25))
        val days = HabitMath.streak(daily, setOf(monday, tuesday, wednesday), wednesday, DayOfWeek.MONDAY)
        assertEquals(StreakUnit.DAYS, days.unit)
        assertEquals(3, days.current)

        val counted = habit(frequency = HabitFrequency.TIMES_PER_WEEK, times = 3, start = day(2026, 5, 25))
        val weeks = HabitMath.streak(counted, setOf(monday, tuesday, wednesday), wednesday, DayOfWeek.MONDAY)
        // The same three marks answer one week of a counted habit, and the current week is the one being
        // read — so a weekly habit never reports a run of days, whatever the user did.
        assertEquals(StreakUnit.WEEKS, weeks.unit)
        assertEquals(1, weeks.current)
        assertEquals(1, weeks.best)
    }

    @Test
    fun `this week asks in marks for a counted habit and in days for the others`() {
        val counted = habit(frequency = HabitFrequency.TIMES_PER_WEEK, times = 4, start = monday)
        // Two of the four the user asked for, spread over days the habit never names as its own.
        assertEquals(
            HabitAdherence(2, 4),
            HabitMath.weekProgress(counted, setOf(monday, friday), wednesday, DayOfWeek.MONDAY)
        )

        val weekday = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            mask = HabitMath.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            start = monday
        )
        // Three days asked this week and one kept; a counted reading of the same row would have had no count
        // to compare against.
        assertEquals(
            HabitAdherence(1, 3),
            HabitMath.weekProgress(weekday, setOf(monday), wednesday, DayOfWeek.MONDAY)
        )
    }

    @Test
    fun `a mark left over from before the habit covered is not in this week`() {
        val started = habit(frequency = HabitFrequency.TIMES_PER_WEEK, times = 3, start = wednesday)
        // The user backdated the start by two days: the marks on Monday and Tuesday predate the habit, and
        // a week figure that counted them would credit a run the row says never happened.
        val kept = HabitMath.weekProgress(started, setOf(monday, tuesday, wednesday), wednesday, DayOfWeek.MONDAY)
        assertEquals(HabitAdherence(1, 3), kept)
    }

    // ------------------------------------------------------------------------- the reminder's instant

    @Test
    fun `a reminder is armed at the wall clock the user named`() {
        val habit = habit(remindMinute = 8 * 60)
        assertEquals(instantOf(2026, 6, 3, 8, 0), HabitMath.reminderInstant(habit, wednesday, berlin))
        // Winter and summer read the same eight o'clock: the minute is local, so the instant it means moves
        // with the offset rather than the alarm moving an hour.
        assertEquals(
            instantOf(2026, 1, 3, 8, 0),
            HabitMath.reminderInstant(habit, day(2026, 1, 3), berlin)
        )
        assertNull(HabitMath.reminderInstant(habit(start = wednesday), wednesday, berlin))
    }

    @Test
    fun `a minute inside a spring-forward gap lands at the first instant after it`() {
        // Berlin skips 02:00-03:00 on 2026-03-29. A habit set for 02:30 that day cannot be armed at 02:30,
        // and arming it an hour *before* the gap would announce a habit that had not come due yet: the
        // answer is the instant the clock resumes, which is 03:00 local.
        val habit = habit(remindMinute = 2 * 60 + 30)
        val jumped = HabitMath.reminderInstant(habit, day(2026, 3, 29), berlin)

        assertEquals(Instant.parse("2026-03-29T01:00:00Z").toEpochMilli(), jumped)
        // The same minute on a day that does not jump is the minute itself.
        assertEquals(instantOf(2026, 3, 28, 2, 30), HabitMath.reminderInstant(habit, day(2026, 3, 28), berlin))
    }

    @Test
    fun `a reminder owed today after its minute is armed for the next asked day`() {
        val habit = habit(remindMinute = 8 * 60)
        val nineOnWednesday = instantOf(2026, 6, 3, 9, 0)

        assertEquals(instantOf(2026, 6, 4, 8, 0), HabitMath.nextReminderInstant(habit, nineOnWednesday, berlin))
        // Before the minute, today is still owed — the same day, not the next one.
        assertEquals(instantOf(2026, 6, 3, 8, 0), HabitMath.nextReminderInstant(habit, instantOf(2026, 6, 3, 7, 0), berlin))
    }

    @Test
    fun `a weekday habit is armed for the next day it names`() {
        val habit = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            mask = HabitMath.maskOf(listOf(DayOfWeek.FRIDAY)),
            remindMinute = 8 * 60
        )
        // Monday morning: the four days in between are not asked for, so the alarm waits for Friday.
        assertEquals(
            instantOf(2026, 6, 5, 8, 0),
            HabitMath.nextReminderInstant(habit, instantOf(2026, 6, 1, 9, 0), berlin)
        )
    }

    @Test
    fun `a habit that has stopped being asked for is never armed again`() {
        val endedToday = habit(start = day(2026, 5, 25), end = wednesday, remindMinute = 8 * 60)
        assertNull(HabitMath.nextReminderInstant(endedToday, instantOf(2026, 6, 3, 9, 0), berlin))

        // A put-away or thrown-away habit has a minute in its row and no asking behind it: the sweep that
        // reads candidates by column still has to be told the schedule stopped.
        val archived = habit(start = monday, remindMinute = 8 * 60, archivedAt = instantOf(2026, 6, 1, 9, 0))
        assertNull(HabitMath.nextReminderInstant(archived, instantOf(2026, 6, 3, 7, 0), berlin))
        val deleted = habit(start = monday, remindMinute = 8 * 60, isDeleted = true)
        assertNull(HabitMath.nextReminderInstant(deleted, instantOf(2026, 6, 3, 7, 0), berlin))
        assertNull(HabitMath.nextReminderInstant(habit(start = monday), instantOf(2026, 6, 3, 7, 0), berlin))
    }

    @Test
    fun `the reminder day rule is the schedule question and nothing else`() {
        val weekday = habit(
            frequency = HabitFrequency.DAYS_OF_WEEK,
            mask = HabitMath.maskOf(listOf(DayOfWeek.WEDNESDAY)),
            start = monday,
            end = friday,
            remindMinute = 8 * 60
        )
        assertTrue(HabitMath.isReminderDay(weekday, wednesday))
        assertFalse(HabitMath.isReminderDay(weekday, thursday))
        // The window closes the asking, and so the reminding, even on a day the mask names.
        assertFalse(HabitMath.isReminderDay(weekday.copy(endDay = tuesday), wednesday))
        assertFalse(HabitMath.isReminderDay(weekday.copy(archivedAt = 1L), wednesday))
    }
}
