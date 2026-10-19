package com.taskplanner.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import kotlinx.coroutines.tasks.await

/**
 * Firestore access layer. Layout: users/{uid}/tasks|categories|groups|recurrence_rules|reminders/{uuid}.
 * The document id is the entity uuid, so pushing the same entity twice is
 * idempotent and a retry after a network failure cannot create duplicates.
 *
 * What a document *contains* lives with the [SyncedTask] / [SyncedCategory] / [SyncedGroup] /
 * [SyncedRule] / [SyncedReminder] types rather than here: the cloud is never reachable from the
 * tests in this repository, and the wire format is the part of sync that can be pinned down without
 * it. This class only moves those maps in and out of Firestore.
 */
class FirestoreSyncStore(
    private val firestore: FirebaseFirestore,
    private val uid: String
) {
    private fun collection(name: String) = firestore.collection(ROOT).document(uid).collection(name)

    suspend fun pushTask(task: Task, categoryUuid: String?) =
        collection(C_TASKS).document(task.uuid)
            .set(SyncedTask.of(task, categoryUuid).fields(), SetOptions.merge())
            .await()

    suspend fun pushCategory(category: Category, parentUuid: String?) =
        collection(C_CATEGORIES).document(category.uuid)
            .set(SyncedCategory.of(category, parentUuid).fields(), SetOptions.merge())
            .await()

    suspend fun pushGroup(group: TaskGroup) =
        collection(C_GROUPS).document(group.uuid)
            .set(SyncedGroup.of(group).fields(), SetOptions.merge())
            .await()

    suspend fun pushRule(rule: RecurrenceRule) =
        collection(C_RULES).document(rule.uuid)
            .set(SyncedRule.of(rule).fields(), SetOptions.merge())
            .await()

    suspend fun pushReminder(reminder: Reminder) =
        collection(C_REMINDERS).document(reminder.uuid)
            .set(SyncedReminder.of(reminder).fields(), SetOptions.merge())
            .await()

    suspend fun pullTasksSince(watermark: Long): List<SyncedTask> =
        page(C_TASKS, watermark).mapNotNull { SyncedTask.fromFields(it) }

    suspend fun pullCategoriesSince(watermark: Long): List<SyncedCategory> =
        page(C_CATEGORIES, watermark).mapNotNull { SyncedCategory.fromFields(it) }

    suspend fun pullGroupsSince(watermark: Long): List<SyncedGroup> =
        page(C_GROUPS, watermark).mapNotNull { SyncedGroup.fromFields(it) }

    suspend fun pullRulesSince(watermark: Long): List<SyncedRule> =
        page(C_RULES, watermark).mapNotNull { SyncedRule.fromFields(it) }

    suspend fun pullRemindersSince(watermark: Long): List<SyncedReminder> =
        page(C_REMINDERS, watermark).mapNotNull { SyncedReminder.fromFields(it) }

    private suspend fun page(name: String, watermark: Long): List<Map<String, Any?>> =
        collection(name)
            .whereGreaterThan(F_UPDATED_AT, watermark)
            .orderBy(F_UPDATED_AT, Query.Direction.ASCENDING)
            .limit(PAGE_SIZE)
            .get()
            .await()
            .documents
            .map { it.data.orEmpty() }

    companion object {
        const val ROOT = "users"
        const val C_TASKS = "tasks"
        const val C_CATEGORIES = "categories"
        const val C_GROUPS = "groups"
        const val C_RULES = "recurrence_rules"
        const val C_REMINDERS = "reminders"

        private const val PAGE_SIZE = 400L
    }
}

private const val F_UUID = "uuid"
private const val F_TITLE = "title"
private const val F_DESCRIPTION = "description"
private const val F_DUE_DATE = "due_date"
private const val F_START_TIME = "start_time"
private const val F_END_TIME = "end_time"
private const val F_ESTIMATED_MINUTES = "estimated_minutes"
private const val F_SERIES_UUID = "series_uuid"
private const val F_OCCURRENCE_AT = "occurrence_at"
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
private const val F_UNIT = "unit"
private const val F_INTERVAL = "interval"
private const val F_WEEK_DAYS = "week_days"
private const val F_DAY_OF_MONTH = "day_of_month"
private const val F_ANCHOR_AT = "anchor_at"
private const val F_ZONE_ID = "zone_id"
private const val F_CURSOR_AT = "cursor_at"
private const val F_PAUSED = "paused"
private const val F_ENDED_AT = "ended_at"
private const val F_TASK_UUID = "task_uuid"
private const val F_OFFSET_MINUTES = "offset_minutes"
private const val F_TRIGGER_AT = "trigger_at"
private const val F_NOTIFIED_AT = "notified_at"
private const val F_IS_DELETED = "is_deleted"
private const val F_DELETED_AT = "deleted_at"
private const val F_CREATED_AT = "created_at"
private const val F_UPDATED_AT = "updated_at"

