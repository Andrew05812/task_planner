package com.taskplanner.data.backup

import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
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

/**
 * The exported rows.
 *
 * Every field defaults to empty, which is what makes a newer app read an older file: a 1.0 backup
 * has no [recurrenceRules] and no [reminders], so Gson leaves the defaults in place and the import
 * simply has nothing to restore for them.
 */
data class BackupData(
    val tasks: List<Task> = emptyList(),
    val categories: List<Category> = emptyList(),
    val groups: List<TaskGroup> = emptyList(),
    val recurrenceRules: List<RecurrenceRule> = emptyList(),
    val reminders: List<Reminder> = emptyList()
)

data class BackupSummary(
    val formatVersion: String,
    val exportedAt: Long,
    val taskCount: Int,
    val categoryCount: Int,
    val groupCount: Int,
    val archivedCount: Int,
    val seriesCount: Int,
    val reminderCount: Int
)

open class BackupValidationException(message: String) : Exception(message)

class BackupUnsupportedVersionException(val version: String) :
    BackupValidationException("Unsupported backup format version: $version")
