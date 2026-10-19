package com.taskplanner.data.calendar

import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.local.WINDOW_QUERY
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.testing.SqliteTestDatabase
import com.taskplanner.testing.TestSchemas
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures what the calendar costs on accounts of the size the brief names — 1k, 5k and 10k tasks —
 * on a real SQLite engine, with the statement the DAO ships.
 *
 * Three things are proven here rather than claimed in a comment.
 *
 * **The window reads what is in it.** The rows a month returns do not grow with the size of the
 * account, and they are exactly the rows the window's predicates say they are: computed in Kotlin from
 * the seeded values, and cross-checked against the shape this query replaced. A month is worth
 * measuring only if it is the *right* month.
 *
 * **The planner seeks instead of walking.** `EXPLAIN QUERY PLAN` is read, not imagined: no step may
 * walk the `tasks` table, and each of the three columns that bound a window must be used as an index
 * range. That is the difference between a cost set by the window and a cost set by everything the user
 * has ever written — the difference this measurement found and fixed.
 *
 * **The screen's own numbers.** Month, week and day each get their real grid, their real read and
 * their real aggregation, timed at every size and printed for the report, under ceilings loose enough
 * for a loaded shared runner and tight enough to catch the read degrading back to a profile walk.
 *
 * The engine is the family Android ships (org.xerial sqlite-jdbc) and the schema is the one Room
 * exported, so this is the production structure running the production SQL, not a sketch of either.
 */
class CalendarWindowBenchmarkTest {

    /** A seeded account: the database, the rows that made it, and the column layout to read back. */
    private class Profile(
        val db: SqliteTestDatabase,
        val tasks: List<Task>,
        val categories: List<Category>,
        val columnOf: Map<String, Int>
    )

    private val opened = mutableListOf<SqliteTestDatabase>()

    @After
    fun closeDatabases() {
        opened.forEach { runCatching(it::close) }
        opened.clear()
    }

    @Test
    fun `the window returns exactly the tasks that fall inside it`() {
        SIZES.forEach { size ->
            val profile = profile(size)
            val expected = expectedInWindow(profile, WINDOW.first, WINDOW.to)
            val found = readWindow(profile, WINDOW.first, WINDOW.to).map { it.id }.toSet()
            assertEquals("$size-row account: the window holds its own rows and no others", expected, found)
            assertTrue("$size-row account offered nothing to the window", found.isNotEmpty())
        }
    }

    @Test
    fun `the number of tasks a month finds does not grow with the account`() {
        val counts = SIZES.map { size ->
            val profile = profile(size)
            readWindow(profile, WINDOW.first, WINDOW.to).size
        }
        // Every account holds the same tasks inside this month and a different number outside it, so
        // an identical count is what a read bounded by its window looks like.
        assertEquals(
            "a month read the same number of rows on a 1k, 5k and 10k account: $counts",
            setOf(counts.first()),
            counts.toSet()
        )
        assertEquals("the seeded month holds ${VISIBLE_IN_WINDOW} tasks", VISIBLE_IN_WINDOW, counts.first())
    }

    @Test
    fun `the bounded window agrees with the or of clauses it replaced`() {
        SIZES.forEach { size ->
            val profile = profile(size)
            assertEquals(
                "$size-row account: the window changed shape, not meaning",
                readLegacyWindow(profile, WINDOW.first, WINDOW.to).map { it.id }.toSet(),
                readWindow(profile, WINDOW.first, WINDOW.to).map { it.id }.toSet()
            )
        }
    }

    @Test
    fun `the window seeks an index instead of walking the tasks table`() {
        val profile = profile(LARGEST)
        val plan = queryPlan(profile)
        plan.forEach { step ->
            assertFalse(
                "the window walked the table. plan: ${plan.joinToString(" ;; ")}",
                step.startsWith("SCAN tasks")
            )
        }
        listOf("due_date", "start_time", "end_time").forEach { column ->
            assertTrue(
                "nothing seeks $column. plan: ${plan.joinToString(" ;; ")}",
                plan.any { it.contains("$column>?") || it.contains("$column<?") }
            )
        }
    }

