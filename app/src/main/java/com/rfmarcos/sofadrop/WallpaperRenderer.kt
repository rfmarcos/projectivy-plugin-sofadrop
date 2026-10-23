package com.rfmarcos.sofadrop

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
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a wallpaper with the same layout as TMDB.py. All sizes are defined for a 3840 px wide
 * canvas and scaled to the requested output width.
 */
class WallpaperRenderer(
    context: Context,
    private val width: Int,
    private val texts: WallpaperTexts,
    private val overviewLines: Int = 4,
) {

    private val height = width * 9 / 16
    private val scale = width / 3840f

    private val light: Typeface = font(context, R.font.roboto_light)
    private val regular: Typeface = font(context, R.font.roboto_regular)
    private val medium: Typeface = font(context, R.font.roboto_medium)
    private val bold: Typeface = font(context, R.font.roboto_bold)
    private val tmdbLogo: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.tmdb_logo)
    // Official logos for dark backgrounds, drawn unaltered (Trakt's branding rules forbid recolouring)
    private val traktLogo: Drawable = AppCompatResources.getDrawable(context, R.drawable.logo_trakt)!!
    private val simklLogo: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.logo_simkl)
    private val mdbListLogo: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.logo_mdblist)
    // Official marks of the rating platforms, unaltered (sources in each drawable)
    private val ratingLogos: Map<Int, Drawable> = listOf(
        R.drawable.rating_imdb, R.drawable.rating_tomatoes_fresh, R.drawable.rating_tomatoes_rotten, R.drawable.rating_popcorn_up,
        R.drawable.rating_popcorn_down, R.drawable.rating_metacritic, R.drawable.rating_letterboxd, R.drawable.rating_tmdb,
        R.drawable.badge_trakt, R.drawable.rating_myanimelist,
    ).associateWith { AppCompatResources.getDrawable(context, it)!! }

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
        drawFooter(layerCanvas, overviewBottom + s(40), providerLogos, info)

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
        val limit = s(MARGIN_LEFT + CONTENT_WIDTH)

        val metaPaint = shadowed(textPaint(medium, s(48f), Color.rgb(230, 230, 230)))
        var parts = listOf(info.year, info.extraInfo, info.genres).filter { it.isNotEmpty() }
        fun line() = parts.joinToString("   ·   ")
        val badgeWidth = info.watchStatus?.let { watchBadgeWidth(it) + s(28f) } ?: 0f
        val afterRatings = if (badgeWidth > 0 || parts.isNotEmpty()) s(RATINGS_GAP) else 0f

        // Up to MIN_RATINGS always fit (the details give way below); more only in the room the
        // other items leave, so hiding the year, runtime or genres makes space for them
        val reserved = badgeWidth + (if (parts.isEmpty()) 0f else metaPaint.measureText(line())) + afterRatings
        val items = info.ratings.map(::ratingItem)
        var shown = 0
        var ratingsWidth = 0f
        for (item in items) {
            val width = ratingsWidth + (if (shown > 0) s(RATING_SPACING) else 0f) + item.width
            if (shown >= MIN_RATINGS && x + width + reserved > limit) break
            ratingsWidth = width
            shown++
        }
        if (shown > 0) x = drawRatings(canvas, items.take(shown), x, center) + afterRatings

        info.watchStatus?.let { x = drawWatchBadge(canvas, it, x, center) + s(28f) }

        // Still too long (long genres, or one wide rating): genres go first, then the runtime / seasons
        while (parts.size > 1 && x + metaPaint.measureText(line()) > limit) parts = parts.dropLast(1)
        if (parts.isNotEmpty()) drawCentered(canvas, line(), x, center, metaPaint)
    }

    /** A platform's logo and its score in the brand colour, measured for the header row. */
    private class RatingItem(val logo: Drawable, val logoWidth: Float, val logoHeight: Float, val text: String, val paint: Paint, val gap: Float) {
        val width: Float get() = logoWidth + gap + paint.measureText(text)
    }

    private fun ratingItem(rating: Rating): RatingItem {
        val logo = ratingLogos.getValue(ratingLogo(rating))
        // Same optical size: wide wordmarks shorter than round icons
        val height = s(
            when (rating.source) {
                RatingSource.IMDB -> 40f
                RatingSource.TMDB -> 44f
                RatingSource.MYANIMELIST -> 42f
                RatingSource.LETTERBOXD -> 26f
                else -> 50f
            }
        )
        // White: the logos already bring the colour, and the score stays a step above the grey details
        val paint = shadowed(textPaint(bold, s(48f), Color.WHITE))
        return RatingItem(logo, height * logo.intrinsicWidth / logo.intrinsicHeight, height, rating.text, paint, s(14f))
    }

    /** Logos and scores in a row, no background (as Nuvio does); returns where they end. */
    private fun drawRatings(canvas: Canvas, items: List<RatingItem>, x: Float, center: Float): Float {
        var left = x
        items.forEachIndexed { index, item ->
            if (index > 0) left += s(RATING_SPACING)
            item.logo.setBounds(left.roundToInt(), (center - item.logoHeight / 2).roundToInt(),
                (left + item.logoWidth).roundToInt(), (center + item.logoHeight / 2).roundToInt())
            item.logo.draw(canvas)
            drawCentered(canvas, item.text, left + item.logoWidth + item.gap, center, item.paint)
            left += item.width
        }
        return left
    }

    private fun ratingLogo(rating: Rating) = when (rating.source) {
        RatingSource.IMDB -> R.drawable.rating_imdb
        RatingSource.TOMATOES -> if (rating.positive) R.drawable.rating_tomatoes_fresh else R.drawable.rating_tomatoes_rotten
        RatingSource.POPCORN -> if (rating.positive) R.drawable.rating_popcorn_up else R.drawable.rating_popcorn_down
        RatingSource.METACRITIC -> R.drawable.rating_metacritic
        RatingSource.LETTERBOXD -> R.drawable.rating_letterboxd
        RatingSource.TMDB -> R.drawable.rating_tmdb
        RatingSource.TRAKT -> R.drawable.badge_trakt
        RatingSource.MYANIMELIST -> R.drawable.rating_myanimelist
    }

    /** Soft shadow that keeps the header text readable over bright backdrops. */
    private fun shadowed(paint: Paint) = paint.apply { setShadowLayer(s(10f), 0f, s(2f), Color.argb(150, 0, 0, 0)) }

    private fun watchBadgeWidth(status: WatchStatus): Float {
        val text = watchBadgeText(status)
        return s(24f) * 2 + s(30f) + s(14f) + textPaint(medium, s(44f), Color.WHITE).measureText(text)
    }

    private fun watchBadgeText(status: WatchStatus) = when (status) {
        is WatchStatus.Watched -> texts.watched
        is WatchStatus.InProgress -> String.format(texts.watching, status.completed, status.aired)
    }

    /** "✓ Watched" (green) or "▶ Watching · 3/8" (blue) pill; returns where it ends. */
    private fun drawWatchBadge(canvas: Canvas, status: WatchStatus, x: Float, center: Float): Float {
        val watched = status is WatchStatus.Watched
        val text = watchBadgeText(status)
        val textPaint = textPaint(medium, s(44f), Color.WHITE)
        val pad = s(24f)
        val icon = s(30f)
        val gap = s(14f)
        val height = s(72f)
        val width = watchBadgeWidth(status)
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
        if (overviewLines <= 0 || overview.isBlank()) return top // hidden or untranslated: the streaming row moves up
        for (line in wrap(overview, paint, s(CONTENT_WIDTH), overviewLines)) {
            canvas.drawText(line, s(MARGIN_LEFT), top - paint.fontMetrics.ascent, paint)
            top += s(66f)
        }
        return top
    }

    /** "Available on [icons]" when there are streaming services, then (dimmer) the list it came from. */
    private fun drawFooter(canvas: Canvas, top: Float, providerLogos: List<Bitmap>, info: WallpaperInfo) {
        if (providerLogos.isEmpty()) {
            drawSource(canvas, top + s(PROVIDER_ICON_SIZE) / 2, info, primary = true)
            return
        }
        drawProviders(canvas, top, providerLogos)
        drawSource(canvas, top + s(PROVIDER_ICON_SIZE) + s(52f), info, primary = false)
    }

    /** "Trending · [TMDB logo]", "Your watchlist · Trakt", "Netflix Movies · MDBList". */
    private fun drawSource(canvas: Canvas, center: Float, info: WallpaperInfo, primary: Boolean) {
        val label = info.sourceLabel ?: return
        val grey = if (primary) 205 else 165
        val size = if (primary) 50f else 40f
        val labelPaint = textPaint(light, s(size), Color.rgb(grey, grey, grey))
        val text = "$label  ·  "
        drawCentered(canvas, text, s(MARGIN_LEFT), center, labelPaint)
        val x = s(MARGIN_LEFT) + labelPaint.measureText(text)
        val h = s(if (primary) 46f else 37f)
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun drawBitmap(bitmap: Bitmap, height: Float, left: Float): Float {
            val w = bitmap.width * height / bitmap.height
            canvas.drawBitmap(bitmap, null, RectF(left, center - height / 2, left + w, center + height / 2), bitmapPaint)
            return left + w
        }
        when (info.sourceProvider) {
            SourceProvider.TRAKT -> {
                // The full logo's mark is taller than its letters: size it on the mark
                val height = h * 1.25f
                val w = height * traktLogo.intrinsicWidth / traktLogo.intrinsicHeight
                traktLogo.setBounds(x.roundToInt(), (center - height / 2).roundToInt(), (x + w).roundToInt(), (center + height / 2).roundToInt())
                traktLogo.draw(canvas)
            }
            SourceProvider.SIMKL -> drawBitmap(simklLogo, h * 0.8f, x)
            // MDBList has no wordmark: its app icon followed by the name
            SourceProvider.MDBLIST -> {
                val end = drawBitmap(mdbListLogo, h * 1.15f, x)
                drawCentered(canvas, "MDBList", end + s(14f), center, textPaint(medium, s(size), Color.WHITE))
            }
            else -> drawBitmap(tmdbLogo, h, x)
        }
    }

    private fun drawProviders(canvas: Canvas, top: Float, providerLogos: List<Bitmap>) {
        val iconSize = s(PROVIDER_ICON_SIZE)
        val center = top + iconSize / 2
        val label = texts.availableOn
        val labelPaint = textPaint(light, s(50f), Color.rgb(205, 205, 205))
        drawCentered(canvas, label, s(MARGIN_LEFT), center, labelPaint)
        var x = s(MARGIN_LEFT) + labelPaint.measureText(label) + s(32f)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
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
        /** Between two ratings, and between the ratings and the rest of the header. */
        private const val RATING_SPACING = 44f
        private const val RATINGS_GAP = 72f
        private const val MIN_RATINGS = 3
        private const val LOGO_MAX_WIDTH = 1350
        private const val LOGO_MAX_HEIGHT = 420
        private const val LOGO_BOTTOM = 640
        private const val META_CENTER = 725
        private const val PROVIDER_ICON_SIZE = 88

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
