package com.taskplanner.data.calendar

import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.ReminderRepository
import com.taskplanner.data.repository.SessionRepository
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The calendar's data layer: one bounded query per screen, the aggregation of its rows, and the
 * writes the screen performs — moving a task to another day and saving the times it was planned by.
 *
 * Two things it is built around.
 *
 * **It never reads the whole profile.** A month asks the database for the millisecond range its grid
 * covers and gets those rows live, so the screen refreshes from the same query when a task is edited,
 * dragged, synced in, or produced by a repeating series, instead of reloading everything to find out
 * that one thing changed.
 *
 * **It never completes a task's plan for the user.** Moving a task between days keeps the times that
 * exist and leaves the ones that do not absent: no duration is invented for a task that has no
 * deadline, and an estimate the user typed is not silently recalculated after a drag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CalendarRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val sessionRepository: SessionRepository,
    private val reminderRepository: ReminderRepository
) {

    /**
     * The cells of a month or week grid, live.
     *
     * [grid] is the days the screen shows, from [CalendarMath.monthGrid] or [CalendarMath.weekGrid];
     * the query window is derived from it, so a grid can never be filled from a range narrower than
     * what is on screen.
     */
    fun observeCells(grid: List<LocalDate>, displayedMonth: YearMonth?): Flow<List<CalendarAggregator.DayCell>> =
        observeRows(grid) { tasks, categories, window ->
            CalendarAggregator.cells(
                grid = grid,
                displayedMonth = displayedMonth,
                tasks = tasks,
                categories = categories,
                today = LocalDate.now(window.zone),
                now = System.currentTimeMillis(),
                zone = window.zone
            )
        }

    /** The selected day's column, live: blocks with their collisions, and the tasks with no time. */
    fun observeDay(date: LocalDate): Flow<CalendarAggregator.DayPlan> =
        observeRows(listOf(date)) { tasks, _, window ->
            CalendarAggregator.dayPlan(date, tasks, System.currentTimeMillis(), window.zone)
        }

    /** The same window read once, for a caller that computes rather than observes. */
    suspend fun cellsOnce(grid: List<LocalDate>, displayedMonth: YearMonth?): List<CalendarAggregator.DayCell> {
        val rows = rowsOnce(grid) ?: return emptyList()
        return CalendarAggregator.cells(
            grid,
            displayedMonth,
            rows.tasks,
            rows.categories,
            LocalDate.now(rows.window.zone),
            System.currentTimeMillis(),
            rows.window.zone
        )
    }

    suspend fun dayOnce(date: LocalDate): CalendarAggregator.DayPlan {
        val rows = rowsOnce(listOf(date)) ?: return CalendarAggregator.DayPlan.empty(date)
        return CalendarAggregator.dayPlan(date, rows.tasks, System.currentTimeMillis(), rows.window.zone)
    }

    /**
     * Drag-and-drop: the task moves to [target] and everything tied to its old instant moves with it.
     *
     * Each stored time is moved by the *wall clock* rather than by adding whole days of milliseconds,
     * so a 21:30 appointment dropped onto Thursday is still at 21:30 on Thursday across a summer-time
     * change. The estimate the user typed is left exactly as it was, and a task that only had a
     * deadline still only has a deadline.
     *
     * The lead times are re-pointed at the new deadline and the row is marked for sync; the same row
     * is updated in place, so a drag never produces a second task.
     *
     * Returns false when the row is not the current profile's or has no instant to move.
     */
    suspend fun moveToDay(taskId: Long, target: LocalDate): Boolean {
        val userId = sessionRepository.currentUserId() ?: return false
        val task = taskDao.getTaskForUser(userId, taskId) ?: return false
        val zone = CalendarMath.systemZone()
        // A row with no instant anywhere cannot be placed on a day, and one is not invented for it.
        CalendarAggregator.anchorOf(task) ?: return false

        val updated = task.copy(
            dueDate = task.dueDate?.let { CalendarMath.moveWallClock(it, target, zone) },
            startTime = task.startTime?.let { CalendarMath.moveWallClock(it, target, zone) },
            endTime = task.endTime?.let { CalendarMath.moveWallClock(it, target, zone) },
            updatedAt = System.currentTimeMillis(),
            syncState = SyncState.PENDING
        )
        taskDao.updateTask(updated)
        // The lead times count back from the deadline, so they follow it to the new day. A null
        // offset list means "the chips the user chose stay exactly as they were".
        reminderRepository.applyReminders(updated, null)
        return true
    }

    /**
     * Saves the times one task is planned by.
     *
     * Nulls mean "no time given", which is a real state here and not a missing value: three nulls is
     * how a user says this task belongs to a day rather than to a moment inside it. An end before its
     * start is refused instead of quietly swapped, because nonsense in the editor should not become
     * nonsense in the database.
     */
    suspend fun setPlannedTimes(
        taskId: Long,
        startMillis: Long?,
        endMillis: Long?,
        estimatedMinutes: Int?
    ): Boolean {
        val userId = sessionRepository.currentUserId() ?: return false
        val task = taskDao.getTaskForUser(userId, taskId) ?: return false
        if (startMillis != null && endMillis != null && endMillis < startMillis) return false
        val updated = task.copy(
            startTime = startMillis,
            endTime = endMillis,
            estimatedMinutes = estimatedMinutes?.takeIf { it > 0 },
            updatedAt = System.currentTimeMillis(),
            syncState = SyncState.PENDING
        )
        taskDao.updateTask(updated)
        // A task whose reminders count back from its deadline keeps them counting back from it after
        // the times change; nothing here adds a lead time the user never asked for.
        reminderRepository.applyReminders(updated, null)
        return true
    }

    private data class Window(
        val first: Long,
        /** Exclusive: the instant one past the last day the grid shows. */
        val to: Long,
        val zone: ZoneId
    )

    private data class Rows(val tasks: List<Task>, val categories: List<Category>, val window: Window)

    private fun <T> observeRows(
        grid: List<LocalDate>,
        transform: (List<Task>, List<Category>, Window) -> T
    ): Flow<T> = sessionRepository.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(transform(emptyList(), emptyList(), windowOf(grid, CalendarMath.systemZone())))
        else {
            val window = windowOf(grid, CalendarMath.systemZone())
            combine(
                taskDao.observeWindow(user.id, window.first, window.to),
                categoryDao.getAllCategories(user.id)
            ) { tasks, categories -> transform(tasks, categories, window) }
        }
    }

    private suspend fun rowsOnce(grid: List<LocalDate>): Rows? {
        val userId = sessionRepository.currentUserId() ?: return null
        val zone = CalendarMath.systemZone()
        val window = windowOf(grid, zone)
        return Rows(
            taskDao.getWindowOnce(userId, window.first, window.to),
            categoryDao.getAllCategoriesOnce(userId),
            window
        )
    }

    /** The instant range a grid covers, widened to whole local days and half-open at the top. */
    private fun windowOf(grid: List<LocalDate>, zone: ZoneId): Window {
        val first = grid.minOrNull() ?: LocalDate.now(zone)
        val last = grid.maxOrNull() ?: first
        return Window(
            CalendarMath.startOfDay(first, zone),
            CalendarMath.startOfDay(last.plusDays(1), zone),
            zone
        )
    }
}
