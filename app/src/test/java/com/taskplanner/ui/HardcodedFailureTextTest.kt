package com.taskplanner.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English the app writes about itself is counted, and may not grow.
 *
 * Stage 14's first pass could not reach these sentences and left them as a number: a plain `ViewModel` holds
 * no `Context`, so the fallback in `CategoriesViewModel.kt:55` had nowhere to read a resource from, and the
 * brief said not to rebuild the injection graph for a translation. That premise turned out to be the defect's
 * excuse rather than its limit. The state now carries the **resource id** and the fragment that draws it
 * resolves the words, which needed no new Context anywhere -- and it fixed a second bug on the way, because a
 * sentence written into a state object at the moment of failure survives the activity recreation that changing
 * the language causes, and is still in the language the reader has just left.
 *
 * **Three shapes, and why each is on the list.**
 *
 * 1. `?: "a sentence"` -- the app's own words standing in for a machine's. This is what the gate always had.
 * 2. A string literal, or a `SOME_CONSTANT`, passed as the first argument of an error sink (`State.Error(...)`,
 *    `Outcome.Failed(...)`, `Upsert.Refused(...)`). The old gate looked only for a literal *written at the
 *    call*, which is how `ProfileState.Error(EMAIL_TAKEN)` reached a Russian Snackbar with the sweep reporting
 *    a clean module: `const val EMAIL_TAKEN = "Email is already taken"` was invisible to all four of its
 *    patterns. A named constant is the same English with one more hop.
 * 3. An exception's own text handed to one of those sinks (`Failed(attempt.message)`, `error = e.message`).
 *    A `SQLITE_BUSY` string is not something a reader can act on, and it is English whatever the app speaks;
 *    the sentence the app owns goes on screen and the complaint goes to `Log.w`.
 *
 * **What is deliberately not counted.** A sink whose first argument is a resource (`R.string.x`), a resource
 * property (`messageRes`, `frame.messageRes`), a named `detail =` / `formatArgs =` argument, or a value that is
 * a machine note rather than a claim about the world. `ReportExport.Failed(ReportFailure.EMPTY_FILE)` is the
 * shape this file wants everywhere: an enum that the screen turns into a localized sentence.
 *
 * **The ratchet.** [CEILING] is the measured count, named site by site in its own comment. A new literal makes
 * this test fail; removing one leaves the ceiling higher than it needs to be, and the next person to touch
 * this file lowers it. When the last one goes, delete this class.
 */
class HardcodedFailureTextTest {

    @Test
    fun theAppWritesNoEnglishSentenceThatAResourceShouldCarry() {
        val found = englishTheAppOwns()
        assertTrue(
            "the app writes ${found.size} English sentences where a resource should carry them; the ceiling " +
                "this pass recorded is $CEILING. Put the words in values/strings.xml and values-ru/strings.xml " +
                "and hand the sink `R.string.the_name` -- a ViewModel needs no Context to hold an id, and the " +
                "fragment that draws it already has one.\n" + found.joinToString("\n"),
            found.size <= CEILING
        )
    }

