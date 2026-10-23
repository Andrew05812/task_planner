package com.taskplanner.data.sync

import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a document that came out of the cloud becomes a local row.
 *
 * This is the half of sync that has to be right on the device: the network either delivers a
 * document or it does not, but once one has arrived the rules below decide whether the user's own
 * edit survives it. They are kept out of [SyncRepository] so they can be tested against a real Room
 * database without a Firebase project, which this repository does not have and must never ship.
 *
 * Conflict rule, identical for all five entity types:
 *  1. a local row with unsent changes (sync_state != SYNCED) always wins — an offline edit must
 *     never be silently dropped in favour of a cloud snapshot;
 *  2. otherwise the newer updated_at wins, deletions included (the tombstone carries it);
 *  3. a remote update of a locally deleted row is ignored, so a delete never resurrects.
 */
@Singleton
class SyncAppliers @Inject constructor(
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val groupDao: GroupDao,
    private val recurrenceDao: RecurrenceDao,
    private val reminderDao: ReminderDao
) {

    suspend fun applyRemoteTask(
        userId: Long,
        remote: SyncedTask,
        categoryUuidById: Map<String, Long>
    ): Boolean {
        val local = taskDao.findTaskByUuid(remote.uuid)
        if (local == null) {
            val incoming = Task(
                uuid = remote.uuid,
                title = remote.title,
                description = remote.description,
                dueDate = remote.dueDate,
                priority = remote.priority,
                status = remote.status,
                categoryId = remote.categoryUuid?.let { categoryUuidById[it] },
                groupId = remote.groupUuid,
                archived = remote.archived,
                archivedAt = remote.archivedAt,
                startedAt = remote.startedAt,
                userId = userId,
                order = remote.order,
                isDeleted = remote.isDeleted,
                deletedAt = remote.deletedAt,
                syncState = SyncState.SYNCED,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
                startTime = remote.startTime,
                endTime = remote.endTime,
                estimatedMinutes = remote.estimatedMinutes,
                seriesUuid = remote.seriesUuid,
                occurrenceAt = remote.occurrenceAt
            )
            // An instance of a series that this device already produced keeps its own row: the user
            // may have edited or finished it, and the (series, occurrence) pair is the same task.
            // Nothing was written in that case, so the document is not counted as applied.
            if (remote.seriesUuid != null && remote.occurrenceAt != null) {
                return taskDao.insertSeriesInstance(incoming) >= 0
            }
            taskDao.insertTask(incoming)
            return true
        }
        if (local.syncState != SyncState.SYNCED) return false
        if (remote.updatedAt <= local.updatedAt) return false
        if (remote.isDeleted && !local.isDeleted) {
            taskDao.softDeleteTasks(listOf(local.id))
            taskDao.setSyncState(local.uuid, SyncState.SYNCED.name)
            return true
        }
        if (!remote.isDeleted && local.isDeleted) return false
        taskDao.insertTask(
            local.copy(
                title = remote.title,
                description = remote.description,
                dueDate = remote.dueDate,
                priority = remote.priority,
                status = remote.status,
                categoryId = remote.categoryUuid?.let { categoryUuidById[it] } ?: local.categoryId,
                groupId = remote.groupUuid,
                archived = remote.archived,
                archivedAt = remote.archivedAt,
                startedAt = remote.startedAt,
                order = remote.order,
                startTime = remote.startTime,
                endTime = remote.endTime,
                estimatedMinutes = remote.estimatedMinutes,
                seriesUuid = remote.seriesUuid,
                occurrenceAt = remote.occurrenceAt,
                syncState = SyncState.SYNCED,
                updatedAt = remote.updatedAt
            )
        )
        return true
    }

    suspend fun applyRemoteCategory(userId: Long, remote: SyncedCategory): Boolean {
        val local = categoryDao.getCategoryByUuid(remote.uuid)
        if (local == null) {
            categoryDao.insertCategory(
                Category(
                    uuid = remote.uuid,
                    name = remote.name,
                    color = remote.color,
                    icon = remote.icon,
                    userId = userId,
                    isDeleted = remote.isDeleted,
                    deletedAt = remote.deletedAt,
                    syncState = SyncState.SYNCED,
                    createdAt = remote.createdAt,
                    updatedAt = remote.updatedAt
                )
            )
            return true
        }
        if (local.syncState != SyncState.SYNCED) return false
        if (remote.updatedAt <= local.updatedAt) return false
        if (remote.isDeleted) {
            categoryDao.softDelete(local.id)
            categoryDao.setSyncState(local.uuid, SyncState.SYNCED.name)
            return true
        }
        if (local.isDeleted) return false
        // An update: the row already exists, and re-inserting it would delete it first, which `tasks`
        // answers by setting their category to null.
        categoryDao.updateCategory(
            local.copy(
                name = remote.name,
                color = remote.color,
                icon = remote.icon,
                syncState = SyncState.SYNCED,
                updatedAt = remote.updatedAt
            )
        )
        return true
    }

    suspend fun applyRemoteGroup(userId: Long, remote: SyncedGroup): Boolean {
        val local = groupDao.getGroupByUuid(remote.uuid)
        if (local == null) {
            groupDao.upsertGroup(
                TaskGroup(
                    uuid = remote.uuid,
                    userId = userId,
                    name = remote.name,
                    collapsed = remote.collapsed,
                    sortOrder = remote.sortOrder,
                    isDeleted = remote.isDeleted,
                    deletedAt = remote.deletedAt,
                    syncState = SyncState.SYNCED,
                    createdAt = remote.createdAt,
                    updatedAt = remote.updatedAt
                )
            )
            return true
        }
        if (local.syncState != SyncState.SYNCED) return false
        if (remote.updatedAt <= local.updatedAt) return false
        groupDao.upsertGroup(
            local.copy(
                name = remote.name,
                collapsed = remote.collapsed,
                sortOrder = remote.sortOrder,
                isDeleted = remote.isDeleted,
                deletedAt = remote.deletedAt,
                syncState = SyncState.SYNCED,
                updatedAt = remote.updatedAt
            )
        )
        return true
    }

    /**
     * A series pulled from another device arrives with the cursor that device advanced it to, which
     * is what stops the pattern restarting from its anchor and handing back occurrences the user
     * already dealt with.
     */
    suspend fun applyRemoteRule(userId: Long, remote: SyncedRule): Boolean {
        val local = recurrenceDao.findByUuid(remote.uuid)
        if (local == null) {
            recurrenceDao.upsertRule(remote.toRule(userId).copy(syncState = SyncState.SYNCED))
            return true
        }
        if (local.syncState != SyncState.SYNCED) return false
        if (remote.updatedAt <= local.updatedAt) return false
        if (remote.isDeleted && !local.isDeleted) {
            recurrenceDao.upsertRule(
                local.copy(
                    isDeleted = true,
                    deletedAt = remote.deletedAt,
                    syncState = SyncState.SYNCED,
                    updatedAt = remote.updatedAt
                )
            )
            return true
        }
        if (!remote.isDeleted && local.isDeleted) return false
        recurrenceDao.upsertRule(
            remote.toRule(userId).copy(
                syncState = SyncState.SYNCED,
                updatedAt = remote.updatedAt
            )
        )
        return true
    }

    /**
     * The lead time is matched by (task, offset) rather than by uuid, because that pair is what the
     * unique index and [Reminder.identity] both make the reminder's identity: a document that renamed
     * it still describes the same warning, and a second row for the same minute is what the index is
     * there to prevent.
     */
    suspend fun applyRemoteReminder(userId: Long, remote: SyncedReminder): Boolean {
        val local = reminderDao.findByTaskAndOffset(remote.taskUuid, remote.offsetMinutes)
        if (local == null) {
            // A row that already owns this uuid belongs to another profile: IGNORE leaves it alone
            // instead of replacing somebody else's reminder.
            reminderDao.insertOrIgnore(remote.toReminder(userId).copy(syncState = SyncState.SYNCED))
            return true
        }
        if (local.userId != userId) return false
        if (local.syncState != SyncState.SYNCED) return false
        if (remote.updatedAt <= local.updatedAt) return false
        if (remote.isDeleted && !local.isDeleted) {
            reminderDao.softDelete(local.uuid, remote.deletedAt ?: System.currentTimeMillis())
            reminderDao.setSyncState(local.uuid, SyncState.SYNCED.name)
            return true
        }
        if (!remote.isDeleted && local.isDeleted) return false
        reminderDao.update(
            local.copy(
                taskUuid = remote.taskUuid,
                offsetMinutes = remote.offsetMinutes,
                triggerAt = remote.triggerAt,
                anchorAt = remote.anchorAt,
                // A warning this device already showed stays shown: otherwise a snapshot from another
                // phone would put the same minute back on the clock and notify the user twice.
                notifiedAt = local.notifiedAt ?: remote.notifiedAt,
                isDeleted = remote.isDeleted,
                deletedAt = remote.deletedAt,
                syncState = SyncState.SYNCED,
                updatedAt = remote.updatedAt
            )
        )
        return true
    }
}
