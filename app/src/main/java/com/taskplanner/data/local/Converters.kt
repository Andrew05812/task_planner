package com.taskplanner.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.model.isCompletedSpelling
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
        // value written by every previous release) keeps its exact name. Which
        // spellings mean "finished" is `isCompletedSpelling`'s, shared with the
        // dashboard's SQL so the two cannot drift apart.
        return when {
            isCompletedSpelling(value) -> TaskStatus.COMPLETED
            else -> when (value.uppercase(Locale.ROOT)) {
                "TODO" -> TaskStatus.TODO
                "IN_PROGRESS" -> TaskStatus.IN_PROGRESS
                "PAUSED" -> TaskStatus.PAUSED
                else -> TaskStatus.TODO
            }
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

    @TypeConverter
    fun toGoalType(value: String): GoalType = GoalType.fromName(value)

    @TypeConverter
    fun fromGoalType(value: GoalType): String = value.name

    @TypeConverter
    fun toHabitFrequency(value: String): HabitFrequency = HabitFrequency.fromName(value)

    @TypeConverter
    fun fromHabitFrequency(value: HabitFrequency): String = value.name

    @TypeConverter
    fun toInsightKind(value: String): InsightKind = InsightKind.fromName(value)

    @TypeConverter
    fun fromInsightKind(value: InsightKind): String = value.name

    // Both of these answer for a nullable column, so the pair is written with nullable signatures: Room
    // only calls the converter when the column holds text, and "no action" and "no answer given" are real
    // states the screen has to be able to tell from an unknown one.
    @TypeConverter
    fun toInsightAction(value: String?): InsightAction? = InsightAction.fromName(value)

    @TypeConverter
    fun fromInsightAction(value: InsightAction?): String? = value?.name

    @TypeConverter
    fun toInsightFeedback(value: String?): InsightFeedback? = InsightFeedback.fromName(value)

    @TypeConverter
    fun fromInsightFeedback(value: InsightFeedback?): String? = value?.name
} 