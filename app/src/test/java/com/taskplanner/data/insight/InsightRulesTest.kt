package com.taskplanner.data.insight

import com.taskplanner.data.model.EstimateComparison
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.PriorityOverdueCounts
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn a profile's rows into things worth saying about it, tested as arithmetic.
 *
 * Nothing here touches Android, a database, or a string resource, and that is the point of the seam: a rule
 * is a function from counted numbers to a typed reading, so the two decisions §7 cares about can be checked
 * without a screen. The first is the minimum sample — a rule that has not seen enough rows says *nothing*,
 * rather than the same thing at a lower confidence, because a card the reader cannot refute is worse than a
 * quiet screen. The second is the kind: a count over rows that exist, a regularity spread over days, and a
 * reading of what the rows point at are three different claims, and a test that only checked "a finding came
 * out" would pass for all three alike.
 *
 * The window the tests are anchored on is Wednesday 3 June 2026, the same midday the rest of the suite parks
 * the clock at, so the epoch days the rules stamp on their findings are days a reader can name.
 */
class InsightRulesTest {

    private val today: Long = LocalDate.of(2026, 6, 3).toEpochDay()
    private val weekFrom: Long = today - 6
    private val monthFrom: Long = today - 27

    private fun inputs(
        overdue: PriorityOverdueCounts = PriorityOverdueCounts(),
        categoryShifts: List<CategoryShift> = emptyList(),
        estimates: List<EstimateComparison> = emptyList(),
        timing: CompletionTiming = CompletionTiming(),
        habits: List<HabitWindow> = emptyList(),
        goals: List<GoalFigures> = emptyList(),
        load: List<DayLoad> = emptyList(),
        baselineMinutes: Int = 0,
        baselineDays: Int = 0
    ) = InsightInputs(
        today = today,
        weekFromDay = weekFrom,
        weekToDay = today,
        monthFromDay = monthFrom,
        monthToDay = today,
        overdue = overdue,
        categoryShifts = categoryShifts,
        estimateComparisons = estimates,
        completionTiming = timing,
        habitWindows = habits,
        goalFigures = goals,
        upcomingLoad = load,
        baselineDailyMinutes = baselineMinutes,
        baselineMeasuredDays = baselineDays
    )

    private fun List<Finding>.of(key: String): Finding? = firstOrNull { it.ruleKey == key }

    /** The one claim the pass makes about a given rule, asserted to be the only one it makes. */
    private fun List<Finding>.only(): Finding {
        assertEquals("exactly one reading was expected", 1, size)
        return first()
    }

    // -- the profile that has not done anything yet -----------------------------------------------

    @Test
    fun `the rules have nothing to say about a profile with no rows`() {
        val findings = InsightRules.evaluate(inputs())
        assertTrue("an empty profile is not a pattern: $findings", findings.isEmpty())
    }

    // -- high-priority work past its deadline ------------------------------------------------------

    @Test
    fun `a pile of overdue high-priority work is a fact about the day it was counted on`() {
        val findings = InsightRules.evaluate(
            inputs(overdue = PriorityOverdueCounts(highOpen = 4, highOverdue = 3, overdue = 5, open = 12))
        )

        val finding = findings.only()
        assertEquals("high-priority-overload", finding.ruleKey)
        assertEquals(
            "a count over rows that exist is not a regularity and not a guess",
            InsightKind.FACT,
            finding.kind
        )
        assertEquals("the window of a fact about now is one day", today, finding.periodFromDay)
        assertEquals(today, finding.periodToDay)
        assertEquals(3, finding.sampleSize)
        assertEquals(InsightAction.CALENDAR_WEEK, finding.actionType)
        assertEquals("the week the count was taken over", today.toString(), finding.actionRef)
        val evidence = finding.evidence as FindingEvidence.HighPriorityOverload
        assertEquals(3, evidence.highOverdue)
        assertEquals(5, evidence.overdue)
        assertEquals(12, evidence.open)
    }

    @Test
    fun `one overdue high-priority task is on the list already and is not called a pile`() {
        val findings = InsightRules.evaluate(
            inputs(overdue = PriorityOverdueCounts(highOpen = 1, highOverdue = 1, overdue = 1, open = 9))
        )
        assertTrue("a rule with one row behind it stays silent: $findings", findings.isEmpty())
    }

