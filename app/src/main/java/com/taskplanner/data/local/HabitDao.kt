package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheck
import com.taskplanner.data.model.HabitCheckChange
import kotlinx.coroutines.flow.Flow

/**
 * The habits a profile owns, the marks made on them, and the history of taking a mark back.
 *
 * The shape of this DAO is set by one decision: nothing here stores a streak, a percentage, or a count.
 * Every figure the habits screen shows is answered from the mark rows within the window the question is
 * about, so a mark made, moved or taken back changes every number that mentions it the moment the row
 * changes. The consequence for queries is that they are range reads — [liveDays] and [liveChecksIn] —
 * bounded by the window asked for rather than by the account, which is what keeps a five-year habit from
 * loading five years of rows to print this week's figure.
 *
 * Two writes here are constraints rather than courtesies. [insertCheck] aborts, because the unique index on
 * `(habit_uuid, day_marker)` is the database's own answer to "the same day marked twice"; and
 * [retireMark] nulls the marker instead of deleting the row, because the history of a mark the user took
 * back is a fact about the habit that no figure needs but the correction history does.
 *
 * Like every other DAO in this app, every query starts from the profile.
 */
@Dao
interface HabitDao {

    // region habits

    @Insert
    suspend fun insertHabit(habit: Habit): Long

    @Update
    suspend fun updateHabit(habit: Habit)

    @Query("SELECT * FROM habits WHERE uuid = :uuid AND user_id = :userId AND is_deleted = 0")
    suspend fun getHabit(uuid: String, userId: Long): Habit?

    /**
     * The habits on the screen: those still being asked for, oldest first.
     *
     * Creation order rather than title order, because a habit list is read as a timeline of what the user
     * decided to start, and the newest addition is the one they have to find. Archived habits are asked
     * for by name for the same reason goals are: putting retired ones in the middle of the list would make
     * the count of habits in progress wrong.
     */
    @Query(
        "SELECT * FROM habits WHERE user_id = :userId AND is_deleted = 0 AND archived_at IS NULL " +
            "ORDER BY created_at ASC"
    )
    fun observeOpenHabits(userId: Long): Flow<List<Habit>>

    @Query(
        "SELECT * FROM habits WHERE user_id = :userId AND is_deleted = 0 AND archived_at IS NOT NULL " +
            "ORDER BY archived_at DESC"
    )
    fun observeArchivedHabits(userId: Long): Flow<List<Habit>>

    @Query("SELECT * FROM habits WHERE user_id = :userId AND is_deleted = 0 ORDER BY created_at ASC")
    suspend fun getOpenHabits(userId: Long): List<Habit>

    /** Everything the profile holds, tombstones included, for the account purge and for export. */
    @Query("SELECT * FROM habits WHERE user_id = :userId ORDER BY created_at ASC")
    suspend fun getAllForUser(userId: Long): List<Habit>

    /**
     * Soft-deletes a habit and its live marks in one statement pair.
     *
     * The marks are retired as well as the habit because [HabitCheck.isLive] is what every figure counts:
     * leaving live rows behind a deleted habit would keep a habit the user threw away contributing to the
     * profile's marked days. The rows and their [HabitCheckChange] history stay, which is the append-only
     * rule this schema keeps everywhere else.
     */
    @Query(
        "UPDATE habits SET is_deleted = 1, deleted_at = :now, updated_at = :now, " +
            "sync_state = 'DELETING' WHERE uuid = :uuid AND user_id = :userId"
    )
    suspend fun softDelete(uuid: String, userId: Long, now: Long)

    @Query(
        "UPDATE habit_checks SET is_deleted = 1, deleted_at = :now, day_marker = NULL, " +
            "sync_state = 'DELETING' WHERE habit_uuid = :habitUuid AND user_id = :userId AND is_deleted = 0"
    )
    suspend fun retireMarksOf(habitUuid: String, userId: Long, now: Long)

    /**
     * Stops asking for a habit without discarding it.
     *
     * The marks stay live: an archived habit's streak and percentage are still a record of what the user
     * did, and a figure that quietly dropped to zero on archive would read as though the work had.
     */
    @Query("UPDATE habits SET archived_at = :now, updated_at = :now WHERE uuid = :uuid AND user_id = :userId")
    suspend fun archive(uuid: String, userId: Long, now: Long)

    @Query("UPDATE habits SET archived_at = NULL, updated_at = :now WHERE uuid = :uuid AND user_id = :userId")
    suspend fun unarchive(uuid: String, userId: Long, now: Long)

