package com.taskplanner.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            // The planted database has to be the one version 7 actually shipped. It used to be a
            // two-column sketch — `CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT)` stamped
            // `user_version = 7` — and no release ever had that: the rescue hands the file to the real
            // migration chain, 7 runs to 16, and 9→10 builds `index_tasks_user_id_due_date` over a
            // column the sketch never had, so the test failed with `no such column: user_id` while
            // proving nothing about any user's upgrade. The statements now come from
            // assets/legacy-v7.sql, generated from Room's exported schema for version 7, and a JVM
            // test keeps that file equal to app/schemas/…/7.json so the two cannot drift.
            legacyStatements().forEach { statement -> handle.execSQL(statement) }
            handle.execSQL(
                "INSERT INTO users (id, email, name, avatar_color, avatar_initial, is_current, " +
                    "last_used, created_at, updated_at) " +
                    "VALUES (1, 'legacy@example.test', 'Legacy', 0, 'L', 1, 0, 0, 0)"
            )
            handle.execSQL(
                "INSERT INTO tasks (uuid, title, description, priority, status, user_id, `order`, " +
                    "created_at, updated_at) VALUES ('legacy-1', 'old task', '', 'LOW', 'TODO', " +
                    "1, 0, 0, 0)"
            )
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

    /**
     * The planted v7 schema, read from the asset rather than typed here, so the file that carries it is
     * the same artefact the JVM drift test compares against Room's exported JSON.
     *
     * The asset is opened through the **instrumentation** context, not `context`: this file ships inside
     * the test APK, while the application context resolves assets of the app under test — and the app has
     * no `src/main/assets` at all, so asking it would be a `FileNotFoundException` for a file that is
     * plainly there. Comments and blank lines are dropped; statements run in file order, table before its
     * indices, because a `CREATE INDEX` on a table that is not there yet is an error.
     */
    private fun legacyStatements(): List<String> =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("legacy-v7.sql").bufferedReader().use { reader -> reader.readLines() }
            .filterNot { it.isBlank() || it.trimStart().startsWith("--") }
            .map { it.trim().removeSuffix(";").trim() }
            .filter { it.isNotEmpty() }

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
