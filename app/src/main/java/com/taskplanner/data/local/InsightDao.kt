package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.model.InsightKind
import kotlinx.coroutines.flow.Flow

/**
 * The rows the app's own readings of the profile leave behind.
 *
 * Nothing here is a record of the user's work, so the queries are few and narrow: a pass writes what it
 * found, the screen reads what is still being found, and the only answers a person can give are the four
 * feedback states.
 *
 * What a pass may overwrite and what it may not is the whole design of this table. It re-states the claim
 * — [refresh] writes the kind, the three pieces of wording, the sample, the confidence and the action,
 * because a card that contradicted the rows it was computed from would be worse than a card that changed
 * its sentence since the reader last saw it. What it never touches is the two things a pass cannot rebuild:
 * when the reading first arrived, and what the reader said about it. The window and the rule are outside
 * both groups, because they are the row's identity — a rule counting different days is a different reading,
 * and it belongs on a different row.
 */
@Dao
interface InsightDao {

    @Insert
    suspend fun insert(insight: Insight): Long

    /**
     * One row by the id its rule and window compute, without asking for the profile twice.
     *
     * The uuid is derived rather than random, so a pass that found the same thing in the same window can
     * look up exactly the row it is about to refresh, and two passes racing each other collide on the
     * unique index instead of leaving two copies of one observation on the screen.
     */
    @Query("SELECT * FROM insights WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): Insight?

    /**
     * Refreshes a reading the current pass still finds.
     *
     * `first_seen_at` and the two feedback columns are deliberately absent: when the thing was first said is
     * the reader's history, and what they said back is theirs, and a pass that re-derives the same figure
     * has no business rewriting either. The window is absent too — a window is part of the row's identity,
     * and a rule that now counts over different days is a different row.
     */
    @Query(
        "UPDATE insights SET kind = :kind, title = :title, body = :body, basis = :basis, " +
            "sample_size = :sampleSize, confidence = :confidence, action_type = :actionType, " +
            "action_ref = :actionRef, is_current = 1, last_seen_at = :lastSeenAt, " +
            "updated_at = :lastSeenAt WHERE uuid = :uuid"
    )
    suspend fun refresh(
        uuid: String,
        kind: InsightKind,
        title: String,
        body: String,
        basis: String,
        sampleSize: Int,
        confidence: Int,
        actionType: InsightAction?,
        actionRef: String?,
        lastSeenAt: Long
    )

    /**
     * Retires everything this pass did not touch.
     *
     * A reading stops being current the moment the rules stop finding it, and the screen's history is exactly
     * the rows that have stopped — so the pass marks the tail rather than deleting it. Rows the reader has
     * answered are left alone: their `is_current` says whether the rule still fires, and what the reader said
     * is a separate fact about them.
     */
    @Query(
        "UPDATE insights SET is_current = 0, updated_at = :now " +
            "WHERE user_id = :userId AND is_current = 1 AND last_seen_at < :passStartedAt"
    )
    suspend fun markStale(userId: Long, passStartedAt: Long, now: Long): Int

    /**
     * The rules the reader has already refused, and is therefore not being told again yet.
     *
     * "This is not true of me" is an answer about the reading, and the row it lands on is already off the
     * offered list by the filter in [observeCurrent]. What that filter cannot do is stop the same rule from
     * arriving next week with a new window and the same claim — which would teach the reader that the
     * buttons do nothing. So a rejected rule key buys a silence here, measured from the moment the answer was
     * given rather than from the window it was about, and it expires on its own: a month after the reader
     * said this was not them, the profile may well have changed.
     */
    @Query(
        "SELECT DISTINCT rule_key FROM insights WHERE user_id = :userId AND feedback = 'REJECTED' " +
            "AND feedback_at IS NOT NULL AND feedback_at >= :since"
    )
    suspend fun rejectedRuleKeys(userId: Long, since: Long): List<String>

    /**
     * What the reader said about one insight.
     *
     * Keyed on the uuid *and* the profile: the four answers are about the row, and a profile whose session
     * went away mid-tap must not have the answer land on a row belonging to somebody else. The row count is
     * what the caller reports back, so a screen can tell "recorded" apart from "there was nothing here".
     */
    @Query(
        "UPDATE insights SET feedback = :feedback, feedback_at = :at, updated_at = :at " +
            "WHERE uuid = :uuid AND user_id = :userId"
    )
    suspend fun setFeedback(uuid: String, userId: Long, feedback: InsightFeedback, at: Long): Int

    /**
     * The readings the current pass still stands behind, newest first.
     *
     * The feedback filter lives in the SQL rather than in the screen's loop so that a rejected reading cannot
     * come back on the next invalidation — the row is still there, still current while its rule keeps
     * firing, and the only thing that keeps it off the list is this clause.
     */
    @Query(
        "SELECT * FROM insights WHERE user_id = :userId AND is_current = 1 " +
            "AND (feedback IS NULL OR feedback IN ('ACCEPTED', 'USEFUL')) " +
            "ORDER BY last_seen_at DESC, id DESC"
    )
    fun observeCurrent(userId: Long): Flow<List<Insight>>

    /** What the app said about windows the rules no longer stand behind, newest first. */
    @Query(
        "SELECT * FROM insights WHERE user_id = :userId AND is_current = 0 " +
            "ORDER BY last_seen_at DESC, id DESC"
    )
    fun observeHistory(userId: Long): Flow<List<Insight>>

    /** Every row this profile holds, current or not, for a screen that has to show one the reader answered. */
    @Query("SELECT * FROM insights WHERE user_id = :userId ORDER BY id ASC")
    suspend fun allOf(userId: Long): List<Insight>

    /** One row, by what its rule and window compute — the read a test checks a refresh against. */
    @Query("SELECT * FROM insights WHERE user_id = :userId AND rule_key = :ruleKey " +
        "AND period_from_day = :fromDay AND period_to_day = :toDay LIMIT 1")
    suspend fun byKey(userId: Long, ruleKey: String, fromDay: Long, toDay: Long): Insight?

    /**
     * Forgets retired readings the reader never answered.
     *
     * A history is worth keeping, and an unbounded one is a table that grows with every pass of every rule
     * whether anyone looked or not. Only rows the rules stopped finding and the reader said nothing about
     * fall out; anything with feedback is the user's own record and stays, however old the window is. The
     * count is what left, so a pass can report the notebook it keeps rather than the one it threw away.
     */
    @Query(
        "DELETE FROM insights WHERE user_id = :userId AND is_current = 0 AND feedback IS NULL " +
            "AND period_to_day < :olderThanDay"
    )
    suspend fun forgetUntouchedHistoryBefore(userId: Long, olderThanDay: Long): Int
}
