package com.taskplanner.ui.theme

import kotlin.math.pow

/**
 * WCAG 2.1 relative luminance and contrast ratio over the sRGB channels of a packed ARGB colour.
 *
 * Kotlin has no exponent operator, so the linearisation is `Double.pow` rather than `**`.
 */
object Wcag {
    private fun channel(v: Int) = (v / 255.0).let {
        if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Int): Double =
        0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)

    fun ratio(foreground: Int, background: Int): Double {
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
