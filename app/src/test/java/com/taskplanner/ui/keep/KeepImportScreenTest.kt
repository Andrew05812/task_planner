package com.taskplanner.ui.keep

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.keep.KeepPreviewRow
import com.taskplanner.data.keep.KeepRowStatus
import com.taskplanner.databinding.FragmentKeepImportBinding
import com.taskplanner.databinding.FragmentSettingsBinding
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Keep import screen's own structure, measured rather than assumed.
 *
 * §17 wants the screen to hold a long paste, a four-level tree, and commands that reach at 320dp with the largest
 * text the app claims. The device matrix is where those get *seen*; what is proven here is the three things a
 * screenshot cannot establish on its own:
 *
 *  * **The way in exists in both languages.** The settings panel is the only route to this screen, so a reader
 *    whose language has no entry has no import. The label is compared against the resource the layout points at,
 *    never against an English literal typed into the test — the shape Stage 14 found passing while the Russian
 *    build showed something else.
 *  * **The field keeps leading whitespace.** In this format indentation is not decoration, it is the hierarchy: a
 *    widget that trimmed a pasted line's start would silently turn a reader's task into a topic, and no later
 *    layer could tell that the text had been changed on the way in.
 *  * **A fourth-level row still has a title column.** The indent is arithmetic this screen owns (a dimen times the
 *    depth), so the budget is measured at the narrowest legal width and the largest scale.
 */
@RunWith(RobolectricTestRunner::class)
class KeepImportScreenTest {

    /** A context that resolves `?attr/…` and the app's type styles the way a real screen does. */
    private fun themed(widthDp: Int = 360, fontScale: Float = 1.0f, language: String = "en"): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply {
            this.fontScale = fontScale
            setLocale(Locale(language))
            // The width a resource qualifier sees. Before this, `widthDp` only steered the measure specs and a
            // test that asked for 320dp still read the default screen's configuration — which is how a
            // width-dependent *layout decision* gets asserted without ever being exercised.
            screenWidthDp = widthDp
            smallestScreenWidthDp = widthDp
        }
        // ContextThemeWrapper does not override createConfigurationContext, so the theme goes on the configuration
        // context rather than wrapped before it — otherwise the inflated row resolves its attributes against the
        // manifest theme and measures a different screen.
        return androidx.appcompat.view.ContextThemeWrapper(
            app.createConfigurationContext(config), R.style.Theme_TaskPlanner
        )
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun width(context: Context, dp: Int) =
        View.MeasureSpec.makeMeasureSpec((dp * context.resources.displayMetrics.density).toInt(),
            View.MeasureSpec.EXACTLY)

    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

    // ---------------------------------------------------------------- the way in

    @Test
    fun theSettingsPanelOffersTheKeepImportEntryInBothLanguages() {
        listOf("en", "ru").forEach { language ->
            val context = themed(language = language)
            val panel = FragmentSettingsBinding.inflate(
                LayoutInflater.from(context), FrameLayout(context), false
            ).root.findViewById<Button>(R.id.keepImportButton)

            assertEquals("the entry is on the panel the reader opens, in $language", View.VISIBLE, panel.visibility)
            assertEquals("resource-driven copy in $language, not a literal this test could outgrow",
                context.getString(R.string.settings_keep_import_entry), panel.text.toString())
            panel.measure(unspecified(), unspecified())
            assertTrue(
                "and a 48dp touch row, whatever the label's length: $language measured " +
                    "${panel.measuredHeight}",
                panel.measuredHeight >= dp(context, 48)
            )
        }
    }

    @Test
    fun theRussianLabelIsNotTheEnglishOne() {
        val english = themed(language = "en").getString(R.string.settings_keep_import_entry)
        val russian = Locale("ru").let { locale ->
            val app = ApplicationProvider.getApplicationContext<Context>()
            val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
            app.createConfigurationContext(config).getString(R.string.settings_keep_import_entry)
        }

        assertNotEquals("the two builds must not answer with the same label", english, russian)
        assertTrue("the Russian entry still names the source it imports from", russian.contains("Google Keep"))
    }

    // ---------------------------------------------------------------- the screen

