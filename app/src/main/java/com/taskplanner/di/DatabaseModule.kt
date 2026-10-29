package com.taskplanner.di

import android.content.Context
import androidx.room.Room
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.local.AchievementDao
import com.taskplanner.data.local.AchievementStatsDao
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GoalDao
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.local.InsightDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.LegacyDbRescuer
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.ProfileAttachDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.TaskStatisticsDao
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.local.WorkStatisticsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single point of Room initialization for the whole app.
 *
 * The database lives in the default private location (getDatabasePath), never in
 * cacheDir. Before the first open, LegacyDbRescuer copies any database found in
 * legacy locations (cacheDir / externalFilesDir) so pre-existing user data
 * survives the switch. No destructive fallback: an unhandled schema version
 * fails loudly instead of silently wiping data.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        LegacyDbRescuer(context).rescueIfNeeded()
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppMigrations.ALL)
            .build()
    }

    @Provides
    fun provideTaskDao(database: AppDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideUserDao(database: AppDatabase): UserDao = database.userDao()

    @Provides
    fun provideGroupDao(database: AppDatabase): GroupDao = database.groupDao()

    @Provides
    fun provideReminderDao(database: AppDatabase): ReminderDao = database.reminderDao()

    @Provides
    fun provideRecurrenceDao(database: AppDatabase): RecurrenceDao = database.recurrenceDao()

    @Provides
    fun provideTrackingSessionDao(database: AppDatabase): TrackingSessionDao =
        database.trackingSessionDao()

    @Provides
    fun provideWorkSessionDao(database: AppDatabase): WorkSessionDao = database.workSessionDao()

    @Provides
    fun provideWorkStatisticsDao(database: AppDatabase): WorkStatisticsDao =
        database.workStatisticsDao()

    /** The task-side aggregates the dashboard reads: the health counts, the status counts, and the
     *  creation and completion figures of a period. */
    @Provides
    fun provideTaskStatisticsDao(database: AppDatabase): TaskStatisticsDao =
        database.taskStatisticsDao()

    /** Goals and habits, their marks and their correction history: the schema v14 tables, read through
     *  the same database the rest of the profile lives in. */
    @Provides
    fun provideGoalDao(database: AppDatabase): GoalDao = database.goalDao()

    @Provides
    fun provideHabitDao(database: AppDatabase): HabitDao = database.habitDao()

    /** The notebook of readings: the schema v15 table, written only by the pass that derives it. */
    @Provides
    fun provideInsightDao(database: AppDatabase): InsightDao = database.insightDao()

    /** Grants the reader earned, with the reading each was granted on: the schema v16 table. */
    @Provides
    fun provideAchievementDao(database: AppDatabase): AchievementDao = database.achievementDao()

    /**
     * The counters a grant is judged against: totals over the rows the reader already has, aggregated in
     * SQLite, with no table of its own.
     */
    @Provides
    fun provideAchievementStatsDao(database: AppDatabase): AchievementStatsDao =
        database.achievementStatsDao()

    /**
     * The statements that move the eleven profile tables the sign-in handover used to leave behind —
     * time tracking, goals, habits, insights and achievements. See the DAO's own doc for the rule they apply.
     */
    @Provides
    fun provideProfileAttachDao(database: AppDatabase): ProfileAttachDao = database.profileAttachDao()
}
