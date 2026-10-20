package com.taskplanner.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskHealthCounts
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.TaskStatusCount
import com.taskplanner.data.model.User
import com.taskplanner.data.model.isCompletedSpelling
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashboard's task-side aggregates, cross-checked against the same figures folded by hand.
 *
 * Same arrangement as `WorkStatisticsDaoTest`, for the same reason: rows go in through the real DAOs into
 * a real Room database, then every number is recomputed in Kotlin over the stored rows with nothing
 * shared with the SQL, and the two have to agree. A `SUM(CASE …)` that counts one row in two buckets, or
 * a `WHERE` that lets an archived row into a panel about work the user still has to do, is invisible on
 * a screen that only shows the answer — and obvious as soon as the answer has a second, slower source.
 *
 * The fixture is deliberately awkward rather than tidy: a deadline that lands exactly on a bound, a
 * completion spelled `DONE` by an old release, a task finished by a build that never recorded the
 * instant, an open task the user put away, a tombstone, and a second profile with a row of each kind.
 * Those are the cases the counts can get wrong, and none of them shows up in a clean fixture.
 */
@RunWith(RobolectricTestRunner::class)
class TaskStatisticsDaoTest {

    private lateinit var fixture: AppFixture
    private var userId: Long = -1L
    private var otherUserId: Long = -1L

    /** Mid-morning on a June day, so no daylight-saving boundary falls inside the windows. */
    private val today: LocalDate = LocalDate.of(2026, 6, 1)
    private val zone: ZoneId = CalendarMath.systemZone()

    private val now: Long get() = at(today, 9, 0)
    private val dayEnd: Long get() = at(today.plusDays(1), 0, 0)
    private val weekEnd: Long get() = at(today.plusDays(7), 0, 0)
    private val staleBefore: Long get() = at(today.minusDays(14), 0, 0)

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    /**
     * One profile holding every shape a health panel can meet, plus a second profile holding a row of
     * each kind that must never be counted for the first.
     *
     * The numbers the tests assert are these rows: ten open (of which two late, three due today, two due
     * inside the week, two with no deadline, one due later than the week), one of those being worked on
     * and two untouched for a month, one open task put away, two finished with an instant — one of them
     * in the old spelling — one finished by a build that recorded no instant, one tombstone, and
     * fourteen non-tombstoned rows in total.
     */
    private suspend fun seed() {
        val user = fixture.signIn()
        userId = user.id
        otherUserId = fixture.userDao.insertUser(User(email = "other@example.com", name = "Other"))

        task("late", due = at(today.minusDays(2), 12, 0))
        // Due at this very instant: the bound belongs to "today", not to "overdue".
        task("due-exactly-now", due = now)
        // The last instant still inside today.
        task("due-last-minute-today", due = dayEnd - 1)
        // The first instant of tomorrow: out of today, inside the coming week.
        task("due-tomorrow", due = dayEnd)
        task("due-in-six-days", due = weekEnd - 1)
        // Open, with a deadline after the week — in the panel's total, in none of its due buckets.
        task("due-next-month", due = at(today.plusDays(30), 8, 0))
        task("no-deadline")
        task("in-progress", status = TaskStatus.IN_PROGRESS, due = now + 60_000)
        task("stale", updatedAt = at(today.minusDays(30), 9, 0))
        task("stale-and-late", due = at(today.minusDays(5), 9, 0), updatedAt = at(today.minusDays(20), 9, 0))

        // Archived but *not* completed. That is not a state the app's own Archive action produces — it
        // completes the row on the way in — but it is exactly what an import or a pull from another
        // device leaves behind, and the panel has to treat both the same: a row the user put away is not
        // work they still have to do, whatever its status column says.
        val archived = task("archived-open", due = at(today.minusDays(9), 9, 0))
        rawUpdate(
            "UPDATE tasks SET archived = 1, archived_at = ${at(today, 11, 0)} WHERE id = ${archived.id}"
        )

        task("finished", status = TaskStatus.COMPLETED, completedAt = at(today, 8, 30))
        // An old release wrote the other spelling, and nothing has ever rewritten the row.
        val legacy = task("legacy-finished", status = TaskStatus.COMPLETED,
            completedAt = at(today.minusDays(1), 18, 0))
        rawUpdate("UPDATE tasks SET status = 'DONE' WHERE id = ${legacy.id}")
        // Finished by a build that had no `completed_at` yet, so the instant is unknown rather than zero.
        task("finished-by-an-old-build", status = TaskStatus.COMPLETED)

        val ghost = task("tombstone", due = at(today.minusDays(3), 9, 0))
        fixture.taskDao.softDeleteTasks(listOf(ghost.id), at(today, 12, 0))

        task("other-late", forUserId = otherUserId, due = at(today.minusDays(1), 9, 0))
        task("other-finished", forUserId = otherUserId, status = TaskStatus.COMPLETED,
            completedAt = at(today, 7, 0))
        val otherArchived = task("other-archived", forUserId = otherUserId,
            due = at(today.minusDays(4), 9, 0))
        rawUpdate("UPDATE tasks SET archived = 1 WHERE id = ${otherArchived.id}")
    }

