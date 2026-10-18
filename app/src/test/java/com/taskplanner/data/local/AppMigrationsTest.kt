package com.taskplanner.data.local

import androidx.room.migration.Migration
import com.taskplanner.testing.LegacyDatabaseBuilder
import com.taskplanner.testing.LegacyDatabaseBuilder.Seeder
import com.taskplanner.testing.SqliteTestDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real [AppMigrations] SQL against legacy databases rebuilt from the schema files Room
 * exported (app/schemas), on top of a genuine SQLite engine.
 *
 * These tests are the guarantee that an install upgrading from any previous release neither loses
 * rows nor opens a database Room would refuse ("Migration didn't properly handle ...").
 */
class AppMigrationsTest {

    private val builder = LegacyDatabaseBuilder()
    private val opened = mutableListOf<SqliteTestDatabase>()

    @After
    fun closeDatabases() {
        opened.forEach { runCatching(it::close) }
        opened.clear()
    }

    private fun open(version: Int) = builder.build(version).also(opened::add)

    @Test
    fun `each migration step produces the schema room exported`() {
        AppMigrations.ALL.forEach { migration ->
            val db = open(migration.startVersion)
            db.migrate(migration)
            builder.assertMatchesSchema(db, migration.endVersion, "MIGRATION_${step(migration)}")
        }
    }

    @Test
    fun `full history chain produces the newest schema room exported`() {
        val db = open(1)
        AppMigrations.ALL.forEach { db.migrate(it) }
        builder.assertMatchesSchema(db, latestVersion, "full chain 1->$latestVersion")
        assertTrue(db.tableNames().contains("task_groups"))
        assertEquals(
            setOf("uuid", "group_id", "archived", "archived_at", "started_at", "is_deleted", "deleted_at", "sync_state"),
            db.columnInfo("tasks").keys.intersect(
                setOf("uuid", "group_id", "archived", "archived_at", "started_at", "is_deleted", "deleted_at", "sync_state")
            )
        )
        assertTrue(db.columnInfo("users").keys.containsAll(setOf("firebase_uid", "avatar_url")))
    }

    @Test
    fun `upgrading never loses a row`() {
        val db = open(1)
        val before = counts(db)
        AppMigrations.ALL.forEach { db.migrate(it) }
        assertEquals(before, counts(db))
        assertEquals(2L, before.getValue("tasks"))
        assertEquals(1L, before.getValue("categories"))
        assertEquals(1L, before.getValue("users"))
    }

    @Test
    fun `task values survive the whole upgrade`() {
        val db = open(1)
        AppMigrations.ALL.forEach { db.migrate(it) }
        val title = db.firstCell(
            "SELECT title FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}"
        )
        assertEquals("Pay rent", title)
        val description = db.firstCell(
            "SELECT description FROM tasks WHERE id = ${Seeder.TODO_TASK_ID}"
        )
        assertEquals("note for Buy milk", description)
        val dueDate = db.firstLong(
            "SELECT due_date FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}"
        )
        assertEquals(1_600_000_000_000L, dueDate)
        assertEquals(Seeder.CATEGORY_ID, db.firstLong(
            "SELECT category_id FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}"
        ))
    }

    @Test
    fun `legacy status names keep their exact spelling so old rows stay readable`() {
        val db = open(1)
        AppMigrations.ALL.forEach { db.migrate(it) }
        val statuses = db.rows("SELECT status FROM tasks ORDER BY id").map { it[0] }
        assertEquals(listOf("COMPLETED", "TODO"), statuses)
    }

    @Test
    fun `migration 1 to 2 adds isCurrent as not null without dropping users`() {
        val db = open(1)
        db.migrate(AppMigrations.MIGRATION_1_2)
        builder.assertMatchesSchema(db, 2, "MIGRATION_1_2")
        assertEquals("0", db.firstCell("SELECT isCurrent FROM users WHERE id = ${Seeder.USER_ID}"))
        assertEquals(1, db.firstLong("SELECT COUNT(*) FROM users").toInt())
    }

