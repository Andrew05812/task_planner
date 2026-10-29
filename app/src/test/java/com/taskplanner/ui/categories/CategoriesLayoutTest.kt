package com.taskplanner.ui.categories

import android.widget.TextView
import androidx.core.view.isVisible
import com.taskplanner.R
import com.taskplanner.databinding.FragmentCategoriesBinding
import com.taskplanner.databinding.FragmentCategoryDetailBinding
import com.taskplanner.databinding.ItemCategoryBinding
import com.taskplanner.ui.Stage8aLayoutHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The category list and its editor, measured at the widths the app declares.
 *
 * Categories is the one tab that never got a header, so a reader who taps the third icon in the bar lands on
 * a screen that does not say what it is. That is the kind of thing a restyle is supposed to notice, and it is
 * also the kind of thing that silently costs a row its count when the title is added, so the row is measured
 * with a name long enough to fight for room.
 */
@RunWith(RobolectricTestRunner::class)
class CategoriesLayoutTest {

    private fun themed() = Stage8aLayoutHarness.themed()

    private fun screen(): FragmentCategoriesBinding =
        FragmentCategoriesBinding.inflate(Stage8aLayoutHarness.inflater())

    private fun row(): ItemCategoryBinding = ItemCategoryBinding.inflate(Stage8aLayoutHarness.inflater())

    @Test
    fun `the screen says which tab it is`() {
        val binding = screen()
        Stage8aLayoutHarness.laidOut(binding.root)
        val header = binding.root.findViewById<TextView>(R.id.categoriesTitleText)
            ?: throw AssertionError(
                "the categories screen carries no title: the bar is the only thing that tells a reader where " +
                    "they are, and Tasks and Profile both name themselves"
            )
        assertEquals(themed().getString(R.string.title_categories), header.text.toString())
        // The height is asked first because two views that both reported y=0 satisfy any ordering claim: the
        // harness measures with an unspecified height, and Stage 8a already had one test that passed on a
        // screen that had not been laid out at all.
        assertTrue("the title measured nothing, so it was never laid out", header.height > 0)
        assertTrue("the title has to sit above the list it belongs to, not under it",
            header.bottom <= binding.recyclerViewCategories.top + 1)
    }

    @Test
    fun `the list, the spinner and the empty state are siblings the fragment can switch between`() {
        val binding = screen()
        val container = binding.recyclerViewCategories.parent
        org.junit.Assert.assertSame(
            "the spinner has to be switched against the same container as the list, or one state leaves two " +
                "views fighting for the same space",
            container, binding.progressBar.parent
        )
        org.junit.Assert.assertSame(container, binding.emptyView.parent)
        assertEquals("the empty state is shown by the fragment, not by the layout",
            false, binding.emptyView.isVisible)
        assertEquals("the spinner is shown by the fragment too", false, binding.progressBar.isVisible)
    }

    @Test
    fun `a category row is a target a finger can hit`() {
        val binding = row()
        binding.categoryName.text = "Work"
        binding.taskCount.text = "12"
        Stage8aLayoutHarness.laidOut(binding.root)
        val density = themed().resources.displayMetrics.density
        val heightDp = binding.root.height / density
        assertTrue(
            "the row measures ${heightDp}dp tall; a list row the finger aims at is 48dp at its narrowest",
            heightDp >= 47.5f
        )
    }

    /**
     * The count is the row's information, and a long name must not push it off the card.
     *
     * `categoryName` is the flexible side and `taskCount` the fixed side; if a restyle gives the name
     * `wrap_content` the count walks out of the card on a narrow handset, which is exactly the defect Stage 4
     * measured and closed on the task card.
     */
    @Test
    fun `a long category name gives room to its count at 320dp`() {
        val binding = row()
        binding.categoryName.text = "Home improvement and maintenance backlog for the winter"
        binding.taskCount.text = "128"
        Stage8aLayoutHarness.laidOut(binding.root, Stage8aLayoutHarness.NARROW_DP)
        val card = binding.root

        assertTrue(
            "the count ends at ${binding.taskCount.right}px, outside a ${card.width}px card",
            binding.taskCount.right <= card.width && binding.taskCount.left >= 0
        )
        assertTrue("the name has to stop before the count rather than overlap it",
            binding.categoryName.right <= binding.taskCount.left + 1)
        assertTrue("and it says it was shortened instead of pretending to fit",
            binding.categoryName.ellipsize != null)
    }

