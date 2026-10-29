package com.taskplanner.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The grep Stage 8a is told to run after every layout, promoted to a gate.
 *
 * This is deliberately a source-text test and not a behaviour test: what it protects is not what the user
 * sees but what the next commit is allowed to reintroduce. Stage 8a changes fourteen files, and
 * the failure mode the plan names for it is silent visual regression — a `?android:attr/textColorSecondary`
 * copied back in from a neighbouring screen, one `16dp` typed instead of `@dimen/spacing_normal`, one
 * `Widget.MaterialComponents.Button.OutlinedButton` pasted from an older file. No colour assertion and no
 * screenshot review catches those at the moment they happen, and by the time a photograph shows a different
 * screen the diff is four files wide.
 *
 * The rules are the substitution table in the plan's Stage 8 header, read as prohibitions:
 *
 *  * the platform's text-colour attributes — the theme answers them, and the answer is not this design system;
 *  * `Widget.MaterialComponents.*` styles — Material 2 widgets inside a Material 3 theme is audit finding 4.5;
 *  * the Material 2 text-appearance aliases (`Caption`, `Body1`, `Body2`, `Subtitle1`, `Subtitle2`,
 *    `Headline6`) — each has a 1.11 successor in the table;
 *  * `@dimen/text_*` and `@color/status_*` / `@color/red*` — Stage 1's roles and tokens replaced them, and
 *    `@dimen/text_*` is only allowed to outlive its last reader, which is `fragment_report.xml` in 8d;
 *  * a literal `4dp`, `8dp`, `12dp`, `16dp`, `20dp` or `24dp` in any padding or margin — all six have
 *    `spacing_*` tokens, so typing one is a decision to stop using the system;
 *  * a literal `app:cardElevation` — elevation belongs to `Widget.TaskPlanner.Card`.
 *
 * `1dp` and `2dp` are allowed on purpose: they are hairlines and optical nudges with no token, and forbidding
 * them would push a contributor to invent a token that means "about two pixels".
 */
@RunWith(RobolectricTestRunner::class)
class Stage8aTokenSweepTest {

    private val screens = listOf(
        "fragment_categories.xml",
        "item_category.xml",
        "fragment_category_detail.xml",
        "fragment_category_tasks.xml",
        "fragment_archive.xml",
        "item_archive.xml",
        "fragment_work_history.xml",
        "item_work_interval.xml",
        "item_history_interval.xml",
        "item_work_history.xml",
        "fragment_settings.xml",
        "fragment_profile_selection.xml",
        "item_profile.xml",
        "dialog_profile_edit.xml"
    )

    private val banned = listOf(
        "?android:attr/textColor" to "the platform's text colour, not `?attr/colorOnSurfaceVariant`",
        "Widget.MaterialComponents." to "a Material 2 style in a Material 3 theme (audit 4.5)",
        "@dimen/text_" to "an old type dimen instead of a TextAppearance role",
        "@color/status_" to "a retired status colour instead of `ink_warning` / `ink_success`",
        "@color/red" to "a raw red instead of `ink_error`",
        "@color/on_surface" to "the Stage 0 name for the surface ink",
        "@color/error" to "the Stage 0 name for the error ink",
        "textAppearanceCaption" to "`?attr/textAppearanceBodySmall` replaced it",
        "textAppearanceBody1" to "`?attr/textAppearanceBodyMedium` replaced it (the theme maps that to `Body`)",
        "textAppearanceBody2" to "`?attr/textAppearanceBodySmall` replaced it (the theme maps that to `Meta`)",
        "textAppearanceSubtitle1" to "`?attr/textAppearanceTitleMedium` replaced it",
        "textAppearanceSubtitle2" to "`?attr/textAppearanceLabelLarge` replaced it",
        "textAppearanceHeadline6" to "`?attr/textAppearanceTitleLarge` replaced it",
        "app:cardElevation=\"" to "elevation belongs to `Widget.TaskPlanner.Card`"
    )

