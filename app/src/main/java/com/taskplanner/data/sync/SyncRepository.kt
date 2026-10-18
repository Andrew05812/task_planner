package com.taskplanner.data.sync

import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.taskplanner.data.auth.CloudConfig
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.repository.SessionRepository
import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncStatus {
    /** Firebase is not configured in this build: cloud work is skipped, local mode is full. */
    object CloudDisabled : SyncStatus()

    /** The active profile has no Firebase uid yet (local-only profile). */
    object NotLinked : SyncStatus()

    object Idle : SyncStatus()
    object Running : SyncStatus()
    data class Done(val pushed: Int, val pulled: Int, val finishedAt: Long) : SyncStatus()
    data class Failed(val message: String) : SyncStatus()
}

/**
 * Offline-capable, queue-based sync.
 *
 * Room stays authoritative for the UI; Firestore mirrors it. Every entity carries a stable
 * uuid, a sync_state and a tombstone, so:
 *  - a push is idempotent (document id = uuid) and safe to retry after any failure;
 *  - a delete propagates instead of resurrecting;
 *  - conflicts follow the single documented rule in [applyRemoteTask], not blind last-write-wins.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val firestore: Lazy<FirebaseFirestore>,
    private val sessionRepository: SessionRepository,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val groupDao: GroupDao,
    private val prefs: SharedPreferences
) {
    private val syncMutex = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(
        if (CloudConfig.isConfigured) SyncStatus.Idle else SyncStatus.CloudDisabled
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Serializes runs so a worker retry can never overlap the previous attempt. */
    suspend fun syncNow(): SyncStatus {
        if (!CloudConfig.isConfigured) return SyncStatus.CloudDisabled.also { _status.value = it }
        val user = sessionRepository.resolveUser()
        val uid = user?.firebaseUid
        if (user == null || uid.isNullOrBlank()) {
            return SyncStatus.NotLinked.also { _status.value = it }
        }
        return syncMutex.withLock {
            _status.value = SyncStatus.Running
            try {
                val store = FirestoreSyncStore(firestore.get(), uid)
                val pushed = pushPending(user.id, store)
                val pulled = pullInto(user.id, uid, store)
                val finished = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_SYNC_AT + uid, finished).apply()
                SyncStatus.Done(pushed, pulled, finished).also { _status.value = it }
            } catch (e: Exception) {
                // Nothing is lost on failure: rows keep sync_state PENDING/DELETING and the
                // watermark only advances over documents that were actually applied.
                SyncStatus.Failed(e.message ?: "Sync failed").also { _status.value = it }
            }
        }
    }

    fun lastSyncAt(): Long =
        sessionRepository.currentUser.value?.firebaseUid
            ?.let { prefs.getLong(KEY_LAST_SYNC_AT + it, 0L) } ?: 0L

    private suspend fun pushPending(userId: Long, store: FirestoreSyncStore): Int {
        var pushed = 0
        val categories = categoryDao.getAllRowsOnce(userId)
        val categoryUuidById = categories.associate { it.id to it.uuid }

        categories.filter { it.syncState != SyncState.SYNCED }.forEach { category ->
            val uuid = ensureUuid(category.uuid) { categoryDao.setUuid(category.id, it) }
            categoryDao.setSyncState(uuid, SyncState.SYNCED.name)
            store.pushCategory(
                category.copy(uuid = uuid),
                category.parentCategoryId?.let { categoryUuidById[it] }
            )
            pushed++
        }

        groupDao.getAllGroupsOnce(userId)
            .filter { it.syncState != SyncState.SYNCED }
            .forEach { group ->
                groupDao.setSyncState(group.uuid, SyncState.SYNCED.name)
                store.pushGroup(group)
                pushed++
            }

        taskDao.getAllRowsOnce(userId)
            .filter { it.syncState != SyncState.SYNCED }
            .forEach { task ->
                val uuid = ensureUuid(task.uuid) { taskDao.setUuid(task.id, it) }
                taskDao.setSyncState(uuid, SyncState.SYNCED.name)
                store.pushTask(
                    task.copy(uuid = uuid),
                    task.categoryId?.let { categoryUuidById[it] }
                )
                pushed++
            }
        return pushed
    }

    /**
     * Rows written before uuid existed (or imported from an older backup) get their stable
     * identity here, once, and it is persisted immediately — so a retry reuses the same id.
     */
    private suspend fun ensureUuid(current: String, persist: suspend (String) -> Unit): String {
        if (current.isNotBlank()) return current
        val generated = UUID.randomUUID().toString()
        persist(generated)
        return generated
    }

    private suspend fun pullInto(userId: Long, uid: String, store: FirestoreSyncStore): Int {
        val since = prefs.getLong(KEY_WATERMARK + uid, 0L)
        var applied = 0
        var maxSeen = since

        store.pullCategoriesSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (applyRemoteCategory(userId, remote)) applied++
        }
        store.pullGroupsSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (applyRemoteGroup(userId, remote)) applied++
        }
        val categoryUuidById = categoryDao.getAllRowsOnce(userId).associate { it.uuid to it.id }
        store.pullTasksSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (applyRemoteTask(userId, remote, categoryUuidById)) applied++
        }

        if (maxSeen > since) prefs.edit().putLong(KEY_WATERMARK + uid, maxSeen).apply()
        return applied
    }

    /**
     * Conflict rule, identical for all three entity types:
     *  1. a local row with unsent changes (sync_state != SYNCED) always wins — an offline edit
     *     must never be silently dropped in favour of a cloud snapshot;
     *  2. otherwise the newer updated_at wins, deletions included (the tombstone carries it);
     *  3. a remote update of a locally deleted row is ignored, so a delete never resurrects.
     */
    private suspend fun applyRemoteTask(
        userId: Long,
        remote: SyncedTask,
        categoryUuidById: Map<String, Long>
    ): Boolean {
        val local = taskDao.findTaskByUuid(remote.uuid)
        if (local == null) {
            taskDao.insertTask(
                Task(
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
                    updatedAt = remote.updatedAt
                )
            )
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
                syncState = SyncState.SYNCED,
                updatedAt = remote.updatedAt
            )
        )
        return true
    }

    private suspend fun applyRemoteCategory(userId: Long, remote: SyncedCategory): Boolean {
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
        categoryDao.insertCategory(
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

    private suspend fun applyRemoteGroup(userId: Long, remote: SyncedGroup): Boolean {
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

    companion object {
        private const val KEY_WATERMARK = "sync_watermark_"
        private const val KEY_LAST_SYNC_AT = "sync_last_at_"
    }
}
