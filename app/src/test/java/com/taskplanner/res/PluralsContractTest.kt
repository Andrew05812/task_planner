package com.taskplanner.res

import com.taskplanner.res.ResourceFiles.LanguageSet
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Russian plural system, contract-tested form by form.
 *
 * English has two cardinal categories. Russian has four — `one`, `few`, `many`, `other` — and the default resource
 * set was written with `one` and `other` only, which means every count line in the app is grammatically wrong in
 * Russian even after it is translated: Android finds no `few` and quietly serves `other`, so a reader sees
 * "2 задача" instead of "2 задачи". That is worse than English, not better, which is why this file checks the
 * categories before any translation is judged finished.
 *
 * The categories are only half of it. A block whose four forms are the same sentence pasted four times passes every
 * structural check and still says "5 задача" — so the shapes are compared against each other, and a block that
 * legitimately repeats a form (a phrase where the noun is not the counted word) has to be named here with its
 * reason, the same way an untranslatable string is.
 */
class PluralsContractTest {

    private val defaults: LanguageSet = ResourceFiles.defaults()
    private val russian: LanguageSet = ResourceFiles.russian()

    private val russianCategories = listOf("one", "few", "many", "other")

    /**
     * Blocks whose Russian forms are the same sentence, and why that is correct rather than unfinished.
     *
     * Each entry is a phrase with no counted noun to decline, so `one`/`few`/`many` cannot differ no matter how
     * well the translation is done. A reason has to be a sentence about the phrase, not a way to make a test pass,
     * and [the two gates agree on the exceptions] requires the CI script to carry the same four names.
     */
    private val identicalFormsAllowed = mapOf(
        "history_manual_count" to "\"%1\$d внесено вручную\" — the counted thing is a set of numbers, not a noun " +
            "in the sentence, and adding one would change what the line claims",
        "goal_count_figure" to "\"%1\$d из %2\$d выполнено\" — the noun is governed by the denominator, which is " +
            "the second argument and never pluralized by this block",
        "goal_set_figure" to "\"%1\$d из %2\$d задач выполнено\" — after \"из\" plus a numeral Russian always takes " +
            "the genitive plural, so no differing form exists",
        "habit_times_per_week" to "the Russian counter word \"раз\" has two shapes for whole numbers, not three: " +
            "1 раз, 2-4 раза, 5 раз — so `one` and `many` coincide by the language rather than by omission",
        "achievement_unit_other" to "\"%d\" alone — a bare figure with no unit word, and the unit family this " +
            "belongs to is unreachable: no achievement rule uses AchievementUnit.OTHER"
    )

    @Test
    fun `every russian plurals block carries one few many and other`() {
        val incomplete = russian.of("plurals").values.mapNotNull { entry ->
            val absent = russianCategories.filterNot { entry.quantities.containsKey(it) }
            if (absent.isEmpty()) null else "${entry.name} is missing ${absent.joinToString()} (${entry.where})"
        }.sorted()
        assertEquals(
            "Android falls back to `other` without a word, so a missing category reads as a grammar mistake in " +
                "every sentence that uses it — " + incomplete.take(20),
            emptyList<String>(),
            incomplete
        )
    }

    @Test
    fun `the english set keeps the categories english resolves`() {
        val incomplete = defaults.of("plurals").values
            .mapNotNull { entry ->
                val absent = listOf("one", "other").filterNot { entry.quantities.containsKey(it) }
                if (absent.isEmpty()) null else "${entry.name} is missing ${absent.joinToString()} (${entry.where})"
            }.sorted()
        assertEquals(
            "English needs `one` and `other`; a block without them cannot be resolved in the default language at " +
                "all — " + incomplete,
            emptyList<String>(),
            incomplete
        )
    }

    @Test
    fun `the russian integer categories of a block are three different forms`() {
        val suspects = russian.of("plurals").values.mapNotNull { entry ->
            // `other` is the fraction fallback ("1,5 задачи"), so it may repeat any of the three; the categories a
            // whole number actually reaches must not, or the block says the wrong thing for some count no matter
            // which one Android picks.
            val integers = listOf("one", "few", "many").mapNotNull { entry.quantities[it]?.text?.trim() }
            if (integers.size != 3 || integers.distinct().size == 3) return@mapNotNull null
            if (identicalFormsAllowed.containsKey(entry.name)) return@mapNotNull null
            "${entry.name}: " + integers.mapIndexed { index, text -> "${listOf("one", "few", "many")[index]}=\"$text\"" }
                .joinToString(" | ") + " (${entry.where})"
        }.sorted()
        assertEquals(
            "a block whose one/few/many forms repeat says the wrong thing for some numbers; if a Russian counter " +
                "genuinely has fewer than three shapes (\"раз\" does), name the block and say why — " +
                suspects.take(12),
            emptyList<String>(),
            suspects
        )
    }

    @Test
    fun `every russian plural count resolves to a form the block has`() {
        val blocks = russian.of("plurals").values
        assertTrue("no russian plurals were read at all, so this test proves nothing", blocks.isNotEmpty())
        val wrong = blocks.flatMap { entry ->
            ResourceFiles.keyCounts.mapNotNull { count ->
                val category = ResourceFiles.russianCategory(count)
                if (entry.quantities.containsKey(category)) null
                else "${entry.name}: $count takes category \"$category\" and the block has no such form"
            }
        }
        assertEquals(
            "the key counts are 0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111 — the ones that catch the " +
                "`few`/`many` mistakes a native reader sees first — " + wrong.take(10),
            emptyList<String>(),
            wrong
        )
    }

