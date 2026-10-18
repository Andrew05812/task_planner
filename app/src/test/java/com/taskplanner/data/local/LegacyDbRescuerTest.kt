package com.taskplanner.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.testing.SqliteTestDatabase
import com.taskplanner.testing.TestSchemas
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Earlier builds kept taskplanner.db in the cache directory, which Android is free to purge — a
 * root cause of users losing tasks. The rescuer copies the most valuable legacy file into the
 * permanent location before Room opens it, and it must never delete or overwrite anything.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyDbRescuerTest {

    private lateinit var context: Context
    private lateinit var target: File
    private val created = mutableListOf<SqliteTestDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        target = context.getDatabasePath(LegacyDbRescuer.DB_NAME)
        target.delete()
        File(target.path + "-wal").delete()
    }

    private fun writeLegacyDatabase(at: File, version: Int, taskCount: Int, updatedAt: Long): File {
        val db = SqliteTestDatabase.openAt(at)
        created += db
        TestSchemas.createVersion(db, version)
        db.exec(
            if (version >= 5)
                "INSERT INTO users (id, email, name, avatar_color, avatar_initial, is_current, " +
                    "last_used, created_at, updated_at) " +
                    "VALUES (1, 'old@example.com', 'Old Profile', -16777000, 'O', 1, $updatedAt, 100, $updatedAt)"
            else
                "INSERT INTO users (id, email, name, is_current, created_at, updated_at) " +
                    "VALUES (1, 'old@example.com', 'Old Profile', 1, 100, $updatedAt)"
        )
        repeat(taskCount) { index ->
            if (version >= 3) {
                db.exec(
                    "INSERT INTO tasks (id, title, description, due_date, priority, status, category_id, " +
                        "user_id, `order`, created_at, updated_at) " +
                        "VALUES ($index, 'task $index', '', NULL, 'MEDIUM', 'TODO', NULL, 1, 0, 100, $updatedAt)"
                )
            } else {
                db.exec(
                    "INSERT INTO tasks (id, title, description, dueDate, priority, status, categoryId, " +
                        "userId, createdAt, updatedAt, isRecurring, tags) " +
                        "VALUES ($index, 'task $index', '', NULL, 'MEDIUM', 'TODO', NULL, 1, 100, $updatedAt, 0, '[]')"
                )
            }
        }
        db.detach()
        return at
    }

    private fun cacheFile() = File(context.cacheDir, LegacyDbRescuer.DB_NAME)

    private fun filesDirFile() = File(context.filesDir, LegacyDbRescuer.DB_NAME)

    @Test
    fun `a database left in the cache is rescued into permanent storage`() {
        val legacy = writeLegacyDatabase(cacheFile(), version = 7, taskCount = 3, updatedAt = 500)

        LegacyDbRescuer(context).rescueIfNeeded()

        assertTrue("the database must now live outside the cache", target.exists())
        val rescued = SqliteTestDatabase.openExisting(target)
        assertEquals(3L, rescued.firstLong("SELECT COUNT(*) FROM tasks"))
        assertEquals("task 2", rescued.firstCell("SELECT title FROM tasks WHERE id = 2"))
        rescued.detach()
        assertTrue("the legacy file must not be deleted", legacy.exists())
    }

    @Test
    fun `the copy with the most data wins`() {
        writeLegacyDatabase(cacheFile(), version = 7, taskCount = 1, updatedAt = 900)
        writeLegacyDatabase(filesDirFile(), version = 7, taskCount = 4, updatedAt = 100)

        LegacyDbRescuer(context).rescueIfNeeded()

        val rescued = SqliteTestDatabase.openExisting(target)
        assertEquals(4L, rescued.firstLong("SELECT COUNT(*) FROM tasks"))
        rescued.detach()
    }

    @Test
    fun `the most recently updated copy wins on a tie`() {
        writeLegacyDatabase(cacheFile(), version = 7, taskCount = 2, updatedAt = 1_000)
        writeLegacyDatabase(filesDirFile(), version = 7, taskCount = 2, updatedAt = 2_000)

        LegacyDbRescuer(context).rescueIfNeeded()

        val rescued = SqliteTestDatabase.openExisting(target)
        assertEquals(2_000L, rescued.firstLong("SELECT MAX(updated_at) FROM tasks"))
        rescued.detach()
    }

    @Test
    fun `a newer schema wins when data is identical`() {
        writeLegacyDatabase(cacheFile(), version = 7, taskCount = 2, updatedAt = 1_000)
        writeLegacyDatabase(filesDirFile(), version = 6, taskCount = 2, updatedAt = 1_000)

        LegacyDbRescuer(context).rescueIfNeeded()

        val rescued = SqliteTestDatabase.openExisting(target)
        assertEquals(7L, rescued.firstLong("PRAGMA user_version"))
        rescued.detach()
    }

    @Test
    fun `wal sidecar files travel with the main database`() {
        val legacy = writeLegacyDatabase(cacheFile(), version = 7, taskCount = 1, updatedAt = 100)
        File(legacy.path + "-wal").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(legacy.path + "-shm").writeBytes(byteArrayOf(9, 9))

        LegacyDbRescuer(context).rescueIfNeeded()

        assertTrue("an open database loses uncommitted writes without its -wal", File(target.path + "-wal").exists())
        assertEquals(
            5,
            File(target.path + "-wal").readBytes().size
        )
        assertTrue(File(target.path + "-shm").exists())
        assertTrue(File(legacy.path + "-wal").exists())
    }

    @Test
    fun `an existing database is never overwritten`() {
        val current = SqliteTestDatabase.openAt(target)
        TestSchemas.createVersion(current, 7)
        current.exec(
            "INSERT INTO tasks (id, title, description, due_date, priority, status, user_id, `order`, " +
                "created_at, updated_at) VALUES (1, 'mine', '', NULL, 'MEDIUM', 'TODO', 1, 0, 1, 1)"
        )
        current.detach()
        writeLegacyDatabase(cacheFile(), version = 7, taskCount = 5, updatedAt = 5)

        LegacyDbRescuer(context).rescueIfNeeded()

        val kept = SqliteTestDatabase.openExisting(target)
        assertEquals(1L, kept.firstLong("SELECT COUNT(*) FROM tasks"))
        assertEquals("mine", kept.firstCell("SELECT title FROM tasks WHERE id = 1"))
        kept.detach()
    }

    @Test
    fun `nothing happens when there is no legacy data`() {
        assertFalse(target.exists())

        LegacyDbRescuer(context).rescueIfNeeded()

        assertFalse("no database may be invented out of nowhere", target.exists())
    }

    @Test
    fun `an empty placeholder file is not treated as data`() {
        cacheFile().apply { parentFile?.mkdirs(); createNewFile() }

        LegacyDbRescuer(context).rescueIfNeeded()

        assertFalse(target.exists())
    }
}
