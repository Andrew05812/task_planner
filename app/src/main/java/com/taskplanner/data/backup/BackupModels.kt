package com.taskplanner.data.backup

import androidx.annotation.StringRes
import com.taskplanner.R
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

/**
 * A backup the app will not restore, named by the resource that says why.
 *
 * The sentence is not carried here: `@StringRes Int` plus the arguments it needs is, so the screen
 * that shows the refusal can show it in the language the reader is reading in. `Exception`'s own
 * message stays a machine note -- it is what a log line and a debugger read, and it is deliberately
 * not what a Snackbar prints.
 */
open class BackupValidationException(
    @StringRes val messageRes: Int,
    val formatArgs: Array<out Any> = emptyArray(),
    detail: String? = null
) : Exception(detail ?: "backup refused (#$messageRes)")

class BackupUnsupportedVersionException(val version: String) :
    BackupValidationException(
        R.string.error_backup_unsupported_version,
        formatArgs = arrayOf(version),
        detail = "Unsupported backup format version: $version"
    )