private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0
private fun Map<String, Any?>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false

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
    val updatedAt: Long,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val estimatedMinutes: Int? = null,
    val seriesUuid: String? = null,
    val occurrenceAt: Long? = null
) {
    fun fields(): Map<String, Any?> = mapOf(
        F_UUID to uuid,
        F_TITLE to title,
        F_DESCRIPTION to description,
        F_DUE_DATE to dueDate,
        F_START_TIME to startTime,
        F_END_TIME to endTime,
        F_ESTIMATED_MINUTES to estimatedMinutes,
        F_SERIES_UUID to seriesUuid,
        F_OCCURRENCE_AT to occurrenceAt,
        F_PRIORITY to priority.name,
        F_STATUS to status.name,
        F_CATEGORY_UUID to categoryUuid,
        F_GROUP_UUID to groupUuid,
        F_ARCHIVED to archived,
        F_ARCHIVED_AT to archivedAt,
        F_STARTED_AT to startedAt,
        F_ORDER to order,
        F_IS_DELETED to isDeleted,
        F_DELETED_AT to deletedAt,
        F_CREATED_AT to createdAt,
        F_UPDATED_AT to updatedAt
        // Deliberately absent: local row id, local user id, auth data, sync queue state.
    )

    companion object {
        fun of(task: Task, categoryUuid: String?): SyncedTask = SyncedTask(
            uuid = task.uuid,
            title = task.title,
            description = task.description,
            dueDate = task.dueDate,
            priority = task.priority,
            status = task.status,
            categoryUuid = categoryUuid,
            groupUuid = task.groupId,
            archived = task.archived,
            archivedAt = task.archivedAt,
            startedAt = task.startedAt,
            order = task.order,
            isDeleted = task.isDeleted,
            deletedAt = task.deletedAt,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt,
            startTime = task.startTime,
            endTime = task.endTime,
            estimatedMinutes = task.estimatedMinutes,
            seriesUuid = task.seriesUuid,
            occurrenceAt = task.occurrenceAt
        )

        fun fromFields(fields: Map<String, Any?>): SyncedTask? {
            val uuid = fields.string(F_UUID)?.takeIf { it.isNotBlank() } ?: return null
            return SyncedTask(
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
                order = fields.int(F_ORDER),
                isDeleted = fields.bool(F_IS_DELETED),
                deletedAt = fields.longOrNull(F_DELETED_AT),
                createdAt = fields.long(F_CREATED_AT),
                updatedAt = fields.long(F_UPDATED_AT),
                startTime = fields.longOrNull(F_START_TIME),
                endTime = fields.longOrNull(F_END_TIME),
                estimatedMinutes = fields.intOrNull(F_ESTIMATED_MINUTES),
                seriesUuid = fields.string(F_SERIES_UUID),
                occurrenceAt = fields.longOrNull(F_OCCURRENCE_AT)
            )
        }
    }
}

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
) {
    fun fields(): Map<String, Any?> = mapOf(
        F_UUID to uuid,
        F_NAME to name,
        F_COLOR to color,
        F_ICON to icon,
        F_PARENT_UUID to parentUuid,
        F_IS_DELETED to isDeleted,
        F_DELETED_AT to deletedAt,
        F_CREATED_AT to createdAt,
        F_UPDATED_AT to updatedAt
    )

    companion object {
        fun of(category: Category, parentUuid: String?): SyncedCategory = SyncedCategory(
            uuid = category.uuid,
            name = category.name,
            color = category.color,
            icon = category.icon,
            parentUuid = parentUuid,
            isDeleted = category.isDeleted,
            deletedAt = category.deletedAt,
            createdAt = category.createdAt,
            updatedAt = category.updatedAt
        )

        fun fromFields(fields: Map<String, Any?>): SyncedCategory? {
            val uuid = fields.string(F_UUID)?.takeIf { it.isNotBlank() } ?: return null
            return SyncedCategory(
                uuid = uuid,
                name = fields.string(F_NAME).orEmpty(),
                color = fields.int(F_COLOR),
                icon = fields.string(F_ICON).orEmpty(),
                parentUuid = fields.string(F_PARENT_UUID),
                isDeleted = fields.bool(F_IS_DELETED),
                deletedAt = fields.longOrNull(F_DELETED_AT),
                createdAt = fields.long(F_CREATED_AT),
                updatedAt = fields.long(F_UPDATED_AT)
            )
        }
    }
}

