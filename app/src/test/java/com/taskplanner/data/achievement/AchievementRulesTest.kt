package com.taskplanner.data.achievement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The catalogue of achievements, tested as arithmetic and as a set of declarations.
 *
 * No database and no pass appears here, which is the whole reason the rules were kept a seam: what a reader
 * earned, and what the card beside it says, are settled by the four numbers a rule states and the one question
 * it asks, and both of those can be read off [AchievementRules.ALL] without a profile. What the tests hold are
 * the five things that would otherwise only show up on a screen:
 *
 *  * **Addresses.** A key is where a grant lives in the history table, so two rules sharing one would mean a
 *    reader who earned the second never sees the first. Keys are unique, and they are the strings the tests
 *    name below — a renamed key is a renamed history, which is why it has to be a decision rather than an
 *    accident.
 *  * **The bar and its sentence agree.** §7's conditions are written as prose ("Finish 500 tasks") rather
 *    than as a number dropped into a template, because the sentences a reader of habits needs and the ones a
 *    reader of hours needs share no shape. The cost is that a retuned target could leave its old number in
 *    the text, so every condition is read back and the figure in it is compared with the rule's own [target].
 *  * **A first is a first.** The five beginnings all ask whether a lifetime total went above zero, at a bar of
 *    one, over the figure their name states.
 *  * **Nothing is counted out of thin air.** With an empty profile — every figure at its default — a rule
 *    either says zero or says nothing at all, and never invents a number. The two answers are different claims
 *    and the screen depends on telling them apart.
 *  * **A family is one question at several heights.** The tiers under a prefix share a unit, a shelf, and the
 *    counter they read, and only their bars differ; that is what makes adding a tier a one-line change rather
 *    than a new kind of rule.
 */
