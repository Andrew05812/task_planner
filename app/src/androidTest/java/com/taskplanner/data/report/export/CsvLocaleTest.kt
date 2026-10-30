package com.taskplanner.data.report.export

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.testing.SeededProfile
import com.taskplanner.ui.MainActivity
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
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
 * What the reader's own language does to the figures, measured with the app's language chosen rather than the
 * machine's assumed.
 *
 * `CsvReport.defaultDelimiter()` and every method of [ReportFormatting] ask `Locale.getDefault()` and hand the
 * answer to `DecimalFormatSymbols` or `DateTimeFormatter`. `CalendarLocaleTest` established on this device that
 * the process default follows the app's chosen language; that makes the choice a reader-facing one in numbers
 * as well as in words, and this class is where each of those promises is pinned:
 *
 * - a Russian reader's Excel must open the CSV in columns, which needs `;` because their numbers write `,`;
 * - the same figure must not appear as `1.5` in one section of a report and `1,5` in another;
 * - a month name belongs to the language the app was set to, not to the language the handset was sold in.
 *
 * **Why the expectations are spelled in Russian here.** The language is the subject under test, so a literal is
 * the only form that says which answer is meant — the same reason `LanguageSwitchInstrumentedTest` writes its
 * tab words out. Every other test in the repository compares to a resource value, and this rule is what keeps
 * this one from being an English test wearing a Russian name.
 *
 * **Why the app is open while the figures are asked about.** A first version of this class never launched an
 * activity, called `AppCompatDelegate.setApplicationLocales` from the instrumentation thread, and found the
 * locale refused to move: `getApplicationLocales()` kept answering the empty string and all four methods failed
 * on that one premise. The delegate stores the choice through the AppCompat host, so with no window there is
 * nothing to store it in — a silent no-op rather than an error, and a trap for any later stage that thinks a
 * locale can be applied from `Application.onCreate`. The app being on screen is also the only moment any of
 * this matters: a report is generated from a window, by a reader who has already chosen a language in one.
 */
@RunWith(AndroidJUnit4::class)
class CsvLocaleTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeTheWindow() {
        scenario.close()
        // Back to the device's own language on the way out, for the same reason the calendar class does it:
        // the rest of the instrumented matrix is written against English resources.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun aRussianReaderGetsTheDelimiterTheirExcelNeedsAndTheCommaThatForcesIt() {
        choose("ru")

        assertEquals(
            "ru_RU writes its decimal separator as a comma, so a comma-separated file would open as one column",
            ',', DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
        )
        assertEquals(
            "and the file therefore separates its columns with a semicolon",
            ';', CsvReport.defaultDelimiter()
        )
        // The figure and the delimiter come from the same locale: a file whose columns are `;` but whose
        // numbers are `1.5` would be read as one long string by the spreadsheet it was written for.
        assertEquals("1,5", ReportFormatting.decimal(1.5, 1))
    }

    @Test
    fun anEnglishReaderGetsCommasBetweenColumnsAndDotsInsideNumbers() {
        choose("en")

        assertEquals('.', DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator)
        assertEquals(',', CsvReport.defaultDelimiter())
        assertEquals("1.5", ReportFormatting.decimal(1.5, 1))
    }

    /**
     * The words the figures are wrapped in.
     *
     * A month heading is the one line a report or a calendar writes that is neither a resource string nor a
     * number, and it is the one place where a Russian reader can see a formatter's guess about grammar. Asked
     * for October 2026 rather than for "this month", so the assertion does not expire and does not depend on
     * the day the suite runs.
     *
     * **The device found the defect behind this expectation.** `SimpleDateFormat("MMMM yyyy")` answers
     * "октября 2026" in Russian — the genitive, the form a date with a day in it takes ("5 октября 2026"),
     * which ICU chooses because a year is present in the pattern. With no day in the heading, the nominative
     * "Октябрь 2026" is the one a reader writes, and the Calendar screen's month title had been showing the
     * genitive on every Russian month. `ReportFormatting.month` now asks for the standalone name for that
     * reason; the Calendar screen asks `month` rather than a pattern of its own.
     */
    @Test
    fun monthAndWeekdayNamesComeFromTheChosenLanguage() {
        choose("ru")
        assertEquals("Октябрь 2026", ReportFormatting.month(LocalDate.of(2026, 10, 15)))
        assertEquals("понедельник", ReportFormatting.weekday(DayOfWeek.MONDAY))

        choose("en")
        assertEquals("October 2026", ReportFormatting.month(LocalDate.of(2026, 10, 15)))
        assertEquals("Monday", ReportFormatting.weekday(DayOfWeek.MONDAY))
    }

    /**
     * One language for the whole file.
     *
     * The two formatting paths the exporter uses are the delimiter and the number; if they were resolved from
     * different locales the header would say `;` while the body wrote dots. Asserted together after a *change*
     * of language, because the defect this prevents is a stale value left over from the language the app was in
     * before — the shape a `companion object` or a field holding a formatter would produce.
     */
    @Test
    fun changingLanguageMovesTheDelimiterAndTheNumbersTogether() {
        choose("ru")
        val russian = Pair(CsvReport.defaultDelimiter(), ReportFormatting.decimal(2.25, 2))
        choose("en")
        val english = Pair(CsvReport.defaultDelimiter(), ReportFormatting.decimal(2.25, 2))

        assertEquals(';', russian.first)
        assertEquals("2,25", russian.second)
        assertEquals(',', english.first)
        assertEquals("2.25", english.second)
        assertTrue(
            "the two languages produced the same file shape, which means one of them is not being read",
            russian != english
        )
    }

    // ---------------------------------------------------------------- the reading

    /** The waits this class shares with [com.taskplanner.ui.calendar.CalendarLocaleTest] — see its KDoc. */
    private fun choose(languageTags: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageTags.ifEmpty { null })
        )
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline &&
            AppCompatDelegate.getApplicationLocales().toLanguageTags() != languageTags
        ) {
            Thread.sleep(100)
        }
        assertEquals(
            "the app locale never became '$languageTags'",
            languageTags, AppCompatDelegate.getApplicationLocales().toLanguageTags()
        )
        // The premise this whole class rests on: the figures are read from the process default, so the process
        // default has to be the chosen language. Asserted rather than assumed because it is the framework's
        // behaviour, not this repository's.
        while (System.currentTimeMillis() < deadline && Locale.getDefault().language != languageTags) {
            Thread.sleep(100)
        }
        assertEquals(
            "Locale.getDefault() did not follow the app's choice, so every formatted figure in a report is " +
                "still being made in the old language",
            languageTags, Locale.getDefault().language
        )
    }

    private companion object {
        /** A locale change is applied by recreating activities; on a cold emulator that is tens of seconds. */
        const val WAIT_MILLIS = 60_000L

        lateinit var signedIn: SeededProfile.SignedIn

        /**
         * A profile to be signed into, so the app opens on a screen with a bottom bar rather than on the
         * profile picker. The window is the point of this class — see the note above on why a locale applied to
         * a process with no AppCompat host does nothing at all.
         */
        @BeforeClass
        @JvmStatic
        fun seedAProfile() {
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.sweepLeftovers(database)
                signedIn = SeededProfile.signIn(
                    database, "stage14-csv-${System.nanoTime()}@example.test", "Stage 14 csv"
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