    @Test
    fun `migration 2 to 3 renames columns to snake case preserving values`() {
        val db = open(2)
        db.migrate(AppMigrations.MIGRATION_2_3)
        builder.assertMatchesSchema(db, 3, "MIGRATION_2_3")
        assertEquals(
            "note for Pay rent",
            db.firstCell("SELECT description FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}")
        )
        assertEquals(
            "Home",
            db.firstCell("SELECT name FROM categories WHERE id = ${Seeder.CATEGORY_ID}")
        )
        assertEquals(
            "ivan@example.com",
            db.firstCell("SELECT email FROM users WHERE id = ${Seeder.USER_ID}")
        )
        // the current flag moved with the row instead of resetting
        assertEquals("1", db.firstCell("SELECT is_current FROM users WHERE id = ${Seeder.USER_ID}"))
    }

    @Test
    fun `migration 3 to 4 introduces manual order and drops the duplicated due date index`() {
        val db = open(3)
        db.migrate(AppMigrations.MIGRATION_3_4)
        builder.assertMatchesSchema(db, 4, "MIGRATION_3_4")
        assertEquals(
            "0",
            db.firstCell("SELECT `order` FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}")
        )
        assertTrue(
            "recurrence data is no longer part of the schema",
            db.columnInfo("tasks").keys.intersect(setOf("is_recurring", "recurrence_pattern")).isEmpty()
        )
    }

    @Test
    fun `migration 4 to 5 backfills avatar initial and last used`() {
        val db = open(4)
        db.migrate(AppMigrations.MIGRATION_4_5)
        builder.assertMatchesSchema(db, 5, "MIGRATION_4_5")
        assertEquals(
            "I",
            db.firstCell("SELECT avatar_initial FROM users WHERE id = ${Seeder.USER_ID}")
        )
        assertEquals(
            "1000",
            db.firstCell("SELECT last_used FROM users WHERE id = ${Seeder.USER_ID}")
        )
    }

    @Test
    fun `migration 5 to 6 is a schema-hash only step`() {
        val db = open(5)
        val before = db.columnInfo("users")
        db.migrate(AppMigrations.MIGRATION_5_6)
        builder.assertMatchesSchema(db, 6, "MIGRATION_5_6")
        assertEquals(before, db.columnInfo("users"))
    }

    @Test
    fun `migration 6 to 7 gives every existing row a uuid for cloud sync`() {
        val db = open(6)
        db.migrate(AppMigrations.MIGRATION_6_7)
        builder.assertMatchesSchema(db, 7, "MIGRATION_6_7")

        val taskUuids = db.rows("SELECT uuid FROM tasks").map { it[0]!! }
        assertEquals(2, taskUuids.size)
        taskUuids.forEach { uuid ->
            assertTrue("uuid must not be blank: '$uuid'", uuid.isNotBlank())
            assertTrue("uuid must be unique per row", taskUuids.count { it == uuid } == 1)
        }
        val categoryUuids = db.rows("SELECT uuid FROM categories").map { it[0]!! }
        assertTrue(categoryUuids.all { it.isNotBlank() })
    }

    @Test
    fun `migration 6 to 7 archives previously completed tasks`() {
        val db = open(6)
        db.migrate(AppMigrations.MIGRATION_6_7)

        val archived = db.rows(
            "SELECT id, archived, archived_at FROM tasks ORDER BY id"
        ).associate { it[0]!! to (it[1]!! to it[2]) }

        assertEquals("1", archived.getValue(Seeder.COMPLETED_TASK_ID.toString()).first)
        assertEquals("1600000000000", archived.getValue(Seeder.COMPLETED_TASK_ID.toString()).second)
        assertEquals("0", archived.getValue(Seeder.TODO_TASK_ID.toString()).first)
        assertNull(archived.getValue(Seeder.TODO_TASK_ID.toString()).second)

        // nothing was deleted on the way: the archive keeps the whole history
        assertEquals(2L, db.firstLong("SELECT COUNT(*) FROM tasks"))
        assertEquals("0", db.firstCell("SELECT is_deleted FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}"))
    }