data class SyncedGroup(
    val uuid: String,
    val name: String,
    val collapsed: Boolean,
    val sortOrder: Int,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun fields(): Map<String, Any?> = mapOf(
        F_UUID to uuid,
        F_NAME to name,
        F_COLLAPSED to collapsed,
        F_SORT_ORDER to sortOrder,
        F_IS_DELETED to isDeleted,
        F_DELETED_AT to deletedAt,
        F_CREATED_AT to createdAt,
        F_UPDATED_AT to updatedAt
    )

    companion object {
        fun of(group: TaskGroup): SyncedGroup = SyncedGroup(
            uuid = group.uuid,
            name = group.name,
            collapsed = group.collapsed,
            sortOrder = group.sortOrder,
            isDeleted = group.isDeleted,
            deletedAt = group.deletedAt,
            createdAt = group.createdAt,
            updatedAt = group.updatedAt
        )

        fun fromFields(fields: Map<String, Any?>): SyncedGroup? {
            val uuid = fields.string(F_UUID)?.takeIf { it.isNotBlank() } ?: return null
            return SyncedGroup(
                uuid = uuid,
                name = fields.string(F_NAME).orEmpty(),
                collapsed = fields.bool(F_COLLAPSED),
                sortOrder = fields.int(F_SORT_ORDER),
                isDeleted = fields.bool(F_IS_DELETED),
                deletedAt = fields.longOrNull(F_DELETED_AT),
                createdAt = fields.long(F_CREATED_AT),
                updatedAt = fields.long(F_UPDATED_AT)
            )
        }
    }
}

/**
 * A repeating series as the cloud sees it.
 *
 * The cursor travels with the rule: which instance a series currently exposes is part of the pattern
 * the user set up, so a second device that pulled the rule must not restart it at the anchor and
 * hand back occurrences the user already finished.
 */
data class SyncedRule(
    val uuid: String,
    val unit: RepeatUnit,
    val interval: Int,
    val weekDays: String,
    val dayOfMonth: Int,
    val anchorAt: Long,
    val zoneId: String,
    val cursorAt: Long,
    val paused: Boolean,
    val endedAt: Long?,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun fields(): Map<String, Any?> = mapOf(
        F_UUID to uuid,
        F_UNIT to unit.name,
        F_INTERVAL to interval,
        F_WEEK_DAYS to weekDays,
        F_DAY_OF_MONTH to dayOfMonth,
        F_ANCHOR_AT to anchorAt,
        F_ZONE_ID to zoneId,
        F_CURSOR_AT to cursorAt,
        F_PAUSED to paused,
        F_ENDED_AT to endedAt,
        F_IS_DELETED to isDeleted,
        F_DELETED_AT to deletedAt,
        F_CREATED_AT to createdAt,
        F_UPDATED_AT to updatedAt
    )

    fun toRule(userId: Long): RecurrenceRule = RecurrenceRule(
        uuid = uuid,
        userId = userId,
        unit = unit,
        interval = interval,
        weekDays = weekDays,
        dayOfMonth = dayOfMonth,
        anchorAt = anchorAt,
        zoneId = zoneId,
        cursorAt = cursorAt,
        paused = paused,
        endedAt = endedAt,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun of(rule: RecurrenceRule): SyncedRule = SyncedRule(
            uuid = rule.uuid,
            unit = rule.unit,
            interval = rule.interval,
            weekDays = rule.weekDays,
            dayOfMonth = rule.dayOfMonth,
            anchorAt = rule.anchorAt,
            zoneId = rule.zoneId,
            cursorAt = rule.cursorAt,
            paused = rule.paused,
            endedAt = rule.endedAt,
            isDeleted = rule.isDeleted,
            deletedAt = rule.deletedAt,
            createdAt = rule.createdAt,
            updatedAt = rule.updatedAt
        )

        fun fromFields(fields: Map<String, Any?>): SyncedRule? {
            val uuid = fields.string(F_UUID)?.takeIf { it.isNotBlank() } ?: return null
            return SyncedRule(
                uuid = uuid,
                unit = runCatching { RepeatUnit.valueOf(fields.string(F_UNIT).orEmpty()) }
                    .getOrDefault(RepeatUnit.DAY),
                interval = fields.int(F_INTERVAL),
                weekDays = fields.string(F_WEEK_DAYS).orEmpty(),
                dayOfMonth = fields.int(F_DAY_OF_MONTH),
                anchorAt = fields.long(F_ANCHOR_AT),
                zoneId = fields.string(F_ZONE_ID).orEmpty(),
                cursorAt = fields.long(F_CURSOR_AT),
                paused = fields.bool(F_PAUSED),
                endedAt = fields.longOrNull(F_ENDED_AT),
                isDeleted = fields.bool(F_IS_DELETED),
                deletedAt = fields.longOrNull(F_DELETED_AT),
                createdAt = fields.long(F_CREATED_AT),
                updatedAt = fields.long(F_UPDATED_AT)
            )
        }
    }
}

