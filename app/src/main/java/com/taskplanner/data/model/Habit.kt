package com.taskplanner.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters
import java.time.DayOfWeek
import java.util.Locale

/**
 * How often a habit is supposed to happen, which is the only thing that makes a gap in its marks
 * mean something.
 *
 * A streak of a daily habit and a streak of a three-times-a-week habit are not the same kind of fact: the
 * first breaks on any missed day, the second does not even ask about the days between. So the frequency is
 * stored rather than inferred from the marks a user happened to make, and every figure derived from those
 * marks — the streak, the adherence percentage, whether today is owed a reminder — is computed against it.
 */
enum class HabitFrequency {
    /** Every day from the start day onward. */
    DAILY,

    /** The weekdays named by [Habit.daysMask], repeating each week. */
    DAYS_OF_WEEK,

    /** [Habit.timesPerWeek] marks in any week, on whichever days the user chooses. */
    TIMES_PER_WEEK;

    companion object {
        /** A row written by a newer build is read as daily rather than thrown away. */
        fun fromName(value: String?): HabitFrequency =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: DAILY
    }
}

/**
 * A habit: something the user intends to do on a schedule, with a mark for each time they did it.
 *
 * The row stores the intention — the frequency, the window of days it applies to, and optionally the
 * minute of the day to be reminded at. It stores no counts. The streak, the percentage kept, the figure of
 * "3 of 4 this week" are all derived from [HabitCheck] rows against this schedule, so a mark corrected
 * somewhere else changes every figure with it instead of leaving a cached number behind. That is the same
 * reason a goal stores no progress: a derived figure cannot go stale, because it is not there to go stale.
 *
 * The reminder is a minute and a rule, not a row in the `reminders` table. Task reminders are anchored to a
 * deadline the task already carries and are identified by `(task_uuid, offset_minutes)`; a habit's schedule
 * repeats from its own start day, so its next minute is computed here and delivered through the same
 * scheduler, receiver and sweep the tasks use. Keeping the row out of `reminders` is what leaves the
 * task-keyed reads — which join on `tasks` and retire themselves when a task is archived — untouched.
 */
