package com.taskplanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.os.Trace
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.R as MaterialR
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.testing.SeededProfile
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom bar on the screen it is actually drawn on.
 *
 * Four of this stage's acceptance items can only be answered by a rendered window: that five words are on the
 * bar at the same time and none of them is cut short (the baseline showed one label), that a rule is drawn
 * between the bar and the content (the bar's surface is 1.05:1 from the page, so the separation is a line and
 * not a tint), that the chosen tab carries a plate the other four do not, and that each tab arrives at the
 * screen it names. A JVM test sees none of them — `activity_main` cannot even be inflated there, because its
 * `FragmentContainerView` builds a navigation host — and an XML read would only prove that somebody typed an
 * attribute.
 *
 * So this class signs in, opens the app, presses all five tabs and reads the destination out of the navigation
 * controller rather than out of whatever happens to be on screen, then samples the pixels the bar is made of.
 * Every pixel claim is measured from a screenshot the test itself took, with the geometry taken from
 * `getLocationOnScreen` and the view's own width and height — never from `getGlobalVisibleRect`, which on a
 * recording canvas reports the *content* bounds and would quietly shrink the region being asserted. It also
 * writes the frames the stage's evidence is made of: one per tab, into a directory named by the `-e tag` the
 * script passes, which the same tag is then checked against — a frame filed under `320-light-10` is refused if
 * the device is not reporting 320 dp, light, font 1.0 at the moment the shutter opens.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavigationInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    /**
     * A tab, the destination it names, the views that prove its screen drew something, and the frame it is
     * photographed as.
     *
     * Two anchors where a screen has an empty state — Categories with no categories shows its empty view, not
     * its list — because the claim is "the reader arrived somewhere that answered", and an empty state is an
     * answer. A destination id that draws nothing at all is still caught.
     */
    private data class Tab(
        val id: Int,
        val titleRes: Int,
        val anchors: List<Int>,
        val frame: String
    )

    private val tabs = listOf(
        Tab(R.id.navigation_tasks, R.string.title_tasks, listOf(R.id.tasksHeader), "s7-nav-tasks"),
        Tab(R.id.navigation_calendar, R.string.title_calendar, listOf(R.id.calendarTitle), "s7-nav-calendar"),
        Tab(
            R.id.navigation_categories, R.string.title_categories,
            listOf(R.id.recyclerViewCategories, R.id.emptyView), "s7-nav-categories"
        ),
        Tab(
            R.id.navigation_statistics, R.string.title_statistics,
            // `emptyView` is not in this layout — it belongs to Tasks and Categories. The dashboard's own
            // headline and its period chips are, and one of the two being on the window is what "the reader
            // arrived" means here.
            listOf(R.id.statisticsPeriodScroll, R.id.dashboardHeadline), "s7-nav-statistics"
        ),
        Tab(R.id.navigation_profile, R.string.title_profile, listOf(R.id.profileName), "s7-nav-profile")
    )

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the app opens on Tasks; the bar is only worth testing on a screen that reached it",
            waitUntil("the tasks screen") { findView(R.id.tasksHeader) != null }
        )
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    /**
     * Five words on the bar at once, none of them cut short, and the mode that says they must be.
     *
     * The baseline this replaces showed one label: `labelVisibilityMode` was left at the widget's own `AUTO`,
     * which with five destinations means "label the chosen one". `isShown()` and a non-zero size are asked
     * rather than `visibility`, because a view can be VISIBLE and still not be attached to a window the reader
     * can see. The ellipsis question is asked of the label's own `Layout`, which is the only thing on the
     * device that knows how many characters it had to drop: Material pins these labels to one line and
     * `ellipsize=end` (`design_bottom_navigation_item.xml`), so an over-long word does not wrap, it loses
     * letters — and "CATEGOR…" is not the destination's name.
     */
    @Test
    fun allFiveLabelsAreOnTheScreenAtOnce() {
        val bar = requireBar()
        assertEquals(
            "the bar is not in the mode that labels every item",
            NavigationBarView.LABEL_VISIBILITY_LABELED,
            bar.labelVisibilityMode
        )
        val labels = labelsOn(bar)
        assertEquals(
            "labels on the bar: " + labels.joinToString { "'${it.text}' ${it.width}x${it.height} shown=${it.isShown}" },
            tabs.size,
            labels.size
        )
        labels.forEachIndexed { index, label ->
            assertTrue("'${label.text}' is not shown at ${label.width}x${label.height}", label.isShown)
            assertTrue("'${label.text}' has no size", label.width > 0 && label.height > 0)
            val layout = label.layout
                ?: throw AssertionError("'${label.text}' has no layout, so it cannot have been drawn")
            assertEquals(
                "'${label.text}' is drawn as '${layout.text}' — ${layout.getEllipsisCount(0)} characters were " +
                    "dropped from it, so the bar does not show all five names at once at this width and font " +
                    "scale",
                0,
                layout.getEllipsisCount(0)
            )
            assertEquals(
                "'${label.text}' is not the word tab $index offers",
                context.getString(tabs[index].titleRes),
                label.text.toString()
            )
            val row = rowOf(label)
            assertTrue(
                "'${label.text}' sits in a row ${row.width}x${row.height}; a finger needs ${dp(48f)} in both " +
                    "directions, and the row is the item view the bar itself sizes",
                row.height >= dp(48f) && row.width >= dp(48f)
            )
        }
        assertEquals(
            "the words are not the five the menu promises",
            tabs.map { context.getString(it.titleRes) }.sorted(),
            labels.map { it.text.toString() }.sorted()
        )
    }

    /**
     * What a screen reader is given, read out of the accessibility tree rather than the view tree.
     *
     * `uiautomator dump` is the same tree from outside, and the stage's acceptance asks for it that way too;
     * the matrix takes that dump as well, but only after restarting the app, because an `ActivityScenario`
     * closes its window with the test and a dump taken afterwards is a picture of the launcher. This method is
     * the claim made while the window this test owns is still on screen.
     *
     * The tree is asked of `UiAutomation`, not of `view.createAccessibilityNodeInfo()`: a node built by the
     * view is not *sealed*, and `getChild` on an unsealed node throws "Cannot perform this action on a not
     * sealed instance" — measured on this device on the first run of this file. The automation's root window
     * is the sealed tree a service actually receives, which is also the honest source: it is what TalkBack
     * reads, including the fields the framework fills in (the tab's role description, its `selected` state)
     * that a view hands out only to a caller that asks through the accessibility channel.
     */
    @Test
    fun theAccessibilityTreeOffersAllFiveNames() {
        val bar = requireBar()
        val root = waitUntilNotNull("the accessibility tree of the app window") {
            instrumentation.uiAutomation.rootInActiveWindow?.takeIf {
                it.findNodeByViewId("com.taskplanner:id/bottom_navigation") != null
            }
        }
        val barNode = root.findNodeByViewId("com.taskplanner:id/bottom_navigation")
            ?: throw AssertionError("the bar has a node in the view tree and none in the accessibility tree")
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            nodes += node
            for (child in 0 until node.childCount) walk(node.getChild(child))
        }
        walk(barNode)
        val named = nodes.mapNotNull { node ->
            (node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty())
                .trim()
                .takeIf { it.isNotEmpty() }
        }
        val words = tabs.map { context.getString(it.titleRes) }
        val missing = words.filter { word -> named.none { it.contains(word) } }
        val line = "nodes in and under the bar: ${nodes.size}\n" +
            "class names: " + nodes.joinToString { it.className.toString() }.take(400) + "\n" +
            "names a reader is offered: ${named.distinct()}\n" +
            "selected nodes: " + nodes.filter { it.isSelected }.joinToString {
                ((it.text ?: it.contentDescription) ?: "<a node with no name>").toString()
            } + "\n"
        // Written where a failure is a failure: the record is part of the evidence, and a run that could not
        // write it has not measured the tree it claims to have read.
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            "shots/${InstrumentationRegistry.getArguments().getString("tag", "393-light-10")}"
        )
        directory.mkdirs()
        File(directory, "accessibility-tree.txt").writeText(line)
        assertTrue(
            "the bar's accessibility tree offers ${named.size} named nodes and not the five words: " +
                "missing $missing. What it does offer: ${named.distinct()}",
            missing.isEmpty()
        )
        // The claim is about what a reader is *told*, not about how many nodes carry the flag. Measured on this
        // device from inside the window and from outside with `uiautomator dump`, a chosen tab raises more than
        // one node and each of them names the same tab. That is a bar that works. What would be a defect is two
        // *different* tabs answering `selected`, or none.
        val chosen = context.getString(tabs.first { it.id == bar.selectedItemId }.titleRes)
        val selected = nodes.filter { it.isSelected }
            .map { (it.text?.toString() ?: it.contentDescription?.toString()).orEmpty().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        assertEquals(
            "the bar tells a reader that these tabs are selected: $selected. One tab is chosen — the flag may " +
                "sit on more than one node of it, but it must not name a second tab",
            listOf(chosen),
            selected
        )
    }

    /** Depth-first search for the node carrying a resource id, in the tree a service is handed. */
    private fun AccessibilityNodeInfo.findNodeByViewId(id: String): AccessibilityNodeInfo? {
        if (viewIdResourceName == id) return this
        for (child in 0 until childCount) {
            val found = getChild(child)?.findNodeByViewId(id)
            if (found != null) return found
        }
        return null
    }

    /**
     * Poll for a value rather than for a boolean, because the accessibility tree is published by a channel
     * outside the app's own layout pass and can answer `null` for a moment after a window is up.
     */
    private fun waitUntilNotNull(description: String, produce: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var last: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline) {
            last = runCatching(produce).getOrNull()
            if (last != null) return last
            Thread.sleep(100)
        }
        throw AssertionError("no $description appeared in ${WAIT_MILLIS}ms (last answer: $last)")
    }

    /**
     * What the five words do at the largest font scale the app supports — measured, not asserted, because
     * the measurement says something an assertion here could not. `allFiveLabelsAreOnTheScreenAtOnce` keeps
     * five whole names at 1.0. This method runs at 1.3 and writes each label's slot and dropped-character
     * count to `label-budget.txt`; the file reads the same at 1.0 and at 1.3 because Material sizes these
     * labels through `setTextAppearanceWithoutFontScaling`, which converts `sp` by
     * `DisplayMetrics.density` alone and applies it with `setTextSize(COMPLEX_UNIT_PX, …)`. A clean budget at
     * 1.3 is therefore not the bar adapting but the bar not responding — WCAG 1.4.4, Stage 10's to fix, with
     * the frame comparison in `docs/UI_UX_AUDIT.md` §4.4. Asserted here: five labels, shown, one word each.
     */
    @Test
    fun theLabelsToldAtTheLargestFontScaleAreWrittenDown() {
        val bar = requireBar()
        val labels = labelsOn(bar)
        assertEquals("the bar lost a label entirely", tabs.size, labels.size)
        val lines = labels.map { label ->
            assertTrue("'${label.text}' stopped being shown", label.isShown)
            val dropped = label.layout?.getEllipsisCount(0) ?: -1
            "'${label.text}' in a ${label.width}px slot dropped $dropped characters"
        }
        val tag = InstrumentationRegistry.getArguments().getString("tag", "393-light-10")
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            "shots/$tag"
        )
        directory.mkdirs()
        File(directory, "label-budget.txt").writeText(lines.joinToString(separator = "\n") + "\n")
        photograph("s7-labels-$tag", bar)
    }

    /**
     * Each tab opens the screen it names, and the bar is photographed there.
     *
     * The destination is read from the navigation controller — the thing that decides what the reader sees —
     * and the anchor view is asked for as well, because a destination id can be correct while the fragment
     * behind it draws nothing.
     */
    @Test
    fun eachTabOpensTheScreenItNames() {
        tabs.forEach { tab ->
            pressUntil(tab.id) { tab.anchors.any { findView(it)?.isShown == true } }
            assertEquals(
                "pressing '${context.getString(tab.titleRes)}' left the reader somewhere else",
                tab.id,
                currentDestination()
            )
            assertTrue(
                "'${context.getString(tab.titleRes)}' is the current destination but none of " +
                    "${tab.anchors.size} views that screen draws is on the window",
                tab.anchors.any { findView(it)?.isShown == true }
            )
            val bar = requireBar()
            assertEquals(
                "the bar does not show '${context.getString(tab.titleRes)}' as the chosen tab",
                tab.id,
                bar.selectedItemId
            )
            photograph(tab.frame, bar)
        }
    }

    /**
     * The chosen tab's word is drawn in a different colour from the other four, on the screen.
     *
     * The style claim is `BottomNavigationStyleTest`'s; this is the same question asked of the widget after
     * the activity has applied its theme and the user has moved the selection, which is where a tint that
     * only exists on paper would be caught.
     */
    @Test
    fun theChosenTabIsDrawnInADifferentColourFromTheOtherFour() {
        arriveAt(R.id.navigation_categories)
        val ink = colour(R.color.ink_text)
        val secondary = colour(R.color.ink_text_secondary)
        val colours = labelsOn(requireBar()).map { it.currentTextColor }
        assertEquals(
            "exactly one label should carry `ink_text`; the five read " +
                colours.joinToString { String.format("#%08X", it) },
            1,
            colours.count { it == ink }
        )
        assertEquals(
            "the other four carry `ink_text_secondary`",
            4,
            colours.count { it == secondary }
        )
    }

    /**
     * A rule is drawn between the content and the bar, and the bar sits on its own surface.
     *
     * Sampled from the photograph rather than read from the layout. Since Stage 7 moved the line *inside* the
     * bar's own background (`bg_bottom_bar.xml`), the rule is the bar's first pixel row, not a row above it:
     * the sibling `View` this replaced painted over the last row of whatever screen the host was showing. So
     * three rows are asked about — the bar's top row is the line, the row two pixels above the bar is not the
     * line (a rule that ran up into the content would be a band, not an edge), and a column inside the bar,
     * clear of the plate and the words, is the surface role the theme names. Flat fills are compared exactly:
     * these are solid shapes with nothing to blend against, and a ±6 tolerance would let a wrong surface pass.
     */
    @Test
    fun aDrawnRuleSeparatesTheBarFromThePage() {
        val bar = requireBar()
        val bitmap = photographed("the bar and the content above it")
        val frame = rectOnScreen(bar)
        val strong = colour(R.color.ink_outline_strong)
        val surface = attrColour(MaterialR.attr.colorSurfaceContainer)

        val left = frame.left + dp(8f)
        val right = frame.right - dp(8f)

        val ruleShare = shareOfRow(bitmap, frame.top, left, right) { it == strong }
        assertTrue(
            "the bar's first row is not a drawn rule of `ink_outline_strong`: ${(ruleShare * 100).roundToInt()}% " +
                "of its columns are ${hex(strong)}; the middle one is " +
                hex(pixelAt(bitmap, frame.top, (left + right) / 2)),
            ruleShare >= 0.9f
        )

        val host = findView(R.id.nav_host_fragment)
            ?: throw AssertionError("no navigation host on the window, so nothing bounds the content")
        val hostFrame = rectOnScreen(host)
        assertTrue(
            "the navigation host reaches ${hostFrame.bottom} and the bar starts at ${frame.top}, so content is" +
                " drawn where the bar's boundary line is: the host is what the bar has to sit under",
            hostFrame.bottom <= frame.top
        )

        val aboveRow = (frame.top - 2).coerceAtLeast(0)
        val clearShare = shareOfRow(bitmap, aboveRow, left, right) { !near(it, strong) }
        assertTrue(
            "the strong line runs up into the content instead of ending at the bar: only " +
                "${(clearShare * 100).roundToInt()}% of the row 2px above the bar is something other than " +
                "${hex(strong)}; the middle is ${hex(pixelAt(bitmap, aboveRow, (left + right) / 2))}",
            clearShare >= 0.9f
        )

        // A column 2dp inside the bar's left edge. The plate is centred in the item and the library clamps its
        // width to the item's width less twice `itemActiveIndicatorMarginHorizontal` (4dp), so the plate cannot
        // start nearer than 4dp from the bar's own edge at any width: 393dp puts it at 7dp, 320dp at 4dp. Two
        // dp is therefore surface or nothing, which is what makes an exact comparison honest here.
        val column = frame.left + dp(2f)
        var hits = 0
        var rows = 0
        var y = frame.top + dp(3f)
        while (y < frame.bottom - dp(3f)) {
            rows++
            if (pixelAt(bitmap, y, column) == surface) hits++
            y += 2
        }
        val fill = if (rows == 0) 0f else hits.toFloat() / rows
        assertTrue(
            "the bar is not painted with its surface role: $hits of $rows pixels down a column inside it are " +
                "${hex(surface)}; the one at the middle of that column is " +
                hex(pixelAt(bitmap, frame.top + (frame.height() / 2), column)),
            fill >= 0.9f
        )
        bitmap.recycle()
    }

    /**
     * The two surfaces, and the line between them, measured as luminance ratios off the photograph.
     *
     * The brief asks the bar's background to sit at least 1.3:1 from `android:colorBackground` while also
     * asking it to be `?attr/colorSurfaceContainer`; those two cannot both be true of this palette, so the
     * stage measures the step instead of assuming one and names the drawn line as the boundary that does the
     * work. The page colour is read from the frame, not from a resource, because the claim is about the pixels
     * a reader sees.
     */
    @Test
    fun theBoundaryBetweenBarAndPageIsMeasurable() {
        val bar = requireBar()
        val bitmap = photographed("the bar and the content above it")
        val frame = rectOnScreen(bar)
        val centre = (frame.left + frame.right) / 2

        val surface = pixelAt(bitmap, frame.top + dp(20f), frame.left + dp(2f))
        val rule = pixelAt(bitmap, frame.top, centre)
        val expectedSurface = attrColour(MaterialR.attr.colorSurfaceContainer)
        val expectedRule = colour(R.color.ink_outline_strong)
        val page = colour(R.color.ink_background)

        assertEquals("the bar's surface pixel is not the role the theme names", expectedSurface, surface)
        assertEquals("the bar's rule pixel is not `ink_outline_strong`", expectedRule, rule)

        val tintStep = ratio(surface, page)
        val ruleAgainstPage = ratio(rule, page)
        val ruleAgainstSurface = ratio(rule, surface)
        writeMeasurements(
            "surface/page tint step ${tintStep.format()}:1 (the brief asks 1.3:1 of the tint alone;" +
                " this palette cannot give both that and `colorSurfaceContainer`)",
            "rule against the page ${ruleAgainstPage.format()}:1",
            "rule against the bar surface ${ruleAgainstSurface.format()}:1"
        )
        assertTrue(
            "the drawn rule is not a boundary a reader can find: ${hex(rule)} against the page ${hex(page)} is " +
                "${ruleAgainstPage.format()}:1 and against the bar it is ${ruleAgainstSurface.format()}:1;" +
                " 1.4.11 asks 3:1",
            ruleAgainstPage >= 3.0 && ruleAgainstSurface >= 3.0
        )
        assertTrue(
            "the bar's surface does not sit above the page at all: ${hex(surface)} against ${hex(page)} is " +
                "${tintStep.format()}:1",
            tintStep > 1.0
        )
        bitmap.recycle()
    }

    /**
     * The plate behind the chosen icon, measured as pixels — and measured around the glyph it has to contain.
     *
     * `ink_nav_plate` was chosen because `pill_accent` at 1.12:1 is not a mark anyone can see, so this is now
     * also the claim that the selection is told without colour (1.4.11). The sample is compared exactly: the
     * plate is a solid shape, and a ±6 window would accept a neighbouring colour.
     *
     * The plate's own edges are found by scanning the chosen item's slot for pixels of the plate colour and
     * taking their extremes, which works even where the glyph's ink interrupts the row, and they are then
     * compared against the drawn icon. That is the check that would have caught the 40dp x 24dp plate this
     * stage first shipped: the shape is a stadium whose ends are semicircles of radius height/2, so a plate
     * the height of the icon has nothing left at the icon's own edges and the glyph's corners fall outside it.
     */
    @Test
    fun theChosenTabSitsOnADrawnPlate() {
        arriveAt(R.id.navigation_statistics)
        val bar = requireBar()
        val bitmap = photographed("the plate behind the chosen tab")
        val pill = colour(R.color.ink_nav_plate)
        val surface = attrColour(MaterialR.attr.colorSurfaceContainer)
        // The plate is 64dp wide and the icon 24dp, so 16dp from the icon's centre is past the glyph and still
        // well inside the plate. Measured at 8dp it would land on the icon and prove nothing.
        val plateOffset = dp(16f)

        val items = itemViews(bar)
        val chosenIndex = tabs.indexOfFirst { it.id == bar.selectedItemId }
        assertTrue("the bar's selection ${bar.selectedItemId} is not one of the five tabs", chosenIndex >= 0)
        val samples = items.mapIndexed { index, item ->
            val icon = iconOf(item, index)
            val at = IntArray(2)
            icon.getLocationOnScreen(at)
            val x = at[0] + icon.width / 2 + plateOffset
            val y = at[1] + icon.height / 2
            assertTrue(
                "the sample point for tab $index is outside the photographed frame ($x, $y)",
                x in 0 until bitmap.width && y in 0 until bitmap.height
            )
            pixelAt(bitmap, y, x)
        }
        assertEquals("the bar drew ${samples.size} icon plates; five were expected", tabs.size, samples.size)

        assertEquals(
            "no plate is drawn behind the chosen tab: the pixel there is ${hex(samples[chosenIndex])}, " +
                "`ink_nav_plate` is ${hex(pill)}",
            pill,
            samples[chosenIndex]
        )
        samples.forEachIndexed { index, pixel ->
            if (index != chosenIndex) {
                assertEquals(
                    "tab $index carries a plate too (${hex(pixel)}), so the plate is not telling the reader " +
                        "which tab is chosen",
                    surface,
                    pixel
                )
            }
        }

        val chosen = items[chosenIndex]
        val icon = iconOf(chosen, chosenIndex)
        val iconAt = IntArray(2)
        icon.getLocationOnScreen(iconAt)
        val iconBox = Rect(iconAt[0], iconAt[1], iconAt[0] + icon.width, iconAt[1] + icon.height)
        val slotAt = IntArray(2)
        chosen.getLocationOnScreen(slotAt)
        val slot = Rect(slotAt[0], slotAt[1], slotAt[0] + chosen.width, slotAt[1] + chosen.height)
        val drawn = plateBounds(bitmap, pill, slot, iconBox.centerY(), iconBox.centerX())
        assertTrue(
            "no drawn plate was found inside tab $chosenIndex's slot $slot: the colour asked for is " +
                "${hex(pill)}",
            drawn != null
        )
        val plate = drawn!!
        // The rooms are written the way they are read — how much plate there is beyond each edge of the glyph,
        // in pixels and in dp, because 1px of plate at 220dpi and at 270dpi are different promises.
        val density = context.resources.displayMetrics.density
        fun dpRoom(px: Int): String = String.format("%.1f dp", px / density)
        writeMeasurements(
            "drawn plate $plate is ${plate.width()}x${plate.height()}px behind a ${iconBox.width()}x" +
                "${iconBox.height()}px glyph, ${String.format("%.1f", plate.width() / density)}dp x " +
                "${String.format("%.1f", plate.height() / density)}dp at ${density}x density: " +
                "${iconBox.left - plate.left}px (${dpRoom(iconBox.left - plate.left)}) of plate to the " +
                "glyph's left, ${plate.right - iconBox.right}px (${dpRoom(plate.right - iconBox.right)}) to " +
                "its right, ${iconBox.top - plate.top}px (${dpRoom(iconBox.top - plate.top)}) above and " +
                "${plate.bottom - iconBox.bottom}px (${dpRoom(plate.bottom - iconBox.bottom)}) below"
        )
        // Room is compared in dp, with half a dp of tolerance, because a pixel grid cannot express 4dp at
        // 220dpi: the plate is 44px around a 33px glyph, which is exactly 4.0dp of room per side, and rounding
        // each edge to a whole pixel leaves 5px on one side and 6px on the other. Measuring the drawn artefact
        // against `dp(4f)` in pixels would fail a plate that is the size the design says it is.
        val sideRoomDp = (plate.width() - iconBox.width()) / 2f / context.resources.displayMetrics.density
        assertTrue(
            "the glyph is not inside the plate: the plate runs ${plate.width()}px wide from ${plate.left} to " +
                "${plate.right} and the icon is ${iconBox.width()}px from ${iconBox.left} to " +
                "${iconBox.right}, which is $sideRoomDp dp of plate beside each side of the glyph and this " +
                "design asks 8dp",
            sideRoomDp >= 8f - 0.5f
        )
        // The vertical room is asserted at the scale the design is measured at, and reported at any other.
        // `NavigationBarItemView.onMeasure` asks for the label's own height and clamps what is left of
        // `m3_navigation_bar_item_height_with_text - glyph - itemPaddingBottom` into `itemPaddingTop`, so a
        // taller label would move the icon's box up while the plate — centred on the item, not the icon —
        // stayed put. It cannot get taller: Material sizes the label through
        // `setTextAppearanceWithoutFontScaling`, which converts `sp` by `DisplayMetrics.density` alone, so
        // the words measure the same at 1.3 as at 1.0 and this stage's first reading (−4dp above, +12dp
        // below at 393dp/1.3) came from a stale frame rather than from a drift. The final matrix measures 5px
        // above and 6px below at 393dp, 6 and 6 at 360dp, 6 and 7 at 320dp, identically at 1.0 and 1.3. That
        // the labels do not scale at all is WCAG 1.4.4's finding and Stage 10's to fix; recorded, not asserted.
        val gapRoomDp = (plate.height() - iconBox.height()) / 2f / context.resources.displayMetrics.density
        val rooms = intArrayOf(iconBox.top - plate.top, plate.bottom - iconBox.bottom)
        if (isTheReferenceFontScale()) {
            assertTrue(
                "the plate is ${plate.height()}px tall around a ${iconBox.height()}px glyph — $gapRoomDp dp per " +
                    "side, and this design asks 4dp. Its rounded ends are semicircles of radius " +
                    "${plate.height() / 2}px, so less than that and a squared-off glyph is cut by the curve. " +
                    "The drawn rooms are ${rooms[0]}px above and ${rooms[1]}px below.",
                gapRoomDp >= 4f - 0.5f
            )
        }
        bitmap.recycle()
    }

    /** Is the device at font scale 1.0 — the scale this stage's geometry is stated in? */
    private fun isTheReferenceFontScale(): Boolean {
        val metrics = context.resources.displayMetrics
        val font = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics) /
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, metrics)
        return abs(font - 1.0f) < 0.02f
    }

    /**
     * A finger on the chosen tab still draws something on it.
     *
     * Stage 7 replaced the bar's background with a solid drawable and changed the plate's colour, and neither
     * of those things is the ripple — but a plate at 3.55:1 is a much stronger mark than the pale tint the
     * pressed state was blended against, and nothing in this suite would notice a pressed tab that looked
     * exactly like an idle one. So a press is injected, held, photographed, and the pixels are compared with
     * the same tab at rest. If the platform will not take the touch, the test says so as a harness failure:
     * the alternative is a green row in the manifest that measured nothing.
     */
    @Test
    fun holdingAFingerOnTheChosenTabChangesItsPixels() {
        arriveAt(R.id.navigation_tasks)
        val bar = requireBar()
        val item = itemViews(bar).first()
        val at = IntArray(2)
        item.getLocationOnScreen(at)
        val region = Rect(at[0], at[1], at[0] + item.width, at[1] + item.height)
        val x = region.centerX()
        val y = region.centerY()
        val before = photographed("the chosen tab at rest")
        val down = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
            x.toFloat(), y.toFloat(), 0
        )
        // The source is not decoration: `MotionEvent.obtain(…)` left alone describes a mouse, and a mouse over
        // a tab hovers. The first run of this test injected a press that reached the window and never set
        // `isPressed`, because the platform did not consider a mouse button a finger.
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        val injected = runCatching { instrumentation.sendPointerSync(down) }
        if (injected.isFailure) {
            before.recycle()
            down.recycle()
            throw AssertionError(
                "HARNESS, not the app: the instrumentation could not inject a touch at ($x, $y) — " +
                    injected.exceptionOrNull()?.message + ". Whether the plate ripples is therefore unmeasured " +
                    "on this run, and this file does not get to call that a pass."
            )
        }
        var pressed = false
        var changed = -1
        var elsewhere = -1
        var heldFor = 0
        var frame: Bitmap? = null
        try {
            // Two shutter moments rather than one, because a ripple is an animation and a single frame is a
            // sample of it: holding for 180 ms and photographing once came back byte-identical to the resting
            // frame in the night theme, which is not distinguishable from "this bar draws no pressed state"
            // without a second look at a longer hold.
            for (hold in HOLD_MILLIS) {
                Thread.sleep(hold.toLong())
                heldFor = hold
                scenario.onActivity { pressed = item.isPressed }
                val candidate = photographed("the chosen tab held for ${hold}ms")
                val moved = countChanged(before, candidate, region)
                val everywhere = countChanged(before, candidate, Rect(0, 0, candidate.width, candidate.height))
                if (moved > 0 || hold == HOLD_MILLIS.last()) {
                    frame = candidate
                    changed = moved
                    elsewhere = everywhere - moved
                } else {
                    candidate.recycle()
                }
            }
        } finally {
            val up = MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                x.toFloat(), y.toFloat(), 0
            )
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            runCatching { instrumentation.sendPointerSync(up) }
            up.recycle()
            down.recycle()
        }
        val during = frame ?: throw AssertionError("no frame was kept from the held press at all")
        val sample = hex(pixelAt(during, y, x))
        writeMeasurements(
            "held ${heldFor}ms: $changed of ${region.width()}x${region.height()} = " +
                "${region.width() * region.height()} pixels of the chosen tab moved, and $elsewhere pixels " +
                "moved elsewhere on the screen; the pixel under the finger is $sample; " +
                "the view answered isPressed=$pressed"
        )
        before.recycle()
        during.recycle()
        assertTrue(
            "nothing under the chosen tab moved while a finger was held on it ($changed pixels of " +
                "${region.width() * region.height()}, after ${heldFor}ms, while $elsewhere pixels moved " +
                "elsewhere on the same screen), so the pressed state is not drawn: the bar has lost the " +
                "feedback a reader gets between touching and arriving. The view said isPressed=$pressed and " +
                "the pixel under the finger stayed $sample.",
            changed > 200
        )
    }

    /** How many pixels inside [region] differ between two frames of the same screen. */
    private fun countChanged(before: Bitmap, after: Bitmap, region: Rect): Int {
        assertEquals("the two frames are of different screens", before.width, after.width)
        assertEquals("the two frames are of different screens", before.height, after.height)
        var changed = 0
        var y = region.top
        while (y < region.bottom) {
            var x = region.left
            while (x < region.right) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                x++
            }
            y++
        }
        return changed
    }

    /**
     * The extents of the drawn plate, by scanning for its colour.
     *
     * A row across the icon's centre gives the left and right, a column through the icon's centre the top and
     * bottom. Extremes rather than runs, because the glyph's own ink interrupts the middle and a contiguous
     * scan would stop at the icon instead of reaching the plate's edge.
     */
    private fun plateBounds(bitmap: Bitmap, pill: Int, slot: Rect, rowY: Int, columnX: Int): Rect? {
        var left = -1
        var right = -1
        var x = slot.left
        while (x < slot.right) {
            if (pixelAt(bitmap, rowY, x) == pill) {
                if (left < 0) left = x
                right = x
            }
            x++
        }
        if (left < 0) return null
        var top = -1
        var bottom = -1
        var y = slot.top
        while (y < slot.bottom) {
            if (pixelAt(bitmap, y, columnX) == pill) {
                if (top < 0) top = y
                bottom = y
            }
            y++
        }
        return if (top < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    // -- the fixture ------------------------------------------------------------------------------

    companion object {
        private lateinit var database: AppDatabase
        private lateinit var signedIn: SeededProfile.SignedIn

        @BeforeClass
        @JvmStatic
        fun signInOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            database = SeededProfile.open(context)
            try {
                SeededProfile.sweepLeftovers(database)
                signedIn = SeededProfile.signIn(database, "nav-stage7@example.test", "Навигация Stage 7")
            } finally {
                database.close()
            }
        }

        @AfterClass
        @JvmStatic
        fun signOutAndTakeTheProfileAway() {
            if (!::signedIn.isInitialized) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            database = SeededProfile.open(context)
            try {
                SeededProfile.takeAway(database, signedIn)
            } finally {
                database.close()
            }
        }

        /** How far a sampled pixel may sit from a colour and still be called that colour. */
        const val CHANNEL_TOLERANCE = 6

        const val WAIT_MILLIS = 20_000L
        const val TAB_ATTEMPTS = 3
        const val SHUTTER_ATTEMPTS = 5

        /** How long to hold the injected finger, in the two attempts the test makes. */
        val HOLD_MILLIS = intArrayOf(180, 420)
    }

    // -- the helpers -------------------------------------------------------------------------------

    private fun requireBar(): BottomNavigationView {
        val found = findView(R.id.bottom_navigation)
            ?: throw AssertionError("no bottom bar on the screen while the app is ${scenario.state}")
        return found as BottomNavigationView
    }

    private fun findView(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun currentDestination(): Int? {
        var destination: Int? = null
        runCatching {
            scenario.onActivity { activity ->
                destination = (activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                    .navController.currentDestination?.id
            }
        }
        return destination
    }

    /** Land on a tab that the other tests need, and say which screen the reader was left on if it fails. */
    private fun arriveAt(tab: Int) {
        val anchors = tabs.first { it.id == tab }.anchors
        pressUntil(tab) { anchors.any { findView(it)?.isShown == true } }
    }

    /**
     * Press a tab until its screen answers, and keep what every press actually did.
     *
     * A single throw-away `getOrNull()` would describe the state after the last attempt only; three presses
     * that each failed differently is the evidence a reader needs to tell a stuck destination from a screen
     * whose anchor view simply is not on the window.
     */
    private fun pressUntil(tab: Int, settled: () -> Boolean) {
        val attempts = ArrayList<String>()
        for (attempt in 1..TAB_ATTEMPTS) {
            val outcome = runCatching { onView(withId(tab)).perform(click()) }
            val ok = outcome.isSuccess && waitUntil("the tab settles", 8_000L, settled)
            val selection = runCatching { requireBar().selectedItemId }.getOrNull()
            val drawn = tabs.first { it.id == tab }.anchors.count { findView(it)?.isShown == true }
            attempts += "press $attempt: click ${if (outcome.isSuccess) "landed" else "failed (${outcome.exceptionOrNull()?.message})"}, " +
                "selection ${nameOf(selection)}, destination ${nameOf(currentDestination())}, " +
                "$drawn of ${tabs.first { it.id == tab }.anchors.size} anchor views shown"
            if (ok) return
        }
        throw AssertionError(
            "pressing '${context.getString(tabs.first { it.id == tab }.titleRes)}' did not bring its screen up " +
                "in $TAB_ATTEMPTS presses:\n  " + attempts.joinToString("\n  ")
        )
    }

    private fun nameOf(id: Int?): String =
        tabs.firstOrNull { it.id == id }?.let { context.getString(it.titleRes) } ?: "[$id]"

    private fun waitUntil(
        description: String,
        millis: Long = WAIT_MILLIS,
        condition: () -> Boolean
    ): Boolean {
        // Named in the system trace as well as in the failure message: when a run spends its whole budget
        // waiting, `dumpsys gfxinfo` and the trace are where the reader finds out what it was waiting for.
        Trace.beginSection("nav waiting for $description")
        try {
            val deadline = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < deadline) {
                if (runCatching(condition).getOrDefault(false)) return true
                Thread.sleep(50)
            }
            return false
        } finally {
            Trace.endSection()
        }
    }

    private fun itemViews(bar: BottomNavigationView): List<ViewGroup> {
        val menuView = bar.menuView as ViewGroup
        return (0 until menuView.childCount).map { menuView.getChildAt(it) as ViewGroup }
    }

    /**
     * The five labels the reader is offered, in tab order, found by asking each item for the text view that
     * carries its own menu title.
     *
     * Taking "the first visible TextView in the item" would be a guess: it happens to be the label today, and
     * if the library ever puts anything else inside the item — a badge, a counter, a state-layer view with a
     * description — the test would assert against that and pass while the real label went dark. Matching on
     * the title pins it to the thing the stage's claim is about, and an item with two such views is reported
     * rather than quietly picked.
     */
    private fun labelsOn(bar: BottomNavigationView): List<TextView> =
        itemViews(bar).mapIndexed { index, item ->
            val title = bar.menu.getItem(index).title?.toString().orEmpty()
            val carrying = ArrayList<TextView>()
            fun walk(view: View) {
                if (view is TextView && view.text.toString() == title) carrying += view
                if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
            }
            walk(item)
            // Material builds every tab with two label views and shows one of them depending on the label
            // mode. Measured on the device with `labelVisibilityMode=labeled`, both views still hold the
            // word and only one of them is on screen, so the question the stage asks — is this tab's name
            // shown — is answered by the shown one, and the count of candidates goes into the message rather
            // than into a failure that means "this library has two labels".
            val shown = carrying.filter { it.isShown && it.width > 0 && it.height > 0 }
            assertEquals(
                "tab $index ('${title}') shows ${shown.size} of ${carrying.size} text views that carry its " +
                    "word; the bar labels every tab exactly once. The candidates are " +
                    carrying.joinToString { "${it.width}x${it.height} vis=${it.visibility} shown=${it.isShown}" },
                1,
                shown.size
            )
            shown.single()
        }

    /** The item view a label belongs to: the ancestor that is a direct child of the bar's menu view. */
    private fun rowOf(view: View): View {
        val menuView = requireBar().menuView as ViewGroup
        var current: View = view
        while (current.parent !== menuView && current.parent is View) {
            current = current.parent as View
        }
        return current
    }

    /**
     * The tab's own icon view.
     *
     * `firstImageView` would answer with a badge's ImageView the moment the menu started using one, and the
     * plate sample would then be taken beside the wrong glyph and still pass. So this asks for exactly one
     * image view that is drawing something, and reports what it found when the count is not one.
     */
    private fun iconOf(item: View, index: Int): ImageView {
        val found = ArrayList<ImageView>()
        fun walk(view: View) {
            if (view is ImageView && view.width > 0 && view.drawable != null) found += view
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(item)
        assertEquals(
            "tab $index has ${found.size} image views that are drawing something, and the plate can only be " +
                "sampled beside the one that is the icon: " +
                found.joinToString { "${it.width}x${it.height} at ${it.measuredWidth}" },
            1,
            found.size
        )
        return found.single()
    }

    /**
     * Where a view really is on the screen, in the coordinate space a screenshot uses.
     *
     * `getGlobalVisibleRect` is the tempting call and the wrong one: under a recording canvas it answers with
     * the *content* bounds, so every region derived from it is smaller than the view the reader sees, and a
     * pixel that landed in a corner would be silently outside the claim.
     */
    private fun rectOnScreen(view: View): Rect {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun colour(id: Int): Int = ContextCompat.getColor(context, id)

    /**
     * The colour a theme role answers with — what the bar paints, not what a token is named.
     *
     * Asked of the activity's theme and not of `targetContext`'s. The application context carries the
     * `<application>` theme, and this app declares `Theme.TaskPlanner` on the activity, so a Material role
     * looked up on the application answers with nothing at all: the first device run of this file failed with
     * "the theme answers no 2130903344" on `colorSurfaceContainer` while the bar on screen painted it.
     */
    private fun attrColour(attr: Int): Int {
        var answered = false
        var resolved: Int? = null
        scenario.onActivity { activity ->
            val out = TypedValue()
            answered = activity.theme.resolveAttribute(attr, out, true)
            if (answered) {
                resolved = if (out.type in TypedValue.TYPE_INT_COLOR_ARGB8..TypedValue.TYPE_INT_COLOR_RGB4) {
                    out.data
                } else {
                    ContextCompat.getColor(activity, out.resourceId)
                }
            }
        }
        assertTrue("the activity's theme answers no attribute $attr", answered)
        return resolved!!
    }

    /** Is a sampled pixel this colour, allowing for blending at the edges of a 1dp line? */
    private fun near(actual: Int, expected: Int): Boolean =
        max(
            max(
                abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF)),
                abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF))
            ),
            abs((actual and 0xFF) - (expected and 0xFF))
        ) <= CHANNEL_TOLERANCE

    private fun hex(colour: Int): String = String.format("#%08X", colour)

    private fun pixelAt(bitmap: Bitmap, y: Int, x: Int): Int = bitmap.getPixel(x, y)

    /** WCAG 2.1's relative luminance and the ratio between two opaque pixels. */
    private fun luminance(pixel: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(pixel shr 16 and 0xFF) +
            0.7152 * channel(pixel shr 8 and 0xFF) +
            0.0722 * channel(pixel and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun Double.format(): String = String.format("%.2f", this)

    /**
     * The share of a row's columns that answer to [predicate], sampled every three pixels.
     *
     * A fraction rather than a count, so a failure can say "4 of 500 columns are the rule" instead of leaving
     * the reader to work out what the number meant.
     */
    private fun shareOfRow(bitmap: Bitmap, y: Int, left: Int, right: Int, predicate: (Int) -> Boolean): Float {
        var hits = 0
        var columns = 0
        var x = left
        while (x < right) {
            columns++
            if (predicate(pixelAt(bitmap, y, x))) hits++
            x += 3
        }
        return if (columns == 0) 0f else hits.toFloat() / columns
    }

    /**
     * Take a screenshot, and if the platform will not hand one over say whose failure that is.
     *
     * A refused shutter looks exactly like a blank screen to whoever reads the report next, and the two need
     * opposite fixes: one is the harness, the other is the app. The message says which.
     */
    private fun photographed(what: String): Bitmap {
        var refusal: Throwable? = null
        repeat(SHUTTER_ATTEMPTS) {
            val outcome = runCatching { instrumentation.uiAutomation.takeScreenshot() }
            outcome.getOrNull()?.let { return it }
            refusal = outcome.exceptionOrNull()
            Thread.sleep(500)
        }
        throw AssertionError(
            "CAPTURE FAILED, not a screen defect: the platform refused to photograph $what on all " +
                "$SHUTTER_ATTEMPTS attempts (" + (refusal?.javaClass?.simpleName ?: "no exception") + ": " +
                (refusal?.message ?: "no picture") + "). Nothing this class asserts about pixels has been " +
                "measured on this run."
        )
    }

    /**
     * Write one frame, and refuse to file it under a configuration the device does not report.
     *
     * Two things are checked before the file is written: that the bar is wholly inside the frame — a bar whose
     * bottom was cut off would still answer a luminance question from the part that survived — and that the
     * region holds enough contrast to be a drawn bar at all.
     */
    private fun photograph(name: String, bar: View) {
        val tag = requireConfigurationMatchesTag()
        val bitmap = photographed(name)
        val region = rectOnScreen(bar)
        assertTrue(
            "the bar is not wholly inside the frame '$name': it is at $region and the picture is " +
                "${bitmap.width}x${bitmap.height}, so what is being measured is a crop",
            region.left >= 0 && region.top >= 0 && region.right <= bitmap.width && region.bottom <= bitmap.height
        )
        assertEquals(
            "the photographed bar is ${region.height()}px tall and the view is ${bar.height}px; part of it is " +
                "off the screen",
            bar.height,
            region.height()
        )
        val spread = luminanceSpread(bitmap, region)
        assertTrue(
            "the frame '$name' is not a drawn bar: inside $region the brightest pixel is $spread levels " +
                    "above the darkest, and five words over a plate span far more",
            spread >= 90
        )
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            "shots/$tag"
        )
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
    }

    private fun luminanceSpread(bitmap: Bitmap, region: Rect): Int {
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        var brightest = 0
        var darkest = 255
        for (pixel in pixels) {
            val luminance = (
                0.2126 * (pixel shr 16 and 0xFF) +
                    0.7152 * (pixel shr 8 and 0xFF) +
                    0.0722 * (pixel and 0xFF)
                ).roundToInt()
            if (luminance > brightest) brightest = luminance
            if (luminance < darkest) darkest = luminance
        }
        return brightest - darkest
    }

    /**
     * Append a measured number to the frame directory.
     *
     * Ratios are what the stage's acceptance criteria are written in, and the only honest place to read them
     * from is the frame the reader will be shown. Written per tag, so a matrix run leaves one file per
     * configuration next to the frames it measured.
     */
    private fun writeMeasurements(vararg lines: String) {
        val tag = InstrumentationRegistry.getArguments().getString("tag", "393-light-10")
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            "shots/$tag"
        )
        directory.mkdirs()
        File(directory, "measurements-$tag.txt").appendText(lines.joinToString(separator = "\n") + "\n")
    }

    /** The tag the script passed, checked against what the device says it is right now. */
    private fun requireConfigurationMatchesTag(): String {
        val tag = InstrumentationRegistry.getArguments().getString("tag", "393-light-10")
        val parts = tag.split("-")
        assertEquals("the tag names a width, a theme and a font scale: got '$tag'", 3, parts.size)
        val metrics = context.resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).roundToInt()
        val night = (
            context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            ) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val font = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 10f, metrics
        ) / android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 10f, metrics
        )
        assertTrue(
            "'$tag' claims ${parts[0]}dp of width; the device reports ${widthDp}dp " +
                "(${metrics.widthPixels}px at ${metrics.densityDpi}dpi)",
            abs(widthDp - parts[0].toInt()) <= 3
        )
        assertEquals("'$tag' claims the ${parts[1]} theme; the device reports night=$night", parts[1] == "dark", night)
        assertEquals(
            "'$tag' claims a font scale of ${parts[2]} tenths; the device reports $font",
            parts[2].toFloat() / 10f,
            font,
            0.02f
        )
        return tag
    }
}
