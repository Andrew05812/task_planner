package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.habit.HabitAdherence
import com.taskplanner.data.habit.HabitMath
import com.taskplanner.data.habit.HabitReminderCoordinator
import com.taskplanner.data.habit.HabitStreak
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheck
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.tracking.WorkTimeMath
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Everything a habit card says about one habit, at the moment it was asked for.
 *
 * None of these figures is a column. [streak], [thisWeek] and [period] are all read off the mark rows
 * every time, which is the only way taking a mark back somewhere in the grid changes the series in the
 * same read that changes the grid — the same reasoning that keeps a goal from storing its progress. The
 * one deliberate exception is scope: [period] and [marks] are answers about the window the read was
 * given, not about the habit's whole life, so the card has to be able to say "of the last nine weeks"
 * beside the percentage rather than imply it has counted everything there was.
 */
data class HabitFigures(
    val habit: Habit,
    /** Whether the day the read is anchored on carries a mark — the button's own state. */
    val markedToday: Boolean,
    val today: Long,
    val streak: HabitStreak,
    /** This week's "3 of 4": marks it holds and what its schedule asks of it. */
    val thisWeek: HabitAdherence,
    /** The same share over the window the read covered. */
    val period: HabitAdherence,
    /**
     * How many days [period] was drawn from.
     *
     * The window the caller asked for and the window the habit can answer about are not always the same
     * length: a habit begun yesterday has no yesterday-before-its-start to be asked about, and its share is
     * read over the two days it lived through. The caption beside a percentage has to name the second
     * number, so the read states the span it used rather than leaving the screen to assume the one it requested.
     */
    val periodDays: Int,
    /** Live marks inside the window the read covered, which is what the two shares were computed from. */
    val marks: Int
) {
    /** A habit that has never been kept has nothing to series, and the card says so rather than "0 days". */
    val hasAnyMark: Boolean get() = marks > 0 || streak.isAlive
}

/** A habit and the figures its marks produced. */
data class HabitWithFigures(val habit: Habit, val figures: HabitFigures) {
    val markedToday: Boolean get() = figures.markedToday
}

/** Why a habit the user asked for was not written, or not written to the day they named. */
enum class HabitRejection {
    /** No profile is signed in, so there is nothing for the habit to belong to. */
    NO_PROFILE,

    /** The habit named is not in this profile's account — including one it does not own, or a tombstone. */
    NOT_FOUND,

    /** A habit the user put away is no longer being asked for, so a fresh mark on it is refused. */
    ARCHIVED,

    /** A habit without a title is a row the screen cannot name; nothing is invented for the user. */
    BLANK_TITLE,

    /** A weekday habit with no weekday in it asks for nothing, and an empty series is not a schedule. */
    EMPTY_WEEKDAY_SET,

    /** A weekly count has to be one to seven, the days a week has; null and out-of-range are the same mistake. */
    WEEKLY_COUNT_OUT_OF_RANGE,

    /** A window whose last day falls before its first asks about no days at all. */
    WINDOW_ENDS_BEFORE_START,

    /** A minute of the day is 0..1439; anything else is not a clock time the habit can be reminded at. */
    BAD_REMINDER_MINUTE,

    /** A mark for a day outside the habit's own window: before it began, or after it stopped. */
    DAY_OUTSIDE_WINDOW,

    /** A mark for a day that has not arrived yet asserts something the user cannot have done. */
    DAY_IN_FUTURE,

    /** The day already stands, by the database's own rule rather than by a check that can race it. */
    ALREADY_MARKED,

    /** There is no live mark on that day to take back or move. */
    NO_SUCH_MARK,

    /** The day moved onto already holds a mark of its own, so the swap would quietly destroy one. */
    TARGET_DAY_TAKEN
}

/**
 * What a habit write ended up doing.
 *
 * Sealed rather than a null for the same reason a goal write is: the editor has genuinely different
 * answers to give — it saved, the schedule is impossible, that day is already kept — and each one needs
 * its own sentence on screen instead of a generic failure toast.
 */
sealed class HabitWriteResult {
    data class Saved(val habitUuid: String) : HabitWriteResult()
    data class Rejected(val reason: HabitRejection) : HabitWriteResult()
}

