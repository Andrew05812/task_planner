package com.taskplanner.ui.settings

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.taskplanner.R
import com.taskplanner.databinding.DialogProfileEditBinding
import com.taskplanner.databinding.FragmentSettingsBinding
import com.taskplanner.ui.Stage8aLayoutHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The five controls Settings is known by, and the shape of the screen that carries them.
 *
 * `SettingsFragment` binds `exportButton`, `importButton`, `syncNowButton`, `signInButton`, `signOutButton`,
 * `migrateLocalDataButton`, `cloudNoticeText`, `versionText` and the local-profile dropdown by id, and it
 * switches four of them between visible and gone from one state object. A restyle that renames a control,
 * turns a button into a text row, or moves a control inside a container the fragment no longer reaches is
 * not a visual change — it is a broken screen that still inflates. So this file asks the inflated tree the
 * questions the fragment depends on, and asks the state machine's rule directly where a screenshot cannot.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLayoutTest {

    private fun themed() = Stage8aLayoutHarness.themed()

    private fun screen(): FragmentSettingsBinding =
        FragmentSettingsBinding.inflate(Stage8aLayoutHarness.inflater())

    @Test
    fun `the five controls the screen is known by are still the kind of thing the fragment binds`() {
        val binding = screen()
        val density = themed().resources.displayMetrics.density

        for ((button, name) in listOf(
            binding.exportButton to "export",
            binding.importButton to "import",
            binding.signInButton to "sign in",
            binding.signOutButton to "sign out",
            binding.syncNowButton to "sync now"
        )) {
            assertSame("$name has to stay a MaterialButton — the fragment enables, disables and re-labels it",
                MaterialButton::class.java, button.javaClass)
            // `signOutButton` is `gone` in the layout because the fragment shows it only to a signed-in
            // reader, and a gone view measures nothing. Laid out in the state where it is used is the only
            // honest way to ask how big its target is.
            button.visibility = View.VISIBLE
            Stage8aLayoutHarness.laidOut(button)
            assertTrue(
                "the $name control measures ${button.height / density}dp tall when it is on screen; a command " +
                    "the finger aims at is 48dp",
                button.isClickable && button.height / density >= 47.5f
            )
        }

        assertTrue("the version is information, not a control",
            binding.versionText is TextView && !binding.versionText.isClickable)
    }

    @Test
    fun `the sign-in command is the Google command, with the Google mark`() {
        val binding = screen()
        assertEquals("the sign-in button has to say what it does",
            themed().getString(R.string.sign_in_google), binding.signInButton.text.toString())
        assertTrue(
            "the sign-in button lost its Google mark, so it reads as an ordinary command in the same shape " +
                "as the others",
            binding.signInButton.icon != null
        )
    }

    /**
     * The account section carries the three things a reader needs to know it is in state A: the state named,
     * the button present, and the reason beside it.
     *
     * `SettingsFragment.render()` used to answer an unconfigured build by hiding `signInButton` and showing one
     * notice at the foot of the screen. Stage 13 moved that decision into `AccountUiState` and gave the card
     * three views, so the layout has to hold all three before the fragment can show any of them — and the
     * layout must not guess the build's state, which is why every one of them starts hidden or empty here and
     * the fragment is the only thing that decides what a reader sees.
     */
    @Test
    fun `the screen carries the notice that explains an unconfigured cloud`() {
        val binding = screen()
        assertEquals(
            "the notice is what a reader gets when the build has no Firebase configuration",
            themed().getString(R.string.app_locked_message), binding.cloudNoticeText.text.toString()
        )
        assertTrue("and the layout must not guess the build's state: it starts hidden",
            !binding.cloudNoticeText.isVisible)
        assertTrue(
            "the state line is empty in the layout: the fragment decides which of the four states this is, " +
                "and a layout that guessed one would make the decision untestable",
            binding.accountStateText.text.isEmpty()
        )
        assertEquals(
            "and the explanation beside the disabled button is the sentence the app writes",
            themed().getString(R.string.sign_in_not_configured_explanation),
            binding.signInExplanation.text.toString()
        )
        assertEquals("the sign-in button is in the layout, not added only when a cloud exists",
            View.VISIBLE, binding.signInButton.visibility)
    }

    @Test
    fun `the screen names itself above the sections`() {
        val binding = screen()
        // The content column, not the scroll view: a scroll view hands its child an unbounded height and
        // Robolectric never lays that child out, so measuring the outer view would compare two zeros and
        // call it a layout finding.
        val content = binding.root.getChildAt(0) as View
        Stage8aLayoutHarness.screenOnScreen(content)
        val header = content.findViewById<TextView>(R.id.settingsTitleText)
            ?: throw AssertionError(
                "Settings has no title of its own, while Tasks, Profile and the editor all carry one"
            )
        assertEquals(themed().getString(R.string.title_settings), header.text.toString())
        val firstSection = Stage8aLayoutHarness.allViews(binding.root)
            .filterIsInstance<TextView>()
            .first { it.text.toString() == themed().getString(R.string.settings_account) }
        assertTrue(
            "the screen's name has to come before the first section it names: title at " +
                "y=${Stage8aLayoutHarness.absoluteTop(header)}, first section heading at " +
                "y=${Stage8aLayoutHarness.absoluteTop(firstSection)} (content column measured at " +
                "${content.measuredHeight}px, title ${header.height}px tall)",
            Stage8aLayoutHarness.absoluteTop(header) <
                Stage8aLayoutHarness.absoluteTop(firstSection)
        )
    }

    @Test
    fun `the sections are named by one role rather than by hand-bolded body text`() {
        val binding = screen()
        val titles = Stage8aLayoutHarness.allViews(binding.root)
            .filterIsInstance<TextView>()
            .filter { view ->
                view.text.toString() == themed().getString(R.string.settings_account) ||
                    view.text.toString() == themed().getString(R.string.settings_sync) ||
                    view.text.toString() == themed().getString(R.string.settings_backup)
            }
        assertEquals("every section of Settings has to be named by a title the reader can see", 3, titles.size)
        val sizes = titles.map { it.paint.textSize }.distinct()
        assertEquals(
            "the three section titles draw at ${sizes.size} different sizes — one screen, three hierarchies: " +
                titles.joinToString { "'${it.text}' ${it.paint.textSize}px" },
            1, sizes.size
        )
        val body = titles.first()
        assertTrue(
            "a section title has to be distinguishable from the body it heads: title ${body.paint.textSize}px " +
                "against the status line ${binding.accountStatusText.paint.textSize}px",
            body.paint.textSize > binding.accountStatusText.paint.textSize
        )
    }

    @Test
    fun `the profile dialog asks for a name and an address in fields the code reads back`() {
        val form = DialogProfileEditBinding.inflate(Stage8aLayoutHarness.inflater())
        val context = themed()
        assertEquals(context.getString(R.string.hint_name), form.nameInput.hint.toString())
        assertEquals(context.getString(R.string.hint_email), form.emailInput.hint.toString())
        assertTrue("a name field that accepts anything is a name field the validator has to fight",
            form.nameInput.maxLines == 1)
    }
}
