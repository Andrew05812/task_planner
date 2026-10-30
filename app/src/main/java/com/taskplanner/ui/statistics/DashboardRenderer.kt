package com.taskplanner.ui.statistics

import android.content.Context
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.taskplanner.R
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.repository.DashboardSnapshot
import com.taskplanner.data.statistics.ChangeDirection
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.statistics.FigureChange
import com.taskplanner.data.statistics.HealthBucket
import com.taskplanner.databinding.FragmentStatisticsBinding
import com.taskplanner.databinding.ItemDashboardTaskBinding
import com.taskplanner.ui.components.ChartLegendView
import com.taskplanner.ui.util.WorkTimeFormat
import com.taskplanner.ui.util.labelRes
import com.taskplanner.ui.util.LocalisedDates
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The screens a dashboard figure can be tapped into.
 *
 * Both are absent rather than assumed, because not every row the dashboard draws has somewhere to go: the
 * bucket of work measured against no category is not a category with an id, and a task that was deleted
 * physically has no detail screen left to open. A row without a destination is drawn without a press
 * effect, so the screen never offers a tap that does nothing.
 */
data class DashboardNavigation(
    val openCategory: ((Long) -> Unit)? = null,
    val openTask: ((Long) -> Unit)? = null
)

/**
 * The dashboard's figures turned into what the cards show.
 *
 * The fragment subscribes and hands the state here; this object decides which number goes in which label,
 * which colour a bar's part wears, and which lines a period has no right to print. That split is what makes
 * the screen testable without a device: the promise "the legend and the bar were handed the same list in the
 * same order" is a fact about this function, and nothing else on the screen can break it.
 *
 * Three rules the rendering keeps, all of them about a figure that would otherwise lie:
 *
 *  * **Nothing is shown that the read did not answer.** Without a snapshot every card is emptied rather
 *    than left as the previous period's numbers under a heading that has since moved on.
 *  * **A share needs something to be a share of.** A completion rate is printed as a percentage only when
 *    the account has tasks; the same rule decides the comparison line, which prints a percentage only over
 *    a period that measured something, and says what happened in words when the period before it measured
 *    nothing. An increase over no base is not an infinite percentage and it is not a hundred either.
 *  * **A missing figure is said, not hidden.** A task that was deleted physically still has its measured
 *    stretches, so the busiest list names what it can and marks the row as a task that is no longer here.
 */
object DashboardRenderer {

    /** The shades the activity grid paints with, from level 0 to the busiest. */
    private const val LEVELS = 5

    /**
     * A change this small rounds to nothing once its percentage is printed with no decimals, so the line
     * that would say "up 0%" says "a little over" instead. Half of one percent is where that rounding is.
     */
    private const val PERCENT_RESOLUTION = 0.005

    /**
     * Draws one state onto one screen.
     *
     * The chip row is the fragment's, because choosing a period is its command. [navigation] is the
     * fragment's for the same reason: deciding where a row goes is navigation, and this object's job ends at
     * saying which rows have somewhere to be.
     */
    fun render(
        binding: FragmentStatisticsBinding,
        state: DashboardState,
        navigation: DashboardNavigation = DashboardNavigation()
    ) {
        val context = binding.root.context
        val snapshot = state.snapshot

        binding.dashboardProgress.isVisible = state.isLoading
        binding.dashboardEmpty.isVisible = state.isEmpty
        binding.dashboardScroll.isVisible = snapshot != null && !snapshot.isEmpty

        if (snapshot == null) {
            clear(binding)
            return
        }
        renderHeadline(context, binding, snapshot)
        renderComparison(context, binding, snapshot)
        renderTrend(context, binding, snapshot)
        renderGrid(context, binding, snapshot)
        renderHealth(context, binding, snapshot)
        renderCategories(context, binding, snapshot, navigation)
        renderTopTasks(context, binding, snapshot, navigation)
    }