    /**
     * Every line the three shapes catch, as `file:line shape "text"`.
     *
     * Comments are skipped -- this file's own documentation quotes the patterns it forbids, and a gate that
     * flagged its own explanation would be deleted rather than read. Everything else is scanned as text rather
     * than parsed, because what is being detected is a choice one call makes: does it hand the sink a resource
     * or a sentence.
     *
     * A call's arguments are gathered across lines until its parentheses close, and the *first* one is what is
     * judged. That is not decoration: `SignInAttempt.Failed(e.message ?: localized(context, R.string.x))` is
     * written over two lines in this module, and a line-by-line reader sees `localized(context` -- an argument
     * that looks like English the app owns and is the fix for it. The first version of this gate made exactly
     * that mistake and reported three false findings, which is how a gate earns its deletion.
     */
    private fun englishTheAppOwns(): List<String> {
        val root = sourceRoot()
        val findings = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = file.path.substringAfter("java${File.separatorChar}")
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                FALLBACK.find(trimmed)?.let { match ->
                    if (match.groupValues[1].contains(' ')) {
                        findings += "$relative:${index + 1} fallback \"${match.groupValues[1]}\""
                    }
                }
                SINK.find(trimmed)?.let { match ->
                    // The parenthesis the *sink's own name* opens, found after the end of the name it matched.
                    // Measuring from the start of the line instead lands on an earlier call -- it did, on
                    // `reportRepository.report(period, zone) ?: return ReportExport.Failed(...)`, where the
                    // argument read came out as `period` and a correct line was reported as a defect.
                    val name = match.groups[1] ?: return@let
                    val open = trimmed.indexOf('(', name.range.last + 1)
                    if (open >= 0) {
                        val first = firstArgument(lines, index, open)
                        if (first.isNotEmpty() && !isAResource(first) && first !in EXEMPT) {
                            findings += "$relative:${index + 1} ${match.groupValues[1]} \"$first\""
                        }
                    }
                }
            }
        }
        return findings
    }

    /** The text between the open paren at [open] and the argument's own end, across as many lines as it takes. */
    private fun firstArgument(lines: List<String>, from: Int, open: Int): String {
        val collected = StringBuilder()
        var depth = 0
        var started = false
        for (lineIndex in from until minOf(from + MAX_SCAN_LINES, lines.size)) {
            val text = if (lineIndex == from) lines[lineIndex].substring(open) else lines[lineIndex]
            for (character in text) {
                if (character == '(') {
                    depth++
                    started = true
                } else if (character == ')') {
                    depth--
                    if (depth == 0) return collected.toString().trim()
                } else if (character == ',' && depth == 1) {
                    return collected.toString().trim()
                }
                if (started && depth >= 1 && character != '(') collected.append(character)
            }
            collected.append(' ')
        }
        return collected.toString().trim()
    }

    /** Does this argument name a string resource, or a value that already holds one? */
    private fun isAResource(argument: String): Boolean =
        argument.contains("R.string.") ||
            ALLOWED.containsMatchIn(argument.trim())

    private fun sourceRoot(): File {
        val tried = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        return tried.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "none of ${tried.map { it.path }} holds the module's Kotlin sources, so this count means nothing"
            )
    }

    private companion object {
        /**
         * Measured over `app/src/main/java` on the Stage 14 completion pass, down from 29. What is left, and
         * why each one is a diagnostic rather than copy:
         *
         * - `AuthRepository.kt:164` — `ProfileUpsert.Refused("Firebase returned no usable uid")`: why the
         *   account could not be matched to a profile. It reaches the settings screen through
         *   `SignInOutcome.Failed.detail`, which is interpolated into a localized sentence
         *   (`R.string.sign_in_failed`) rather than shown as this text.
         * - `AuthRepository.kt:165` — `"Google user"`: a *default display name* written into `users.name` when
         *   the account gives none. It is data the reader can rename, not a message the app says; translating
         *   it would freeze one language into a stored row and show a Russian name to a reader who switches to
         *   English tomorrow.
         * - `AuthRepository.kt:183` — `Refused("linkProfile did not run")`: an invariant. It is written so a
         *   stack trace names itself, and no branch that reaches it is reachable without a code change first.
         * - `BackupModels.kt:60` — the note `BackupValidationException` reports to `Exception.message`, so a
         *   log line says which resource it refused. The reader gets `error.messageRes`, resolved by the
         *   screen that shows it.
         * - `ReportExport.kt:225` — an `IOException` for a PDF writer that returned nothing. The report screen
         *   never prints it: it maps `ReportFailure.EMPTY_FILE` to `R.string.report_fail_empty`.
         *
         * Google sign-in as a whole is `BLOCKED — Firebase project / OAuth configuration required`, so the two
         * `AuthRepository` refusals are unreachable in a shipped build today; when it is configured they join
         * the resources. Lower the ceiling as each one goes, and delete this class when it reaches zero.
         */
        const val CEILING = 5

        /** `x.message ?: "a sentence"` -- and any other fallback to a literal with a space in it. */
        val FALLBACK = Regex("""\?:\s*"([^"]+)""")

        /** An error sink, at the parenthesis its arguments open after. */
        val SINK = Regex("""([A-Za-z_][\w.]*\.(?:Error|Failed|Refused))\(""")

        /** How far to follow an open call for its arguments. No error sink in this module is deeper than three. */
        const val MAX_SCAN_LINES = 8

        /** A first argument that is already the right shape: a resource, a resource property, or a named note. */
        val ALLOWED = Regex(
            """^(R\.string\.|\w*Res\b|detail\s*=|formatArgs\s*=|message\s*=|message$|null\b|\w*Failure\.)"""
        )

        /**
         * Values the sink rule would otherwise flag that are not English the app owns: a throwable forwarded as
         * the cause rather than as a sentence.
         */
        val EXEMPT = setOf("it", "e", "this")
    }
}
