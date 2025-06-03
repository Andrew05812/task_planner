package com.taskplanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.taskplanner.R

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val rect = RectF()
    private var initials: String = ""

    fun setInitials(text: String) {
        initials = text.take(2).uppercase()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw circle background
        val backgroundColor = paint.color // Store the background color
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawOval(rect, paint)
        
        // Draw initials
        paint.color = ContextCompat.getColor(context, android.R.color.white)
        paint.textSize = height * 0.4f
        paint.textAlign = Paint.Align.CENTER
        
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(initials, x, y, paint)
        
        // Restore the background color for future use
        paint.color = backgroundColor
    }

    override fun setBackgroundColor(color: Int) {
        paint.color = color
        invalidate()
    }
} 