    private fun renderHeadline(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot
    ) {
        val measured = snapshot.measured
        binding.dashboardHeadline.text = WorkTimeFormat.compact(context, measured.seconds)
        binding.dashboardHeadlineDetails.text = context.getString(
            R.string.dashboard_headline_meta,
            counted(context, R.plurals.history_stretch_count, measured.intervals),
            counted(context, R.plurals.history_session_count, measured.sessions),
            // The label comes from the snapshot's own request rather than from the period the screen thinks
            // it is on, so a heading can never describe a window the figures were not read from.
            context.getString(snapshot.request.period.labelRes())
        )
        binding.dashboardFinished.text = counted(context, R.plurals.dashboard_finished, snapshot.completed)
        binding.dashboardAdded.text = counted(context, R.plurals.dashboard_added, snapshot.created)
        binding.dashboardStreak.text = counted(context, R.plurals.dashboard_streak, snapshot.streakDays)

        val rate = snapshot.completionRate
        if (rate == null) {
            binding.dashboardRate.setText(R.string.dashboard_rate_unknown)
            binding.dashboardRateDetails.isVisible = false
        } else {
            binding.dashboardRate.text = context.getString(
                R.string.dashboard_rate,
                percent().format(rate.toDouble())
            )
            binding.dashboardRateDetails.isVisible = true
            binding.dashboardRateDetails.text = context.resources.getQuantityString(
                R.plurals.dashboard_rate_tasks,
                snapshot.completedEver,
                snapshot.completedEver,
                snapshot.totalTasks
            )
        }
    }

    private fun renderComparison(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot
    ) {
        val work = snapshot.workChange
        val line = changeLine(context, work)
        binding.dashboardWorkChange.isVisible = line != null
        if (line != null) binding.dashboardWorkChange.text = line

        // Completions are counted rather than weighed, so the honest line is the two numbers: one finished
        // task against none is a fact, and a percentage of it would be arithmetic on nothing.
        val completions = snapshot.completionsChange
        val worthSaying = completions.current != 0L || completions.previous != 0L
        binding.dashboardCompletionsChange.isVisible = worthSaying
        if (worthSaying) {
            binding.dashboardCompletionsChange.text = context.getString(
                R.string.dashboard_completions_against,
                completions.current,
                completions.previous
            )
        }
    }

    /**
     * The one sentence a change supports, or null when there is nothing for a sentence to say.
     *
     * Null is the pair of zeroes: two empty periods did not stay the same as each other, and a line that
     * read "the same as the period before" would be reporting a comparison of two absences.
     */
    private fun changeLine(context: Context, change: FigureChange): String? {
        if (change.current == 0L && change.previous == 0L) return null
        val fraction = change.fraction ?: return context.getString(R.string.dashboard_change_from_nothing)
        if (change.direction == ChangeDirection.FLAT) return context.getString(R.string.dashboard_change_flat)

        // A change too small to move the rounded percent is still a change, and "up 0%" would contradict
        // the sentence it sits in. The threshold is the rounding itself makes: a percentage printed with no
        // decimals shows nothing under half of one, in every locale, so the test is on the number rather
        // than on the formatted text — whose percent sign is not even in the same place in every one.
        return if (abs(fraction) < PERCENT_RESOLUTION) {
            context.getString(
                if (change.direction == ChangeDirection.UP) R.string.dashboard_change_slightly_up
                else R.string.dashboard_change_slightly_down
            )
        } else {
            context.getString(
                if (change.direction == ChangeDirection.UP) R.string.dashboard_change_up
                else R.string.dashboard_change_down,
                percent().format(abs(fraction))
            )
        }
    }

    private fun renderTrend(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot
    ) {
        // Two bars over the same days: one in seconds, one in tasks. They are the same list read twice, so
        // a day a reader sees in one chart cannot be missing from the other.
        binding.trendWorkChart.setValues(snapshot.trend.map { it.seconds })
        binding.trendCompletionsChart.setBarColor(ContextCompat.getColor(context, R.color.chart_completions))
        binding.trendCompletionsChart.setValues(snapshot.trend.map { it.completions.toLong() })
        binding.trendEmpty.isVisible = snapshot.trend.isEmpty()
    }

