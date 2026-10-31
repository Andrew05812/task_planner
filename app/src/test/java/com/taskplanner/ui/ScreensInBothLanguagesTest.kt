package com.taskplanner.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.databinding.FragmentTaskDetailBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The same screens, inflated twice, in the two languages the app offers.
 *
 * Stage 14's third defect was not that the app said the wrong thing in Russian — it was that the tests could
 * only ask in English. `EnglishLiteralAssertionTest` keeps the literals out of the UI tests; this class is the
 * positive half of the same rule, because a sweep that forbids the wrong shape says nothing about whether the
 * right one works: it proves no label ever actually arrived from `values-ru`.
 *
 * **What each half claims, and why both are needed.** The first asserts, for a set of named controls, that the
 * text on the view is the text of its resource *in the language currently configured*, and that Russian is not
 * English — a resource wired to the wrong file, or a label hardcoded in Kotlin, fails one of those two and only
 * one of them. The second walks the same controls after switching the qualifier mid-test, which is what the app
 * does to itself when the reader chooses a language: the resources follow, without a rebuild.
 *
 * **Why this file names English words out loud.** Its subject is the language, which is the exemption
 * `EnglishLiteralAssertionTest` already carries for `LanguageSwitchInstrumentedTest` and the locale tests. A
 * test that asks "is this the English string?" is not the defect that gate exists to stop; a *card* test that
 * asks it is.
 */
@RunWith(RobolectricTestRunner::class)
class ScreensInBothLanguagesTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The controls a reader meets first, each named by the resource it is supposed to be drawn from. */
    private val labels = listOf(
        R.string.title_tasks to "the Tasks tab",
        R.string.title_calendar to "the Calendar tab",
        R.string.title_categories to "the Categories tab",
        R.string.title_statistics to "the Statistics tab",
        R.string.title_profile to "the Profile tab",
        R.string.action_save to "the editor's primary action",
        R.string.action_cancel to "the editor's secondary action",
        R.string.action_delete to "the destructive action"
    )

    @Test
    fun everyLabelComesFromItsResourceInBothLanguages() {
        for (qualifier in listOf("en-rUS", "ru-rRU")) {
            RuntimeEnvironment.setQualifiers(qualifier)
            val english = readLabels("en-rUS")
            val spoken = readLabels(qualifier)

            spoken.forEach { (res, text) ->
                assertEquals(
                    "[$qualifier] ${describe(res)} is not the text of its own resource: " +
                        "the view says \"$text\", the resource says \"${context.getString(res)}\"",
                    context.getString(res),
                    text
                )
            }

            if (qualifier == "ru-rRU") {
                // Every one of these has a Russian form — Stage 14.3 translated the whole set — so an answer
                // still in English is a label that never reached the translation, whatever the test says.
                val unchanged = spoken.filter { (res, text) -> text == english[res] }.keys
                assertTrue(
                    "Russian build still draws these from English: " +
                        unchanged.joinToString { describe(it) },
                    unchanged.isEmpty()
                )
            }
        }
    }

    /**
     * The editor's footer, inflated the way the fragment inflates it, in both languages.
     *
     * The strings above could be read straight off the resources and the test would prove nothing about the
     * screen; this reads the buttons a layout actually produced, so an `android:text` replaced by a literal in
     * the layout file is caught here. The theme wrapper is not ceremony: the fragment always sits inside the
     * activity's theme, and Material's buttons refuse to inflate without it.
     */
    @Test
    fun theEditorsFooterIsDrawnFromResourcesInBothLanguages() {
        val seen = HashMap<Int, MutableSet<String>>()
        for (qualifier in listOf("en-rUS", "ru-rRU")) {
            RuntimeEnvironment.setQualifiers(qualifier)
            val themed = ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
            val binding = FragmentTaskDetailBinding.inflate(LayoutInflater.from(themed))
            for ((id, button) in listOf(
                R.id.saveTask to binding.saveTask,
                R.id.cancelButton to binding.cancelButton
            )) {
                val text = button.text.toString()
                assertEquals(
                    "[$qualifier] the footer's ${context.resources.getResourceEntryName(id)} is not drawn " +
                        "from its resource",
                    themed.getString(
                        if (id == R.id.saveTask) R.string.action_save else R.string.action_cancel
                    ),
                    text
                )
                seen.getOrPut(id) { mutableSetOf() } += text
            }
        }
        for (id in listOf(R.id.saveTask, R.id.cancelButton)) {
            val forms = seen.getValue(id)
            assertEquals(
                "${context.resources.getResourceEntryName(id)} drew the same words in both languages: $forms",
                2,
                forms.size
            )
        }
    }

    // ---------------------------------------------------------------- the reading

    private fun readLabels(qualifier: String): Map<Int, String> {
        RuntimeEnvironment.setQualifiers(qualifier)
        return labels.map { (res, _) -> res to context.getString(res) }.toMap()
    }

    private fun describe(res: Int): String =
        labels.first { it.first == res }.second + " (" +
            context.resources.getResourceEntryName(res) + ")"
}
