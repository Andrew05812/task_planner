package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalTaskLink
import com.taskplanner.data.model.GoalValueChange
import kotlinx.coroutines.flow.Flow

/** What a linked set of tasks holds: how many were asked for, and how many of those are finished. */
data class GoalSetProgress(
    /**
     * Every task the goal names, including one whose row is gone.
     *
     * It counts the link rather than the task, because the user asked for that work and the goal still
     * says so even after the task was deleted physically. A denominator that quietly dropped a task would
     * turn "3 of 5" into "3 of 4" and read as progress made while nothing was done.
     */
    val linked: Int,

    /** Of the named tasks, those finished and not tombstoned. */
    val completed: Int,

    /** Of the named tasks, those whose row no longer exists at all — reported rather than hidden. */
    val missing: Int
)

/** One task of a goal's own set, with the goal's view of it. */
data class GoalTaskRow(
    val taskUuid: String,
    /** Null when the task was deleted physically: the goal still names it, the screen has nowhere to open. */
    val title: String?,
    val taskId: Long?,
    val completed: Boolean,
    val addedAt: Long
)

/**
 * The rows a goal owns — its own figures, its links, and the history of its targets.
 *
 * There is no progress column here to read and no query that writes one. A goal's progress is answered by
 * joining its links to `tasks` at the moment of the read, and by the two aggregates the dashboard already
 * uses for the profile-wide kinds (`completedTasksInPeriod` and `periodSummary`), so completing, cancelling
 * or deleting a task changes the goal the instant it changes the task list. Nothing can go stale, because
 * nothing was cached.
 *
 * Like every other DAO in this app, every query starts from the profile: `user_id` is a condition rather
 * than an afterthought, and the uuid-keyed reads all carry it too, so a row of another account cannot be
 * reached even by a caller holding its uuid.
 */
@Dao
interface GoalDao {

    @Insert
    suspend fun insertGoal(goal: Goal): Long

    @Update
    suspend fun updateGoal(goal: Goal)

    @Query("SELECT * FROM goals WHERE uuid = :uuid AND user_id = :userId AND is_deleted = 0")
    suspend fun getGoal(uuid: String, userId: Long): Goal?

    /**
     * The goals on the screen, open ones first by default.
     *
     * An archived goal is asked for by name rather than included by default: the list of things the user
     * is working toward is the screen's main subject, and putting retired aims in the middle of it would
     * make the count on the card lie about how much is still in progress.
     */
    @Query(
        "SELECT * FROM goals WHERE user_id = :userId AND is_deleted = 0 AND archived_at IS NULL " +
            "ORDER BY created_at DESC"
    )
    fun observeOpenGoals(userId: Long): Flow<List<Goal>>

    @Query(
        "SELECT * FROM goals WHERE user_id = :userId AND is_deleted = 0 AND archived_at IS NOT NULL " +
            "ORDER BY archived_at DESC"
    )
    fun observeArchivedGoals(userId: Long): Flow<List<Goal>>

    /** Everything the profile holds, tombstones included, for the account purge and for export. */
    @Query("SELECT * FROM goals WHERE user_id = :userId ORDER BY created_at ASC")
    suspend fun getAllForUser(userId: Long): List<Goal>

    @Query(
        "UPDATE goals SET is_deleted = 1, deleted_at = :now, updated_at = :now, " +
            "sync_state = 'DELETING' WHERE uuid = :uuid AND user_id = :userId"
    )
    suspend fun softDelete(uuid: String, userId: Long, now: Long)

    /**
     * Puts a goal away without deleting it.
     *
     * The links and the target history stay: an aim the user retired is still a record of what they were
     * aiming at, and the work it counted is still in the task and interval rows.
     */
    @Query("UPDATE goals SET archived_at = :now, updated_at = :now WHERE uuid = :uuid AND user_id = :userId")
    suspend fun archive(uuid: String, userId: Long, now: Long)

    @Query("UPDATE goals SET archived_at = NULL, updated_at = :now WHERE uuid = :uuid AND user_id = :userId")
    suspend fun unarchive(uuid: String, userId: Long, now: Long)

    // region links

    /**
     * Names one task for a goal.
     *
     * `IGNORE` because the same task asked for twice is the same request: the composite key would reject
     * the second link, and a goal whose denominator counts one piece of work twice is a goal that can
     * never be reached. A no-op keeps the caller from having to ask first.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: GoalTaskLink): Long

    /**
     * Drops one name from a goal's set.
     *
     * `user_id` is in the predicate rather than checked by the caller: two accounts that imported each
     * other's data can hold a goal and a task under the same two uuids, and unlinking must only ever be
     * able to undo the caller's own link.
     */
    @Query(
        "DELETE FROM goal_tasks WHERE goal_uuid = :goalUuid AND task_uuid = :taskUuid " +
            "AND user_id = :userId"
    )
    suspend fun removeLink(goalUuid: String, taskUuid: String, userId: Long)

