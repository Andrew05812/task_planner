package com.taskplanner.ui.statistics

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.model.TaskHealthCounts
import com.taskplanner.data.model.TaskStatusCount
import com.taskplanner.data.model.TaskWorkSummary
import com.taskplanner.data.model.WorkPeriodSummary
import com.taskplanner.data.repository.DashboardRequest
import com.taskplanner.data.repository.DashboardSnapshot
import com.taskplanner.data.repository.PreviousPeriod
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.data.statistics.ChangeDirection
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.statistics.HealthBucket
import com.taskplanner.data.statistics.HeatmapCell
import com.taskplanner.data.statistics.HeatmapGrid
import com.taskplanner.data.statistics.TrendDay
import com.taskplanner.databinding.FragmentStatisticsBinding
import com.taskplanner.ui.util.WorkTimeFormat
import com.taskplanner.ui.util.labelRes
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashboard's figures turned into what the cards show, drawn on the real screen under the real theme.
 *
 * [DashboardRenderer] is the one place that decides which number lands in which label, which colour a bar
 * part wears, and which line a period has no right to print — so every promise worth keeping about the
 * dashboard is a fact about this function, and none of it needs a device or a database to prove. What is
 * guarded here are the failures a reader would actually suffer: a chart that is not redrawn keeps the
 * previous period's marks under a heading that has since moved on, so a snapshot-free render must empty
 * every card rather than leave the old ones standing; a legend and its bar that were handed different
 * lists describe one figure twice; a heading that names a window the figures were not read from; a share
 * printed as "0%" for an account with nothing to divide; a physically deleted task shown as a blank line
 * instead of admitted as gone; and a category painted with a colour the user never chose. Each check below
 * reads back through the views' own seams (currentValues, currentGrid, currentColors, entries) so the
 * assertion is about what reached the canvas, not about pixels.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardRendererTest {

    private lateinit var themed: Context

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        // The fragment always builds inside the activity's theme: ?attr/textAppearance* and the Material3
        // card styles do not resolve without it, which is not how the screen is used.
        themed = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
    }

    private fun screen(): FragmentStatisticsBinding =
        FragmentStatisticsBinding.inflate(LayoutInflater.from(themed))

    // region fixtures

    /** A fixed day and zone so no fixture quietly depends on WeekFields/locale or LocalDate.now(). */
    private fun request(period: WorkPeriod = WorkPeriod.THIS_WEEK): DashboardRequest = DashboardRequest(
        period = period,
        heatmapWeeks = 2,
        onDay = LocalDate.of(2026, 3, 10),
        zone = ZoneId.of("UTC"),
        firstDayOfWeek = DayOfWeek.MONDAY
    )

    private fun grid(busiest: Long = 1_800L): HeatmapGrid {
        val cells = (0 until 14).map { index ->
            HeatmapCell(
                localDay = index.toLong(),
                seconds = if (index == 3) busiest else 60L,
                completions = 0,
                level = if (index == 3) 4 else 1,
                isFuture = false
            )
        }
        return HeatmapGrid(weeks = cells.chunked(7), busiestDaySeconds = busiest)
    }

    private fun snapshot(
        period: WorkPeriod = WorkPeriod.THIS_WEEK,
        measured: WorkPeriodSummary = WorkPeriodSummary(seconds = 5_400L, intervals = 12, sessions = 4),
        trend: List<TrendDay> = listOf(TrendDay(localDay = 1L, seconds = 3_000L, completions = 2)),
        heatmap: HeatmapGrid = grid(),
        health: TaskHealthCounts = TaskHealthCounts(
            overdue = 2, dueToday = 3, dueThisWeek = 1, withoutDeadline = 4,
            inProgress = 2, stale = 5, open = 15
        ),
        completed: Int = 7,
        created: Int = 3,
        statusCounts: List<TaskStatusCount> = listOf(
            TaskStatusCount(status = "COMPLETED", count = 31),
            TaskStatusCount(status = "TODO", count = 19)
        ),
        categories: List<CategoryWorkSummary> = emptyList(),
        topTasks: List<TaskWorkSummary> = emptyList(),
        streakDays: Int = 4,
        // The default is a period that measured 5 400 s over the 4 500 s before it — a fifth more, which is
        // a percentage the screen can print honestly — and 7 finishes over 4. Tests that need another shape
        // of comparison say so on this one parameter rather than on two.
        previous: PreviousPeriod = PreviousPeriod(measuredSeconds = 4_500L, completed = 4)
    ): DashboardSnapshot = DashboardSnapshot(
        request = request(period),
        measured = measured,
        trend = trend,
        heatmap = heatmap,
        health = health,
        completed = completed,
        created = created,
        statusCounts = statusCounts,
        categories = categories,
        topTasks = topTasks,
        streakDays = streakDays,
        previous = previous
    )

    // endregion

    // region 1: nothing is left standing when there is no snapshot

    @Test
    fun `a render with no snapshot empties every card rather than keeping the last period's marks`() {
        val binding = screen()
        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    categories = listOf(
                        CategoryWorkSummary(categoryId = 1L, categoryName = "Work", seconds = 900L, intervals = 1),
                        CategoryWorkSummary(categoryId = 2L, categoryName = "Home", seconds = 600L, intervals = 1)
                    ),
                    topTasks = listOf(TaskWorkSummary(taskId = 1L, title = "Report", seconds = 900L, intervals = 1))
                ),
                isLoading = false
            )
        )
        // Precondition: the cards genuinely hold something before they are cleared, or the clearing proves
        // nothing about a chart that was never painted.
        assertTrue("the trend held days before the empty render", binding.trendWorkChart.currentValues.isNotEmpty())
        assertTrue("the top-task list held a row before the empty render", binding.topTasksContainer.childCount > 0)

        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = null, isLoading = false)
        )

        assertEquals(
            "a bar chart that is not redrawn keeps the previous period's columns on screen",
            emptyList<Long>(),
            binding.trendWorkChart.currentValues
        )
        assertEquals(
            "and so does its companion of completions",
            emptyList<Long>(),
            binding.trendCompletionsChart.currentValues
        )
        assertNull(
            "the grid must not stay shaded with days nobody re-read",
            binding.activityGrid.currentGrid
        )
        assertEquals("the health bar has no parts left", emptyList<Long>(), binding.healthBar.currentValues)
        assertEquals("nor any colours", 0, binding.healthBar.currentColors.size)
        assertEquals("the category bar has no parts left", emptyList<Long>(), binding.categoryBar.currentValues)
        assertEquals("nor any colours", 0, binding.categoryBar.currentColors.size)
        assertEquals("the health legend explains nothing", 0, binding.healthLegend.entries.size)
        assertEquals("the category legend explains nothing", 0, binding.categoryLegend.entries.size)
        assertEquals("the grid legend explains nothing", 0, binding.activityGridLegend.entries.size)
        assertEquals(
            "the busiest-task list keeps no rows",
            0,
            binding.topTasksContainer.childCount
        )
        assertTrue("the headline is blank", binding.dashboardHeadline.text.isNullOrEmpty())
        assertTrue("its detail line is blank", binding.dashboardHeadlineDetails.text.isNullOrEmpty())
        assertFalse("the comparison line goes with it", binding.dashboardWorkChange.isVisible)
        assertFalse("and so does the one under it", binding.dashboardCompletionsChange.isVisible)
        assertTrue("the finished count is blank", binding.dashboardFinished.text.isNullOrEmpty())
        assertTrue("the added count is blank", binding.dashboardAdded.text.isNullOrEmpty())
        assertTrue("the streak is blank", binding.dashboardStreak.text.isNullOrEmpty())
        assertTrue("the rate is blank", binding.dashboardRate.text.isNullOrEmpty())
        assertTrue("the health title is blank", binding.healthTitle.text.isNullOrEmpty())
        assertFalse("the rate detail is taken away with the rate", binding.dashboardRateDetails.isVisible)
        assertFalse("the grid's busiest line is taken away with the grid", binding.activityGridBusiest.isVisible)
    }

    // endregion

    // region 2: progress, empty and content visibility

    @Test
    fun `loading shows the spinner and hides both the scroll and the empty state`() {
        val binding = screen()
        DashboardRenderer.render(binding, DashboardState(snapshot = null, isLoading = true))

        assertTrue(binding.dashboardProgress.isVisible)
        assertFalse(binding.dashboardEmpty.isVisible)
        assertFalse(binding.dashboardScroll.isVisible)
    }

    @Test
    fun `an account with no tasks and no measured work is the empty state`() {
        val binding = screen()
        val empty = snapshot(
            measured = WorkPeriodSummary(seconds = 0L, intervals = 0, sessions = 0),
            statusCounts = emptyList()
        )
        assertTrue("the fixture really is empty", empty.isEmpty)

        DashboardRenderer.render(binding, DashboardState(snapshot = empty, isLoading = false))

        assertTrue(binding.dashboardEmpty.isVisible)
        assertFalse(binding.dashboardScroll.isVisible)
        assertFalse(binding.dashboardProgress.isVisible)
    }

    @Test
    fun `a signed-out null snapshot is empty too`() {
        val binding = screen()
        DashboardRenderer.render(binding, DashboardState(snapshot = null, isLoading = false))

        assertTrue(binding.dashboardEmpty.isVisible)
        assertFalse(binding.dashboardScroll.isVisible)
    }

    @Test
    fun `a snapshot with work in it shows the scroll and hides spinner and empty`() {
        val binding = screen()
        val full = snapshot()
        assertFalse("the fixture really is non-empty", full.isEmpty)

        DashboardRenderer.render(binding, DashboardState(snapshot = full, isLoading = false))

        assertTrue(binding.dashboardScroll.isVisible)
        assertFalse(binding.dashboardEmpty.isVisible)
        assertFalse(binding.dashboardProgress.isVisible)
    }

    // endregion

    // region 3: headline

    @Test
    fun `the headline prints the measured figure and its own window's period label`() {
        val binding = screen()
        val state = DashboardState(
            // The screen believes it is on TODAY while the figures were read for LAST_MONTH; the heading
            // must follow the snapshot, never the screen's belief.
            period = WorkPeriod.TODAY,
            snapshot = snapshot(
                period = WorkPeriod.LAST_MONTH,
                measured = WorkPeriodSummary(seconds = 7_500L, intervals = 12, sessions = 4)
            ),
            isLoading = false
        )

        DashboardRenderer.render(binding, state)

        val snapshot = requireNotNull(state.snapshot)
        assertEquals(
            "the big figure is the measured seconds, in the compact words the card can fit",
            WorkTimeFormat.compact(themed, 7_500L),
            binding.dashboardHeadline.text.toString()
        )
        val expected = themed.getString(
            R.string.dashboard_headline_meta,
            themed.resources.getQuantityString(R.plurals.history_stretch_count, 12, 12),
            themed.resources.getQuantityString(R.plurals.history_session_count, 4, 4),
            themed.getString(snapshot.request.period.labelRes())
        )
        assertEquals(
            "the detail line is stretches, sessions and the snapshot's own period",
            expected,
            binding.dashboardHeadlineDetails.text.toString()
        )
        assertTrue(
            "the label shown is the snapshot's period, not the state's",
            binding.dashboardHeadlineDetails.text.contains(themed.getString(R.string.history_period_last_month))
        )
        assertFalse(
            "and never the period the screen merely thinks it is on",
            binding.dashboardHeadlineDetails.text.contains(themed.getString(R.string.history_period_today))
        )
    }

    // endregion

    // region 4: completion rate

    @Test
    fun `a rate with nothing to divide says so and hides the detail line`() {
        val binding = screen()
        // totalTasks == 0 makes completionRate null while the account still measured work, so the card
        // renders but has no share to print.
        val state = snapshot(
            measured = WorkPeriodSummary(seconds = 600L, intervals = 2, sessions = 1),
            statusCounts = emptyList()
        )
        assertNull("the fixture really has no share", state.completionRate)

        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = state, isLoading = false)
        )

        assertEquals(themed.getString(R.string.dashboard_rate_unknown), binding.dashboardRate.text.toString())
        assertFalse(
            "a share that does not exist has no 'x of y tasks' to show beside it",
            binding.dashboardRateDetails.isVisible
        )
    }

    @Test
    fun `a real rate prints as whole percent with the counts beside it`() {
        val binding = screen()
        val state = snapshot(
            statusCounts = listOf(
                TaskStatusCount(status = "COMPLETED", count = 31),
                TaskStatusCount(status = "TODO", count = 19)
            )
        )
        assertEquals(31, state.completedEver)
        assertEquals(50, state.totalTasks)
        val rate = requireNotNull(state.completionRate)

        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = state, isLoading = false)
        )

        // The exact figure NumberFormat produces for the same float, so the test asserts the rounding rule
        // (a glance, not 61.904762%) without hard-coding a locale's symbol.
        val expectedPercent = NumberFormat.getPercentInstance(Locale.getDefault())
            .apply { maximumFractionDigits = 0 }
            .format(rate.toDouble())
        assertEquals(
            themed.getString(R.string.dashboard_rate, expectedPercent),
            binding.dashboardRate.text.toString()
        )
        assertTrue(binding.dashboardRateDetails.isVisible)
        assertEquals(
            themed.resources.getQuantityString(R.plurals.dashboard_rate_tasks, 31, 31, 50),
            binding.dashboardRateDetails.text.toString()
        )
    }

    // endregion

    // region 5: health

    @Test
    fun `the health bar and its legend are handed the same buckets in the same order`() {
        val binding = screen()
        val counts = TaskHealthCounts(
            overdue = 2, dueToday = 3, dueThisWeek = 1, withoutDeadline = 4,
            inProgress = 2, stale = 5, open = 15
        )
        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = snapshot(health = counts), isLoading = false)
        )

        val buckets = DashboardMath.healthBuckets(counts)
        assertEquals(
            "the bar's parts, in the order DashboardMath orders them",
            listOf(2L, 3L, 1L, 5L, 4L),
            binding.healthBar.currentValues
        )
        assertEquals(
            "one colour per value — the bar and the legend cannot disagree about how many parts there are",
            buckets.size,
            binding.healthBar.currentColors.size
        )
        assertEquals(
            "each part wears its bucket's colour, in order",
            buckets.map { ContextCompat.getColor(themed, healthColorRes(it.bucket)) },
            binding.healthBar.currentColors.toList()
        )

        assertEquals("the legend has one row per part", buckets.size, binding.healthLegend.entries.size)
        assertEquals(
            "the legend lists the same colours in the same order as the bar",
            buckets.map { ContextCompat.getColor(themed, healthColorRes(it.bucket)) },
            binding.healthLegend.entries.map { it.color }
        )
        assertEquals(
            "the legend labels are the five health states in bucket order",
            buckets.map { themed.getString(healthLabelRes(it.bucket)) },
            binding.healthLegend.entries.map { it.label.toString() }
        )
        val number = NumberFormat.getNumberInstance(Locale.getDefault())
        assertEquals(
            "each legend row carries its own count",
            buckets.map { number.format(it.count) },
            binding.healthLegend.entries.map { it.detail.toString() }
        )

        assertEquals(
            themed.getString(
                R.string.dashboard_health_title_format,
                themed.resources.getQuantityString(R.plurals.dashboard_open, 15, 15)
            ),
            binding.healthTitle.text.toString()
        )
    }

    @Test
    fun `the cross-cutting health figures appear only when there are any`() {
        val binding = screen()
        val counts = TaskHealthCounts(
            overdue = 2, dueToday = 3, dueThisWeek = 1, withoutDeadline = 4,
            inProgress = 2, stale = 5, open = 15
        )
        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = snapshot(health = counts), isLoading = false)
        )

        assertTrue("being-worked-on is a figure beside the bar, shown when non-zero", binding.healthInProgress.isVisible)
        assertEquals(themed.getString(R.string.dashboard_in_progress, 2), binding.healthInProgress.text.toString())
        assertTrue("untouched is a figure beside the bar, shown when non-zero", binding.healthStale.isVisible)
        assertEquals(themed.getString(R.string.dashboard_stale, 5), binding.healthStale.text.toString())

        val quiet = TaskHealthCounts(
            overdue = 2, dueToday = 3, dueThisWeek = 1, withoutDeadline = 4,
            inProgress = 0, stale = 0, open = 10
        )
        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = snapshot(health = quiet), isLoading = false)
        )
        assertFalse("zero being worked on is not printed", binding.healthInProgress.isVisible)
        assertFalse("zero untouched is not printed", binding.healthStale.isVisible)
    }

    // endregion

    // region 6: categories

    @Test
    fun `categories are largest-first and every slice wears the colour it was given`() {
        val stored = 0xFF00AA00.toInt()
        val noneColor = ContextCompat.getColor(themed, R.color.chart_category_none)
        val binding = screen()
        // Delivered smallest-first with a null-name/null-colour bucket last; order must not survive.
        val categories = listOf(
            CategoryWorkSummary(categoryId = null, categoryName = null, seconds = 1_800L, intervals = 1, color = null),
            CategoryWorkSummary(categoryId = 1L, categoryName = "Work", seconds = 3_600L, intervals = 2, color = stored),
            CategoryWorkSummary(categoryId = 2L, categoryName = "Home", seconds = 7_200L, intervals = 4, color = null)
        )

        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    measured = WorkPeriodSummary(seconds = 12_600L, intervals = 7, sessions = 3),
                    categories = categories
                ),
                isLoading = false
            )
        )

        assertEquals(
            "the bar is ordered by seconds descending, whatever order the read arrived in",
            listOf(7_200L, 3_600L, 1_800L),
            binding.categoryBar.currentValues
        )
        assertEquals(
            "a stored colour is painted exactly as the user chose it; a missing one falls back to the neutral shade",
            listOf(noneColor, stored, noneColor),
            binding.categoryBar.currentColors.toList()
        )
        assertEquals(
            "the legend names each slice, with the null bucket labelled as no-category",
            listOf("Home", "Work", themed.getString(R.string.dashboard_category_none)),
            binding.categoryLegend.entries.map { it.label.toString() }
        )
        assertEquals(
            "the legend and the bar wear the same colours in the same order",
            binding.categoryBar.currentColors.toList(),
            binding.categoryLegend.entries.map { it.color }
        )
        assertEquals(
            "each legend row shows its own measured time",
            listOf(7_200L, 3_600L, 1_800L).map { WorkTimeFormat.compact(themed, it) },
            binding.categoryLegend.entries.map { it.detail.toString() }
        )
    }

    // endregion

    // region 7: activity grid

    @Test
    fun `the grid shows its own picture, a two-ended legend, and a busiest line only when there is one`() {
        val binding = screen()
        val heat = grid(busiest = 1_800L)

        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = snapshot(heatmap = heat), isLoading = false)
        )

        assertSame(
            "the grid gets the snapshot's own heatmap",
            heat,
            binding.activityGrid.currentGrid
        )
        assertEquals("the legend is only the two ends of the scale", 2, binding.activityGridLegend.entries.size)
        assertEquals(themed.getString(R.string.dashboard_grid_quiet), binding.activityGridLegend.entries[0].label.toString())
        assertEquals(themed.getString(R.string.dashboard_grid_busy), binding.activityGridLegend.entries[1].label.toString())
        assertNull("the quiet end carries no detail figure", binding.activityGridLegend.entries[0].detail)
        assertNull("nor does the busy end", binding.activityGridLegend.entries[1].detail)
        assertEquals(binding.activityGrid.colorForLevel(0), binding.activityGridLegend.entries[0].color)
        assertEquals(binding.activityGrid.colorForLevel(4), binding.activityGridLegend.entries[1].color)

        assertTrue(binding.activityGridBusiest.isVisible)
        assertEquals(
            themed.getString(R.string.dashboard_grid_busiest, WorkTimeFormat.compact(themed, 1_800L)),
            binding.activityGridBusiest.text.toString()
        )
    }

    @Test
    fun `a grid with no busiest day prints no busiest line`() {
        val binding = screen()
        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = snapshot(heatmap = grid(busiest = 0L)), isLoading = false)
        )

        assertFalse(
            "a zero-second peak is not a claim worth making",
            binding.activityGridBusiest.isVisible
        )
    }

    // endregion

    // region 8: top tasks

    @Test
    fun `the busiest list names each task and admits the ones that are gone`() {
        val binding = screen()
        val tasks = listOf(
            TaskWorkSummary(taskId = 1L, title = "Write the report", seconds = 5_400L, intervals = 3),
            TaskWorkSummary(taskId = 2L, title = null, seconds = 600L, intervals = 1)
        )

        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    measured = WorkPeriodSummary(seconds = 6_000L, intervals = 4, sessions = 2),
                    topTasks = tasks
                ),
                isLoading = false
            )
        )

        assertEquals("one inflated row per task", 2, binding.topTasksContainer.childCount)
        val first = binding.topTasksContainer.getChildAt(0)
        assertEquals(
            "a live task prints its own title",
            "Write the report",
            first.findViewById<TextView>(R.id.dashboardTaskTitle).text.toString()
        )
        assertEquals(
            themed.getString(
                R.string.dashboard_task_meta,
                WorkTimeFormat.compact(themed, 5_400L),
                themed.resources.getQuantityString(R.plurals.history_stretch_count, 3, 3)
            ),
            first.findViewById<TextView>(R.id.dashboardTaskMeta).text.toString()
        )

        val gone = binding.topTasksContainer.getChildAt(1)
        assertEquals(
            "a physically deleted task keeps its measurements, so the row says it is gone rather than printing blank",
            themed.getString(R.string.dashboard_task_gone),
            gone.findViewById<TextView>(R.id.dashboardTaskTitle).text.toString()
        )
        assertFalse(gone.findViewById<TextView>(R.id.dashboardTaskTitle).text.isNullOrEmpty())
    }

    @Test
    fun `an empty busiest list shows the empty line and no rows`() {
        val binding = screen()

        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    measured = WorkPeriodSummary(seconds = 600L, intervals = 1, sessions = 1),
                    topTasks = emptyList()
                ),
                isLoading = false
            )
        )

        assertTrue(binding.topTasksEmpty.isVisible)
        assertEquals(0, binding.topTasksContainer.childCount)
        assertEquals(themed.getString(R.string.dashboard_top_tasks_empty), binding.topTasksEmpty.text.toString())
    }

    // endregion

    // region 8b: the period before this one

    /** The same rounding the line prints with, so a test can name a percentage without hard-coding a locale. */
    private fun percent(fraction: Double): String = NumberFormat.getPercentInstance(Locale.getDefault())
        .apply { maximumFractionDigits = 0 }
        .format(fraction)

    @Test
    fun `a busy period after an empty one is said in words rather than divided by nothing`() {
        val binding = screen()
        // A first week of tracked work: the figure the comparison would have to divide by is zero.
        val state = snapshot(previous = PreviousPeriod(measuredSeconds = 0L, completed = 0))
        assertNull("the change really has no percentage", state.workChange.fraction)

        DashboardRenderer.render(binding, DashboardState(snapshot = state, isLoading = false))

        assertEquals(
            themed.getString(R.string.dashboard_change_from_nothing),
            binding.dashboardWorkChange.text.toString()
        )
        assertFalse(
            "and the line carries no percentage, because an increase over no base is not one",
            binding.dashboardWorkChange.text.contains("%")
        )
        // The finishes are the same trap and are answered the same way: two counts, no division.
        assertEquals(
            themed.getString(R.string.dashboard_completions_against, 7, 0),
            binding.dashboardCompletionsChange.text.toString()
        )
    }

    @Test
    fun `two empty periods are not a comparison of anything`() {
        val binding = screen()
        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    measured = WorkPeriodSummary(seconds = 0L, intervals = 0, sessions = 0),
                    completed = 0,
                    previous = PreviousPeriod(measuredSeconds = 0L, completed = 0)
                ),
                isLoading = false
            )
        )

        assertFalse(
            "nothing stayed the same: there was nothing to stay",
            binding.dashboardWorkChange.isVisible
        )
        assertFalse("and the same for the finishes", binding.dashboardCompletionsChange.isVisible)
    }

    @Test
    fun `a rise prints the percentage it is and a fall prints its own`() {
        val binding = screen()
        val up = snapshot()
        // The default fixture is 5 400 s against 4 500 s: a fifth more than the period before.
        assertEquals(0.2, requireNotNull(up.workChange.fraction), 1e-9)
        DashboardRenderer.render(binding, DashboardState(snapshot = up, isLoading = false))
        assertEquals(
            themed.getString(R.string.dashboard_change_up, percent(0.2)),
            binding.dashboardWorkChange.text.toString()
        )

        val down = snapshot(
            measured = WorkPeriodSummary(seconds = 3_000L, intervals = 5, sessions = 2),
            previous = PreviousPeriod(measuredSeconds = 4_500L, completed = 4)
        )
        DashboardRenderer.render(binding, DashboardState(snapshot = down, isLoading = false))
        assertEquals(
            themed.getString(R.string.dashboard_change_down, percent(1.0 / 3.0)),
            binding.dashboardWorkChange.text.toString()
        )
    }

    @Test
    fun `measuring nothing where the period before measured something is a hundred percent down`() {
        val binding = screen()
        val state = snapshot(
            measured = WorkPeriodSummary(seconds = 0L, intervals = 0, sessions = 0),
            completed = 0,
            previous = PreviousPeriod(measuredSeconds = 1_800L, completed = 3)
        )
        assertEquals(
            "a fall to nothing has a base, so it has a percentage — and it is the whole of the change",
            -1.0,
            requireNotNull(state.workChange.fraction),
            1e-9
        )

        DashboardRenderer.render(binding, DashboardState(snapshot = state, isLoading = false))

        assertEquals(
            themed.getString(R.string.dashboard_change_down, percent(1.0)),
            binding.dashboardWorkChange.text.toString()
        )
        assertTrue(binding.dashboardCompletionsChange.isVisible)
        assertEquals(
            themed.getString(R.string.dashboard_completions_against, 0, 3),
            binding.dashboardCompletionsChange.text.toString()
        )
    }

    @Test
    fun `a change too small for a whole percent is admitted rather than rounded to zero`() {
        val binding = screen()
        val state = snapshot(
            measured = WorkPeriodSummary(seconds = 5_401L, intervals = 12, sessions = 4),
            previous = PreviousPeriod(measuredSeconds = 5_400L, completed = 7)
        )
        assertTrue(
            "the fixture really is under half a percent",
            kotlin.math.abs(requireNotNull(state.workChange.fraction)) < 0.005
        )

        DashboardRenderer.render(binding, DashboardState(snapshot = state, isLoading = false))

        assertEquals(
            "one second more rounds to no percent at all, and the line says what happened instead",
            themed.getString(R.string.dashboard_change_slightly_up),
            binding.dashboardWorkChange.text.toString()
        )
        assertFalse(binding.dashboardWorkChange.text.toString().contains("%"))

        val under = snapshot(
            measured = WorkPeriodSummary(seconds = 5_399L, intervals = 12, sessions = 4),
            previous = PreviousPeriod(measuredSeconds = 5_400L, completed = 7)
        )
        DashboardRenderer.render(binding, DashboardState(snapshot = under, isLoading = false))
        assertEquals(
            themed.getString(R.string.dashboard_change_slightly_down),
            binding.dashboardWorkChange.text.toString()
        )
    }

    @Test
    fun `the same figure in both periods is said as the same`() {
        val binding = screen()
        val state = snapshot(previous = PreviousPeriod(measuredSeconds = 5_400L, completed = 7))
        assertEquals(ChangeDirection.FLAT, state.workChange.direction)

        DashboardRenderer.render(binding, DashboardState(snapshot = state, isLoading = false))

        assertEquals(
            themed.getString(R.string.dashboard_change_flat),
            binding.dashboardWorkChange.text.toString()
        )
        assertEquals(
            themed.getString(R.string.dashboard_completions_against, 7, 7),
            binding.dashboardCompletionsChange.text.toString()
        )
    }

    // endregion

    // region 9: where a row leads

    /** One row the account can open and one bucket it cannot, in the order the renderer will sort them. */
    private val categoryRows = listOf(
        CategoryWorkSummary(categoryId = 2L, categoryName = "Home", seconds = 7_200L, intervals = 4, color = null),
        CategoryWorkSummary(categoryId = null, categoryName = null, seconds = 1_800L, intervals = 1, color = null)
    )

    @Test
    fun `a category row opens the category it names and the ungrouped bucket opens nothing`() {
        val binding = screen()
        val opened = mutableListOf<Long>()

        DashboardRenderer.render(
            binding,
            DashboardState(snapshot = snapshot(categories = categoryRows), isLoading = false),
            DashboardNavigation(openCategory = { opened.add(it) })
        )

        val entries = binding.categoryLegend.entries
        assertEquals(2, entries.size)
        assertNotNull("the row that names a category the account still has has somewhere to go", entries[0].onClick)
        assertNull("work nobody filed is not a category, so it has no screen of its own", entries[1].onClick)

        assertTrue("the row the legend drew is the clickable one", binding.categoryLegend.getChildAt(0).isClickable)
        assertFalse(
            "and the row with no destination does not press",
            binding.categoryLegend.getChildAt(1).isClickable
        )

        entries[0].onClick?.invoke()
        assertEquals("the tap carries the id of the category in that row, not of another", listOf(2L), opened)
    }

    @Test
    fun `a busy task row opens its task and a row for a task that is gone opens nothing`() {
        val binding = screen()
        val opened = mutableListOf<Long>()

        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    topTasks = listOf(
                        TaskWorkSummary(taskId = 7L, title = "Write the report", seconds = 5_400L, intervals = 3),
                        TaskWorkSummary(taskId = 8L, title = null, seconds = 600L, intervals = 1)
                    )
                ),
                isLoading = false
            ),
            DashboardNavigation(openTask = { opened.add(it) })
        )

        val here = binding.topTasksContainer.getChildAt(0)
        val gone = binding.topTasksContainer.getChildAt(1)

        assertTrue(here.isClickable)
        assertFalse(
            "a task deleted physically has no screen left to open, so its row says what it is rather than " +
                "promising a tap",
            gone.isClickable
        )

        here.performClick()
        assertEquals(listOf(7L), opened)
    }

    @Test
    fun `a screen with nowhere to go offers no row as a tap`() {
        val binding = screen()

        DashboardRenderer.render(
            binding,
            DashboardState(
                snapshot = snapshot(
                    categories = categoryRows,
                    topTasks = listOf(
                        TaskWorkSummary(taskId = 7L, title = "Write the report", seconds = 5_400L, intervals = 3)
                    )
                ),
                isLoading = false
            )
        )

        assertTrue(
            "no row claims a destination the screen was never given",
            binding.categoryLegend.entries.all { it.onClick == null }
        )
        assertFalse(binding.categoryLegend.getChildAt(0).isClickable)
        assertFalse(binding.topTasksContainer.getChildAt(0).isClickable)
    }

    // endregion

    // region resource maps kept in step with the renderer's private ones

    private fun healthLabelRes(bucket: HealthBucket): Int = when (bucket) {
        HealthBucket.OVERDUE -> R.string.health_overdue
        HealthBucket.DUE_TODAY -> R.string.health_due_today
        HealthBucket.DUE_THIS_WEEK -> R.string.health_due_week
        HealthBucket.DUE_LATER -> R.string.health_due_later
        HealthBucket.WITHOUT_DEADLINE -> R.string.health_without_deadline
    }

    private fun healthColorRes(bucket: HealthBucket): Int = when (bucket) {
        HealthBucket.OVERDUE -> R.color.health_overdue
        HealthBucket.DUE_TODAY -> R.color.health_due_today
        HealthBucket.DUE_THIS_WEEK -> R.color.health_due_week
        HealthBucket.DUE_LATER -> R.color.health_due_later
        HealthBucket.WITHOUT_DEADLINE -> R.color.health_without_deadline
    }

    // endregion
}
