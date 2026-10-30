package com.taskplanner.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing that resolves the reader's language is allowed to be worked out once and kept.
 *
 * Stage 14 makes the language a choice a reader can make while the app is running, and every locale-derived
 * value the app cached at an earlier moment is now a sentence in the wrong language. A field on a Fragment, an
 * adapter or a view is safe: choosing a language recreates the activity, so those objects are built again and
 * read the new locale. A `companion object` is not — it belongs to the class and to the process, is initialised
 * the first time the class is touched, and is never asked again. `CategoryTaskAdapter` held exactly that when
 * this test was written, which is why this file exists: its due-date column kept answering "Oct 15, 2026" to a
 * reader who had just chosen Русский, on a screen that had every other word in Russian.
 *
 * **Why a source gate rather than a UI test.** The claim is about a lifetime, and a UI test would have to
 * happen to open that one screen after a language change to notice. This reads every Kotlin file in the module
 * and answers the question for all of them, in milliseconds, on every CI run — the same shape as
 * `Stage8aTokenSweepTest`, which checks the layouts for literals for the same reason.
 *
 * **What is allowed to stay static.** A format that is a machine's, not a reader's: `Locale.ROOT` patterns and
 * ISO formatters carry no language, and refusing them would push their authors into resolving them per call for
 * no reason. The gate looks for the reach for the *default* locale, which is the only thing that changes under
 * a language choice.
 */
class LocaleStateSourceTest {

    /**
     * The expressions that resolve something in the reader's language.
     *
     * Named with the reason each is here, because the list is the design: a value computed from any of these is
     * only as current as the moment it was computed.
     */
    private val localeResolvers = listOf(
        "SimpleDateFormat(" to "a formatter that carries the locale it was built with",
        "DateFormat.getDateInstance" to "a locale-derived instance held for the class's lifetime",
        "DateFormat.getDateTimeInstance" to "a locale-derived instance held for the class's lifetime",
        "DateFormat.getTimeFormat" to "a locale-derived instance held for the class's lifetime",
        "DateFormat.getDateFormat" to "a locale-derived instance held for the class's lifetime",
        "NumberFormat.get" to "the reader's separators and percent sign, frozen at first touch",
        "DecimalFormatSymbols.getInstance" to "the decimal separator that also chooses the CSV delimiter",
        "DateTimeFormatter.ofLocalized" to "a locale-derived formatter",
        "WeekFields.of(" to "the week's first day, which is the language's, not a constant",
        "Collator.getInstance" to "the alphabet order of the reader's language",
        "Locale.getDefault()" to "the reader's locale, read now and stale after a language change",
        "LocaleList.getDefault()" to "the reader's locale list, same lifetime problem"
    )

    @Test
    fun noValueResolvedFromTheReadersLocaleIsHeldAtClassLevel() {
        val findings = companionHeldLocaleValues()
        assertTrue(
            "these values are computed once per process and never re-read, so a language change leaves them " +
                "behind:\n" + findings.joinToString("\n") +
                "\nMove each one to the object that is rebuilt with the window (an adapter field, a fragment " +
                "field) or resolve it where it is used.",
            findings.isEmpty()
        )
    }

    /**
     * The same sweep, turned on a file this test writes.
     *
     * Without this the empty result above would only prove the detector is looking at nothing — the shape of
     * failure every source gate in this repository defends against, and the reason
     * `Stage8aTokenSweepTest` and `LegacySchemaV7SourceTest` each carry a self-check too.
     */
    @Test
    fun theSweepRefusesACompanionThatResolvesTheReadersLocale() {
        val source = """
            package com.example

            class Reader {
                companion object {
                    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    private val machine = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
                }
            }
        """.trimIndent()

        val flagged = localeResolversInCompanion(source, "Reader.kt")
        assertEquals(
            "the machine's format is allowed to stay, the reader's is not: $flagged",
            listOf("Reader.kt:5 SimpleDateFormat( — a formatter that carries the locale it was built with"),
            flagged
        )
    }

