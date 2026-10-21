package com.taskplanner.data.achievement

import com.taskplanner.R
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit

/**
 * A rule as a declaration: the four things an achievement is made of, and the one question it asks.
 *
 * The catalogue is twenty-odd achievements, and almost all of them differ only in how high the bar is set —
 * ten tasks and a hundred tasks ask the identical question of the identical figure. Writing each one as its
 * own object would be twenty-odd copies of the same three lines, which is exactly the kind of thing that
 * drifts: the day someone fixes the wording of one tier and misses the six beside it. So a tier family
 * declares its question once, in the class that builds it, and each member names only its own bar and its own
 * two strings.
 *
 * [SimpleRule] still implements the public [AchievementRule] interface rather than being a data class the pass
 * reads fields off, because the interface is what a rule that needs something stranger — a counter over a
 * list, a rule that reads two figures — can grow into without the pass noticing. [AchievementRules.HabitsKept]
 * is that rule, written out in full beside them.
 */
private class SimpleRule(
    override val key: String,
    override val category: AchievementCategory,
    override val unit: AchievementUnit,
    override val target: Long,
    override val titleRes: Int,
    override val conditionRes: Int,
    private val counterOf: (AchievementFigures) -> Long?
) : AchievementRule {
    override fun counter(figures: AchievementFigures): Long? = counterOf(figures)
}

/**
 * The achievements the app knows how to grant, as declarations.
 *
 * Each one states four things and answers one question. The four are its address ([AchievementRule.key]), the
 * shelf it is filed on ([AchievementCategory]), the figure it counts and how much of that figure the reader
 * has to reach; the question is [AchievementRule.counter] — how far this profile has come. Nothing else. No
 * rule compares its own counter to its own target, no rule says anything about a task it has not seen, and no
 * rule knows the table, the pass, or the screen.
 *
 * That narrowing is what §7.1's extensibility clause is actually asking for. Adding an achievement means
 * writing one line in the family it belongs to: the pass picks it up because it iterates [ALL], the table
 * needs no new column because a grant is a key plus a unit plus two numbers, the wording needs no new branch
 * in the renderer because the strings are a title, a condition and a unit plural, and no existing rule is
 * touched — which is the only way a new achievement cannot change the meaning of one already earned.
 *
 * ## What the set covers, and what it deliberately does not
 *
 * The families follow §7: a beginning is a first time, steadiness is a thing kept across days, volume is a
 * quantity reached, a milestone is a whole arc carried through. Every figure is read over the profile's whole
 * life rather than over a period, so a quiet stretch cannot make a card the reader earned read as never earned,
 * and no card can be paid out for a window the reader never chose.
 *
 * §7's examples are all here, with two differences, both stated rather than left for the reader to notice:
 *
 *  * **"Первая задача" is "первая завершённая задача".** A badge for typing a title into the app would be a
 *    reward for an intention, and §7.1 asks that achievements not be granted for data that shows no work.
 *    The first thing this set hands out for a new profile is the first task it was actually finished.
 *  * **Groups have a first and nothing above it.** Closing a project is a real arc, so "the first one" is
 *    worth keeping; "ten of them" would be a count of a thing most readers will never have ten of, and a card
 *    that stays locked for years is the nagging §7 warns about as much as a hollow one.
 *
 * Nothing in here touches Android beyond the two string ids each rule names, and nothing touches a database:
 * [AchievementFigures] in, a number or no number out. Whether the number is enough is decided by
 * [com.taskplanner.data.repository.AchievementRepository], once, for all rules together.
 */
object AchievementRules {

    /** The run of kept days at which a habit counts as one the reader holds, rather than one they tried. */
    private const val SERIES_A_WEEK_KEPT = 7

    // -- beginnings: the first time a thing happened -------------------------------------------------

    /**
     * The first of everything: a task finished, a session measured, a habit day marked, a goal reached, a
     * project closed.
     *
     * They share one shape because the question is the same every time — did this figure ever go above zero —
     * and only the figure differs. Each names its own two strings, because a beginning is the one family where
     * the reader is not being shown a number: "your first measured hour" is a sentence about a session, and
     * forcing it through the shared "N sessions completed" wording would say something the card has already
     * said better.
     */
    private val firsts: List<AchievementRule> = listOf(
        begin("first_task", AchievementUnit.TASKS, R.string.achievement_first_task_title,
            R.string.achievement_first_task_condition) { it.tasksEverCompleted },
        begin("first_session", AchievementUnit.SESSIONS, R.string.achievement_first_session_title,
            R.string.achievement_first_session_condition) { it.sessionsEverMeasured },
        begin("first_habit_mark", AchievementUnit.MARKS, R.string.achievement_first_habit_mark_title,
            R.string.achievement_first_habit_mark_condition) { it.habitMarksEver },
        begin("first_goal", AchievementUnit.GOALS, R.string.achievement_first_goal_title,
            R.string.achievement_first_goal_condition) { it.goalsEverReached },
        begin("first_project", AchievementUnit.PROJECTS, R.string.achievement_first_project_title,
            R.string.achievement_first_project_condition) { it.projectsEverCompleted }
    )

