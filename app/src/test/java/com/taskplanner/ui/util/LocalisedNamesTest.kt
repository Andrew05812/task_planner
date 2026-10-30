package com.taskplanner.ui.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alphabet the reader was taught, not the order Unicode assigned to the code points.
 *
 * Three screens in this app put names in order: the Tasks list's "by name", the calendar's overflow legend,
 * and the Categories list. All three were comparing strings, which orders them by UTF-16 unit — and for a
 * Russian reader that is not an alphabet. "Ёлка" came before "Афиша", because U+0451 is lower than U+0410;
 * "Яблоко" came before "апельсин", because every capital letter sits below every small one. A reader who
 * presses "По имени" and sees those orders concludes the sort did nothing, and the app gives them no way to
 * tell that apart from a broken button.
 *
 * The fix is a `Collator` for the app's language, which is what `java.text` offers for exactly this question.
 * The DAO keeps its `ORDER BY name ASC` — a stored order the database can answer cheaply, and one that stays
 * useful for the sync engine and for a reader who never opens a sorted list. What the reader is *shown* is
 * ordered by the alphabet, which is the layer that knows the language.
 *
 * **What these tests deliberately do not settle.** Russian collation has real disagreements — where a stress
 * mark belongs, whether `ё` counts as `е` at the level a list should care about — and Android's ICU and the
 * JDK's `java.text.Collator` are not guaranteed to answer them alike. Every expectation below is a case the
 * two agree on and a reader can check by eye; anything subtler belongs on a device, and `TasksHeaderInstrumentedTest`
 * already checks the visible order of five Russian titles there.
 */
class LocalisedNamesTest {

    /** The five titles `TasksHeaderInstrumentedTest` seeds, in the order a Russian reader expects. */
    @Test
    fun theRussianAlphabetOrdersTitlesTheCodePointsDoNot() {
        val names = listOf("Яндекс", "Афиша", "Блокнот", "Написать обзор литературы", "Сформировать цель ВКР")

        val sorted = names.sortedWith(LocalisedNames.byString(Locale("ru")))

        assertEquals(
            "Афиша, Блокнот, then the two long ones in their own order, then Яндекс",
            listOf("Афиша", "Блокнот", "Написать обзор литературы", "Сформировать цель ВКР", "Яндекс"),
            sorted
        )
    }

    /**
     * The letter that code points put at the top of the list.
     *
     * This is the defect in its clearest form: `ё` is U+0451, below `а` at U+0430, so a plain comparison files
     * "Ёлка" ahead of everything in the alphabet and a reader looking for it looks first, not last. Russian
     * collation puts it next to `е`, which is where a Russian dictionary puts it.
     */
    @Test
    fun yoSortsWithTheLetterItBelongsTo() {
        val names = listOf("Ёлка", "Афиша", "Блокнот", "Еда")

        val sorted = names.sortedWith(LocalisedNames.byString(Locale("ru")))

        assertEquals(listOf("Афиша", "Блокнот", "Еда", "Ёлка"), sorted)
        assertTrue(
            "a list that begins with Ёлка is the bug: ${sorted.first()}",
            sorted.first() != "Ёлка"
        )
    }

    @Test
    fun caseDoesNotJumpTheAlphabet() {
        val names = listOf("Яблоко", "апельсин", "хлеб", "Хлебный")

        val sorted = names.sortedWith(LocalisedNames.byString(Locale("ru")))

        assertEquals(
            "х comes before я, so both х words belong ahead of Яблоко however it is capitalised",
            listOf("апельсин", "хлеб", "Хлебный", "Яблоко"),
            sorted
        )
    }

    @Test
    fun anEnglishReaderGetsTheOrderTheyExpect() {
        val names = listOf("yesterday", "Apple", "banana", "Cherry")

        assertEquals(
            listOf("Apple", "banana", "Cherry", "yesterday"),
            names.sortedWith(LocalisedNames.byString(Locale.ENGLISH))
        )
    }

    /**
     * The comparator takes its language from the call, and nothing about it is remembered.
     *
     * **What this file can and cannot prove.** A locale change is a thing a reader can do while the app runs, so
     * a collator resolved once and kept would sort by the alphabet the reader has left. That is guarded by
     * construction — `byString()` builds a fresh collator on every call — and the class-level version of the same
     * mistake is refused by `LocaleStateSourceTest`. What the JVM cannot show is the two alphabets *disagreeing*
     * about a word list: measured here, the JDK's Russian collation keeps the root script order, so a Latin name
     * still precedes a Cyrillic one in both languages, and every Cyrillic pair these tests use is ordered the
     * same way by any `Collator`. A first version of this method asserted a difference between the two that does
     * not exist, and the honest answer is to stop claiming it: the promise that the app's chosen language
     * reaches `Locale.getDefault()` at all is measured on a device, in `CalendarLocaleTest` and `CsvLocaleTest`,
     * and the order a reader actually sees in a Russian list is measured there too.
     */
    @Test
    fun theComparatorIsBuiltFromWhateverTheDefaultLocaleIsNow() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            val names = listOf("Ёлка", "Афиша", "Еда")
            assertEquals(
                "a collator asked for the current default, whatever it is, still puts the letters in order",
                listOf("Афиша", "Еда", "Ёлка"),
                names.sortedWith(LocalisedNames.byString())
            )
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun sortingByKeyUsesTheNameAndLeavesTheRestAlone() {
        data class Row(val title: String, val order: Int)
        val rows = listOf(Row("Яблоко", 3), Row("Афиша", 1), Row("Блокнот", 2))

        val sorted = rows.sortedWith(LocalisedNames.byKey(Locale("ru")) { it.title })

        assertEquals(listOf("Афиша", "Блокнот", "Яблоко"), sorted.map { it.title })
        assertEquals(
            "the sort reorders the rows and nothing else; a manual order is a different question",
            listOf(1, 2, 3), sorted.map { it.order }
        )
    }

    @Test
    fun anEmptyNameDoesNotBreakTheComparison() {
        val names = listOf("Б", "", "А")

        assertEquals(listOf("", "А", "Б"), names.sortedWith(LocalisedNames.byString(Locale("ru"))))
    }
}
