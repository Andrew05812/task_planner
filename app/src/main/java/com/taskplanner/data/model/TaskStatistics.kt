package com.taskplanner.data.model

/**
 * The shapes the dashboard's task-side totals come back in.
 *
 * Same rule as the work totals in `WorkStatistics`: these are what SQLite produced, typed. A dashboard
 * that wants "how many tasks are still open" gets one integer, not the profile's rows loaded into a
 * list to be counted in Kotlin — which is the pattern this screen used to run on and the reason it is
 * being rebuilt.
 */

/** How many non-tombstoned tasks carry one status spelling. */
data class TaskStatusCount(
    val status: String,
    val count: Int
)

/**
 * The open tasks of one profile, sorted into the states a user can act on.
 *
 * Every field is a count of the same rows under a different condition, produced by one scan: the
 * dashboard shows them side by side, and reading them from separate queries at separate instants would
 * let a task move out of "overdue" and into "open" between two of them, so the numbers would not add
 * up to the same set.
 *
 * "Open" here means what the active list means: not completed, not archived, not tombstoned. An
 * archived task with a deadline that passed is left out rather than counted — it is work the user put
 * away on purpose, and a health panel that shouts about it would disagree with the screen the task is
 * no longer on.
 */
data class TaskHealthCounts(
    /** Open, has a deadline, and that deadline is already past. */
    val overdue: Int,
    /** Open, due between now and the end of today. */
    val dueToday: Int,
    /** Open, due after today and within the coming seven days. */
    val dueThisWeek: Int,
    /** Open with no deadline at all — neither late nor on time. */
    val withoutDeadline: Int,
    /** Open and marked as being worked on. */
    val inProgress: Int,
    /** Open and untouched since [staleBefore]. */
    val stale: Int,
    /** Every open row the panel counted. */
    val open: Int
)

/** Completions on one local day, as the dashboard's trend bar reads them. */
data class DailyCompletionCount(
    val localDay: Long,
    val count: Int
)

/**
 * How many tasks a category had finished inside a window, and the name the category row answers to.
 *
 * The counterpart of [com.taskplanner.data.model.CategoryWorkSummary] rather than a restatement of it: that
 * row sums measured seconds, this one counts completions, and the two are not each other's answer — a
 * category of quick tasks can hold most of a week's finishes and almost none of its tracked time, which is
 * exactly the case a report that inferred one from the other would get wrong.
 *
 * A null [categoryId] is the unclassified bucket, and it holds two kinds of row on purpose: tasks that never
 * had a category, and tasks whose category row is gone — the same shape
 * [com.taskplanner.data.model.CategoryWorkSummary] reports, because the two aggregates group on the joined
 * `categories.id` rather than on `tasks.category_id`. Grouping on the task's own column would file the second
 * kind under an id with no name while the time beside it landed in the unclassified bucket, and one real
 * category would come out of the report as two half-empty rows.
 */
data class CategoryCompletionCount(
    val categoryId: Long?,
    val categoryName: String?,
    val color: Int?,
    val completions: Int
)

/**
 * How many tasks the account stands behind, how many of them read finished, how many are put away, and how
 * many of the finished ones carry no date.
 *
 * One scan rather than four counts, because the report's standing figures have to divide into each other: a
 * rate computed from two separate statements could be built over a set a write moved between them, and a
 * heading that printed "12 of 40" beside a total of 41 would be a report arguing with itself. The fourth
 * column is here for the same reason rather than as a second query over rows this one already read.
 *
 * [archived] is counted by its own column rather than taken from [completed], and the two differ in real
 * data: finishing a task locally archives it in the same statement, while a task that arrived from another
 * device already finished can stand un-archived. A report that derived one from the other would be guessing
 * at a state the row names.
 */
data class AccountTaskCounts(
    val tasks: Int = 0,
    val completed: Int = 0,
    val archived: Int = 0,
    /** Finished rows with no `completed_at`: the work the account cannot place in any period. */
    val undated: Int = 0
)

/**
 * The open tasks sorted by how late they are and how much they matter, from one scan.
 *
 * The insight rules ask a question the dashboard's [TaskHealthCounts] does not: not "how much is overdue"
 * but "how much of the overdue work is the work the user called important". Both are counts of the same
 * rows, and both come out of the same pass over them, so the two screens cannot disagree about what is
 * open by reading the table twice at two instants.
 *
 * [highOpen] is carried beside the overdue figures because a rule that says "three of your high-priority
 * tasks are late" has to know how many there are in total; without it the sentence cannot be qualified, and
 * a reading that cannot be checked against the list it came from is the kind §7 was written against.
 */
data class PriorityOverdueCounts(
    /** Open tasks marked HIGH, late or not. */
    val highOpen: Int = 0,
    /** Open tasks marked HIGH whose deadline is already past. */
    val highOverdue: Int = 0,
    /** Every open task whose deadline is past, at any priority. */
    val overdue: Int = 0,
    /** Every open row the scan looked at. */
    val open: Int = 0
)

/**
 * Whether a stored `status` spelling means the task is finished.
 *
 * One function because the answer is needed in two places that cannot see each other: `Converters`
 * reading a row into [TaskStatus], and the dashboard's SQL deciding which rows are open. Older
 * databases spell a completion `DONE` — every release since writes `COMPLETED`, and nothing rewrote the
 * old rows, so both spellings are still data a real install can hold. A screen that counted only one of
 * them would report a finished task as still open.
 */
fun isCompletedSpelling(status: String): Boolean =
    status.uppercase(java.util.Locale.ROOT) in COMPLETED_SPELLINGS

private val COMPLETED_SPELLINGS = setOf("COMPLETED", "DONE")
