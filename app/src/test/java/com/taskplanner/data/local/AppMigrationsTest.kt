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
            listOf(
                1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9, 9 to 10, 10 to 11,
                11 to 12, 12 to 13, 13 to 14
            ),
            pairs
        )
    }

    @Test
    fun `migration 10 to 11 adds only the indices the calendar window reads through`() {
        val db = open(10)
        val before = counts(db)
        val beforeValues = db.rows(
            "SELECT id, title, due_date, start_time, end_time FROM tasks ORDER BY id"
        )
        db.migrate(AppMigrations.MIGRATION_10_11)
        builder.assertMatchesSchema(db, 11, "MIGRATION_10_11")

        assertEquals("an index migration moves no row", before, counts(db))
        assertEquals(
            beforeValues,
            db.rows("SELECT id, title, due_date, start_time, end_time FROM tasks ORDER BY id")
        )
        mapOf(
            "index_tasks_user_id_start_time" to listOf("user_id", "start_time"),
            "index_tasks_user_id_end_time_start_time" to listOf("user_id", "end_time", "start_time")
        ).forEach { (index, columns) ->
            assertEquals(
                "$index has to answer the window's lookup for that column",
                columns,
                db.rows("PRAGMA index_info('$index')").map { it[2] }
            )
        }
    }

    @Test
    fun `migration 10 to 11 applied to a database that already has the indices changes nothing`() {
        val db = open(10)
        db.migrate(AppMigrations.MIGRATION_10_11)
        val indexes = db.rows("PRAGMA index_list('tasks')").size
        db.migrate(AppMigrations.MIGRATION_10_11)

        assertEquals(indexes, db.rows("PRAGMA index_list('tasks')").size)
        builder.assertMatchesSchema(db, 11, "MIGRATION_10_11 applied twice")
    }

    private val latestVersion: Int = AppMigrations.ALL.maxOf { it.endVersion }

    @Test
    fun `migration 11 to 12 adds completed_at without touching a stored value`() {
        val db = open(11)
        val before = counts(db)
        val beforeValues = db.rows(
            "SELECT id, title, status, started_at, archived_at, updated_at FROM tasks ORDER BY id"
        )
        db.migrate(AppMigrations.MIGRATION_11_12)
        builder.assertMatchesSchema(db, 12, "MIGRATION_11_12")

        assertEquals("adding a column moves no row", before, counts(db))
        assertEquals(beforeValues, db.rows(
            "SELECT id, title, status, started_at, archived_at, updated_at FROM tasks ORDER BY id"
        ))
        // A task an older build finished was completed — but when exactly was never recorded, and
        // filling the gap from updated_at or archived_at would put a guessed instant into the column
        // whose only purpose is to be believed.
        assertEquals(
            listOf(null, null),
            db.rows("SELECT completed_at FROM tasks ORDER BY id").map { it[0] }
        )
        // The column is nullable, so the states a task can already be in stay writable.
        db.exec(
            "INSERT INTO tasks (title, description, priority, status, user_id, `order`, created_at, " +
                "updated_at) VALUES ('later', '', 'MEDIUM', 'TODO', ${Seeder.USER_ID}, 0, 1, 1)"
        )
        assertEquals(3L, db.firstLong("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `migration 11 to 12 creates the work tables with the links the history needs`() {
        val db = open(11)
        db.migrate(AppMigrations.MIGRATION_11_12)

        assertTrue(
            "sessions, intervals and their correction audit, and nothing else",
            db.tableNames().containsAll(setOf("tracking_sessions", "work_sessions", "work_interval_corrections"))
        )
        assertEquals(
            "one measured interval per profile can be open at a time",
            listOf("user_id", "open_marker"),
            db.rows("PRAGMA index_info('index_work_sessions_user_id_open_marker')").map { it[2] }
        )
        assertEquals(
            "and one session per profile can be running at a time",
            listOf("running_marker"),
            db.rows("PRAGMA index_info('index_tracking_sessions_running_marker')").map { it[2] }
        )
        assertTrue(
            "the unique index on the marker is what makes those two database rules, not UI courtesy",
            db.rows("PRAGMA index_list('work_sessions')").any { it[2] == "1" && it[1] == "index_work_sessions_user_id_open_marker" }
        )
        assertEquals(
            "work history belongs to a profile; dropping the profile drops it, dropping a task does not",
            listOf("users"),
            db.rows("PRAGMA foreign_key_list('work_sessions')").map { it[2] }.distinct()
        )
        assertEquals(
            "nothing points at tasks, so no ON DELETE CASCADE can take a measurement with it",
            emptyList<String>(),
            db.rows("PRAGMA foreign_key_list('work_sessions')")
                .map { it[2]!! }.filter { it == "tasks" }
        )
    }

    @Test
    fun `migration 11 to 12 rejects a second open interval for the same profile at the database level`() {
        val db = open(12)
        db.exec(session("s1"))
        db.exec(interval("s1", "i1", openMarker = "s1"))

        // Not "the second tap found nothing running" — the write itself is refused, which is the only
        // thing that also covers a second screen, a replayed import and two threads at once.
        val rejected = runCatching { db.exec(interval("s1", "i2", openMarker = "s1")) }
        assertTrue("a second open interval must not be insertable", rejected.isFailure)
        assertTrue(
            "and it fails on the constraint, not on something incidental",
            rejected.exceptionOrNull()?.message?.contains("UNIQUE") == true
        )
        assertEquals(1L, db.firstLong("SELECT COUNT(*) FROM work_sessions WHERE open_marker IS NOT NULL"))

        // Another profile keeps its own timer: the marker is unique per profile, not per device.
        val other = Seeder.USER_ID + 1
        db.exec(otherUser(other))
        db.exec(session("s2", userId = other))
        assertEquals(
            "a second profile must not be locked out by the first one's timer",
            0,
            runCatching { db.exec(interval("s2", "i3", openMarker = "s2", userId = other)) }
                .fold({ 0 }, { 1 })
        )

        // Closing the first interval frees the slot, and closed rows never collide however many the
        // user accumulates — SQLite keeps NULLs distinct inside a unique index.
        db.exec("UPDATE work_sessions SET open_marker = NULL WHERE uuid = 'i1'")
        db.exec(interval("s1", "i4", openMarker = "s1"))
        db.exec(interval("s4", "i5", openMarker = null))
        db.exec(interval("s4", "i6", openMarker = null))
        assertEquals(3L, db.firstLong("SELECT COUNT(*) FROM work_sessions WHERE open_marker IS NULL"))
    }

    @Test
    fun `migration 11 to 12 keeps measured time after the task it was measured on is gone`() {
        val db = open(12)
        db.exec(session("s1", taskId = Seeder.TODO_TASK_ID))
        db.exec(interval("s1", "i1", openMarker = null, taskId = Seeder.TODO_TASK_ID))
        db.exec("UPDATE work_sessions SET ended_at = 2, duration_seconds = 60 WHERE uuid = 'i1'")

        db.exec("DELETE FROM tasks WHERE id = ${Seeder.TODO_TASK_ID}")

        assertEquals(
            "the interval outlives the card it was started from",
            1L,
            db.firstLong("SELECT COUNT(*) FROM work_sessions")
        )
        assertEquals(
            "and the history still says what it was work on, from the title the session saved",
            "Buy milk",
            db.firstCell("SELECT task_title FROM tracking_sessions WHERE uuid = 's1'")
        )
    }

    @Test
    fun `a schema built by the migration stores one session as two intervals and sums them`() {
        val db = open(11)
        db.migrate(AppMigrations.MIGRATION_11_12)

        // Start 09:00, pause 09:30, resume 10:00, stop 10:45: the break in between is not a row, and
        // the total the screens show is the sum of the two that are.
        db.exec(session("s1"))
        db.exec(interval("s1", "i1", openMarker = "s1", startedAt = 0L))
        db.exec("UPDATE work_sessions SET ended_at = 1800, duration_seconds = 1800, open_marker = NULL WHERE uuid = 'i1'")
        db.exec(interval("s1", "i2", openMarker = "s1", startedAt = 3600L))
        db.exec("UPDATE work_sessions SET ended_at = 6300, duration_seconds = 2700, open_marker = NULL WHERE uuid = 'i2'")

        assertEquals(
            "75 measured minutes, with the 30-minute pause counted out",
            4500L,
            db.firstLong(
                "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
                    "WHERE tracking_session_uuid = 's1' AND ended_at IS NOT NULL"
            )
        )
        assertEquals(
            "the gap is not a row",
            2L,
            db.firstLong("SELECT COUNT(*) FROM work_sessions")
        )
        assertEquals(
            "and nothing is left open",
            0L,
            db.firstLong("SELECT COUNT(*) FROM work_sessions WHERE open_marker IS NOT NULL")
        )
    }

    @Test
    fun `migration 12 to 13 adds only the index the dashboard reads completed work through`() {
        val db = open(12)
        val before = counts(db)
        val beforeValues = db.rows(
            "SELECT id, title, status, user_id, is_deleted, completed_at FROM tasks ORDER BY id"
        )
        db.migrate(AppMigrations.MIGRATION_12_13)
        builder.assertMatchesSchema(db, 13, "MIGRATION_12_13")

        assertEquals("an index migration moves no row", before, counts(db))
        assertEquals(
            "an index migration changes no stored value",
            beforeValues,
            db.rows("SELECT id, title, status, user_id, is_deleted, completed_at FROM tasks ORDER BY id")
        )
        assertEquals(
            listOf("user_id", "completed_at"),
            db.rows("PRAGMA index_info('index_tasks_user_id_completed_at')").map { it[2] }
        )
    }

    @Test
    fun `migration 12 to 13 applied to a database that already has the index changes nothing`() {
        val db = open(12)
        db.migrate(AppMigrations.MIGRATION_12_13)
        val indexes = db.rows("PRAGMA index_list('tasks')").size
        db.migrate(AppMigrations.MIGRATION_12_13)

        assertEquals(indexes, db.rows("PRAGMA index_list('tasks')").size)
        builder.assertMatchesSchema(db, 13, "MIGRATION_12_13 applied twice")
    }

    @Test
    fun `the completion window seeks the new index instead of walking the profile`() {
        val db = open(12)
        db.migrate(AppMigrations.MIGRATION_12_13)
        val plan = db.rows(
            "EXPLAIN QUERY PLAN SELECT completed_at FROM tasks WHERE user_id = ${Seeder.USER_ID} " +
                "AND is_deleted = 0 AND status IN ('COMPLETED', 'DONE') " +
                "AND completed_at IS NOT NULL AND completed_at >= 100 AND completed_at < 200"
        ).flatten().joinToString(" ")

        assertTrue(
            "the window has to be answered by seeking the new index; the plan was: $plan",
            plan.contains("index_tasks_user_id_completed_at")
        )
    }

    @Test
    fun `a database built at 13 finds the tasks finished inside a window`() {
        val db = open(12)
        db.migrate(AppMigrations.MIGRATION_12_13)
        db.exec(
            "UPDATE tasks SET completed_at = 150, status = 'COMPLETED' " +
                "WHERE id = ${Seeder.COMPLETED_TASK_ID}"
        )

        // Exactly the statement the dashboard's completion series runs, against the schema the
        // migration produces rather than against Room's generated code: what has to hold is that a
        // window of instants selects the tasks finished inside it, that one finished outside it is
        // not in the result, and that an unfinished task cannot be there — its `completed_at` is NULL.
        assertEquals(
            listOf(Seeder.COMPLETED_TASK_ID.toString()),
            db.rows(
                "SELECT id FROM tasks WHERE user_id = ${Seeder.USER_ID} AND is_deleted = 0 " +
                    "AND status IN ('COMPLETED', 'DONE') AND completed_at IS NOT NULL " +
                    "AND completed_at >= 100 AND completed_at < 200"
            ).map { it[0] }
        )
        assertEquals(
            "an unfinished task has no completion instant to be found by",
            0L,
            db.firstLong(
                "SELECT COUNT(*) FROM tasks WHERE user_id = ${Seeder.USER_ID} AND is_deleted = 0 " +
                    "AND completed_at IS NOT NULL AND completed_at >= 1000 AND completed_at < 2000"
            )
        )
    }

    @Test
    fun `migration 13 to 14 adds goals and habits and rewrites no stored value`() {
        val db = open(13)
        val before = counts(db)
        val beforeTasks = db.rows(
            "SELECT id, title, status, user_id, is_deleted, completed_at FROM tasks ORDER BY id"
        )
        db.migrate(AppMigrations.MIGRATION_13_14)
        builder.assertMatchesSchema(db, 14, "MIGRATION_13_14")

        assertEquals("adding six tables moves no row", before, counts(db))
        assertEquals(
            "and changes no value in the work the new features count",
            beforeTasks,
            db.rows("SELECT id, title, status, user_id, is_deleted, completed_at FROM tasks ORDER BY id")
        )
        assertTrue(
            "goals, habits, and the history rows of each — nothing else arrives",
            db.tableNames().containsAll(
                setOf(
                    "goals", "goal_tasks", "goal_value_changes",
                    "habits", "habit_checks", "habit_check_changes"
                )
            )
        )
        // An upgrade cannot invent an aim or a schedule the user never set, and it has to invent no
        // progress either: the figures are computed from the task and interval rows that are already
        // here, which is why this step reads and writes none of them.
        assertEquals(0L, db.firstLong("SELECT COUNT(*) FROM goals"))
        assertEquals(0L, db.firstLong("SELECT COUNT(*) FROM habits"))
        assertEquals(0L, db.firstLong("SELECT COUNT(*) FROM habit_checks"))
    }

    @Test
    fun `migration 13 to 14 applies twice without adding a duplicate index`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        val marks = db.rows("PRAGMA index_list('habit_checks')").size
        db.migrate(AppMigrations.MIGRATION_13_14)

        assertEquals(marks, db.rows("PRAGMA index_list('habit_checks')").size)
        builder.assertMatchesSchema(db, 14, "MIGRATION_13_14 applied twice")
    }

    @Test
    fun `migration 13 to 14 makes one live mark per habit per day a database rule`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        db.exec(habit("h1"))
        db.exec(check("h1", "c1", day = 20_000L))

        val rejected = runCatching { db.exec(check("h1", "c2", day = 20_000L)) }
        assertTrue("a day marked twice must not be insertable", rejected.isFailure)
        assertTrue(
            "and it fails on the marker's unique index, not incidentally",
            rejected.exceptionOrNull()?.message?.contains("UNIQUE") == true
        )

        // Taking the day back nulls the marker, so the slot is free again — and the retired row keeps
        // its own local_day, which is what lets the correction history say when the mark stood.
        db.exec("UPDATE habit_checks SET day_marker = NULL, is_deleted = 1 WHERE uuid = 'c1'")
        db.exec(check("h1", "c2", day = 20_000L))
        assertEquals("20000", db.firstCell("SELECT local_day FROM habit_checks WHERE uuid = 'c1'"))
        assertNull(db.firstCell("SELECT day_marker FROM habit_checks WHERE uuid = 'c1'"))

        // Two habits can be kept on the same day, because the rule is per habit rather than per day.
        db.exec(habit("h2", title = "Read"))
        db.exec(check("h2", "c3", day = 20_000L))
        assertEquals(
            listOf("c1", "c2", "c3"),
            db.rows("SELECT uuid FROM habit_checks ORDER BY uuid").map { it[0] }
        )
        assertEquals(
            "only the live marks count",
            2L,
            db.firstLong("SELECT COUNT(*) FROM habit_checks WHERE day_marker IS NOT NULL AND is_deleted = 0")
        )
    }

    @Test
    fun `migration 13 to 14 links a goal to a task it does not own and keeps the link after the task is gone`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        nameTasks(db)
        db.exec(goal("g1", type = "TASK_SET", target = 5))

        // A link to a task that has not arrived yet is normal: an import or a sync pull can deliver the
        // goal before the card, and nothing points at `tasks` to refuse it.
        db.exec(link("g1", "task-not-here-yet", addedAt = 1L))
        db.exec(link("g1", "task-completed", addedAt = 2L))
        assertEquals(2L, db.firstLong("SELECT COUNT(*) FROM goal_tasks WHERE goal_uuid = 'g1'"))

        // The same task named twice is the same request: the composite key refuses the second link, and
        // a denominator that counted one piece of work twice would be a goal that can never be reached.
        assertTrue(runCatching { db.exec(link("g1", "task-completed", addedAt = 3L)) }.isFailure)
        assertEquals(2L, db.firstLong("SELECT COUNT(*) FROM goal_tasks WHERE goal_uuid = 'g1'"))

        db.exec("DELETE FROM tasks WHERE uuid = 'task-todo'")
        assertEquals(
            "deleting a task takes no goal and no link with it",
            2L,
            db.firstLong("SELECT COUNT(*) FROM goal_tasks WHERE goal_uuid = 'g1'")
        )
        assertEquals(1L, db.firstLong("SELECT COUNT(*) FROM goals WHERE uuid = 'g1'"))
    }

    @Test
    fun `a goal created after the upgrade counts work recorded before it`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        nameTasks(db)
        db.exec(goal("g1", type = "TASK_SET", target = 2))
        db.exec(link("g1", "task-completed", addedAt = 1L))
        db.exec(link("g1", "task-todo", addedAt = 2L))

        // Exactly the statement the goal screen runs, against the schema the migration produces rather
        // than against Room's generated code: the denominator counts both links, the numerator only the
        // task that is actually finished, and the row that no longer exists would be reported as
        // missing rather than dropped from the figure.
        val progress = "SELECT COUNT(*), " +
            "COALESCE(SUM(CASE WHEN t.is_deleted = 0 AND UPPER(t.status) IN ('COMPLETED', 'DONE') " +
            "THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN t.uuid IS NULL THEN 1 ELSE 0 END), 0) " +
            "FROM goal_tasks gt LEFT JOIN tasks t ON t.uuid = gt.task_uuid AND t.user_id = gt.user_id " +
            "WHERE gt.goal_uuid = 'g1' AND gt.user_id = ${Seeder.USER_ID}"
        assertEquals(listOf("2", "1", "0"), db.rows(progress).single())

        db.exec("DELETE FROM tasks WHERE uuid = 'task-todo'")
        assertEquals(
            "and after the second task is gone the goal still asks for two, one of them finished",
            listOf("2", "1", "1"),
            db.rows(progress).single()
        )
    }

    @Test
    fun `a habit created after the upgrade can be marked on a day it predates`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        // A measured interval from before the habit existed, which is the work a TIME_SPENT-shaped
        // figure and a backdated mark both reach into.
        db.exec(session("s1"))
        db.exec(interval("s1", "i1", openMarker = null))
        db.exec("UPDATE work_sessions SET ended_at = 2, local_day = 20000, duration_seconds = 1800 WHERE uuid = 'i1'")

        db.exec(habit("h1", startDay = 19_000L))
        db.exec(check("h1", "c1", day = 19_500L))

        assertEquals(
            "the mark needs no row in any new table other than its own",
            listOf("19500"),
            db.rows(
                "SELECT local_day FROM habit_checks WHERE habit_uuid = 'h1' " +
                    "AND user_id = ${Seeder.USER_ID} AND day_marker IS NOT NULL AND is_deleted = 0"
            ).map { it[0] }
        )
        assertEquals(
            "and the seconds a habit is asked for are already measurable in the old table",
            1800L,
            db.firstLong(
                "SELECT COALESCE(SUM(duration_seconds), 0) FROM work_sessions " +
                    "WHERE user_id = ${Seeder.USER_ID} AND local_day = 20000 AND is_deleted = 0"
            )
        )
    }

    @Test
    fun `migration 13 to 14 gives every new row the profile it belongs to and nothing else`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        db.exec("PRAGMA foreign_keys = ON")

        mapOf(
            "goals" to "INSERT INTO goals (uuid, user_id, title, type, target_value, created_at, updated_at) " +
                "VALUES ('ghost-goal', 999, 'Nothing', 'TASK_COUNT', 5, 1, 1)",
            "goal_tasks" to "INSERT INTO goal_tasks (goal_uuid, task_uuid, user_id, created_at, added_at) " +
                "VALUES ('g1', 't1', 999, 1, 1)",
            "goal_value_changes" to "INSERT INTO goal_value_changes " +
                "(uuid, goal_uuid, user_id, field, created_at, updated_at) VALUES ('v1', 'g1', 999, 'target', 1, 1)",
            "habits" to "INSERT INTO habits (uuid, user_id, title, frequency, start_day, created_at, updated_at) " +
                "VALUES ('ghost-habit', 999, 'Run', 'DAILY', 1, 1, 1)",
            "habit_checks" to "INSERT INTO habit_checks " +
                "(uuid, habit_uuid, user_id, local_day, utc_offset_minutes, created_at, updated_at) " +
                "VALUES ('c1', 'h1', 999, 20000, 60, 1, 1)",
            "habit_check_changes" to "INSERT INTO habit_check_changes " +
                "(uuid, check_uuid, habit_uuid, user_id, old_local_day, created_at, updated_at) " +
                "VALUES ('k1', 'c1', 'h1', 999, 20000, 1, 1)"
        ).forEach { (table, sql) ->
            val rejected = runCatching { db.exec(sql) }
            assertTrue("$table must refuse a profile that does not exist", rejected.isFailure)
        }
        assertEquals(
            "so no query of a real profile can be reached by a row of an imaginary one",
            0L,
            db.firstLong("SELECT COUNT(*) FROM habits") + db.firstLong("SELECT COUNT(*) FROM goals")
        )
    }

    @Test
    fun `migration 13 to 14 leaves the sweep's candidate read seekable by profile`() {
        val db = open(13)
        db.migrate(AppMigrations.MIGRATION_13_14)
        repeat(20) { index -> db.exec(habit("h$index")) }

        assertEquals(
            listOf("user_id", "remind_minute_of_day"),
            db.rows("PRAGMA index_info('index_habits_user_id_remind_minute_of_day')").map { it[2] }
        )
        val plan = db.rows(
            "EXPLAIN QUERY PLAN SELECT uuid FROM habits WHERE user_id = ${Seeder.USER_ID} " +
                "AND is_deleted = 0 AND archived_at IS NULL AND remind_minute_of_day IS NOT NULL " +
                "AND (reminded_on_day IS NULL OR reminded_on_day != 20000)"
        ).flatten().joinToString(" ")
        assertTrue(
            "the sweep's one question per profile must be answered by an index, not by walking the " +
                "table; the plan was: $plan",
            !Regex("SCAN habits\\b").containsMatchIn(plan)
        )
    }

    private fun habit(
        uuid: String,
        title: String = "Run",
        startDay: Long = 19_000L,
        userId: Long = Seeder.USER_ID
    ) = "INSERT INTO habits (uuid, user_id, title, frequency, days_mask, start_day, remind_minute_of_day, " +
        "created_at, updated_at) VALUES ('$uuid', $userId, '$title', 'DAILY', 0, $startDay, 420, 1, 1)"

    private fun check(
        habitUuid: String,
        uuid: String,
        day: Long,
        userId: Long = Seeder.USER_ID
    ) = "INSERT INTO habit_checks (uuid, habit_uuid, user_id, local_day, utc_offset_minutes, day_marker, " +
        "created_at, updated_at) VALUES ('$uuid', '$habitUuid', $userId, $day, 60, $day, 1, 1)"

    private fun goal(uuid: String, type: String, target: Long, userId: Long = Seeder.USER_ID) =
        "INSERT INTO goals (uuid, user_id, title, type, target_value, created_at, updated_at) VALUES " +
            "('$uuid', $userId, 'Aim', '$type', $target, 1, 1)"

    private fun link(goalUuid: String, taskUuid: String, addedAt: Long, userId: Long = Seeder.USER_ID) =
        "INSERT INTO goal_tasks (goal_uuid, task_uuid, user_id, created_at, added_at) VALUES " +
            "('$goalUuid', '$taskUuid', $userId, 1, $addedAt)"

    /**
     * Gives the two seeded tasks the uuids the goal links name.
     *
     * The seeder writes rows the way an upgrade leaves them — the uuid column carries its default — so a
     * test that links a goal to a task has to name it first, exactly as migration 6 to 7 did for a
     * database that came from before uuids existed.
     */
    private fun nameTasks(db: SqliteTestDatabase) {
        db.exec("UPDATE tasks SET uuid = 'task-completed' WHERE id = ${Seeder.COMPLETED_TASK_ID}")
        db.exec("UPDATE tasks SET uuid = 'task-todo' WHERE id = ${Seeder.TODO_TASK_ID}")
    }

    private fun otherUser(id: Long) =
        "INSERT INTO users (id, email, name, avatar_color, avatar_initial, is_current, last_used, " +
            "created_at, updated_at) VALUES ($id, 'other@example.com', 'Other', -1, 'O', 0, 1, 1, 1)"

    private fun session(uuid: String, taskId: Long = Seeder.TODO_TASK_ID, userId: Long = Seeder.USER_ID) =
        "INSERT INTO tracking_sessions (uuid, user_id, task_id, task_uuid, task_title, state, " +
            "started_at, zone_id, running_marker, created_at, updated_at) VALUES " +
            "('$uuid', $userId, $taskId, 'task-uuid-$taskId', 'Buy milk', 'RUNNING', 1, 'Europe/Berlin', " +
            "'$userId', 1, 1)"

    private fun interval(
        sessionUuid: String,
        uuid: String,
        openMarker: String?,
        taskId: Long = Seeder.TODO_TASK_ID,
        userId: Long = Seeder.USER_ID,
        startedAt: Long = 1L
    ) = "INSERT INTO work_sessions (uuid, user_id, tracking_session_uuid, task_id, task_uuid, started_at, " +
        "local_day, utc_offset_minutes, source, open_marker, created_at, updated_at) VALUES " +
        "('$uuid', $userId, '$sessionUuid', $taskId, 'task-uuid-$taskId', $startedAt, 20000, 60, 'TRACKER', " +
        (if (openMarker == null) "NULL" else "'$openMarker'") + ", 1, 1)"

    @Test
    fun `migration 9 to 10 adds the planned times without touching a single stored value`() {
        val db = open(9)
        val before = counts(db)
        val beforeValues = db.rows("SELECT id, title, due_date FROM tasks ORDER BY id")
        db.migrate(AppMigrations.MIGRATION_9_10)
        builder.assertMatchesSchema(db, 10, "MIGRATION_9_10")

        assertEquals(before, counts(db))
        assertEquals("adding the calendar columns rewrites no row", beforeValues,
            db.rows("SELECT id, title, due_date FROM tasks ORDER BY id"))
        // A task from before the calendar existed is scheduled by its deadline alone, which is
        // exactly what three nulls mean; inventing a start or a length here would be a lie about
        // data the user never gave.
        assertEquals(
            listOf(null to null to null),
            db.rows(
                "SELECT start_time, end_time, estimated_minutes FROM tasks " +
                    "WHERE id = ${Seeder.TODO_TASK_ID}"
            ).map { it[0] to it[1] to it[2] }
        )
    }

    @Test
    fun `migration 9 to 10 lets an upgraded task carry a start an end and an estimate`() {
        val db = open(9)
        db.migrate(AppMigrations.MIGRATION_9_10)

        db.exec(
            "UPDATE tasks SET start_time = 1700000000000, end_time = 1700001800000, " +
                "estimated_minutes = 30 WHERE id = ${Seeder.TODO_TASK_ID}"
        )

        assertEquals(
            listOf("1700000000000", "1700001800000", "30"),
            db.rows(
                "SELECT start_time, end_time, estimated_minutes FROM tasks " +
                    "WHERE id = ${Seeder.TODO_TASK_ID}"
            ).single()
        )
        // The new columns are nullable, so the state the user came from stays writable.
        db.exec(
            "INSERT INTO tasks (title, description, priority, status, user_id, `order`, created_at, updated_at) " +
                "VALUES ('no times', '', 'MEDIUM', 'TODO', ${Seeder.USER_ID}, 0, 1, 1)"
        )
        assertEquals(3L, db.firstLong("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `migration 9 to 10 creates the index the calendar window query reads through`() {
        val db = open(9)
        val before = db.rows("PRAGMA index_list('tasks')").size
        db.migrate(AppMigrations.MIGRATION_9_10)

        val indexes = db.rows("PRAGMA index_list('tasks')")
        assertEquals("one index is added, none is lost", before + 1, indexes.size)
        val window = db.rows("PRAGMA index_info('index_tasks_user_id_due_date')").map { it[2] }
        assertEquals(
            "the calendar filters by profile and sorts by deadline",
            listOf("user_id", "due_date"),
            window
        )
    }

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
