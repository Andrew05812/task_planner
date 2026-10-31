package com.taskplanner.data.sync

import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.taskplanner.data.auth.CloudConfig
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.repository.ReminderRepository
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
    /**
     * The machine's complaint, kept apart from the reader's sentence about it.
     *
     * Nullable because most sync failures arrive as a Firebase exception whose message no reader could act on
     * anyway. The settings line then says the one sentence the app owns — in the reader's language — and the
     * detail stays with the log, where it can be looked up. The old shape wrote `"Sync failed"` into a Russian
     * sentence as its interpolation argument, which is the half-translated screen this stage exists to close.
     */
    data class Failed(val detail: String? = null) : SyncStatus()
}

/**
 * Offline-capable, queue-based sync.
 *
 * Room stays authoritative for the UI; Firestore mirrors it. Every entity carries a stable
 * uuid, a sync_state and a tombstone, so:
 *  - a push is idempotent (document id = uuid) and safe to retry after any failure;
 *  - a delete propagates instead of resurrecting;
 *  - conflicts follow the single documented rule in [SyncAppliers], not blind last-write-wins.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val firestore: Lazy<FirebaseFirestore>,
    private val sessionRepository: SessionRepository,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val groupDao: GroupDao,
    private val recurrenceDao: RecurrenceDao,
    private val reminderDao: ReminderDao,
    private val appliers: SyncAppliers,
    private val recurrenceRepository: RecurrenceRepository,
    private val reminderRepository: ReminderRepository,
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
                // Another device may have completed or deleted the instance this series points at,
                // so the pull leaves the local series without an open task until it is repaired.
                recurrenceRepository.ensureOpenInstances()
                // A remote edit can move a deadline, finish a task or delete one, and none of that can
                // leave an alarm pointed at the minute the other device used. Reminders are rebuilt
                // from the rows that are now on disk, and the ones whose minute arrived while the
                // sync was away are delivered here.
                reminderRepository.rescheduleAll()
                val finished = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_SYNC_AT + uid, finished).apply()
                SyncStatus.Done(pushed, pulled, finished).also { _status.value = it }
            } catch (e: Exception) {
                // Nothing is lost on failure: rows keep sync_state PENDING/DELETING and the
                // watermark only advances over documents that were actually applied.
                Log.w(TAG, "a sync pass failed", e)
                SyncStatus.Failed(detail = e.message).also { _status.value = it }
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

        // A series and its lead times are part of what the user set up, so they are queued like any
        // other row: a repeating pattern that never reaches the cloud would start a second,
        // independent one on the next device.
        recurrenceDao.getAllRowsOnce(userId)
            .filter { it.syncState != SyncState.SYNCED }
            .forEach { rule ->
                recurrenceDao.setSyncState(rule.uuid, SyncState.SYNCED.name)
                store.pushRule(rule)
                pushed++
            }

        reminderDao.getAllRowsOnce(userId)
            .filter { it.syncState != SyncState.SYNCED }
            .forEach { reminder ->
                reminderDao.setSyncState(reminder.uuid, SyncState.SYNCED.name)
                store.pushReminder(reminder)
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
            if (appliers.applyRemoteCategory(userId, remote)) applied++
        }
        store.pullGroupsSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (appliers.applyRemoteGroup(userId, remote)) applied++
        }
        // Before the tasks: an instance of a series has to find its rule on disk to be drawn as a
        // repeating task rather than as an ordinary one.
        store.pullRulesSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (appliers.applyRemoteRule(userId, remote)) applied++
        }
        val categoryUuidById = categoryDao.getAllRowsOnce(userId).associate { it.uuid to it.id }
        store.pullTasksSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (appliers.applyRemoteTask(userId, remote, categoryUuidById)) applied++
        }
        store.pullRemindersSince(since).forEach { remote ->
            maxSeen = maxOf(maxSeen, remote.updatedAt)
            if (appliers.applyRemoteReminder(userId, remote)) applied++
        }

        if (maxSeen > since) prefs.edit().putLong(KEY_WATERMARK + uid, maxSeen).apply()
        return applied
    }

    companion object {
        private const val TAG = "SyncRepository"
        private const val KEY_WATERMARK = "sync_watermark_"
        private const val KEY_LAST_SYNC_AT = "sync_last_at_"
    }
}