/**
 * The only writer of habits and marks, and the only reader of what they add up to.
 *
 * Three rules shape the class:
 *
 *  * **A mark is the only fact stored, and it is stored once per day.** The series, the "3 of 4", the
 *    percentage kept are all computed from the mark rows against the habit's own schedule by
 *    [HabitMath], so §6's "серии" and "процент соблюдения" cannot disagree with the grid beside them. The
 *    unique index on `(habit_uuid, day_marker)` is what makes one live mark per day a database fact, and
 *    [mark] turns its refusal into [HabitRejection.ALREADY_MARKED] rather than looking first — a
 *    check-then-insert pair is not a rule, only the index is.
 *  * **Corrections are history, never silence.** [takeBack] and [moveMark] retire the row they stand on
 *    and append a [HabitCheckChange] in the same transaction, so "the user missed Tuesday" and "the user
 *    marked Tuesday and then said that was a mistake" stay two different database states. That is §6's
 *    "исправление отметки с корректной историей", and it is why a mark is never edited in place.
 *  * **The schedule belongs to one frequency at a time.** [create] and [edit] write the weekday mask only
 *    for [HabitFrequency.DAYS_OF_WEEK] and the weekly count only for [HabitFrequency.TIMES_PER_WEEK],
 *    leaving the other column empty rather than storing a second schedule the figures would ignore.
 *
 * Every write takes the profile first and every read of a row by uuid checks that profile, so §16's
 * isolation holds here the way it does for goals: after an import, two accounts can hold habits and marks
 * and only their own rows move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HabitRepository @Inject constructor(
    private val database: AppDatabase,
    private val habitDao: HabitDao,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    private val ticker: WorkTicker,
    private val reminders: HabitReminderCoordinator
) {

    /**
     * Starts a habit.
     *
     * [startDay] and [endDay] are inclusive epoch days; [endDay] null means the habit is open-ended. The
     * schedule columns are normalised by frequency rather than taken as they arrive — see the class
     * comment — so a daily habit stores no weekday mask and a weekday habit stores no weekly count, and
     * there is exactly one reading of the row.
     */
    suspend fun create(
        title: String,
        frequency: HabitFrequency,
        startDay: Long,
        daysOfWeek: List<DayOfWeek> = emptyList(),
        timesPerWeek: Int? = null,
        endDay: Long? = null,
        remindMinuteOfDay: Int? = null
    ): HabitWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_PROFILE)
        val mask = scheduleMask(frequency, daysOfWeek)
        val count = scheduleCount(frequency, timesPerWeek)
        val rejection = validate(title, frequency, mask, count, startDay, endDay, remindMinuteOfDay)
        if (rejection != null) return HabitWriteResult.Rejected(rejection)

        val now = timeBase.wallMillis()
        val uuid = UUID.randomUUID().toString()
        habitDao.insertHabit(
            Habit(
                uuid = uuid,
                userId = userId,
                title = title.trim(),
                frequency = frequency,
                daysMask = mask,
                timesPerWeek = count,
                startDay = startDay,
                endDay = endDay,
                remindMinuteOfDay = remindMinuteOfDay,
                syncState = SyncState.PENDING,
                createdAt = now,
                updatedAt = now
            )
        )
        // A habit born with a minute is armed for its next owed day the moment it exists, so the editor
        // does not have to remember to ask — and one born without a minute is answered the same way, by
        // nothing being armed at all.
        reminders.onScheduleChanged(uuid)
        return HabitWriteResult.Saved(uuid)
    }

    /**
     * Applies the editor's request to a stored habit.
     *
     * The figures are not recomputed or adjusted here: a frequency change rewrites the question the marks
     * answer, and because none of them is stored the new answer is simply what the next read produces. A
     * series that the old schedule supported and the new one does not is reported by the next read rather
     * than repaired by this write — the user's marks stay exactly where they put them.
     */
    suspend fun edit(
        habitUuid: String,
        title: String,
        frequency: HabitFrequency,
        startDay: Long,
        daysOfWeek: List<DayOfWeek> = emptyList(),
        timesPerWeek: Int? = null,
        endDay: Long? = null,
        remindMinuteOfDay: Int? = null
    ): HabitWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_PROFILE)
        val existing = ownHabit(habitUuid, userId)
            ?: return HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)
        val mask = scheduleMask(frequency, daysOfWeek)
        val count = scheduleCount(frequency, timesPerWeek)
        val rejection = validate(title, frequency, mask, count, startDay, endDay, remindMinuteOfDay)
        if (rejection != null) return HabitWriteResult.Rejected(rejection)

        val now = timeBase.wallMillis()
        habitDao.updateHabit(
            existing.copy(
                title = title.trim(),
                frequency = frequency,
                daysMask = mask,
                timesPerWeek = count,
                startDay = startDay,
                endDay = endDay,
                remindMinuteOfDay = remindMinuteOfDay,
                syncState = SyncState.PENDING,
                updatedAt = now
            )
        )
        // The schedule the alarm is derived from is the one the user just wrote: a minute moved to the
        // evening, a weekday dropped from the mask, a window that ended — each changes the next owed
        // instant, and re-arming from the stored row is the only way the alarm cannot disagree with the
        // grid beside it.
        reminders.onScheduleChanged(habitUuid)
        return HabitWriteResult.Saved(habitUuid)
    }

    /**
     * Asserts that the user did the habit on [day].
     *
     * The day is the whole fact, so the two ways of writing it wrong are refused outright: a day the habit
     * never covered — before it began or after it stopped — and a day that has not arrived yet. A day the
     * schedule does not name (a Sunday mark on a Monday-to-Friday habit) is *not* refused: it is real work,
     * and [HabitMath] keeps it out of the percentage rather than pretending it was asked for.
     *
     * A second mark for a day already standing is refused by the unique index, in the race this app's
     * double-tap actually creates, and the refusal is reported as [HabitRejection.ALREADY_MARKED].
     */
    suspend fun mark(habitUuid: String, day: Long): HabitWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_PROFILE)
        val habit = habitDao.getHabit(habitUuid, userId)
            ?: return HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)
        if (habit.isDeleted) return HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)
        if (!habit.isOpen) return HabitWriteResult.Rejected(HabitRejection.ARCHIVED)
        dayRejection(habit, day)?.let { return HabitWriteResult.Rejected(it) }

        val now = timeBase.wallMillis()
        val zone = CalendarMath.systemZone()
        val check = HabitCheck(
            uuid = UUID.randomUUID().toString(),
            habitUuid = habit.uuid,
            userId = userId,
            localDay = day,
            // The offset the *day* was resolved at, not the offset of the tap: a mark made this morning
            // for yesterday's run belongs to yesterday in the zone yesterday was, and a flight between
            // the two days must not slide the mark across a date boundary.
            utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(
                CalendarMath.startOfDay(LocalDate.ofEpochDay(day), zone), zone
            ),
            dayMarker = day,
            syncState = SyncState.PENDING,
            createdAt = now,
            updatedAt = now
        )
        return try {
            habitDao.insertCheck(check)
            // Keeping the day the habit is being asked about today answers the notice that is on screen,
            // so it comes down. A mark for any other day leaves it standing: today is still owed, and a
            // mark made for last week does not say so.
            if (day == todayEpochDay()) reminders.onAskAnswered(habit.uuid)
            HabitWriteResult.Saved(habit.uuid)
        } catch (e: RuntimeException) {
            if (habitDao.liveMark(habit.uuid, userId, day) != null) {
                HabitWriteResult.Rejected(HabitRejection.ALREADY_MARKED)
            } else {
                throw e
            }
        }
    }

    /**
     * Takes back the mark on [day].
     *
     * The row is retired rather than deleted, so the correction history can still say which day the user
     * stood behind and then walked back. [HabitRejection.NO_SUCH_MARK] is reported when nothing live was
     * there to take — the double tap of this button is as possible as the double tap of the mark, and the
     * second one has taken nothing.
     *
     * A correction to a habit that has been put away is allowed, unlike a fresh mark: the user reviewing an
     * archived habit's grid is fixing something they asserted while it was live, and refusing that would
     * leave a wrong mark standing precisely where it can no longer be edited.
     */
    suspend fun takeBack(habitUuid: String, day: Long, reason: String? = null): HabitWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_PROFILE)
        val habit = habitDao.getHabit(habitUuid, userId)
            ?: return HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)
        val mark = habitDao.liveMark(habit.uuid, userId, day)
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_SUCH_MARK)

        val now = timeBase.wallMillis()
        try {
            database.withTransaction {
                // The update reports how many rows it retired, and a zero means the live mark this
                // transaction read is gone. The correction row is then not written either: an account of a
                // mark nobody was standing behind would be a history the figures cannot confirm.
                if (habitDao.retireMark(habit.uuid, userId, day, now) == 0) throw MarkGoneException()
                habitDao.insertCheckChange(
                    change(mark.uuid, habit.uuid, userId, day, null, reason, now)
                )
            }
        } catch (e: MarkGoneException) {
            return HabitWriteResult.Rejected(HabitRejection.NO_SUCH_MARK)
        }
        return HabitWriteResult.Saved(habit.uuid)
    }

    /**
     * Moves the mark on [fromDay] to [toDay] — the correction the user reaches for when the day was wrong
     * rather than the work.
     *
     * It is two rows, never one edited in place: the old mark is retired and a new one stands on the target
     * day, with a [HabitCheckChange] naming both. That keeps the unique index doing its job (the day the
     * mark left is free again, and the day it arrived at is held by exactly one row) and keeps the story
     * readable, because the retired row still carries the day it was made for.
     *
     * Everything happens in one transaction, and the two ways it can fail are checked at the database: a
     * target day already marked refuses the swap rather than swallowing one of the two marks, and a target
     * day outside the window or in the future is refused before anything is written — a half-moved mark
     * would leave the user with neither day.
     */
    suspend fun moveMark(
        habitUuid: String,
        fromDay: Long,
        toDay: Long,
        reason: String? = null
    ): HabitWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_PROFILE)
        val habit = habitDao.getHabit(habitUuid, userId)
            ?: return HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)
        val mark = habitDao.liveMark(habit.uuid, userId, fromDay)
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_SUCH_MARK)
        if (fromDay == toDay) return HabitWriteResult.Saved(habit.uuid)
        dayRejection(habit, toDay)?.let { return HabitWriteResult.Rejected(it) }

        val now = timeBase.wallMillis()
        val zone = CalendarMath.systemZone()
        return try {
            database.withTransaction {
                if (habitDao.retireMark(habit.uuid, userId, fromDay, now) == 0) throw MarkGoneException()
                habitDao.insertCheck(
                    HabitCheck(
                        uuid = UUID.randomUUID().toString(),
                        habitUuid = habit.uuid,
                        userId = userId,
                        localDay = toDay,
                        utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(
                            CalendarMath.startOfDay(LocalDate.ofEpochDay(toDay), zone), zone
                        ),
                        dayMarker = toDay,
                        syncState = SyncState.PENDING,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                habitDao.insertCheckChange(change(mark.uuid, habit.uuid, userId, fromDay, toDay, reason, now))
            }
            if (toDay == todayEpochDay()) reminders.onAskAnswered(habit.uuid)
            HabitWriteResult.Saved(habit.uuid)
        } catch (e: MarkGoneException) {
            // Nothing live was on the day the move started from, so nothing moved and nothing is charged
            // for the attempt.
            HabitWriteResult.Rejected(HabitRejection.NO_SUCH_MARK)
        } catch (e: RuntimeException) {
            // The transaction rolled back, so this reads the state as it stands: the source mark is still
            // live, and only the target day being held can have refused the insert.
            if (habitDao.liveMark(habit.uuid, userId, toDay) != null) {
                HabitWriteResult.Rejected(HabitRejection.TARGET_DAY_TAKEN)
            } else {
                throw e
            }
        }
    }

    /**
     * Puts the habit away: the asking stops, the marks stay.
     *
     * The live marks are deliberately not retired here, unlike [remove] — an archived habit's series and
     * percentage are still the record of what the user did, and a figure that dropped to zero on archive
     * would read as though the work had.
     */
    suspend fun archive(habitUuid: String): HabitWriteResult =
        transition(habitUuid) { uuid, user, now -> habitDao.archive(uuid, user, now) }

    /** Starts asking again, from the day the user says. */
    suspend fun unarchive(habitUuid: String): HabitWriteResult =
        transition(habitUuid) { uuid, user, now -> habitDao.unarchive(uuid, user, now) }

    /**
     * Throws the habit away, softly, and retires its live marks with it.
     *
     * Both halves in one transaction, because [HabitCheck.isLive] is what every figure counts: marks left
     * standing behind a deleted habit would keep a habit the user threw away in the profile's marked days.
     * The rows and their correction history stay, which is the append-only rule this schema keeps
     * everywhere else — and [HabitCheck.habitUuid] has no foreign key for exactly that reason, so a
     * restore that brings the habit back finds the marks that belonged to it.
     */
    suspend fun remove(habitUuid: String): HabitWriteResult =
        transition(habitUuid) { uuid, user, now ->
            habitDao.softDelete(uuid, user, now)
            habitDao.retireMarksOf(uuid, user, now)
        }

    /** The habit [habitUuid] names for this profile, or null when the profile does not hold it. */
    suspend fun habit(habitUuid: String): Habit? =
        sessionRepository.currentUserId()?.let { ownHabit(habitUuid, it) }

    /**
     * The stored row a screen was opened on, with no figures beside it.
     *
     * The editor prefills from this and nothing else: it asks what the user chose last time, which is a
     * question about the row. Reading the streak or the percentage here would put numbers the editor cannot
     * edit in the form beside the ones it can.
     */
    suspend fun habitById(habitId: Long): Habit? =
        sessionRepository.currentUserId()?.let { habitDao.getHabitById(it, habitId) }

    /** Whether [day] stands as kept, for a screen that only needs the button's state. */
    suspend fun isMarked(habitUuid: String, day: Long): Boolean {
        val userId = sessionRepository.currentUserId() ?: return false
        return habitDao.liveMark(habitUuid, userId, day) != null
    }

    /** The kept days in `[fromDay, toDay]`, ascending — the detail screen's grid over one window. */
    suspend fun markedDays(habitUuid: String, fromDay: Long, toDay: Long): List<Long> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return habitDao.liveDays(habitUuid, userId, fromDay, toDay)
    }

    /** The account of a habit: which days were asserted and which were walked back, oldest first. */
    suspend fun checkChangesOf(habitUuid: String): List<HabitCheckChange> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return habitDao.checkChangesOf(habitUuid, userId)
    }

    /** The profile's most recent corrections, newest first — the history screen's feed. */
    suspend fun recentCheckChanges(limit: Int): List<HabitCheckChange> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return habitDao.recentCheckChanges(userId, limit)
    }

    /**
     * Today as the habits count it: the local day the stored instants and marks are filed under.
     *
     * Taken from the injected time base rather than from the clock a caller happens to read, so a test that
     * parks the wall clock at 23:59 on a Sunday gets Sunday — and the same test can move it one minute
     * forward and watch every card re-anchor on Monday.
     */
    fun todayEpochDay(zone: ZoneId = CalendarMath.systemZone()): Long =
        WorkTimeMath.epochDayOf(timeBase.wallMillis(), zone)

    /**
     * What one habit has kept, read now, over the window the caller named.
     *
     * [periodFromDay] and [periodToDay] are the card's own question — "the last nine weeks", "this month" —
     * and the marks are read only across it, clamped at the habit's start day, which is what stops a
     * five-year habit from loading five years of rows to print this month's percentage. [HabitFigures.marks]
     * and [HabitFigures.period] are therefore answers about that window, while [HabitFigures.streak] is
     * anchored on today: the walk back stops at the first break, so a window longer than the series states
     * the series exactly and a shorter one says "at least".
     */
    suspend fun figuresOf(
        habit: Habit,
        periodFromDay: Long,
        periodToDay: Long = todayEpochDay(),
        zone: ZoneId = CalendarMath.systemZone()
    ): HabitFigures {
        val userId = sessionRepository.currentUserId()
        val marked = if (userId == null) emptySet() else readDays(habit, userId, periodFromDay, periodToDay).toSet()
        return figuresOf(habit, marked, todayEpochDay(zone), periodFromDay, periodToDay)
    }

    /**
     * The habits the screen lists, each with the figures its marks produced.
     *
     * The list and the figures come apart on purpose, exactly as a goal's do: [HabitDao.observeOpenHabits]
     * is the row list and each habit's marks are their own bounded aggregate Flow, so making a mark re-runs
     * that habit's figures and nothing else, and a habit whose marks fall outside the window is not woken
     * by them at all. Room's invalidation tracker is the whole notification mechanism — there is no counter
     * here to keep in step with the rows.
     *
     * The read is re-anchored on [WorkTicker.localDays] because the date figures are about without a write
     * — which week is "this week", whether today's unmarked day has become a break — changes at midnight,
     * and midnight is the one moment that can be predicted in advance.
     */
    fun observeOpenHabits(
        periodFromDay: Long,
        periodToDay: Long,
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<List<HabitWithFigures>> = observeHabits(open = true, periodFromDay, periodToDay, zone)

    /** The habits put away, with the figures they stopped being asked for at. */
    fun observeArchivedHabits(
        periodFromDay: Long,
        periodToDay: Long,
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<List<HabitWithFigures>> = observeHabits(open = false, periodFromDay, periodToDay, zone)

    /** One habit's figures as a Flow that re-runs when its marks move. */
    fun observeFigures(
        habit: Habit,
        periodFromDay: Long,
        periodToDay: Long,
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<HabitFigures> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        if (userId == null) {
            flowOf(figuresOf(habit, emptySet(), todayEpochDay(zone), periodFromDay, periodToDay))
        } else {
            ticker.localDays(zone).flatMapLatest { day ->
                figuresFlow(habit, userId, periodFromDay, periodToDay, day.toEpochDay())
            }
        }
    }

    /** The whole live history of one habit as a Flow — the detail screen's grid, which has no window. */
    fun observeAllMarkedDays(habitUuid: String): Flow<List<Long>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            val userId = user?.id
            if (userId == null) flowOf(emptyList()) else habitDao.observeAllLiveDays(habitUuid, userId)
        }

    /** Which days were asserted and which were walked back, oldest first, as a Flow. */
    fun observeCheckChanges(habitUuid: String): Flow<List<HabitCheckChange>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            val userId = user?.id
            if (userId == null) flowOf(emptyList()) else habitDao.observeCheckChanges(habitUuid, userId)
        }

    /**
     * The habit a screen was opened on — by the row id this device gave it — with the figures under it.
     *
     * This is the read a habit notification's link resolves to, so it has to hand over the row and its
     * figures together: a screen that printed a series from one query and a percentage from another could
     * show a habit that had just been edited as two different habits. Null is the answer for a habit this
     * profile does not hold, which includes one it never owned.
     */
    fun observeHabitWithFigures(
        habitId: Long,
        periodFromDay: Long,
        periodToDay: Long = todayEpochDay(),
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<HabitWithFigures?> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        if (userId == null) {
            flowOf(null)
        } else {
            habitDao.observeHabitById(userId, habitId).flatMapLatest { rows ->
                val habit = rows.firstOrNull()
                if (habit == null) {
                    flowOf(null)
                } else {
                    ticker.localDays(zone).flatMapLatest { day ->
                        figuresFlow(habit, userId, periodFromDay, periodToDay, day.toEpochDay())
                            .map { figures -> HabitWithFigures(habit, figures) }
                    }
                }
            }
        }
    }

    private fun observeHabits(
        open: Boolean,
        periodFromDay: Long,
        periodToDay: Long,
        zone: ZoneId
    ): Flow<List<HabitWithFigures>> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        if (userId == null) flowOf(emptyList()) else {
            val rows = if (open) habitDao.observeOpenHabits(userId) else habitDao.observeArchivedHabits(userId)
            rows.flatMapLatest { habits ->
                if (habits.isEmpty()) {
                    // `combine` over no flows never emits, and a screen with zero habits still has to be
                    // told "zero" rather than left waiting on a list that will never arrive.
                    flowOf(emptyList())
                } else {
                    ticker.localDays(zone).flatMapLatest { day ->
                        val perHabit = habits.map { habit ->
                            figuresFlow(habit, userId, periodFromDay, periodToDay, day.toEpochDay())
                                .map { figures -> HabitWithFigures(habit, figures) }
                        }.toTypedArray()
                        // `combine` waits for every source before its first emission, which is what a list
                        // of cards wants — no half-drawn screen — and its array comes back in the order the
                        // flows were given, so figures cannot arrive shuffled.
                        combine(*perHabit) { cards -> cards.toList() }
                    }
                }
            }
        }
    }

    private fun figuresFlow(
        habit: Habit,
        userId: Long,
        periodFromDay: Long,
        periodToDay: Long,
        today: Long
    ): Flow<HabitFigures> {
        val (from, to) = figureWindow(habit, periodFromDay, periodToDay)
        return habitDao.observeLiveDays(habit.uuid, userId, from, to)
            .map { days -> figuresOf(habit, days.toSet(), today, periodFromDay, periodToDay) }
    }

    /**
     * The figures from the days the read already has.
     *
     * Pure, and shared by the suspend and the reactive path: one place where the four questions a card
     * asks are answered, so the number a test reads out of [figuresOf] is the same number the screen will
     * be handed for the same marks.
     *
     * Public for the one caller that holds the days before it holds a habit: a report reads every mark the
     * profile made inside its window in one statement and works out each habit's figures from that set,
     * because one query per habit would be one query per card over rows the first query already returned.
     * Such a caller owes [marked] the same four filters [readDays] applies — live, not tombstoned, this
     * profile's, and nothing before [Habit.startDay] — or the figures describe work the cards refuse to
     * count. The start-day filter is the one a batch read cannot express in its `WHERE` clause, because the
     * day each mark may be credited from belongs to the habit rather than to the mark.
     */
    fun figuresOf(
        habit: Habit,
        marked: Set<Long>,
        today: Long,
        periodFromDay: Long,
        periodToDay: Long
    ): HabitFigures {
        val firstDay = CalendarMath.firstDayOfWeek()
        val (from, to) = figureWindow(habit, periodFromDay, periodToDay)
        return HabitFigures(
            habit = habit,
            markedToday = today in marked,
            today = today,
            streak = HabitMath.streak(habit, marked, today, firstDay),
            thisWeek = HabitMath.weekProgress(habit, marked, today, firstDay),
            period = HabitMath.adherence(habit, marked, from, to, firstDay),
            periodDays = (to - from + 1).coerceAtLeast(0L).toInt(),
            marks = marked.size
        )
    }

    private suspend fun readDays(
        habit: Habit,
        userId: Long,
        periodFromDay: Long,
        periodToDay: Long
    ): List<Long> {
        val (from, to) = figureWindow(habit, periodFromDay, periodToDay)
        return habitDao.liveDays(habit.uuid, userId, from, to)
    }

    /**
     * The window a read covers: the caller's period, cut off at the habit's own start.
     *
     * Nothing before [Habit.startDay] can answer a question about this habit — a backdated start would
     * otherwise have to load marks that belong to the habit the user is looking at from before it existed.
     * The end stays the caller's, because a habit that ended last month is still worth showing a window
     * over, and [HabitMath] asks about its days one at a time.
     */
    private fun figureWindow(habit: Habit, periodFromDay: Long, periodToDay: Long): Pair<Long, Long> =
        maxOf(periodFromDay, habit.startDay) to periodToDay

    /**
     * The rules every write passes through, in the order the screen can show them.
     *
     * Checked here rather than left to SQLite because each one has a sentence attached: the editor has to
     * be able to say which field is impossible, and the database could only refuse the row.
     */
    private fun validate(
        title: String,
        frequency: HabitFrequency,
        mask: Int,
        timesPerWeek: Int?,
        startDay: Long,
        endDay: Long?,
        remindMinuteOfDay: Int?
    ): HabitRejection? = when {
        title.isBlank() -> HabitRejection.BLANK_TITLE
        frequency == HabitFrequency.DAYS_OF_WEEK && mask == 0 -> HabitRejection.EMPTY_WEEKDAY_SET
        frequency == HabitFrequency.TIMES_PER_WEEK &&
            (timesPerWeek == null || timesPerWeek !in 1..DAYS_IN_WEEK) -> HabitRejection.WEEKLY_COUNT_OUT_OF_RANGE
        endDay != null && endDay < startDay -> HabitRejection.WINDOW_ENDS_BEFORE_START
        // A minute of the day counts from zero to one less than the day's length: 1440 is tomorrow's
        // midnight, which the schedule stores as minute zero and the picker must not accept as a minute.
        remindMinuteOfDay != null && remindMinuteOfDay !in 0 until MINUTES_IN_DAY ->
            HabitRejection.BAD_REMINDER_MINUTE
        else -> null
    }

    /** The two rules a day has to pass before a mark can stand on it, in the order the screen names them. */
    private fun dayRejection(habit: Habit, day: Long): HabitRejection? = when {
        !habit.covers(day) -> HabitRejection.DAY_OUTSIDE_WINDOW
        day > todayEpochDay() -> HabitRejection.DAY_IN_FUTURE
        else -> null
    }

    /** The weekday mask the row may carry: only a weekday habit has one. */
    private fun scheduleMask(frequency: HabitFrequency, daysOfWeek: List<DayOfWeek>): Int =
        if (frequency == HabitFrequency.DAYS_OF_WEEK) HabitMath.maskOf(daysOfWeek) else 0

    /** The weekly count the row may carry: only a counted habit has one. */
    private fun scheduleCount(frequency: HabitFrequency, timesPerWeek: Int?): Int? =
        if (frequency == HabitFrequency.TIMES_PER_WEEK) timesPerWeek else null

    private fun change(
        checkUuid: String,
        habitUuid: String,
        userId: Long,
        oldDay: Long,
        newDay: Long?,
        reason: String?,
        now: Long
    ): HabitCheckChange = HabitCheckChange(
        uuid = UUID.randomUUID().toString(),
        // The mark that stopped standing, not the one that arrived: the row this change explains is the
        // day the screen had been showing as kept, and after a move it is the retired half that needs the
        // sentence attached to it.
        checkUuid = checkUuid,
        habitUuid = habitUuid,
        userId = userId,
        oldLocalDay = oldDay,
        newLocalDay = newDay,
        reason = reason,
        syncState = SyncState.PENDING,
        createdAt = now,
        updatedAt = now
    )

    /**
     * The habit [uuid] names, when this profile may change it, and null when it may not.
     *
     * A uuid is not a permission. The unique index on `habits.uuid` means exactly one profile in the
     * database can hold a given habit, so the danger is not two accounts owning the same row — it is a
     * screen that still holds a uuid from the account the user has left. Every read here is scoped to the
     * signed-in profile, so that uuid names nothing, and the write is refused rather than reaching into
     * another account. A tombstone is refused for the same reason as a goal's: the user is not looking at
     * a row they can still change.
     */
    private suspend fun ownHabit(uuid: String, userId: Long): Habit? =
        habitDao.getHabit(uuid, userId)?.takeIf { !it.isDeleted }

    private suspend fun transition(
        habitUuid: String,
        block: suspend (uuid: String, userId: Long, now: Long) -> Unit
    ): HabitWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return HabitWriteResult.Rejected(HabitRejection.NO_PROFILE)
        ownHabit(habitUuid, userId)
            ?: return HabitWriteResult.Rejected(HabitRejection.NOT_FOUND)
        database.withTransaction { block(habitUuid, userId, timeBase.wallMillis()) }
        // The asking follows the row: a habit put away or thrown away stops making noise, and one brought
        // back starts again at the next minute its schedule is owed. Outside the transaction, because an
        // alarm and a notification are not database state — a write that rolls back must not have taken a
        // wakeup away with it.
        reminders.onScheduleChanged(habitUuid)
        return HabitWriteResult.Saved(habitUuid)
    }

    companion object {
        private const val DAYS_IN_WEEK = 7
        private const val MINUTES_IN_DAY = 24 * 60
    }

    /**
     * The live mark this transaction read is gone before it could be retired.
     *
     * A private signal rather than an error code, because no caller can do anything with it beyond what
     * [HabitRejection.NO_SUCH_MARK] already says, and because throwing it *inside* [AppDatabase.withTransaction]
     * is what rolls the whole correction back: a history row for a mark that no longer stands would be an
     * account the figures cannot confirm.
     */
    private class MarkGoneException : RuntimeException()
}
