package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import java.util.Date

/**
 * Projectivy's home drawn over the preview: the user's own rows and app banners where the layout reader
 * found them, or a generic layout calibrated on Projectivy's default. A chip says which one it is.
 */
class LauncherGuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 40, 40, 48) }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 24, 24, 32) }
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(224, 36, 94) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255) }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
    private val rect = RectF()
    private val clip = Path()

    /** Banner (or icon) of each installed app, by the label Projectivy shows. */
    private val banners: Map<String, Drawable> by lazy {
        val pm = context.packageManager
        val intents = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
            runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }.getOrDefault(emptyList())
        }
        intents.mapNotNull { info ->
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull() ?: return@mapNotNull null
            val art = info.activityInfo.loadBanner(pm) ?: info.activityInfo.applicationInfo.loadBanner(pm) ?: info.loadIcon(pm)
            label to art
        }.toMap()
    }

    // As tall as a 16:9 screen of this width, not the whole column a plain View would take
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else w * 9 / 16
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val captured = Settings.launcherLayout
        val layout = captured ?: LauncherLayout.GENERIC
        val sx = w / layout.width; val sy = h / layout.height
        fun box(b: List<Int>) = rect.apply { set(b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy) }

        // Status cluster (connectivity, settings) and the clock
        layout.status?.let { b -> val r = box(b); canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, pill) }
        layout.clock?.let { b ->
            val r = box(b)
            text.textSize = r.height() * 0.62f
            val time = DateFormat.getTimeFormat(context).format(Date())
            canvas.drawText(time, r.right - text.measureText(time) - r.height() * 0.15f, r.bottom - r.height() * 0.24f, text)
        }

        // App cards: the user's own banners when captured, ghosts in the generic layout
        for (card in layout.cards) {
            val r = box(card.box)
            val radius = r.height() * 0.06f
            val art = if (captured != null) banners[card.label] else null
            when {
                art != null -> {
                    canvas.save()
                    clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW); canvas.clipPath(clip)
                    canvas.drawRoundRect(r, radius, radius, tile)
                    // Banners fill the card; square icons sit centred on a dark tile
                    val ratio = art.intrinsicWidth.toFloat() / art.intrinsicHeight.coerceAtLeast(1)
                    if (ratio > 1.4f) art.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                    else { val s = r.height() * 0.62f; art.setBounds((r.centerX() - s / 2).toInt(), (r.centerY() - s / 2).toInt(), (r.centerX() + s / 2).toInt(), (r.centerY() + s / 2).toInt()) }
                    art.draw(canvas)
                    canvas.restore()
                }
                captured != null -> {
                    canvas.drawRoundRect(r, radius, radius, tile)
                    text.textSize = r.height() * 0.16f
                    val label = card.label.take(18)
                    canvas.drawText(label, r.centerX() - text.measureText(label) / 2, r.centerY() + text.textSize / 3, text)
                }
                else -> canvas.drawRoundRect(r, radius, radius, ghost)
            }
            if (card.focused) {
                focus.strokeWidth = (r.height() * 0.03f).coerceAtLeast(1.5f)
                canvas.drawRoundRect(r, radius, radius, focus)
            }
        }

        // Which layout this is
        val label = if (captured != null) context.getString(R.string.layout_chip_real, LauncherLayoutFragment.capturedLabel(context, captured.capturedAt))
        else context.getString(R.string.layout_chip_generic)
        text.textSize = (h * 0.028f).coerceAtLeast(10f)
        val pad = text.textSize * 0.6f
        // Top centre: wallpapers keep their own text on the left and Projectivy its clock on the right
        val chipWidth = pad * 2 + text.measureText(label)
        rect.set((w - chipWidth) / 2, pad, (w + chipWidth) / 2, pad * 2.6f + text.textSize)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, chip)
        canvas.drawText(label, rect.left + pad, rect.bottom - pad * 0.95f, text)
    }
}
