package com.taskplanner.data.achievement

import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit

/**
 * What the rules are handed to judge: the counters a profile's work adds up to, in the units an achievement
 * names them in.
 *
 * This is the same seam [com.taskplanner.data.insight.InsightInputs] is for the reading rules, and it is here
 * for the same reason. A rule that reached into a dao could not be tested as arithmetic, and a rule that read
 * the database itself would decide its own threshold against whichever window it happened to be given — which
 * is how a "100 tasks" badge ends up counting three. The repository fills these figures in once per pass, from
 * the aggregates the dashboard and the goal and habit screens already use, so the number an achievement was
 * granted on is the number the screen beside it states.
 *
 * Every field is a lifetime total, and none of them is a window. That is what §7.1's "не выдавать одно
 * достижение повторно" needs in the figures rather than only in the table: a counter that counts everything
 * the profile ever did can only ever grow, so a quiet month cannot make an achievement the reader already
 * earned untrue, and a grant that was missed because no pass ran on the right day is still handed out by the
 * next one.
 *
 * Two conversions happen on the way in, both because the achievements speak the figure rather than the schema
 * doing so. Measured time arrives as whole hours, because "100 hours of work" is a claim about hours and
 * dividing seconds inside a rule would be a second rounding of a figure the report also prints. And a habit
 * arrives as one number per habit rather than as a single best, because two of the achievements ask different
 * questions of the same marks — how long the longest run was, and how many habits were ever kept for a week —
 * and a figure folded before the rule saw it could answer only one of them.
 */
data class AchievementFigures(
    /** Tasks finished, whatever their state now: un-completing work does not take the count back. */
    val tasksEverCompleted: Long = 0L,

    /** Goals carried to their target, including ones since archived. */
    val goalsEverReached: Long = 0L,

    /** Groups (projects) closed, however long ago. */
    val projectsEverCompleted: Long = 0L,

    /**
     * The longest run of asked days each habit ever reached, one entry per habit.
     *
     * Empty means the profile has never had a habit at all, which the streak rules read as "nothing to
     * count" rather than as a series of nought — see [AchievementRule.counter]. A habit that has marks but
     * none in a run contributes a zero, which is a real answer to a real question.
     */
    val habitBestStreaks: List<Int> = emptyList(),

    /** Days a habit was marked, over every habit the profile has. */
    val habitMarksEver: Long = 0L,

    /** Days with measured work on them, so a run of use is counted in days rather than in sessions. */
    val activeDays: Int = 0,

    /** Measured intervals of work closed out, whatever their length. */
    val sessionsEverMeasured: Long = 0L,

    /** Whole hours of measured work. */
    val trackedHours: Long = 0L
)

/**
 * What a rule judged, before anybody put words on it.
 *
 * A grant carries the condition it met and the reading that met it, because §7.2 asks for both the conditions
 * and the date, and a row that stored only the second could not tell the reader what they had done. [progress]
 * is what the counter read, which may be past [target] — a profile that finished eleven tasks in one day earns
 * "10 tasks" with eleven behind it, and the history says so. [ruleVersion] is the edition of the rule that
 * judged it, so a condition retuned later can be told apart from the one the reader's history was written
 * under.
 *
 * [key] names the rule and is never edited: it is the address the no-repeat index is keyed on, so two passes
 * that both find the condition met reach one row rather than racing for two.
 */
data class Grant(
    val key: String,
    val category: AchievementCategory,
    val unit: AchievementUnit,
    val ruleVersion: Int,
    val target: Long,
    val progress: Long,
    val earnedDay: Long,
    val earnedAt: Long
) {
    /** The row this grant belongs on: one achievement, one account. */
    fun uuid(userId: Long): String = Achievement.uuidFor(userId, key)
}

/**
 * One achievement the app knows how to grant.
 *
 * The interface is the extensibility §7.1 asks for: adding an achievement means adding one implementation and
 * naming it in the registry, not editing the pass, the table, the screen or another rule. That is why the
 * comparison is not part of it. A rule answers one question — [counter], the figure this profile has reached
 * so far — and the repository applies the threshold, so a new rule cannot get "is it earned yet" wrong and two
 * rules cannot disagree about what reaching means.
 *
 * [counter] is a total rather than a window, and it reads [AchievementFigures] as they stand, so an achievement
 * is judged against everything the profile has ever done. That is what "never grant twice" needs: a counter
 * that drops — a habit whose marks were deleted, a task un-completed — cannot make an achievement the reader
 * already earned untrue, and the unique index in `achievements` keeps the grant standing even if a later pass
 * measures less.
 *
 * The wording is not on the interface either, only the ids of it. A rule that returned a sentence could not be
 * tested as arithmetic, and a screen could not re-state a grant in another language; a rule that names its two
 * strings by resource id stays declarative, and the renderer in the repository needs no new branch when a rule
 * is added.
 */
interface AchievementRule {

    /** The stable address of this achievement in this profile's history. Never edited, never displayed. */
    val key: String

    /** Which of the reader's headings this grant is filed under. */
    val category: AchievementCategory

    /** What [counter] counts, so the stored number keeps its meaning after the rule changes. */
    val unit: AchievementUnit

    /** What the reader has to reach. */
    val target: Long

    /** The heading, as a string resource. */
    val titleRes: Int

    /**
     * The condition, as a string resource, phrased as a sentence rather than as a template.
     *
     * The bar is written into the text instead of being substituted into it, because the sentence a reader of
     * habits needs is not the sentence a reader of hours needs ("Measure 100 hours of work" and "Keep one habit
     * for 30 of its days in a row" share no shape worth forcing them through). The cost is that a rule and its
     * text can drift, so the rules' own suite reads the number out of every condition and compares it with
     * [target] — the drift is caught as a failing test rather than left for the reader to notice.
     */
    val conditionRes: Int

    /**
     * Which edition of this condition is in force.
     *
     * §7.2 asks the history to record the version of the rule that handed a grant out, and this is the only
     * place that version can live: a rule that changes what it asks for — a target retuned, a figure counted
     * over a window rather than a life — raises this by one, and the grants already on the table keep the
     * number they were written under. It is a default rather than an abstract member because the ordinary way
     * to add an achievement is to write a new rule, not to retune an old one: a new rule inherits version 1
     * and needs no second thought.
     */
    val version: Int get() = 1

    /**
     * How far this profile has come on the figure this rule counts, or null while there is nothing to count.
     *
     * Null is not zero. A profile that has never finished a task has a zero to show, while a rule that has no
     * figure for this profile at all — no habit to take a best streak from — has nothing, and the screen says
     * "not started" rather than "0 of 7".
     */
    fun counter(figures: AchievementFigures): Long?
}
