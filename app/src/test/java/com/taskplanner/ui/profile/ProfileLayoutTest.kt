package com.taskplanner.ui.profile

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.taskplanner.R
import com.taskplanner.ui.components.AvatarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The shape of the Profile screen, read off the inflated layout.
 *
 * Profile has never had a test — `find app/src/test -path "*profile*"` returned nothing before this file —
 * and the reason it could go untested for two years is that eight identical full-width buttons look like a
 * finished screen from a distance. So this file tests the four things a list of rows has and a stack of
 * buttons cannot: an icon and a chevron per row, a height a finger can hit, group headers that say what the
 * group is for, and a header block that shows who is signed in.
 *
 * Nothing here depends on ids this stage invents. Rows are found through the ids `row_list` already gives
 * its children, and the section headers are the group's own text children — so the test cannot be satisfied
 * by declaring a view and calling it done, and it fails today for the real reason: there are no rows.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileLayoutTest {

    /** The eight destinations, in the order the reader meets them. */
    private val rows = 8

    private fun context(qualifier: String = "notnight"): Context {
        RuntimeEnvironment.setQualifiers(qualifier)
        return ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_TaskPlanner
        )
    }

    /** Inflated, measured and laid out at a real width, because an unmeasured view is 0x0 and 0 >= 48dp. */
    private fun profileAt(widthDp: Int, qualifier: String = "notnight"): View {
        val c = context(qualifier)
        val root = LayoutInflater.from(c).inflate(R.layout.fragment_profile, null) as ViewGroup
        val px = (widthDp * c.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_000_000, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun View.everything(): List<View> {
        val all = mutableListOf<View>()
        if (this is ViewGroup) for (i in 0 until childCount) all += getChildAt(i).everything()
        all += this
        return all
    }

    private fun View.children(): List<View> =
        if (this is ViewGroup) (0 until childCount).map { getChildAt(it) } else emptyList()

    private fun rowRoots(root: View): List<View> =
        root.everything().filter { it.id == R.id.rowTitle }.map { it.parent as View }

    private fun content(root: View): View = root.findViewById(R.id.contentGroup)

    /** The group's children, read in order, as "row", "header" or "block". */
    private fun sections(root: View): List<Pair<String, View>> = content(root).children().map { child ->
        when {
            child.everything().any { it.id == R.id.rowTitle } -> "row" to child
            child is TextView -> "header" to child
            else -> "block" to child
        }
    }

    @Test
    fun `the eight destinations are list rows with an icon, a title and a chevron`() {
        val found = rowRoots(profileAt(393))
        assertEquals("Profile is a list of $rows rows built on row_list, not a stack of buttons",
            rows, found.size)
        found.forEachIndexed { index, row ->
            assertNotNull("row $index has a chevron saying it goes somewhere",
                row.everything().firstOrNull { it.id == R.id.rowChevron })
            val container = row.everything().firstOrNull { it.id == R.id.rowIconContainer } as? FrameLayout
            assertNotNull("row $index has the icon container row_list declares", container)
            val glyph = container!!.everything().filterIsInstance<ImageView>().firstOrNull()
            assertNotNull("row $index holds an ImageView in that container, so the row is not text alone",
                glyph)
            // And the plate ships empty on purpose: which glyph a row carries is the screen's decision, and
            // the device test is where `drawable != null` is asserted. Naming it here says which half of the
            // promise the layout owns.
            assertNull("the include declares a place for a glyph, not a glyph", glyph!!.drawable)
        }
    }

    @Test
    fun `every row is at least a touch target tall`() {
        val floor = (48 * context().resources.displayMetrics.density).toInt()
        val found = rowRoots(profileAt(393))
        // Named before measured: a loop over an empty list passes every assertion inside it, and a test that
        // passes because there is nothing to check is the one kind of green that hides a screen.
        assertEquals("there are $rows rows to measure", rows, found.size)
        found.forEach { row ->
            assertTrue("a row measures ${row.height}px against a ${floor}px floor", row.height >= floor)
        }
    }

    @Test
    fun `no destination is a bare button spanning the screen`() {
        val buttons = content(profileAt(393)).everything().filterIsInstance<MaterialButton>()
        assertEquals(
            "the eight buttons were the screen; a row is a LinearLayout with an id, not a MaterialButton",
            0, buttons.size
        )
    }

    /**
     * Four headers, and the rows fall under them 2 / 4 / 1 / 1.
     *
     * The counts are the information architecture spec §4.9 settled: Account holds the profile itself and
     * the way to another one; Your work holds the four screens the account's own data produced; Data holds
     * settings, which owns export and import; and Danger zone holds the one row that cannot be undone.
     */
    @Test
    fun `the rows are read under four section headers`() {
        val grouped = sections(profileAt(393))
        val headers = grouped.filter { it.first == "header" }.map { it.second as TextView }
        assertEquals("Account, Your work, Data and Danger zone each name their group", 4, headers.size)
        headers.forEach { assertTrue("a section header with no words names nothing", it.text.isNotBlank()) }
        assertEquals("no two headers say the same thing",
            4, headers.map { it.text.toString() }.distinct().size)

        val counts = mutableListOf<Int>()
        grouped.filter { it.first != "block" }.forEach { (kind, _) ->
            if (kind == "header") counts += 0 else if (counts.isNotEmpty()) {
                counts[counts.size - 1] += 1
            }
        }
        assertEquals("Account 2, Your work 4, Data 1, Danger zone 1", listOf(2, 4, 1, 1), counts)
    }

    /**
     * The destructive row is set apart by a header of its own, not by how it is painted.
     *
     * Colour alone is what spec §4.4 exists to refuse: a red word on a white card is invisible to a reader
     * with deuteranopia and identical to every other row for a screen reader. So this asks the layout's
     * reading order rather than its palette — the last row must open a section, and be the only row in it.
     */
    @Test
    fun `the destructive row opens its own section rather than leaning on colour`() {
        val ordered = sections(profileAt(393)).filter { it.first != "block" }
        val headers = ordered.filter { it.first == "header" }.map { (it.second as TextView).text.toString() }
        assertEquals("the screen ends on a row", "row", ordered.last().first)
        assertTrue("the final row sits under a header of its own",
            headers.last().isNotBlank() && headers.last() != headers[headers.size - 2])
        val rowsUnderLast = ordered.reversed().takeWhile { it.first == "row" }.count()
        assertEquals("and it is the only row in that section", 1, rowsUnderLast)
    }

    /**
     * The four headers, in the order the reader meets them, are the four words the spec named.
     *
     * The test above counts headers; this one says which. A later stage that renames "Danger zone" to
     * "Advanced" is a design decision, and this is the line that makes someone write it down.
     */
    @Test
    fun `the four headers are the four words the spec named`() {
        val c = context()
        val wanted = listOf(
            R.string.section_account, R.string.section_your_work, R.string.section_data,
            R.string.section_danger_zone
        ).map { c.getString(it) }
        val found = sections(profileAt(393)).filter { it.first == "header" }
            .map { (it.second as TextView).text.toString() }
        assertEquals(wanted, found)
    }

    /**
     * A row that takes a press is announced as a button; a row that does not, is not.
     *
     * The primitive Stage 1 shipped is a `LinearLayout`, so a screen reader introduced the eight destinations
     * by their layout class and a reader who cannot see the screen never heard that these are things to
     * press. `ListRowView` fixes that for the rows a finger can press and leaves a display-only row alone,
     * because a button that does nothing when pressed is worse than a line of text. The press is installed by
     * the fragment, so the test installs it too: what is under check is the announcement rule, not which line
     * of Kotlin happens to run first.
     */
    @Test
    fun `a row is announced as a button when it takes a press, and not when it does not`() {
        val row = rowRoots(profileAt(393)).first()
        val plain = android.view.accessibility.AccessibilityNodeInfo.obtain()
        row.onInitializeAccessibilityNodeInfo(plain)
        // Asserted as "not a button" rather than as the layout's own class name, because the class is filled
        // in by a framework path Robolectric does not run: on the device it reads
        // `android.widget.LinearLayout` here, and under this test it reads null. What must hold in both is
        // that a row nobody has made pressable does not claim to be a button.
        assertNotEquals("a row nobody can press does not announce itself as a button",
            "android.widget.Button", plain.className?.toString())

        row.isClickable = true
        val pressed = android.view.accessibility.AccessibilityNodeInfo.obtain()
        row.onInitializeAccessibilityNodeInfo(pressed)
        assertEquals("android.widget.Button", pressed.className?.toString())
    }

    /**
     * A section header is a heading, not a smaller word.
     *
     * A screen-reader user moves between sections by jumping to headings; four labels that are only styled
     * differently leave them walking the whole list. `android:accessibilityHeading` is API 28 and this runs
     * on 34, so the SDK assertion above is what keeps the check below from passing on a device where the
     * attribute does not exist.
     */
    @Test
    fun `a section header is a heading a reader can jump to`() {
        assertTrue("the platform has to be one where the attribute means something",
            android.os.Build.VERSION.SDK_INT >= 28)
        val headers = sections(profileAt(393)).filter { it.first == "header" }.map { it.second as TextView }
        assertEquals(4, headers.size)
        headers.forEach { label ->
            assertTrue("\"${label.text}\" is not announced as a heading", label.isAccessibilityHeading)
        }
    }

    @Test
    fun `the header shows who is signed in`() {
        val root = profileAt(393)
        val side = (64 * context().resources.displayMetrics.density).toInt()
        val avatar = root.everything().filterIsInstance<AvatarView>().firstOrNull()
        assertNotNull("a 64dp avatar carries the initials the name would say", avatar)
        assertEquals("avatar_header", side, avatar!!.layoutParams.height)
        assertEquals(side, avatar.layoutParams.width)
        assertNotNull(root.findViewById<View>(R.id.profileName))
        assertNotNull(root.findViewById<View>(R.id.profileEmail))
    }

    /**
     * The rewrite may not quietly drop the handles the rest of the code holds.
     *
     * `contentGroup` is what `ProfileFragment.observeViewModel` shows and hides for three of its four states
     * and what `ProfileNoUserInstrumentedTest` asserts is gone when nobody is signed in; the no-profile group
     * is the only way into the app for a reader who has never made a profile. (The plan's original reason for
     * keeping `contentGroup` — that `TasksScreenInstrumentedTest` asserted it from another screen — is stale:
     * that assertion was replaced in Stage 0.1a, because `contentGroup` does not exist on Statistics and the
     * test could never have passed.)
     */
    @Test
    fun `the views the rest of the app reaches for survive the rewrite`() {
        val root = profileAt(393)
        listOf(
            R.id.contentGroup, R.id.noUserGroup, R.id.progressBar, R.id.profileName, R.id.profileEmail,
            R.id.createProfileButton, R.id.switchProfileButtonNoUser
        ).forEach { id ->
            assertNotNull("id ${root.resources.getResourceEntryName(id)} is bound outside this screen",
                root.findViewById<View>(id))
        }
    }

    /**
     * The same eight rows at 320dp, where the budget this design was argued against is tightest.
     *
     * A row that overflowed its group would still measure a plausible height, so the width is asserted equal
     * to the group's own content width rather than merely not larger, and the height is held to the same 48dp
     * floor as at 393dp — a row that shrinks to fit a narrow panel is the failure this stage promised not to
     * produce by shrinking anything.
     */
    @Test
    fun `the rows still fit at the narrowest panel the budget allows`() {
        val root = profileAt(320)
        val group = content(root)
        val found = rowRoots(root)
        assertEquals("the same eight rows at 320dp", rows, found.size)
        val floor = (48 * context().resources.displayMetrics.density).toInt()
        val inner = group.width - group.paddingLeft - group.paddingRight
        found.forEach { row ->
            assertEquals("a row fills its group's content width and no more", inner, row.width)
            assertTrue("a row is ${row.height}px at 320dp, under the same ${floor}px floor",
                row.height >= floor)
            val chevron = row.everything().firstOrNull { it.id == R.id.rowChevron }!!
            assertTrue("and the chevron is inside the row, not hanging past it",
                chevron.right <= row.width && chevron.right > row.left)
        }
    }
}
