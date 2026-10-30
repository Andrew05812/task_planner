package com.taskplanner.ui.calendar

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportWindow
import com.taskplanner.data.repository.DashboardRequest
import com.taskplanner.testing.SeededProfile
import com.taskplanner.ui.MainActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whose week the app counts, measured on a device with the language chosen rather than assumed.
 *
 * Three screens divide the same work into weeks — the calendar grid, the dashboard's buckets, a report's
 * period — and they are allowed to disagree in exactly one way: they are not allowed to disagree. All three
 * reach the same [CalendarMath.firstDayOfWeek], and that function asks `Locale.getDefault()`, which is a
 * device-level question wearing a process-level answer. Whether the app's own chosen language moves it is the
 * premise the whole of Stage 14's calendar work rests on, and nothing in the repository had measured it: the
 * JVM tests pin `Locale.setDefault(Locale.US)` and `Locale.GERMANY` and so prove the arithmetic follows the
 * locale they hand it, not that the app ever *has* the locale it means.
 *
 * So this class does the measuring. Russian first, because Monday is the answer a Russian reader expects and
 * the one a Sunday-defaulting device would get wrong; English second, because a fix that hardcoded Monday
 * would pass the first and fail this; System default last, because the third promise is that choosing nothing
 * leaves the device in charge.
 *
 * **Why the language is set here through `AppCompatDelegate` and not by tapping the Settings row.** The row is
 * proved where it belongs, in `LanguageSwitchInstrumentedTest`. What this class needs is a locale applied while
 * an activity is alive — the moment the app actually computes a calendar — and it takes the same API the row
 * takes, so the two tests cannot drift apart about which mechanism is the real one.
 */
