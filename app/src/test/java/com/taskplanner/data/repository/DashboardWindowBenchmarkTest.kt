package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.model.isCompletedSpelling
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What one visit to the dashboard costs on accounts of the size the brief names — 1k, 5k and 10k tasks —
 * measured through the production repository on the production Room database.
 *
 * The DAO tests already hold every statement up against a hand-folded oracle and read
 * `EXPLAIN QUERY PLAN` for it. Neither can say what this file has to. A plan may name an index and still
 * describe a read whose cost is set by the account, and an answer may be right over twelve seeded rows
 * while the screen that assembles it loads thousands. So what is measured here is the whole read the
 * dashboard makes, on an account with years of history underneath it.
 *
 * Four claims, each one something a later change could break quietly:
 *
 *  * **The week is the week.** One seeded week answers identically at every size, and the answer is the
 *    rows inside it — folded here in Kotlin from the values that were inserted rather than from what the
 *    SQL said, so a figure leaking outside its window shows up as a disagreement.
 *  * **The answer does not grow with the account.** The number of values handed to the screen is the same
 *    from 1k to 10k while the profile's own rows grow tenfold, and the figures that are allowed to move —
 *    the rows the account holds and the share of them ever finished — are named as exactly those. That is
 *    the difference between a chart whose cost is set by the period it names and one whose cost is set by
 *    everything the user has ever written.
 *  * **The read stays inside a ceiling** at every size, and grows far less than the account does. Measured
 *    here beside the old way of doing it, on the same harness, in the same test.
 *  * **The aggregates changed the cost and not the meaning.** The same figures are folded here the way
 *    this screen used to compute them — every task and every interval of the profile loaded and counted in
 *    Kotlin — and the two answers are made to agree before their costs are compared.
 *
 * Every account is anchored on a Wednesday with the clock parked at midday of it, so "this week",
 * "overdue", "stale" and a window's two bounds name the same instants in the seeds and in the repository.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardWindowBenchmarkTest {

    private val zone: ZoneId = CalendarMath.systemZone()
    private val today: LocalDate = LocalDate.of(2026, 6, 3)
    private val monday: LocalDate = today.minusDays(2)
    private val gridDays = DashboardMath.heatmapRange(
        today, DashboardRequest.DEFAULT_HEATMAP_WEEKS, DayOfWeek.MONDAY
    )

    private val opened = ArrayList<AppFixture>()

    @After
    fun closeAccounts() {
        opened.forEach { runCatching(it::close) }
        opened.clear()
    }

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---- the account under test ---------------------------------------------

    /** A `tasks` row as it was written, with only the columns the dashboard's predicates read. */
    private data class SeededTask(
        val id: Long,
        val userId: Long,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val dueAt: Long? = null,
        val completedAt: Long? = null,
        val status: TaskStatus = TaskStatus.TODO,
        val categoryId: Long? = null,
        val archived: Boolean = false,
        val deleted: Boolean = false
    )

    /** One closed `work_sessions` stretch: what the period's totals are summed from. */
    private data class SeededInterval(
        val uuid: String,
        val userId: Long,
        val sessionUuid: String,
        val taskId: Long,
        val startedAt: Long,
        val seconds: Long,
        val localDay: Long
    )

    /** A seeded account: the fixture to read through, and the rows that make it up. */
    private class Account(
        val fixture: AppFixture,
        val userId: Long,
        val tasks: List<SeededTask>,
        val intervals: List<SeededInterval>
    )

    /**
     * A [size]-row account whose current week is always the same week.
     *
     * The week is fixed: six tasks made in it, three of them finished in it at hours that land on
     * different trend days than the clock's own, six more open and each sitting in a different state of
     * the health panel, work measured on four of its days by three sessions over six tasks and two
     * categories. Around it sits a grid's worth of quieter weeks, and outside the grid's first square
     * lies the rest of the account — history, another profile's rows, an archived task, a tombstone. Only
     * that outer mass changes with [size], so any figure that moves when the size does is a figure that
     * leaked out of its window.
     */
    private fun account(size: Int): Account {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = AppFixture(context).also { opened.add(it) }
        val userId = runBlocking { fixture.signIn(email = "bench-$size@example.com").id }
        fixture.timeBase.shiftTo(at(today, 12, 0))
        val db = fixture.database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO categories (id, uuid, name, color, icon, user_id, created_at, updated_at) VALUES " +
                "(1, 'cat-work', 'Work', -16711936, 'ic_tag', $userId, ${at(monday)}, ${at(monday)})," +
                "(2, 'cat-home', 'Home', -16777216, 'ic_tag', $userId, ${at(monday)}, ${at(monday)})"
        )

        var nextId = 1L
        fun id(): Long = nextId++

        // Made this week; the first three finished in it, one a minute before midnight and one just after
        // it, so a completion is placed by its own instant rather than by the day the read happens on.
        val made = (0 until 6).map { index ->
            val day = monday.plusDays(index.toLong())
            val finished = index < 3
            SeededTask(
                id = id(), userId = userId, title = "made-$index",
                createdAt = at(day, 8, 0), updatedAt = at(day, 20, 0),
                dueAt = at(day, 21, 0), categoryId = if (index % 2 == 0) 1L else 2L,
                status = if (finished) TaskStatus.COMPLETED else TaskStatus.TODO,
                completedAt = when (index) {
                    0 -> at(monday, 23, 58)
                    1 -> at(monday.plusDays(1), 0, 20)
                    else -> at(today, 9, 30)
                }.takeIf { finished }
            )
        }

        // One row per state the health panel names, all made before the week so the period's "created"
        // figure belongs to the six above alone.
        val longAgo = today.minusDays(20)
        val staleBoundary = at(today.minusDays(14), 0, 0)
        val openTasks = listOf(
            SeededTask(id(), userId, "late", at(longAgo), at(longAgo), dueAt = at(monday, 9, 0)),
            SeededTask(
                id(), userId, "late-and-running", at(longAgo), at(longAgo),
                dueAt = at(monday, 8, 0), status = TaskStatus.IN_PROGRESS
            ),
            SeededTask(id(), userId, "due-today", at(longAgo), at(longAgo), dueAt = at(today, 18, 0)),
            SeededTask(
                id(), userId, "due-this-week", at(longAgo), at(longAgo),
                dueAt = at(monday.plusDays(6), 7, 0)
            ),
            // Touched a minute before the stale boundary, and given a deadline past the week's end: one
            // row the panel counts in two different figures at once.
            SeededTask(
                id(), userId, "stale-and-later", at(longAgo), staleBoundary - 60_000L,
                dueAt = at(today.plusDays(30), 9, 0)
            ),
            SeededTask(id(), userId, "no-deadline", at(longAgo), at(longAgo))
        )

        // Rows that belong to no figure on the screen at all.
        val hidden = listOf(
            SeededTask(
                id(), userId, "finished-before-the-grid", at(longAgo), at(longAgo),
                status = TaskStatus.COMPLETED, completedAt = at(longAgo)
            ),
            SeededTask(
                id(), userId, "put-away-when-finished", at(longAgo), at(longAgo),
                status = TaskStatus.COMPLETED, completedAt = at(longAgo), archived = true
            ),
            SeededTask(
                id(), userId, "tombstone", at(longAgo), at(longAgo),
                dueAt = at(monday, 9, 0), deleted = true
            ),
            SeededTask(
                id(), userId = OTHER_PROFILE, title = "their-week", createdAt = at(monday),
                updatedAt = at(monday), dueAt = at(today, 12, 0), categoryId = 1L
            )
        )

        // The week's work: four of its seven days, three sessions, six tasks. Worked on two of the open
        // rows as well, so an unclassified stretch reaches the category chart instead of vanishing from it.
        val worked = listOf(made[0], made[1], made[3], made[4], openTasks[0], openTasks[2])
        val weekWork = listOf(
            stretch("w1", userId, worked[0], monday, 10, "s-1"),
            stretch("w2", userId, worked[0], monday, 15, "s-1"),
            stretch("w3", userId, worked[1], monday.plusDays(1), 20, "s-1"),
            stretch("w4", userId, worked[2], monday.plusDays(2), 25, "s-2"),
            stretch("w5", userId, worked[3], today, 30, "s-2"),
            stretch("w6", userId, worked[4], today, 35, "s-3"),
            stretch("w7", userId, worked[5], today, 40, "s-3"),
            stretch("w8", userId, worked[2], today, 45, "s-3")
        )

        // Ten quieter weeks the grid draws and the period's totals do not add.
        val gridWork = (0 until 10).map { index ->
            val day = today.minusDays(9L + index * 7L)
            require(day.toEpochDay() in gridDays) { "the grid seeds must lie inside the grid" }
            SeededInterval(
                uuid = "g$index", userId = userId, sessionUuid = "s-grid-$index",
                taskId = made[index % made.size].id, startedAt = at(day, 11, 0),
                seconds = 600L + index * 60L, localDay = day.toEpochDay()
            )
        }

        // The outer mass: history older than the grid's first square, and another profile's share of it.
        // Every one of those rows is finished, because that is what a person's history is — and because
        // the health panel is about the account rather than about a window, an unfinished row out here
        // would move the panel's figure with the account's size. That is the dashboard being right, and
        // this seeding is what lets the window's own figures be measured for not moving.
        val history = ArrayList<SeededTask>()
        val historyWork = ArrayList<SeededInterval>()
        val mine = made + openTasks + hidden
        var ordinal = 0
        while (mine.size + history.size < size) {
            val candidate = LocalDate.of(2019, 1, 5).plusDays(((ordinal * 37) % 2400).toLong())
            val day = if (candidate.toEpochDay() in gridDays || candidate >= monday) {
                gridDays.first.let { LocalDate.ofEpochDay(it) }.minusDays((ordinal % 900).toLong())
            } else {
                candidate
            }
            val theirs = ordinal % 5 == 0
            val task = SeededTask(
                id = id(),
                userId = if (theirs) OTHER_PROFILE else userId,
                title = "history-$ordinal",
                createdAt = at(day, 9, 0),
                updatedAt = at(day, 17, 0),
                dueAt = at(day, 18, 0),
                completedAt = at(day, 17, 0),
                status = TaskStatus.COMPLETED,
                categoryId = if (ordinal % 3 == 0) 1L else null,
                archived = ordinal % 9 == 0
            )
            history += task
            if (!theirs && ordinal % 4 == 0) {
                historyWork += SeededInterval(
                    uuid = "h$ordinal", userId = userId, sessionUuid = "s-h-$ordinal", taskId = task.id,
                    startedAt = at(day, 12, 0), seconds = 900L, localDay = day.toEpochDay()
                )
            }
            ordinal++
        }

        val tasks = mine + history
        val intervals = weekWork + gridWork + historyWork
        insertTasks(db, tasks)
        insertIntervals(db, intervals)
        return Account(fixture, userId, tasks, intervals)
    }

    private fun stretch(
        uuid: String,
        userId: Long,
        task: SeededTask,
        day: LocalDate,
        minutes: Long,
        session: String
    ): SeededInterval = SeededInterval(
        uuid = uuid, userId = userId, sessionUuid = session, taskId = task.id,
        startedAt = at(day, 9, 0), seconds = minutes * 60L, localDay = day.toEpochDay()
    )

    private fun insertTasks(db: SupportSQLiteDatabase, rows: List<SeededTask>) {
        rows.chunked(INSERT_CHUNK).forEach { chunk ->
            db.execSQL(
                "INSERT INTO tasks (id, uuid, title, description, due_date, priority, status, " +
                    "category_id, user_id, `order`, archived, is_deleted, completed_at, created_at, " +
                    "updated_at) VALUES " +
                    chunk.joinToString(",") { task ->
                        "(${task.id}, 'task-${task.id}', '${task.title}', '', ${orNull(task.dueAt)}, " +
                            "'${Priority.MEDIUM.name}', '${task.status.name}', ${orNull(task.categoryId)}, " +
                            "${task.userId}, 0, ${if (task.archived) 1 else 0}, ${if (task.deleted) 1 else 0}, " +
                            "${orNull(task.completedAt)}, ${task.createdAt}, ${task.updatedAt})"
                    }
            )
        }
    }

    private fun insertIntervals(db: SupportSQLiteDatabase, rows: List<SeededInterval>) {
        rows.chunked(INSERT_CHUNK).forEach { chunk ->
            db.execSQL(
                "INSERT INTO work_sessions (uuid, user_id, tracking_session_uuid, task_id, task_uuid, " +
                    "started_at, ended_at, duration_seconds, local_day, utc_offset_minutes, source, " +
                    "created_at, updated_at) VALUES " +
                    chunk.joinToString(",") { row ->
                        "('${row.uuid}', ${row.userId}, '${row.sessionUuid}', ${row.taskId}, " +
                            "'task-${row.taskId}', ${row.startedAt}, ${row.startedAt + row.seconds * 1000L}, " +
                            "${row.seconds}, ${row.localDay}, ${WorkTimeMath.utcOffsetMinutesOf(row.startedAt)}, " +
                            "'${WorkSource.TRACKER.name}', ${row.startedAt}, ${row.startedAt})"
                    }
            )
        }
    }

    private fun orNull(value: Long?): String = value?.toString() ?: "NULL"

    // ---- the week's own answer, folded from the rows -------------------------

    private class Expected(
        val seconds: Long,
        val intervals: Int,
        val sessions: Int,
        val completed: Int,
        val created: Int,
        val trend: List<Pair<Long, Long>>,
        val litGridDays: Int,
        val busiestDaySeconds: Long,
        val topTasks: List<Pair<Long, Long>>,
        val categories: List<Pair<Long?, Long>>,
        val streak: Int,
        val open: Int,
        val overdue: Int,
        val dueToday: Int,
        val dueThisWeek: Int,
        val withoutDeadline: Int,
        val inProgress: Int,
        val stale: Int,
        val totalTasks: Int,
        val completedEver: Int
    ) {
        /**
         * The figures that speak about the period or the grid, as one comparable value.
         *
         * The account-wide ones are deliberately not in it: the share of everything finished and the count
         * of rows the profile holds are about the account, and a dashboard that kept them constant while the
         * account grew would be a dashboard hiding the growth.
         */
        fun windowFigures(): List<Any> = listOf(
            seconds, intervals, sessions, completed, created, trend, litGridDays, busiestDaySeconds,
            topTasks, categories, streak, open, overdue, dueToday, dueThisWeek, withoutDeadline,
            inProgress, stale
        )
    }

    /**
     * The same sixteen figures, worked out from the rows rather than asked of SQLite.
     *
     * The predicates are the repository's, restated here on purpose: two half-open windows, an open row
     * one that is neither a tombstone nor archived nor finished, a completion credited to its own instant.
     * Where the two disagree, one of them is wrong about the data — and at ten thousand rows this is the
     * test that says so.
     */
    private fun expected(account: Account): Expected {
        val period = account.fixture.workStatisticsRepository.window(
            WorkPeriod.THIS_WEEK, today, zone
        )
        val mine = account.tasks.filter { it.userId == account.userId && !it.deleted }
        val inWeek = account.intervals.filter {
            it.userId == account.userId && it.localDay >= period.fromDay && it.localDay < period.toDay
        }
        val inGrid = account.intervals.filter {
            it.userId == account.userId && it.localDay >= gridDays.first && it.localDay <= gridDays.last
        }

        // The panel's open rows exclude archived ones; the period's completions do not, because a task put
        // away after it was finished was still finished inside the window.
        val finishedInWeek = mine.filter {
            it.completedAt != null && isCompletedSpelling(it.status.name) &&
                it.completedAt >= period.fromMillis && it.completedAt < period.toMillis
        }
        val openNow = mine.filter { !it.archived && !isCompletedSpelling(it.status.name) }
        val now = at(today, 12, 0)
        val dayEnd = at(today.plusDays(1), 0, 0)
        val weekEnd = at(today.plusDays(7), 0, 0)
        val staleBefore = at(today.minusDays(14), 0, 0)

        val secondsOfDay = inWeek.groupBy { it.localDay }.mapValues { (_, rows) -> rows.sumOf { it.seconds } }
        val completionsOfDay = finishedInWeek
            .groupBy { DashboardMath.epochDayOf(it.completedAt!!, zone) }
            .mapValues { (_, rows) -> rows.size.toLong() }
        val gridSeconds = inGrid.groupBy { it.localDay }.mapValues { (_, rows) -> rows.sumOf { it.seconds } }
        val perTask = inWeek.groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.sumOf { it.seconds } }
            .entries.sortedByDescending { it.value }
        val categoryOf = mine.associate { it.id to it.categoryId }
        val perCategory = inWeek.groupBy { row -> categoryOf[row.taskId] }
            .mapValues { (_, rows) -> rows.sumOf { it.seconds } }
            .entries.sortedByDescending { it.value }

        var streak = 0
        while ((gridSeconds[today.toEpochDay() - streak] ?: 0L) > 0L) streak++

        return Expected(
            seconds = inWeek.sumOf { it.seconds },
            intervals = inWeek.size,
            sessions = inWeek.map { it.sessionUuid }.distinct().size,
            completed = finishedInWeek.size,
            created = mine.count { it.createdAt >= period.fromMillis && it.createdAt < period.toMillis },
            trend = (secondsOfDay.keys + completionsOfDay.keys).sorted().map { day ->
                day to (completionsOfDay[day] ?: 0L)
            },
            litGridDays = gridSeconds.count { it.value > 0L },
            busiestDaySeconds = gridSeconds.values.maxOrNull() ?: 0L,
            topTasks = perTask.take(TOP_TASK_LIMIT).map { it.key to it.value },
            categories = perCategory.map { it.key to it.value },
            streak = streak,
            open = openNow.size,
            overdue = openNow.count { it.dueAt != null && it.dueAt < now },
            dueToday = openNow.count { it.dueAt != null && it.dueAt >= now && it.dueAt < dayEnd },
            dueThisWeek = openNow.count { it.dueAt != null && it.dueAt >= dayEnd && it.dueAt < weekEnd },
            withoutDeadline = openNow.count { it.dueAt == null },
            inProgress = openNow.count { it.status == TaskStatus.IN_PROGRESS },
            stale = openNow.count { it.updatedAt < staleBefore },
            totalTasks = mine.size,
            completedEver = mine.count { isCompletedSpelling(it.status.name) }
        )
    }

    private fun read(account: Account): DashboardSnapshot {
        val snapshot = runBlocking {
            account.fixture.dashboardRepository.snapshot(
                DashboardRequest(
                    period = WorkPeriod.THIS_WEEK,
                    onDay = today,
                    zone = zone,
                    firstDayOfWeek = DayOfWeek.MONDAY
                )
            )
        }
        return checkNotNull(snapshot) { "the dashboard answered nothing for a signed-in account" }
    }

    // ---- the claims ----------------------------------------------------------

    @Test
    fun `one seeded week answers the same on a thousand-row account and a ten-thousand-row one`() {
        val accounts = SIZES.map { size -> account(size) }
        val answers = accounts.map { read(it) }
        val fold = accounts.map { expected(it) }

        fold.forEachIndexed { index, e ->
            val snapshot = answers[index]
            val at = "the ${SIZES[index]}-row account"
            assertEquals("$at measured what the week held", e.seconds, snapshot.measured.seconds)
            assertEquals("$at counted its stretches", e.intervals, snapshot.measured.intervals)
            assertEquals("$at counted its sessions", e.sessions, snapshot.measured.sessions)
            assertEquals("$at finished this week's own tasks", e.completed, snapshot.completed)
            assertEquals("$at made this week's own tasks", e.created, snapshot.created)
            assertEquals(
                "$at drew a bar per day the period produced something on, and no day between them",
                e.trend.map { it.first },
                snapshot.trend.map { it.localDay }
            )
            assertEquals(
                "$at put the completions on the day each instant falls in, not the day it was read",
                e.trend,
                snapshot.trend.map { it.localDay to it.completions.toLong() }
            )
            assertEquals(
                "$at ranked the busiest tasks the way the rows rank",
                e.topTasks,
                snapshot.topTasks.map { it.taskId to it.seconds }
            )
            assertEquals(
                "$at grouped the week's work by the category its task carries, including the work whose " +
                    "task carries none",
                e.categories,
                snapshot.categories.map { it.categoryId to it.seconds }
            )
            assertEquals("$at lit the grid's days", e.litGridDays, litSquares(snapshot))
            assertEquals(
                "$at scaled the grid on its busiest day", e.busiestDaySeconds,
                snapshot.heatmap.busiestDaySeconds
            )
            assertEquals("$at ran for this many days", e.streak, snapshot.streakDays)
            assertEquals("$at found this many open", e.open, snapshot.health.open)
            assertEquals("$at late", e.overdue, snapshot.health.overdue)
            assertEquals("$at due today", e.dueToday, snapshot.health.dueToday)
            assertEquals("$at due this week", e.dueThisWeek, snapshot.health.dueThisWeek)
            assertEquals("$at without a deadline", e.withoutDeadline, snapshot.health.withoutDeadline)
            assertEquals("$at in progress", e.inProgress, snapshot.health.inProgress)
            assertEquals("$at stale", e.stale, snapshot.health.stale)
            assertEquals("$at counted every row the profile holds", e.totalTasks, snapshot.totalTasks)
            assertEquals("$at counted everything ever finished", e.completedEver, snapshot.completedEver)
        }

        // The same week, read on three accounts of different weights. This is the claim the whole design
        // rests on: what the period and the grid answer cannot depend on how much the user has written
        // beside them.
        val windowFigures = fold.map { it.windowFigures() }
        assertEquals(
            "one week, three accounts, one set of window figures: ${windowFigures.first()}",
            setOf(windowFigures.first()),
            windowFigures.toSet()
        )

        // And the opposite claim, about the two figures that are about the account rather than about a
        // window: those are meant to move when the account does, and a dashboard that hid that growth
        // would be the other kind of wrong.
        val accountWide = answers.map { it.totalTasks }
        assertEquals("each account holds more than the one before it", accountWide.sorted(), accountWide)
        assertTrue(
            "and the largest really is the largest the brief names: $accountWide rows of tasks",
            accountWide.last() >= accountWide.first() * 5
        )
        assertTrue(
            "the share finished follows the account, not the week: " +
                answers.map { it.completionRate }.map { "%.2f".format(it ?: -1f) },
            answers.map { it.completedEver }.distinct().size == SIZES.size
        )
    }

    @Test
    fun `the number of values the screen is handed does not grow with the account`() {
        val shapes = SIZES.map { size ->
            val account = account(size)
            val snapshot = read(account)
            Shape(
                size = size,
                rows = runBlocking { account.fixture.taskDao.getAllTasksOnce(account.userId).size },
                values = snapshot.trend.size + snapshot.topTasks.size + snapshot.categories.size +
                    snapshot.statusCounts.size + litSquares(snapshot) + snapshot.measured.intervals,
                busiest = snapshot.topTasks.size
            )
        }
        println(
            "dashboard answer shapes: " +
                shapes.joinToString(" | ") { "${it.rows} rows -> ${it.values} values" }
        )

        assertEquals(
            "the same answer handed to a screen over a 1k, 5k and 10k profile: ${shapes.map { it.values }}",
            1,
            shapes.map { it.values }.distinct().size
        )
        assertTrue(
            "while the profile itself grew from ${shapes.first().rows} rows to ${shapes.last().rows}",
            shapes.last().rows >= shapes.first().rows * 5
        )
        assertEquals(
            "the busiest list is capped rather than complete, at every size: a screen that listed every " +
                "task the period touched is a list of the account wearing a chart's clothes",
            setOf(TOP_TASK_LIMIT),
            shapes.map { it.busiest }.toSet()
        )
    }

    @Test
    fun `one visit to the dashboard reads within a ceiling at every account size`() {
        val costs = SIZES.map { size ->
            val account = account(size)
            val (aggregate, _) = median(RUNS) { read(account).measured.seconds.toInt() }
            val (walk, rows) = median(RUNS) {
                runBlocking {
                    val profile = account.fixture.taskDao.getAllTasksOnce(account.userId)
                    val every = account.fixture.workSessionDao.getAllForUser(account.userId)
                    val open = profile.count { !it.archived && !isCompletedSpelling(it.status.name) }
                    val seconds = every.sumOf { it.durationSeconds ?: 0L }
                    profile.size + every.size to open + seconds.toInt()
                }.first
            }
            println(
                String.format(
                    "dashboard  profile=%5d (%5d rows loaded the old way) | aggregates %6.2f ms | " +
                        "loading and folding the account %6.2f ms",
                    size, rows, aggregate, walk
                )
            )
            Cost(size, aggregate, walk)
        }

        costs.forEach { cost ->
            assertTrue(
                "the whole dashboard read took ${cost.read} ms on a ${cost.size}-row account",
                cost.read < READ_CEILING_MS
            )
        }

        // The numbers the read has to keep: ten times the account is not allowed to become ten times the
        // wait. Part of the dashboard is about the account rather than about a window — the panel counts
        // every open row and the share divides everything ever finished — so this read does grow, and the
        // measurement says how much.
        //
        // The bound is absolute milliseconds rather than a multiple, because a multiple of a timing this
        // small measures the runner rather than the read: the same code passed at 27.8 → 57.8 ms and failed
        // on a CI runner at 3.6 → 16.8 ms, where a fourth of the wait is two milliseconds of scheduling and
        // the whole read still costs a fortieth of what the ceiling allows. What the claim needs is that the
        // tenfold account adds a bounded amount of time — and the way this screen used to work, loading every
        // row, adds more than that bound, which the walk comparison below keeps proving.
        val read = costs.map { it.read }
        assertTrue(
            "the read grew from ${read.first()} ms to ${read.last()} ms, adding " +
                "${read.last() - read.first()} ms while the account grew tenfold, over the " +
                "$READ_GROWTH_MS the aggregate read is allowed to need for that",
            read.last() - read.first() < READ_GROWTH_MS
        )

        // The same account, counted the way this screen used to count it. Measured on this harness at
        // 22.1 / 94.6 / 159.0 ms against the read's 27.8 / 41.8 / 57.8: at a thousand rows the eight
        // indexed statements cost about what loading the rows does, and from five thousand on they cost
        // between two and three times less — a gap that only widens, which is the point.
        val walk = costs.map { it.walk }
        assertTrue(
            "loading the account grew from ${walk.first()} ms to ${walk.last()} ms",
            walk.last() > walk.first() * WALK_GROWTH_FACTOR
        )
        costs.drop(1).forEach { cost ->
            assertTrue(
                "at ${cost.size} rows the read costs ${cost.read} ms and loading the same account " +
                    "${cost.walk} ms",
                cost.read < cost.walk
            )
        }
    }

    @Test
    fun `counting in SQL answers what loading the account used to answer`() {
        val account = account(LARGEST)
        val snapshot = read(account)
        val period = account.fixture.workStatisticsRepository.window(WorkPeriod.THIS_WEEK, today, zone)

        val profile = runBlocking { account.fixture.taskDao.getAllTasksOnce(account.userId) }
        val every = runBlocking { account.fixture.workSessionDao.getAllForUser(account.userId) }
        val open = profile.filter { !it.archived && !isCompletedSpelling(it.status.name) }
        val worked = every.filter {
            !it.isDeleted && it.endedAt != null && it.durationSeconds != null &&
                it.localDay >= period.fromDay && it.localDay < period.toDay
        }

        assertEquals(
            "the panel counts the rows a fold would have walked; only the loading stopped",
            open.size,
            snapshot.health.open
        )
        assertEquals(
            "and the totals sum the very stretches the old fold summed",
            worked.sumOf { it.durationSeconds!! },
            snapshot.measured.seconds
        )
        assertEquals(worked.size, snapshot.measured.intervals)
        assertEquals(
            "the bars are the same days the fold lands on, completions and all",
            expected(account).trend,
            snapshot.trend.map { it.localDay to it.completions.toLong() }
        )
        assertTrue(
            "the old screen pulled ${profile.size} tasks and ${every.size} intervals over the boundary " +
                "between SQLite and the JVM to draw this one week; the read answers it with " +
                "${snapshot.trend.size + snapshot.topTasks.size} figures instead",
            profile.size + every.size > LOADED_ROWS_FLOOR
        )
    }

    /** Squares of the grid that have anything in them — the day count the picture is made of. */
    private fun litSquares(snapshot: DashboardSnapshot): Int =
        snapshot.heatmap.weeks.flatten().count { it.seconds > 0L }

    /**
     * Median of [runs] repetitions after two warm-up reads, as the calendar's benchmark does it: a single
     * timing on a shared runner says nothing and a minimum flatters the machine.
     */
    private fun median(runs: Int, block: () -> Int): Pair<Double, Int> {
        repeat(2) { block() }
        var last = 0
        val samples = (1..runs).map {
            val started = System.nanoTime()
            last = block()
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        return samples[samples.size / 2] to last
    }

    private data class Shape(
        val size: Int,
        val rows: Int,
        val values: Int,
        val busiest: Int
    )

    /** One account size, and what the two ways of answering the dashboard cost on it. */
    private data class Cost(val size: Int, val read: Double, val walk: Double)

    private companion object {
        val SIZES = intArrayOf(1_000, 5_000, 10_000)
        const val LARGEST = 10_000
        const val OTHER_PROFILE = 999_999L

        /** The cap the dashboard's busiest list is read with; the number the shape claim needs. */
        const val TOP_TASK_LIMIT = 5

        const val INSERT_CHUNK = 400
        const val RUNS = 5

        /**
         * Rows the old screen had to pull across the boundary between SQLite and the JVM to draw one
         * dashboard. Stated as a floor rather than a ratio because the point is the order of magnitude,
         * and an assertion about a ratio would be an assertion about a shared runner's scheduling.
         */
        const val LOADED_ROWS_FLOOR = 10_000

        /**
         * One visit to the dashboard on production Room under Robolectric, thread handoffs and the Kotlin
         * folds included. Measured here at 27.8, 41.8 and 57.8 ms for 1k, 5k and 10k rows, so this is
         * three times the worst of them: loose enough that a loaded shared runner cannot fail it, tight
         * enough to catch the read going back to walking the account.
         */
        const val READ_CEILING_MS = 200.0

        /**
         * What ten times the account may add to the read, in milliseconds. Measured at about 30 ms on this
         * harness (27.8 → 57.8) and 13 ms on a CI runner (3.6 → 16.8); the old way of counting it — this
         * divided by a multiple of the fastest read — failed on the CI numbers, and the walk over the same
         * accounts grows by about 137 ms, which this still catches.
         */
        const val READ_GROWTH_MS = 100.0

        /** The same growth of the read that loads every row: measured at seven times over the range. */
        const val WALK_GROWTH_FACTOR = 3.0
    }
}
