package com.taskplanner.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import kotlinx.coroutines.tasks.await

/**
 * Firestore access layer. Layout: users/{uid}/tasks|categories|groups/{uuid}.
 * The document id is the entity uuid, so pushing the same entity twice is
 * idempotent and a retry after a network failure cannot create duplicates.
 */
class FirestoreSyncStore(
    private val firestore: FirebaseFirestore,
    private val uid: String
) {
    private fun collection(name: String) = firestore.collection(ROOT).document(uid).collection(name)

    suspend fun pushTask(task: Task, categoryUuid: String?) =
        collection(C_TASKS).document(task.uuid)
            .set(taskToFields(task, categoryUuid), SetOptions.merge())
            .await()

    suspend fun pushCategory(category: Category, parentUuid: String?) =
        collection(C_CATEGORIES).document(category.uuid)
            .set(categoryToFields(category, parentUuid), SetOptions.merge())
            .await()

    suspend fun pushGroup(group: TaskGroup) =
        collection(C_GROUPS).document(group.uuid).set(groupToFields(group), SetOptions.merge()).await()

    suspend fun pullTasksSince(watermark: Long): List<SyncedTask> =
        collection(C_TASKS)
            .whereGreaterThan(F_UPDATED_AT, watermark)
            .orderBy(F_UPDATED_AT, Query.Direction.ASCENDING)
            .limit(PAGE_SIZE)
            .get()
            .await()
            .documents
            .map { it.data.orEmpty() }
            .mapNotNull { fields ->
                val uuid = fields.string(F_UUID) ?: return@mapNotNull null
                SyncedTask(
                    uuid = uuid,
                    title = fields.string(F_TITLE).orEmpty(),
                    description = fields.string(F_DESCRIPTION).orEmpty(),
                    dueDate = fields.longOrNull(F_DUE_DATE),
                    priority = runCatching { Priority.valueOf(fields.string(F_PRIORITY).orEmpty()) }
                        .getOrDefault(Priority.MEDIUM),
                    status = runCatching { TaskStatus.valueOf(fields.string(F_STATUS).orEmpty()) }
                        .getOrDefault(TaskStatus.TODO),
                    categoryUuid = fields.string(F_CATEGORY_UUID),
                    groupUuid = fields.string(F_GROUP_UUID),
                    archived = fields.bool(F_ARCHIVED),
                    archivedAt = fields.longOrNull(F_ARCHIVED_AT),
                    startedAt = fields.longOrNull(F_STARTED_AT),
                    order = fields.longOrNull(F_ORDER)?.toInt() ?: 0,
                    isDeleted = fields.bool(F_IS_DELETED),
                    deletedAt = fields.longOrNull(F_DELETED_AT),
                    createdAt = fields.long(F_CREATED_AT),
                    updatedAt = fields.long(F_UPDATED_AT)
                )
            }

    suspend fun pullCategoriesSince(watermark: Long): List<SyncedCategory> =
        collection(C_CATEGORIES)
            .whereGreaterThan(F_UPDATED_AT, watermark)
            .orderBy(F_UPDATED_AT, Query.Direction.ASCENDING)
            .limit(PAGE_SIZE)
            .get()
            .await()
            .documents
            .map { it.data.orEmpty() }
            .mapNotNull { fields ->
                val uuid = fields.string(F_UUID) ?: return@mapNotNull null
                SyncedCategory(
                    uuid = uuid,
                    name = fields.string(F_NAME).orEmpty(),
                    color = fields.longOrNull(F_COLOR)?.toInt() ?: 0,
                    icon = fields.string(F_ICON).orEmpty(),
                    parentUuid = fields.string(F_PARENT_UUID),
                    isDeleted = fields.bool(F_IS_DELETED),
                    deletedAt = fields.longOrNull(F_DELETED_AT),
                    createdAt = fields.long(F_CREATED_AT),
                    updatedAt = fields.long(F_UPDATED_AT)
                )
            }

    suspend fun pullGroupsSince(watermark: Long): List<SyncedGroup> =
        collection(C_GROUPS)
            .whereGreaterThan(F_UPDATED_AT, watermark)
            .orderBy(F_UPDATED_AT, Query.Direction.ASCENDING)
            .limit(PAGE_SIZE)
            .get()
            .await()
            .documents
            .map { it.data.orEmpty() }
            .mapNotNull { fields ->
                val uuid = fields.string(F_UUID) ?: return@mapNotNull null
                SyncedGroup(
                    uuid = uuid,
                    name = fields.string(F_NAME).orEmpty(),
                    collapsed = fields.bool(F_COLLAPSED),
                    sortOrder = fields.longOrNull(F_SORT_ORDER)?.toInt() ?: 0,
                    isDeleted = fields.bool(F_IS_DELETED),
                    deletedAt = fields.longOrNull(F_DELETED_AT),
                    createdAt = fields.long(F_CREATED_AT),
                    updatedAt = fields.long(F_UPDATED_AT)
                )
            }

    private fun taskToFields(task: Task, categoryUuid: String?): Map<String, Any?> = mapOf(
        F_UUID to task.uuid,
        F_TITLE to task.title,
        F_DESCRIPTION to task.description,
        F_DUE_DATE to task.dueDate,
        F_PRIORITY to task.priority.name,
        F_STATUS to task.status.name,
        F_CATEGORY_UUID to categoryUuid,
        F_GROUP_UUID to task.groupId,
        F_ARCHIVED to task.archived,
        F_ARCHIVED_AT to task.archivedAt,
        F_STARTED_AT to task.startedAt,
        F_ORDER to task.order,
        F_IS_DELETED to task.isDeleted,
        F_DELETED_AT to task.deletedAt,
        F_CREATED_AT to task.createdAt,
        F_UPDATED_AT to task.updatedAt
        // Deliberately absent: local row id, local user id, auth data, sync queue state.
    )

    private fun categoryToFields(category: Category, parentUuid: String?): Map<String, Any?> = mapOf(
        F_UUID to category.uuid,
        F_NAME to category.name,
        F_COLOR to category.color,
        F_ICON to category.icon,
        F_PARENT_UUID to parentUuid,
        F_IS_DELETED to category.isDeleted,
        F_DELETED_AT to category.deletedAt,
        F_CREATED_AT to category.createdAt,
        F_UPDATED_AT to category.updatedAt
    )

    private fun groupToFields(group: TaskGroup): Map<String, Any?> = mapOf(
        F_UUID to group.uuid,
        F_NAME to group.name,
        F_COLLAPSED to group.collapsed,
        F_SORT_ORDER to group.sortOrder,
        F_IS_DELETED to group.isDeleted,
        F_DELETED_AT to group.deletedAt,
        F_CREATED_AT to group.createdAt,
        F_UPDATED_AT to group.updatedAt
    )

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false

    companion object {
        const val ROOT = "users"
        const val C_TASKS = "tasks"
        const val C_CATEGORIES = "categories"
        const val C_GROUPS = "groups"

        private const val PAGE_SIZE = 400L
        private const val F_UUID = "uuid"
        private const val F_TITLE = "title"
        private const val F_DESCRIPTION = "description"
        private const val F_DUE_DATE = "due_date"
        private const val F_PRIORITY = "priority"
        private const val F_STATUS = "status"
        private const val F_CATEGORY_UUID = "category_uuid"
        private const val F_GROUP_UUID = "group_uuid"
        private const val F_ARCHIVED = "archived"
        private const val F_ARCHIVED_AT = "archived_at"
        private const val F_STARTED_AT = "started_at"
        private const val F_ORDER = "order"
        private const val F_NAME = "name"
        private const val F_COLOR = "color"
        private const val F_ICON = "icon"
        private const val F_PARENT_UUID = "parent_uuid"
        private const val F_COLLAPSED = "collapsed"
        private const val F_SORT_ORDER = "sort_order"
        private const val F_IS_DELETED = "is_deleted"
        private const val F_DELETED_AT = "deleted_at"
        private const val F_CREATED_AT = "created_at"
        private const val F_UPDATED_AT = "updated_at"
    }
}

data class SyncedTask(
    val uuid: String,
    val title: String,
    val description: String,
    val dueDate: Long?,
    val priority: Priority,
    val status: TaskStatus,
    val categoryUuid: String?,
    val groupUuid: String?,
    val archived: Boolean,
    val archivedAt: Long?,
    val startedAt: Long?,
    val order: Int,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

data class SyncedCategory(
    val uuid: String,
    val name: String,
    val color: Int,
    val icon: String,
    val parentUuid: String?,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

data class SyncedGroup(
    val uuid: String,
    val name: String,
    val collapsed: Boolean,
    val sortOrder: Int,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