@RunWith(AndroidJUnit4::class)
class CalendarLocaleTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the app never came up; a locale applied to a process with no activity is not the case this " +
                "feature has to answer",
            waitUntil("a window") { resumedActivityExists() }
        )
    }

    @After
    fun handTheLanguageBack() {
        // The reset happens *before* the window is closed, because a locale applied with no AppCompat host
        // alive is the silent no-op this class measures elsewhere: the first version closed the scenario first,
        // the reset did nothing, and every instrumented class that ran afterwards found a Russian app and
        // false-failed on its own English expectations. `build/stage14-device/report-screen-probe.log` is that
        // failure — a report screen reading "Месяц: 1 окт. 2026 г." under a test written in English.
        // The language goes back to the device on the way out, in every method's success *and* failure path:
        // a class that leaves the app Russian hands the rest of the instrumented matrix a screen full of
        // Russian words, and every one of those classes compares what it sees to a resource value.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun russianWeekOpensOnMondayAndEveryScreenThatCountsWeeksAgrees() {
        choose("ru")

        assertEquals(
            "the calendar's own answer",
            DayOfWeek.MONDAY, CalendarMath.firstDayOfWeek()
        )
        assertEquals(
            "and the report's, which asks the same question through its own companion",
            DayOfWeek.MONDAY, ReportPeriod.firstDayOfCurrentWeek()
        )
        assertEquals(
            "and the dashboard's, read at the moment the request is built rather than cached earlier",
            DayOfWeek.MONDAY, DashboardRequest().firstDayOfWeek
        )

        // The week the report window covers begins on the same instant the calendar's week row begins. This
        // is the claim the brief calls unacceptable to break, and it is stated as data rather than as pixels:
        // two windows that open on the same millisecond cannot be showing different weeks.
        val anchor = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val window = ReportWindow.of(
            period = ReportPeriod.weekOf(anchor),
            zone = zone,
            today = anchor,
            generatedAt = System.currentTimeMillis()
        )
        assertEquals(
            "the report's week starts where the calendar's week starts",
            CalendarMath.startOfDay(CalendarMath.weekGrid(anchor).first(), zone), window.fromMillis
        )
        assertEquals(DayOfWeek.MONDAY, window.firstDay)

        // The month grid, in whole weeks, opening on the language's own first day: a Russian October must not
        // begin on a Sunday, and a grid that silently kept its old opening day would still show every day of
        // the month, which is why the first cell is what is asserted.
        val grid = CalendarMath.monthGrid(YearMonth.of(2026, 10))
        assertEquals("a month grid opens on the week's first day", DayOfWeek.MONDAY, grid.first().dayOfWeek)
        assertEquals("and holds whole weeks", 0, grid.size % 7)
        assertTrue("and covers the 1st it is a grid of", grid.contains(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun englishWeekOpensOnSundaySoNothingWasSimplyHardcodedToMonday() {
        choose("en")

        assertEquals(DayOfWeek.SUNDAY, CalendarMath.firstDayOfWeek())
        assertEquals(DayOfWeek.SUNDAY, ReportPeriod.firstDayOfCurrentWeek())
        assertEquals(DayOfWeek.SUNDAY, DashboardRequest().firstDayOfWeek)

        val anchor = LocalDate.now()
        assertEquals(DayOfWeek.SUNDAY, CalendarMath.weekGrid(anchor).first().dayOfWeek)
        assertEquals(
            "the report's week still starts where the calendar's week starts",
            CalendarMath.startOfDay(CalendarMath.weekGrid(anchor).first(), ZoneId.systemDefault()),
            ReportWindow.of(
                ReportPeriod.weekOf(anchor), ZoneId.systemDefault(), anchor, System.currentTimeMillis()
            ).fromMillis
        )
    }

    @Test
    fun systemDefaultLeavesTheDeviceAnswering() {
        // Leave Russian first, so "no choice of our own" is a change rather than the starting state; then hand
        // the language back and ask the arithmetic. The window is deliberately not watched here: a locale change
        // recreates every activity in the task, and two of them in a row — the leaving and the handing back —
        // measured on this device, can run past a minute while the arithmetic has already settled. This method's
        // claim is about which day a week opens on, so that is what it waits for. `LanguageSwitchInstrumentedTest`
        // owns the claim about what the screen shows after System default.
        applyLocale("ru")
        applyLocale("")

        assertEquals(
            "the app holds no locale of its own",
            "", AppCompatDelegate.getApplicationLocales().toLanguageTags()
        )
        assertEquals(
            "and the week opens where the device opens it, not where the language it just left did",
            deviceWeekStart(), CalendarMath.firstDayOfWeek()
        )
    }

    // ---------------------------------------------------------------- the reading

    /**
     * The device's own first day of week, read from the instrumentation package rather than the app's.
     *
     * The test package holds no app locale of its own, so its configuration is the system's; the target app's
     * configuration reflects whatever language the app last chose, including in this very test. Asking the
     * wrong one would let a leaked Russian setting look like a device that opens its week on Monday.
     */
    private fun deviceWeekStart(): DayOfWeek {
        val deviceLocale = InstrumentationRegistry.getInstrumentation()
            .context.resources.configuration.locales[0]
        return java.time.temporal.WeekFields.of(deviceLocale).firstDayOfWeek
    }

    private fun choose(languageTags: String) {
        applyLocale(languageTags)
        val wanted = languageTags.ifEmpty { deviceLanguageTag() }
        assertTrue(
            "the window never resolved its resources in '$wanted'; the process says " +
                "'${Locale.getDefault().toLanguageTag()}' and the window still says '${activityLanguage()}'",
            waitUntil("the window's language") { activityLanguage() == wanted.substringBefore('-') }
        )
    }

    /**
     * Apply a language and wait for the two things the arithmetic reads.
     *
     * The middle wait is the finding this class exists to record. Thirty-seven production sites ask
     * `Locale.getDefault()` — every date, weekday name, number, percent and CSV delimiter the app renders —
     * and none of them is handed the app's chosen locale; they read the process default and trust it to be the
     * reader's language. Measured on API 35, that trust is earned: choosing Russian moved the process default
     * to `ru` and English moved it back, without a restart of the process. It is asserted here rather than
     * relied upon silently, because it is a framework behaviour this repository does not own and cannot
     * assume past an OS update.
     */
    private fun applyLocale(languageTags: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTags.ifEmpty { null }))
        assertTrue(
            "the app locale never became '$languageTags'; it reports " +
                "'${AppCompatDelegate.getApplicationLocales().toLanguageTags()}'",
            waitUntil("the app locale") {
                AppCompatDelegate.getApplicationLocales().toLanguageTags() == languageTags
            }
        )
        val wanted = languageTags.ifEmpty { deviceLanguageTag() }
        assertTrue(
            "the process default did not follow the app's choice: the app says '$languageTags' while " +
                "Locale.getDefault() says '${Locale.getDefault().toLanguageTag()}' — the calendar reads the " +
                "second of these, so this is where the two come apart",
            waitUntil("the process locale") { Locale.getDefault().toLanguageTag() == wanted }
        )
    }

    /**
     * The language the live window resolves in, read the same way the resources are resolved.
     *
     * Compared as a language rather than as a full tag on purpose. A chosen `ru` reaches the window as `ru`,
     * while handing the decision back to the device can arrive as the device's own `en-US` — measured, when a
     * first version of this assertion compared whole tags and reported `expected en-US, was ru` for a screen
     * that had in fact just been handed the device's language and was on its way to showing it. The claim that
     * matters is which language the reader is being shown, and that is a language.
     */
    private fun activityLanguage(): String {
        var language = ""
        runCatching {
            scenario.onActivity { activity -> language = activity.resources.configuration.locales[0].language }
        }
        return language
    }

    private fun deviceLanguageTag(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.resources.configuration.locales[0].toLanguageTag()

    private fun resumedActivityExists(): Boolean {
        var alive = false
        runCatching { scenario.onActivity { activity -> alive = !activity.isFinishing } }
        return alive
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    private companion object {
        /** A locale change recreates every activity in the task; on a cold emulator that is tens of seconds. */
        const val WAIT_MILLIS = 60_000L

        lateinit var signedIn: SeededProfile.SignedIn

        /** The app has to be on a profile to have a calendar to reason about. */
        @BeforeClass
        @JvmStatic
        fun seedAProfile() {
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.sweepLeftovers(database)
                signedIn = SeededProfile.signIn(
                    database, "stage14-calendar-${System.nanoTime()}@example.test", "Stage 14 calendar"
                )
            } finally {
                database.close()
            }
        }

        @AfterClass
        @JvmStatic
        fun takeTheProfileAway() {
            if (!::signedIn.isInitialized) return
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.takeAway(database, signedIn)
            } finally {
                database.close()
            }
        }
    }
}
