package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Query
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task

/**
 * Moving the tables the handover used to forget.
 *
 * `AuthRepository.attachLocalDataToAccount` grew one helper per table as the app gained features, and it
 * stopped at five: tasks, categories, task_groups, recurrence_rules and reminders. The schema this branch ships
 * (`app/schemas/com.taskplanner.data.local.AppDatabase/16.json`, version 16) has **sixteen** tables carrying a
 * `user_id`, and the eleven that arrived with time tracking, goals, habits, insights and achievements were in
 * none of the handover's code. Neither was cloud sync (`SyncRepository.kt:105-158` pushes the same five) nor a
 * backup file (`BackupModels.kt:27-33` lists the same five). So a reader who signed in and accepted "move my
 * local data" kept their measured work, goals, habit marks, insights and achievements in a profile the app had
 * just stopped showing them. This DAO moves them.
 *
 * **Why most of these tables need no merge logic at all.** Every one of them carries a `uuid` that is either its
 * primary key or under a unique index *across the whole table*, not per profile — `habit_checks.uuid`,
 * `goals.uuid`, `insights.uuid`, `achievements.uuid`, `work_sessions.uuid`, `habits.uuid` in the schema. Two
 * profiles therefore cannot hold the same uuid, and the "same record arriving twice" case that
 * `attachTasks`/`attachCategories` handle cannot arise here. A `uuid NOT IN (…)` guard on these tables would be
 * dead code, and dead code in a data move is worse than no code: it reads like a rule that was considered. What
 * *can* collide is the small set of indexes that describe a slot rather than a record, and each of those is
 * answered below in proportion to what a merge would cost — with the answer for each one stated where the
 * statement stands, including the two that the same DDL makes unreachable.
 *
 * **The slots, read off the shipped DDL rather than guessed.** What matters is whether the index spans the table
 * or one profile, and what the column actually holds:
 *
 *  * `reminders (task_uuid, offset_minutes)` and `tasks (series_uuid, occurrence_at)` are unique **across the whole
 *    table**, so two warnings for one task at one minute, or two instances of one series at one instant, cannot be
 *    written by anybody — not by a second profile, not by this handover. Nothing merges them and nothing refuses
 *    them; the index is the guarantee. An earlier draft of this file counted these pairs before the transaction and
 *    refused, which was dead code: the state it guarded cannot exist. It is gone, and this sentence is the reason.
 *    One qualification, because the DDL gives it: both of `tasks`' two columns are nullable (16.json), and SQLite
 *    treats two NULLs as distinct inside a unique index, so what the index forbids is a repeated *dated* instance —
 *    a materialised occurrence always carries both, which is the case that would have collided.
 *  * `tracking_sessions (running_marker)` is unique **across the table** and the marker holds the **owner's user
 *    id** (`WorkTrackingStateMachine.kt:165`), which is how one profile gets exactly one running timer while two
 *    profiles can each have one. Moving a running row is not stopped by that index — the moved marker names the
 *    profile it left, so it differs from the account's own — and that is precisely the problem: the account ends up
 *    answering to two clocks, while `getRunning(userId)` is a `LIMIT 1`, and the marker on the arriving row names a
 *    profile nobody is signed into. A state the index allows and the app cannot use has to be refused by a query, so
 *    [openIntervalsOf] refuses the handover before anything is written and tells the reader to stop the timer.
 *    Because of that refusal a row that does move always carries a null marker.
 *  * `work_sessions (user_id, open_marker)` is per profile, and `work_sessions.uuid` is unique across the table, so
 *    this move cannot collide either: the marker carries the uuid of the **tracking session** the interval belongs
 *    to (`WorkTrackingRepository.kt:520`, where `session` is the `TrackingSession` and the interval's own uuid is
 *    minted one line above), and no other profile can hold a session with that uuid. It is refused for the reason
 *    that matters rather than an index: an open interval is a number still counting on somebody's screen, and the
 *    state machine that started it reads it from the profile it belongs to. Moving the row out from under it would
 *    leave a timer running on a profile the app no longer shows.
 *  * `insights (user_id, rule_key, period_from_day, period_to_day)` and
 *    `achievements (user_id, achievement_key)` are per-profile slots: two profiles can each legitimately hold one
 *    reading of a rule over one window, one grant of a key. The account's own survives and the incoming one is
 *    dropped, which is the only deletion the handover performs outside a uuid twin, and the reason a verified copy
 *    of the database is taken first.
 *
 * **The two guards the current schema makes unreachable.** `goal_tasks (goal_uuid, task_uuid)` is a primary key and
 * `habit_checks (habit_uuid, day_marker)` is a unique index, but both name their parent *by uuid*, and `goals.uuid`
 * and `habits.uuid` are unique across the table — so the pair cannot exist on this schema. `moveGoalTaskLinks`
 * still drops a repeated pair, because two identical links are one fact and keeping one loses nothing.
 * `moveHabitChecks` does not merge at all: two marks of one habit for one day could carry different correction
 * histories, so the plain `UPDATE` lets SQLite refuse the statement if that state ever arrives, the transaction
 * rolls back, and nothing is chosen for the reader that the app cannot justify. The unreachability itself is under
 * test — `AccountAttachTest.the schema says which row identities two profiles can share` reads the live
 * `sqlite_master` and fails if a migration makes one of those identities per-profile, which is the day these two
 * statements need rules and tests of their own. `day_marker` is the *nullable* twin of `localDay` and is NULL on
 * every retired mark (Habit.kt:228-235), and SQLite treats two NULLs as distinct, so any future rule here must
 * compare only where a marker exists — one that folded NULL into a sentinel would delete the reader's mark history
 * as if it were duplicated, and `retired habit marks are not treated as duplicates of each other` guards that.
 *
 * **What is re-pointed rather than dropped.** A row that other rows name must not vanish with its name still in
 * use: `categories.parent_category_id` moves onto the category that survived, and `work_sessions.task_id` and
 * `tracking_sessions.task_id` onto the task that survived. Two of those three are undeclared links —
 * `work_sessions.task_id` and `tracking_sessions.task_id` are plain columns, so `PRAGMA foreign_key_check` cannot
 * see them pointing at a deleted row — and the third is declared but declared `ON DELETE SET NULL`, which is
 * worse than invisible: the child row survives, intact and legal, with its parent gone. No integrity check
 * reports either outcome, which is exactly why `AccountAttachTest` asserts each of them by name.
 *
 * The moves are scoped to `user_id = :source` and the re-pointings to `user_id = :target` — the first because a
 * handover may only empty the profile it was asked about, the second because by then the rows being rewired are
 * already the account's. Nothing here addresses a third profile.
 */
