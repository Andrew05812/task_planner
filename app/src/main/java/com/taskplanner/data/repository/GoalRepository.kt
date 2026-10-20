package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.GoalDao
import com.taskplanner.data.local.GoalSetProgress
import com.taskplanner.data.local.GoalTaskRow
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalTaskLink
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.tracking.WorkTimeMath
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
 * How far a goal is, at the moment it was asked for.
 *
 * The whole point of this being a value rather than a column is that it cannot be out of date: it is
 * assembled from the task and interval rows on the read, so nothing here survives as a number after the
 * work it was computed from changed. [target] is what "done" is measured against — the figure the user
 * typed for, or for the two types that name tasks the size of the named set when they typed none, since
 * "3 of 5" is the sentence the screen has to print and [linked] is the denominator that set produces.
 */
data class GoalProgress(
    val goal: Goal,
    /** Tasks finished, or seconds measured, depending on what the goal's type counts. */
    val counted: Long,
    val target: Long,
    /** Tasks the goal names, for the two types that name any; zero for the profile-wide ones. */
    val linked: Int = 0,
    /** Of the named tasks, those finished. */
    val completedLinks: Int = 0,
    /** Of the named tasks, those whose row no longer exists — reported, never dropped from [linked]. */
    val missing: Int = 0,
    /** Past its date and not reached; false for a goal with no date, which cannot be late. */
    val late: Boolean = false
) {
    /**
     * Whether the goal is done.
     *
     * A target of zero is not "achieved" but "not yet aimed at": the screen has to be able to tell a goal
     * the user hasn't set a figure for apart from one they have already passed, and calling the empty one
     * complete would let a card the user just created read as finished work.
     */
    val reached: Boolean get() = target > 0L && counted >= target

    /** Share of the way there, or null when there is no figure to divide by. */
    val fraction: Float? get() = if (target <= 0L) null else (counted.toFloat() / target).coerceIn(0f, 1f)

    /** What is left to reach the target, in the unit the type counts in. */
    val remaining: Long? get() = if (target <= 0L) null else (target - counted).coerceAtLeast(0L)
}

/** A goal and the figure that moved under it. */
data class GoalWithProgress(val goal: Goal, val progress: GoalProgress)

/** Why a goal the user asked for was not written. */
enum class GoalRejection {
    /** No profile is signed in, so there is nothing for the aim to belong to. */
    NO_PROFILE,

    /** The goal named is not in this profile's account — including one it does not own. */
    NOT_FOUND,

    /** A goal without a title is a row the screen cannot name; nothing is invented for the user. */
    BLANK_TITLE,

    /** A negative figure measures nothing, and clamping it to zero would record an aim nobody set. */
    NEGATIVE_TARGET,

    /** A window whose end falls before its start asks about no days at all. */
    WINDOW_ENDS_BEFORE_START,

    /** A deadline goal with no date has no question left to answer. */
    DEADLINE_WITHOUT_A_DATE,

    /** The task belongs to another profile, or is gone. */
    TASK_NOT_IN_PROFILE
}

/**
 * What a goal write ended up doing.
 *
 * A sealed type rather than a null because the editor has three genuinely different answers to give — it
 * saved, it cannot save this, or the task it was pointed at is somebody else's — and each needs its own
 * words on screen rather than a generic failure toast.
 */
sealed class GoalWriteResult {
    data class Saved(val goalUuid: String) : GoalWriteResult()
    data class Rejected(val reason: GoalRejection) : GoalWriteResult()
}

