package com.taskplanner.ui.tasks

import android.content.Context
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a Russian label fits the box it is drawn in, measured on the device that draws it.
 *
 * Stage 14's frame matrix showed two Russian-only failures in the task editor: the plan grid's time field
 * reading «Время окончан…» at 393dp/font 1.0 and at 320dp/1.3, and «СОХРАНИТЬ» breaking across two lines in
 * the sticky footer at 320dp/1.3 while English "SAVE" stayed on one. Both were found by looking at a
 * photograph, and a photograph is evidence but not a check — it cannot run again tomorrow.
 *
 * **Why this is an instrumented test and not a JVM one.** No Robolectric test in this repository can ask
 * whether a string fits its box: the shadow graphics return one pixel per character from `measureText` and
 * ignore `textSize`, which is how a Stage 8a test came to fail on a layout that was already correct. The
 * numbers below therefore come from the real font, the real density and the real locale of a running
 * device, and the driver re-runs this class at every width and font scale the matrix uses.
 *
 * **What "fits" is measured against.** The plan fields are tap-to-pick, not type-to-edit: they carry
 * `focusable="false"`, so an empty one shows its hint at the *expanded* size — the size of the text the
 * field would hold. That is the size the label must fit at, so the comparison is made with the edit view's
 * own text size and the horizontal room its own box actually leaves. The footer buttons are real
 * `TextView`s, so their line count is read from their rendered layout rather than computed.
 *
 * **Both languages, one configuration, one run.** English is asserted in the same breath as Russian on
 * purpose: a check that only ever fails in Russian cannot tell a localization defect from a broken
 * measurement, and the brief's criterion is "nothing truncated in Russian that is not truncated in
 * English". The measured numbers are printed for every field so a report can quote what the device said
 * rather than what the layout claims.
 */