@Dao
interface ProfileAttachDao {

    // ---------------------------------------------------------------- the identities the merge reads
    //
    // `tasks.uuid` and `categories.uuid` are the only two row identities in this schema with nothing enforcing
    // their uniqueness, so one uuid really can be held by two profiles — which is what the merge rules below are
    // for. The question the merge has to ask is "does the profile I am moving *into* already hold this identity",
    // and it can only be asked with the owner in the query.

    /**
     * The account's own copy of one task identity, and nothing else.
     *
     * `TaskDao.findTaskByUuid` is `WHERE uuid = :uuid LIMIT 1` — no owner, no order — and a table scan answers in
     * rowid order, which is the *local* row, because the local profile predates the account's. Read that way the
     * twin is never recognised, the local row is re-tagged beside the account's copy of the same task, and the
     * reader gets one task twice with nothing stranded to complain about. The two tests that cover this merge
     * seeded the account's row first, which is the rowid order that hides the bug, so the fix is a query that
     * cannot answer for the wrong profile and a test that seeds the other way round.
     */
    @Query("SELECT * FROM tasks WHERE uuid = :uuid AND user_id = :userId LIMIT 1")
    suspend fun getTaskOfUser(uuid: String, userId: Long): Task?

    /** The same rule for categories, for the same reason. */
    @Query("SELECT * FROM categories WHERE uuid = :uuid AND user_id = :userId LIMIT 1")
    suspend fun getCategoryOfUser(uuid: String, userId: Long): Category?

