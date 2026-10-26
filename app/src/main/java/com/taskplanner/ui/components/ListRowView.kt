package com.taskplanner.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.widget.LinearLayout

/**
 * The root of a `row_list`: one line of a list that takes you somewhere.
 *
 * A row that responds to a press is a button shaped like a paragraph, and Android does not know that: the
 * primitive Stage 1 shipped is a `LinearLayout`, so the accessibility node carried the layout's class name
 * and a screen reader introduced the eight destinations of Profile as eight pieces of layout rather than as
 * eight things to press. Overriding the class to `Button` is the fix, and it lives in a class rather than in
 * a delegate per fragment so every screen that includes `row_list` inherits it — which is the point of
 * having the include.
 *
 * The override is conditional on being clickable, because `row_list` is also the shape of a row that only
 * *shows* something: `rowValue` exists for a row with a trailing figure, and a row nobody can press must not
 * announce itself as a button that does nothing when pressed.
 *
 * `screenReaderFocusable` goes with it: the row is read as one thing — its own `contentDescription` — rather
 * than as an icon, then a title, then a chevron. On releases before API 28 the compat call is a no-op and
 * the row is still a focusable, labelled, 56dp target.
 */
class ListRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                // Super first: the framework fills the node in, and the class name is the one thing about a
                // row it gets wrong.
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (isClickable) info.className = BUTTON_CLASS
            }
        })
        ViewCompat.setScreenReaderFocusable(this, true)
    }

    companion object {
        /** The class name a native button reports, so an announcement of this row says "button". */
        private const val BUTTON_CLASS = "android.widget.Button"
    }
}