    // endregion

    // region marks

    /**
     * Makes a mark. Aborts on conflict — deliberately.
     *
     * The unique index on `(habit_uuid, day_marker)` rejects a second live mark for the same day whatever
     * path writes it: a double tap, two screens, a replayed import. The repository turns that abort into
     * "today is already marked" rather than checking first, because a check-then-insert pair is not a
     * database rule and this app has made every "one live row per key" claim with an index instead.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCheck(check: HabitCheck): Long

    /**
     * A mark that is ignored rather than rejected: the sync pull and an import replay a day the profile
     * already holds, and a replayed mark is the same assertion, not a second one.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckOrIgnore(check: HabitCheck): Long

    @Update
    suspend fun updateCheck(check: HabitCheck)

    @Query(
        "SELECT * FROM habit_checks WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "AND day_marker = :day AND is_deleted = 0 LIMIT 1"
    )
    suspend fun liveMark(habitUuid: String, userId: Long, day: Long): HabitCheck?

    @Query("SELECT * FROM habit_checks WHERE uuid = :uuid AND user_id = :userId LIMIT 1")
    suspend fun getCheck(uuid: String, userId: Long): HabitCheck?

    /**
     * The days this habit was kept in a window, ascending.
     *
     * The projection is a bare column because every figure that reads this — the streak, the week's
     * "3 of 4", the percentage — is arithmetic on days, and a caller that has the days does not need the
     * uuid, the offset or the timestamps to answer them. The window is `[fromInclusive, toInclusive]` and
     * the caller computes it, which is how a screen showing nine weeks asks for nine weeks rather than
     * the whole account.
     */
    @Query(
        "SELECT local_day FROM habit_checks WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "AND is_deleted = 0 AND day_marker IS NOT NULL " +
            "AND local_day BETWEEN :fromInclusive AND :toInclusive ORDER BY local_day ASC"
    )
    suspend fun liveDays(habitUuid: String, userId: Long, fromInclusive: Long, toInclusive: Long): List<Long>

    @Query(
        "SELECT * FROM habit_checks WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "AND is_deleted = 0 AND day_marker IS NOT NULL " +
            "AND local_day BETWEEN :fromInclusive AND :toInclusive ORDER BY local_day ASC"
    )
    fun observeLiveChecks(habitUuid: String, userId: Long, fromInclusive: Long, toInclusive: Long):
        Flow<List<HabitCheck>>

    @Query(
        "SELECT * FROM habit_checks WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "AND is_deleted = 0 AND day_marker IS NOT NULL " +
            "AND local_day BETWEEN :fromInclusive AND :toInclusive ORDER BY local_day ASC"
    )
    suspend fun liveChecksIn(habitUuid: String, userId: Long, fromInclusive: Long, toInclusive: Long):
        List<HabitCheck>

    /** The whole live history of one habit, oldest first: the detail screen's own grid. */
    @Query(
        "SELECT local_day FROM habit_checks WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "AND is_deleted = 0 AND day_marker IS NOT NULL ORDER BY local_day ASC"
    )
    fun observeAllLiveDays(habitUuid: String, userId: Long): Flow<List<Long>>

    @Query(
        "SELECT COUNT(*) FROM habit_checks WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "AND is_deleted = 0 AND day_marker IS NOT NULL " +
            "AND local_day BETWEEN :fromInclusive AND :toInclusive"
    )
    suspend fun countLive(habitUuid: String, userId: Long, fromInclusive: Long, toInclusive: Long): Int

    /**
     * Takes a day's mark back.
     *
     * Two columns change for one reason: [HabitCheck.dayMarker] goes null so the unique index stops
     * holding that day and a fresh mark for it becomes possible, and `is_deleted` goes 1 so no live-row
     * query counts it. The [HabitCheck.localDay] stays exactly as it was, because "the user marked
     * Tuesday and then un-marked it" is the story the correction row next to it tells, and it needs both
     * halves to be readable.
     */
    @Query(
        "UPDATE habit_checks SET day_marker = NULL, is_deleted = 1, deleted_at = :now, " +
            "updated_at = :now, sync_state = 'DELETING' " +
            "WHERE habit_uuid = :habitUuid AND user_id = :userId AND day_marker = :day AND is_deleted = 0"
    )
    suspend fun retireMark(habitUuid: String, userId: Long, day: Long, now: Long): Int

    @Query("SELECT * FROM habit_checks WHERE user_id = :userId")
    suspend fun getAllChecksForUser(userId: Long): List<HabitCheck>

