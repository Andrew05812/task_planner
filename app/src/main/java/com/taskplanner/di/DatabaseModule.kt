package com.taskplanner.di

import android.content.Context
import androidx.room.Room
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.local.Converters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        val dbFile = File(context.cacheDir, "taskplanner.db")
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbFile.absolutePath
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideTaskDao(database: AppDatabase): TaskDao {
        return database.taskDao()
    }

    @Provides
    fun provideCategoryDao(database: AppDatabase): CategoryDao {
        return database.categoryDao()
    }

    @Provides
    fun provideUserDao(database: AppDatabase): UserDao {
        return database.userDao()
    }
} 