package com.taskplanner.testing

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Which tables a profile owns but SQLite does not clear with it, re-read from the shipped schema every run.
 *
 * [SeededProfile] deletes those rows by hand because the cascade does not. That is only right while the schema
 * still lacks the foreign key — and a list that has quietly become redundant is worse than no list, because it
 * reads like a protection the database already gives. So the claim is asked of SQLite itself, on the device,
 * in both directions:
 *
 *  * every table [SeededProfile.orphanTables] names really has no `user_id` foreign key to `users`;
 *  * every table that holds a `user_id` column and no such foreign key is named there.
 *
 * A migration that adds the cascade fails the first check and says to drop the entry; a new table owned by a
 * profile fails the second and says to add one. Either way the file and the schema cannot drift apart.
 */
@RunWith(AndroidJUnit4::class)
class SeededProfileCleanupTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase

    @Before
    fun openTheAppsOwnDatabase() {
        database = SeededProfile.open(context)
    }

    @After
    fun closeIt() {
        database.close()
    }

    /** The tables SQLite itself says exist, `users` and the Room bookkeeping left out. */
    private fun tables(): List<String> =
        query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name <> 'users'",
            "name"
        ).map { it.lowercase() }.sorted()

    /** The parent tables this table points at, straight from `PRAGMA foreign_key_list`. */
    private fun parentsOf(table: String): List<String> =
        query("PRAGMA foreign_key_list($table)", "table")

    private fun columnsOf(table: String): List<String> = query("PRAGMA table_info($table)", "name")

    private fun query(sql: String, column: String): List<String> {
        val rows = mutableListOf<String>()
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            val index = cursor.getColumnIndexOrThrow(column)
            while (cursor.moveToNext()) rows += cursor.getString(index)
        }
        return rows
    }

    @Test
    fun theTablesTheFixtureDeletesByHandHaveNoCascadeToLose() {
        SeededProfile.orphanTables.forEach { table ->
            assertTrue(
                "$table is listed as needing an explicit delete, but SQLite now cascades it from `users` — " +
                    "take it out of SeededProfile.orphanTables",
                tables().contains(table.lowercase())
            )
            assertTrue(
                "$table is listed as an orphan, yet it carries a foreign key to `users`",
                "users" !in parentsOf(table)
            )
        }
    }

    @Test
    fun everyProfileOwnedTableWithoutACascadeIsNamedInTheFixture() {
        val missed = tables().filter { table ->
            "user_id" in columnsOf(table) && "users" !in parentsOf(table)
        }
        assertTrue(
            "these tables hold rows a profile owns and nothing deletes them when the profile goes: $missed. " +
                "Add them to SeededProfile.orphanTables or the instrumented suites leave them on the device.",
            missed.toSet() == SeededProfile.orphanTables.map { it.lowercase() }.toSet()
        )
    }

    /**
     * The cleanup itself, measured on the database the app uses.
     *
     * `takeTheProfileAndEverythingUnderItAway` is the only thing keeping a fixture off the next stage's
     * device, and its first version deleted the profile alone — which left eleven tasks and thirty-one
     * categories owned by nobody, because those tables carry no foreign key to `users` and so no cascade.
     * A comment saying the rows go with it is worth nothing against a count, so this writes one row in each
     * of the three tables, takes the profile away, and counts what is left.
     */
    @Test
    fun takingAProfileAwayTakesTheRowsItOwnedWithIt() {
        val fixture = SeededProfile.signIn(
            database, "cleanup-${System.nanoTime()}@example.test", "Проверка очистки"
        )
        val userId = fixture.seededUserId
        val taskUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        runBlocking {
            database.categoryDao().insertCategory(
                Category(
                    uuid = UUID.randomUUID().toString(),
                    name = "Категория чистки",
                    color = 0xFF2F6F5E.toInt(),
                    icon = "x",
                    userId = userId
                )
            )
            database.taskDao().insertTask(
                Task(uuid = taskUuid, title = "Задача чистки", description = "", userId = userId)
            )
            database.reminderDao().insertOrIgnore(
                Reminder(
                    uuid = UUID.randomUUID().toString(),
                    taskUuid = taskUuid,
                    userId = userId,
                    offsetMinutes = 15,
                    triggerAt = now,
                    anchorAt = now
                )
            )
        }
        assertEquals("the fixture owns one row in each of the three tables", 3, ownedRows(userId))

        SeededProfile.takeAway(database, fixture)

        assertEquals(
            "tasks, categories and reminders all went with the profile that owned them",
            0,
            ownedRows(userId)
        )
    }

    /** How many rows the given profile still owns across the tables that have no cascade. */
    private fun ownedRows(userId: Long): Int = SeededProfile.orphanTables.sumOf { table ->
        query("SELECT count(*) AS c FROM $table WHERE user_id = $userId", "c").first().toInt()
    }
}
