package com.taskplanner.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.di.DatabaseModule
import com.taskplanner.testing.LegacyDatabaseBuilder
import com.taskplanner.testing.SqliteTestDatabase
import com.taskplanner.testing.TestSchemas
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
import org.robolectric.RobolectricTestRunner

/**
 * Data safety of the wiring the app itself uses — [DatabaseModule.provideDatabase] — rather than
 * the rescue or the migration logic, which have their own suites. A fixture on an in-memory
 * database can never show that the app reads the same file it writes, that a version 1 install is
 * migrated instead of rebuilt, or that an unexpected schema version leaves the rows alone.
 */
@RunWith(RobolectricTestRunner::class)
class DataSafetyTest {

    private lateinit var context: Context
    private lateinit var target: File
    private val restored = mutableListOf<Pair<File, File>>()
    private val planted = mutableListOf<File>()

    @Before
    fun makeThePermanentLocationEmpty() {
        context = ApplicationProvider.getApplicationContext()
        target = context.getDatabasePath(AppDatabase.NAME)
        databaseFiles().forEach { file ->
            if (file.exists()) {
                val backup = File("${file.path}.test-backup")
                backup.delete()
                assertTrue("could not move ${file.name} out of the way", file.renameTo(backup))
                restored += backup to file
            }
        }
    }

    @After
    fun putBackTheDatabaseThatWasThere() {
        // Only the files this test created are removed; whatever predates it goes back untouched.
        databaseFiles().forEach { it.delete() }
        restored.forEach { (backup, original) ->
            assertTrue("could not restore ${original.name}", backup.renameTo(original))
        }
        planted.forEach { file ->
            file.delete()
            File("${file.path}-wal").delete()
        }
        File(context.cacheDir, RESCUE_SCRATCH).deleteRecursively()
    }

    @Test
    fun `the app resolves its database from private storage, never from the cache`() {
        val database = openProductionDatabase()
        try {
            val path = database.openHelper.readableDatabase.path
            assertNotNull("Room must use a file, not an in-memory database", path)
            assertEquals(target.absolutePath, path)
            assertFalse(
                "Android is free to purge ${context.cacheDir} at any time",
                path!!.startsWith(context.cacheDir.absolutePath)
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version 1 database left in the cache is rescued, migrated and keeps its tasks`() {
        val legacy = File(context.cacheDir, AppDatabase.NAME)
        planted += legacy
        val legacyHandle = SqliteTestDatabase.openAt(legacy)
        try {
            TestSchemas.createVersion(legacyHandle, 1)
            LegacyDatabaseBuilder.Seeder.seed(legacyHandle, 1)
        } finally {
            legacyHandle.detach()
        }

        val database = openProductionDatabase()
        try {
            assertEquals(
                "Room must land on the rescued file, not on a fresh empty one",
                target.absolutePath, database.openHelper.readableDatabase.path
            )
            val tasks = runBlocking(Dispatchers.IO) {
                database.taskDao().getAllTasksOnce(LegacyDatabaseBuilder.Seeder.USER_ID)
            }
            assertEquals(
                "the rescue plus the migration chain must carry every old task over",
                setOf("Pay rent", "Buy milk"), tasks.map { it.title }.toSet()
            )
            assertEquals(
                "the migrated file must report the schema the app was compiled against",
                7L, readUserVersion()
            )
        } finally {
            database.close()
        }
        assertTrue("the legacy copy is the user's backup and may not be consumed", legacy.exists())
    }

    @Test
    fun `a task written through the app is still there after the process restarts`() {
        val userId = insertThroughTheApp("Survive the restart")
        openProductionDatabase().close()

        val reopened = openProductionDatabase()
        try {
            val stored = runBlocking(Dispatchers.IO) { reopened.taskDao().getAllTasksOnce(userId) }
            assertEquals(listOf("Survive the restart"), stored.map { it.title })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `an unknown schema version is refused instead of silently rebuilt empty`() {
        insertThroughTheApp("Mine")

        val stamped = SqliteTestDatabase.openExisting(target)
        stamped.exec("PRAGMA user_version = 99")
        stamped.detach()

        // Room validates the schema when the file is first touched, so the refusal has to be
        // provoked by a query: merely building the database would not open the file at all.
        val outcome = try {
            val database = openProductionDatabase()
            try {
                "read ${runBlocking(Dispatchers.IO) { database.taskDao().getAllTasksOnce(1) }.size} rows"
            } finally {
                runCatching { database.close() }
            }
        } catch (refused: IllegalStateException) {
            "refused: ${refused.message}"
        }
        assertTrue(
            "the app must refuse an unknown schema instead of rebuilding it, got: $outcome",
            outcome.startsWith("refused:") && outcome.contains("migration")
        )

        val kept = SqliteTestDatabase.openExisting(target)
        try {
            assertEquals(
                "refusing to open must leave the rows exactly as they were",
                1L, kept.firstLong("SELECT COUNT(*) FROM tasks")
            )
            assertEquals("Mine", kept.firstCell("SELECT title FROM tasks LIMIT 1"))
        } finally {
            kept.detach()
        }
    }

    private fun insertThroughTheApp(title: String): Long {
        val database = openProductionDatabase()
        return try {
            runBlocking(Dispatchers.IO) {
                val userId = database.userDao().insertUser(
                    User(email = "$title@example.com".lowercase(), name = title)
                )
                database.taskDao().insertTask(Task(title = title, description = "", userId = userId))
                userId
            }
        } finally {
            database.close()
        }
    }

    /** The production provider, including the legacy rescue and the full migration chain. */
    private fun openProductionDatabase(): AppDatabase =
        runBlocking(Dispatchers.IO) { DatabaseModule.provideDatabase(context) }

    private fun readUserVersion(): Long {
        val handle = SqliteTestDatabase.openExisting(target)
        return try {
            handle.userVersion()
        } finally {
            handle.detach()
        }
    }

    private fun databaseFiles(): List<File> =
        listOf("", "-wal", "-shm").map { suffix -> File(target.path + suffix) }

    private companion object {
        const val RESCUE_SCRATCH = "db-rescue-probe"
    }
}