    @Test
    fun `a month week and day read within a ceiling at every account size`() {
        SIZES.forEach { size ->
            val profile = profile(size)

            val (monthQuery, monthRows) = median(MONTH_RUNS) {
                readWindow(profile, WINDOW.first, WINDOW.to).size
            }
            val (monthScreen, _) = median(MONTH_RUNS) {
                CalendarAggregator.cells(
                    grid = MONTH_GRID,
                    displayedMonth = DISPLAYED,
                    tasks = readWindow(profile, WINDOW.first, WINDOW.to),
                    categories = profile.categories,
                    today = TODAY,
                    now = CLOCK,
                    zone = ZONE
                ).size
            }
            val week = WEEK_GRID
            val (weekScreen, weekRows) = median(ROUND) {
                val rows = readWindow(profile, startOfWeek(week), endOfWeek(week))
                CalendarAggregator.cells(week, null, rows, profile.categories, TODAY, CLOCK, ZONE).size
            }
            val (dayScreen, dayRows) = median(ROUND) {
                CalendarAggregator.dayPlan(
                    TODAY,
                    readWindow(profile, WINDOW.dayFirst, WINDOW.dayTo),
                    CLOCK,
                    ZONE
                ).blocks.size
            }
            val (legacyQuery, legacyRows) = median(ROUND) {
                readLegacyWindow(profile, WINDOW.first, WINDOW.to).size
            }

            println(
                String.format(
                    "calendar  profile=%5d | month %6.2f ms read + %6.2f ms screen (%d rows) | " +
                        "week %6.2f ms screen (%d rows) | day %6.2f ms screen (%d rows) | " +
                        "month before the bounded read: %6.2f ms (%d rows)",
                    size, monthQuery, monthScreen, monthRows, weekScreen, weekRows,
                    dayScreen, dayRows, legacyQuery, legacyRows
                )
            )

            assertTrue("month read took ${monthScreen} ms on a $size-row account", monthScreen < SCREEN_CEILING_MS)
            assertTrue("week read took ${weekScreen} ms", weekScreen < SCREEN_CEILING_MS)
            assertTrue("day read took ${dayScreen} ms", dayScreen < SCREEN_CEILING_MS)
        }
    }

    // ---- reading the window ------------------------------------------------

    private val WINDOW: Window = Window(MONTH_GRID)

    private class Window(grid: List<LocalDate>) {
        val first: Long = CalendarMath.startOfDay(grid.first(), ZONE)

        /** Exclusive, the way the production query's upper bound is. */
        val to: Long = CalendarMath.startOfDay(grid.last().plusDays(1), ZONE)
        val dayFirst: Long = CalendarMath.startOfDay(TODAY, ZONE)

        /** Exclusive upper bound of the day view's own window. */
        val dayTo: Long = CalendarMath.startOfDay(TODAY.plusDays(1), ZONE)
    }

    /**
     * Room turns every `:name` into a `?` before SQLite ever sees the statement; doing the same here
     * keeps this test measuring [WINDOW_QUERY] itself instead of a transcription that could drift from
     * the query the app runs.
     */
    private fun windowSql(): String {
        val sql = WINDOW_QUERY.replace(":userId", "?").replace(":from", "?").replace(":to", "?")
        assertFalse("a parameter Room does not bind reached SQLite", sql.contains(':'))
        assertEquals("the window binds one profile and two bounds per lookup", 12, sql.count { it == '?' })
        return sql
    }

    private fun windowArgs(from: Long, to: Long): List<Any> = (1..4).flatMap { listOf(PROFILE, from, to) }

    private fun readWindow(profile: Profile, from: Long, to: Long): List<Task> =
        profile.db.rows(windowSql(), windowArgs(from, to)).map { row -> row.toTask(profile.columnOf) }