    /**
     * `(android:)?(padding|margin)<side>="16dp"` and friends, with the six tokened values called out.
     *
     * `Horizontal` and `Vertical` are in the side group because they are the same decision wearing a different
     * attribute: a `paddingHorizontal="16dp"` is exactly as un-tokened as two `paddingStart`/`paddingEnd`
     * lines, and a sweep that matched only the per-side spellings would let the shorthand walk back in through
     * the door the long form was locked at.
     */
    private val literalSpacing = Regex(
        """android:(padding|layout_margin)(Start|End|Top|Bottom|Left|Right|Horizontal|Vertical)?="(\d+)dp""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The six literals the plan's table names, mapped to the token that already carries each value. `1dp` and
     * `2dp` are absent on purpose: a hairline and an optical nudge have no token, and inventing one that means
     * "about two pixels" would be a worse system than the number.
     */
    private val token = mapOf(
        "4" to "@dimen/spacing_xs",
        "8" to "@dimen/spacing_small",
        "12" to "@dimen/spacing_medium",
        "16" to "@dimen/spacing_normal",
        "20" to "@dimen/spacing_section",
        "24" to "@dimen/spacing_large"
    )

    private fun layoutOf(name: String): File {
        val relative = "src/main/res/layout/$name"
        val tried = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return tried.firstOrNull(File::exists)
            ?: throw AssertionError(
                "none of ${tried.map { it.path }} holds $name — the working directory of this test is not " +
                    "the module it was written for, so the sweep cannot say anything"
            )
    }

    @Test
    fun `the fourteen files carry no value the substitution table replaced`() {
        val findings = ArrayList<String>()
        for (name in screens) {
            val file = layoutOf(name)
            file.readLines().forEachIndexed { index, line ->
                for ((needle, why) in banned) {
                    if (line.contains(needle)) {
                        findings += "${name}:${index + 1}: `$needle` — $why"
                    }
                }
            }
        }
        assertTrue(
            "Stage 8a's files still hold values the design system replaced:\n  " +
                findings.joinToString("\n  "),
            findings.isEmpty()
        )
    }

    @Test
    fun `every padding and margin in those files is a spacing token`() {
        val findings = ArrayList<String>()
        for (name in screens) {
            val file = layoutOf(name)
            file.readLines().forEachIndexed { index, line ->
                literalSpacing.findAll(line).forEach { match ->
                    val value = match.groupValues[3]
                    val attribute = match.groupValues[0]
                    token[value]?.let { tokenName ->
                        findings += "$name:${index + 1}: `$attribute` — $tokenName is the name of that gap"
                    }
                }
            }
        }
        assertTrue(
            "these paddings and margins type a number where the system has a name:\n  " +
                findings.joinToString("\n  ") +
                "\n  (4=spacing_xs, 8=spacing_small, 12=spacing_medium, 16=spacing_normal, " +
                "20=spacing_section, 24=spacing_large)",
            findings.isEmpty()
        )
    }

    /**
     * The gaps that are not on the grid at all.
     *
     * The test above catches the six numbers the system has names for; this catches the ones it has no name
     * for, which is the other way a restyled screen drifts off the design — a `10dp` that was typed because
     * "12 looked too wide" is a row whose rhythm belongs to no token, and Stage 8b will not know what to copy.
     * `1dp` and `2dp` are allowed with the same reasoning as above; everything else has to be 4, 8, 12, 16,
     * 20 or 24.
     */
    @Test
    fun `no padding or margin in those files sits between the grid steps`() {
        val offGrid = Regex(
            """android:(padding|layout_margin)(Start|End|Top|Bottom|Left|Right)?="(\d+)dp""",
            RegexOption.IGNORE_CASE
        )
        val findings = ArrayList<String>()
        for (name in screens) {
            layoutOf(name).readLines().forEachIndexed { index, line ->
                offGrid.findAll(line).forEach { match ->
                    val value = match.groupValues[3]
                    if (value != "1" && value != "2" && value !in token.keys) {
                        findings += "$name:${index + 1}: `${match.groupValues[0]}` — $value dp is not a step of " +
                            "this scale"
                    }
                }
            }
        }
        assertTrue(
            "these gaps belong to no spacing token and no accepted hairline:\n  " +
                findings.joinToString("\n  "),
            findings.isEmpty()
        )
    }

    /**
     * Every `?attr/textAppearance…` a restyled layout asks for is one the theme actually answers.
     *
     * This is the check the substitution table needs and the banned-value list cannot be: the redesign's type
     * roles are applied *through* theme attributes (`values/themes.xml` maps `textAppearanceTitleMedium` onto
     * `TextAppearance.TaskPlanner.Title` and so on), so an attribute that looks like a role and is not mapped
     * resolves to Material's own default — a different size, no colour of its own, and a layout that then
     * needs a hand-written `android:textColor` to look right. `?attr/textAppearanceBodyLarge` is exactly that
     * attribute: this stage reached for it as the "modern" spelling of `Body1`, and the theme answers it with
     * nothing. The mapping is read from the theme rather than copied into this file, so a role added to the
     * theme is a role this gate accepts on the same commit.
     */
    @Test
    fun `every text appearance asked of the theme is one the theme maps`() {
        val themeFile = listOf(
            File("src/main/res/values/themes.xml"),
            File("app/src/main/res/values/themes.xml"),
            File("../src/main/res/values/themes.xml")
        ).firstOrNull(File::exists) ?: throw AssertionError(
            "no values/themes.xml reachable from ${File(".").absolutePath}, so this gate cannot know the scale"
        )
        val mapped = Regex("""<item name="(textAppearance[A-Za-z0-9]+)""")
            .findAll(themeFile.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("the theme maps no text appearances at all, which cannot be true", mapped.isNotEmpty())

        val asked = Regex("""\?attr/(textAppearance[A-Za-z0-9]+)""")
        // The night theme has to answer the same names, or a layout is on-scale by day and off-scale by night.
        val nightFile = listOf(
            File("src/main/res/values-night/themes.xml"),
            File("app/src/main/res/values-night/themes.xml"),
            File("../src/main/res/values-night/themes.xml")
        ).firstOrNull(File::exists)
        val nightMapped = nightFile?.let { source ->
            Regex("""<item name="(textAppearance[A-Za-z0-9]+)""")
                .findAll(source.readText()).map { it.groupValues[1] }.toSet()
        } ?: emptySet()

        val findings = ArrayList<String>()
        for (name in screens) {
            layoutOf(name).readLines().forEachIndexed { index, line ->
                asked.findAll(line).forEach { match ->
                    val attr = match.groupValues[1]
                    if (attr !in mapped) {
                        findings += "$name:${index + 1}: `?attr/$attr` is answered by no role in themes.xml " +
                            "(it resolves to Material's own default). Mapped names: " +
                            mapped.filter { it.startsWith("textAppearanceBody") || it.startsWith("textAppearanceTitle") }
                    } else if (attr !in nightMapped) {
                        findings += "$name:${index + 1}: `?attr/$attr` is mapped by day and not by night"
                    }
                }
            }
        }
        assertTrue(
            "these layouts ask the theme for a type attribute the theme does not map:\n  " +
                findings.joinToString("\n  "),
            findings.isEmpty()
        )
    }

    /**
     * One id, once, per layout. `values/ids.xml` declares ids centrally so a typo in `@id/` fails to link, and
     * the reviewer's point stands: what that does *not* buy is protection against the same id appearing twice
     * in one file, which is legal, silent, and makes `findViewById` answer whichever match comes first.
     */
    @Test
    fun `no layout gives the same id to two views`() {
        val idAttribute = Regex("""android:id="@\+?id/([A-Za-z0-9_]+)"""")
        val findings = ArrayList<String>()
        for (name in screens) {
            val seen = HashMap<String, Int>()
            layoutOf(name).readLines().forEachIndexed { index, line ->
                idAttribute.findAll(line).forEach { match ->
                    val id = match.groupValues[1]
                    seen[id]?.let { first -> findings += "$name: `$id` at lines $first and ${index + 1}" }
                    seen.putIfAbsent(id, index + 1)
                }
            }
        }
        assertTrue(
            "a duplicated id inside one layout makes findViewById answer one of two views, silently:\n  " +
                findings.joinToString("\n  "),
            findings.isEmpty()
        )
    }

    /**
     * The sweep is only worth running if it can fail. This builds the patterns it claims to catch and
     * refuses the file if the matchers no longer see them — a sweep whose regular expressions have rotted
     * reports a clean screen forever.
     */
    @Test
    fun `the sweep itself can still see what it forbids`() {
        val sample = """
            <TextView android:textColor="?android:attr/textColorSecondary"
                android:padding="16dp"
                android:paddingTop="10dp"
                style="@style/Widget.MaterialComponents.Button.TextButton" />
        """.trimIndent()
        assertTrue(
            "the banned-value matcher no longer sees a platform text colour, so the sweep is blind",
            banned.any { (needle, _) -> sample.contains(needle) }
        )
        assertTrue(
            "the spacing matcher no longer sees a literal 16dp padding, so the sweep is blind",
            literalSpacing.findAll(sample).any { it.groupValues[3] in token.keys }
        )
        assertTrue(
            "the off-grid matcher no longer sees a 10dp padding, so the sweep is blind",
            literalSpacing.findAll(sample).any {
                val value = it.groupValues[3]
                value != "1" && value != "2" && value !in token.keys
            }
        )
        assertTrue(
            "the spacing matcher no longer sees the `paddingHorizontal` shorthand, so the sweep is blind " +
                "on the two-axis spelling",
            literalSpacing.findAll("""android:paddingHorizontal="16dp"""").any { it.groupValues[3] in token.keys }
        )
        assertTrue(
            "the text-appearance matcher no longer pulls the attribute name out of `?attr/…`, so the " +
            "mapping gate is blind",
            Regex("""\?attr/(textAppearance[A-Za-z0-9]+)""")
                .findAll("""android:textAppearance="?attr/textAppearanceBodyLarge"""")
                .any { it.groupValues[1] == "textAppearanceBodyLarge" }
        )
        assertTrue(
            "the duplicate-id matcher no longer sees two views sharing a name, so that gate is blind",
            Regex("""android:id="@\+?id/([A-Za-z0-9_]+)"""")
                .findAll(
                    """<View android:id="@+id/rowRoot"/><View android:id="@id/rowRoot"/>"""
                ).count() == 2
        )
    }
}
