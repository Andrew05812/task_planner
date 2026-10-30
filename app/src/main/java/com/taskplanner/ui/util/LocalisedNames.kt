package com.taskplanner.ui.util

import com.taskplanner.ui.settings.AppLocale
import java.text.Collator
import java.util.Locale

/**
 * Ordering that answers to the reader's alphabet rather than to Unicode's numbering.
 *
 * Three screens put names in order — the Tasks list's "by name", the calendar's legend, and the Categories
 * list — and all three compared strings directly before Stage 14. A comparison of strings is a comparison of
 * UTF-16 units, which for Russian is not an alphabet: `ё` (U+0451) sits below `а` (U+0430), so "Ёлка" filed
 * itself at the top of a list, and every capital letter sits below every small one, so "Яблоко" came before
 * "апельсин". A reader who presses "По имени" and gets those orders has no way to tell a working sort from a
 * broken button.
 *
 * A `Collator` is what `java.text` offers for the question, and it is asked here for the *app's* locale —
 * `Locale.getDefault()`, which Stage 14 measured to follow the language chosen in Settings, so this is the
 * reader's alphabet and not the handset's.
 *
 * **Why the collator is made per call rather than kept.** A language change is a thing a reader can do while
 * the app runs, and a collator resolved once belongs to the language that was current at that moment — the
 * same lifetime mistake as a `SimpleDateFormat` in a companion object, which `LocaleStateSourceTest` refuses.
 * `Collator.getInstance` is cheap against the lists this app holds, which are a reader's own categories and
 * tasks, not a table of a million rows.
 *
 * **Why the DAO is left alone.** `ORDER BY name ASC` stays binary collation, and that is not an oversight: the
 * stored order is what the sync engine and the export walk cheaply, and a locale does not belong in a database
 * row. The reader is shown an alphabet because the *screen* is the layer that knows the language.
 */
object LocalisedNames {

    /**
     * A comparator that orders displayed names as [locale] reads its alphabet.
     *
     * `SECONDARY` is the strength a list wants: it compares `ё` after `е` — the letters a reader looks for
     * together — while treating a capital and a small letter as the same letter, so "хлеб" and "Хлебный" stay
     * adjacent instead of being split across the alphabet by their case. The default argument is read at call
     * time, which is the whole point of the object existing in this shape.
     */
    fun byString(locale: Locale = AppLocale.current()): Comparator<String> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return Comparator { first, second -> collator.compare(first, second) }
    }

    /**
     * The same comparison applied to the name a row carries, leaving every other field alone.
     *
     * A manual order is a different question and this does not touch it: the caller picks which rows it hands
     * over, and reordering them by name changes no stored value.
     */
    fun <T> byKey(locale: Locale = AppLocale.current(), key: (T) -> String): Comparator<T> {
        val byName = byString(locale)
        return Comparator { first, second -> byName.compare(key(first), key(second)) }
    }
}
