package com.taskplanner.res

import com.taskplanner.res.ResourceFiles.Entry
import com.taskplanner.res.ResourceFiles.LanguageSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two language sets hold the same words.
 *
 * Android's own answer to a missing translation is silence: it falls back to the default `values/` and the reader
 * sees English inside a Russian interface, which is the failure this stage exists to prevent. The framework will
 * never report it, and lint's `MissingTranslation` only sees it at build time on a machine that runs lint — so the
 * contract lives here, where a new English string with no Russian counterpart fails a test the same build already
 * runs.
 *
 * The last method is the one that keeps the others honest. A completeness gate whose comparison has rotted reports a
 * clean translation forever, so the test builds a set with one entry missing and requires the checker to name it.
 */
class StringCompletenessTest {

    private val defaults: LanguageSet = ResourceFiles.defaults()
    private val russian: LanguageSet = ResourceFiles.russian()

    @Test
    fun `the russian resource set exists`() {
        assertTrue(
            "values-ru/ holds no resource at all, so every Russian reader is being served the English default " +
                "and no test on this page can say anything yet",
            russian.present
        )
    }

    @Test
    fun `every translatable default entry has a russian counterpart`() {
        val missing = defaults.names()
            .filter { defaults.entries.getValue(it).value?.translatable != false }
            .filterNot { russian.entries.containsKey(it) }
            .sorted()
        assertEquals(
            "these entries are shown to a reader and have no Russian text — the first twenty of ${missing.size}: " +
                missing.take(20),
            emptyList<String>(),
            missing
        )
    }

    @Test
    fun `the russian set holds no entry the default set does not have`() {
        val extra = russian.names().filterNot { defaults.entries.containsKey(it) }.sorted()
        assertEquals(
            "a Russian string with no English original is a string the app can never show, and it will drift out " +
                "of date in silence — " + extra.take(20),
            emptyList<String>(),
            extra
        )
    }

    @Test
    fun `a resource keeps its type across the two languages`() {
        val mismatched = defaults.names()
            .filter { russian.entries.containsKey(it) }
            .filter { defaults.entries.getValue(it).type != russian.entries.getValue(it).type }
            .map { "$it: default says ${defaults.entries.getValue(it).type}, russian says " +
                "${russian.entries.getValue(it).type}" }
        assertEquals(
            "a name that is a string in one language and a plurals block in the other cannot be resolved by " +
                "either — ",
            emptyList<String>(),
            mismatched
        )
    }

    @Test
    fun `no russian value is left empty`() {
        val empty = russian.names().sorted().flatMap { name ->
            val entry = russian.entries.getValue(name)
            when (entry.type) {
                "string" -> if (entry.value!!.text.isBlank()) listOf("$name (${entry.where})") else emptyList()
                "plurals" -> entry.quantities.filterValues { it.text.isBlank() }.keys.map {
                    "$name[$it] (${entry.where})"
                }
                else -> emptyList()
            }
        }
        assertEquals(
            "an empty translation is worse than no translation: the reader gets a blank where a word should be — " +
                empty.take(20),
            emptyList<String>(),
            empty
        )
    }

    /**
     * A Russian value that repeats the English one is either a brand name or an unfinished translation, and the
     * build cannot tell them apart — a person can, once, and the reason then has to be written down here.
     *
     * Values with no letters in them at all (a middle dot, an em dash, "%1$d · %2$d") are exempt by rule rather than
     * by name: there is nothing in them to translate.
     */
    @Test
    fun `a russian value is either translated or named here as untranslatable`() {
        val allowed = mapOf(
            "app_name" to "the product's own name, kept in both languages; a reader searching for the app types it " +
                "the same way whichever language the interface is in"
        )
        val suspects = defaults.names().sorted().mapNotNull { name ->
            val source = defaults.entries.getValue(name)
            val target = russian.entries[name] ?: return@mapNotNull null
            if (source.type != "string" || target.type != "string") return@mapNotNull null
            if (!source.value!!.translatable) return@mapNotNull null
            val english = source.value!!.text.trim()
            val russianText = target.value!!.text.trim()
            if (english != russianText) return@mapNotNull null
            // A value whose only letters belong to a format specifier ("%1$s · %2$s") is a joiner, not a sentence:
            // there is nothing here for a translator to say in Russian.
            if (ResourceFiles.lettersOf(english).isEmpty()) return@mapNotNull null
            val reason = allowed[name]
            if (reason != null) return@mapNotNull null
            "$name: \"$english\" (${target.where})"
        }
        assertEquals(
            "these Russian strings repeat the English word for word with no reason recorded — ",
            emptyList<String>(),
            suspects
        )
    }

    /**
     * The gate's own check: it refuses a set with one entry missing, and it names the entry.
     *
     * Without this, a change to [missingEntries] that made it always return an empty list would leave four green
     * tests and a Russian interface full of English.
     */
    @Test
    fun `the comparison refuses a default entry with no translation and names it`() {
        val source = languageSetOf(
            "keep" to stringEntry("keep", "Keep the task"),
            "drop" to stringEntry("drop", "Delete the task"),
            "already" to stringEntry("already", "Already done")
        )
        val target = languageSetOf(
            "drop" to stringEntry("drop", "Удалить задачу"),
            "already" to stringEntry("already", "Готово")
        )
        assertEquals(listOf("keep"), missingEntries(source, target))

        val everything = languageSetOf(
            "keep" to stringEntry("keep", "Keep the task"),
            "drop" to stringEntry("drop", "Delete the task"),
            "already" to stringEntry("already", "Already done")
        )
        assertEquals("a complete set is not reported as missing anything", emptyList<String>(),
            missingEntries(source, everything))
    }

    // ---------------------------------------------------------------- the shared reading

    private fun missingEntries(source: LanguageSet, target: LanguageSet): List<String> =
        source.names()
            .filter { source.entries.getValue(it).value?.translatable != false }
            .filterNot { target.entries.containsKey(it) }
            .sorted()

    private fun stringEntry(name: String, text: String) =
        Entry(name, "string", value = ResourceFiles.Value(text), where = "synthetic")

    private fun languageSetOf(vararg entries: Pair<String, Entry>) =
        LanguageSet("synthetic", LinkedHashMap(entries.toMap()))
}
