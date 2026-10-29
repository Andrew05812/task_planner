package com.taskplanner.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The two language sets, read the way Android reads them: as a merged map per resource directory.
 *
 * Three decisions are worth their reasons, because every localization test in this package is built on them.
 *
 * **XML, not text.** A `<string>` can span lines, carry attributes (`translatable`, `formatted`), hold an entity
 * (`&#8212;`) or sit inside a comment. A regular expression over the file sees the first case as two entries, the
 * second as none, and the third as a value with markup in it. `DocumentBuilderFactory` sees what the resource
 * compiler sees, so a contract test that passes here is a contract test that passes in the build.
 *
 * **Merged across files, compared as one map.** Android resolves `values/strings.xml` and `values/calendar.xml`
 * into the same table, so a Russian key may legitimately move between files. What must not move is the key set and
 * the type of each key — which is why the loader walks every XML file in a directory rather than pairing files.
 *
 * **A missing directory is a state, not an exception.** `values-ru/` does not exist yet, and the tests have to say
 * "the Russian set is absent" rather than crash on a `FileNotFound`; `defaulted` tells a test whether it is looking
 * at a real translation or at an empty map, so an assertion cannot quietly become vacuous.
 */
object ResourceFiles {

    /** One `<string>` or one `<item>` of a `<plurals>`: its text, and the attributes that decide whether it counts. */
    data class Value(
        val text: String,
        val translatable: Boolean = true
    )

    data class Entry(
        val name: String,
        /** "string", "plurals" or "string-array" — the type must not change between languages. */
        val type: String,
        /** For a string or an array item; empty for a plurals block. */
        val value: Value? = null,
        /** For a plurals block: quantity → value. Empty otherwise. */
        val quantities: Map<String, Value> = emptyMap(),
        /** Where this entry was read from, for the failure message: `values/strings.xml:164`. */
        val where: String
    )

    /** Everything a language directory holds, keyed by resource name. */
    class LanguageSet(val directoryName: String, val entries: Map<String, Entry>) {
        val present: Boolean get() = entries.isNotEmpty()

        fun names(): Set<String> = entries.keys
        fun of(type: String): Map<String, Entry> = entries.filterValues { it.type == type }
    }

