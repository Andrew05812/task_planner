package com.taskplanner.ui.settings

import androidx.core.os.LocaleListCompat
import com.taskplanner.res.ResourceFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the three choices on the Settings screen mean, before anything touches AppCompat.
 *
 * The mapping is the whole contract of the feature, and it is small enough to be read here: an empty app locale
 * list is "ask the system", `"en"` and `"ru"` are the two languages the app is actually translated into, and
 * nothing else is reachable. The reason to write it as a pure function of a tag string, rather than as a call
 * into `AppCompatDelegate`, is that the delegate needs a live Activity to do anything visible — so a rule kept
 * inside it can only be observed on a device, and a device cannot tell whether "a locale the app never offered"
 * was meant to fall back or was a typo.
 *
 * The fallback is a decision, not an accident. Android 13 lets a reader set a per-app language from system
 * settings for any language the device knows; if that value is neither of the two this app carries, the only
 * honest reading is "not one of ours", which is the system default. Silently treating `"de"` as English would
 * show a Settings row that disagrees with the system screen.
 */
class AppLanguageTest {

    @Test
    fun `the three choices are three choices`() {
        assertEquals(
            "the row offers exactly System default, English and Русский",
            listOf(AppLanguage.SystemDefault, AppLanguage.English, AppLanguage.Russian),
            AppLanguage.entries.toList()
        )
        val labels = AppLanguage.entries.map { it.labelRes }
        assertEquals(
            "no two choices may share a label — a row that reads the same as its neighbour is not a choice",
            labels.size,
            labels.distinct().size
        )
    }

    @Test
    fun `system default asks for no locale at all`() {
        // An empty list, not "en" and not "ru": forcing the device's own language would freeze the app into
        // whatever it resolved at the moment of the choice, and a reader who changes the system language would
        // find the app refusing to follow.
        assertEquals("", AppLanguage.SystemDefault.languageTags)
        assertEquals(AppLanguage.SystemDefault, AppLanguage.of(null))
        assertEquals(AppLanguage.SystemDefault, AppLanguage.of(""))
        assertEquals(AppLanguage.SystemDefault, AppLanguage.of(LocaleListCompat.getEmptyLocaleList()))
    }

    @Test
    fun `each named language carries the tag the resource directory is named for`() {
        // `values-ru` is resolved by the tag "ru". A tag of "ru-RU" would resolve too, but a reader on the
        // system's App Languages screen picks the entry the manifest advertises, and the two have to agree.
        assertEquals("ru", AppLanguage.Russian.languageTags)
        assertEquals("en", AppLanguage.English.languageTags)
        assertEquals(AppLanguage.Russian, AppLanguage.of("ru"))
        assertEquals(AppLanguage.English, AppLanguage.of("en"))
        assertEquals(
            AppLanguage.Russian,
            AppLanguage.of(LocaleListCompat.forLanguageTags("ru"))
        )
    }

    @Test
    fun `a locale the app does not carry reads as the system default`() {
        assertEquals(AppLanguage.SystemDefault, AppLanguage.of("de"))
        assertEquals(AppLanguage.SystemDefault, AppLanguage.of("de,fr"))
        assertEquals(
            "a tag with a region still names a language the app has no strings for",
            AppLanguage.SystemDefault, AppLanguage.of("es-419")
        )
        assertNotEquals(
            "an unknown language must not be shown as English just because English is the default resource set",
            AppLanguage.English,
            AppLanguage.of("de")
        )
    }

    @Test
    fun `a region-qualified choice still resolves to its language`() {
        assertEquals(AppLanguage.Russian, AppLanguage.of("ru-RU"))
        assertEquals(AppLanguage.English, AppLanguage.of("en-US"))
    }

    /**
     * The two language names are the same words in both interfaces, because each is written in the script a
     * reader of that language recognises: «Русский» is how a Russian reader finds Russian, and "English" is how
     * an English reader finds English. The system-default row is the one that has to be translated.
     *
     * Read from the resource files rather than from the enum, because the claim is about what a reader sees:
     * an untranslated name must be marked so in the default set and must have no Russian counterpart, and the
     * row that *is* translated must have one. A test that compared a rendered label to the English word would
     * be the exact mistake this stage is removing everywhere else.
     */
    @Test
    fun `the language names are untranslated and the system row is translated`() {
        val english = ResourceFiles.defaults()
        val russian = ResourceFiles.russian()

        assertEquals("English", english.entries.getValue("language_english").value!!.text)
        assertEquals("Русский", english.entries.getValue("language_russian").value!!.text)
        assertTrue(
            "the two names must be marked translatable=\"false\", or the completeness gate will demand a " +
                "Russian version of the word \"English\"",
            listOf("language_english", "language_russian").all {
                english.entries.getValue(it).value!!.translatable.not()
            }
        )
        assertTrue(
            "and neither may exist in the Russian set",
            listOf("language_english", "language_russian").none { russian.entries.containsKey(it) }
        )

        assertTrue(
            "the system-default row is a sentence the reader has to understand in their own language, so it " +
                "must carry a Russian counterpart",
            russian.entries.containsKey("language_system_default")
        )
        assertNotEquals(
            "and that counterpart must not be the English words",
            english.entries.getValue("language_system_default").value!!.text,
            russian.entries.getValue("language_system_default").value!!.text
        )
    }
}
