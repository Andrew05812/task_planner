package com.taskplanner.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.taskplanner.R

class ColorPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val chipGroup: ChipGroup
    private var onColorSelectedListener: ((Int) -> Unit)? = null
    private var selectedColor: Int = 0

    private val colors = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.YELLOW,
        android.graphics.Color.MAGENTA,
        android.graphics.Color.CYAN,
        android.graphics.Color.GRAY,
        android.graphics.Color.BLACK
    )

    init {
        orientation = VERTICAL
        chipGroup = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        addView(chipGroup)
        setupColorChips()
    }

    private fun setupColorChips() {
        colors.forEach { color ->
            val chip = Chip(context).apply {
                isCheckable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
                chipStrokeWidth = 1f
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_on_surface)
                )
            }
            chipGroup.addView(chip)

            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedColor = color
                    onColorSelectedListener?.invoke(color)
                }
            }
        }
    }

    fun setOnColorSelectedListener(listener: (Int) -> Unit) {
        onColorSelectedListener = listener
    }

    fun setSelectedColor(color: Int) {
        selectedColor = color
        val index = colors.indexOf(color)
        if (index != -1) {
            (chipGroup.getChildAt(index) as? Chip)?.isChecked = true
        }
    }
} 