@Entity(
    tableName = "habits",
    indices = [
        Index(value = ["user_id", "is_deleted"]),
        // The sweep asks one question of the whole profile: which habits are owed a reminder today.
        Index(value = ["user_id", "remind_minute_of_day"]),
        Index(value = ["uuid"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
@TypeConverters(Converters::class)
data class Habit(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "frequency", defaultValue = "'DAILY'")
    val frequency: HabitFrequency = HabitFrequency.DAILY,

    /**
     * The weekdays this habit is asked for, one bit per day with Monday in the lowest bit.
     *
     * A mask rather than a list of rows because it is read on every figure and written rarely, and seven
     * bits cannot hold an impossible value the way a repeated row can. The bit of a weekday is
     * `1 shl ordinal`, and [java.time.DayOfWeek] numbers Monday nought, so the lowest bit is Monday.
     * That order is chosen on purpose: a Sunday-first stored mask would make one habit mean different days
     * to a Russian user and an American one, whose grids open on different weekdays, while these seven
     * bits name the same seven days to everyone and only the drawing order moves.
     *
     * Only [HabitFrequency.DAYS_OF_WEEK] reads it; the other frequencies leave it at zero.
     */
    @ColumnInfo(name = "days_mask", defaultValue = "0")
    val daysMask: Int = 0,

    /**
     * How many marks a week must hold for [HabitFrequency.TIMES_PER_WEEK] to be kept.
     *
     * Ignored by the other frequencies. Seven is the most a week has days in, and the repository refuses
     * anything outside one to seven rather than inventing a meaning for a zero or a fifteen.
     */
    @ColumnInfo(name = "times_per_week")
    val timesPerWeek: Int? = null,

    /** Epoch day the habit begins. Nothing before it counts, so a backdated start cannot inflate a streak. */
    @ColumnInfo(name = "start_day")
    val startDay: Long,

    /** Epoch day the habit stops being asked for; null means it is open-ended. */
    @ColumnInfo(name = "end_day")
    val endDay: Long? = null,

    /**
     * Minutes after local midnight to be reminded at, or null for no reminder.
     *
     * Local minutes, not an instant, because the habit is asked for at seven in the morning wherever the
     * user happens to be — the same reason a daily alarm is a time rather than a date. The instant a
     * reminder is armed for is computed for the day in question, at the zone in force then.
     */
    @ColumnInfo(name = "remind_minute_of_day")
    val remindMinuteOfDay: Int? = null,

    /**
     * The epoch day whose reminder has already been posted, or null while none has.
     *
     * The one stored figure on this row, and it is stored because it cannot be derived: the alarms fire
     * more than once a day (an alarm, a reboot, a six-hourly sweep all reach the same rule), and without
     * this the user would be told the same habit is owed twice. It is a delivery note, not a fact about
     * the habit, which is why clearing it is what a "remind me later" does.
     */
    @ColumnInfo(name = "reminded_on_day")
    val remindedOnDay: Long? = null,

    /**
     * Set when the user puts the habit away, null while it is asked for.
     *
     * Distinguished from deletion the way a goal's archive is: the marks the user made stay in the
     * history and the figures they feed, and only the asking stops.
     */
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** The weekdays this habit is asked for on, read out of [daysMask] in Monday-first order. */
    fun scheduledWeekdays(): Set<DayOfWeek> =
        DayOfWeek.entries.filter { daysMask and (1 shl it.ordinal) != 0 }.toSet()

    /** Live on the screen: not deleted, not put away. */
    val isOpen: Boolean get() = !isDeleted && archivedAt == null

    /** Whether [day] falls inside the window this habit was asked for. */
    fun covers(day: Long): Boolean = day >= startDay && (endDay == null || day <= endDay)
}

/**
 * One day the user did the habit — the fact every habit figure is computed from.
 *
 * A mark is a day, not an instant: "I ran today" has no duration, and storing one would invite the
 * question of how long was enough. The mark records the local calendar day it was made for, as an epoch
 * day plus the offset it was resolved at, exactly as a measured interval does, so a habit kept in Moscow
 * and marked after a flight does not silently move to another day.
 *
 * Marks are never edited in place. A mark deleted and made again is two rows, and the second one is the
 * live one, because [dayMarker] — the nullable twin of [localDay] — is what the unique index is built on.
 * SQLite treats nulls as distinct inside a unique index, so this is the pattern this schema already uses
 * for "one open interval per profile": the live row carries the day and a second mark for the same day is
 * refused, while retired rows carry nothing in that column and collide with nothing. The retired row keeps
 * its own [localDay], so the history of when a mark was made and unmade is still readable.
 */
@Entity(
    tableName = "habit_checks",
    indices = [
        Index(value = ["habit_uuid", "day_marker"], unique = true),
        // Every figure — streak, week, percentage — is a range over the day of the mark.
        Index(value = ["user_id", "local_day"]),
        Index(value = ["habit_uuid", "local_day"]),
        Index(value = ["uuid"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
@TypeConverters(Converters::class)
data class HabitCheck(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    /** The habit by its stable identity, with no foreign key: a mark outlives the habit it was made for. */
    @ColumnInfo(name = "habit_uuid")
    val habitUuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "local_day")
    val localDay: Long,

    /** The offset behind [localDay], kept so a day can be shown and audited the way it was marked. */
    @ColumnInfo(name = "utc_offset_minutes")
    val utcOffsetMinutes: Int,

    /** [localDay] while this mark stands, null once it has been taken back. See the class comment. */
    @ColumnInfo(name = "day_marker")
    val dayMarker: Long? = null,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** The mark the screens count: a day the user stands behind. */
    val isLive: Boolean get() = !isDeleted && dayMarker != null
}

/**
 * One live mark, named by its habit and its day and by nothing else.
 *
 * The shape a profile-wide window read returns, where the row itself is not wanted: the report's habit
 * section asks which habits were kept between two days, and pulling a [HabitCheck] for each of them would
 * carry the uuid, the offset frozen at measurement, the tombstone columns and four timestamps to read two
 * figures off. [localDay] is the day the mark was made on in the zone the user was in when they made it,
 * exactly as [HabitCheck.localDay] states it, so grouping these rows by day agrees with the cards.
 */
data class HabitMarkedDay(
    val habitUuid: String,
    val localDay: Long
)

/**
 * The record that a mark was taken back or moved, and what it was before.
 *
 * A mark is a fact the user asserted on a day, and un-asserting it is worth as much history as correcting
 * a measured interval was: without this row, "the user missed Tuesday" and "the user marked Tuesday and
 * then said that was a mistake" are the same database state, and a streak that used to reach today cannot
 * be explained. Append-only like every other history table here — a later correction adds a row rather
 * than editing the one that records the earlier one.
 *
 * [newLocalDay] null means the mark was taken back outright; a value means it moved to that day. Either
 * way [oldLocalDay] holds the day the screen had been showing as kept.
 */
@Entity(
    tableName = "habit_check_changes",
    indices = [
        Index(value = ["check_uuid"]),
        Index(value = ["habit_uuid", "created_at"]),
        Index(value = ["user_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
@TypeConverters(Converters::class)
data class HabitCheckChange(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    /** The [HabitCheck.uuid] this change was made against. */
    @ColumnInfo(name = "check_uuid")
    val checkUuid: String,

    @ColumnInfo(name = "habit_uuid")
    val habitUuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "old_local_day")
    val oldLocalDay: Long,

    @ColumnInfo(name = "new_local_day")
    val newLocalDay: Long? = null,

    /** Why the user changed it, in their words; optional, and never invented for them. */
    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
