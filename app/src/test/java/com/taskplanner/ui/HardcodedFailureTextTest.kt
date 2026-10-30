package com.taskplanner.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English sentences the app writes about its own failures are counted, and may not grow.
 *
 * Stage 14 translated everything the app *says*: 897 strings, 48 plurals, the whole of `values-ru`. What it
 * could not reach in this pass is the small family of fallback sentences a ViewModel writes when an exception
 * arrives with no message — `"Failed to load categories"`, `"Unexpected error"`, `"Google sign-in failed"` —
 * because a plain `ViewModel` holds no `Context` and asking for one means turning it into an `AndroidViewModel`,
 * which is a change to the injection graph of a dozen screens rather than a change to a string. The brief says
 * not to restructure the architecture for localization and not to leave English islands, so this file does the
 * one thing available between those two: it makes the remainder a number that is checked on every build, and
 * names every place it lives.
 *
 * **Why these are diagnostics rather than copy, and why that is not an excuse.** The text a reader sees in that
 * path is usually the exception's own message — machine output from Room, Firebase or a content resolver, in
 * English whatever the app's language is. Translating only the fallback would produce a Russian sentence
 * wrapped around an English error, which is the half-translated screen the brief forbids. The fix that actually
 * reads well is a resource plus a *localised* detail for the exceptions the app can name, and that belongs to
 * the error-handling stage, not to a translation sweep.
 *
 * **The ratchet.** [CEILING] is the measured count, named file by file in its own comment. A new literal makes this test fail; removing
 * one leaves the ceiling higher than it needs to be, and the next person to touch this file lowers it. When the
 * last one goes, this class should be deleted and the strings checked as translated like any other.
 */
class HardcodedFailureTextTest {

    @Test
    fun theFallbackSentencesAreFewerThanTheCeilingAndListed() {
        val found = fallbackSentences()
        assertTrue(
            "the app writes ${found.size} failure sentences in code where a resource should carry them; " +
                "the ceiling this stage recorded is $CEILING. If one of these is new, put the words in " +
                "values/strings.xml and values-ru/strings.xml instead — a ViewModel can be given the " +
                "sentence to show by whoever holds the Context.\n" + found.joinToString("\n"),
            found.size <= CEILING
        )
    }

    /**
     * What the sweep counts, so the ceiling is a statement about these lines and not about a feeling.
     *
     * Three shapes, each one a sentence the app authored rather than a value read from a resource:
     * `e.message ?: "…"`, `showError("…")` / `Error("…")` with a literal, and a `Snackbar` handed a literal
     * instead of a string resource. Lines that resolve through `R.string.` are not counted; neither are test
     * sources, comments, or the format markers a real string resource would carry.
     */
    private fun fallbackSentences(): List<String> {
        val root = sourceRoot()
        val findings = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = file.path.substringAfter("java${File.separatorChar}")
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                if (trimmed.contains("R.string.") || trimmed.contains("getString(")) return@forEachIndexed
                for ((pattern, kind) in SHAPES) {
                    val match = pattern.find(trimmed) ?: continue
                    val text = match.groupValues[1]
                    // A single word or a machine token is not a sentence; a clause with a space or a colon is.
                    if (!text.contains(' ')) continue
                    findings += "$relative:${index + 1} $kind \"$text\""
                    break
                }
            }
        }
        return findings
    }

    private fun sourceRoot(): File {
        val tried = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        return tried.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "none of ${tried.map { it.path }} holds the module's Kotlin sources, so this count means nothing"
            )
    }

    private companion object {
        /**
         * Measured on stage 14.9 over `app/src/main/java`: 29 sentences across eight files — twenty `e.message
         * ?: …` fallbacks in the categories, profile and tasks ViewModels, five `… not found` state messages,
         * the three Google sign-in strings Stage 13 wrote, the export's "the document writer produced no file",
         * the sync "Sync failed", the task editor's "Title cannot be empty", and `AuthRepository`'s default
         * display name "Google user". Lower it as each one becomes a resource; delete this class when it
         * reaches zero.
         */
        const val CEILING = 29

        val SHAPES = listOf(
            Regex("""\?:\s*"([^"]+)"""") to "fallback",
            Regex("""showError\(\s*"([^"]+)""") to "shown",
            Regex("""Error\(\s*"([^"]+)""") to "state",
            Regex("""make\([^,]+,\s*"([^"]+)""") to "snackbar"
        )
    }
}
