package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taskplanner.data.model.Achievement
import kotlinx.coroutines.flow.Flow

/**
 * The grants a profile has earned.
 *
 * Three things shape this interface, and all three come from §7. **A grant is written once**: the pass asks
 * [insertOrIgnore] and a row that already exists is refused by the unique index, keeping its original date, so
 * "не начисляй достижение повторно" does not depend on the pass remembering anything. **A grant is never
 * amended or deleted by the app**: there is no update or delete statement here at all, because the reader's
 * history is theirs — un-completing the tenth task does not take back the achievement the tenth task earned,
 * and a screen that let the app revise that would make the whole idea of an achievement conditional on the
 * future. **A grant is not a notification**: nothing here tracks whether the reader has looked, because the app
 * must not turn this into a game that nags.
 *
 * The pass reads [earnedKeys] rather than loading every row, and the repository reads one Flow and files the
 * rows itself: the number of achievements a profile can hold is bounded by the number of rules the app ships, so
 * this is the one table where reading the whole set is the cheap option.
 */
@Dao
interface AchievementDao {

    /**
     * Records a grant, or does nothing if this profile already holds it.
     *
     * `IGNORE` rather than `REPLACE` on purpose: REPLACE would delete the existing row and write the new one,
     * which would re-date an achievement the profile earned long ago and quietly rewrite its stored wording.
     * The return value is the row id, or -1 when the grant was already there — the pass reports "newly earned"
     * from that number rather than from a lookup that could race with the insert itself.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(achievement: Achievement): Long

    /**
     * This profile's grant of one achievement, if it has one.
     *
     * The read a caller reaches for when it wants the stored row behind one key: the tests that check what a pass
     * left on the table ask for exactly one grant, and this is the cheapest way to name it.
     *
     * The pass itself does not use it. Deciding whether a condition was already earned is one question about
     * every rule, and [earnedKeys] answers all of them with one read of one column — a lookup per rule would be
     * the slower way to say the same thing, and a slower way of exactly the question that is asked on every
     * screen opening the history is on.
     */
    @Query("SELECT * FROM achievements WHERE user_id = :userId AND achievement_key = :key LIMIT 1")
    suspend fun byKey(userId: Long, key: String): Achievement?

    /**
     * The keys this profile already holds.
     *
     * A pass that asked for the whole table would get rows it does not need: the only thing that changes whether
     * a rule is measured is whether its grant exists, and a text column answers that.
     */
    @Query("SELECT achievement_key FROM achievements WHERE user_id = :userId")
    suspend fun earnedKeys(userId: Long): List<String>

    /** Every grant, newest earned first — the list the achievements screen reads and files by category. */
    @Query("SELECT * FROM achievements WHERE user_id = :userId ORDER BY earned_at DESC, id DESC")
    fun observeEarned(userId: Long): Flow<List<Achievement>>

    /**
     * Grants earned inside an inclusive epoch-day window, oldest first.
     *
     * The window arrives as days rather than milliseconds because the caller is a report: it cuts its period in
     * the time zone the reader asked for, and the day a grant landed on is the fact the report is about. A
     * month's report wants the grants that fall inside that month *in that zone*, and converting the period to
     * instants here would re-apply whichever zone this build happens to run in.
     *
     * §10's report is the caller this was written for and it arrives later in this stage, so nothing in the app
     * reads it yet. What it does to a window — both ends inclusive, the order a printed section wants, rows
     * belonging to another profile kept out — is stated and tested here rather than left to be discovered when
     * the report starts relying on it.
     */
    @Query(
        "SELECT * FROM achievements WHERE user_id = :userId AND earned_day >= :fromDay " +
            "AND earned_day <= :toDay ORDER BY earned_at ASC, id ASC"
    )
    suspend fun earnedBetween(userId: Long, fromDay: Long, toDay: Long): List<Achievement>

    /**
     * How many grants landed in the window, without handing the caller rows to count.
     *
     * The same window as [earnedBetween] and the same reason for it; a report's summary line asks for the count
     * while its section asks for the rows, and one of the two should not have to load the other.
     */
    @Query(
        "SELECT COUNT(*) FROM achievements WHERE user_id = :userId AND earned_day >= :fromDay " +
            "AND earned_day <= :toDay"
    )
    suspend fun countEarnedBetween(userId: Long, fromDay: Long, toDay: Long): Int
}
