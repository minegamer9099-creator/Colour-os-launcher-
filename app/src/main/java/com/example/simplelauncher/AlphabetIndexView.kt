package com.example.simplelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** The A-Z strip on the right side of the app drawer. Slide a finger along it to jump to a letter. */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#".toCharArray()
    private var active = -1

    var onLetterSelected: ((Char) -> Unit)? = null

    var letterColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val cellH = height / letters.size.toFloat()
        paint.textSize = minOf(cellH * 0.7f, 11f * resources.displayMetrics.density)
        for (i in letters.indices) {
            paint.color = letterColor
            paint.alpha = if (i == active) 255 else 150
            paint.isFakeBoldText = i == active
            val y = cellH * i + cellH / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letters[i].toString(), width / 2f, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = (event.y / (height / letters.size.toFloat())).toInt()
                    .coerceIn(0, letters.lastIndex)
                if (index != active) {
                    active = index
                    onLetterSelected?.invoke(letters[index])
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
            }
        }
        return true
    }
}