    private fun queryPlan(profile: Profile): List<String> =
        profile.db.rows("EXPLAIN QUERY PLAN " + windowSql(), windowArgs(WINDOW.first, WINDOW.to))
            .map { it[3] ?: "" }

    /** The window as it was written before this measurement: one user scan, four ORed clauses. */
    private fun legacyWindowSql(): String =
        "SELECT * FROM tasks WHERE user_id = ? AND is_deleted = 0 AND archived = 0 AND ( " +
            "(due_date IS NOT NULL AND due_date >= ? AND due_date < ?) OR " +
            "(start_time IS NOT NULL AND start_time >= ? AND start_time < ?) OR " +
            "(end_time IS NOT NULL AND end_time >= ? AND end_time < ?) OR " +
            "(start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < ? AND end_time >= ?) " +
            ") ORDER BY COALESCE(start_time, due_date, end_time) ASC, id ASC"

    private fun readLegacyWindow(profile: Profile, from: Long, to: Long): List<Task> =
        profile.db.rows(
            legacyWindowSql(),
            listOf<Any>(PROFILE, from, to, from, to, from, to, from, to)
        ).map { row -> row.toTask(profile.columnOf) }

    /** The predicates the window is defined by, applied to the seeded values instead of to SQL. */
    private fun expectedInWindow(profile: Profile, from: Long, to: Long): Set<Long> =
        profile.tasks.asSequence()
            .filter { it.userId == PROFILE && !it.isDeleted && !it.archived }
            .filter { task ->
                val due = task.dueDate
                val start = task.startTime
                val end = task.endTime
                (due != null && due >= from && due < to) ||
                    (start != null && start >= from && start < to) ||
                    (end != null && end >= from && end < to) ||
                    (start != null && end != null && start < from && end >= to)
            }
            .map { it.id }
            .toSet()

    // ---- timing ------------------------------------------------------------