/**
 * One lead time on one task. The document id is the reminder's uuid, which [Reminder.identity]
 * derives from (task, offset), so two devices that both offered the same 15-minute warning on the
 * same task write the same document instead of competing with two alarms for one minute.
 */
data class SyncedReminder(
    val uuid: String,
    val taskUuid: String,
    val offsetMinutes: Int,
    val triggerAt: Long,
    val anchorAt: Long,
    val notifiedAt: Long?,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun fields(): Map<String, Any?> = mapOf(
        F_UUID to uuid,
        F_TASK_UUID to taskUuid,
        F_OFFSET_MINUTES to offsetMinutes,
        F_TRIGGER_AT to triggerAt,
        F_ANCHOR_AT to anchorAt,
        F_NOTIFIED_AT to notifiedAt,
        F_IS_DELETED to isDeleted,
        F_DELETED_AT to deletedAt,
        F_CREATED_AT to createdAt,
        F_UPDATED_AT to updatedAt
    )

    fun toReminder(userId: Long): Reminder = Reminder(
        uuid = uuid,
        taskUuid = taskUuid,
        userId = userId,
        offsetMinutes = offsetMinutes,
        triggerAt = triggerAt,
        anchorAt = anchorAt,
        notifiedAt = notifiedAt,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun of(reminder: Reminder): SyncedReminder = SyncedReminder(
            uuid = reminder.uuid,
            taskUuid = reminder.taskUuid,
            offsetMinutes = reminder.offsetMinutes,
            triggerAt = reminder.triggerAt,
            anchorAt = reminder.anchorAt,
            notifiedAt = reminder.notifiedAt,
            isDeleted = reminder.isDeleted,
            deletedAt = reminder.deletedAt,
            createdAt = reminder.createdAt,
            updatedAt = reminder.updatedAt
        )

        fun fromFields(fields: Map<String, Any?>): SyncedReminder? {
            val uuid = fields.string(F_UUID)?.takeIf { it.isNotBlank() } ?: return null
            val taskUuid = fields.string(F_TASK_UUID)?.takeIf { it.isNotBlank() } ?: return null
            return SyncedReminder(
                uuid = uuid,
                taskUuid = taskUuid,
                offsetMinutes = fields.int(F_OFFSET_MINUTES),
                triggerAt = fields.long(F_TRIGGER_AT),
                anchorAt = fields.long(F_ANCHOR_AT),
                notifiedAt = fields.longOrNull(F_NOTIFIED_AT),
                isDeleted = fields.bool(F_IS_DELETED),
                deletedAt = fields.longOrNull(F_DELETED_AT),
                createdAt = fields.long(F_CREATED_AT),
                updatedAt = fields.long(F_UPDATED_AT)
            )
        }
    }
}