    @Test
    fun `overdue work of other priorities does not become a high-priority reading`() {
        val findings = InsightRules.evaluate(
            inputs(overdue = PriorityOverdueCounts(highOpen = 6, highOverdue = 0, overdue = 6, open = 6))
        )
        assertTrue(findings.isEmpty())
    }

    // -- a category's work growing -----------------------------------------------------------------

    @Test
    fun `a category that grew on real measurements is a pattern that names both weeks`() {
        val findings = InsightRules.evaluate(
            inputs(
                categoryShifts = listOf(
                    CategoryShift(
                        categoryId = 7L,
                        categoryName = "Study",
                        currentMinutes = 300,
                        previousMinutes = 120,
                        currentIntervals = 8,
                        previousIntervals = 5
                    )
                )
            )
        )

        val finding = findings.only()
        assertEquals("category-growth-7", finding.ruleKey)
        assertEquals(InsightKind.PATTERN, finding.kind)
        assertEquals(weekFrom, finding.periodFromDay)
        assertEquals(today, finding.periodToDay)
        assertEquals("the sample is the measurements both weeks were counted from", 13, finding.sampleSize)
        assertEquals(InsightAction.CATEGORY, finding.actionType)
        assertEquals("7", finding.actionRef)
        val evidence = finding.evidence as FindingEvidence.CategoryGrowth
        assertEquals("Study", evidence.categoryName)
        assertEquals(300, evidence.currentMinutes)
        assertEquals(120, evidence.previousMinutes)
        assertEquals("the growth the reader is told about is the one that was counted", 150, evidence.growthPercent)
    }

    @Test
    fun `a category measured once last week has no growth to report`() {
        val findings = InsightRules.evaluate(
            inputs(
                categoryShifts = listOf(
                    CategoryShift(7L, "Study", currentMinutes = 300, previousMinutes = 25, 6, 1)
                )
            )
        )
        assertTrue("one measurement is not a baseline: $findings", findings.isEmpty())
    }