    /**
     * Median of [runs] repetitions after two warm-up reads. A single timing on a shared runner says
     * nothing and a minimum flatters the machine, so the middle of the distribution is the number worth
     * comparing with a ceiling.
     */
    private fun median(runs: Int, block: () -> Int): Pair<Double, Int> {
        repeat(2) { block() }
        var rows = 0
        val samples = (1..runs).map {
            val started = System.nanoTime()
            rows = block()
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        return samples[samples.size / 2] to rows
    }

    // ---- the account under test -------------------------------------------

    /**
     * Builds a [size]-row account in the newest schema Room exported: the tasks one month holds, the
     * states a screen must never show (archived, deleted, belonging to somebody else), and a person's
     * remaining history spread over the years on both sides of the window, half of it planned as
     * blocks.
     */
    private fun profile(size: Int): Profile {
        val db = SqliteTestDatabase.open("calendar-window-$size").also(opened::add)
        TestSchemas.createVersion(db, AppMigrations.ALL.maxOf { it.endVersion })
        db.exec(
            "INSERT INTO categories (id, uuid, name, color, icon, user_id, created_at, updated_at) VALUES " +
                "(7, 'cat-work', 'Work', -16711936, 'ic_work', $PROFILE, 1, 1)," +
                "(8, 'cat-home', 'Home', -16777000, 'ic_home', $PROFILE, 1, 1)"
        )
        val tasks = ArrayList<Task>(size)
        var id = 1L
        repeat(IN_WINDOW) { index -> tasks += inTheMonth(index, id++) }
        repeat(SPANNING) { index -> tasks += spanningTheGrid(index, id++) }
        repeat(OUT_OF_VIEW) { index -> tasks += hiddenFromTheScreen(index, id++) }
        while (tasks.size < size) tasks += somewhereInHistory(tasks.size, id++)
        tasks.chunked(INSERT_CHUNK).forEach { chunk -> insert(db, chunk) }
        val layout = db.rows("PRAGMA table_info(tasks)").associate { row -> row[1]!! to row[0]!!.toInt() }
        return Profile(db, tasks, CATEGORIES, layout)
    }

    /** A task the displayed month holds, in each shape the calendar has to draw. */
    private fun inTheMonth(index: Int, id: Long): Task {
        val day = DISPLAYED.atDay(1).plusDays((index % 28).toLong())
        return when (index % 3) {
            0 -> task(id, "deadline ${index + 1}", due = at(day, 23, 59), categoryId = if (index % 4 == 0) 8L else 7L)
            1 -> task(
                id, "meeting ${index + 1}",
                start = at(day, 9, 0), end = at(day, 10, 30), due = at(day, 23, 59), estimate = 90
            )

            else -> task(id, "focus ${index + 1}", start = at(day, 14, 0), estimate = 45)
        }
    }

    /**
     * Blocks that start before the grid and finish after it: the case one bounded lookup alone would
     * lose, so it is what the window's fourth seek exists for.
     */
    private fun spanningTheGrid(index: Int, id: Long): Task = task(
        id, "renovation ${index + 1}",
        start = at(MONTH_GRID.first().minusDays(7 + index.toLong()), 8, 0),
        end = at(MONTH_GRID.last().plusDays(7 + index.toLong()), 18, 0)
    )

    /** Rows inside the window a calendar screen must never draw. */
    private fun hiddenFromTheScreen(index: Int, id: Long): Task {
        val day = DISPLAYED.atDay(1).plusDays((index % 28).toLong())
        val archived = index % 2 == 0
        return task(
            id, "hidden ${index + 1}", due = at(day, 12, 0),
            archived = archived,
            deleted = !archived,
            otherAccount = index % 7 == 0
        )
    }

    /**
     * The rest of the account. A day that would fall inside the grid is pushed past its end instead,
     * which keeps the month's row count fixed while still leaving the tasks after the window that the
     * spanning lookup has to look at.
     */
    private fun somewhereInHistory(index: Int, id: Long): Task {
        val candidate = LocalDate.of(2023, 1, 4).plusDays(((index * 37) % 2160).toLong())
        val day = if (candidate in WINDOW_DAYS) MONTH_GRID.last().plusDays(2 + (index % 28).toLong()) else candidate
        return if (index % 2 == 0) {
            task(id, "history ${index + 1}", due = at(day, 23, 59))
        } else {
            task(
                id, "history ${index + 1}",
                start = at(day, 8, 0), end = at(day, 9, 30), due = at(day, 23, 59), categoryId = 7L
            )
        }
    }

    private fun insert(db: SqliteTestDatabase, chunk: List<Task>) {
        val values = chunk.joinToString(",") { task ->
            "(${task.id}, '${task.uuid}', '${task.title}', '', ${literal(task.dueDate)}, " +
                "${literal(task.startTime)}, ${literal(task.endTime)}, ${literal(task.estimatedMinutes)}, " +
                "'${task.priority.name}', '${task.status.name}', ${literal(task.categoryId)}, ${task.userId}, " +
                "${task.order}, ${bit(task.archived)}, ${bit(task.isDeleted)}, ${literal(task.deletedAt)}, 1, 1)"
        }
        db.exec(
            "INSERT INTO tasks (id, uuid, title, description, due_date, start_time, end_time, " +
                "estimated_minutes, priority, status, category_id, user_id, `order`, archived, " +
                "is_deleted, deleted_at, created_at, updated_at) VALUES $values"
        )
    }

    private fun bit(value: Boolean): Int = if (value) 1 else 0

    private fun literal(value: Any?): String = value?.toString() ?: "NULL"

    private fun task(
        id: Long,
        title: String,
        due: Long? = null,
        start: Long? = null,
        end: Long? = null,
        estimate: Int? = null,
        categoryId: Long? = null,
        archived: Boolean = false,
        deleted: Boolean = false,
        otherAccount: Boolean = false
    ): Task = Task(
        id = id,
        uuid = "task-$id",
        title = title,
        description = "",
        dueDate = due,
        startTime = start,
        endTime = end,
        estimatedMinutes = estimate,
        priority = Priority.MEDIUM,
        status = TaskStatus.TODO,
        categoryId = categoryId,
        userId = if (otherAccount) OTHER_ACCOUNT else PROFILE,
        archived = archived,
        isDeleted = deleted,
        deletedAt = if (deleted) CLOCK else null
    )

    /** A wall-clock instant in the measured zone, so the grid and its rows agree about a day. */
    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(ZONE).toInstant().toEpochMilli()

    private fun startOfWeek(grid: List<LocalDate>): Long = CalendarMath.startOfDay(grid.first(), ZONE)

    private fun endOfWeek(grid: List<LocalDate>): Long = CalendarMath.startOfDay(grid.last().plusDays(1), ZONE)

    /** A row of `SELECT *`, read by column name so the schema's own order cannot silently move it. */
    private fun List<String?>.toTask(layout: Map<String, Int>): Task {
        fun cell(column: String): String? = this[layout.getValue(column)]
        fun long(column: String): Long? = cell(column)?.toLong()
        fun bool(column: String): Boolean = cell(column)?.toInt() != 0
        return Task(
            id = long("id")!!,
            uuid = cell("uuid") ?: "",
            title = cell("title") ?: "",
            description = cell("description") ?: "",
            dueDate = long("due_date"),
            startTime = long("start_time"),
            endTime = long("end_time"),
            estimatedMinutes = cell("estimated_minutes")?.toInt(),
            priority = Priority.valueOf(cell("priority")!!),
            status = TaskStatus.valueOf(cell("status")!!),
            categoryId = long("category_id"),
            userId = long("user_id")!!,
            order = cell("order")!!.toInt(),
            archived = bool("archived"),
            isDeleted = bool("is_deleted")
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val DISPLAYED: YearMonth = YearMonth.of(2026, 3)
        val TODAY: LocalDate = LocalDate.of(2026, 3, 11)
        val MONTH_GRID: List<LocalDate> = CalendarMath.monthGrid(DISPLAYED, DayOfWeek.MONDAY)
        val WEEK_GRID: List<LocalDate> = CalendarMath.weekGrid(TODAY, DayOfWeek.MONDAY)
        val WINDOW_DAYS: Set<LocalDate> = MONTH_GRID.toSet()

        const val PROFILE = 1L
        const val OTHER_ACCOUNT = 2L

        /** A named clock: the day view marks tasks overdue against it, so the test says which now. */
        val CLOCK: Long = LocalDateTime.of(TODAY, LocalTime.of(13, 0)).atZone(ZONE).toInstant().toEpochMilli()

        const val IN_WINDOW = 60
        const val SPANNING = 2
        const val OUT_OF_VIEW = 600
        const val VISIBLE_IN_WINDOW = IN_WINDOW + SPANNING

        const val LARGEST = 10_000
        val SIZES = intArrayOf(1_000, 5_000, LARGEST)
        const val INSERT_CHUNK = 400

        val CATEGORIES = listOf(
            Category(
                id = 7, uuid = "cat-work", name = "Work", color = -16711936,
                icon = "ic_work", userId = PROFILE
            ),
            Category(
                id = 8, uuid = "cat-home", name = "Home", color = -16777000,
                icon = "ic_home", userId = PROFILE
            )
        )

        const val MONTH_RUNS = 9
        const val ROUND = 5

        /**
         * One screen's read plus its aggregation, on the SQLite engine the harness runs. Room's own row
         * mapping and all of Android's view work are outside this number. The measured medians sit
         * between 1 and 6 ms on every account size, so this is an order of magnitude above them: it
         * cannot fail on a loaded shared runner, and it catches the read going back to walking the
         * profile, which the same harness measured at 8.3 ms for 10k tasks against 2.8 ms for the
         * bounded window — and which grows from there with everything the user writes.
         */
        const val SCREEN_CEILING_MS = 60.0
    }
}
