package com.taskplanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.taskplanner.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The bottom bar as the design system configures it, laid out at a real width, and the contrast its colours
 * actually measure.
 *
 * `activity_main` cannot be inflated here — its `FragmentContainerView` carries `app:navGraph`, and building
 * a navigation host needs the activity, so the attempt fails inside `onInflate` before any view of the bar
 * exists. What a JVM *can* see is the widget the theme builds: `bottomNavigationStyle` points every
 * `BottomNavigationView` in the app at `Widget.TaskPlanner.BottomBar`, so a bare instance under the app theme
 * carries the same colours, the same plate and the same label appearance the screen will use. Two claims are
 * left to `BottomNavigationInstrumentedTest` because only a rendered window can answer them: that five labels
 * are on screen at once and none of them ellipsizes (`app:labelVisibilityMode` is a view attribute in
 * `activity_main`, not a style entry, and an ellipsis needs real text metrics), and that the plate and the
 * rule are painted rather than merely configured.
 *
 * Most of this is not an attribute read: each assertion asks the laid-out widget what it applied, or reads a
 * colour out of the resource table and computes a ratio from it. One test is an attribute read —
 * `the screen's own bar element is the one that labels every tab` — and it has to be, because the relation it
 * states (this view carries this mode) is resolved by the platform when the view is constructed, and the view
 * cannot be constructed here. The same exception is what let Stage 5's `labelFor` test and Stage 6's section-
 * id test read the compiled XML, and the device test says the same thing again in pixels.
 *
 * The contrast helpers are WCAG 2.1's own definitions, written out rather than borrowed, because the numbers
 * the resource comments quote have to be reproducible by a reader who has only this file and
 * `scripts/contrast_report.py`, the design system's gate over `docs/design/tokens.json`.
 */
@RunWith(RobolectricTestRunner::class)
class BottomNavigationStyleTest {

    private val tabs = listOf(
        R.id.navigation_tasks to R.string.title_tasks,
        R.id.navigation_calendar to R.string.title_calendar,
        R.id.navigation_categories to R.string.title_categories,
        R.id.navigation_statistics to R.string.title_statistics,
        R.id.navigation_profile to R.string.title_profile
    )

