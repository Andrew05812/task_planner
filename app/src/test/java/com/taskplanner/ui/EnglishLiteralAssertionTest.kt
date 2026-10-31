package com.taskplanner.ui

import com.taskplanner.res.ResourceFiles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No test may compare what the app renders to an English word it could have asked the resources for.
 *
 * Stage 14 gave the app a second language, and every test written against the first one became a statement
 * about it. `onView(withText("Tasks"))` does not mean "the tab is labelled the way the app labels it"; it means
 * "the tab is English" — and on a handset set to Russian that is false even when the feature works perfectly.
 * The rule the stage adopted is one line: a test compares a rendered string to `getString(...)` or to a number,
 * never to a word.
 *
 * **The check is the resource list, not a guess about prose.** A literal is a finding here only when the same
 * words are in `values/strings.xml` or `values/calendar.xml` — the strings the app translates. That separates
 * the two things a string literal in a test can be: data the test wrote itself ("Pay rent" seeded into a row
 * and read back, correct in every language because the app never translates a reader's task title) and a label
 * the app produced from a resource (which is exactly what Russian changes). A first version of this sweep
 * matched any literal that looked like words and reported forty lines, most of them fixture data; the version
 * here reports only the ones a language choice breaks.
 *
 * **Two kinds of exemption, both named.** Files whose subject *is* the language — `LanguageSwitchInstrumentedTest`,
 * `CalendarLocaleTest`, `CsvLocaleTest`, `LocalisedNamesTest` — state their expectations as words on purpose:
 * `getString(R.string.title_tasks)` answers "Задачи" in a Russian build and "Tasks" in an English one, and both
 * pass, which is the tautology those tests were written to avoid. Machine-facing text (a CSV column header the
 * export schema promises, a mime type) is listed in [machineFacing] with the reason, because an exemption nobody
 * has to justify is a way of turning a gate off.
 *
 * **Both source sets are swept, on two rules, and the difference is stated.** `app/src/androidTest` runs on a
 * device in whatever language the app is set to, so an English literal there is a false failure waiting for the
 * next reader who opens Settings — and a stem ("categor") counts, because that is how a label gets half-typed
 * into a matcher. `app/src/test` runs under Robolectric, whose locale comes from the test's own `@Config`
 * qualifiers, so no user choice can move it: the literals there compare English to English by construction. That
 * makes them a cleanliness debt rather than a live failure, and the stem rule reports 49 lines of it — 45 of
 * which are a data-layer test naming a SQLite table, an enum constant, or an ISO timestamp. So the JVM sweep
 * takes the `ui` package, where a test is about what a reader sees, and matches whole labels only. Both rules
 * are zero-tolerance; what differs is what each is allowed to see.
 */
class EnglishLiteralAssertionTest {

    /** Files where the language under test is the subject; see the class note for why a word is honest there. */
    private val languageIsTheSubject = setOf(
        "LanguageSwitchInstrumentedTest.kt",
        "CalendarLocaleTest.kt",
        "CsvLocaleTest.kt",
        "LocalisedNamesTest.kt"
    )

    /** Text the app writes for a program rather than for a reader, and therefore never translates. */
    private val machineFacing = mapOf(
        "section" to "the CSV header the export schema is named by; docs/REPORT_EXPORT.md records it",
        "uuid" to "an identity column name in the export, not a label"
    )

    @Test
    fun noRenderedStringIsComparedToATranslatableEnglishWord() {
        val translated = translatableValues()
        assertTrue("the resource sweep found nothing to compare against", translated.isNotEmpty())

        val findings = ArrayList<String>()
        sourceRoot("androidTest").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.name in languageIsTheSubject) return@forEach
            findings += findingsIn(file, translated)
        }

        assertTrue(
            "these assertions compare a string the app renders to the English text it would have taken from " +
                "a resource, so a reader who chose Russian turns a working feature into a failing test " +
                "($findings.size of them):\n" + findings.joinToString("\n") +
                "\nAsk `getString(R.string.…)` / `getQuantityString(…)` instead, or compare the resource id, or " +
                "compare a number. A literal that really is machine-facing belongs in `machineFacing` with the " +
                "reason it is not translated.",
            findings.isEmpty()
        )
    }

    /**
     * The same sweep over the JVM sources, reported as a number rather than as a clean pass.
     *
     * `app/src/test` runs under Robolectric, whose locale comes from the `@Config` qualifiers of the test and
     * not from the reader's choice, so an English literal there cannot produce the false failure an instrumented
     * one causes. It is still a comparison of English to English by construction, and it is what this stage was
     * asked to inventory: the count is named, the files are listed, and the number may not grow.
     *
     * **The JVM sweep matches whole labels, and says why the two rules differ.** The stem rule the instrumented
     * sweep needs — "categor" standing for "Categories" — reports 49 lines over 540 JVM test files, and reading
     * them found four that were about rendered text. `"first"` is not a comparison of a label; it is a word
     * inside `"date (oldest first)"`, and `"tasks"` is a SQLite table name. A rule tuned to catch a stem in a
     * UI matcher is the wrong rule for a corpus of data-layer tests, and pretending otherwise would put 45
     * false findings in front of the 4 real ones every run. The instrumented sweep keeps the loose rule, because
     * that is where a stem is a label being half-typed against a rendered string.
     */
    @Test
    fun theJvmSourcesCompareNoRenderedLabelToItsEnglishText() {
        val translated = translatableValues()
        val findings = ArrayList<String>()
        sourceRoot("test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "ui${File.separatorChar}" in it.path }
            .forEach { file ->
                if (file.name in languageIsTheSubject || file.name in jvmFixtureFiles ||
                    file.name == "EnglishLiteralAssertionTest.kt"
                ) {
                    return@forEach
                }
                findings += findingsIn(file, translated, wholeLabelsOnly = true)
            }
        assertTrue(
            "a UI test compares a rendered string to the English text of a resource in ${findings.size} " +
                "places, and the rule is zero. Robolectric answers in the language of the test's own " +
                "qualifiers, so this is not the false failure an instrumented literal causes — but a test that " +
                "names the English words cannot tell that Russian was ever offered, and it will quietly stop " +
                "checking anything the day a `@Config(qualifiers = \"ru-rRU\")` case is added. Ask " +
                "getString(...) / getQuantityString(...) for the expectation, or seed and compare your own " +
                "data and add the file to jvmFixtureFiles with the reason.\n" + findings.joinToString("\n"),
            findings.isEmpty()
        )
    }

    /**
     * The detector run on a file it writes, because an empty finding list is also what a broken sweep prints.
     *
     * Each shape has its own case, and the fixture case is here too: a literal that is not a resource value must
     * not be reported, or the sweep would drown the real findings in seeded data.
     */
    @Test
    fun theDetectorFindsTheShapesItClaimsToFind() {
        val translated = setOf("tasks", "archive", "a week", "categories")
        val cases = listOf(
            // An Espresso matcher handed a resource value.
            """onView(withText("Archive")).perform(click())""" to true,
            // The same words reached through a constant in the same file.
            """
                private const val WEEK_CHIP = "A week"
                onView(withId(R.id.periodChip)).check(matches(withText(WEEK_CHIP)))
            """.trimIndent() to true,
            // A translation asked for by words, in the three-argument form this suite writes.
            """assertEquals("the tab changed", "Tasks", text(R.id.tabTitle))""" to true,
            // A stem of a resource value, which is how the destructive-confirmation test checked five rows.
            """assertTrue("says what it deletes", message.contains("categor"))""" to true,
            // Seeded data: the app never translates a reader's own title, so this one is legitimate.
            """assertEquals("Pay rent", movedTask.title)""" to false,
            // A resource-based comparison, which is the shape the rule wants instead.
            """assertEquals(context.getString(R.string.title_tasks), text(R.id.tabTitle))""" to false,
            // A machine-facing token named in the exemption list.
            """assertTrue(body.startsWith("section"))""" to false
        )

        for ((source, expected) in cases) {
            val found = findingsInText(source, translated, "Synthetic.kt")
            assertTrue(
                "expected a finding: $expected for `${source.replace("\n", " ")}` but got $found",
                found.isNotEmpty() == expected
            )
        }
    }

    @Test
    fun everyExemptionSaysWhatItIs() {
        assertTrue(
            "an exemption has to be a reason: $machineFacing",
            machineFacing.values.all { it.length > 15 && it.isNotBlank() }
        )
        assertTrue(
            "the language-subject files are named by their real filenames",
            languageIsTheSubject.all { name ->
    listOf("androidTest", "test").any { set ->
                sourceRoot(set).walkTopDown().any { it.name == name }
            }
            }
        )
    }

    // ---------------------------------------------------------------- the reading

    private fun findingsIn(file: File, translated: Set<String>, wholeLabelsOnly: Boolean = false): List<String> =
        findingsInText(file.readText(), translated, file.name, wholeLabelsOnly)

    /**
     * Every literal on an assertion-shaped line that names a translatable string.
     *
     * Two passes: constants in the file are resolved first, because `withText(WEEK_CHIP)` is the same claim as
     * `withText("A week")` and the second form is the only one a line-based sweep can see. Lines are matched
     * rather than parsed — a literal can still hide in an expression built at runtime, which is what the three
     * files rewritten for this stage were found by reading. The sweep's job is to stop the next one being added
     * by accident.
     */
    private fun findingsInText(
        source: String,
        translated: Set<String>,
        label: String,
        wholeLabelsOnly: Boolean = false
    ): List<String> {
        val constants = Regex("""(?:const |private |val)\s+(\w+)\s*=\s*"([^"]+)"""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val findings = ArrayList<String>()
        source.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
            if (!ASSERTION_SHAPE.containsMatchIn(line)) return@forEachIndexed

            val literals = LITERALS.findAll(line).map { it.groupValues[1] }.toMutableList()
            RESOLVED.find(line)?.let { constants[it.groupValues[2]] }?.let { literals += it }

            // The first quoted argument of these calls is the message a reader of the failure sees, which is
            // prose by design and is not a comparison: `waitUntil("the list")` describes what the helper is
            // waiting for. Dropping it is the difference between four findings and one false one.
            if (MESSAGE_FIRST.containsMatchIn(line) && literals.isNotEmpty()) literals.removeAt(0)

            for (value in literals) {
                val lowered = value.lowercase().trim()
                if (lowered.length < 4) continue
                if (lowered.contains('%') || lowered.contains('$') || lowered.contains('.')) continue
                if (machineFacing.keys.any { lowered.startsWith(it) }) continue
                // A full label, or the stem of one: "categor" is how the destructive-confirmation test reached
                // "Categories" five times over, and a stem is as English as the word it cuts. The JVM sweep
                // turns the stem rule off -- see [theJvmSourcesCompareNoRenderedLabelToItsEnglishText] for what
                // it reports when it is on.
                val matched = translated.firstOrNull { entry ->
                    if (wholeLabelsOnly) entry == lowered
                    else entry == lowered ||
                        (lowered.length >= 5 && entry.split(" ").any { it == lowered || it.startsWith(lowered) })
                }
                if (matched != null) {
                    findings += "$label:${index + 1} \"$value\" matches the resource text \"$matched\""
                }
            }
        }
        return findings
    }

    /**
     * The English text the app can be asked to render, normalised for comparison.
     *
     * Format arguments are cut because a rendered string never contains `%1$s`; a literal like "1 task" is
     * matched against the resource's own shape once the placeholder is gone.
     */
    private fun translatableValues(): Set<String> =
        ResourceFiles.defaults().entries.values
            .flatMap { entry ->
                when (entry.type) {
                    "string" -> listOfNotNull(entry.value?.takeIf { it.translatable }?.text)
                    "plurals" -> entry.quantities.values.filter { it.translatable }.map { it.text }
                    else -> emptyList()
                }
            }
            .filter { it.isNotBlank() }
            .map { normalise(it) }
            .filter { it.isNotEmpty() && it.any(Char::isLetter) }
            .toSet()

    private fun normalise(text: String): String =
        text.replace(Regex("""%[0-9$]*[sd]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()

    private fun sourceRoot(sourceSet: String): File {
        val tried = listOf(
            File("src/$sourceSet/java"),
            File("app/src/$sourceSet/java"),
            File("../app/src/$sourceSet/java")
        )
        return tried.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "none of ${tried.map { it.path }} holds the $sourceSet sources — the working directory of this " +
                    "test is not the module it was written for, so the sweep cannot say anything"
            )
    }

    private companion object {
        /** Only lines that are an assertion or an Espresso matcher are read at all. */
        val ASSERTION_SHAPE = Regex(
            """(assertEquals|assertThat|assertTrue|assertFalse|withText|textContains|contains\(|matches\().*"""
        )

        /**
         * UI-package tests whose English words are the test's own data or a Java identifier, not a label the
         * app looked up in a resource. Each one matched a resource value by coincidence of wording.
         */
        val jvmFixtureFiles = mapOf(
            "ChartViewDrawTest.kt" to
                "the legend entries are constructed by the test and read back off the view it built; \"Late\" " +
                    "is the test's own label, and the resource of the same word never enters the assertion",
            "HabitsViewModelTest.kt" to
                "\"Stretch\" is a habit name the test seeds and then asks the ViewModel about. The app never " +
                    "translates a reader's own name, in either language",
            "BottomNavigationStyleTest.kt" to
                "asserts that a destination's `android:name` contains \"Fragment\" — a Java identifier checked " +
                    "as text, not copy a reader sees"
        )

        /** A double-quoted literal on the line. */
        val LITERALS = Regex(""""([^"]{4,})"""")

        /** Lines whose leading literal is an assertion's own message rather than its expectation. */
        val MESSAGE_FIRST =
            Regex("""^(assertEquals|assertNotEquals|assertTrue|assertFalse|waitUntil|assertNull|assertNotNull)\s*\(\s*"""")

        /** A bare identifier handed to a text matcher, resolved through the file's constants. */
        val RESOLVED = Regex("""(withText|textContains|equalTo)\(([A-Z][A-Z0-9_]*)\)""")
    }
}