    @Query("SELECT * FROM habit_checks WHERE sync_state != 'SYNCED'")
    suspend fun getPendingSyncChecks(): List<HabitCheck>

    @Query("UPDATE habit_checks SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setCheckSyncState(uuid: String, state: String)

    @Query("UPDATE habits SET sync_state = :state WHERE uuid = :uuid")
    suspend fun setHabitSyncState(uuid: String, state: String)

    // endregion

    // region mark history

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCheckChange(change: HabitCheckChange): Long

    /**
     * One habit's correction history, oldest first.
     *
     * Ordered by [HabitCheckChange.createdAt] rather than grouped by mark: the screen shows the account of
     * a habit — which days were asserted and which were walked back — in the order it happened, and a mark
     * that was taken back and made again appears as the two events it was.
     */
    @Query(
        "SELECT * FROM habit_check_changes WHERE habit_uuid = :habitUuid AND user_id = :userId " +
            "ORDER BY created_at ASC"
    )
    suspend fun checkChangesOf(habitUuid: String, userId: Long): List<HabitCheckChange>

    @Query(
        "SELECT * FROM habit_check_changes WHERE check_uuid = :checkUuid AND user_id = :userId " +
            "ORDER BY created_at ASC"
    )
    suspend fun checkChangesOfMark(checkUuid: String, userId: Long): List<HabitCheckChange>

    @Query(
        "SELECT * FROM habit_check_changes WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun recentCheckChanges(userId: Long, limit: Int): List<HabitCheckChange>

    @Query("SELECT * FROM habit_check_changes WHERE user_id = :userId")
    suspend fun getAllChangesForUser(userId: Long): List<HabitCheckChange>

    // endregion

    // region reminders

    /**
     * The habits that could still be owed a reminder on [day]: a reminder minute set, the habit live, and
     * that day's delivery note not yet written.
     *
     * This is the candidate list, not the answer. Whether the day actually asks for the habit — every day,
     * named weekdays, or a count that still has room in its week — is a rule over the frequency and the
     * marks, and it belongs with the other derived figures rather than in SQL. Keeping the two steps apart
     * is what lets the sweep ask one cheap indexed question per profile instead of joining marks to
     * habits to compute a schedule for habits that have no minute set.
     */
    @Query(
        "SELECT * FROM habits WHERE user_id = :userId AND is_deleted = 0 AND archived_at IS NULL " +
            "AND remind_minute_of_day IS NOT NULL " +
            "AND (reminded_on_day IS NULL OR reminded_on_day != :day) ORDER BY remind_minute_of_day ASC"
    )
    suspend fun reminderCandidates(userId: Long, day: Long): List<Habit>

    @Query(
        "SELECT * FROM habits WHERE user_id = :userId AND is_deleted = 0 AND archived_at IS NULL " +
            "AND remind_minute_of_day IS NOT NULL ORDER BY remind_minute_of_day ASC"
    )
    suspend fun habitsWithReminder(userId: Long): List<Habit>

    /** Writes the delivery note, so the same day is not announced twice by an alarm and a sweep. */
    @Query(
        "UPDATE habits SET reminded_on_day = :day, updated_at = :now " +
            "WHERE uuid = :uuid AND user_id = :userId"
    )
    suspend fun markReminded(uuid: String, userId: Long, day: Long, now: Long)

    /**
     * Clears the note for a snoozed habit: the day is owed an announcement again, and the alarm already
     * armed for the snooze minute is the one that will make it.
     */
    @Query(
        "UPDATE habits SET reminded_on_day = NULL, updated_at = :now " +
            "WHERE uuid = :uuid AND user_id = :userId"
    )
    suspend fun clearRemindedOn(uuid: String, userId: Long, now: Long)

    /**
     * The note from a day the habit no longer means anything for.
     *
     * A habit reminded on Monday and then given a Tuesday start must not carry Monday's note into the next
     * week; this clears only the day named, so a note for today survives a re-read of the schedule.
     */
    @Query(
        "UPDATE habits SET reminded_on_day = NULL, updated_at = :now " +
            "WHERE user_id = :userId AND reminded_on_day = :day"
    )
    suspend fun clearRemindedOnDay(userId: Long, day: Long, now: Long)

    // endregion

    /** The signal the habits screen re-reads itself on: the moment anything in the profile's habits moved. */
    @Query("SELECT COALESCE(MAX(updated_at), 0) FROM habits WHERE user_id = :userId")
    fun observeChanges(userId: Long): Flow<Long>
}