    /**
     * The name is the only thing that tells two categories apart, so it is given the two lines the task card's
     * title has carried since before this redesign and Stage 4 kept: `item_task.xml` gave `taskTitle`
     * `maxLines="2"` in `b367904`, and the card work left it there.
     *
     * What made this visible was the stage's own photograph at 393dp: "Quarterly reconciliation of supp…"
     * beside a row that also said "0 tasks", so the only part of the screen that distinguishes the two
     * categories was the part that had been shortened.
     *
     * **What this class measures and what it cannot.** Everything below is box arithmetic — the width the
     * chain hands the name, where the count ends up, that the two do not overlap. Whether a given string
     * fits that box is not measurable here: Robolectric's LEGACY graphics return `text.length()` from
     * `Paint.measureText` and ignore `textSize` (measured in this stage: 81 characters came back as 81.0
     * pixels), and the NATIVE mode that would measure it has no binary for this machine — the same wall
     * `TaskCardWidthBudgetTest` documents from Stage 4. So "the seeded name is read in full" is asserted by
     * `Stage8aScreensInstrumentedTest`, at 393dp and at 320dp, by the renderer that ships. A JVM test that
     * asserted it here would pass on a row that clipped the name away — and its first draft in this very
     * stage failed on a row that did not.
     */
    @Test
    fun `a long category name is given two lines before it is cut`() {
        val binding = row()
        binding.categoryName.text = "Quarterly reconciliation of supplier invoices and the receipts filed " +
            "against them, signed and countersigned"
        binding.taskCount.text = "0 tasks"
        Stage8aLayoutHarness.laidOut(binding.root, Stage8aLayoutHarness.NARROW_DP)

        assertEquals(
            "the name is the row's only identifier and it is pinned to ${binding.categoryName.maxLines} " +
                "line(s); the task card's title has had two since before this redesign",
            2, binding.categoryName.maxLines
        )
        assertTrue("and it says when even two lines were not enough",
            binding.categoryName.ellipsize != null)
        assertTrue(
            "the count ends at ${binding.taskCount.right}px, outside a ${binding.root.width}px card",
            binding.taskCount.right <= binding.root.width && binding.taskCount.left >= 0
        )
        assertTrue(
            "the name stops before the count rather than overlapping it: name right=" +
                "${binding.categoryName.right}, count left=${binding.taskCount.left}",
            binding.categoryName.right <= binding.taskCount.left + 1
        )
        assertTrue(
            "and the name is given a real column, not the scrap between an icon and a count: it measures " +
                "${binding.categoryName.width}px of a ${binding.root.width}px card",
            binding.categoryName.width >= (200 * themed().resources.displayMetrics.density)
        )
    }

    @Test
    fun `the name is the row's headline and the count is its meta`() {
        val binding = row()
        Stage8aLayoutHarness.laidOut(binding.root)
        val nameSize = binding.categoryName.paint.textSize
        val countSize = binding.taskCount.paint.textSize
        assertTrue(
            "the hierarchy is inverted: the name draws at ${nameSize}px and the count at ${countSize}px",
            nameSize > countSize
        )
    }

    @Test
    fun `the editor keeps the two commands the fragment binds, as buttons`() {
        val binding = FragmentCategoryDetailBinding.inflate(Stage8aLayoutHarness.inflater())
        Stage8aLayoutHarness.laidOut(binding.root)
        val density = themed().resources.displayMetrics.density
        for ((button, name) in listOf(binding.saveButton to "save", binding.buttonCancel to "cancel")) {
            assertTrue(
                "the $name control stopped being a button a finger can press (height ${button.height / density}dp)",
                button.isClickable && button.height / density >= 47.5f
            )
        }
        assertEquals("the name field is what the screen reads back, so its label has to survive the restyle",
            themed().getString(R.string.hint_category_name), binding.categoryNameLayout.hint.toString())
    }
}