    private fun begin(
        key: String,
        unit: AchievementUnit,
        titleRes: Int,
        conditionRes: Int,
        counterOf: (AchievementFigures) -> Long?
    ): AchievementRule = SimpleRule(
        key = key,
        category = AchievementCategory.BEGINNING,
        unit = unit,
        target = 1L,
        titleRes = titleRes,
        conditionRes = conditionRes,
        counterOf = counterOf
    )

    // -- steadiness: days the profile was actually used ----------------------------------------------

    /**
     * 3, 7, 14, 30 and 100 days on which the reader left measured work behind.
     *
     * Read as days of use rather than as days since the account was made: a run of calendar days is a fact
     * about the date, the app cannot know the reader opened it, and an achievement that paid out for that
     * would be a streak mechanic wearing the language of a record. The figure counts every day the profile has
     * ever left closed-out work on, over the whole account rather than a period, and an interval still running
     * is not one of them.
     */
    private val activityDays: List<AchievementRule> = tiers(
        prefix = "active_days",
        category = AchievementCategory.STEADINESS,
        unit = AchievementUnit.ACTIVE_DAYS,
        counterOf = { it.activeDays.toLong() },
        titles = listOf(
            3L to R.string.achievement_active_days_3_title,
            7L to R.string.achievement_active_days_7_title,
            14L to R.string.achievement_active_days_14_title,
            30L to R.string.achievement_active_days_30_title,
            100L to R.string.achievement_active_days_100_title
        ),
        conditions = listOf(
            3L to R.string.achievement_active_days_3_condition,
            7L to R.string.achievement_active_days_7_condition,
            14L to R.string.achievement_active_days_14_condition,
            30L to R.string.achievement_active_days_30_condition,
            100L to R.string.achievement_active_days_100_condition
        )
    )

    /**
     * A habit kept for 7 and for 30 of its own asked days.
     *
     * The counter is the longest run any single habit ever reached, so these two are the same question at two
     * heights; a reader with one habit kept for a month earns both, and the pass writes both rows, which is
     * what §7's separate cards for a week and a month ask for.
     *
     * Nothing at all is returned while the profile has no habits, which the screen reads as "not started"
     * rather than as a series of nought — the distinction §7.3 wants, since a locked card that says the reader
     * is seven days short of a week implies a habit they never made.
     */
    private val habitSeries: List<AchievementRule> = tiers(
        prefix = "habit_series",
        category = AchievementCategory.STEADINESS,
        unit = AchievementUnit.STREAK_DAYS,
        counterOf = { it.habitBestStreaks.maxOrNull()?.toLong() },
        titles = listOf(
            7L to R.string.achievement_habit_series_7_title,
            30L to R.string.achievement_habit_series_30_title
        ),
        conditions = listOf(
            7L to R.string.achievement_habit_series_7_condition,
            30L to R.string.achievement_habit_series_30_condition
        )
    )

    // -- volume: a quantity reached ------------------------------------------------------------------

    /** 10, 50, 100, 500 and 1000 tasks finished, over the whole account rather than any period of it. */
    private val taskTiers: List<AchievementRule> = tiers(
        prefix = "tasks",
        category = AchievementCategory.VOLUME,
        unit = AchievementUnit.TASKS,
        counterOf = { it.tasksEverCompleted },
        titles = listOf(
            10L to R.string.achievement_tasks_10_title,
            50L to R.string.achievement_tasks_50_title,
            100L to R.string.achievement_tasks_100_title,
            500L to R.string.achievement_tasks_500_title,
            1_000L to R.string.achievement_tasks_1000_title
        ),
        conditions = listOf(
            10L to R.string.achievement_tasks_10_condition,
            50L to R.string.achievement_tasks_50_condition,
            100L to R.string.achievement_tasks_100_condition,
            500L to R.string.achievement_tasks_500_condition,
            1_000L to R.string.achievement_tasks_1000_condition
        )
    )

    /**
     * 10, 50, 100 and 500 whole hours of measured work.
     *
     * The division to hours already happened on the way in, in the repository that read the seconds, because
     * "100 hours of work" is a claim about hours and dividing here would be a second rounding of a figure the
     * report also prints.
     */
    private val hourTiers: List<AchievementRule> = tiers(
        prefix = "hours",
        category = AchievementCategory.VOLUME,
        unit = AchievementUnit.HOURS,
        counterOf = { it.trackedHours },
        titles = listOf(
            10L to R.string.achievement_hours_10_title,
            50L to R.string.achievement_hours_50_title,
            100L to R.string.achievement_hours_100_title,
            500L to R.string.achievement_hours_500_title
        ),
        conditions = listOf(
            10L to R.string.achievement_hours_10_condition,
            50L to R.string.achievement_hours_50_condition,
            100L to R.string.achievement_hours_100_condition,
            500L to R.string.achievement_hours_500_condition
        )
    )