    /**
     * The module's `res/` directory, found the way Stage 8a's token sweep finds its layouts: the test JVM's working
     * directory is not fixed across Gradle, the IDE and CI, so the three plausible roots are tried and a miss is
     * reported as the harness problem it is rather than as a passing test.
     */
    private fun resRoot(): File {
        val tried = listOf(File("src/main/res"), File("app/src/main/res"), File("../app/src/main/res"))
        return tried.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "no resource directory among ${tried.map { it.path }} — this test cannot speak about strings.xml " +
                    "from a working directory that is not the module it was written in"
            )
    }

    fun setOf(directory: String): LanguageSet {
        val dir = File(resRoot(), directory)
        if (!dir.isDirectory) return LanguageSet(directory, emptyMap())
        val merged = LinkedHashMap<String, Entry>()
        dir.listFiles { file -> file.extension == "xml" }
            ?.sortedBy { it.name }
            ?.forEach { file -> parse(file, directory, merged) }
        return LanguageSet(directory, merged)
    }

    fun defaults(): LanguageSet = setOf("values")

    fun russian(): LanguageSet = setOf("values-ru")

    private fun parse(file: File, directory: String, into: LinkedHashMap<String, Entry>) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The resource files declare no DTD and fetch nothing; turning external entities off also stops a test
            // run reaching the network over a malformed file.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(file)
        val root = document.documentElement
        var line = 0
        val children = root.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            line++
            val element = node as org.w3c.dom.Element
            val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            val where = "$directory/${file.name}"
            when (element.tagName) {
                "string" -> into[name] = Entry(
                    name = name,
                    type = "string",
                    value = Value(
                        text = element.textContent,
                        translatable = element.getAttribute("translatable") != "false"
                    ),
                    where = where
                )
                "plurals" -> {
                    val quantities = LinkedHashMap<String, Value>()
                    val items = element.getElementsByTagName("item")
                    for (i in 0 until items.length) {
                        val item = items.item(i) as org.w3c.dom.Element
                        quantities[item.getAttribute("quantity")] = Value(
                            text = item.textContent,
                            translatable = item.getAttribute("translatable") != "false"
                        )
                    }
                    into[name] = Entry(name, "plurals", quantities = quantities, where = where)
                }
                "string-array", "array" -> {
                    val items = LinkedHashMap<String, Value>()
                    val childrenOfArray = element.getElementsByTagName("item")
                    for (i in 0 until childrenOfArray.length) {
                        val item = childrenOfArray.item(i) as org.w3c.dom.Element
                        items[i.toString()] = Value(item.textContent)
                    }
                    into[name] = Entry(name, "string-array", quantities = items, where = where)
                }
                else -> Unit // colors, dimens, styles: not part of the language contract
            }
        }
    }

    // ---------------------------------------------------------------- the rules

    /**
     * Every format specifier in a value, in the order it appears, each as the text that produced it
     * (`%1$s`, `%d`, `%%`).
     *
     * Android's own grammar is what a translation has to satisfy, so this follows `java.util.Formatter`'s shape:
     * `%`, an optional `index$`, flags, width, precision, then the conversion. `%%` is a literal percent and is
     * returned as itself, because a translation that drops one is as much a defect as one that drops an argument.
     */
    private val specifier = Regex("""%(\d+\$)?[-+ #0]*\d*(?:\.\d+)?[a-zA-Z%]""")

    fun specifiersOf(text: String): List<String> = specifier.findAll(text).map { it.value }.toList()

    /** The conversion characters of the real arguments, with `%%` removed and an index stripped from each. */
    fun argumentsOf(text: String): List<String> =
        specifiersOf(text).filter { !it.endsWith("%") }.map { it.removePrefix("%").replace(Regex("""\d+\$"""), "") }

    /**
     * The argument *indices* a value names: `[1, 2]` for "%1$s … %2$s", empty for "%s … %s".
     *
     * This is what separates a translation that may reorder its sentence from one that cannot: a positional marker
     * lets the number sit wherever the grammar wants it, and a bare conversion pins the sentence to the order of the
     * Kotlin call.
     */
    fun positionalOf(text: String): List<String> =
        specifiersOf(text)
            .mapNotNull { it.substringAfter('%').takeIf { body -> body.endsWith('$') || '$' in body } }
            .map { it.substringBefore('$') }
            .filter { it.isNotEmpty() }

    /** A value that carries more than one argument cannot reorder its words without positional arguments. */
    fun needsPositional(text: String): Boolean {
        val arguments = specifiersOf(text).count { !it.endsWith("%") }
        return arguments > 1 && positionalOf(text).size != arguments
    }

    /**
     * The letters a value really says, with every format specifier cut out first.
     *
     * "%1$s · %2$s" contains an `s`, but that `s` belongs to the formatter, not to a reader: a separator string has
     * nothing to translate, and a check that looks at raw characters would demand a Russian version of a joiner.
     */
    fun lettersOf(text: String): String = text.replace(specifier, "").filter { it.isLetter() }

    /**
     * The CLDR category Russian picks for a whole number.
     *
     * Written out rather than read from a table because the whole point of the stage is the two categories English
     * does not have: `few` (2, 3, 4, 22, 102 …) and `many` (0, 5-20, 25, 111 …). The trap the brief names — 11 taking
     * the form of 1, 22 taking the form of 5 — is exactly the `%100` clause, so the numbers below are the ones that
     * exercise it.
     */
    fun russianCategory(count: Int): String {
        val ten = count % 10
        val hundred = count % 100
        return when {
            ten == 1 && hundred != 11 -> "one"
            ten in 2..4 && hundred !in 11..14 -> "few"
            ten == 0 || ten in 5..9 || hundred in 11..14 -> "many"
            else -> "other"
        }
    }

    /** The numbers a Russian plural must be right for: the singles, the teens, the second decade, and 101/111. */
    val keyCounts = listOf(0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111)
}