    private fun renderGrid(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot
    ) {
        val grid = snapshot.heatmap
        binding.activityGrid.setGrid(grid)
        // The legend names the two ends of the scale rather than every shade: a square is read against its
        // neighbours, and five rows of "level three" would describe the palette instead of the picture.
        binding.activityGridLegend.setEntries(
            listOf(
                ChartLegendView.Entry(
                    context.getString(R.string.dashboard_grid_quiet),
                    null,
                    binding.activityGrid.colorForLevel(0)
                ),
                ChartLegendView.Entry(
                    context.getString(R.string.dashboard_grid_busy),
                    null,
                    binding.activityGrid.colorForLevel(LEVELS - 1)
                )
            )
        )
        val busiest = grid.busiestDaySeconds
        binding.activityGridBusiest.isVisible = busiest > 0L
        binding.activityGridBusiest.text = context.getString(
            R.string.dashboard_grid_busiest,
            WorkTimeFormat.compact(context, busiest)
        )
    }

    private fun renderHealth(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot
    ) {
        val counts = snapshot.health
        val buckets = DashboardMath.healthBuckets(counts)
        binding.healthTitle.text = context.getString(
            R.string.dashboard_health_title_format,
            counted(context, R.plurals.dashboard_open, counts.open)
        )
        binding.healthBar.setSegments(
            buckets.map { it.count.toLong() },
            IntArray(buckets.size) { healthColor(context, buckets[it].bucket) }
        )
        binding.healthLegend.setEntries(
            buckets.map { bucket ->
                ChartLegendView.Entry(
                    context.getString(healthLabelRes(bucket.bucket)),
                    number().format(bucket.count),
                    healthColor(context, bucket.bucket)
                )
            }
        )
        // Both of these cut across the buckets above — a task can be late and being worked on at once — so
        // they are figures beside the bar rather than parts of it.
        binding.healthInProgress.isVisible = counts.inProgress > 0
        binding.healthInProgress.text = context.getString(R.string.dashboard_in_progress, counts.inProgress)
        binding.healthStale.isVisible = counts.stale > 0
        binding.healthStale.text = context.getString(R.string.dashboard_stale, counts.stale)
    }

    private fun renderCategories(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot,
        navigation: DashboardNavigation
    ) {
        // Largest first: the question a reader brings to this bar is "where did most of it go", and the
        // order the database happened to group them in says nothing about that.
        val categories = snapshot.categories.sortedByDescending { it.seconds }
        binding.categoryBar.setSegments(
            categories.map { it.seconds },
            IntArray(categories.size) { categoryColor(context, categories[it]) }
        )
        val openCategory = navigation.openCategory
        binding.categoryLegend.setEntries(
            categories.map { category ->
                val categoryId = category.categoryId
                ChartLegendView.Entry(
                    category.categoryName ?: context.getString(R.string.dashboard_category_none),
                    WorkTimeFormat.compact(context, category.seconds),
                    categoryColor(context, category),
                    // Only a row that names a category the account still has can be opened. The ungrouped
                    // bucket is a count of work nobody filed, and there is no screen of "no category" tasks
                    // to be taken to.
                    onClick = if (categoryId != null && openCategory != null) {
                        { openCategory(categoryId) }
                    } else {
                        null
                    }
                )
            }
        )
    }

    private fun renderTopTasks(
        context: Context,
        binding: FragmentStatisticsBinding,
        snapshot: DashboardSnapshot,
        navigation: DashboardNavigation
    ) {
        val inflater = LayoutInflater.from(context)
        val openTask = navigation.openTask
        binding.topTasksContainer.removeAllViews()
        snapshot.topTasks.forEach { task ->
            val row = ItemDashboardTaskBinding.inflate(inflater, binding.topTasksContainer, false)
            row.dashboardTaskTitle.text = task.title ?: context.getString(R.string.dashboard_task_gone)
            row.dashboardTaskMeta.text = context.getString(
                R.string.dashboard_task_meta,
                WorkTimeFormat.compact(context, task.seconds),
                counted(context, R.plurals.history_stretch_count, task.intervals)
            )
            // A row whose task is gone is said as gone and left alone: opening the detail of an id nothing
            // answers for would take the reader to an empty screen.
            val target = task.title?.let { task.taskId }
            if (target != null && openTask != null) {
                row.root.isClickable = true
                row.root.setOnClickListener { openTask(target) }
            } else {
                row.root.isClickable = false
            }
            binding.topTasksContainer.addView(row.root)
        }
        binding.topTasksEmpty.isVisible = snapshot.topTasks.isEmpty()
    }