    private suspend fun task(
        title: String,
        forUserId: Long = userId,
        due: Long? = null,
        status: TaskStatus = TaskStatus.TODO,
        completedAt: Long? = null,
        updatedAt: Long = at(today, 9, 0),
        createdAt: Long = at(today.minusDays(3), 9, 0)
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title", title = title, description = "", userId = forUserId,
                dueDate = due, status = status, completedAt = completedAt,
                createdAt = createdAt, updatedAt = updatedAt
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private fun rawUpdate(sql: String) {
        fixture.database.openHelper.writableDatabase.execSQL(sql)
    }

    /**
     * The obvious count: read every non-tombstoned row of the profile and fold it in Kotlin.
     *
     * The conditions are written out again rather than reused, which is the point — if the two disagree,
     * one of them is wrong about the data, and the test is what says so.
     */
    private suspend fun oracleHealth(): TaskHealthCounts {
        val open = fixture.taskDao.getAllTasksOnce(userId).filter { task ->
            !task.archived && !isCompletedSpelling(task.status.name)
        }
        return TaskHealthCounts(
            overdue = open.count { it.dueDate != null && it.dueDate!! < now },
            dueToday = open.count { it.dueDate != null && it.dueDate!! >= now && it.dueDate!! < dayEnd },
            dueThisWeek = open.count { it.dueDate != null && it.dueDate!! >= dayEnd && it.dueDate!! < weekEnd },
            withoutDeadline = open.count { it.dueDate == null },
            inProgress = open.count { it.status == TaskStatus.IN_PROGRESS },
            stale = open.count { it.updatedAt < staleBefore },
            open = open.size
        )
    }

    private suspend fun readHealth() =
        fixture.taskStatisticsDao.healthCounts(userId, now, dayEnd, weekEnd, staleBefore)

    @Test
    fun `health counts agree with the same figures folded by hand`() = runBlocking {
        seed()

        assertEquals(oracleHealth(), readHealth())
    }

    @Test
    fun `a deadline exactly on a bound falls on the later side of it`() = runBlocking {
        seed()

        val counts = readHealth()

        // Two half-open ranges rather than three guesses about a boundary: `due-exactly-now` is due
        // today rather than already late, `due-last-minute-today` is still today, and `due-tomorrow`
        // belongs to the week instead of to a today that has ended.
        assertEquals(2, counts.overdue)
        assertEquals(3, counts.dueToday)
        assertEquals(2, counts.dueThisWeek)
    }

    @Test
    fun `the open total includes rows every deadline bucket leaves out`() = runBlocking {
        seed()

        val counts = readHealth()

        // A task due in a month is open, has a deadline, and is neither late, today, nor this week — so
        // "open" cannot be the sum of the buckets, which is why the panel shows it as its own figure.
        assertEquals(10, counts.open)
        assertEquals(
            "one row is open with a deadline further out than the week",
            counts.open,
            counts.overdue + counts.dueToday + counts.dueThisWeek + counts.withoutDeadline + 1
        )
    }

    @Test
    fun `archived, completed and thrown-away rows are not in the health panel`() = runBlocking {
        seed()

        val counts = readHealth()

        // Four rows carry a deadline that has passed and only two of them are overdue: the archived one,
        // which the user put away on purpose, and the tombstone are not in the count. A panel that
        // shouted about them would disagree with the screen the rows are no longer on.
        assertEquals(2, counts.overdue)
        assertEquals(
            "both completion spellings are out, and the open rows add up to the total",
            oracleHealth(),
            counts
        )
    }

    @Test
    fun `status counts report every spelling the profile holds, tombstones left out`() = runBlocking {
        seed()

        val counts = fixture.taskStatisticsDao.statusCounts(userId).associate { it.status to it.count }

        // The raw TEXT rather than the enum: the grouping happened in SQLite, and the answer has to stay
        // readable for a screen that knows two of these spellings mean the same thing.
        assertEquals(
            mapOf("TODO" to 10, "IN_PROGRESS" to 1, "COMPLETED" to 2, "DONE" to 1),
            counts
        )
    }

    @Test
    fun `status counts see the archived row an active list hides`() = runBlocking {
        seed()

        val all = fixture.taskStatisticsDao.statusCounts(userId).sumOf { it.count }
        val health = readHealth()

        // The completion figure is about the account's history, so an archived row still belongs to it —
        // the opposite of what the health panel does, and why the two read different statements rather
        // than one list filtered twice.
        assertEquals(14, all)
        assertTrue("the panel counts fewer rows than the account holds", health.open < all)
        assertEquals(
            "the other profile's rows stay its own",
            mapOf("TODO" to 2, "COMPLETED" to 1),
            fixture.taskStatisticsDao.statusCounts(otherUserId).associate { it.status to it.count }
        )
    }

    @Test
    fun `a status an import spelled in lower case is a completion everywhere`() = runBlocking {
        seed()
        val imported = task("imported-by-hand", completedAt = at(today, 6, 0))
        rawUpdate("UPDATE tasks SET status = 'completed' WHERE id = ${imported.id}")

        val counts = readHealth()

        // `Converters` reads this row as finished, so every statement about completions has to as well.
        // A `WHERE status = 'COMPLETED'` would leave it in the panel as work still to do while the trend
        // counted the instant it was finished — two figures on one screen contradicting each other.
        assertEquals(oracleHealth(), counts)
        assertTrue(
            "and its completion is found by the window",
            fixture.taskStatisticsDao
                .completedInstantsInPeriod(userId, at(today, 0, 0), dayEnd)
                .contains(at(today, 6, 0))
        )
        assertEquals(
            "the header's count and the chart's rows are the same rows",
            fixture.taskStatisticsDao
                .completedInstantsInPeriod(userId, at(today, 0, 0), dayEnd).size,
            fixture.workStatisticsDao.completedTasksInPeriod(userId, at(today, 0, 0), dayEnd)
        )
    }

    @Test
    fun `archiving in the app moves a task out of the panel and into the completed count`() = runBlocking {
        seed()
        val putAway = task("taken-away-later", due = at(today.minusDays(1), 9, 0))
        val before = readHealth()
        val beforeCompleted = completedCount()

        // The app's own Archive action completes the row on the way in, so one statement's two figures
        // have to move together: the late deadline leaves the panel with the row, and the account's
        // history gains a completion. A screen that read one before and one after would show a task
        // that is both put away and still open.
        fixture.taskDao.archiveTask(putAway.id, at(today, 15, 0))

        val after = readHealth()
        assertEquals(before.overdue - 1, after.overdue)
        assertEquals(before.open - 1, after.open)
        assertEquals(beforeCompleted + 1, completedCount())
    }

    private suspend fun completedCount(): Int =
        fixture.taskStatisticsDao.statusCounts(userId)
            .filter { isCompletedSpelling(it.status) }
            .sumOf { it.count }

    @Test
    fun `completions in a window are the tasks finished inside it, oldest first`() = runBlocking {
        seed()

        val from = at(today.minusDays(2), 0, 0)
        val to = at(today.plusDays(1), 0, 0)
        val instants = fixture.taskStatisticsDao.completedInstantsInPeriod(userId, from, to)

        // Both spellings are completions. The finish by an old build is not in the result, because its
        // instant was never recorded and filling the gap from `updated_at` would put a guess into the
        // column whose whole purpose is to be believed.
        assertEquals(listOf(at(today.minusDays(1), 18, 0), at(today, 8, 30)), instants)
        assertEquals(
            "the other profile keeps its own completions",
            listOf(at(today, 7, 0)),
            fixture.taskStatisticsDao.completedInstantsInPeriod(otherUserId, from, to)
        )
    }

    @Test
    fun `the completion window is closed at the bottom and open at the top`() = runBlocking {
        seed()

        val yesterday = at(today.minusDays(1), 18, 0)
        val thisMorning = at(today, 8, 30)

        assertEquals(
            "the instant a window starts on belongs to it",
            listOf(yesterday, thisMorning),
            fixture.taskStatisticsDao.completedInstantsInPeriod(userId, yesterday, thisMorning + 1)
        )
        assertEquals(
            "the instant it ends on does not",
            listOf(yesterday),
            fixture.taskStatisticsDao.completedInstantsInPeriod(userId, yesterday, thisMorning)
        )
    }

    @Test
    fun `created in a period counts the rows made inside it`() = runBlocking {
        seed()

        val stored = fixture.taskDao.getAllTasksOnce(userId)
        val created = fixture.taskStatisticsDao.createdInPeriod(
            userId, at(today.minusDays(4), 0, 0), at(today.plusDays(1), 0, 0)
        )

        // Every row of the fixture was made three days before the anchor, so a window covering that day
        // sees the profile and a window that does not sees nothing.
        assertEquals(stored.size, created)
        assertEquals(
            0,
            fixture.taskStatisticsDao.createdInPeriod(userId, at(today, 0, 0), at(today.plusDays(1), 0, 0))
        )
    }

    @Test
    fun `a profile with nothing open answers with zeroes instead of a null`() = runBlocking {
        val user = fixture.signIn("Empty", "empty@example.com")

        val counts = fixture.taskStatisticsDao.healthCounts(user.id, now, dayEnd, weekEnd, staleBefore)

        // `SUM` over no rows is NULL in SQL, and Room reading NULL into a non-null Int is a crash — so an
        // account with no tasks has to be a screen full of honest zeroes, not a screen that dies.
        assertEquals(TaskHealthCounts(0, 0, 0, 0, 0, 0, 0), counts)
        assertEquals(emptyList<TaskStatusCount>(), fixture.taskStatisticsDao.statusCounts(user.id))
        assertEquals(0, fixture.taskStatisticsDao.createdInPeriod(user.id, 0L, Long.MAX_VALUE))
        assertEquals(
            emptyList<Long>(),
            fixture.taskStatisticsDao.completedInstantsInPeriod(user.id, 0L, Long.MAX_VALUE)
        )
    }

    @Test
    fun `the change signal moves when a task is written`() = runBlocking {
        seed()
        val before = fixture.taskDao.getAllTasksOnce(userId).maxOf { it.updatedAt }
        val after = at(today, 13, 0)

        val seen = signalAroundWrite {
            fixture.taskDao.updateTask(
                fixture.taskDao.getTaskByUuid("task-late")!!.copy(updatedAt = after, status = TaskStatus.IN_PROGRESS)
            )
        }

        // The dashboard cannot watch a grouped result, so it watches the last write to the profile's rows
        // and re-runs its own queries when that moves. Completing or reopening a task moves `updated_at`,
        // which is the whole mechanism — and the second answer is the new maximum, not a re-read of the
        // first.
        assertEquals(before, seen.first())
        assertEquals(after, seen[1])
    }

    /**
     * The first two answers of the change signal, with [write] run only after the first has arrived.
     *
     * `fixture.awaitIdle()` cannot be used here: it blocks the thread the collection runs on, so the
     * watcher would never subscribe and the write would land before anything was listening. Waiting on
     * the first answer is both what makes the test work and the proof it needs — the write is known to
     * have happened while the screen was already watching.
     */
    private suspend fun signalAroundWrite(write: suspend () -> Unit): List<Long> = coroutineScope {
        val seen = ArrayList<Long>()
        val firstAnswer = CompletableDeferred<Unit>()
        val secondAnswer = CompletableDeferred<Unit>()
        val watching = launch {
            fixture.taskStatisticsDao.observeChanges(userId).take(2).collect { value ->
                seen.add(value)
                if (seen.size == 1) firstAnswer.complete(Unit) else secondAnswer.complete(Unit)
            }
        }

        withTimeout(5_000L) { firstAnswer.await() }
        write()
        withTimeout(5_000L) { secondAnswer.await() }
        watching.cancel()
        seen.toList()
    }

    @Test
    fun `a completion window seeks the index instead of walking the profile`() {
        val plan = planOf(
            "SELECT completed_at FROM tasks WHERE user_id = 1 AND is_deleted = 0 " +
                "AND UPPER(status) IN ('COMPLETED', 'DONE') AND completed_at IS NOT NULL " +
                "AND completed_at >= 1 AND completed_at < 2 ORDER BY completed_at ASC"
        )

        assertTrue(
            "expected the completion index in the plan, got: $plan",
            plan.any { it.contains("index_tasks_user_id_completed_at") }
        )
        assertFalse(
            "a trend that walks every row the account has cannot survive years of tasks: $plan",
            plan.any { it.contains("SCAN tasks") || it.contains("SCAN TABLE tasks") }
        )
    }

    @Test
    fun `the health scan seeks the profile instead of walking the table`() {
        val plan = planOf(
            "SELECT COUNT(*) FROM tasks WHERE user_id = 1 AND is_deleted = 0 AND archived = 0 " +
                "AND UPPER(status) NOT IN ('COMPLETED', 'DONE')"
        )

        assertTrue(
            "expected the profile's rows to be sought, got: $plan",
            plan.any { it.contains("SEARCH") }
        )
        assertFalse(
            "one walk of the whole table is what this screen used to do: $plan",
            plan.any { it.contains("SCAN tasks") || it.contains("SCAN TABLE tasks") }
        )
    }

    /** `EXPLAIN QUERY PLAN` for one statement, as the text SQLite reports it. */
    private fun planOf(sql: String): List<String> {
        val cursor = fixture.database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql")
        return cursor.use { rows ->
            val index = rows.getColumnIndexOrThrow("detail")
            val out = ArrayList<String>()
            while (rows.moveToNext()) out.add(rows.getString(index))
            out
        }
    }
}
