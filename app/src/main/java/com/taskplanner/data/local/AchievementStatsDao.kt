package com.taskplanner.data.local

import androidx.room.Dao
import androidx.room.Query
import com.taskplanner.data.model.CompletedGroup

/**
 * The lifetime counters the achievements are judged against.
 *
 * An achievement is a claim about everything a profile has ever done, so these reads deliberately have no
 * window in them: a "100 tasks" grant that counted only this month would be handed out on the first of the
 * month after a busy one, and taken back in the reader's own history by a quiet one. What the dashboard and the
 * reports ask for is a figure inside a period; what these questions ask for is a total, and a total is
 * cheaper — one scan of the profile's own rows, aggregated in SQLite, returning a number.
 *
 * The same two rules the other statistics reads keep apply here: nothing selects `*`, and a tombstone is not a
 * piece of work. Finished tasks are counted by their `completed_at` rather than by whichever spelling the status
 * column happens to hold, because an import or an old release may have written `DONE`, `done` or `COMPLETED` for
 * the same finished task, and the instant is what the app set when the work was marked done — and what it sets
 * back to null when the reader un-completes it, which is how a cancelled task stops being counted.
 *
 * Two of the §7 counters are not here, and both for the same reason: whether a goal was reached and what a
 * habit's best streak was are questions the goals and habits screens already answer, out of aggregates that
 * differ by goal type and by each habit's own schedule. The pass asks [com.taskplanner.data.repository.
 * GoalRepository.progressOf] and [com.taskplanner.data.repository.HabitRepository.figuresOf] instead of
 * re-stating that arithmetic against another table, so the number an achievement was granted on is the number
 * the screen beside it states — and a change to how a goal is counted moves both together.
 */
@Dao
interface AchievementStatsDao {

    /**
     * Tasks this profile has finished, ever.
     *
     * Archived rows are in: a task the reader put away after finishing was still finished, and the history list
     * already counts it. Deleted rows are not, because a tombstone is the reader saying there was no such work —
     * and §7 asks that an achievement never be paid out for a task that was cancelled rather than done, which
     * is the same row seen from the other side.
     */
    @Query(
        "SELECT COUNT(*) FROM tasks WHERE user_id = :userId AND is_deleted = 0 " +
            "AND completed_at IS NOT NULL"
    )
    suspend fun completedTasksEver(userId: Long): Long

    /**
     * Whole days this profile left measured work on, ever.
     *
     * Counted from `work_sessions`'s own `local_day`, the day each interval was measured on as decided at the
     * moment of measuring — not from a division of instants by a day length, which would move a session that ran
     * across midnight local into whichever UTC day it happened to sit in. A run of use is a run of the reader's
     * days, so it has to be counted in the calendar they lived through. Open intervals are excluded, exactly as
     * every work total in this app excludes them: an interval with no end has no length, and counting the day it
     * started would credit the reader with a day of work they may never have finished.
     *
     * This is the figure "30 days of using the app" is judged against, rather than the days since the account
     * was created: thirty days since an install is a fact about a calendar, not about the reader, and paying an
     * achievement for having left the app on the phone is precisely the nagging §7 asks the system to avoid.
     */
    @Query(
        "SELECT COUNT(DISTINCT local_day) FROM work_sessions WHERE user_id = :userId " +
            "AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    suspend fun activeDays(userId: Long): Int

    /**
     * Seconds of work this profile has measured, ever.
     *
     * The hour figure an achievement prints is this divided by 3600 in the pass, not here: the rule states its
     * threshold in hours because that is what the sentence says, and a query named after hours would let the
     * rounding hide inside the SQL.
     */
    @Query(
        "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions WHERE user_id = :userId " +
            "AND ended_at IS NOT NULL AND is_deleted = 0"
    )
    suspend fun measuredSeconds(userId: Long): Long

    /**
     * Intervals of work this profile has closed out, ever.
     *
     * Counted separately from their length because "the first session" and "a hundred hours" are two different
     * claims about the same table: an interval of ninety seconds is a real first time and is nothing at all
     * measured in hours. Open intervals are excluded, as everywhere else — a stretch with no end has no
     * length and no history entry yet.
     */
    @Query(
        "SELECT COUNT(*) FROM work_sessions WHERE user_id = :userId AND ended_at IS NOT NULL " +
            "AND is_deleted = 0"
    )
    suspend fun measuredSessions(userId: Long): Long

    /**
     * Days this profile kept a habit, summed over every habit it has.
     *
     * The habit's own row is joined so that marks of a habit the reader deleted out of the account stop
     * counting: the mark row is not the work, and the work it was a mark of is gone. A walk-back row is
     * excluded by the same clause every habit figure uses — only a row carrying a day marker is a day kept.
     */
    @Query(
        "SELECT COUNT(*) FROM habit_checks c JOIN habits h ON h.uuid = c.habit_uuid " +
            "AND h.user_id = c.user_id WHERE c.user_id = :userId AND c.is_deleted = 0 " +
            "AND c.day_marker IS NOT NULL AND h.is_deleted = 0"
    )
    suspend fun habitMarks(userId: Long): Long

    /**
     * Groups (projects) whose every live task is finished, ever.
     *
     * The definition is the one a reader would check by opening the project: at least one task in it, and none
     * of the tasks still standing. A group with no tasks has not been completed, it has been emptied, which is a
     * different thing and not an achievement; a group whose tasks were deleted out from under it has not
     * finished either, because there is no work left that the reader carried through.
     *
     * The link is `tasks.group_id`, which holds a group's uuid rather than a numeric row id — the one column
     * pair in this schema joined without a database-level foreign key, so the join is spelled out here rather
     * than trusted to a constraint. Ordered by the day the project was made, oldest first, which is the order a
     * reader would call "the first one closed".
     *
     * The rows come back as a list rather than a count on purpose: §7 wants the achievement to name what was
     * done, and a project count alone cannot say which project was the first one closed.
     */
    @Query(
        "SELECT g.uuid AS groupUuid, g.name AS name FROM task_groups g " +
            "WHERE g.user_id = :userId AND g.is_deleted = 0 AND " +
            "EXISTS (SELECT 1 FROM tasks t WHERE t.group_id = g.uuid AND t.is_deleted = 0) AND " +
            "NOT EXISTS (SELECT 1 FROM tasks t WHERE t.group_id = g.uuid AND t.is_deleted = 0 AND " +
            "t.completed_at IS NULL) ORDER BY g.created_at ASC, g.uuid ASC"
    )
    suspend fun completedGroups(userId: Long): List<CompletedGroup>
}
