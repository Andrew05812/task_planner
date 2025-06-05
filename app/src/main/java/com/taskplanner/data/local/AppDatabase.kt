package com.taskplanner.data.local

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Database(
    entities = [
        Task::class,
        Category::class,
        User::class
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = []
)
@TypeConverters(Converters::class, builtInTypeConverters = BuiltInTypeConverters())
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbFile = File(context.getExternalFilesDir(null), "taskplanner.db")
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbFile.absolutePath
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            INSTANCE?.let { _ ->
                                CoroutineScope(Dispatchers.IO).launch {
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
} 