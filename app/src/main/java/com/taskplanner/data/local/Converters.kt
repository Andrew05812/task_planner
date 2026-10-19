package com.taskplanner.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.WorkSource
import java.util.Date

import java.util.Locale

class Converters {
    private val gson: Gson = Gson()

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromStringToList(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        return try {
            gson.fromJson<List<String>>(value, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromListToString(list: List<String>): String {
        return try {
            gson.toJson(list)
        } catch (e: Exception) {
            "[]"
        }
    }

    @TypeConverter
    fun toPriority(value: String): Priority {
        return try {
            Priority.valueOf(value)
        } catch (e: Exception) {
            Priority.MEDIUM
        }
    }

    @TypeConverter
    fun fromPriority(value: Priority): String {
        return value.name
    }

    @TypeConverter
    fun toRepeatUnit(value: String): RepeatUnit {
        // A rule written by a newer build (or a damaged row) falls back to DAY instead of
        // throwing while the database is being read.
        return try {
            RepeatUnit.valueOf(value)
        } catch (e: Exception) {
            RepeatUnit.DAY
        }
    }

    @TypeConverter
    fun fromRepeatUnit(value: RepeatUnit): String {
        return value.name
    }

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus {
        // Historical databases store TaskStatus constant names as TEXT. Unknown or
        // legacy aliases fall back to TODO instead of throwing, and COMPLETED (the
        // value written by every previous release) keeps its exact name.
        return when (value.uppercase(Locale.ROOT)) {
            "TODO" -> TaskStatus.TODO
            "IN_PROGRESS" -> TaskStatus.IN_PROGRESS
            "PAUSED" -> TaskStatus.PAUSED
            "COMPLETED", "DONE" -> TaskStatus.COMPLETED
            else -> TaskStatus.TODO
        }
    }

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String {
        return value.name
    }

    @TypeConverter
    fun toSyncState(value: String): SyncState {
        return try {
            SyncState.valueOf(value)
        } catch (e: Exception) {
            SyncState.PENDING
        }
    }

    @TypeConverter
    fun fromSyncState(value: SyncState): String {
        return value.name
    }

    @TypeConverter
    fun toTrackingState(value: String): TrackingState {
        // A state written by a newer build, or damaged, is read as paused rather than running: the
        // worst thing an unknown row could be read as is "a timer is going" that nobody started.
        return try {
            TrackingState.valueOf(value.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            TrackingState.PAUSED
        }
    }

    @TypeConverter
    fun fromTrackingState(value: TrackingState): String = value.name

    @TypeConverter
    fun toWorkSource(value: String): WorkSource {
        // Same rule as the other columns: an unreadable source is still work the user did, so the
        // interval is kept and read as a measured one rather than dropped.
        return try {
            WorkSource.valueOf(value.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            WorkSource.TRACKER
        }
    }

    @TypeConverter
    fun fromWorkSource(value: WorkSource): String = value.name
} 