    @Test
    fun `migration 6 to 7 creates the topic group table for the active user`() {
        val db = open(6)
        db.migrate(AppMigrations.MIGRATION_6_7)
        db.exec(
            "INSERT INTO task_groups (uuid, user_id, name, collapsed, sort_order, created_at, updated_at) " +
                "VALUES ('group-1', ${Seeder.USER_ID}, 'РАБОТА', 0, 0, 1, 1)"
        )
        db.exec("UPDATE tasks SET group_id = 'group-1' WHERE id = ${Seeder.COMPLETED_TASK_ID}")
        assertEquals(
            "group-1",
            db.firstCell("SELECT group_id FROM tasks WHERE id = ${Seeder.COMPLETED_TASK_ID}")
        )
    }

    @Test
    fun `legacy preferences table does not break the upgrade`() {
        val db = open(1)
        assertTrue(db.tableNames().contains("preferences"))
        AppMigrations.ALL.forEach { db.migrate(it) }
        builder.assertMatchesSchema(db, latestVersion, "chain with preferences present")
    }

    @Test
    fun `migrations are idempotently registered for every retained version pair`() {
        val pairs = AppMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9),
            pairs
        )
    }

    private val latestVersion: Int = AppMigrations.ALL.maxOf { it.endVersion }

    @Test
    fun `migration 7 to 8 leaves existing tasks without a series`() {
        val db = open(7)
        val before = counts(db)
        db.migrate(AppMigrations.MIGRATION_7_8)
        builder.assertMatchesSchema(db, 8, "MIGRATION_7_8")

        assertEquals(before, counts(db))
        assertEquals(
            listOf(null to null),
            db.rows("SELECT series_uuid, occurrence_at FROM tasks WHERE id = ${Seeder.TODO_TASK_ID}")
                .map { it[0] to it[1] }
        )
    }

    @Test
    fun `migration 7 to 8 does not treat rows without a series as duplicates of each other`() {
        val db = open(7)
        db.migrate(AppMigrations.MIGRATION_7_8)

        db.exec(
            "INSERT INTO tasks (title, description, priority, status, user_id, `order`, created_at, updated_at) " +
                "VALUES ('plain a', '', 'MEDIUM', 'TODO', ${Seeder.USER_ID}, 0, 1, 1), " +
                "('plain b', '', 'MEDIUM', 'TODO', ${Seeder.USER_ID}, 1, 1, 1)"
        )

        assertEquals("plain tasks keep being insertable", 4L, db.firstLong("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `migration 7 to 8 refuses a second instance of the same occurrence`() {
        val db = open(7)
        db.migrate(AppMigrations.MIGRATION_7_8)

        var rejected = false
        try {
            insertSeriesTask(db, "instance 1")
            insertSeriesTask(db, "instance 2")
        } catch (e: Exception) {
            rejected = true
        }

        assertTrue("the unique (series, occurrence) index must reject a duplicate instance", rejected)
        assertEquals(1L, db.firstLong("SELECT COUNT(*) FROM tasks WHERE series_uuid = 'series-1'"))
        assertEquals(3L, db.firstLong("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `migration 7 to 8 creates the rule table the repository writes to`() {
        val db = open(7)
        db.migrate(AppMigrations.MIGRATION_7_8)

        db.exec(
            "INSERT INTO recurrence_rules (uuid, user_id, unit, `interval`, week_days, day_of_month, " +
                "anchor_at, zone_id, cursor_at, created_at, updated_at) " +
                "VALUES ('series-1', ${Seeder.USER_ID}, 'WEEK', 2, '1,3,5', 0, 1000, 'Europe/Berlin', 1000, 1, 1)"
        )

        val row = db.rows(
            "SELECT unit, `interval`, week_days, zone_id, paused, sync_state FROM recurrence_rules"
        ).single()
        assertEquals(listOf("WEEK", "2", "1,3,5", "Europe/Berlin", "0", "PENDING"), row)
    }

    @Test
    fun `migration 7 to 8 refuses a rule for a profile that does not exist`() {
        val db = open(7)
        db.migrate(AppMigrations.MIGRATION_7_8)
        db.exec("PRAGMA foreign_keys = ON")

        var rejected = false
        try {
            db.exec(
                "INSERT INTO recurrence_rules (uuid, user_id, unit, `interval`, week_days, day_of_month, " +
                    "anchor_at, zone_id, cursor_at, created_at, updated_at) " +
                    "VALUES ('ghost', 999, 'DAY', 1, '', 0, 1, '', 1, 1, 1)"
            )
        } catch (e: Exception) {
            rejected = true
        }

        assertTrue("a series must belong to a real profile", rejected)
    }

    @Test
    fun `migration 8 to 9 gives an existing database no reminders and no changed rows`() {
        val db = open(8)
        val before = counts(db)
        db.migrate(AppMigrations.MIGRATION_8_9)
        builder.assertMatchesSchema(db, 9, "MIGRATION_8_9")

        assertEquals(before, counts(db))
        assertEquals("upgrading adds the feature, it does not invent reminders",
            0L, db.firstLong("SELECT COUNT(*) FROM reminders"))
    }

    @Test
    fun `migration 8 to 9 refuses the same lead time twice on one task`() {
        val db = open(8)
        db.migrate(AppMigrations.MIGRATION_8_9)

        insertReminder(db, "reminder-a", 15)
        var rejected = false
        try {
            insertReminder(db, "reminder-b", 15)
        } catch (e: Exception) {
            rejected = true
        }

        assertTrue("a reminder is (task, offset): a second copy of the same minute must not exist", rejected)
        insertReminder(db, "reminder-c", 60)
        assertEquals(2L, db.firstLong("SELECT COUNT(*) FROM reminders"))
    }

    @Test
    fun `migration 8 to 9 leaves a new reminder pending and alive`() {
        val db = open(8)
        db.migrate(AppMigrations.MIGRATION_8_9)
        db.exec(
            "INSERT INTO reminders (uuid, task_uuid, user_id, trigger_at, anchor_at, created_at, updated_at) " +
                "VALUES ('reminder-d', 'task-1', ${Seeder.USER_ID}, 1000, 1000, 1, 1)"
        )

        // The offset column carries the "at the deadline itself" default, so a plain reminder row is
        // an insert without one, and a row that is neither deleted nor already notified is what the
        // sweep looks for.
        assertEquals(listOf("0", "PENDING", "0", null), db.rows(
            "SELECT offset_minutes, sync_state, is_deleted, notified_at FROM reminders"
        ).single())
    }

    private fun insertReminder(db: SqliteTestDatabase, uuid: String, offsetMinutes: Int) {
        db.exec(
            "INSERT INTO reminders (uuid, task_uuid, user_id, offset_minutes, trigger_at, anchor_at, " +
                "created_at, updated_at) VALUES ('$uuid', 'task-1', ${Seeder.USER_ID}, $offsetMinutes, " +
                "2000 - $offsetMinutes * 60000, 2000, 1, 1)"
        )
    }

    private fun insertSeriesTask(db: SqliteTestDatabase, title: String) {
        db.exec(
            "INSERT INTO tasks (title, description, priority, status, user_id, `order`, created_at, " +
                "updated_at, uuid, series_uuid, occurrence_at) VALUES " +
                "('$title', '', 'MEDIUM', 'TODO', ${Seeder.USER_ID}, 0, 1, 1, 'uuid-$title', 'series-1', 1000)"
        )
    }

    private fun counts(db: SqliteTestDatabase): Map<String, Long> =
        listOf("tasks", "categories", "users").associateWith {
            db.firstLong("SELECT COUNT(*) FROM $it")
        }

    private fun step(migration: Migration) = "${migration.startVersion}_${migration.endVersion}"
}
