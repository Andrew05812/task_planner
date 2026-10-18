package com.taskplanner.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.taskplanner.BuildConfig
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.repository.SessionRepository
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val groupDao: GroupDao,
    private val sessionRepository: SessionRepository
) {
    private val gson = Gson()

    suspend fun buildBackupJson(): String {
        val userId = sessionRepository.currentUserId()
            ?: throw BackupValidationException("No active profile")
        val data = BackupData(
            tasks = tasksWithIdentity(taskDao.getAllTasksOnce(userId)),
            categories = categoriesWithIdentity(categoryDao.getAllCategoriesOnce(userId)),
            groups = groupsWithIdentity(groupDao.getAllGroupsOnce(userId))
        )
        val file = BackupFile(
            formatVersion = FORMAT_VERSION.toString(),
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            data = data
        )
        return gson.toJson(file)
    }

    /** Writes the payload to a SAF-selected uri; rethrows IO problems to the caller. */
    fun writeBackup(uri: Uri, json: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw BackupValidationException("Cannot open the selected location")
    }

    fun readAndValidate(uri: Uri): BackupFile {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(stream.reader()).readText()
        } ?: throw BackupValidationException("Cannot open the selected file")

        val file = try {
            gson.fromJson(text, BackupFile::class.java)
                ?: throw BackupValidationException("Empty backup file")
        } catch (e: JsonParseException) {
            throw BackupValidationException("This file is not a valid Task Planner backup")
        }

        val version = SemanticVersion.parse(file.formatVersion)
        if (version == null || version.major != FORMAT_VERSION.major ||
            version.minor > FORMAT_VERSION.minor
        ) {
            throw BackupUnsupportedVersionException(file.formatVersion)
        }
        validateRelations(file)
        return file
    }

    private fun validateRelations(file: BackupFile) {
        val categoryIds = file.data.categories.map { it.id }.toSet()
        // Blank uuids are allowed: they belong to rows written before stable identities existed.
        // What must never repeat is an identity, because the import matches rows by uuid.
        val uuids = (file.data.tasks.map { it.uuid } + file.data.categories.map { it.uuid } +
            file.data.groups.map { it.uuid }).filter { it.isNotBlank() }
        if (uuids.size != uuids.distinct().size) {
            throw BackupValidationException("Backup contains duplicated entity identifiers")
        }
        val groupUuids = file.data.groups.map { it.uuid }.toSet()
        file.data.categories.forEach { category ->
            category.parentCategoryId?.let {
                if (it != 0L && it !in categoryIds) {
                    throw BackupValidationException(
                        "Category '${category.name}' references a missing parent category"
                    )
                }
            }
        }
        file.data.tasks.forEach { task ->
            task.categoryId?.let {
                if (it != 0L && it !in categoryIds) {
                    throw BackupValidationException(
                        "Task '${task.title}' references a missing category"
                    )
                }
            }
            task.groupId?.let {
                if (it !in groupUuids) {
                    throw BackupValidationException(
                        "Task '${task.title}' references a missing topic"
                    )
                }
            }
        }
    }

    fun summarize(file: BackupFile): BackupSummary = BackupSummary(
        formatVersion = file.formatVersion,
        exportedAt = file.exportedAt,
        taskCount = file.data.tasks.count { !it.archived },
        categoryCount = file.data.categories.size,
        groupCount = file.data.groups.size,
        archivedCount = file.data.tasks.count { it.archived }
    )

    /** Safety copy kept in app-private storage before any destructive replace. */
    suspend fun createSafetyCopy() {
        val json = buildBackupJson()
        val dir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
        java.io.File(dir, "safety_${System.currentTimeMillis()}.json")
            .writeText(json, Charsets.UTF_8)
    }

    suspend fun import(
        file: BackupFile,
        replaceExisting: Boolean,
        safetyCopyBeforeReplace: Boolean = true
    ) {
        val userId = sessionRepository.currentUserId()
            ?: throw BackupValidationException("No active profile")
        // Validated again here: import() has to stay safe even if a caller skips the preview step.
        validateRelations(file)
        if (replaceExisting && safetyCopyBeforeReplace) {
            createSafetyCopy()
        }
        // Whole import in a single transaction: on any failure Room rolls back,
        // so partially imported data can never remain.
        database.withTransaction {
            if (replaceExisting) {
                taskDao.deleteAllTasks(userId)
                categoryDao.deleteAllCategories(userId)
                groupDao.deleteAllGroups(userId)
            }
            // A backup is written by one device, so its numeric row ids mean nothing on another:
            // rows are matched by uuid and every new row gets a fresh local id. Inserting the file
            // ids directly would REPLACE whatever unrelated local row happens to own that id.
            val categoryIds = importCategories(file.data.categories, userId)
            val ownedGroups = importGroups(file.data.groups, userId)
            importTasks(file.data.tasks, userId, categoryIds, ownedGroups)
        }
    }

    /** Maps the category id used inside the backup onto the local row that now holds it. */
    private suspend fun importCategories(
        categories: List<Category>,
        userId: Long
    ): Map<Long, Long> {
        val localIds = LinkedHashMap<Long, Long>()
        var pending = categories.mapIndexed { index, category -> index to category }
        while (pending.isNotEmpty()) {
            // parents first, so a nested category can be re-linked to its new local row
            val ready = pending.filter { (_, category) ->
                category.parentCategoryId == null || category.parentCategoryId in localIds
            }
            // cycle or unresolvable parent: import the rest flat, linked to nothing
            val batch = ready.ifEmpty { pending }
            batch.forEach { (_, incoming) ->
                val parent = incoming.parentCategoryId?.let { localIds[it] }
                // uuid lookups are global, so only this profile's rows may be matched and updated
                val existing = incoming.uuid.ifBlank { null }
                    ?.let { categoryDao.getCategoryByUuid(it) }
                    ?.takeIf { it.userId == userId }
                if (existing == null) {
                    localIds[incoming.id] = categoryDao.insertCategory(
                        incoming.forUser(userId).copy(id = 0, parentCategoryId = parent)
                    )
                } else {
                    localIds[incoming.id] = existing.id
                    if (incoming.updatedAt > existing.updatedAt) {
                        categoryDao.insertCategory(
                            incoming.forUser(userId).copy(
                                id = existing.id,
                                uuid = existing.uuid,
                                parentCategoryId = parent
                            )
                        )
                    }
                }
            }
            val processed = batch.mapTo(mutableSetOf()) { it.first }
            pending = pending.filterNot { it.first in processed }
        }
        return localIds
    }

    /** Returns the topic groups owned by [userId] once the import finished. */
    private suspend fun importGroups(groups: List<TaskGroup>, userId: Long): Set<String> {
        val owned = mutableSetOf<String>()
        groups.forEach { incoming ->
            val existing = incoming.uuid.ifBlank { null }?.let { groupDao.getGroupByUuid(it) }
            when {
                // This profile already has the group: refresh it only when the file is newer.
                existing?.userId == userId -> {
                    if (incoming.updatedAt > existing.updatedAt) {
                        groupDao.upsertGroup(incoming.forUser(userId))
                    }
                    owned += incoming.uuid
                }

                // Another profile owns this uuid: neither steal the row nor link tasks to it.
                existing != null -> Unit

                else -> {
                    val withIdentity = incoming.copy(
                        uuid = incoming.uuid.ifBlank { UUID.randomUUID().toString() }
                    )
                    groupDao.upsertGroup(withIdentity.forUser(userId))
                    owned += withIdentity.uuid
                }
            }
        }
        return owned
    }

    private suspend fun importTasks(
        tasks: List<Task>,
        userId: Long,
        categoryIds: Map<Long, Long>,
        ownedGroups: Set<String>
    ) {
        tasks.forEach { incoming ->
            val linked = incoming.forUser(userId).copy(
                categoryId = incoming.categoryId?.let { categoryIds[it] },
                // a topic that belongs to another profile must not leak into this one
                groupId = incoming.groupId?.takeIf { it in ownedGroups }
            )
            // findTaskByUuid also sees tombstones, so re-importing revives a row instead of
            // creating a second row with the same uuid; only this profile's rows are matched.
            val existing = incoming.uuid.ifBlank { null }
                ?.let { taskDao.findTaskByUuid(it) }
                ?.takeIf { it.userId == userId }
            when {
                existing == null -> taskDao.insertTask(linked.copy(id = 0))
                incoming.updatedAt >= existing.updatedAt ->
                    taskDao.insertTask(linked.copy(id = existing.id, uuid = existing.uuid))
            }
        }
    }

    /**
     * Rows written before uuids existed are given a stable identity while exporting, and the new
     * uuid is stored locally too. Without that the backup could not be recognised on re-import,
     * so every restore would add a second copy of the same task.
     */
    private suspend fun tasksWithIdentity(tasks: List<Task>): List<Task> = tasks.map { task ->
        if (task.uuid.isNotBlank()) task
        else task.copy(uuid = UUID.randomUUID().toString()).also { taskDao.setUuid(task.id, it.uuid) }
    }

    private suspend fun categoriesWithIdentity(categories: List<Category>): List<Category> =
        categories.map { category ->
            if (category.uuid.isNotBlank()) category
            else category.copy(uuid = UUID.randomUUID().toString())
                .also { categoryDao.setUuid(category.id, it.uuid) }
        }

    /** Topics are referenced by uuid from tasks, so their identity has to be stable too. */
    private suspend fun groupsWithIdentity(groups: List<TaskGroup>): List<TaskGroup> =
        groups.map { group ->
            if (group.uuid.isNotBlank()) group
            else group.copy(uuid = UUID.randomUUID().toString())
                .also { groupDao.upsertGroup(it) }
        }

    private fun Task.forUser(userId: Long) = copy(
        userId = userId,
        uuid = uuid.ifBlank { UUID.randomUUID().toString() },
        syncState = SyncState.PENDING
    )

    private fun Category.forUser(userId: Long) = copy(
        userId = userId,
        uuid = uuid.ifBlank { UUID.randomUUID().toString() },
        syncState = SyncState.PENDING
    )

    private fun TaskGroup.forUser(userId: Long) = copy(
        userId = userId,
        syncState = SyncState.PENDING
    )

    companion object {
        /** Compatible range: same major, minor <= supported. */
        val FORMAT_VERSION = SemanticVersion(1, 0)
    }
}

data class SemanticVersion(val major: Int, val minor: Int) {
    override fun toString(): String = "$major.$minor"

    companion object {
        fun parse(raw: String): SemanticVersion? {
            val parts = raw.trim().split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SemanticVersion(major, minor)
        }
    }
}