    /**
     * Brings every card back to nothing.
     *
     * The views outlive one snapshot — the same screen shows period after period — so a chart that is not
     * redrawn keeps the previous period's marks on screen under a heading that no longer matches them.
     */
    private fun clear(binding: FragmentStatisticsBinding) {
        binding.dashboardHeadline.text = null
        binding.dashboardHeadlineDetails.text = null
        binding.dashboardWorkChange.text = null
        binding.dashboardWorkChange.isVisible = false
        binding.dashboardCompletionsChange.text = null
        binding.dashboardCompletionsChange.isVisible = false
        binding.dashboardFinished.text = null
        binding.dashboardAdded.text = null
        binding.dashboardStreak.text = null
        binding.dashboardRate.text = null
        binding.dashboardRateDetails.isVisible = false
        binding.trendWorkChart.setValues(emptyList())
        binding.trendCompletionsChart.setValues(emptyList())
        binding.trendEmpty.isVisible = false
        binding.activityGrid.setGrid(null)
        binding.activityGridLegend.setEntries(emptyList())
        binding.activityGridBusiest.isVisible = false
        binding.healthTitle.text = null
        binding.healthBar.setSegments(emptyList(), IntArray(0))
        binding.healthLegend.setEntries(emptyList())
        binding.healthInProgress.isVisible = false
        binding.healthStale.isVisible = false
        binding.categoryBar.setSegments(emptyList(), IntArray(0))
        binding.categoryLegend.setEntries(emptyList())
        binding.topTasksContainer.removeAllViews()
        binding.topTasksEmpty.isVisible = false
    }

    private fun healthLabelRes(bucket: HealthBucket): Int = when (bucket) {
        HealthBucket.OVERDUE -> R.string.health_overdue
        HealthBucket.DUE_TODAY -> R.string.health_due_today
        HealthBucket.DUE_THIS_WEEK -> R.string.health_due_week
        HealthBucket.DUE_LATER -> R.string.health_due_later
        HealthBucket.WITHOUT_DEADLINE -> R.string.health_without_deadline
    }

    private fun healthColor(context: Context, bucket: HealthBucket): Int = ContextCompat.getColor(
        context,
        when (bucket) {
            HealthBucket.OVERDUE -> R.color.health_overdue
            HealthBucket.DUE_TODAY -> R.color.health_due_today
            HealthBucket.DUE_THIS_WEEK -> R.color.health_due_week
            HealthBucket.DUE_LATER -> R.color.health_due_later
            HealthBucket.WITHOUT_DEADLINE -> R.color.health_without_deadline
        }
    )

    /**
     * The colour one category's slice wears: the user's own, or grey for the bucket that is not a category.
     *
     * A category whose stored colour is missing has no colour to be drawn with, and inventing one would make
     * the chart disagree with the list that category is shown in.
     */
    private fun categoryColor(context: Context, category: CategoryWorkSummary): Int =
        category.color ?: ContextCompat.getColor(context, R.color.chart_category_none)

    /** "62%" and not "61.904762%": the share is a glance, and the exact counts sit beside it. */
    private fun percent(): NumberFormat =
        NumberFormat.getPercentInstance(LocalisedDates.locale()).apply { maximumFractionDigits = 0 }

    private fun number(): NumberFormat = NumberFormat.getNumberInstance(LocalisedDates.locale())

    /**
     * A count in the words the locale asks for.
     *
     * The figure is handed to a plurals resource rather than glued into a sentence because which words one
     * and seven need are the language's decision, and a phrase assembled with "+" in Kotlin is one a
     * translator cannot fix.
     */
    private fun counted(context: Context, pluralRes: Int, count: Int): String =
        context.resources.getQuantityString(pluralRes, count, count)
}