    @Test
    fun `a category that shrank is not offered as growth`() {
        val findings = InsightRules.evaluate(
            inputs(
                categoryShifts = listOf(
                    CategoryShift(7L, "Study", currentMinutes = 60, previousMinutes = 300, 5, 8)
                )
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `work with no category row behind it is not named as a growing category`() {
        val findings = InsightRules.evaluate(
            inputs(
                categoryShifts = listOf(
                    CategoryShift(0L, null, currentMinutes = 400, previousMinutes = 100, 9, 6)
                )
            )
        )
        assertTrue("an unclassified bucket has no screen to lead the reader to: $findings", findings.isEmpty())
    }

    @Test
    fun `two growing categories are two readings, each with its own key`() {
        val profile = inputs(
            categoryShifts = listOf(
                CategoryShift(9L, "Home", currentMinutes = 240, previousMinutes = 120, 6, 4),
                CategoryShift(7L, "Study", currentMinutes = 300, previousMinutes = 100, 8, 5)
            )
        )

        val findings = InsightRules.evaluate(profile)
        assertEquals(2, findings.size)
        assertEquals(
            "the reading with the bigger share is the one offered first",
            listOf("category-growth-7", "category-growth-9"),
            findings.map { it.ruleKey }
        )
        assertEquals(
            "so a pass over the same week reaches the same two rows rather than four",
            findings,
            InsightRules.evaluate(profile)
        )
    }

    // -- estimates against measured time -----------------------------------------------------------

    private fun estimate(id: Long, title: String, minutes: Int, measuredSeconds: Long) =
        EstimateComparison(id, title, minutes, measuredSeconds)

    @Test
    fun `tasks that overran their estimate become a pattern pointed at the worst of them`() {
        val findings = InsightRules.evaluate(
            inputs(
                estimates = listOf(
                    estimate(11L, "Write the second chapter", 45, 3_000L),
                    estimate(12L, "Review the pull request", 30, 1_800L),
                    estimate(13L, "Prepare the talk", 60, 9_000L),
                    estimate(14L, "Fix the flaky test", 20, 2_400L)
                )
            )
        )

        val finding = findings.only()
        assertEquals("estimate-gap", finding.ruleKey)
        assertEquals(InsightKind.PATTERN, finding.kind)
        assertEquals(monthFrom, finding.periodFromDay)
        assertEquals(today, finding.periodToDay)
        assertEquals(4, finding.sampleSize)
        assertEquals(InsightAction.TASK, finding.actionType)
        assertEquals("the task that missed by the most, not the first one read", "13", finding.actionRef)
        val evidence = finding.evidence as FindingEvidence.EstimateGap
        assertEquals(3, evidence.over)
        assertEquals(4, evidence.compared)
        assertEquals("Prepare the talk", evidence.taskTitle)
        assertEquals(60, evidence.estimatedMinutes)
        assertEquals(150, evidence.measuredMinutes)
    }

    @Test
    fun `two estimated tasks are too few to call a habit of underestimating`() {
        val findings = InsightRules.evaluate(
            inputs(
                estimates = listOf(
                    estimate(11L, "a", 30, 7_200L),
                    estimate(12L, "b", 30, 7_200L)
                )
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a month of estimates that held is not a complaint`() {
        val findings = InsightRules.evaluate(
            inputs(
                estimates = listOf(
                    estimate(11L, "a", 45, 1_500L),
                    estimate(12L, "b", 30, 1_200L),
                    estimate(13L, "c", 60, 2_400L),
                    estimate(14L, "d", 20, 900L)
                )
            )
        )
        assertTrue("estimates that held are not a reading of failure: $findings", findings.isEmpty())
    }

    @Test
    fun `work that was never estimated is not counted against the estimates`() {
        val findings = InsightRules.evaluate(
            inputs(
                estimates = listOf(
                    estimate(11L, "a", 45, 7_200L),
                    estimate(12L, "b", 30, 0L),
                    estimate(13L, "c", 60, 5_400L),
                    estimate(14L, "d", 20, 3_000L)
                )
            )
        )

        val evidence = findings.only().evidence as FindingEvidence.EstimateGap
        assertEquals("the sample is the tasks that have a measurement to compare", 3, evidence.compared)
        assertEquals(3, evidence.over)
    }

    // -- when the work gets finished ---------------------------------------------------------------

    @Test
    fun `a weekday holding most of the completions is a pattern that leads nowhere in particular`() {
        val findings = InsightRules.evaluate(
            inputs(
                timing = CompletionTiming(
                    total = 12,
                    byWeekday = mapOf(
                        DayOfWeek.TUESDAY to 7,
                        DayOfWeek.FRIDAY to 3,
                        DayOfWeek.MONDAY to 2
                    )
                )
            )
        )

        val finding = findings.only()
        assertEquals("completion-weekday", finding.ruleKey)
        assertEquals(InsightKind.PATTERN, finding.kind)
        assertEquals(monthFrom, finding.periodFromDay)
        assertEquals("the reading is about a weekday across the window, so no single day opens it",
            null, finding.actionType)
        assertNull(finding.actionRef)
        val evidence = finding.evidence as FindingEvidence.CompletionWeekday
        assertEquals(DayOfWeek.TUESDAY, evidence.weekday)
        assertEquals(7, evidence.count)
        assertEquals(12, evidence.total)
        assertEquals(58, evidence.sharePercent)
    }

    @Test
    fun `a weekday with a third of the completions is not most of them`() {
        val findings = InsightRules.evaluate(
            inputs(
                timing = CompletionTiming(
                    total = 9,
                    byWeekday = mapOf(
                        DayOfWeek.TUESDAY to 3,
                        DayOfWeek.WEDNESDAY to 3,
                        DayOfWeek.THURSDAY to 3
                    )
                )
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `five completions are not yet a rhythm`() {
        val findings = InsightRules.evaluate(
            inputs(timing = CompletionTiming(total = 5, byWeekday = mapOf(DayOfWeek.TUESDAY to 5)))
        )
        assertTrue("the sample gate silences the rule instead of lowering its confidence", findings.isEmpty())
    }

    @Test
    fun `the hours of the day are read as a span rather than a single hour`() {
        val findings = InsightRules.evaluate(
            inputs(
                timing = CompletionTiming(
                    total = 11,
                    byHour = mapOf(16 to 5, 17 to 4, 9 to 2)
                )
            )
        )

        val finding = findings.of("completion-hours")!!
        assertEquals(InsightKind.PATTERN, finding.kind)
        assertNull(finding.actionType)
        val evidence = finding.evidence as FindingEvidence.CompletionHours
        assertEquals("the busiest two hours start at 16:00", 16, evidence.fromHour)
        assertEquals("and cover 16:00 and 17:00", 9, evidence.count)
        assertEquals(11, evidence.total)
        assertEquals(81, evidence.sharePercent)
    }

    @Test
    fun `work finished evenly across the day gives the rules no hour to name`() {
        val findings = InsightRules.evaluate(
            inputs(
                timing = CompletionTiming(
                    total = 12,
                    byHour = (8..19).associateWith { 1 }
                )
            )
        )
        assertTrue(findings.isEmpty())
    }

    // -- a habit's missed days ----------------------------------------------------------------------

    @Test
    fun `a habit missed on two of its days is a fact about the week it asked for`() {
        val findings = InsightRules.evaluate(
            inputs(habits = listOf(HabitWindow(4L, "Run", asked = 7, kept = 5)))
        )

        val finding = findings.only()
        assertEquals("habit-miss-4", finding.ruleKey)
        assertEquals("the rows exist and the marks are there to check", InsightKind.FACT, finding.kind)
        assertEquals(weekFrom, finding.periodFromDay)
        assertEquals(today, finding.periodToDay)
        assertEquals(7, finding.sampleSize)
        assertEquals(InsightAction.HABIT, finding.actionType)
        assertEquals("4", finding.actionRef)
        val evidence = finding.evidence as FindingEvidence.HabitMiss
        assertEquals("Run", evidence.habitTitle)
        assertEquals(2, evidence.missed)
        assertEquals(7, evidence.asked)
        assertEquals(5, evidence.kept)
    }

    @Test
    fun `a habit the profile has only been asked for twice has no missed week to report`() {
        val findings = InsightRules.evaluate(
            inputs(habits = listOf(HabitWindow(4L, "Run", asked = 2, kept = 0)))
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a kept habit is not offered as a reading at all`() {
        val findings = InsightRules.evaluate(
            inputs(habits = listOf(HabitWindow(4L, "Run", asked = 7, kept = 7)))
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `two habits missed in the same week are two readings the reader can answer apart`() {
        val findings = InsightRules.evaluate(
            inputs(
                habits = listOf(
                    HabitWindow(4L, "Run", asked = 7, kept = 4),
                    HabitWindow(8L, "Read", asked = 5, kept = 2)
                )
            )
        )
        assertEquals(listOf("habit-miss-4", "habit-miss-8"), findings.map { it.ruleKey })
    }

    // -- a goal's remaining days ---------------------------------------------------------------------

    @Test
    fun `a goal with days left to run is a reading of the days ahead, not a fact about them`() {
        val findings = InsightRules.evaluate(
            inputs(
                goals = listOf(
                    GoalFigures(
                        goalId = 3L,
                        title = "Ship the report",
                        asksTasks = true,
                        remaining = 6,
                        target = 20,
                        counted = 14,
                        daysLeft = 5,
                        deadlineDay = today + 5
                    )
                )
            )
        )

        val finding = findings.only()
        assertEquals("goal-pace-3", finding.ruleKey)
        assertEquals(
            "tomorrow is not in the database, so the pace is a reading rather than a count",
            InsightKind.HYPOTHESIS,
            finding.kind
        )
        assertEquals(today, finding.periodFromDay)
        assertEquals(today + 5, finding.periodToDay)
        assertEquals(InsightAction.GOAL, finding.actionType)
        assertEquals("3", finding.actionRef)
        assertTrue(
            "a suggestion about days that have not happened is held below a count",
            finding.confidence < InsightRules.FACT_CONFIDENCE
        )
        val evidence = finding.evidence as FindingEvidence.GoalPace
        assertEquals("Ship the report", evidence.title)
        assertEquals(6, evidence.remaining)
        assertEquals(
            "six tasks over five days is two a day, and a fraction the reader cannot act on is no use",
            2,
            evidence.perDay
        )
        assertTrue(evidence.asksTasks)
    }

    @Test
    fun `a goal that is already reached has nothing left to pace`() {
        val findings = InsightRules.evaluate(
            inputs(
                goals = listOf(GoalFigures(3L, "Ship it", true, remaining = 0, target = 20, counted = 20, 5, today + 5))
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a goal due tomorrow cannot be spread over days that are not left`() {
        val findings = InsightRules.evaluate(
            inputs(
                goals = listOf(GoalFigures(3L, "Ship it", true, remaining = 6, target = 20, counted = 14, 1, today + 1))
            )
        )
        assertTrue("one day is not a pace to read: $findings", findings.isEmpty())
    }

    @Test
    fun `a time goal is paced in minutes rather than in tasks`() {
        val findings = InsightRules.evaluate(
            inputs(
                goals = listOf(
                    GoalFigures(3L, "Practice", asksTasks = false, remaining = 300, target = 900, counted = 600, 6, today + 6)
                )
            )
        )

        val evidence = findings.only().evidence as FindingEvidence.GoalPace
        assertFalse("the screen has to know which unit to print", evidence.asksTasks)
        assertEquals(300, evidence.remaining)
        assertEquals(50, evidence.perDay)
    }

    // -- a heavy day ahead ---------------------------------------------------------------------------

    @Test
    fun `a day ahead that asks for more than the profile has been working is a suggestion`() {
        val findings = InsightRules.evaluate(
            inputs(
                load = listOf(
                    DayLoad(today + 1, minutes = 90, tasks = 2),
                    DayLoad(today + 2, minutes = 300, tasks = 5),
                    DayLoad(today + 3, minutes = 60, tasks = 1)
                ),
                baselineMinutes = 100,
                baselineDays = 9
            )
        )

        val finding = findings.only()
        assertEquals("heavy-day-${today + 2}", finding.ruleKey)
        assertEquals(InsightKind.HYPOTHESIS, finding.kind)
        assertEquals(today, finding.periodFromDay)
        assertEquals(today + 3, finding.periodToDay)
        assertEquals(InsightAction.DAY, finding.actionType)
        assertEquals((today + 2).toString(), finding.actionRef)
        val evidence = finding.evidence as FindingEvidence.HeavyDay
        assertEquals(300, evidence.minutes)
        assertEquals(5, evidence.tasks)
        assertEquals(100, evidence.baselineMinutes)
        assertEquals(300, evidence.percentOfBaseline)
        assertEquals(2, evidence.daysOut)
        assertEquals(
            "stated blocks are not estimates, so the reading does not need the caveat",
            false,
            evidence.approximate
        )
    }

    @Test
    fun `a heavy day whose minutes came from estimates says so`() {
        val findings = InsightRules.evaluate(
            inputs(
                load = listOf(DayLoad(today + 4, minutes = 260, tasks = 4, approximate = true)),
                baselineMinutes = 100,
                baselineDays = 9
            )
        )

        val evidence = findings.only().evidence as FindingEvidence.HeavyDay
        assertTrue("the reader has to be told the hours are a guess", evidence.approximate)
    }

    @Test
    fun `a busy day is not heavy without days already worked to compare it to`() {
        val findings = InsightRules.evaluate(
            inputs(
                load = listOf(DayLoad(today + 2, minutes = 480, tasks = 8)),
                baselineMinutes = 0,
                baselineDays = 1
            )
        )
        assertTrue("no baseline means no claim about weight: $findings", findings.isEmpty())
    }

    @Test
    fun `a day with work but no hours named is not called heavy`() {
        val findings = InsightRules.evaluate(
            inputs(
                load = listOf(DayLoad(today + 2, minutes = 0, tasks = 9)),
                baselineMinutes = 100,
                baselineDays = 9
            )
        )
        assertTrue(
            "tasks without a stated duration are numbers, and a number of them is not a load in hours",
            findings.isEmpty()
        )
    }

    @Test
    fun `the heaviest of several heavy days is the one offered`() {
        val findings = InsightRules.evaluate(
            inputs(
                load = listOf(
                    DayLoad(today + 1, minutes = 200, tasks = 3),
                    DayLoad(today + 2, minutes = 400, tasks = 6),
                    DayLoad(today + 5, minutes = 260, tasks = 4)
                ),
                baselineMinutes = 100,
                baselineDays = 9
            )
        )

        assertEquals("one reading per pass, not one per crowded day", 1, findings.size)
        assertEquals("heavy-day-${today + 2}", findings.first().ruleKey)
    }

    // -- what every reading has in common ------------------------------------------------------------

    @Test
    fun `a full profile gives one reading per rule rather than a wall of them`() {
        val findings = InsightRules.evaluate(
            inputs(
                overdue = PriorityOverdueCounts(4, 3, 5, 12),
                categoryShifts = listOf(CategoryShift(7L, "Study", 300, 120, 8, 5)),
                estimates = listOf(
                    estimate(11L, "a", 45, 6_000L),
                    estimate(12L, "b", 30, 1_800L),
                    estimate(13L, "c", 60, 5_400L)
                ),
                timing = CompletionTiming(total = 12, byWeekday = mapOf(DayOfWeek.TUESDAY to 7)),
                habits = listOf(HabitWindow(4L, "Run", asked = 7, kept = 5)),
                goals = listOf(GoalFigures(3L, "Ship it", true, 6, 20, 14, 5, today + 5)),
                load = listOf(DayLoad(today + 2, minutes = 300, tasks = 5)),
                baselineMinutes = 100,
                baselineDays = 9
            )
        )

        assertEquals(
            listOf(
                "completion-weekday",
                "estimate-gap",
                "goal-pace-3",
                "habit-miss-4",
                "heavy-day-${today + 2}",
                "high-priority-overload",
                "category-growth-7"
            ).sorted(),
            findings.map { it.ruleKey }.sorted()
        )
    }

    @Test
    fun `no reading ever claims certainty`() {
        val findings = InsightRules.evaluate(
            inputs(
                overdue = PriorityOverdueCounts(40, 30, 50, 120),
                categoryShifts = listOf(CategoryShift(7L, "Study", 3_000, 120, 80, 50)),
                timing = CompletionTiming(total = 120, byWeekday = mapOf(DayOfWeek.TUESDAY to 100)),
                load = listOf(DayLoad(today + 2, minutes = 600, tasks = 12)),
                baselineMinutes = 100,
                baselineDays = 40
            )
        )

        assertTrue(findings.isNotEmpty())
        findings.forEach { finding ->
            assertTrue(
                "${finding.ruleKey} states ${finding.confidence}%",
                finding.confidence in 1..99
            )
        }
        findings.filter { it.kind != InsightKind.FACT }.forEach { finding ->
            assertTrue(
                "a reading with no rows ahead of it is held below a count: ${finding.ruleKey}",
                finding.confidence < InsightRules.FACT_CONFIDENCE
            )
        }
    }

    @Test
    fun `a pattern gets more confident as the measurements behind it grow`() {
        val few = InsightRules.evaluate(
            inputs(categoryShifts = listOf(CategoryShift(7L, "Study", 300, 150, 6, 4)))
        ).only()
        val many = InsightRules.evaluate(
            inputs(categoryShifts = listOf(CategoryShift(7L, "Study", 300, 150, 40, 30)))
        ).only()

        assertTrue(
            "sample ${few.sampleSize} read as ${few.confidence}%, sample ${many.sampleSize} as ${many.confidence}%",
            many.confidence > few.confidence
        )
    }

    @Test
    fun `the same rows read twice produce the same readings`() {
        val profile = inputs(
            overdue = PriorityOverdueCounts(4, 3, 5, 12),
            habits = listOf(HabitWindow(4L, "Run", asked = 7, kept = 4)),
            load = listOf(DayLoad(today + 2, minutes = 300, tasks = 5)),
            baselineMinutes = 100,
            baselineDays = 9
        )

        assertEquals(
            "a rerun has to land on the rows already left behind, which are keyed by rule and window",
            InsightRules.evaluate(profile),
            InsightRules.evaluate(profile)
        )
    }

    @Test
    fun `a reading whose uuid is derived from rule and window cannot collide with another profile`() {
        val finding = InsightRules.evaluate(
            inputs(overdue = PriorityOverdueCounts(4, 3, 5, 12))
        ).only()

        assertEquals(finding.uuid(1L), finding.uuid(1L))
        assertNotEquals(
            "two accounts on one device may each be told the same thing about their own rows",
            finding.uuid(1L),
            finding.uuid(2L)
        )
    }
}