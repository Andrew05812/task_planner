package com.taskplanner.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.TaskStatus
import java.util.Date

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
    fun toTaskStatus(value: String): TaskStatus {
        return try {
            TaskStatus.valueOf(value)
        } catch (e: Exception) {
            TaskStatus.TODO
        }
    }

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String {
        return value.name
    }
} 