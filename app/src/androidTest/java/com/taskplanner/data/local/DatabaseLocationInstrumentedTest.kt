package com.taskplanner.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskplanner.di.DatabaseModule
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the user's tasks physically live, checked on a real device rather than in the JVM harness:
 * the JVM tests can prove the rescue and the migrations, but only the device runs the same
 * filesystem Android manages, including the cache it is allowed to purge.
 *
 * The permanent location is moved aside for the duration of each test and put back afterwards, so
 * nothing a developer already has on the device is destroyed.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseLocationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val target by lazy { context.getDatabasePath(AppDatabase.NAME) }
    private val restored = mutableListOf<Pair<File, File>>()
    private val planted = mutableListOf<File>()

    @Before
    fun moveTheCurrentDatabaseAside() {
        databaseFiles().forEach { file ->
            if (file.exists()) {
                val backup = File("${file.path}.instrumented-backup")
                backup.delete()
                assertTrue("could not move ${file.name} out of the way", file.renameTo(backup))
                restored += backup to file
            }
        }
    }

    @After
    fun restoreTheDatabase() {
        // Close nothing implicitly: only the files this test created are removed.
        databaseFiles().forEach { it.delete() }
        restored.forEach { (backup, original) ->
            assertTrue("could not restore ${original.name}", backup.renameTo(original))
        }
        planted.forEach { file ->
            file.delete()
            File("${file.path}-wal").delete()
        }
    }

    @Test
    fun theProductionDatabaseFileIsNotInALocationAndroidCanPurge() {
        val database = runBlocking(Dispatchers.IO) { DatabaseModule.provideDatabase(context) }
        try {
            val path = database.openHelper.readableDatabase.path
            assertNotNull("the app must persist to a file", path)
            assertEquals(target.absolutePath, path)
            assertFalse(
                "tasks must never live under ${context.cacheDir}",
                path!!.startsWith(context.cacheDir.absolutePath)
            )
            assertFalse(path.startsWith(context.externalCacheDir?.absolutePath ?: "/nonexistent"))
        } finally {
            database.close()
        }
    }

    @Test
    fun aTaskSurvivesTheProcessBeingKilledAndRestarted() {
        val userId = writeOneTask("Instrumented survivor")
        val reopened = runBlocking(Dispatchers.IO) { DatabaseModule.provideDatabase(context) }
        try {
            val titles = runBlocking(Dispatchers.IO) {
                reopened.taskDao().getAllTasksOnce(userId).map { it.title }
            }
            assertEquals(listOf("Instrumented survivor"), titles)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aLegacyCacheDatabaseIsCopiedIntoPermanentStorageAndLeftInPlace() {
        val legacy = File(context.cacheDir, AppDatabase.NAME)
        planted += legacy
        legacy.parentFile?.mkdirs()
        legacy.delete()
        val handle = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacy, null)
        try {
            handle.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT)")
            handle.execSQL("INSERT INTO tasks (title) VALUES ('old task')")
            handle.execSQL("PRAGMA user_version = 7")
        } finally {
            handle.close()
        }
        val wal = File(legacy.path + "-wal").apply { writeText("wal-bytes") }
        planted += wal

        LegacyDbRescuer(context).rescueIfNeeded()

        assertTrue("the rescue placed nothing at ${target.absolutePath}", target.exists())
        assertEquals("the WAL sidecar must travel with the database", "wal-bytes", File(target.path + "-wal").readText())
        assertTrue("the original must stay where it was", legacy.exists())

        File(target.path + "-wal").delete()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppMigrations.ALL)
            .build()
        try {
            runBlocking(Dispatchers.IO) {
                // Room may only validate the schema on first use, so touch the tasks table.
                val cursor = database.openHelper.readableDatabase.query("SELECT title FROM tasks")
                try {
                    assertTrue(cursor.moveToFirst())
                    assertEquals("old task", cursor.getString(0))
                } finally {
                    cursor.close()
                }
            }
        } finally {
            database.close()
        }
    }

    private fun writeOneTask(title: String): Long {
        val database = runBlocking(Dispatchers.IO) { DatabaseModule.provideDatabase(context) }
        return try {
            runBlocking(Dispatchers.IO) {
                val userId = database.userDao().insertUser(
                    com.taskplanner.data.model.User(
                        email = "instrumented-${System.nanoTime()}@example.com",
                        name = "instrumented"
                    )
                )
                database.taskDao().insertTask(
                    com.taskplanner.data.model.Task(title = title, description = "", userId = userId)
                )
                userId
            }
        } finally {
            database.close()
        }
    }

    private fun databaseFiles(): List<File> =
        listOf("", "-wal", "-shm").map { suffix -> File(target.path + suffix) }
}
