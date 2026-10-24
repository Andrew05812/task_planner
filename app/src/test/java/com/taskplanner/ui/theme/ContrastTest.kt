package com.taskplanner.ui.theme

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The pairs spec 3.2 promises, asserted against the shipped colour resources.
 *
 * scripts/contrast_report.py measures docs/design/tokens.json; this class measures what the APK carries.
 * The two agree only while the resources match the table, which is the point: editing a hex value in
 * colors.xml without the table fails here, in the build, rather than in a screenshot three stages later.
 */
@RunWith(RobolectricTestRunner::class)
abstract class AbstractContrastTest {
    protected lateinit var context: Context
        private set

    abstract fun themeName(): String

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers(qualifiers())
        context = ApplicationProvider.getApplicationContext()
    }

    protected abstract fun qualifiers(): String

    private fun color(name: String): Int {
        val id = R.color::class.java.getField(name).getInt(null)
        return ContextCompat.getColor(context, id)
    }

    private fun text(fg: String, bg: String) = check(fg, bg, 4.5, "text")
    private fun graphic(fg: String, bg: String) = check(fg, bg, 3.0, "graphic")

    private fun check(fg: String, bg: String, bar: Double, kind: String) {
        val ratio = Wcag.ratio(color(fg), color(bg))
        assertTrue(
            "$kind [${themeName()}]: @$fg on @$bg is %.2f, below the %.1f the design promises"
                .format(ratio, bar),
            ratio >= bar
        )
    }

    /** The four surfaces every general text role may sit on. */
    private val surfaces = listOf("ink_background", "ink_surface", "ink_surface_raised", "ink_surface_variant")

    @Test fun `general text clears AA on every surface it is used on`() {
        listOf("ink_text", "ink_text_secondary", "ink_text_tertiary", "ink_accent")
            .forEach { fg -> surfaces.forEach { bg -> text(fg, bg) } }
    }

    @Test fun `semantic text clears AA on the three surfaces the design allows it on`() {
        listOf("ink_success", "ink_warning", "ink_error").forEach { fg ->
            listOf("ink_background", "ink_surface", "ink_surface_raised").forEach { bg -> text(fg, bg) }
        }
    }

    @Test fun `a pill carries its own background and its own text`() {
        text("ink_accent", "pill_accent"); text("ink_success", "pill_success")
        text("ink_warning", "pill_warning"); text("ink_error", "pill_error")
        text("ink_text_secondary", "pill_neutral")
    }

    @Test fun `filled controls carry their declared foreground`() {
        text("ink_on_accent", "ink_accent"); text("ink_on_error", "ink_error")
    }

    @Test fun `graphics that carry state clear one point three`() {
        graphic("ink_error", "ink_surface"); graphic("ink_warning", "ink_surface")
        graphic("ink_accent", "ink_surface"); graphic("ink_outline_strong", "ink_surface")
        graphic("ink_outline_strong", "ink_surface_variant")
    }
}

@RunWith(RobolectricTestRunner::class)
class ContrastLightTest : AbstractContrastTest() {
    override fun themeName() = "light"
    override fun qualifiers() = "notnight"
}

@RunWith(RobolectricTestRunner::class)
class ContrastDarkTest : AbstractContrastTest() {
    override fun themeName() = "dark"
    override fun qualifiers() = "night"
}