    /** The uuids a goal names, in the order the user put them — the editor's current selection. */
    @Query(
        "SELECT * FROM goal_tasks WHERE goal_uuid = :goalUuid AND user_id = :userId ORDER BY added_at ASC"
    )
    suspend fun linksOf(goalUuid: String, userId: Long): List<GoalTaskLink>

    /**
     * The goal's own set, as the screen shows it: title, whether it is finished, and whether it still
     * exists.
     *
     * A `LEFT JOIN`, because a link outlives the task it pointed at. The alternative — an inner join —
     * would make a deleted task vanish from the list, and the user would see a goal asking for four tasks
     * when they named five, with no way to find out where the fifth went.
     */
    @Query(
        "SELECT gt.task_uuid AS taskUuid, t.title AS title, t.id AS taskId, " +
            "CASE WHEN t.is_deleted = 0 AND UPPER(t.status) IN ('COMPLETED', 'DONE') THEN 1 ELSE 0 END AS completed, " +
            "gt.added_at AS addedAt " +
            "FROM goal_tasks gt LEFT JOIN tasks t ON t.uuid = gt.task_uuid AND t.user_id = gt.user_id " +
            "WHERE gt.goal_uuid = :goalUuid AND gt.user_id = :userId ORDER BY gt.added_at ASC"
    )
    fun observeTaskRows(goalUuid: String, userId: Long): Flow<List<GoalTaskRow>>

    /**
     * The two numbers a set goal is drawn from, in one scan of its links.
     *
     * `UPPER(status) IN ('COMPLETED', 'DONE')` is the same spelling rule the dashboard counts with and
     * `Converters` reads with, so a goal and the completion figure beside it cannot disagree about a row
     * an older build or an import spelled `done`.
     */
    @Query(
        "SELECT COUNT(*) AS linked, " +
            "COALESCE(SUM(CASE WHEN t.is_deleted = 0 AND UPPER(t.status) IN ('COMPLETED', 'DONE') " +
            "THEN 1 ELSE 0 END), 0) AS completed, " +
            "COALESCE(SUM(CASE WHEN t.uuid IS NULL THEN 1 ELSE 0 END), 0) AS missing " +
            "FROM goal_tasks gt LEFT JOIN tasks t ON t.uuid = gt.task_uuid AND t.user_id = gt.user_id " +
            "WHERE gt.goal_uuid = :goalUuid AND gt.user_id = :userId"
    )
    suspend fun setProgress(goalUuid: String, userId: Long): GoalSetProgress

    /**
     * The same two numbers as a Flow, so a set goal re-reads itself the moment one of its tasks is
     * finished, cancelled or thrown away.
     *
     * This is what makes the recomputation §5 asks for automatic rather than scheduled: the join runs
     * again when `tasks` moves, so there is no event a writer could miss and no cached figure left
     * standing behind the change.
     */
    @Query(
        "SELECT COUNT(*) AS linked, " +
            "COALESCE(SUM(CASE WHEN t.is_deleted = 0 AND UPPER(t.status) IN ('COMPLETED', 'DONE') " +
            "THEN 1 ELSE 0 END), 0) AS completed, " +
            "COALESCE(SUM(CASE WHEN t.uuid IS NULL THEN 1 ELSE 0 END), 0) AS missing " +
            "FROM goal_tasks gt LEFT JOIN tasks t ON t.uuid = gt.task_uuid AND t.user_id = gt.user_id " +
            "WHERE gt.goal_uuid = :goalUuid AND gt.user_id = :userId"
    )
    fun observeSetProgress(goalUuid: String, userId: Long): Flow<GoalSetProgress>

    /** The goals that name a given task, for the task's own screen. */
    @Query(
        "SELECT g.* FROM goals g JOIN goal_tasks gt ON gt.goal_uuid = g.uuid " +
            "WHERE gt.task_uuid = :taskUuid AND g.user_id = :userId AND g.is_deleted = 0 " +
            "AND g.archived_at IS NULL"
    )
    suspend fun goalsForTask(taskUuid: String, userId: Long): List<Goal>

    // endregion

    // region target history

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertValueChange(change: GoalValueChange)

    /**
     * One goal's target history, oldest first.
     *
     * The order is the one the screen prints a story in: the aim the user started with at the top, the
     * most recent change at the bottom.
     */
    @Query(
        "SELECT * FROM goal_value_changes WHERE goal_uuid = :goalUuid AND user_id = :userId " +
            "ORDER BY created_at ASC"
    )
    suspend fun valueChangesOf(goalUuid: String, userId: Long): List<GoalValueChange>

    @Query(
        "SELECT * FROM goal_value_changes WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun recentValueChanges(userId: Long, limit: Int): List<GoalValueChange>

    // endregion
}