    /** 100 habit days marked, over every habit the profile has. */
    private val markTiers: List<AchievementRule> = tiers(
        prefix = "marks",
        category = AchievementCategory.VOLUME,
        unit = AchievementUnit.MARKS,
        counterOf = { it.habitMarksEver },
        titles = listOf(100L to R.string.achievement_marks_100_title),
        conditions = listOf(100L to R.string.achievement_marks_100_condition)
    )

    // -- milestones: a whole arc carried through -----------------------------------------------------

    /** 5 and 10 goals carried to their target, including the ones since archived. */
    private val goalTiers: List<AchievementRule> = tiers(
        prefix = "goals",
        category = AchievementCategory.MILESTONE,
        unit = AchievementUnit.GOALS,
        counterOf = { it.goalsEverReached },
        titles = listOf(
            5L to R.string.achievement_goals_5_title,
            10L to R.string.achievement_goals_10_title
        ),
        conditions = listOf(
            5L to R.string.achievement_goals_5_condition,
            10L to R.string.achievement_goals_10_condition
        )
    )

    /**
     * Three habits each kept for a week at some point.
     *
     * This is the one rule that reads the per-habit list rather than its largest element, and the reason the
     * figures carry a list instead of a single folded best: "what was the longest run" and "how many habits
     * were ever held for a week" are different questions about the same marks, and a figure folded before a
     * rule saw it can answer only one of them.
     *
     * The count is over each habit's *best* series rather than its current one, which is the only version that
     * reads as a thing done: a reader who kept three habits through March and let one slip in June did keep
     * three habits, and an achievement that went quiet when the streak broke would be asking the reader to
     * feel about the present rather than remembering what they did.
     */
    private object HabitsKept : AchievementRule {
        override val key = "habits_kept_3"
        override val category = AchievementCategory.MILESTONE
        override val unit = AchievementUnit.HABITS
        override val target = 3L
        override val titleRes = R.string.achievement_habits_kept_3_title
        override val conditionRes = R.string.achievement_habits_kept_3_condition

        override fun counter(figures: AchievementFigures): Long? = figures.habitBestStreaks
            .takeIf { it.isNotEmpty() }
            ?.count { it >= SERIES_A_WEEK_KEPT }
            ?.toLong()
    }

    /**
     * The family's rules, one per bar, in the order the bars ascend.
     *
     * The key is derived from the bar rather than spelled out beside it: `tasks_100` cannot be typed
     * differently in the two places that have to agree, and §7.2's history rows address themselves by that key
     * for as long as the app reads them.
     *
     * [titles] drives the list, so the two string lists must hold the same bars. A family that gains a title and
     * forgets its condition does not quietly re-use another tier's text — [conditions] cannot answer for the new
     * bar, and `NoSuchElementException` is thrown while this object is being initialized. That is loud rather
     * than silent, and it is caught by the rules' own suite, which reads [ALL] and so fails the moment the
     * registry cannot be built; but it is worth knowing the failure arrives at runtime and takes the whole
     * catalogue with it, not only the tier that was left out. A condition without a matching title is the
     * harmless direction: nothing asks for it.
     */
    private fun tiers(
        prefix: String,
        category: AchievementCategory,
        unit: AchievementUnit,
        counterOf: (AchievementFigures) -> Long?,
        titles: List<Pair<Long, Int>>,
        conditions: List<Pair<Long, Int>>
    ): List<AchievementRule> = titles.map { (target, titleRes) ->
        SimpleRule(
            key = "${prefix}_$target",
            category = category,
            unit = unit,
            target = target,
            titleRes = titleRes,
            conditionRes = conditions.first { it.first == target }.second,
            counterOf = counterOf
        )
    }

    /**
     * Every achievement the app grants, grouped the way the screen shelves them.
     *
     * The order is the reader's, not the rules': beginnings first, because "you did the first one" is the news
     * a new profile can actually earn today, then the runs of days, then the quantities, then the arcs. Within
     * a family the bars ascend, so a reader who scrolls sees the next one they can reach before the ones far
     * ahead of them. Nothing sorts by key — a list order is a statement about what to show first, and a screen
     * that re-sorted it would decide that again badly.
     *
     * It is last in the file because a Kotlin object initializes its properties in declaration order: reading
     * a family declared below this line would read a field that has not been assigned yet.
     */
    val ALL: List<AchievementRule> =
        firsts + activityDays + habitSeries + taskTiers + hourTiers + markTiers + goalTiers + HabitsKept
}
