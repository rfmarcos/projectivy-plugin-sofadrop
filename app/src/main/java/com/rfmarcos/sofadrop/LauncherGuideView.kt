package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.text.DateFormat
import java.util.Date

/**
 * Ghost of a launcher home screen drawn over a wallpaper: a top bar with the clock and a row of app
 * cards over the lower part. Approximate on purpose (Projectivy layouts vary): it answers "will the
 * app row cover the text?" without pretending to be the user's own layout.
 */
class LauncherGuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val focused = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // Top bar with the clock on the right
        val barH = h * 0.09f
        canvas.drawRect(0f, 0f, w, barH, bar)
        text.textSize = barH * 0.42f
        val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        canvas.drawText(clock, w * 0.96f - text.measureText(clock), barH * 0.64f, text)
        // One row of six app cards over the lower part, the first one focused
        val margin = w * 0.04f; val gap = w * 0.015f
        val cardW = (w - 2 * margin - 5 * gap) / 6f
        val cardH = cardW * 0.56f
        val top = h * 0.66f
        caption.textSize = barH * 0.32f
        canvas.drawText(context.getString(R.string.guide_caption), margin, top - caption.textSize * 0.8f, caption)
        val r = cardH * 0.12f
        for (i in 0 until 6) {
            val left = margin + i * (cardW + gap)
            rect.set(left, top, left + cardW, top + cardH)
            canvas.drawRoundRect(rect, r, r, if (i == 0) focused else card)
        }
    }
}
