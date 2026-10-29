package com.taskplanner.res

import com.taskplanner.res.ResourceFiles.LanguageSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A translation may reorder a sentence; it may not change what the sentence asks the code for.
 *
 * `getString(R.string.x, one)` and `getString(R.string.x, one, two)` are different promises, and the compiler keeps
 * neither: a Russian form that asks for a third argument the Kotlin call never passes does not fail the build, it
 * fails the reader, at the moment that string is shown. Positional arguments (`%1$s`, `%2$d`) are the only form that
 * lets a translation put the number where the grammar wants it without changing the argument list, so every
 * multi-argument string has to carry them — in both languages, because the English file is the one the code was
 * written against.
 */
class StringFormatContractTest {

    private val defaults: LanguageSet = ResourceFiles.defaults()
    private val russian: LanguageSet = ResourceFiles.russian()

    @Test
    fun `a russian string asks its caller for the same arguments the english one does`() {
        val mismatched = defaults.names().sorted().flatMap { name ->
            val source = defaults.entries.getValue(name)
            val target = russian.entries[name] ?: return@flatMap emptyList<String>()
            when {
                source.type == "string" && target.type == "string" ->
                    compare(name, source.value!!.text, target.value!!.text, source.where)
                source.type == "plurals" && target.type == "plurals" ->
                    source.quantities.keys.flatMap { quantity ->
                        val english = source.quantities[quantity]?.text ?: return@flatMap emptyList<String>()
                        val russianText = target.quantities[quantity]?.text ?: return@flatMap emptyList<String>()
                        compare("$name[$quantity]", english, russianText, target.where)
                    }
                else -> emptyList()
            }
        }.sorted()
        assertEquals(
            "the argument list a string declares and the argument list its caller passes are the same contract " +
                "seen from two ends — " + mismatched.take(20),
            emptyList<String>(),
            mismatched
        )
    }

    @Test
    fun `every multi-argument string names its arguments positionally`() {
        val offenders = (defaults.entries.values + russian.entries.values)
            .filter { it.value?.translatable != false }
            .flatMap { entry ->
                when (entry.type) {
                    "string" -> listOf(entry.name to entry.value!!.text to entry)
                    "plurals" -> entry.quantities.map { (quantity, value) ->
                        "${entry.name}[$quantity]" to value.text to entry
                    }
                    else -> emptyList()
                }
            }
            .filter { (pair, _) -> ResourceFiles.needsPositional(pair.second) }
            .map { (pair, entry) -> "${pair.first} — \"${pair.second}\" (${entry.where})" }
            .sorted()
        assertEquals(
            "a string with two arguments and no positional markers fixes the word order of the translation to " +
                "the order of the Kotlin call — " + offenders.take(20),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `a russian string escapes the same literal percents the english one does`() {
        val mismatched = defaults.names().sorted().mapNotNull { name ->
            val source = defaults.entries.getValue(name)
            val target = russian.entries[name] ?: return@mapNotNull null
            if (source.type != "string" || target.type != "string") return@mapNotNull null
            val english = source.value!!.text.count { c -> c == '%' }
            val russianCount = target.value!!.text.count { c -> c == '%' }
            if (english == russianCount) null
            else "$name: $english percent signs in English, $russianCount in Russian (${target.where})"
        }.sorted()
        assertEquals(
            "`%%` is a percent the reader sees, not an argument; losing one turns \"100%%\" into a crash or into " +
                "\"100\" — " + mismatched,
            emptyList<String>(),
            mismatched
        )
    }

    /**
     * The gate's own check, against sentences that are wrong on purpose.
     *
     * Each of the three rules above has a failure mode that looks like success: a comparison that reads only the
     * first specifier, or that treats a Russian set it never loaded as "nothing to complain about", reports a clean
     * localization forever.
     */
    @Test
    fun `the checker refuses an added argument, a dropped argument and an unpositioned pair`() {
        val source = languageSetOf(
            "extra" to "Moved %1\$d record(s)",
            "missing" to "Signed in as %1\$s",
            "unpositioned" to "%s of %s done"
        )
        val target = languageSetOf(
            "extra" to "Перенесено %1\$d записей из %2\$d",
            "missing" to "Выполнен вход",
            "unpositioned" to "%s из %s готово"
        )
        val findings = source.names().sorted().mapNotNull { name ->
            compare(name, source.entries.getValue(name).value!!.text,
                target.entries.getValue(name).value!!.text, "synthetic")
        }.flatten()
        assertTrue(
            "a Russian form that asks for a second argument must be refused: $findings",
            findings.any { it.startsWith("extra") }
        )
        assertTrue(
            "a Russian form that drops an argument must be refused: $findings",
            findings.any { it.startsWith("missing") }
        )
        assertTrue(
            "two arguments with no positional markers must be refused: $findings",
            listOf("unpositioned").any { name ->
                ResourceFiles.needsPositional(source.entries.getValue(name).value!!.text)
            }
        )
        assertEquals(
            "a matching pair is not reported",
            emptyList<String>(),
            compare("same", "Shows %1\$d of %2\$d", "Показано %1\$d из %2\$d", "synthetic")
        )
    }

    // ---------------------------------------------------------------- the shared reading

    /** The one comparison all three tests and the self-check share: same arguments, same order, same escapes. */
    private fun compare(name: String, english: String, russianText: String, where: String): List<String> {
        val source = ResourceFiles.argumentsOf(english)
        val target = ResourceFiles.argumentsOf(russianText)
        val findings = mutableListOf<String>()
        // A translation may reorder its arguments -- that is the whole point of the positional markers demanded
        // below -- so this compares the multiset of types. A dropped or added argument still fails.
        if (source.sorted() != target.sorted()) {
            findings += "$name: English asks $source, Russian asks $target ($where)"
        }
        if (source.size > 1) {
            if (ResourceFiles.positionalOf(english).size != source.size) {
                findings += "$name: ${source.size} arguments with no positional markers in English ($where)"
            }
            if (ResourceFiles.positionalOf(russianText).size != target.size) {
                findings += "$name: ${target.size} arguments with no positional markers in Russian ($where)"
            }
        }
        return findings
    }

    private fun languageSetOf(vararg entries: Pair<String, String>): LanguageSet =
        LanguageSet(
            "synthetic",
            entries.associate { (name, text) ->
                name to ResourceFiles.Entry(
                    name = name, type = "string", value = ResourceFiles.Value(text), where = "synthetic"
                )
            }.toLinkedMap()
        )

    private fun <K, V> Map<K, V>.toLinkedMap(): LinkedHashMap<K, V> = LinkedHashMap(this)
}
