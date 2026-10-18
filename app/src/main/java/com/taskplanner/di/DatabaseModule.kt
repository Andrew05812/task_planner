package com.taskplanner.di

import android.content.Context
import androidx.room.Room
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.LegacyDbRescuer
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.UserDao
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
    fun provideRecurrenceDao(database: AppDatabase): RecurrenceDao = database.recurrenceDao()
}