    // ---------------------------------------------------------------- the plain moves
    //
    // Identity is globally unique in each of these tables, so there is nothing to merge and no slot to collide
    // on: the rows change owner and nothing else. `work_sessions` also carries
    // `index_work_sessions_user_id_open_marker (user_id, open_marker)`, the open-interval slot; an interval that is
    // still counting is refused by [openIntervalsOf] before this code is reached — not because that index would
    // reject the move, which it would not, but because the clock would leave the profile that is reading it.

    @Query("UPDATE tracking_sessions SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveTrackingSessions(source: Long, target: Long, now: Long): Int

    @Query("UPDATE work_sessions SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveWorkSessions(source: Long, target: Long, now: Long): Int

    @Query(
        """
        UPDATE work_interval_corrections SET user_id = :target, updated_at = :now WHERE user_id = :source
        """
    )
    suspend fun moveIntervalCorrections(source: Long, target: Long, now: Long): Int

    @Query("UPDATE goals SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveGoals(source: Long, target: Long, now: Long): Int

    @Query("UPDATE goal_value_changes SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveGoalValueChanges(source: Long, target: Long, now: Long): Int

    @Query("UPDATE habits SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveHabits(source: Long, target: Long, now: Long): Int

    @Query("UPDATE habit_check_changes SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveHabitCheckChanges(source: Long, target: Long, now: Long): Int

    // ---------------------------------------------------------------- the slots

    /**
     * Marks move with one statement, because a mark cannot have a twin to merge into.
     *
     * `habit_checks (habit_uuid, day_marker)` is unique across the table and its parent habit is keyed by a
     * table-wide unique `uuid`, so two profiles cannot each hold a mark of one habit for one day: the second
     * profile could not have written the habit in the first place. A plain `UPDATE` is therefore both the whole
     * of the rule and its safety net — should that pairing ever exist (a migration making the habit identity
     * per-profile, which `AccountAttachTest`'s schema test would fail first), SQLite refuses the statement, the
     * caller's transaction rolls back, and both profiles are left exactly as they were.
     *
     * That is the right outcome where a merge would not be: two marks of one habit for one day could carry
     * different correction histories, so choosing one to keep would delete a record whose contents the app has no
     * evidence to compare — the same line this stage draws at a lead time or a running timer.
     *
     * Retired marks (`day_marker IS NULL`) matter here for a different reason: the index does not compare NULLs,
     * so a habit may hold any number of them, and `retired habit marks are not treated as duplicates of each
     * other` exists to make sure a future rule that folds NULL into a sentinel does not delete the reader's
     * history of taking a day back.
     */
    @Query("UPDATE habit_checks SET user_id = :target, updated_at = :now WHERE user_id = :source")
    suspend fun moveHabitChecks(source: Long, target: Long, now: Long): Int

    /** A goal's task set: the pair *is* the slot, so a repeated pair is the same link and keeping one loses nothing. */
    @Query(
        """
        UPDATE goal_tasks SET user_id = :target
        WHERE user_id = :source
          AND NOT EXISTS (
            SELECT 1 FROM goal_tasks g
            WHERE g.user_id = :target AND g.goal_uuid = goal_tasks.goal_uuid
              AND g.task_uuid = goal_tasks.task_uuid
          )
        """
    )
    suspend fun moveGoalTaskLinks(source: Long, target: Long): Int

    @Query(
        """
        DELETE FROM goal_tasks
        WHERE user_id = :source
          AND EXISTS (
            SELECT 1 FROM goal_tasks g
            WHERE g.user_id = :target AND g.goal_uuid = goal_tasks.goal_uuid
              AND g.task_uuid = goal_tasks.task_uuid
          )
        """
    )
    suspend fun dropDuplicateGoalTaskLinks(source: Long, target: Long): Int

    /**
     * One reading per rule per window. The account's own copy stands, and the incoming duplicate is dropped —
     * the same claim about the same period, not two different facts.
     */
    @Query(
        """
        UPDATE insights SET user_id = :target, updated_at = :now
        WHERE user_id = :source
          AND NOT EXISTS (
            SELECT 1 FROM insights i
            WHERE i.user_id = :target AND i.rule_key = insights.rule_key
              AND i.period_from_day = insights.period_from_day
              AND i.period_to_day = insights.period_to_day
          )
        """
    )
    suspend fun moveInsights(source: Long, target: Long, now: Long): Int

    @Query(
        """
        DELETE FROM insights
        WHERE user_id = :source
          AND EXISTS (
            SELECT 1 FROM insights i
            WHERE i.user_id = :target AND i.rule_key = insights.rule_key
              AND i.period_from_day = insights.period_from_day
              AND i.period_to_day = insights.period_to_day
          )
        """
    )
    suspend fun dropDuplicateInsights(source: Long, target: Long): Int

    @Query(
        """
        UPDATE achievements SET user_id = :target
        WHERE user_id = :source
          AND achievement_key NOT IN (
              SELECT achievement_key FROM achievements WHERE user_id = :target)
        """
    )
    suspend fun moveAchievements(source: Long, target: Long): Int

    @Query(
        """
        DELETE FROM achievements
        WHERE user_id = :source
          AND achievement_key IN (SELECT achievement_key FROM achievements WHERE user_id = :target)
        """
    )
    suspend fun dropDuplicateAchievements(source: Long, target: Long): Int

    // ---------------------------------------------------------------- links the move must keep

    /**
     * Measured work is joined to its task by the local row id, so a task that merged away takes its hours with it
     * unless they are re-pointed first.
     *
     * `WorkStatisticsDao` groups by `work_sessions.task_id` and joins `tasks.id` (WorkStatisticsDao.kt:113-141).
     * When two profiles held the same task uuid, `attachTasks` deletes one of the two rows; the intervals that
     * named the deleted rowid would otherwise keep their `task_uuid` and lose the join, so the reader's history
     * shows an interval about nothing. [pairs] is the map of source rowid → surviving rowid, and only the rows
     * that lost are in it.
     */
    @Query(
        """
        UPDATE work_sessions SET task_id = :survivor, updated_at = :now
        WHERE user_id = :target AND task_id = :gone
        """
    )
    suspend fun repointWorkSessions(
        target: Long,
        survivor: Long,
        gone: Long,
        now: Long
    ): Int

    @Query(
        """
        UPDATE tracking_sessions SET task_id = :survivor, updated_at = :now
        WHERE user_id = :target AND task_id = :gone
        """
    )
    suspend fun repointTrackingSessions(target: Long, survivor: Long, gone: Long, now: Long): Int

    /**
     * The children of a category that merged into the account's own copy move to that copy.
     *
     * `categories.parent_category_id` is `ON DELETE SET NULL`, so deleting the local copy of a merged category
     * without re-pointing first leaves its children at the top of the tree — a structure the reader built and
     * silently lost. Runs before [AuthRepository]'s `removeMergedCategories`, for the same reason the tasks are
     * moved before it.
     */
    @Query(
        """
        UPDATE categories SET parent_category_id = :survivor, updated_at = :now
        WHERE user_id = :target AND parent_category_id = :gone
        """
    )
    suspend fun repointCategoryChildren(target: Long, survivor: Long, gone: Long, now: Long): Int

    // ---------------------------------------------------------------- the refusals and the check

    /**
     * A running timer is the one row this handover must not move.
     *
     * `work_sessions (user_id, open_marker)` is unique per profile, so an interval that is still open on the
     * local profile cannot be re-tagged onto an account that has one of its own — and an open interval is a
     * number still counting on somebody's screen. The honest answer is to refuse before the transaction opens
     * and say what to do, rather than close somebody's timer for them or roll back halfway with the safety copy
     * already written.
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM tracking_sessions WHERE user_id = :userId AND running_marker IS NOT NULL) +
               (SELECT COUNT(*) FROM work_sessions         WHERE user_id = :userId AND open_marker IS NOT NULL)
        """
    )
    suspend fun openIntervalsOf(userId: Long): Int

    /**
     * Rows still owned by [userId] in every table a handover is responsible for. A non-zero answer is a stranded
     * record — the state this DAO exists to make impossible — so the caller treats it as a failure and rolls the
     * transaction back rather than reporting a completed move. The five tables the handover has always covered
     * are counted here too: the guarantee is one number over sixteen tables, not a promise about the new ones
     * and silence about the old.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM tasks                     WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM categories                WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM task_groups               WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM recurrence_rules          WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM reminders                 WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM tracking_sessions         WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM work_sessions             WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM work_interval_corrections WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM goals                     WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM goal_tasks                WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM goal_value_changes        WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM habits                    WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM habit_checks              WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM habit_check_changes       WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM insights                  WHERE user_id = :userId) +
            (SELECT COUNT(*) FROM achievements              WHERE user_id = :userId)
        """
    )
    suspend fun rowsStillOwnedBy(userId: Long): Int

    /**
     * Which of those tables, by name — because a count that says "one row is left" without saying where leaves the
     * reader, and the next engineer, with nothing to act on. The order is the order [rowsStillOwnedBy] adds the
     * tables in, so the two answers can be read side by side.
     */
    @Query(
        """
        SELECT name FROM (
            SELECT 'tasks' AS name, 0 AS ord, COUNT(*) AS c FROM tasks WHERE user_id = :userId
            UNION ALL SELECT 'categories', 1, COUNT(*) FROM categories WHERE user_id = :userId
            UNION ALL SELECT 'task_groups', 2, COUNT(*) FROM task_groups WHERE user_id = :userId
            UNION ALL SELECT 'recurrence_rules', 3, COUNT(*) FROM recurrence_rules WHERE user_id = :userId
            UNION ALL SELECT 'reminders', 4, COUNT(*) FROM reminders WHERE user_id = :userId
            UNION ALL SELECT 'tracking_sessions', 5, COUNT(*) FROM tracking_sessions WHERE user_id = :userId
            UNION ALL SELECT 'work_sessions', 6, COUNT(*) FROM work_sessions WHERE user_id = :userId
            UNION ALL SELECT 'work_interval_corrections', 7, COUNT(*) FROM work_interval_corrections
                WHERE user_id = :userId
            UNION ALL SELECT 'goals', 8, COUNT(*) FROM goals WHERE user_id = :userId
            UNION ALL SELECT 'goal_tasks', 9, COUNT(*) FROM goal_tasks WHERE user_id = :userId
            UNION ALL SELECT 'goal_value_changes', 10, COUNT(*) FROM goal_value_changes WHERE user_id = :userId
            UNION ALL SELECT 'habits', 11, COUNT(*) FROM habits WHERE user_id = :userId
            UNION ALL SELECT 'habit_checks', 12, COUNT(*) FROM habit_checks WHERE user_id = :userId
            UNION ALL SELECT 'habit_check_changes', 13, COUNT(*) FROM habit_check_changes WHERE user_id = :userId
            UNION ALL SELECT 'insights', 14, COUNT(*) FROM insights WHERE user_id = :userId
            UNION ALL SELECT 'achievements', 15, COUNT(*) FROM achievements WHERE user_id = :userId
        ) WHERE c > 0 ORDER BY ord
        """
    )
    suspend fun strandedTablesOf(userId: Long): List<String>
}