    /**
     * The rule itself, before it is applied to anything.
     *
     * 11 and 101 are the pair that separates a correct implementation from one that only looks at the last digit:
     * both end in 1, only 101 is `one`, because 11 is the teens case. 21 and 111 say the same thing from the other
     * direction.
     */
    @Test
    fun `the russian category rule answers the numbers a reader would check`() {
        val expected = mapOf(
            0 to "many", 1 to "one", 2 to "few", 3 to "few", 4 to "few", 5 to "many",
            11 to "many", 12 to "many", 14 to "many", 21 to "one", 22 to "few", 25 to "many",
            101 to "one", 111 to "many"
        )
        val wrong = expected.filterNot { (count, category) -> ResourceFiles.russianCategory(count) == category }
            .map { (count, category) -> "$count should be \"$category\", was \"${ResourceFiles.russianCategory(count)}\"" }
        assertEquals("the CLDR rule is what every block below is checked with, so it is tested on its own — $wrong",
            emptyList<String>(), wrong)
    }

    /**
     * The gate's own check: a block missing `many`, and a block whose three integer forms collapse, are both refused.
     *
     * Written against synthetic data because the real data is what the other four methods are testing — if the
     * comparison rotted, those four would go green on the day the file is written and stay green forever.
     */
    @Test
    fun `the checker refuses a block with a missing category and a block of pasted forms`() {
        val incomplete = pluralsSetOf("задача" to mapOf("one" to "%d задача", "other" to "%d задач"))
        assertEquals(listOf("few", "many"), absentRussianCategories(incomplete).getValue("задача"))

        val pasted = pluralsSetOf(
            "справка" to mapOf(
                "one" to "%d справка", "few" to "%d справка", "many" to "%d справка", "other" to "%d справки"
            )
        )
        assertEquals(listOf("справка"), blocksWithRepeatedForms(pasted))

        // `other` is the fraction fallback and may repeat the few-form; that is the CLDR rule, not a mistake.
        val fractionFallback = pluralsSetOf(
            "задача" to mapOf(
                "one" to "%d задача", "few" to "%d задачи", "many" to "%d задач", "other" to "%d задачи"
            )
        )
        assertEquals(emptyList<String>(), blocksWithRepeatedForms(fractionFallback))

        val correct = pluralsSetOf(
            "задача" to mapOf(
                "one" to "%d задача", "few" to "%d задачи", "many" to "%d задач", "other" to "%d задачи"
            )
        )
        assertEquals(emptyList<String>(), blocksWithRepeatedForms(correct).filterNot { it == "задача" })
        assertTrue(
            "a complete block must not be reported as incomplete",
            absentRussianCategories(correct).values.all { it.isEmpty() }
        )
    }

    /**
     * The two gates agree on the exceptions.
     *
     * `scripts/check-localization.py` enforces the same rule for CI, in another language, from another process. A
     * name allowed here and refused there makes the pipeline red for a sentence that is correct, and the reverse
     * lets a genuinely pasted block through both. So every exception is required to appear in the script's own
     * allow-list, which is read as text because the script is not importable from a JVM test.
     */
    @Test
    fun `the two gates agree on the exceptions`() {
        val script = listOf(
            File("scripts/check-localization.py"),
            File("app/../scripts/check-localization.py"),
            File("../scripts/check-localization.py")
        ).firstOrNull(File::exists)
            ?: throw AssertionError("the CI localization script is not where this test looks for it")
        val text = script.readText()
        val declared = Regex(""""([a-z0-9_]+)"\s*: """).findAll(
            text.substringAfter("IDENTICAL_FORMS_ALLOWED").substringBefore("]")
        ).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the JVM allow-list and the CI allow-list have drifted apart",
            identicalFormsAllowed.keys.toSortedSet(),
            declared.toSortedSet()
        )
    }

    // ---------------------------------------------------------------- the shared reading

    private fun absentRussianCategories(set: LanguageSet): Map<String, List<String>> =
        set.of("plurals").values.map { entry ->
            entry.name to russianCategories.filterNot { entry.quantities.containsKey(it) }
        }.filter { it.second.isNotEmpty() }.toMap()

    private fun blocksWithRepeatedForms(set: LanguageSet): List<String> =
        set.of("plurals").values.mapNotNull { entry ->
            val integers = listOf("one", "few", "many").mapNotNull { entry.quantities[it]?.text?.trim() }
            if (integers.size == 3 && integers.distinct().size < 3) entry.name else null
        }

    private fun pluralsSetOf(vararg blocks: Pair<String, Map<String, String>>): LanguageSet {
        val entries = LinkedHashMap<String, ResourceFiles.Entry>()
        blocks.forEach { (name, quantities) ->
            entries[name] = ResourceFiles.Entry(
                name = name,
                type = "plurals",
                quantities = quantities.entries.associate { (quantity, text) ->
                    quantity to ResourceFiles.Value(text)
                },
                where = "synthetic"
            )
        }
        return LanguageSet("synthetic", entries)
    }
}
