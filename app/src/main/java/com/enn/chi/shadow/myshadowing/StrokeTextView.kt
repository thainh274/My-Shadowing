package com.enn.chi.shadow.myshadowing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.withStyledAttributes
import  com.enn.chi.shadow.myshadowing.R

class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var strokeColor: Int = Color.BLACK
    private var strokeWidth: Float = 6f

    init {
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.StrokeTextView) {
                strokeColor = getColor(R.styleable.StrokeTextView_strokeColor, Color.BLACK)
                strokeWidth = getDimension(R.styleable.StrokeTextView_strokeWidth, 6f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val textColor = currentTextColor

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeJoin = Paint.Join.ROUND
        setTextColor(strokeColor)
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        setTextColor(textColor)
        super.onDraw(canvas)
    }
}
