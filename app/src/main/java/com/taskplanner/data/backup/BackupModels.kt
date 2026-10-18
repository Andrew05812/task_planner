package com.taskplanner.data.backup

import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup

/**
 * Versioned JSON backup format. Secrets (auth tokens, Firebase config) are
 * intentionally never part of it.
 */
data class BackupFile(
    val formatVersion: String,
    val appVersion: String,
    val exportedAt: Long,
    val data: BackupData
)

data class BackupData(
    val tasks: List<Task> = emptyList(),
    val categories: List<Category> = emptyList(),
    val groups: List<TaskGroup> = emptyList()
)

data class BackupSummary(
    val formatVersion: String,
    val exportedAt: Long,
    val taskCount: Int,
    val categoryCount: Int,
    val groupCount: Int,
    val archivedCount: Int
)

open class BackupValidationException(message: String) : Exception(message)

class BackupUnsupportedVersionException(val version: String) :
    BackupValidationException("Unsupported backup format version: $version")