/**
 * The only writer of goals, and the only reader of what a goal has reached.
 *
 * Three rules shape the whole class:
 *
 *  * **Progress is never stored.** The four types are four ways of asking a question that is already
 *    answerable — the completed-task count for a count goal, the period total for a time goal, the links
 *    joined to `tasks` for a set — so completing, cancelling, or deleting a task changes a goal in the
 *    same instant it changes the task list, with no event to subscribe to and no missed event to leave a
 *    stale figure behind. §5's "автопересчёт при завершении/отмене/удалении" is satisfied by the absence of
 *    a cache rather than by a listener.
 *  * **A goal borrows tasks, it never owns them.** [link] writes a row that names a task by its uuid, and
 *    the composite key makes the same task named once. Nothing copies a task into the goal, so there is
 *    no second row free to drift, and the dashboard keeps counting the real one.
 *  * **The target is the one figure the user chose, so changing it is history.** [edit] compares the
 *    request against the stored row and appends one [GoalValueChange] per field that actually moved — a
 *    save that changed nothing writes no row, because a history padded with no-ops is a history nobody
 *    can read.
 *
 * Every write takes the profile first and every read of a row by uuid checks that profile, so §16's
 * isolation holds for a goal the way it does for an interval: after an import two accounts can hold rows
 * under the same uuid, and the one that does not own a row cannot edit it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GoalRepository @Inject constructor(
    private val database: AppDatabase,
    private val goalDao: GoalDao,
    private val taskDao: TaskDao,
    private val statisticsDao: WorkStatisticsDao,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    private val ticker: WorkTicker
) {

    /**
     * Sets a new aim, optionally naming the tasks it is about.
     *
     * [windowFromDay] and [windowToDay] are inclusive epoch days; either may be null, which means the
     * goal is open at that end. [taskUuids] is rejected as soon as one of them is not the profile's own,
     * because a goal that silently counted another account's work would be wrong in exactly the way the
     * screen cannot see.
     *
     * [targetValue] may be left out, and 0 is a storable aim rather than a missing one: the two types
     * that name tasks read their denominator from the set itself, so a goal created before the user typed
     * a figure is a goal that has nothing reached yet, not a goal the editor refused to save.
     */
    suspend fun create(
        title: String,
        type: GoalType,
        targetValue: Long = 0L,
        windowFromDay: Long? = null,
        windowToDay: Long? = null,
        deadlineAt: Long? = null,
        taskUuids: List<String> = emptyList()
    ): GoalWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return GoalWriteResult.Rejected(GoalRejection.NO_PROFILE)
        val rejection = validate(title, targetValue, windowFromDay, windowToDay, type, deadlineAt)
        if (rejection != null) return GoalWriteResult.Rejected(rejection)
        // Checked before anything is written: a goal that stored its own row and then refused half of the
        // set would leave the user with an aim asking for tasks it never got round to naming.
        val strangers = taskUuids.filterNot { taskDao.hasTaskForUser(userId, it) }
        if (strangers.isNotEmpty()) return GoalWriteResult.Rejected(GoalRejection.TASK_NOT_IN_PROFILE)

        val now = timeBase.wallMillis()
        val uuid = UUID.randomUUID().toString()
        database.withTransaction {
            goalDao.insertGoal(
                Goal(
                    uuid = uuid,
                    userId = userId,
                    title = title.trim(),
                    type = type,
                    targetValue = targetValue,
                    windowFromDay = windowFromDay,
                    windowToDay = windowToDay,
                    deadlineAt = deadlineAt,
                    syncState = SyncState.PENDING,
                    createdAt = now,
                    updatedAt = now
                )
            )
            // `added_at` is the order the user put them in, so it is the create instant plus a step per
            // link rather than a timestamp per loop — a tight loop on one clock can otherwise give the
            // screen no order to print in.
            taskUuids.forEachIndexed { index, taskUuid ->
                goalDao.insertLink(
                    GoalTaskLink(
                        goalUuid = uuid,
                        taskUuid = taskUuid,
                        userId = userId,
                        createdAt = now,
                        addedAt = now + index
                    )
                )
            }
        }
        return GoalWriteResult.Saved(uuid)
    }

    /**
     * Applies the editor's request to a stored goal, and records what moved.
     *
     * The five history fields are compared against the row as it stands rather than against what the
     * screen thinks it sent, so an edit that lands on a goal someone else changed still reports the
     * difference it actually made. Everything happens in one transaction: a goal half-updated, with its
     * new target stored and the record of the old one missing, is the exact inconsistency this table
     * exists to prevent.
     *
     * [reason] is the user's own words for why the aim moved, and goes on every row this edit produced;
     * it is never filled in for them.
     */
    suspend fun edit(
        goalUuid: String,
        title: String,
        type: GoalType,
        targetValue: Long,
        windowFromDay: Long?,
        windowToDay: Long?,
        deadlineAt: Long?,
        reason: String? = null
    ): GoalWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return GoalWriteResult.Rejected(GoalRejection.NO_PROFILE)
        val existing = ownGoal(goalUuid, userId)
            ?: return GoalWriteResult.Rejected(GoalRejection.NOT_FOUND)
        val rejection = validate(title, targetValue, windowFromDay, windowToDay, type, deadlineAt)
        if (rejection != null) return GoalWriteResult.Rejected(rejection)

        val now = timeBase.wallMillis()
        val zone = CalendarMath.systemZone()
        val changes = listOfNotNull(
            changeIf(userId, goalUuid, GoalValueChange.FIELD_TITLE, now, reason) {
                existing.title to title.trim()
            },
            // A type change belongs in the history as much as a figure does: it is the question the goal
            // asks that moved, and a goal that counted hours and now counts finishes is a different aim
            // even when the number beside it never changed.
            changeIf(userId, goalUuid, GoalValueChange.FIELD_TYPE, now, reason) {
                existing.type.name to type.name
            },
            changeIf(userId, goalUuid, GoalValueChange.FIELD_TARGET, now, reason) {
                existing.targetValue to targetValue
            },
            changeIf(userId, goalUuid, GoalValueChange.FIELD_WINDOW, now, reason) {
                windowOf(existing.windowFromDay, existing.windowToDay) to
                    windowOf(windowFromDay, windowToDay)
            },
            changeIf(userId, goalUuid, GoalValueChange.FIELD_DEADLINE, now, reason) {
                dateText(existing.deadlineAt, zone) to dateText(deadlineAt, zone)
            }
        )
        database.withTransaction {
            // One edit writes several rows in one millisecond, and the story is read back in
            // `created_at` order, so each row steps a millisecond past the last — the same trick the
            // link order takes in [create]. Without it, an editor that moved the title, the figure and
            // the window at once would hand the screen three rows in whatever order SQLite reached them.
            changes.forEachIndexed { index, change ->
                goalDao.insertValueChange(change.copy(createdAt = now + index, updatedAt = now))
            }
            goalDao.updateGoal(
                existing.copy(
                    title = title.trim(),
                    type = type,
                    targetValue = targetValue,
                    windowFromDay = windowFromDay,
                    windowToDay = windowToDay,
                    deadlineAt = deadlineAt,
                    syncState = SyncState.PENDING,
                    updatedAt = now
                )
            )
        }
        return GoalWriteResult.Saved(goalUuid)
    }

    /**
     * Names one more task for a goal.
     *
     * A goal of the two profile-wide types still takes the link: the type is the user's to change back,
     * and a rule that refused — or, worse, deleted — links the current type happens not to read would
     * throw away an intention the row still carries. [GoalType.TASK_SET] and [GoalType.DEADLINE] are the
     * types that count them.
     *
     * The same task named twice is one request, not two pieces of work: the composite key is what makes
     * that a database fact, so this reports the link it found rather than a second one.
     */
    suspend fun link(goalUuid: String, taskUuid: String): GoalWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return GoalWriteResult.Rejected(GoalRejection.NO_PROFILE)
        val goal = ownGoal(goalUuid, userId)
            ?: return GoalWriteResult.Rejected(GoalRejection.NOT_FOUND)
        if (!taskDao.hasTaskForUser(userId, taskUuid)) {
            return GoalWriteResult.Rejected(GoalRejection.TASK_NOT_IN_PROFILE)
        }
        val now = timeBase.wallMillis()
        database.withTransaction {
            // The new name goes at the end of the set. `added_at` is what orders the screen's list, so it
            // is the later of "now" and "one past the last row" — a plain timestamp would tie for two links
            // written in the same millisecond and leave the order the user put them in undefined.
            val lastAddedAt = goalDao.linksOf(goal.uuid, userId).maxOfOrNull { it.addedAt } ?: 0L
            goalDao.insertLink(
                GoalTaskLink(
                    goalUuid = goal.uuid,
                    taskUuid = taskUuid,
                    userId = userId,
                    createdAt = now,
                    addedAt = maxOf(now, lastAddedAt + 1L)
                )
            )
            // The goal's row moves so the screen's own change signal fires on a link as well as on an
            // edit; without it a goal list watching `goals` would keep showing yesterday's denominator.
            goalDao.updateGoal(goal.copy(syncState = SyncState.PENDING, updatedAt = now))
        }
        return GoalWriteResult.Saved(goalUuid)
    }

    /** Stops naming one task. Whatever is left of the set is still what the goal asks for. */
    suspend fun unlink(goalUuid: String, taskUuid: String): GoalWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return GoalWriteResult.Rejected(GoalRejection.NO_PROFILE)
        val goal = ownGoal(goalUuid, userId)
            ?: return GoalWriteResult.Rejected(GoalRejection.NOT_FOUND)
        val now = timeBase.wallMillis()
        database.withTransaction {
            goalDao.removeLink(goalUuid, taskUuid, userId)
            goalDao.updateGoal(goal.copy(syncState = SyncState.PENDING, updatedAt = now))
        }
        return GoalWriteResult.Saved(goalUuid)
    }

    /** Puts the goal away. Its links and its target history stay, and so does the work it counted. */
    suspend fun archive(goalUuid: String): GoalWriteResult =
        transition(goalUuid) { uuid, user, now -> goalDao.archive(uuid, user, now) }

    /** Brings a retired aim back. */
    suspend fun unarchive(goalUuid: String): GoalWriteResult =
        transition(goalUuid) { uuid, user, now -> goalDao.unarchive(uuid, user, now) }

    /**
     * Throws a goal away, softly, and leaves its links where they are.
     *
     * The links of a deleted goal answer no figure — the goal itself is the thing that stopped existing —
     * but they are rows the user never asked to lose, and an export or a restore that brings the goal back
     * finds the set it named. A hard delete here would be the one write in this feature that could not be
     * undone.
     */
    suspend fun remove(goalUuid: String): GoalWriteResult =
        transition(goalUuid) { uuid, user, now -> goalDao.softDelete(uuid, user, now) }

    /** The goal [goalUuid] names for this profile, or null when the profile does not hold it. */
    suspend fun goal(goalUuid: String): Goal? =
        sessionRepository.currentUserId()?.let { ownGoal(goalUuid, it) }

    /** The uuids the goal names, in the order the user put them — what the editor re-checks. */
    suspend fun linkedTaskUuids(goalUuid: String): List<String> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return goalDao.linksOf(goalUuid, userId).map { it.taskUuid }
    }

    /** The aim's own history — what the target was, in the order the user changed it. */
    suspend fun valueChangesOf(goalUuid: String): List<GoalValueChange> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return goalDao.valueChangesOf(goalUuid, userId)
    }

    /** The open goals that name [taskUuid], for the task's own screen. */
    suspend fun goalsNaming(taskUuid: String): List<Goal> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return goalDao.goalsForTask(taskUuid, userId)
    }

    /**
     * The goals the screen lists, each with the figure it reaches for.
     *
     * The list and the figures come apart on purpose: [GoalDao.observeOpenGoals] is the row list, and
     * each goal's number is its own aggregate Flow. That is what makes the recomputation §5 asks for
     * real rather than scheduled. A single signal for the whole screen could not do it — every goal has
     * its own window, so a completion inside one goal's dates is not necessarily inside another's, and
     * one cached "something changed" tick would have to reload every figure on the screen to be safe.
     * Asked per goal, Room's invalidation tracker re-runs exactly the aggregate the write touched,
     * including the statements that count tombstones, so a task un-completed or thrown away takes its
     * share of the goal with it in the same frame.
     *
     * The read is re-anchored on [WorkTicker.localDays] rather than on a timer, because "is it late yet"
     * is the one part of a goal that moves without a write, and midnight is when the answer changes.
     */
    fun observeOpenGoals(zone: ZoneId = CalendarMath.systemZone()): Flow<List<GoalWithProgress>> =
        observeGoals(open = true, zone = zone)

    /** The archived goals, with the figures they retired at. */
    fun observeArchivedGoals(zone: ZoneId = CalendarMath.systemZone()): Flow<List<GoalWithProgress>> =
        observeGoals(open = false, zone = zone)

    /**
     * What one goal has reached, read now.
     *
     * The four types differ only in which existing aggregate answers them, which is the whole reason they
     * are one table: a count goal and a time goal are the same row with a different question asked of the
     * database, and a screen that had to know which column held its progress would be a screen that could
     * be wrong about a task it never re-read.
     */
    suspend fun progressOf(
        goal: Goal,
        onDay: LocalDate = LocalDate.now(),
        zone: ZoneId = CalendarMath.systemZone()
    ): GoalProgress {
        val userId = sessionRepository.currentUserId()
            // With no profile there is no set of rows to count, and an all-zero figure is the honest
            // answer; inventing one would put a number on a screen that has no account behind it.
            ?: return GoalProgress(goal, counted = 0L, target = goal.targetValue)
        return when (goal.type) {
            GoalType.TASK_COUNT -> {
                val (from, to) = goal.instantBounds(zone)
                progressOf(
                    goal,
                    counted = statisticsDao.completedTasksInPeriod(userId, from, to).toLong(),
                    set = null,
                    onDay = onDay,
                    zone = zone
                )
            }

            GoalType.TIME_SPENT -> {
                val (from, to) = goal.dayBounds()
                progressOf(
                    goal,
                    counted = statisticsDao.periodSummary(userId, from, to).seconds,
                    set = null,
                    onDay = onDay,
                    zone = zone
                )
            }

            GoalType.TASK_SET, GoalType.DEADLINE -> {
                val set = goalDao.setProgress(goal.uuid, userId)
                progressOf(goal, counted = set.completed.toLong(), set = set, onDay = onDay, zone = zone)
            }
        }
    }

    /** The same figure as a Flow that re-runs when the work under this goal moves. */
    fun observeProgress(
        goal: Goal,
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<GoalProgress> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        if (userId == null) {
            flowOf(GoalProgress(goal, counted = 0L, target = goal.targetValue))
        } else {
            ticker.localDays(zone).flatMapLatest { day -> progressFlow(goal, userId, day, zone) }
        }
    }

    /** The goal's own set, as the detail screen lists it. */
    fun observeTasksOf(goalUuid: String): Flow<List<GoalTaskRow>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            val userId = user?.id
            if (userId == null) flowOf(emptyList()) else goalDao.observeTaskRows(goalUuid, userId)
        }

    private fun observeGoals(
        open: Boolean,
        zone: ZoneId
    ): Flow<List<GoalWithProgress>> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        if (userId == null) flowOf(emptyList()) else {
            val rows = if (open) goalDao.observeOpenGoals(userId) else goalDao.observeArchivedGoals(userId)
            rows.flatMapLatest { goals ->
                if (goals.isEmpty()) {
                    // `combine` over no flows never emits, and a screen with zero goals still has to be
                    // told "zero", not left waiting on a list that will never arrive.
                    flowOf(emptyList())
                } else {
                    ticker.localDays(zone).flatMapLatest { day ->
                        // One aggregate Flow per goal rather than one for the screen: every goal has its own
                        // window, so a write only has to re-run the figures whose dates it falls inside.
                        val perGoal = goals.map { progressFlow(it, userId, day, zone) }.toTypedArray()
                        // `combine` waits for every source before its first emission, which is exactly what
                        // a list of cards wants — no half-drawn screen — and its array comes back in the
                        // order the flows were given, so the figures cannot arrive shuffled.
                        combine(*perGoal) { rows ->
                            rows.map { GoalWithProgress(it.goal, it) }
                        }
                    }
                }
            }
        }
    }

    /**
     * One goal's figure as a Flow, from the aggregate its type asks.
     *
     * These are the same statements [progressOf] runs, in the reactive shape the DAO already offers for
     * them: one source per type, so a set goal is woken by its links and a time goal by the intervals, and
     * neither has to care what the other one changed.
     */
    private fun progressFlow(
        goal: Goal,
        userId: Long,
        onDay: LocalDate,
        zone: ZoneId
    ): Flow<GoalProgress> = when (goal.type) {
        GoalType.TASK_COUNT -> {
            val (from, to) = goal.instantBounds(zone)
            statisticsDao.observeCompletedTasksInPeriod(userId, from, to)
                .map { progressOf(goal, it.toLong(), null, onDay, zone) }
        }

        GoalType.TIME_SPENT -> {
            val (from, to) = goal.dayBounds()
            statisticsDao.observePeriodSummary(userId, from, to)
                .map { progressOf(goal, it.seconds, null, onDay, zone) }
        }

        GoalType.TASK_SET, GoalType.DEADLINE -> goalDao.observeSetProgress(goal.uuid, userId)
            .map { progressOf(goal, it.completed.toLong(), it, onDay, zone) }
    }

    /**
     * The goal's own set, as the screen draws it: the figures the links produced, plus whether the date
     * has passed.
     *
     * [set] is null for the two profile-wide types, which ask nothing of the links — their denominator is
     * only ever the figure the user typed.
     */
    private fun progressOf(
        goal: Goal,
        counted: Long,
        set: GoalSetProgress?,
        onDay: LocalDate,
        zone: ZoneId
    ): GoalProgress {
        val target = if (set != null && goal.targetValue <= 0L) set.linked.toLong() else goal.targetValue
        return GoalProgress(
            goal = goal,
            counted = counted,
            target = target,
            linked = set?.linked ?: 0,
            completedLinks = set?.completed ?: 0,
            missing = set?.missing ?: 0,
            late = isLate(goal, target, counted, onDay, zone)
        )
    }

    /**
     * Whether the date the goal was set for has passed with the aim still unmet.
     *
     * The comparison is on days, not instants: "due Friday" is not late on Friday, whatever hour of
     * Friday it is, and the day boundary is the one the [onDay] the read is anchored on already knows.
     * A goal that reached its figure stops being late by itself — reaching it after the date reads as
     * finished work, which is what the history will say happened, not as something still outstanding.
     */
    private fun isLate(
        goal: Goal,
        target: Long,
        counted: Long,
        onDay: LocalDate,
        zone: ZoneId
    ): Boolean {
        if (target > 0L && counted >= target) return false
        val deadline = goal.deadlineAt ?: return false
        return WorkTimeMath.epochDayOf(deadline, zone) < onDay.toEpochDay()
    }

    /**
     * The goal [uuid] names, when this profile may change it, and null when it may not.
     *
     * A uuid is not a permission: after an import two accounts can hold the same one, and the screen that
     * happens to be showing it has no business editing the other's aim. A tombstone is included in the
     * refusal for the same reason — the user is not looking at a goal they can still change.
     */
    private suspend fun ownGoal(uuid: String, userId: Long): Goal? =
        goalDao.getGoal(uuid, userId)?.takeIf { !it.isDeleted }

    private suspend fun transition(
        goalUuid: String,
        block: suspend (uuid: String, userId: Long, now: Long) -> Unit
    ): GoalWriteResult {
        val userId = sessionRepository.currentUserId()
            ?: return GoalWriteResult.Rejected(GoalRejection.NO_PROFILE)
        ownGoal(goalUuid, userId)
            ?: return GoalWriteResult.Rejected(GoalRejection.NOT_FOUND)
        database.withTransaction { block(goalUuid, userId, timeBase.wallMillis()) }
        return GoalWriteResult.Saved(goalUuid)
    }

    /**
     * The rules every write passes through, in the order the screen can show them.
     *
     * They are checked here rather than left to the database because each one has a sentence attached to
     * it: a goal cannot be saved with no title, and the editor has to say which of its four fields is the
     * problem. SQLite could only refuse the whole row.
     */
    private fun validate(
        title: String,
        targetValue: Long,
        windowFromDay: Long?,
        windowToDay: Long?,
        type: GoalType,
        deadlineAt: Long?
    ): GoalRejection? = when {
        title.isBlank() -> GoalRejection.BLANK_TITLE
        targetValue < 0L -> GoalRejection.NEGATIVE_TARGET
        windowFromDay != null && windowToDay != null && windowToDay < windowFromDay ->
            GoalRejection.WINDOW_ENDS_BEFORE_START
        type == GoalType.DEADLINE && deadlineAt == null -> GoalRejection.DEADLINE_WITHOUT_A_DATE
        else -> null
    }

    /**
     * The one spelling a window keeps in history: the two dates it covers, or nothing at all when the
     * goal has no window.
     *
     * Days are written as calendar dates rather than the epoch-day numbers the column holds, for the same
     * reason [dateText] gives below — a history row is read by a person, and an open end says so instead
     * of leaving the row to be decoded.
     */
    private fun windowOf(fromDay: Long?, toDay: Long?): String? =
        if (fromDay == null && toDay == null) null
        else "${dayText(fromDay)}..${dayText(toDay)}"

    private fun dayText(day: Long?): String = day?.let { WorkTimeMath.toDate(it).toString() } ?: OPEN_END

    /**
     * The date a deadline reads as, in history.
     *
     * The stored value is an instant and the screen prints a day, so the history row carries the day: a
     * record that said "1735689600000 → 1743436800000" would need the very column that changed decoded
     * before anyone could read it. The comparison that decides whether to write a row at all is still on
     * the instants, so a move within one day is reported as what it is.
     */
    private fun dateText(deadlineAt: Long?, zone: ZoneId): String? =
        deadlineAt?.let { WorkTimeMath.toDate(WorkTimeMath.epochDayOf(it, zone)).toString() }

    /**
     * One history row when a value moved, and no row when it did not.
     *
     * The pair is compared by value and stored as the text the screen prints, because the field that
     * changed is not always a number: a window is a pair of days, a deadline is a date, and a title is a
     * title. [values] is only called for the field's own pair, so an editor that changed three things
     * still writes exactly three rows.
     */
    private fun changeIf(
        userId: Long,
        goalUuid: String,
        field: String,
        now: Long,
        reason: String?,
        values: () -> Pair<Any?, Any?>
    ): GoalValueChange? {
        val (old, new) = values()
        if (old == new) return null
        return GoalValueChange(
            uuid = UUID.randomUUID().toString(),
            goalUuid = goalUuid,
            userId = userId,
            field = field,
            oldValue = old?.toString(),
            newValue = new?.toString(),
            reason = reason,
            syncState = SyncState.PENDING,
            createdAt = now,
            updatedAt = now
        )
    }

    companion object {
        /** How a window that is open at one end is written in history, so the row says "none" out loud. */
        private const val OPEN_END = "open"
    }
}

/**
 * The window as the half-open epoch-day pair the interval totals are filtered on.
 *
 * `periodSummary` reads `[from, to)` over `local_day`, so the goal's inclusive end day is passed as the
 * day after it; forgetting the `+1` would leave every time goal one day short of the window it names. An
 * open end stays open by reaching past any day a row can carry.
 */
private fun Goal.dayBounds(): Pair<Long, Long> =
    (windowFromDay ?: Long.MIN_VALUE) to (windowToDay?.plus(1L) ?: Long.MAX_VALUE)

/** The same window as the half-open instant pair the completion column is filtered by. */
private fun Goal.instantBounds(zone: ZoneId): Pair<Long, Long> = Pair(
    windowFromDay?.let { CalendarMath.startOfDay(LocalDate.ofEpochDay(it), zone) } ?: Long.MIN_VALUE,
    windowToDay?.let { CalendarMath.startOfDay(LocalDate.ofEpochDay(it + 1L), zone) } ?: Long.MAX_VALUE
)
