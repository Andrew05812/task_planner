package com.taskplanner.ui.keep

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The confirm bar's shape, chosen from the width it was given.
 *
 * Two actions that name themselves in full — «Pasted text» / «Вставленный текст» and «Import» /
 * «Импортировать» — share a row on a wide screen and cannot on a narrow one at the largest font the app claims.
 * When they are forced to share it anyway, the longer label wraps inside its half-width button and the second line
 * falls outside the bar: measured at 320dp with the font at 1.3 in Russian, where the primary action of the screen
 * that writes 382 rows ended up with a cut-off label and a touch target that Espresso refused to press because
 * less than 90% of it was on the window.
 *
 * The rule is in one place rather than in XML because a layout cannot pick an orientation from a width on its
 * own, and because the shape is a claim worth a test: [shape] is what `KeepImportScreenTest` measures.
 */
object KeepConfirmBar {

    /**
     * Put the bar's actions in the shape this width can hold.
     *
     * Stacked, each action takes the whole width and no label has to break; side by side, they split it equally,
     * which is the shape the wide frames were approved in. The weights travel with the orientation — a weighted
     * zero width means "half the row" horizontally and "nothing" vertically, so leaving them behind is how a
     * button disappears on one configuration while looking right on the next.
     */
    fun shape(bar: LinearLayout, back: View, confirm: View, stacked: Boolean) {
        bar.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val gap = if (stacked) back.resources.getDimensionPixelSize(com.taskplanner.R.dimen.spacing_small) else 0
        listOf(back to 0, confirm to gap).forEach { (action, top) ->
            val params = action.layoutParams as LinearLayout.LayoutParams
            params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.weight = if (stacked) 0f else 1f
            // The second action is the one that needs air when the row becomes a column; the first already has
            // the bar's own padding above it.
            params.topMargin = top
            action.layoutParams = params
        }
    }
}
