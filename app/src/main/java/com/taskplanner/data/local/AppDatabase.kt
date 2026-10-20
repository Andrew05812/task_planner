package com.taskplanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalTaskLink
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheck
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkSession

@Database(
    entities = [
        Task::class,
        Category::class,
        User::class,
        TaskGroup::class,
        RecurrenceRule::class,
        Reminder::class,
        TrackingSession::class,
        WorkSession::class,
        WorkIntervalCorrection::class,
        Goal::class,
        GoalTaskLink::class,
        GoalValueChange::class,
        Habit::class,
        HabitCheck::class,
        HabitCheckChange::class,
        Insight::class
    ],
    version = 15,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun recurrenceDao(): RecurrenceDao
    abstract fun reminderDao(): ReminderDao
    abstract fun trackingSessionDao(): TrackingSessionDao
    abstract fun workSessionDao(): WorkSessionDao
    abstract fun workStatisticsDao(): WorkStatisticsDao
    abstract fun taskStatisticsDao(): TaskStatisticsDao
    abstract fun goalDao(): GoalDao
    abstract fun habitDao(): HabitDao
    abstract fun insightDao(): InsightDao

    companion object {
        const val NAME = "taskplanner.db"
    }
}