    @Test
    fun aMachineFormatIsNotFlaggedBecauseItCarriesNoLanguage() {
        val source = """
            package com.example

            object Machine {
                private val day = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
                private val stamp = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
            }
        """.trimIndent()

        assertEquals(emptyList<String>(), localeResolversInCompanion(source, "Machine.kt"))
    }

    @Test
    fun theSameValueOnAnObjectThatDiesWithTheWindowIsFine() {
        // A field of the adapter itself is rebuilt when the activity recreates, which is what a language change
        // does. Naming the difference here is what stops a later reader from "fixing" ArchiveAdapter by moving
        // its formatter into a companion.
        val source = """
            package com.example

            class Adapter {
                private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            }
        """.trimIndent()

        assertEquals(emptyList<String>(), localeResolversInCompanion(source, "Adapter.kt"))
    }

    /**
     * The pattern `CardPresentation` already uses, protected from a later "cleanup".
     *
     * Its companion holds the *pattern* strings and builds the formatter inside the function that needs it, so
     * every call resolves the reader's current language. A sweep that flagged this would push the module toward
     * caching the formatter to get quiet — turning the one correct file into the defect the gate exists to
     * prevent. Found while writing this gate: the first version flagged `CardPresentation.kt:116` alongside the
     * real defect, which is how the distinction between a body's property and a function's local came to be
     * tested rather than assumed.
     */
    @Test
    fun aFormatterBuiltInsideACompanionFunctionIsNotHeldAtAll() {
        val source = """
            package com.example

            class Presentation {
                companion object {
                    private const val DAY_PATTERN = "MMM dd, yyyy"

                    fun dueLine(dueDate: Long): String {
                        val time = SimpleDateFormat(DAY_PATTERN, Locale.getDefault()).format(java.util.Date(dueDate))
                        return time
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            "a local built per call is the fix, not the finding: ",
            emptyList<String>(),
            localeResolversInCompanion(source, "Presentation.kt")
        )
    }

    /**
     * The process default is read in exactly one place, and that place is the accessor whose job it is.
     *
     * The companion sweep above answers "was a locale-derived value kept too long". This answers the other
     * question Stage 14 opened: a call that reaches for `Locale.getDefault()` *at the right moment* is still
     * the wrong moment on API 24 to 32, where the per-app locale changes the resources and leaves the process
     * default alone. Five reader-facing sites survived the first sweep for exactly that reason — a weekday
     * name in an insight, a clock reading in a span, the month letter beside a calendar day, and the
     * dashboard's percent and plain number — each correct on the API 35 device and each answering the handset
     * rather than the reader below it.
     *
     * The rule is therefore not "read the default freshly" but "ask [AppLocale], which decides between the
     * reader's choice and the device". One file is allowed to read the process default, because one file has
     * to: `AppLocale.current()` is where the fallback lives.
     */
    @Test
    fun onlyTheAccessorReadsTheProcessDefault() {
        // `.toList()` on purpose. A `Sequence` compared to a `List` never equals it, so a first version of
        // this rule failed on its own plumbing and printed `FlatteningSequence@36bc415e` where it should have
        // named a file — a gate whose failure message cannot be read is a gate that will be deleted rather
        // than debugged.
        val offenders: List<String> = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                file.readLines().withIndex().filter { (_, line) ->
                    val trimmed = line.trim()
                    !(trimmed.startsWith("*") || trimmed.startsWith("//")) &&
                        (line.contains("Locale.getDefault()") || line.contains("LocaleList.getDefault()"))
                }.map { (index, _) -> "${index + 1}: ${file.name}" }.toList()
            }
            .toList().flatten()
        val outside = offenders.filterNot { it.endsWith("AppLocale.kt") }

        assertEquals(
            "`Locale.getDefault()` belongs in AppLocale.kt alone — everywhere else, ask AppLocale.current(), " +
                "which is the same call with the reader's choice in front of it. Found: $outside",
            emptyList<String>(), outside
        )
        assertEquals(
            "and even there it is one line, the fallback — a second one is a new site that missed this rule. " +
                "Found: ${offenders.filter { it.endsWith("AppLocale.kt") }}",
            1, offenders.count { it.endsWith("AppLocale.kt") }
        )
    }

    // ---------------------------------------------------------------- the sweep

    private fun companionHeldLocaleValues(): List<String> {
        val root = sourceRoot()
        val findings = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = file.path.substringAfter("java${File.separatorChar}")
            findings += localeResolversInCompanion(file.readText(), relative)
        }
        return findings
    }

    /**
     * Every locale-resolving property declaration that sits in a static body.
     *
     * Brace depth, not indentation: the module's files disagree about both, and the only thing the sweep needs
     * is "am I still inside the companion". A property's initializer is read across lines until its brackets
     * close, because a multi-line initialiser still resolves its value once, at the same moment — and because
     * the exemption for a machine format has to be decided by *that* declaration alone. A first version of this
     * sweep looked four lines ahead instead, so a `Locale.ROOT` belonging to the next property excused the one
     * under inspection, and the gate reported a clean module while `CategoryTaskAdapter` was holding exactly the
     * defect it was written to find.
     *
     * A local inside a function of that body is not a finding and must not become one: `CardPresentation` holds
     * its date *patterns* at class level and builds the formatter inside `dueLine`, which is the correct answer
     * to this problem — a fresh object per call reads the current locale. The two share a file, so the sweep
     * distinguishes them by where the declaration sits, and the test that covers that distinction is
     * [aFormatterBuiltInsideACompanionFunctionIsNotHeldAtAll].
     */
    private fun localeResolversInCompanion(source: String, label: String): List<String> {
        val lines = source.lines()
        val findings = ArrayList<String>()
        var staticDepth = -1
        var functionDepth = -1
        var depth = 0
        lines.forEachIndexed { index, line ->
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            val inStaticBody = staticDepth >= 0 && depth > staticDepth
            val inFunction = functionDepth >= 0 && depth > functionDepth
            when {
                staticDepth < 0 && STATIC_BODY.containsMatchIn(line) -> staticDepth = depth
                inStaticBody && !inFunction && opens > 0 && FUNCTION.containsMatchIn(line) -> functionDepth = depth
                inStaticBody && !inFunction && line.matches(PROPERTY) -> {
                    val declaration = initializerAt(lines, index)
                    val machineOnly = declaration.contains("Locale.ROOT") || declaration.contains("ISO_")
                    if (!machineOnly) {
                        for ((needle, why) in localeResolvers) {
                            if (declaration.contains(needle)) {
                                findings += "$label:${index + 1} $needle — $why"
                                break
                            }
                        }
                    }
                }
            }
            depth += opens - closes
            if (functionDepth >= 0 && depth <= functionDepth) functionDepth = -1
            if (staticDepth >= 0 && depth <= staticDepth) staticDepth = -1
        }
        return findings
    }

    /** The text of the declaration starting at [index]: its own line plus the lines its brackets need. */
    private fun initializerAt(lines: List<String>, index: Int): String {
        val builder = StringBuilder()
        var balance = 0
        var i = index
        while (i < lines.size) {
            val line = lines[i]
            builder.append(line).append(' ')
            balance += line.count { it == '(' || it == '{' || it == '[' } -
                line.count { it == ')' || it == '}' || it == ']' }
            if (balance <= 0) break
            i++
        }
        return builder.toString().trim()
    }

    private fun sourceRoot(): File {
        val tried = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java")
        )
        return tried.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "none of ${tried.map { it.path }} is a source directory — the working directory of this test " +
                    "is not the module it was written for, so the sweep cannot say anything"
            )
    }

    private companion object {
        /**
         * A property declaration that computes something: modifiers optional, an `=` somewhere after the name.
         *
         * `line.matches` demands a whole-line match, so this pattern has to run to the end of the line itself —
         * a version that stopped at `=` matched only declarations whose initialiser began on the next line, and
         * swept every real file clean.
         */
        val PROPERTY = Regex("""\s*(private |internal |public )?(const )?(val|var) \w+[=: ].*""")

        /** A `companion object` and a named `object` are the same lifetime problem: one instance, per process. */
        val STATIC_BODY = Regex("""(companion object|^\s*(private |internal |public )?object \w+)""")

        /** A function inside that body: what it builds locally is rebuilt on every call, which is the point. */
        val FUNCTION = Regex("""\bfun \w+""")
    }
}