@RunWith(AndroidJUnit4::class)
class TaskEditorRussianFitInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The editor is *not* opened here: choosing a language recreates the activity, and a recreation on a
        // detail screen comes back on that detail screen, which has no bottom bar and no FAB to reopen from.
        // Each language therefore opens its own editor, from the list, the way a reader does.
        settle(R.id.fabAddTask)
    }

    @After
    fun handTheLanguageBack() {
        // Unconditional, and before the language is touched: a test that failed inside a detail screen used to
        // leave the app *on* that screen, and the next test's locale change then recreated the destination it
        // was on — a detail screen, which has no bottom bar — so two unrelated tests reported "the Tasks list
        // never came back" for a screen they had never reached. The state a failed test leaves behind is
        // precisely the state its successor cannot be allowed to inherit.
        runCatching { Espresso.pressBack() }
        runCatching { goTasksList() }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        runCatching { scenario.close() }
    }

    @Test
    fun everyPlanLabelFitsItsBoxInBothLanguages() {
        for (tag in listOf("en-US", "ru")) {
            speak(tag)
            openEditorToThePlanGrid()
            for ((id, name) in PLAN_FIELDS) {
                val layout = findView(id.first) as com.google.android.material.textfield.TextInputLayout
                val edit = findView(id.second) as TextView
                val hint = layout.hint?.toString().orEmpty()
                val left = edit.left + edit.paddingStart + edit.compoundPaddingLeft
                val right = edit.right - edit.paddingEnd - edit.compoundPaddingRight
                val available = right - left
                // The hint, measured on the field's paint: an empty field's own `Layout` holds nothing at all,
                // so the label that is drawn here never passed through it (see [drawnText]).
                val needed = drawnWidth(hint, edit.paint)
                println(
                    "stage14 fit [$tag] $name: hint='$hint' " +
                        "needed=${needed.toInt()}px " +
                        "available=${available.toInt()}px textSize=${edit.textSize} boxWidth=${layout.width}"
                )
                assertTrue(
                    "[$tag] the label '$hint' needs ${needed.toInt()}px and its box leaves " +
                        "${available.toInt()}px — it is drawn truncated. Width ${widthDp()}dp, " +
                        "font ${fontScale()}",
                    needed <= available
                )
            }
            leaveEditor()
        }
    }

    /**
     * The footer's two buttons, on one line each, at no less than 48dp tall.
     *
     * `lineCount` is read from the rendered layout, which is the only place the answer exists: the Russian
     * word is longer than its English one, and a button that wraps mid-word («СОХРАН / ИТЬ») is not a
     * truncated button — it passes every ellipsis check and still reads as a typographical error.
     */
    @Test
    fun theFooterButtonsHoldTheirLabelsOnOneLineInBothLanguages() {
        for (tag in listOf("en-US", "ru")) {
            speak(tag)
            openEditorToThePlanGrid()
            for ((id, name) in FOOTER_BUTTONS) assertOneLineLabel(id, tag, name)
            leaveEditor()
        }
    }

    /**
     * The same two Russian labels on the other screen that puts them side by side in one row.
     *
     * Found while measuring the task footer: the category editor pairs «Сохранить» with «Отмена» as two
     * evenly-chained halves of one row, with nothing else competing for the width. A fix aimed at the task
     * editor alone would leave the same mid-word break one tab away, and the reviewer of a screenshot of
     * *that* screen would file it as a new defect. So the row that is not the task footer is measured here
     * too, and what it says is recorded whether or not the number is comfortable.
     */
    @Test
    fun theCategoryEditorHoldsTheSameTwoLabelsInOneRow() {
        for (tag in listOf("en-US", "ru")) {
            speak(tag)
            openCategoryEditor()
            for ((id, name) in CATEGORY_BUTTONS) assertOneLineLabel(id, tag, name)
            Espresso.pressBack()          // the category editor, back to the Categories list
            goTasksList()                 // `speak` returns to the Tasks list, which is what the next run needs
        }
    }

    // ---------------------------------------------------------------- the helpers

    /**
     * One button, one line, and the whole arithmetic of why.
     *
     * The box is not the room the label gets: a Material button spends part of its width on horizontal
     * padding, and the first version of this check compared the label against the *box*, so it could have
     * passed a button that had already wrapped. Nothing about the decoration is assumed — the padding is read
     * off the view, because a first attempt that reconstructed it from Material's dimens ("24dp each side,
     * plus the 6dp inset") predicted a deficit on a button that had drawn its label comfortably. Every term
     * is printed, including the width of the row the button sits in, because the fix for a label that does not
     * fit is chosen from those numbers and a failure message that only says "it wrapped" leaves the next
     * reader guessing.
     */
    private fun assertOneLineLabel(id: Int, tag: String, name: String) {
        val button = findView(id) as TextView
        val minimum = 48f * context.resources.displayMetrics.density
        val content = button.width - button.paddingStart - button.paddingEnd
        val label = drawnText(button)
        val needed = drawnWidth(label, button.paint)
        val row = (button.parent as? View)?.width ?: -1
        // The measurement is only worth what its input is worth. A button that has not rendered has no Layout,
        // and the fallback in `drawnText` would then hand back the raw word — which is exactly the wrong
        // string, and would have made this file pass the defect it was written to catch.
        assertTrue("[$tag] '$name' has no rendered Layout to read its drawn label from", button.layout != null)
        assertTrue(
            "[$tag] '$name' draws '$label' from the text '${button.text}': the drawn form is what has to fit, " +
                "and a measurement of the wrong string is worse than no measurement.",
            label.equals(button.text.toString(), ignoreCase = true)
        )
        println(
            "stage14 label [$tag] $name: drawn='$label' lines=${button.lineCount} " +
                "needed=${needed.toInt()}px box=${button.width}px " +
                "pad=${button.paddingStart}/${button.paddingEnd} content=${content}px " +
                "row=${row}px height=${button.height}px min=${minimum.toInt()}px"
        )
        assertTrue(
            "[$tag] '$name' is drawn on ${button.lineCount} lines — at ${widthDp()}dp with the font at " +
                "${fontScale()} its label breaks mid-word. The drawn form is '$label': it needs " +
                "${needed.toInt()}px, and of the ${button.width}px box the ${button.paddingStart}+" +
                "${button.paddingEnd} padding leaves ${content}px for the word.",
            button.lineCount == 1
        )
        assertTrue(
            "[$tag] '$name' is ${button.height}px tall, below the ${minimum.toInt()}px touch target",
            button.height >= minimum
        )
    }

    /**
     * The width the layout will actually give this text, which is not the width the glyphs have.
     *
     * Two things stand between the two. `Paint.measureText` ignores `letterSpacing`, and the `Label`
     * appearance carries 0.045em of it — about 1.5px per character gap at the widest configuration. And a
     * button does not draw its `text`, it draws the *transformed* text: `Label` is all-caps, so the Russian
     * «Сохранить» on the layout file becomes «СОХРАНИТЬ» on the screen, and capital Russian letters are
     * measurably wider than the lowercase word they come from. Both were invisible to the first version of
     * this file, which measured the raw word and so reported "130px needed, 147px available" for a button it
     * had just watched wrap — a green-looking number attached to a broken screen, which is the exact failure
     * mode Stage 14 exists to close.
     */
    private fun drawnWidth(text: String, paint: Paint): Float {
        val spacing = (paint as? TextPaint)?.letterSpacing?.coerceAtLeast(0f)?.times(paint.textSize) ?: 0f
        return paint.measureText(text) + spacing * (text.length - 1).coerceAtLeast(0)
    }

    /**
     * What a rendered `TextView` holds, as opposed to what the layout file asked it to hold.
     *
     * Read from the view's own `Layout` rather than by upper-casing here: the transformation belongs to the
     * style, so if the design system stops setting `textAllCaps` this measurement follows it instead of
     * asserting the old behaviour. Only valid for a view that has *rendered* text — a field sitting empty has
     * an empty layout and its label is a hint the layout never held, which is why the plan fields above
     * measure their hint instead and why this function is not used for them.
     */
    private fun drawnText(textView: TextView): String =
        textView.layout?.text?.toString() ?: textView.text.toString()

    /**
     * The language the reader would have chosen, applied the way the app applies it.
     *
     * Always from the Tasks list, never from a detail screen: a locale change recreates the activity and the
     * recreation restores the destination the reader was on, so a detail screen comes back a detail screen —
     * with no bottom bar and no FAB in it to navigate out of. That is not a theory; it is §6's finding, and
     * this class met it as a failure on its own first run.
     */
    private fun speak(tag: String) {
        goTasksList()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        assertTrue(
            "the Tasks list did not come back in '$tag'",
            waitUntil { findView(R.id.fabAddTask)?.isShown == true }
        )
        // A recreated window draws before it takes focus, and a tap delivered to a window that does not hold
        // focus is dropped rather than queued. Espresso waits for the main thread to idle, not for the activity
        // transition to end, so every failure this line prevents arrives as "the Add button was pressed and the
        // app stayed on the Tasks list" — measured twice on this emulator, once at each configuration, and never
        // with the editor actually refusing to open. `Stage14LanguageFramesInstrumentedTest` documents the same
        // trap for the presses it makes, and names the run `ENVIRONMENT` when the machine will not give focus at
        // all; this class asks for the same patience and says the same thing when patience does not work.
        assertTrue(
            "ENVIRONMENT, not a screen defect: the Tasks screen drew in '$tag' but its window never took " +
                "focus, so nothing pressed after here would reach the app.",
            waitUntil { windowHasFocus() }
        )
    }

    private fun windowHasFocus(): Boolean {
        var focused = false
        runCatching {
            scenario.onActivity { activity -> focused = activity.window.decorView.hasWindowFocus() }
        }
        return focused
    }

    /** The tab that has a bottom bar in it, so a later recreate always has a screen to come back to. */
    private fun goTasksList() {
        if (findView(R.id.fabAddTask)?.isShown == true) return
        runCatching { Espresso.onView(withId(R.id.navigation_tasks)).perform(click()) }
        waitUntil { findView(R.id.fabAddTask)?.isShown == true }
    }

    /** Open a fresh category, which is the other screen that puts these two labels in one shared row. */
    private fun openCategoryEditor() {
        Espresso.onView(withId(R.id.navigation_categories)).perform(click())
        settle(R.id.fabAddCategory)
        Espresso.onView(withId(R.id.fabAddCategory)).perform(click())
        Espresso.onView(withId(R.id.saveButton)).perform(scrollTo())
        settle(R.id.buttonCancel)
    }

    /** Open a fresh task and bring the plan grid on screen; at 320dp/1.3 it is below the fold. */
    private fun openEditorToThePlanGrid() {
        onView(withId(R.id.fabAddTask)).perform(click())
        if (!waitUntil { findView(R.id.task_title)?.isShown == true }) {
            // Say which screen the app is actually on. The same failure at 393dp said "task_title never drew"
            // and left two readings open — the editor never opened, or it opened and the field is not drawn —
            // and only the first was true. A second that names its cause is what tells the next reader whether
            // to look at the app or at the machine.
            val stillOnTheList = findView(R.id.fabAddTask)?.isShown == true
            assertTrue(
                if (stillOnTheList) {
                    "the Add button was pressed and the app stayed on the Tasks list — the editor did not " +
                        "open at all (fab still shown)"
                } else {
                    "the editor opened (the list is gone) but its title field never drew"
                },
                false
            )
        }
        onView(withId(R.id.plan_start_date_layout)).perform(
            androidx.test.espresso.action.ViewActions.scrollTo()
        )
        settle(R.id.plan_end_time_layout)
    }

    /**
     * Leave the editor the way a reader does.
     *
     * Called *after* the measurements of one language, not inside the opener: the views being measured have
     * to still be attached when the loop reads them, and a back press taken too early turns the assertion
     * into a null cast rather than into a finding.
     */
    private fun leaveEditor() {
        runCatching { androidx.test.espresso.Espresso.pressBack() }
        waitUntil { findView(R.id.fabAddTask)?.isShown == true }
    }

    private fun findView(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun settle(id: Int) {
        assertTrue(
            "${context.resources.getResourceEntryName(id)} never drew",
            waitUntil { findView(id)?.isShown == true }
        )
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun widthDp(): Int =
        (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()

    private fun fontScale(): Float =
        context.resources.configuration.fontScale

    private companion object {
        /**
         * How long a screen is allowed to take coming back.
         *
         * 20s was the budget when this class was written, and it expired once on a 393dp run while the two
         * language switches of an earlier test were still settling on the emulator — the app had not failed, it
         * had been re-created twice in as many seconds. A locale change restarts the whole activity with a
         * rebuilt resource table, so this is the slowest transition the app makes, and a wait that only holds
         * when the machine is idle reports an environment as a defect. Raised to 45s, which is longer than any
         * single measured transition here and far shorter than the 20s the class would take to fail for real.
         */
        const val WAIT_MILLIS = 45_000L

        /** layout id, edit id, and the name a failure should call it by. */
        val PLAN_FIELDS = listOf(
            Pair(R.id.plan_start_date_layout, R.id.plan_start_date) to "start date",
            Pair(R.id.plan_start_time_layout, R.id.plan_start_time) to "start time",
            Pair(R.id.plan_end_date_layout, R.id.plan_end_date) to "end date",
            Pair(R.id.plan_end_time_layout, R.id.plan_end_time) to "end time"
        )

        val FOOTER_BUTTONS = listOf(
            R.id.cancelButton to "cancel",
            R.id.saveTask to "save"
        )

        /** The category editor's own pair: two labels, one row, no spinner competing for the width. */
        val CATEGORY_BUTTONS = listOf(
            R.id.saveButton to "category save",
            R.id.buttonCancel to "category cancel"
        )
    }
}
