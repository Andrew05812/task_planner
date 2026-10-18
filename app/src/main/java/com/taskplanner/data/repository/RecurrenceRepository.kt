package com.taskplanner.data.repository

import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.recurrence.RecurrenceCalculator
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Recurring tasks, without ever storing a task per day for the next decade.
 *
 * A series keeps exactly one open instance in `tasks`: the row its cursor points at. When that
 * instance is resolved — completed, archived, deleted — the cursor moves to the next instant the
 * calculator produces and the new instance is built from the content of the previous one. So a
 * series that has been running for a year is one rule row plus one task row, and catching up after
 * the app was closed for a week is a bounded walk instead of a backlog of rows.
 *
 * Room stays authoritative: every mutation leaves the row PENDING for sync, and nothing depends on
 * the local row id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecurrenceRepository @Inject constructor(
    private val recurrenceDao: RecurrenceDao,
    private val taskDao: TaskDao,
    private val sessionRepository: SessionRepository
) {

    private companion object {
        /** How many resolved instances one refresh may walk past; a series never runs away. */
        const val MAX_CATCH_UP = 500
    }

    fun observeRules(): Flow<List<RecurrenceRule>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else recurrenceDao.observeRules(user.id)
        }

    suspend fun ruleFor(task: Task): RecurrenceRule? =
        task.seriesUuid?.let { recurrenceDao.findByUuid(it) }

    /** The instance the series exposes right now, if this task belongs to it. */
    suspend fun liveInstance(rule: RecurrenceRule): Task? =
        taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)

    /**
     * Turns the already persisted [task] into the first instance of a new series.
     *
     * The task keeps its own uuid — it is a row sync may already know — and only the instances the
     * series produces later get calculator-derived ids.
     */
    suspend fun startSeries(
        task: Task,
        unit: RepeatUnit,
        interval: Int = 1,
        weekDays: List<Int> = emptyList(),
        dayOfMonth: Int = 0
    ): RecurrenceRule? {
        val userId = sessionRepository.currentUserId() ?: return null
        if (task.id == 0L) return null
        val now = System.currentTimeMillis()
        val anchor = task.dueDate ?: now
        val rule = RecurrenceRule(
            userId = userId,
            unit = unit,
            interval = interval.coerceAtLeast(1),
            weekDays = weekDays.joinToString(","),
            dayOfMonth = dayOfMonth,
            anchorAt = anchor,
            zoneId = ZoneId.systemDefault().id,
            cursorAt = anchor,
            createdAt = now,
            updatedAt = now
        )
        recurrenceDao.upsertRule(rule)
        taskDao.updateTask(
            task.copy(
                seriesUuid = rule.uuid,
                occurrenceAt = anchor,
                updatedAt = now,
                syncState = SyncState.PENDING
            )
        )
        return rule
    }

    /**
     * Changes the pattern for the live instance and everything after it.
     *
     * The schedule is re-anchored at [rebaseAt] — the instant the live instance carries unless the
     * caller says otherwise — so "every Monday from now on" starts from what the user is looking at
     * rather than from the day the series was created. Returns null when another instance of the
     * series already occupies that instant.
     */
    suspend fun updateThisAndFuture(
        seriesUuid: String,
        unit: RepeatUnit,
        interval: Int = 1,
        weekDays: List<Int> = emptyList(),
        dayOfMonth: Int = 0,
        rebaseAt: Long? = null
    ): RecurrenceRule? {
        val rule = recurrenceDao.findByUuid(seriesUuid) ?: return null
        val live = taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)
        val rebase = rebaseAt ?: rule.cursorAt
        if (rebase != rule.cursorAt) {
            val occupant = taskDao.findSeriesInstance(rule.uuid, rebase)
            if (occupant != null && occupant.id != live?.id) return null
        }
        val now = System.currentTimeMillis()
        // A paused or finished series stays paused: changing its pattern is not a request to start
        // producing instances again.
        val updated = rule.copy(
            unit = unit,
            interval = interval.coerceAtLeast(1),
            weekDays = weekDays.joinToString(","),
            dayOfMonth = dayOfMonth,
            anchorAt = rebase,
            cursorAt = rebase,
            updatedAt = now,
            syncState = SyncState.PENDING
        )
        recurrenceDao.upsertRule(updated)
        if (live != null && rebase != rule.cursorAt) {
            taskDao.setInstanceOccurrence(live.id, rebase, now)
        }
        return updated
    }

    /**
     * Pauses or resumes a series.
     *
     * Resuming is the moment the series has to catch up: it owed a task the whole time it was
     * stopped, and the user should not have to finish anything to get it back.
     */
    suspend fun setPaused(seriesUuid: String, paused: Boolean): Boolean {
        val changed = mutate(seriesUuid) { it.copy(paused = paused) }
        if (changed && !paused) ensureOpenInstances()
        return changed
    }

    /** Ends the series: no further instances, the open one stays as a normal task. */
    suspend fun finish(seriesUuid: String): Boolean =
        mutate(seriesUuid) { it.copy(endedAt = System.currentTimeMillis()) }

    /**
     * Deletes the series together with the instances it still owes the user. Archived history is
     * kept — it records work that was actually done.
     */
    suspend fun deleteSeries(seriesUuid: String): Boolean {
        val rule = recurrenceDao.findByUuid(seriesUuid) ?: return false
        val now = System.currentTimeMillis()
        val open = taskDao.getOpenSeriesInstances(seriesUuid)
        if (open.isNotEmpty()) taskDao.softDeleteTasks(open.map { it.id }, now)
        recurrenceDao.upsertRule(
            rule.copy(isDeleted = true, deletedAt = now, updatedAt = now, syncState = SyncState.DELETING)
        )
        return true
    }

    /**
     * Makes every open series of the current profile own an instance again.
     *
     * The idempotent repair step: safe to call on start-up, after a pull, after a restart, or after
     * anything that may have resolved an instance, because it only creates a row when the series has
     * none and only moves the cursor over instances that are provably finished with.
     */
    suspend fun ensureOpenInstances(now: Long = System.currentTimeMillis()): Int {
        val userId = sessionRepository.currentUserId() ?: return 0
        var created = 0
        recurrenceDao.getOpenRules(userId).forEach { rule ->
            created += refreshRule(rule, now)
        }
        return created
    }

    private suspend fun refreshRule(rule: RecurrenceRule, now: Long): Int {
        var current = rule
        repeat(MAX_CATCH_UP) {
            val live = taskDao.findSeriesInstance(current.uuid, current.cursorAt)
            when {
                live == null -> return if (materialize(current, current.cursorAt, now) != null) 1 else 0
                !live.isResolvedInstance() -> return 0
            }
            val next = RecurrenceCalculator.nextOccurrence(current, current.cursorAt) ?: return 0
            current = current.copy(cursorAt = next, updatedAt = now, syncState = SyncState.PENDING)
            recurrenceDao.upsertRule(current)
        }
        return 0
    }

    private suspend fun materialize(rule: RecurrenceRule, occurrenceAt: Long, now: Long): Task? {
        val template = taskDao.getNewestSeriesInstance(rule.uuid) ?: return null
        val instance = template.copy(
            id = 0,
            uuid = RecurrenceCalculator.occurrenceUuid(rule.uuid, occurrenceAt),
            dueDate = occurrenceAt,
            status = TaskStatus.TODO,
            archived = false,
            archivedAt = null,
            startedAt = null,
            isDeleted = false,
            deletedAt = null,
            syncState = SyncState.PENDING,
            createdAt = now,
            updatedAt = now,
            seriesUuid = rule.uuid,
            occurrenceAt = occurrenceAt
        )
        // Below zero means the unique (series, occurrence) index already holds this instance: a
        // retry, a restart, or another device that produced the same occurrence first. Nothing to do.
        if (taskDao.insertSeriesInstance(instance) < 0) return null
        return instance
    }

    private suspend fun mutate(
        seriesUuid: String,
        change: (RecurrenceRule) -> RecurrenceRule
    ): Boolean {
        val rule = recurrenceDao.findByUuid(seriesUuid) ?: return false
        val now = System.currentTimeMillis()
        recurrenceDao.upsertRule(change(rule).copy(updatedAt = now, syncState = SyncState.PENDING))
        return true
    }

    /** An instance the user is done with, so it no longer counts as the series' open one. */
    private fun Task.isResolvedInstance(): Boolean =
        isDeleted || archived || status == TaskStatus.COMPLETED
}
