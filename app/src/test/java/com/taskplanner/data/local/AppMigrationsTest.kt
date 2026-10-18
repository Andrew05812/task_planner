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
    fun `full history chain 1 to 7 produces the current schema`() {
        val db = open(1)
        AppMigrations.ALL.forEach { db.migrate(it) }
        builder.assertMatchesSchema(db, 7, "full chain 1->7")
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
        builder.assertMatchesSchema(db, 7, "chain with preferences present")
    }

    @Test
    fun `migrations are idempotently registered for every retained version pair`() {
        val pairs = AppMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7),
            pairs
        )
    }

    private fun counts(db: SqliteTestDatabase): Map<String, Long> =
        listOf("tasks", "categories", "users").associateWith {
            db.firstLong("SELECT COUNT(*) FROM $it")
        }

    private fun step(migration: Migration) = "${migration.startVersion}_${migration.endVersion}"
}