    private fun context(qualifier: String = "notnight"): Context {
        RuntimeEnvironment.setQualifiers(qualifier)
        return ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_TaskPlanner
        )
    }

    /** The bar the app's own theme builds, with the app's own menu, measured at a phone width. */
    private fun barAt(widthDp: Int, qualifier: String = "notnight"): BottomNavigationView {
        val c = context(qualifier)
        val bar = BottomNavigationView(c)
        bar.inflateMenu(R.menu.bottom_nav_menu)
        val px = (widthDp * c.resources.displayMetrics.density).toInt()
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(px / 2, View.MeasureSpec.AT_MOST)
        )
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        return bar
    }

    private fun dp(c: Context, value: Float): Int = (value * c.resources.displayMetrics.density).toInt()

    /**
     * The colour a theme attribute resolves to.
     *
     * Asked through the theme and not by name, because the claim is about what the bar paints. The attribute
     * ids come from `MaterialR`, not the app's `R`: `android.nonTransitiveRClass=true` keeps
     * `com.taskplanner.R.attr` to the resources this module declares, and Material's roles are not among them,
     * so `R.attr.colorSurfaceContainer` here is either a different id or no id at all — the same trap that
     * makes `app:colorSurfaceContainer` in a layout resolve when a caller writes it as a name and never when
     * it is looked up from the wrong class. The value is read from the `TypedValue` when the theme inlines it
     * and from the resource it points at when the theme names one, which is what lets the tests below speak
     * of `ink_*` roles by their own names.
     */
    private fun attr(c: Context, attr: Int): Int {
        val out = TypedValue()
        assertTrue("the theme answers no attribute $attr", c.theme.resolveAttribute(attr, out, true))
        return if (out.type in TypedValue.TYPE_INT_COLOR_ARGB8..TypedValue.TYPE_INT_COLOR_RGB4) {
            out.data
        } else {
            ContextCompat.getColor(c, out.resourceId)
        }
    }

    private fun items(bar: BottomNavigationView): List<ViewGroup> {
        val menuView = bar.menuView as ViewGroup
        assertEquals(
            "the bar built ${menuView.childCount} item views for ${tabs.size} menu entries",
            tabs.size,
            menuView.childCount
        )
        return (0 until menuView.childCount).map { menuView.getChildAt(it) as ViewGroup }
    }

    // -- the contrast arithmetic, WCAG 2.1 --

    private fun luminance(colour: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(colour shr 16 and 0xFF) +
            0.7152 * channel(colour shr 8 and 0xFF) +
            0.0722 * channel(colour and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun hex(colour: Int): String = String.format("#%08X", colour)

    // -- the tests -------------------------------------------------------------------------------

    @Test
    fun `the bar offers five destinations in the order the reader meets them`() {
        val c = context()
        val menu = barAt(393).menu
        assertEquals("the bar gained or lost a tab", tabs.size, menu.size())
        tabs.forEachIndexed { index, (id, titleRes) ->
            val item = menu.getItem(index)
            assertEquals("tab $index is not the destination the plan names", id, item.itemId)
            assertEquals("tab $index lost its word", c.getString(titleRes), item.title.toString())
        }
    }

    @Test
    fun `the chosen tab is tinted differently from the other four`() {
        val c = context("notnight")
        val bar = barAt(393)
        val ink = attr(c, MaterialR.attr.colorOnSurface)
        val secondary = attr(c, MaterialR.attr.colorOnSurfaceVariant)
        val checked = intArrayOf(android.R.attr.state_checked)

        val textTint = bar.itemTextColor
            ?: throw AssertionError("the bar applies no item text tint, so the theme says nothing about it")
        val iconTint = bar.itemIconTintList
            ?: throw AssertionError("the bar applies no item icon tint either")

        assertEquals(
            "a chosen tab's word is not `ink_text`: " + hex(textTint.getColorForState(checked, 0)),
            ink,
            textTint.getColorForState(checked, 0)
        )
        assertEquals(
            "an unchosen tab's word is not `ink_text_secondary`: " + hex(textTint.getColorForState(EMPTY, 0)),
            secondary,
            textTint.getColorForState(EMPTY, 0)
        )
        assertEquals("a chosen icon is not `ink_text`", ink, iconTint.getColorForState(checked, 0))
        assertEquals(
            "an unchosen icon is not `ink_text_secondary`",
            secondary,
            iconTint.getColorForState(EMPTY, 0)
        )
        assertNotEquals(
            "text and icon tints do not change with the checked state at all, so colour cannot carry the " +
                "selection and the bar would rely on the plate alone",
            textTint.getColorForState(checked, 0),
            textTint.getColorForState(EMPTY, 0)
        )
        // Both roles have to clear AA as text on the surface the bar paints, or the redesign would have
        // traded a bar nobody could read for a bar nobody could tell apart.
        val surface = attr(c, MaterialR.attr.colorSurfaceContainer)
        assertTrue(
            "the chosen word measures ${contrast(ink, surface).format()} against the bar's own surface",
            contrast(ink, surface) >= 4.5
        )
        assertTrue(
            "the unchosen word measures ${contrast(secondary, surface).format()} against the bar's surface; " +
                "1.4.3 asks 4.5 of text a reader has to compare",
            contrast(secondary, surface) >= 4.5
        )
    }

    /**
     * `nav_item_icon_ink.xml` is the library's selector with one entry re-pointed, not a shorter list.
     *
     * A two-state selector is the easy mistake here — it says the same thing about the chosen tab and drops
     * the six entries that make a trackpad hover or a D-pad focus answer with anything at all.
     */
    @Test
    fun `the icon tint keeps the library's pressed, focused and hovered entries`() {
        val c = context()
        val bar = barAt(393)
        val ink = attr(c, MaterialR.attr.colorOnSurface)
        val secondary = attr(c, MaterialR.attr.colorOnSurfaceVariant)
        val iconTint = bar.itemIconTintList ?: throw AssertionError("no icon tint on the bar")

        for (state in listOf(android.R.attr.state_pressed, android.R.attr.state_focused, android.R.attr.state_hovered)) {
            val focusedOnly = iconTint.getColorForState(intArrayOf(state), 0)
            assertEquals(
                "an unchosen tab that is $state takes ${hex(focusedOnly)} instead of the foreground ink the " +
                    "library's own selector takes — the state entry has been dropped from the override",
                ink,
                focusedOnly
            )
            assertNotEquals(
                "an unchosen tab looks the same whether it is $state or untouched, so the bar answers to " +
                    "no pointer or keyboard focus at all",
                secondary,
                focusedOnly
            )
            val checkedAnd = iconTint.getColorForState(intArrayOf(android.R.attr.state_checked, state), 0)
            assertEquals("a chosen tab that is $state lost the ink", ink, checkedAnd)
        }
    }

    /**
     * The plate is a visible mark, not a tint.
     *
     * WCAG 1.4.11 asks 3:1 of the visual that tells a component's state, and 1.4.3 asks 4.5:1 of the word
     * that sits on it. The pill colour the chips use — `pill_accent`, 1.12:1 on the bar — fails the first, so
     * a plate that quiet would leave the ink pair as the only cue, which is the pattern the design spec
     * forbids ("цвет как единственный индикатор состояния").
     */
    @Test
    fun `the plate under the chosen tab is visible against the bar and under the ink`() {
        assertPlateIsAStateMarker("notnight")
    }

    /**
     * The same three claims read from the night palette, as its own method.
     *
     * Both themes used to be two turns of one loop, and that is not free: `setQualifiers` rebuilds what the
     * theme resolves, while a colour-state list the widget loaded on the first turn stays loaded. CI found the
     * asymmetry in `the icon override earns its place …`, where the night turn compared a freshly resolved
     * night token against a tint list still holding the light value — `expected:<#FFF2EDE7> but was:<#FF1A1614>`
     * on a Linux runner, green on Windows. A verdict that depends on which configuration a resource cache saw
     * first is not a verdict, so each configuration now gets its own method and its own cold environment.
     */
    @Config(qualifiers = "night")
    @Test
    fun `the plate under the chosen tab is visible against the bar and under the ink, at night`() {
        assertPlateIsAStateMarker("night")
    }

    private fun assertPlateIsAStateMarker(qualifier: String) {
        val c = context(qualifier)
        val surface = attr(c, MaterialR.attr.colorSurfaceContainer)
        val plate = ContextCompat.getColor(c, R.color.ink_nav_plate)
        val ink = attr(c, MaterialR.attr.colorOnSurface)
        assertTrue(
            "$qualifier: the plate ${hex(plate)} is ${contrast(plate, surface).format()} from the bar's " +
                "surface ${hex(surface)}; 1.4.11 asks 3:1 of a state marker",
            contrast(plate, surface) >= 3.0
        )
        assertTrue(
            "$qualifier: the chosen word ${hex(ink)} on the plate ${hex(plate)} measures " +
                "${contrast(ink, plate).format()}; 1.4.3 asks 4.5:1",
            contrast(ink, plate) >= 4.5
        )
        val bar = barAt(393, qualifier)
        assertTrue(
            "$qualifier: the bar has no active indicator at all, so the plate is not a cue the reader " +
                "can be given",
            bar.isItemActiveIndicatorEnabled
        )
    }

    /**
     * The plate is big enough to contain the glyph it is drawn behind.
     *
     * `getDimension` already answers in pixels, so the widget's own numbers are compared as pixels — wrapping
     * them in `dp()` a second time multiplies by the density twice and is invisible at Robolectric's 1.0,
     * which is how the first draft of this file passed a wrong geometry. The first draft's geometry was
     * 40dp x 24dp behind a 24dp icon, and the shape is a stadium whose ends are semicircles of radius
     * height/2 = 12dp: at the icon's own left and right edges the plate has no height left, so the corners of
     * a squared-off glyph like `ic_task` fall outside it. What the design owes the reader is a plate with room
     * on every side of the glyph, so the assertions are about room and not about a magic width: 8dp of plate
     * beside each side of the icon, and 4dp above and below it. `icon_container` and `spacing_large` — the two
     * tokens the first draft named — are row metrics from Stage 1's list and calendar grids, not this widget's
     * sizes, and neither is re-declared here any more.
     */
    @Test
    fun `the plate has room around the glyph it is drawn behind`() {
        val c = context()
        val bar = barAt(393)
        assertTrue(
            "the bar has no active indicator, so the chosen tab is told by colour alone",
            bar.isItemActiveIndicatorEnabled
        )
        val icon = bar.itemIconSize
        assertEquals(
            "the glyph is not the library's 24dp any more, and every margin below is measured against it",
            dp(c, 24f),
            icon
        )
        assertTrue(
            "the plate is ${bar.itemActiveIndicatorWidth}px wide around a ${icon}px glyph: the design asks at " +
                "least 8dp of plate beside each side of the icon, and a stadium narrower than that clips the " +
                "glyph's corners",
            bar.itemActiveIndicatorWidth - icon >= 2 * dp(c, 8f)
        )
        assertTrue(
            "the plate is ${bar.itemActiveIndicatorHeight}px tall around a ${icon}px glyph: with no room above " +
                "and below, the rounded ends cut across the icon instead of sitting behind it",
            bar.itemActiveIndicatorHeight - icon >= 2 * dp(c, 4f)
        )
        assertEquals(
            "the plate's inset from the item's edges is not the library's 4dp, which is the gap that keeps " +
                "two neighbouring plates from touching on a 320dp screen",
            dp(c, 4f),
            bar.itemActiveIndicatorMarginHorizontal
        )
        assertTrue(
            "the plate is ${bar.itemActiveIndicatorHeight}px tall in a ${bar.height}px bar",
            bar.itemActiveIndicatorHeight in 1..bar.height
        )
        // And the plate has to fit inside a tab, which at the narrowest supported width is a 64dp slot with
        // 4dp inset on each side: a plate wider than that is a plate that touches its neighbour.
        val narrow = barAt(320)
        val slot = narrow.width / tabs.size
        assertTrue(
            "at 320dp each tab owns ${slot}px and the plate is ${narrow.itemActiveIndicatorWidth}px; the " +
                "library insets it by ${narrow.itemActiveIndicatorMarginHorizontal}px on each side, so the " +
                "widest it can draw is ${slot - 2 * narrow.itemActiveIndicatorMarginHorizontal}px",
            narrow.itemActiveIndicatorWidth - 2 * narrow.itemActiveIndicatorMarginHorizontal <= slot
        )
    }

    /**
     * The tint between the bar and the page is not the boundary — the drawn line is.
     *
     * The stage's brief asks the bar's background to sit 1.3:1 from `android:colorBackground` and, in the same
     * section, asks that background to be `?attr/colorSurfaceContainer`. This theme's answer to that role is
     * `ink_surface` (#FFFFFF light, #1A1817 night) against `ink_background` (#FBF9F6 / #12100F), and the pair
     * measures 1.05:1 and 1.07:1: the two requirements cannot both hold without repainting every other
     * surface in the app, which is not this stage's scope. So the separation is drawn instead — 1dp of
     * `ink_outline_strong` inside the bar's own background — and this test keeps the deviation honest: it
     * pins the tint below the 1.3 the brief asks for, so the day a token moves enough to satisfy the number
     * is the day this file has to say so, and it asserts the line clears 3:1 against both sides it touches.
     */
    @Test
    fun `the bar's surface tint is not a boundary and the drawn rule is`() {
        assertTintIsNotTheBoundaryAndTheRuleIs("notnight")
    }

    @Config(qualifiers = "night")
    @Test
    fun `the bar's surface tint is not a boundary and the drawn rule is, at night`() {
        assertTintIsNotTheBoundaryAndTheRuleIs("night")
    }

    private fun assertTintIsNotTheBoundaryAndTheRuleIs(qualifier: String) {
        val c = context(qualifier)
        val surface = attr(c, MaterialR.attr.colorSurfaceContainer)
        val page = attr(c, android.R.attr.colorBackground)
        val rule = ContextCompat.getColor(c, R.color.ink_outline_strong)
        val tint = contrast(surface, page)
        assertTrue(
            "$qualifier: the bar's surface and the page now measure ${tint.format()} apart, which is the " +
                "1.3:1 the brief asks of the tint alone. The assertion below the bar's background is now " +
                "redundant: delete the rule from `bg_bottom_bar` and let the tint do the work, and update " +
                "docs/UI_UX_AUDIT.md and the plan's Stage 7 deviation note.",
            tint < 1.3
        )
        assertTrue(
            "$qualifier: the rule ${hex(rule)} is ${contrast(rule, page).format()} from the page it ends " +
                "against; a 1dp line a reader must find needs 3:1 (1.4.11)",
            contrast(rule, page) >= 3.0
        )
        assertTrue(
            "$qualifier: the rule is ${contrast(rule, surface).format()} from the bar it sits in",
            contrast(rule, surface) >= 3.0
        )
    }

    /**
     * The two themes are two themes.
     *
     * Every night test above reads its colours through a night context, which would still pass if the dark
     * column were a copy of the light one. This is the check that they are not.
     */
    @Test
    fun `the night bar is drawn from a different palette and not the light one`() {
        // Every value of one theme is read before the other theme is asked for. `setQualifiers` rebuilds the
        // application's resources, and a `ContextThemeWrapper` around it keeps pointing at whatever the last
        // call installed — so building both contexts first and reading them after would answer both questions
        // with the night palette, which is exactly how this test first failed.
        val light = context("notnight")
        val lightInk = ContextCompat.getColor(light, R.color.ink_text)
        val lightPlate = ContextCompat.getColor(light, R.color.ink_nav_plate)
        val lightSurface = attr(light, MaterialR.attr.colorSurfaceContainer)
        val night = context("night")
        val nightInk = ContextCompat.getColor(night, R.color.ink_text)
        val nightPlate = ContextCompat.getColor(night, R.color.ink_nav_plate)
        val nightSurface = attr(night, MaterialR.attr.colorSurfaceContainer)

        assertNotEquals(
            "values-night/colors.xml answers ink_text with the light value (${hex(lightInk)}), so the dark " +
                "theme is not a theme",
            lightInk,
            nightInk
        )
        assertNotEquals(
            "the night plate is the light plate (${hex(lightPlate)}): one of the two cannot clear 3:1 against " +
                "its own bar",
            lightPlate,
            nightPlate
        )
        assertNotEquals(
            "the night bar surface is the light bar surface (${hex(lightSurface)})",
            lightSurface,
            nightSurface
        )
    }

    @Test
    fun `every tab is a target a finger can hit`() {
        val c = context()
        val bar = barAt(393)
        val floor = dp(c, 48f)
        items(bar).forEachIndexed { index, item ->
            assertTrue(
                "tab $index is ${item.height}px tall at ${c.resources.displayMetrics.density}x density; " +
                    "a finger needs $floor",
                item.height >= floor
            )
            assertTrue(
                "tab $index is ${item.width}px wide; the touch floor is $floor in both directions",
                item.width >= floor
            )
        }
    }

    /**
     * Five tabs at 320dp, and the width each one owns.
     *
     * The bar's own width is what the test asked the measure spec for, so asserting it would prove nothing;
     * what the narrowest phone can lose is a tab's share of it. Below 48dp per tab the five destinations stop
     * being five targets, and Stage 4's card budget is the precedent for measuring that rather than trusting
     * the arithmetic.
     */
    @Test
    fun `the bar keeps its five item views at the narrowest width the app supports`() {
        val c = context()
        val bar = barAt(320)
        val rows = items(bar)
        assertEquals("the bar lost an item at 320dp", tabs.size, rows.size)
        val floor = dp(c, 48f)
        rows.forEachIndexed { index, item ->
            assertTrue(
                "at 320dp tab $index owns ${item.width}px of the bar's ${bar.width}; the touch floor is $floor",
                item.width >= floor
            )
        }
        assertTrue(
            "the five items together measure ${rows.sumOf { it.width }}px and the bar is ${bar.width}px; " +
                "a bar wider than its items has room nobody can press",
            rows.sumOf { it.width } <= bar.width
        )
        rows.forEach { item ->
            val labels = ArrayList<TextView>()
            fun walk(view: View) {
                if (view is TextView) labels += view
                if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
            }
            walk(item)
            assertTrue("a tab at 320dp carries no text view at all", labels.isNotEmpty())
        }
    }

    /**
     * The night bar wears the night pair.
     *
     * Declared with `@Config(qualifiers = "night")` rather than switched to inside the method: the widget's
     * colour-state lists are loaded once and cached, so a method that builds a light bar earlier in the same
     * JVM — and several do — can hand this one a tint list from the other palette. That is how CI caught
     * `the icon override earns its place …` failing on Linux while passing on Windows.
     */
    @Config(qualifiers = "night")
    @Test
    fun `the night bar keeps the same tint pair and the same plate`() {
        val c = context("night")
        val bar = barAt(393, "night")
        val ink = attr(c, MaterialR.attr.colorOnSurface)
        val secondary = attr(c, MaterialR.attr.colorOnSurfaceVariant)
        val textTint = bar.itemTextColor ?: throw AssertionError("no text tint in the night theme")
        val iconTint = bar.itemIconTintList ?: throw AssertionError("no icon tint in the night theme")
        val checked = intArrayOf(android.R.attr.state_checked)
        assertEquals("night: a chosen tab is not `ink_text`", ink, textTint.getColorForState(checked, 0))
        assertEquals(
            "night: an unchosen tab is not `ink_text_secondary`",
            secondary,
            textTint.getColorForState(EMPTY, 0)
        )
        assertEquals("night: a chosen icon is not `ink_text`", ink, iconTint.getColorForState(checked, 0))
        assertTrue(
            "night: the bar has no active indicator, so nothing but colour tells the chosen tab",
            bar.isItemActiveIndicatorEnabled
        )
        // The night tokens are not the light ones: if a future edit points both themes at one colour, this
        // is where it shows — the pair has to differ from each other in the dark theme as well.
        assertNotEquals(
            "the two states of the night tint are the same colour",
            textTint.getColorForState(checked, 0),
            textTint.getColorForState(EMPTY, 0)
        )
    }

    /**
     * The icon override does something the library default could not.
     *
     * Every colour in `nav_item_icon_ink.xml` is already some other token's value in this theme — Material's
     * inactive pair `colorOnSurfaceVariant` is `@color/ink_text_secondary` (`themes.xml:24`) and its active
     * label `colorOnSurface` is `@color/ink_text` (`themes.xml:23`) — so the tint assertions above are all
     * satisfiable by the library default, and a future edit that deleted the override file would still pass
     * them. What the override exists for is one entry: the library paints a chosen icon with
     * `colorOnSecondaryContainer`, which this theme answers with `ink_accent`, and §4.4 asks for the ink.
     * That difference is what this test pins, in both themes.
     */
    @Test
    fun `the icon override earns its place by differing from the library's own active colour`() {
        assertIconOverrideDiffersFromTheLibraryDefault("notnight")
    }

    @Config(qualifiers = "night")
    @Test
    fun `the icon override earns its place by differing from the library's own active colour, at night`() {
        assertIconOverrideDiffersFromTheLibraryDefault("night")
    }

    private fun assertIconOverrideDiffersFromTheLibraryDefault(qualifier: String) {
        val c = context(qualifier)
        val bar = barAt(393, qualifier)
        val ink = attr(c, MaterialR.attr.colorOnSurface)
        val secondary = attr(c, MaterialR.attr.colorOnSurfaceVariant)
        val libraryActiveIcon = attr(c, MaterialR.attr.colorOnSecondaryContainer)
        val iconTint = bar.itemIconTintList ?: throw AssertionError("$qualifier: the bar applies no icon tint")
        val checked = intArrayOf(android.R.attr.state_checked)

        assertNotEquals(
            "$qualifier: the chosen icon answers with the library's `colorOnSecondaryContainer` " +
                "(${hex(libraryActiveIcon)}), so `nav_item_icon_ink.xml` is either gone or not applied to " +
                "this widget",
            libraryActiveIcon,
            iconTint.getColorForState(checked, 0)
        )
        assertEquals("$qualifier: the chosen icon is not the foreground ink", ink, iconTint.getColorForState(checked, 0))
        // And the two states must not both be the accent either: a chosen and an unchosen icon in the same
        // accent is the flat bar the audit started from.
        assertNotEquals(
            "$qualifier: both icon states resolve to the same colour, so the icons cannot tell the tabs " +
                "apart",
            iconTint.getColorForState(checked, 0),
            iconTint.getColorForState(EMPTY, 0)
        )
        assertEquals("$qualifier: an unchosen icon is not the secondary ink", secondary, iconTint.getColorForState(EMPTY, 0))
    }

    /**
     * The duplicate text selector stays deleted.
     *
     * `color/nav_item_text_ink.xml` was the same pair Material already applied, with four fewer states; if it
     * comes back and the style names it again, the bar loses its pressed, focused and hovered colours while
     * every colour assertion above still passes. Checking that the resource is gone is the part a colour
     * comparison cannot do, because in this theme all three names are one colour.
     */
    @Test
    fun `the text roles are Material's own and the app duplicate is gone`() {
        val c = context()
        val orphan = c.resources.getIdentifier("nav_item_text_ink", "color", c.packageName)
        assertEquals(
            "`color/nav_item_text_ink.xml` came back: it re-declares `colorOnSurface`/`colorOnSurfaceVariant`, " +
                "which themes.xml:23-24 already map to `ink_text`/`ink_text_secondary`, and a two-state list " +
                "drops the four states the library's own selector carries",
            0,
            orphan
        )
        val bar = barAt(393)
        val library = librarySelector(c, "m3_navigation_bar_item_with_indicator_label_tint")
        val textTint = bar.itemTextColor ?: throw AssertionError("the bar applies no text tint")
        for (stateSet in listOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_hovered),
            EMPTY
        )) {
            assertEquals(
                "the text tint differs from the library's own selector for ${describe(stateSet)} — the style " +
                    "has started overriding a role it only meant to inherit",
                library.getColorForState(stateSet, 0),
                textTint.getColorForState(stateSet, 0)
            )
        }
    }

    /**
     * The two claims the component style cannot carry, read off the layout that does.
     *
     * `app:labelVisibilityMode` is a view attribute in `activity_main.xml`, not an entry in
     * `Widget.TaskPlanner.BottomBar`, so the standalone widget in this file keeps the library's `AUTO` however
     * the screen is written — which is exactly the shape of Stage 5's `labelFor` test: a relation the platform
     * resolves when a service asks for the node, and that Robolectric never asks about. The background is the
     * other half: the bar's surface and its rule travel together as one drawable, and a second view wearing
     * that drawable would put two lines on the screen.
     */
    @Test
    fun `the screen's own bar element is the one that labels every tab`() {
        val c = context()
        val elements = elements(c, R.layout.activity_main)
        val bar = elements.filter { it["id"] == "bottom_navigation" }
        assertEquals(
            "`activity_main` has ${bar.size} views called `bottom_navigation`; MainActivity's " +
                "findViewById(R.id.bottom_navigation).setupWithNavController(...) wires one, and the other " +
                "would be a bar nobody navigates with. Elements: ${elements.map { it.tag to it["id"] }}",
            1,
            bar.size
        )
        val mode = bar.single()["labelVisibilityMode"]
            ?: throw AssertionError(
                "the screen's bar declares no labelVisibilityMode at all, so it inherits the library's AUTO, " +
                    "which with five destinations labels only the chosen one — the attribute the stage's own " +
                    "brief names, and the one the standalone widget in this file cannot see. " +
                    "What the bar element carries: ${bar.single()}"
            )
        assertEquals(
            "the bar's labelVisibilityMode is '$mode', which is not `labeled`",
            NavigationBarView.LABEL_VISIBILITY_LABELED,
            labelVisibilityModeOf(mode)
        )
        assertEquals(
            "the bar's background is '${bar.single()["background"]}' rather than `bg_bottom_bar`: the surface " +
                "and the rule that separates it are one drawable, and this is the only place the stage claims " +
                "the surface role the brief asks for",
            "bg_bottom_bar",
            entryName(c, bar.single()["background"].orEmpty())
        )
        val wearers = elements.filter { entryName(c, it["background"].orEmpty()) == "bg_bottom_bar" }
        assertEquals(
            "${wearers.size} views in `activity_main` paint `bg_bottom_bar`; one rule is the boundary, and a " +
                "second is a line nobody asked for",
            1,
            wearers.size
        )
        val host = elements.single { it["id"] == "nav_host_fragment" }
        assertEquals(
            "the navigation host stops at ${host["layout_constraintBottom_toTopOf"]} instead of at the bar, so " +
                "the bar either overlaps a screen or the content runs under it",
            "bottom_navigation",
            host["layout_constraintBottom_toTopOf"]
        )
    }

    /**
     * Five tabs, five destinations the graph can actually reach.
     *
     * `setupWithNavController` matches a menu item to a destination by resource id and silently does nothing
     * for an id it cannot find — the failure mode is a tab that looks pressed and a screen that never changes,
     * and it is invisible to every colour and pixel test in this file. The graph is the other half of that
     * pair, so the pair is asserted here; the device test walks the tabs and reads the controller to prove the
     * wiring runs.
     */
    @Test
    fun `every tab names a destination the graph can reach`() {
        val c = context()
        val menu = elements(c, R.menu.bottom_nav_menu).filter { it["id"] != null }
        assertEquals("the menu offers ${menu.size} items for five tabs", tabs.size, menu.size)
        val destinations = elements(c, R.navigation.nav_graph).filter { it["name"] != null }
        tabs.forEachIndexed { index, (id, _) ->
            val name = c.resources.getResourceName(id).substringAfterLast('/')
            assertEquals("menu item $index is not $name", name, menu[index]["id"])
            val destination = destinations.firstOrNull { it["id"] == name }
            assertTrue(
                "'$name' is a tab with no destination in nav_graph; the bar would press and nothing would " +
                    "change. Destinations here: ${destinations.mapNotNull { it["id"] }}",
                destination != null
            )
            assertTrue(
                "'$name' exists in the graph but names no fragment: ${destination!!["name"]}",
                destination["name"]!!.contains("Fragment")
            )
        }
    }

    /**
     * The bar's height is what its content makes it.
     *
     * `activity_main.xml:80` is `layout_height="wrap_content"`, so the row the labels sit in is the whole bar
     * and nothing pads it: `NavigationBarItemView.onMeasure` adds `paddingTop + paddingBottom` to the row
     * height, and the bar's own top padding is where a 1dp rule *would* have lived if Stage 7 had drawn one as
     * a sibling. If a later edit puts padding there, the five rows stop filling the bar and this is where it
     * surfaces.
     */
    @Test
    fun `the bar's height belongs to its rows and not to an inset`() {
        val bar = barAt(393)
        assertEquals(
            "the bar pads itself by ${bar.paddingTop}px at the top; the row fills the bar today, and padding " +
                "here would push the labels out of the region the device test samples",
            0,
            bar.paddingTop
        )
        items(bar).forEachIndexed { index, item ->
            assertEquals(
                "tab $index is ${item.height}px in a ${bar.height}px bar, so something other than the rows " +
                    "owns the bar's height",
                bar.height,
                item.height
            )
        }
    }

    private fun Double.format(): String = String.format("%.2f", this)

    // -- reading the compiled resources ---------------------------------------------------------

    /**
     * One element of a compiled XML resource, with its attribute values already resolved to entry names.
     *
     * AAPT2 hands references to the parser as numbers — `@+id/bottom_navigation` arrives as `@2131296848` —
     * and a test that compares against a name is the only form that survives the ids being renumbered.
     */
    private class Element(val tag: String, private val c: Context, private val raw: Map<String, String?>) {
        operator fun get(key: String): String? = raw[key]?.let { entryName(c, it) }
        override fun toString(): String = "<$tag $raw>"
    }

    private fun elements(c: Context, xmlRes: Int): List<Element> {
        val found = ArrayList<Element>()
        c.resources.getXml(xmlRes).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val attrs = (0 until parser.attributeCount).associate { index ->
                        parser.getAttributeName(index).substringAfterLast('.') to parser.getAttributeValue(index)
                    }
                    found += Element(tag = parser.name, c = c, raw = attrs)
                }
                parser.next()
            }
        }
        return found
    }

    /**
     * The value of `app:labelVisibilityMode`, whether the compiler kept the word or the number.
     *
     * The enum in Material's `attrs.xml` is auto=-1, selected=0, labeled=1, unlabeled=2, and
     * `NavigationBarView`'s own constants are the same four; a compiled resource may report either form, so
     * both are read and an unrecognised one falls through to `Int.MIN_VALUE` and fails the caller's
     * comparison with the raw text in its message.
     */
    private fun labelVisibilityModeOf(raw: String): Int {
        // `@`, `+` and `$` are the prefixes the compiled form and the resource-name form each use for a
        // reference; `labeled` is how the source writes it and `@1` is how AAPT2 leaves it in the APK, so the
        // claim has to survive whichever one the tool last touched left behind.
        val body = raw.removePrefix("@").removePrefix("+").removePrefix("$")
        body.toIntOrNull()?.let { return it }
        if (body.startsWith("0x")) return body.drop(2).toIntOrNull(16) ?: Int.MIN_VALUE
        return NAMES_TO_MODES[body] ?: Int.MIN_VALUE
    }

    /**
     * The library's own selector, by the name the library gives it.
     *
     * Comparing the bar's tint with this is what lets a test say "the style inherits" rather than "the style
     * happens to produce the same colour as" — in this theme the two are otherwise indistinguishable.
     */
    private fun librarySelector(c: Context, name: String): ColorStateList {
        val id = c.resources.getIdentifier(name, "color", c.packageName)
        assertTrue(
            "the library's `$name` colour selector is not in the merged resource table, so the comparison " +
                "this test makes has no second side",
            id != 0
        )
        return c.resources.getColorStateList(id, c.theme)
    }

    private fun describe(states: IntArray): String =
        if (states.isEmpty()) "no state" else states.joinToString(",") { STATE_NAMES[it] ?: "?${it}" }

    private companion object {
        val EMPTY = IntArray(0)

        val NAMES_TO_MODES = mapOf("auto" to -1, "selected" to 0, "labeled" to 1, "unlabeled" to 2)

        val STATE_NAMES = mapOf(
            android.R.attr.state_checked to "state_checked",
            android.R.attr.state_pressed to "state_pressed",
            android.R.attr.state_focused to "state_focused",
            android.R.attr.state_hovered to "state_hovered",
            android.R.attr.state_selected to "state_selected",
            android.R.attr.state_enabled to "state_enabled",
        )
    }
}

/**
 * `@id/x`, `@+id/x`, `@drawable/x` and the compiled numeric form all name one entry: this returns the name.
 *
 * `$` is stripped because that is the form Robolectric's compiled parser hands back for a private resource
 * reference, and an id read through it would otherwise come out as `$123` — a name no view has.
 */
private fun entryName(c: Context, reference: String): String? {
    val body = reference.removePrefix("@").removePrefix("+").removePrefix("$")
    if (body.all { it.isDigit() } && body.isNotEmpty()) {
        return runCatching {
            c.resources.getResourceName(body.toInt()).substringAfterLast('/')
        }.getOrNull() ?: body
    }
    return if (body.isEmpty()) null else body.substringAfterLast('/')
}