@RunWith(RobolectricTestRunner::class)
class AchievementRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rules = AchievementRules.ALL

    private fun rule(key: String): AchievementRule =
        rules.first { it.key == key }.also { assertNotNull("no rule named $key", it) }

    /**
     * The figures a profile with nothing in it hands over: every count at zero, and the per-habit list empty,
     * which is what "no habits at all" looks like from the pass's side.
     */
    private val empty = AchievementFigures()

    private fun figures(
        tasks: Long = 0L,
        goals: Long = 0L,
        projects: Long = 0L,
        streaks: List<Int> = emptyList(),
        marks: Long = 0L,
        activeDays: Int = 0,
        sessions: Long = 0L,
        hours: Long = 0L
    ) = AchievementFigures(
        tasksEverCompleted = tasks,
        goalsEverReached = goals,
        projectsEverCompleted = projects,
        habitBestStreaks = streaks,
        habitMarksEver = marks,
        activeDays = activeDays,
        sessionsEverMeasured = sessions,
        trackedHours = hours
    )

    // -- the catalogue as a set ----------------------------------------------------------------------

    @Test
    fun `every achievement has an address no other one uses`() {
        val keys = rules.map { it.key }
        assertEquals("keys repeat: $keys", keys.size, keys.toSet().size)
        keys.forEach { assertTrue("a key has to be safe in a table name: $it", it.matches(Regex("[a-z0-9_]+"))) }
    }

    @Test
    fun `the catalogue fills the four shelves the screen groups it by`() {
        val byCategory = rules.groupBy { it.category }
        AchievementCategory.entries.filter { it != AchievementCategory.OTHER }.forEach { category ->
            assertTrue(
                "$category has no achievements, so the screen would shelve nothing under it",
                (byCategory[category] ?: emptyList()).isNotEmpty()
            )
        }
        // OTHER is the shelf unknown text is put on, not one a rule is written for.
        assertTrue(rules.none { it.category == AchievementCategory.OTHER })
        assertTrue(rules.none { it.unit == AchievementUnit.OTHER })
    }

    @Test
    fun `every rule states a bar a reader can reach`() {
        rules.forEach { rule ->
            assertTrue("${rule.key} asks for ${rule.target}", rule.target >= 1L)
            // A bar no profile can ever be measured against would be a permanently locked card, which is the
            // nagging §7 tells the system to avoid rather than a record of anything.
            assertTrue("${rule.key} sets a bar past a plausible lifetime total", rule.target <= 1_000L)
        }
    }

    @Test
    fun `a condition names the same bar as the rule it belongs to`() {
        rules.filter { it.target > 1L }.forEach { rule ->
            val condition = context.getString(rule.conditionRes).filterNot { it.isWhitespace() }
            val numbers = Regex("\\d+").findAll(condition).map { it.value }.toList()
            assertTrue(
                "${rule.key} sets a bar of ${rule.target}, but its condition says \"$condition\" with $numbers",
                numbers.contains(rule.target.toString())
            )
        }
    }

    @Test
    fun `a condition whose bar is a first says so instead of counting`() {
        rules.filter { it.target == 1L }.forEach { rule ->
            val condition = context.getString(rule.conditionRes)
            assertTrue(
                "${rule.key} is a beginning, so its condition has to name a first rather than a figure: " +
                    "\"$condition\"",
                condition.contains("first", ignoreCase = true) ||
                    condition.contains("one", ignoreCase = true)
            )
        }
    }

    @Test
    fun `every rule has wording of its own in both places a card reads it`() {
        val titles = rules.map { context.getString(it.titleRes) }
        val conditions = rules.map { context.getString(it.conditionRes) }
        assertEquals("titles repeat", titles.size, titles.toSet().size)
        assertEquals("conditions repeat", conditions.size, conditions.toSet().size)
        titles.forEach { assertTrue("a blank title: $it", it.isNotBlank()) }
        conditions.forEach { assertTrue("a blank condition: $it", it.isNotBlank()) }
    }

    @Test
    fun `a new profile is measured rather than invented`() {
        rules.forEach { rule ->
            val counter = rule.counter(empty)
            // Zero is the honest answer for a profile that has done nothing; null is the honest answer where
            // there is no figure to answer with at all. Anything else is a number out of nowhere.
            assertTrue(
                "${rule.key} reads ${counter} over an empty profile",
                counter == null || counter == 0L
            )
        }
    }

    // -- beginnings ----------------------------------------------------------------------------------

    @Test
    fun `a beginning asks whether its own lifetime figure went above zero`() {
        val cases = listOf(
            "first_task" to figures(tasks = 1L),
            "first_session" to figures(sessions = 1L),
            "first_habit_mark" to figures(marks = 1L),
            "first_goal" to figures(goals = 1L),
            "first_project" to figures(projects = 1L)
        )
        cases.forEach { (key, reached) ->
            val rule = rule(key)
            assertEquals("a beginning sets a bar of one", 1L, rule.target)
            assertEquals(AchievementCategory.BEGINNING, rule.category)
            assertEquals("$key reached", 1L, rule.counter(reached))
            assertEquals("$key not reached", 0L, rule.counter(empty))
        }
    }

    @Test
    fun `the first session is a first even when it measures less than an hour`() {
        // A ninety-second interval is nothing at all counted in hours and a real first time counted in
        // sessions, which is why the two figures are read separately on the way in.
        assertEquals(1L, rule("first_session").counter(figures(sessions = 1L, hours = 0L)))
        assertEquals(0L, rule("hours_10").counter(figures(sessions = 1L, hours = 0L)))
    }

    // -- steadiness ----------------------------------------------------------------------------------

    @Test
    fun `the day tiers all read the same count of days the profile was used`() {
        listOf(3L, 7L, 14L, 30L, 100L).forEach { bar ->
            val rule = rule("active_days_$bar")
            assertEquals(AchievementCategory.STEADINESS, rule.category)
            assertEquals(AchievementUnit.ACTIVE_DAYS, rule.unit)
            assertEquals("active days below the bar", bar - 1L, rule.counter(figures(activeDays = bar.toInt() - 1)))
            assertEquals("active days at the bar", bar, rule.counter(figures(activeDays = bar.toInt())))
        }
    }

    @Test
    fun `a habit tier counts the longest run any habit reached, not the runs added together`() {
        val week = rule("habit_series_7")
        // Two habits, one kept for nine days and one for four: the best is nine, and the total would be
        // thirteen — enough to hand out the thirty-day card for a reader who never kept one habit for a month.
        assertEquals(9L, week.counter(figures(streaks = listOf(9, 4))))
        assertNull("a profile with no habits has no series to be short of", week.counter(empty))
        assertEquals(
            "a habit with marks and no run is a zero, not a missing figure",
            0L,
            week.counter(figures(streaks = listOf(0)))
        )
        assertEquals(30L, rule("habit_series_30").counter(figures(streaks = listOf(30, 2))))
    }

    @Test
    fun `only the habits kept for a week count towards holding several habits`() {
        val rule = rule("habits_kept_3")
        assertEquals(AchievementUnit.HABITS, rule.unit)
        assertEquals("three habits, each past a week", 3L, rule.counter(figures(streaks = listOf(30, 7, 8, 2))))
        assertEquals("two of them", 2L, rule.counter(figures(streaks = listOf(30, 7, 2, 1))))
        assertNull(rule.counter(empty))
        assertEquals("a habit tried and dropped is a zero here", 0L, rule.counter(figures(streaks = listOf(6, 2))))
    }

    // -- volume and milestones -----------------------------------------------------------------------

    @Test
    fun `a quantity tier reads its own figure whatever the rows look like now`() {
        listOf(10L, 50L, 100L, 500L, 1000L).forEach { bar ->
            val rule = rule("tasks_$bar")
            assertEquals(AchievementCategory.VOLUME, rule.category)
            assertEquals(AchievementUnit.TASKS, rule.unit)
            assertEquals(bar, rule.counter(figures(tasks = bar)))
            // A counter past the bar is stated as it is: eleven finished tasks earn the ten-task card with
            // eleven behind it, and the history says eleven.
            assertEquals("a tier met reads the whole figure", bar + 4L, rule.counter(figures(tasks = bar + 4L)))
        }
        listOf(10L, 50L, 100L, 500L).forEach { bar ->
            val rule = rule("hours_$bar")
            assertEquals(AchievementUnit.HOURS, rule.unit)
            assertEquals(bar, rule.counter(figures(hours = bar)))
        }
        assertEquals(AchievementUnit.MARKS, rule("marks_100").unit)
        assertEquals(100L, rule("marks_100").counter(figures(marks = 100L)))
        listOf(5L, 10L).forEach { bar ->
            val rule = rule("goals_$bar")
            assertEquals(AchievementCategory.MILESTONE, rule.category)
            assertEquals(bar, rule.counter(figures(goals = bar)))
        }
    }

    @Test
    fun `the tiers under one prefix are one question at rising bars`() {
        // The beginnings are excluded because their shared prefix names a shelf rather than a family: five
        // firsts ask five different questions, which is exactly what makes them beginnings.
        val families = rules.filter { it.category != AchievementCategory.BEGINNING }
            .groupBy { it.key.substringBeforeLast('_') }
        families.filter { it.value.size > 1 }.forEach { (prefix, family) ->
            val units = family.map { it.unit }.distinct()
            val categories = family.map { it.category }.distinct()
            val counters = family.map { it.counter(figures(tasks = 7L, hours = 7L, goals = 7L)) }
            assertEquals("$prefix mixes units: $units", 1, units.size)
            assertEquals("$prefix mixes shelves: $categories", 1, categories.size)
            val targets = family.map { it.target }
            assertEquals(
                "$prefix bars do not ascend in the order the screen lists them: $targets",
                targets.sorted(),
                targets
            )
            // Same question, so same answer for the same figures — the tiers cannot disagree about what the
            // profile did and differ only about whether that is enough yet. (A family that reads a figure this
            // profile has never produced answers null for every one of its bars, which is one answer too.)
            assertEquals("$prefix reads different figures", 1, counters.distinct().size)
        }
    }

    @Test
    fun `a key is the bar it names, so an address cannot drift from its height`() {
        rules.filter { it.category != AchievementCategory.BEGINNING }.forEach { rule ->
            assertTrue(
                "${rule.key} states a bar of ${rule.target}",
                rule.key.endsWith("_${rule.target}")
            )
        }
    }
}
