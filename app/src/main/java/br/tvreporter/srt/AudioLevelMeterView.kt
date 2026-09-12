package br.tvreporter.srt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil

class AudioLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bars = 14
    private var level = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spacing = 6f
        val barWidth = (width - spacing * (bars - 1)) / bars
        val active = ceil(level * bars).toInt().coerceIn(0, bars)

        repeat(bars) { index ->
            val left = index * (barWidth + spacing)
            val ratio = (index + 1f) / bars
            val barHeight = height * (0.25f + 0.75f * ratio)
            val top = height - barHeight
            paint.color = when {
                index >= active -> Color.argb(55, 255, 255, 255)
                index < 9 -> Color.rgb(76, 175, 80)
                index < 12 -> Color.rgb(255, 193, 7)
                else -> Color.rgb(244, 67, 54)
            }
            canvas.drawRoundRect(left, top, left + barWidth, height.toFloat(), 5f, 5f, paint)
        }
    }
}
