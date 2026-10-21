package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a wallpaper with the same layout as TMDB.py. All sizes are defined for a 3840 px wide
 * canvas and scaled to the requested output width.
 */
class WallpaperRenderer(context: Context, private val width: Int, private val texts: WallpaperTexts) {

    private val height = width * 9 / 16
    private val scale = width / 3840f

    private val light: Typeface = font(context, R.font.roboto_light)
    private val regular: Typeface = font(context, R.font.roboto_regular)
    private val medium: Typeface = font(context, R.font.roboto_medium)
    private val bold: Typeface = font(context, R.font.roboto_bold)
    private val tmdbLogo: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.tmdb_logo)

    private fun s(value: Float) = value * scale
    private fun s(value: Int) = value * scale

    fun render(info: WallpaperInfo, backdrop: Bitmap, logo: Bitmap?, providerLogos: List<Bitmap>): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Full-bleed backdrop
        drawCover(canvas, backdrop)

        // Gradients tinted with the backdrop's own colour: left (info), bottom (launcher rows), top (clock)
        val average = Bitmap.createScaledBitmap(backdrop, 1, 1, true).getPixel(0, 0)
        val tint = Color.rgb(
            (6 + Color.red(average) * 0.1f).roundToInt(),
            (6 + Color.green(average) * 0.1f).roundToInt(),
            (6 + Color.blue(average) * 0.1f).roundToInt(),
        )
        drawGradient(canvas, tint, horizontal = true) { t -> 0.96f * (1 - smoothstep((t - 0.28f) / 0.45f)) }
        drawGradient(canvas, tint, horizontal = false) { t -> 0.98f * smoothstep((t - 0.42f) / 0.46f) }
        drawGradient(canvas, tint, horizontal = false) { t -> 0.45f * (1 - smoothstep(t / 0.22f)) }

        // Info block on its own layer, so it can cast a soft shadow
        val layer = Bitmap.createBitmap(
            min(width, s(2100).roundToInt()), min(height, s(1500).roundToInt()), Bitmap.Config.ARGB_8888
        )
        val layerCanvas = Canvas(layer)
        if (logo != null) drawLogo(layerCanvas, logo) else drawTitle(layerCanvas, info.title)
        drawMetadata(layerCanvas, info)
        val overviewBottom = drawOverview(layerCanvas, info.overview)
        drawProviders(layerCanvas, overviewBottom + s(40), providerLogos)

        val offset = IntArray(2)
        val shadow = layer.extractAlpha(
            Paint().apply { maskFilter = BlurMaskFilter(s(18f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) },
            offset,
        )
        canvas.drawBitmap(shadow, offset[0].toFloat(), offset[1].toFloat(), Paint().apply {
            color = Color.BLACK
            alpha = 153
        })
        canvas.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        shadow.recycle()
        layer.recycle()
        return output
    }

    // --- Background ---

    private fun drawCover(canvas: Canvas, image: Bitmap) {
        val ratio = max(width / image.width.toFloat(), height / image.height.toFloat())
        val srcWidth = (width / ratio).roundToInt()
        val srcHeight = (height / ratio).roundToInt()
        val left = (image.width - srcWidth) / 2
        val top = (image.height - srcHeight) / 2
        canvas.drawBitmap(
            image,
            Rect(left, top, left + srcWidth, top + srcHeight),
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
    }

    private fun drawGradient(canvas: Canvas, tint: Int, horizontal: Boolean, alphaAt: (Float) -> Float) {
        val stops = 33
        val positions = FloatArray(stops) { it / (stops - 1f) }
        val colors = IntArray(stops) {
            Color.argb((alphaAt(positions[it]).coerceIn(0f, 1f) * 255).roundToInt(), Color.red(tint), Color.green(tint), Color.blue(tint))
        }
        val shader = if (horizontal) {
            LinearGradient(0f, 0f, width.toFloat(), 0f, colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, 0f, height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            this.shader = shader
            isDither = true
        })
    }

    // --- Title / logo ---

    private fun drawLogo(canvas: Canvas, logo: Bitmap) {
        val ratio = min(s(LOGO_MAX_WIDTH) / logo.width, s(LOGO_MAX_HEIGHT) / logo.height)
        val w = logo.width * ratio
        val h = logo.height * ratio
        val top = s(LOGO_BOTTOM) - h
        canvas.drawBitmap(logo, null, RectF(s(MARGIN_LEFT), top, s(MARGIN_LEFT) + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** Fallback when there is no logo: biggest size that fits in two lines. */
    private fun drawTitle(canvas: Canvas, title: String) {
        val paint = textPaint(bold, 170f, Color.WHITE)
        var lines = listOf(title)
        var size = 170
        while (size >= 90) {
            paint.textSize = s(size)
            lines = wrap(title, paint, s(CONTENT_WIDTH), 99)
            if (lines.size <= 2 && lines.all { paint.measureText(it) <= s(CONTENT_WIDTH) }) break
            size -= 10
        }
        lines = wrap(title, paint, s(CONTENT_WIDTH), 2)
        val lineHeight = s(size * 1.08f)
        var top = s(LOGO_BOTTOM) - lineHeight * lines.size
        for (line in lines) {
            canvas.drawText(line, s(MARGIN_LEFT - 6), top - paint.fontMetrics.ascent, paint)
            top += lineHeight
        }
    }

    // --- Metadata ---

    private fun drawMetadata(canvas: Canvas, info: WallpaperInfo) {
        var x = s(MARGIN_LEFT)
        val center = s(META_CENTER)

        if (info.rating > 0) {
            val ratingPaint = textPaint(medium, s(46f), Color.WHITE)
            val ratingText = String.format(java.util.Locale.US, "%.1f", info.rating)
            val pad = s(24f)
            val star = s(22f)
            val gap = s(14f)
            val pillHeight = s(72f)
            val pillWidth = pad + star * 2 + gap + ratingPaint.measureText(ratingText) + pad
            canvas.drawRoundRect(
                RectF(x, center - pillHeight / 2, x + pillWidth, center + pillHeight / 2),
                pillHeight / 2, pillHeight / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) },
            )
            canvas.drawPath(starPath(x + pad + star, center, star, star * 0.45f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 197, 24)
            })
            drawCentered(canvas, ratingText, x + pad + star * 2 + gap, center, ratingPaint)
            x += pillWidth + s(20f)
        }

        info.watchStatus?.let { x = drawWatchBadge(canvas, it, x, center) + s(14f) }
        if (info.rating > 0 || info.watchStatus != null) x += s(14f)

        val meta = listOf(info.year, info.extraInfo, info.genres).filter { it.isNotEmpty() }.joinToString("   ·   ")
        drawCentered(canvas, meta, x, center, textPaint(medium, s(48f), Color.rgb(230, 230, 230)))
    }

    /** "✓ Watched" (green) or "▶ Watching · 3/8" (blue) pill; returns where it ends. */
    private fun drawWatchBadge(canvas: Canvas, status: WatchStatus, x: Float, center: Float): Float {
        val watched = status is WatchStatus.Watched
        val text = when (status) {
            is WatchStatus.Watched -> texts.watched
            is WatchStatus.InProgress -> String.format(texts.watching, status.completed, status.aired)
        }
        val textPaint = textPaint(medium, s(44f), Color.WHITE)
        val pad = s(24f)
        val icon = s(30f)
        val gap = s(14f)
        val height = s(72f)
        val width = pad + icon + gap + textPaint.measureText(text) + pad
        val color = if (watched) Color.rgb(46, 160, 87) else Color.rgb(59, 130, 246)
        canvas.drawRoundRect(
            RectF(x, center - height / 2, x + width, center + height / 2), height / 2, height / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)) },
        )
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
        val left = x + pad
        if (watched) {
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = s(6f)
            iconPaint.strokeCap = Paint.Cap.ROUND
            iconPaint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(Path().apply {
                moveTo(left + icon * 0.08f, center + icon * 0.02f)
                lineTo(left + icon * 0.38f, center + icon * 0.3f)
                lineTo(left + icon * 0.95f, center - icon * 0.32f)
            }, iconPaint)
        } else {
            canvas.drawPath(Path().apply {
                moveTo(left + icon * 0.1f, center - icon * 0.42f)
                lineTo(left + icon * 0.95f, center)
                lineTo(left + icon * 0.1f, center + icon * 0.42f)
                close()
            }, iconPaint)
        }
        drawCentered(canvas, text, left + icon + gap, center, textPaint)
        return x + width
    }

    private fun drawOverview(canvas: Canvas, overview: String): Float {
        val paint = textPaint(regular, s(48f), Color.rgb(220, 220, 220))
        var top = s(META_CENTER + 36 + 52)
        for (line in wrap(overview, paint, s(CONTENT_WIDTH), OVERVIEW_LINES)) {
            canvas.drawText(line, s(MARGIN_LEFT), top - paint.fontMetrics.ascent, paint)
            top += s(66f)
        }
        return top
    }

    private fun drawProviders(canvas: Canvas, top: Float, providerLogos: List<Bitmap>) {
        val iconSize = s(PROVIDER_ICON_SIZE)
        val center = top + iconSize / 2
        val label = if (providerLogos.isNotEmpty()) texts.availableOn else texts.nowTrendingOn
        val labelPaint = textPaint(light, s(50f), Color.rgb(205, 205, 205))
        drawCentered(canvas, label, s(MARGIN_LEFT), center, labelPaint)
        var x = s(MARGIN_LEFT) + labelPaint.measureText(label) + s(32f)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (providerLogos.isEmpty()) {
            val h = s(46f)
            val w = tmdbLogo.width * h / tmdbLogo.height
            canvas.drawBitmap(tmdbLogo, null, RectF(x, center - h / 2, x + w, center + h / 2), paint)
            return
        }
        for (logo in providerLogos.take(4)) {
            val rounded = roundedSquare(logo, iconSize.roundToInt(), s(20f))
            canvas.drawBitmap(rounded, x, center - iconSize / 2, paint)
            rounded.recycle()
            x += iconSize + s(22f)
        }
    }

    // --- Helpers ---

    private fun textPaint(typeface: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        textSize = size
        this.color = color
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, centerY: Float, paint: Paint) {
        val metrics = paint.fontMetrics
        canvas.drawText(text, x, centerY - (metrics.ascent + metrics.descent) / 2, paint)
    }

    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float) = Path().apply {
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val px = cx + radius * cos(angle).toFloat()
            val py = cy + radius * sin(angle).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    private fun roundedSquare(source: Bitmap, size: Int, radius: Float): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        paint.isFilterBitmap = true
        canvas.drawBitmap(source, null, Rect(0, 0, size, size), paint)
        return result
    }

    companion object {
        private const val MARGIN_LEFT = 210
        private const val CONTENT_WIDTH = 1550
        private const val LOGO_MAX_WIDTH = 1350
        private const val LOGO_MAX_HEIGHT = 420
        private const val LOGO_BOTTOM = 640
        private const val META_CENTER = 725
        private const val PROVIDER_ICON_SIZE = 88
        private const val OVERVIEW_LINES = 4

        private fun font(context: Context, id: Int): Typeface =
            ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT

        private fun smoothstep(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        /** Word wrap by measured width, ending with "…" when truncated. */
        fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
            val lines = mutableListOf<String>()
            var current = ""
            for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current = candidate
                } else {
                    lines += current
                    current = word
                }
            }
            if (current.isNotEmpty()) lines += current
            if (lines.size <= maxLines) return lines

            val kept = lines.take(maxLines).toMutableList()
            var last = kept.last()
            while (' ' in last && paint.measureText("$last…") > maxWidth) last = last.substringBeforeLast(' ')
            kept[kept.lastIndex] = last.trimEnd(',', '.', ';', ':') + "…"
            return kept
        }

        /** Remove transparent padding around a logo. */
        fun trimTransparent(bitmap: Bitmap): Bitmap {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var left = w; var top = h; var right = -1; var bottom = -1
            for (y in 0 until h) for (x in 0 until w) {
                if (Color.alpha(pixels[y * w + x]) > 10) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
            if (right < left || bottom < top) return bitmap
            if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return bitmap
            return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        }

        /**
         * Dark, colourless logos (e.g. black wordmarks) vanish on the dark gradient:
         * invert their luminance so black becomes white while keeping the details.
         */
        fun brightenDarkLogo(bitmap: Bitmap): Bitmap {
            val step = max(1, (bitmap.width * bitmap.height) / 40_000)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var count = 0
            var valueSum = 0f
            var saturationSum = 0f
            val hsv = FloatArray(3)
            var i = 0
            while (i < pixels.size) {
                val pixel = pixels[i]
                if (Color.alpha(pixel) > 128) {
                    Color.colorToHSV(pixel, hsv)
                    saturationSum += hsv[1]
                    valueSum += hsv[2]
                    count++
                }
                i += step
            }
            if (count == 0) return bitmap
            val value = valueSum / count * 255
            val saturation = saturationSum / count * 255
            if (value >= 70 || saturation >= 60) return bitmap

            val inverted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val invert = ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ))
            Canvas(inverted).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(invert) })
            return inverted
        }
    }
}