    @Test
    fun theImportScreenOpensOnTheFieldWithNoPreviewAndNoConfirm() {
        val context = themed()
        val binding = FragmentKeepImportBinding.inflate(LayoutInflater.from(context), FrameLayout(context), false)

        assertEquals("the paste box is what the reader is asked to fill", View.VISIBLE, binding.pasteInput.visibility)
        assertEquals("the tree is not drawn until something has been parsed", View.GONE, binding.previewList.visibility)
        assertEquals("and the confirm bar — with the Import button in it — waits for a preview",
            View.GONE, binding.confirmBar.visibility)
        // `TextInputLayout` puts its edit view inside an internal frame, so the parent is not the layout; the
        // pair the screen binds to is the layout and the field it owns, which is what makes the outlined box, the
        // label and the counter appear.
        assertTrue("the field sits in the app's own outlined text component",
            binding.pasteFieldLayout is com.google.android.material.textfield.TextInputLayout)
        assertEquals("and it is the field that component edits", binding.pasteInput,
            binding.pasteFieldLayout.editText)
        assertTrue("the preview is a RecyclerView, so a 382-row tree recycles rather than inflates",
            binding.previewList is RecyclerView)
    }

    @Test
    fun theCommandsReachAFingerInBothLanguages() {
        listOf("en", "ru").forEach { language ->
            val context = themed(language = language)
            val binding = FragmentKeepImportBinding.inflate(
                LayoutInflater.from(context), FrameLayout(context), false
            )
            listOf(binding.parseButton, binding.clearButton, binding.confirmButton).forEach { button ->
                button.measure(unspecified(), unspecified())
                assertTrue("$language command is at least 48dp tall: ${button.text} " +
                    "measured ${button.measuredHeight}", button.measuredHeight >= dp(context, 48))
            }
        }
    }

    @Test
    fun theFieldKeepsTheIndentationThatDecidesTheTree() {
        val context = themed()
        val binding = FragmentKeepImportBinding.inflate(LayoutInflater.from(context), FrameLayout(context), false)
        val pasted = "[ ] topic\r\n [ ] a task under it\r\n  [ ] and one under that"

        binding.pasteInput.setText(pasted)

        assertEquals("nothing trimmed, reordered or normalised on the way through the widget", pasted,
            binding.pasteInput.text?.toString())
        assertEquals("the lines that start with a space are the tree, so they have to survive the field",
            2, binding.pasteInput.text!!.toString().lines().count { it.startsWith(" ") })
    }

    /**
     * The paste field explains its format in the row Material owns, not at the position it writes at.
     *
     * The behaviour frame of the empty field photographed two sentences on one line: the layout's floating label
     * «Pasted text», drawn at the text position because the field is empty, and the edit text's own `android:hint` —
     * the long sentence about one note per line — drawn at the same place. Zoomed, the first grey line reads
     * «Paste here, one note per line…» with the dark label printed straight across «Paste here».
     *
     * What this test can pin, and what it cannot, are both measured. The collision is a *drawing* fact and Robolectric
     * does not reproduce it: read back here, `TextInputLayout` reports its own label as the edit view's hint —
     * `pasteInput.hint` is «Pasted text» whether or not the XML gives the child a hint — so an assertion about
     * `getHint()` passes against the broken layout and would prove nothing. What does discriminate is where the
     * guidance sentence lives: it is now the layout's helper text, which Material draws in its own row under the box,
     * readable while the field is empty and while it is full, and announced with the field. The frame remains the
     * proof of the drawing.
     */
    @Test
    fun thePasteFieldExplainsItsFormatBelowTheBoxRatherThanInsideIt() {
        val context = themed()
        val binding = FragmentKeepImportBinding.inflate(LayoutInflater.from(context), FrameLayout(context), false)

        assertEquals(
            "the format guidance is the layout's helper text, not a second text at the writing position",
            context.getString(R.string.keep_import_paste_placeholder),
            binding.pasteFieldLayout.helperText?.toString()
        )
    }

    /**
     * The row spends its width on the note's name before the control that opens it.
     *
     * Measured on the device at 320dp with the font at 1.3: with the title, the outcome caption, the hidden count
     * and a Russian toggle label all siblings of one horizontal row, the weighted title was left with nothing to
     * take and was measured at **0 px** — the row drew its checkbox, its two captions and its button, and no title
     * at all, while the binding still held «🟥Сегодня». A JVM assertion on `rowTitle.text` cannot see that and
     * neither can a Robolectric measure (LEGACY graphics answer `measureText` with a character count), so this
     * pins the *arrangement* the row depends on — the captions live under the title, in the column the row weights,
     * and only the topic's control is outside it — and the device run measures the width.
     */
    @Test
    fun theRowGivesItsWidthToTheTitleBeforeTheControlThatOpensIt() {
        val context = themed(widthDp = 320, fontScale = 1.3f)
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_keep_preview_row, FrameLayout(context), false)
        val column = row.findViewById<View>(R.id.rowTextColumn)
        val title = row.findViewById<TextView>(R.id.rowTitle)
        val status = row.findViewById<TextView>(R.id.rowStatus)
        val toggle = row.findViewById<View>(R.id.rowToggle)

        assertSame("the title is read inside the column the row weights", column, title.parent)
        assertNotSame(
            "the outcome caption is not a rival for the title's width — it sits under the title",
            row, status.parent
        )
        assertNotSame(
            "the topic's control is outside the column, which makes it the row's one fixed competitor",
            column, toggle.parent
        )
        val params = column.layoutParams as LinearLayout.LayoutParams
        assertTrue(
            "and the column is the weighted part of the row, so it is what gives way rather than the title inside " +
                "it vanishing",
            params.weight >= 1f
        )
    }

    /**
     * The confirm bar's two actions sit one above the other when the width cannot hold them side by side.
     *
     * Measured at 320dp with the font at 1.3 in Russian: «Импортировать» wrapped inside a half-width button and
     * its second line fell below the bar's own bottom edge — a primary action with a cut-off label, on the screen
     * whose whole job is to be pressed. The bar's shape is therefore chosen from the width rather than fixed in
     * XML, and the matrix's wide frames (side by side) and narrow ones (stacked) are the two halves of the proof.
     */
    @Test
    fun theConfirmBarStacksItsActionsWhenTheWidthCannotHoldThemSideBySide() {
        val narrow = themed(widthDp = 320, fontScale = 1.3f)
        val wide = themed(widthDp = 393)

        assertTrue("at 320dp the two Russian verbs cannot share a row",
            narrow.resources.getBoolean(R.bool.keep_confirm_actions_stacked))
        assertFalse("at 393dp they fit, and the bar keeps the shape the wide frames show",
            wide.resources.getBoolean(R.bool.keep_confirm_actions_stacked))

        val binding = FragmentKeepImportBinding.inflate(LayoutInflater.from(wide), FrameLayout(wide), false)

        KeepConfirmBar.shape(binding.confirmActions, binding.backToTextButton, binding.confirmButton, true)
        assertEquals("stacked", LinearLayout.VERTICAL, binding.confirmActions.orientation)
        assertEquals("every action gets the whole width, which is what stops the wrap",
            ViewGroup.LayoutParams.MATCH_PARENT,
            (binding.confirmButton.layoutParams as LinearLayout.LayoutParams).width)

        KeepConfirmBar.shape(binding.confirmActions, binding.backToTextButton, binding.confirmButton, false)
        assertEquals(LinearLayout.HORIZONTAL, binding.confirmActions.orientation)
        assertEquals("side by side they split it", 0,
            (binding.confirmButton.layoutParams as LinearLayout.LayoutParams).width)
        assertEquals(1f, (binding.confirmButton.layoutParams as LinearLayout.LayoutParams).weight)
    }

    // ---------------------------------------------------------------- the tree rows

    @Test
    fun aFourthLevelRowStillHasRoomForItsTitleAt320dp() {
        val context = themed(widthDp = 320, fontScale = 1.3f)
        val holder = bindOneRow(context, KeepPreviewRow(
            key = "0/0/0/0", parentKey = "0/0/0",
            title = "🟥Сегодня — 12.03.27 - 09:30 - Ул. Примера, д. 7", depth = 3, isTopic = false,
            checked = false, status = KeepRowStatus.WILL_WRITE, expanded = true, descendants = 0, line = 4
        ))
        val row = holder.itemView
        row.measure(width(context, 320), unspecified())
        row.layout(0, 0, dp(context, 320), row.measuredHeight)

        val title = row.findViewById<TextView>(R.id.rowTitle)
        val indent = row.findViewById<View>(R.id.rowIndent)
        assertEquals("and a row that will be written says so rather than leaving the reader to infer it",
            context.getString(R.string.keep_import_row_new),
            row.findViewById<android.widget.TextView>(R.id.rowStatus).text.toString())
        assertEquals("the fourth level is indented by three steps of the dimen",
            3 * context.resources.getDimensionPixelSize(R.dimen.keep_preview_indent), indent.width)
        assertTrue("the title keeps a column of its own at 320dp and 1.3: got ${title.width}px of " +
            "${dp(context, 320)}", title.width >= dp(context, 100))
        // What the screen promises about a title it cannot show at once: it is never cut. `maxLines` uncapped
        // and no ellipsis is the whole of that promise, and it is the part that can be proven here — whether the
        // row then wraps to three lines or two at this width is what the 320dp frames on the device are for.
        // The leftovers are handed to the column the title lives in, not to the title itself: a weighted view
        // inside a row whose other siblings are all fixed is what measured 0 px on the device at 1.3.
        assertEquals("the title's column is given the row's leftovers, not a fixed box",
            0, (row.findViewById<View>(R.id.rowTextColumn).layoutParams as ViewGroup.MarginLayoutParams).width)
        assertEquals("and the title takes the whole of that column",
            ViewGroup.LayoutParams.MATCH_PARENT, title.layoutParams.width)
        assertNull("no ellipsis on a reader's own note", title.ellipsize)
        assertTrue("and no line cap either: maxLines=${title.maxLines}", title.maxLines > 8)
    }

    @Test
    fun aCollapsedTopicSaysHowManyLinesItIsHiding() {
        val context = themed()
        val holder = bindOneRow(context, KeepPreviewRow(
            key = "0", parentKey = null, title = "Topic with a branch", depth = 0, isTopic = true,
            checked = true, status = KeepRowStatus.WILL_WRITE, expanded = false, descendants = 95, line = 1
        ))
        val row = holder.itemView.apply {
            measure(width(context, 360), unspecified())
            layout(0, 0, dp(context, 360), measuredHeight)
        }

        val hidden = row.findViewById<TextView>(R.id.rowHidden)
        assertEquals("a closed branch names its size", View.VISIBLE, hidden.visibility)
        assertEquals(context.getString(R.string.keep_import_hidden_rows, 95), hidden.text.toString())
        val toggle = row.findViewById<Button>(R.id.rowToggle)
        assertEquals("and the control says how to open it again",
            context.getString(R.string.keep_import_expand_topic), toggle.text.toString())
    }

    @Test
    fun aRowOfAnotherProfileIsNamedRatherThanSilentlySkipped() {
        val context = themed()
        val holder = bindOneRow(context, KeepPreviewRow(
            key = "0/1", parentKey = "0", title = "Already somebody's row", depth = 1, isTopic = false,
            checked = null, status = KeepRowStatus.CONFLICT, expanded = true, descendants = 0, line = 2
        ))
        val row = holder.itemView.apply {
            measure(width(context, 360), unspecified())
            layout(0, 0, dp(context, 360), measuredHeight)
        }

        assertEquals(context.getString(R.string.keep_import_row_conflict),
            row.findViewById<TextView>(R.id.rowStatus).text.toString())
        assertEquals("a line with no box is drawn as nothing said, not as done", "·",
            row.findViewById<TextView>(R.id.rowStateMark).text.toString())
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One row through the real adapter, on a real context.
     *
     * `submitList` diffs on a background thread and posts the result back to the main looper, so the row is not
     * there until the looper is pumped — a holder created before that would bind position 0 of an empty list.
     */
    private fun bindOneRow(context: Context, row: KeepPreviewRow): RecyclerView.ViewHolder {
        val adapter = KeepPreviewAdapter(
            indentPerLevel = context.resources.getDimensionPixelSize(R.dimen.keep_preview_indent),
            onToggle = {}
        )
        adapter.submitList(listOf(KeepPreviewEntry.Line(row)))
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.itemCount == 0) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
            if (System.currentTimeMillis() > deadline) error("the adapter never received its row")
        }
        val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder
    }

}
