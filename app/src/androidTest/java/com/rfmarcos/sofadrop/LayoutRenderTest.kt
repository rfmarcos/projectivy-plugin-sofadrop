package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders one title with combinations of the Wallpaper details parts (header line, synopsis length, cast and
 * its photos, footer) into files/layout_test/, plus contact sheets of the info area, to check the spacing between parts.
 */
@RunWith(AndroidJUnit4::class)
class LayoutRenderTest {

    /**
     * [header]: "all", "ratings" or "none"; [footer]: "both", "providers", "source" or "none"; [photos]: cast photos,
     * with the second actor's missing when [noPhoto].
     */
    private data class Combo(
        val header: String, val lines: Int, val cast: Boolean, val footer: String, val logo: Boolean = true,
        val photos: Boolean = false, val noPhoto: Boolean = false, val style: String = Settings.STYLE_FULL,
    ) {
        val label get() = "cab:$header · sinopsis:$lines · reparto:${if (photos) "fotos" else if (cast) "sí" else "no"} · pie:$footer" +
            (if (logo) "" else " · sin logo") + (if (noPhoto) " · 2º sin foto" else "") + (if (style == Settings.STYLE_FULL) "" else " · estilo:$style")
    }

    @Test
    fun rendersCombinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        Settings.style = Settings.STYLE_FULL
        Settings.headerOrder = Settings.HEADER_BLOCKS
        Settings.minRatings = 2
        Settings.showCast = true
        val dir = File(context.filesDir, "layout_test").apply { deleteRecursively(); mkdirs() }

        val combos = listOf(
            Combo("all", 4, true, "both"),
            Combo("all", 4, false, "both"),
            Combo("all", 2, true, "both"),
            Combo("all", 0, true, "both"),
            Combo("all", 0, false, "both"),
            Combo("all", 4, true, "none"),
            Combo("none", 4, true, "both"),
            Combo("none", 0, true, "both"),
            Combo("none", 0, true, "none"),
            Combo("none", 0, false, "both"),
            Combo("none", 4, false, "none"),
            Combo("none", 0, false, "none"),
            Combo("ratings", 0, true, "none"),
            Combo("ratings", 4, true, "none"),
            Combo("ratings", 0, false, "source"),
            Combo("ratings", 0, true, "both"),
            Combo("all", 4, true, "source"),
            Combo("all", 4, true, "providers"),
            Combo("all", 0, false, "source"),
            Combo("none", 0, true, "source"),
            Combo("all", 0, false, "none"),
            Combo("ratings", 0, false, "none"),
            Combo("all", 4, true, "both", logo = false),
            Combo("none", 0, true, "none", logo = false),
            Combo("all", 4, true, "both", photos = true),
            Combo("all", 2, true, "both", photos = true),
            Combo("all", 0, true, "both", photos = true),
            Combo("none", 0, true, "none", photos = true),
            Combo("ratings", 4, true, "source", photos = true),
            Combo("all", 4, true, "both", photos = true, noPhoto = true),
            Combo("all", 4, true, "both", logo = false, photos = true),
            Combo("all", 4, true, "both", photos = true, style = Settings.STYLE_LOGO),
        )

        try {
            val base = WallpaperFactory(context, 1920).catalog.build(TitleRef(MediaType.TV, 1399))
            assertNotNull(base)
            assertTrue("needs streaming services in Spain", base!!.providerLogoPaths.isNotEmpty())
            assertTrue("needs cast with photos", base.cast.size >= 3 && base.cast.take(3).all { it.profilePath != null })
            val ratings = listOf(Rating(RatingSource.IMDB, 9.2), Rating(RatingSource.TOMATOES, 89.0), Rating(RatingSource.POPCORN, 85.0))

            val crops = combos.mapIndexed { index, combo ->
                val info = base.copy(
                    ratings = if (combo.header == "none") emptyList() else ratings,
                    year = base.year.takeIf { combo.header == "all" }.orEmpty(),
                    extraInfo = base.extraInfo.takeIf { combo.header == "all" }.orEmpty(),
                    genres = base.genres.takeIf { combo.header == "all" }.orEmpty(),
                    certification = base.certification.takeIf { combo.header == "all" },
                    cast = if (!combo.cast) emptyList()
                    else if (combo.noPhoto) base.cast.mapIndexed { i, member -> if (i == 1) member.copy(profilePath = null) else member }
                    else base.cast,
                    providerLogoPaths = if (combo.footer == "both" || combo.footer == "providers") base.providerLogoPaths else emptyList(),
                    sourceLabel = if (combo.footer == "both" || combo.footer == "source") "Tendencias" else null,
                    sourceProvider = SourceProvider.TMDB,
                    logoPath = base.logoPath.takeIf { combo.logo },
                )
                Settings.overviewLines = combo.lines
                Settings.showCastPhotos = combo.photos
                Settings.style = combo.style
                val bitmap = WallpaperFactory(context, 1920).render(info)
                val name = "%02d".format(index + 1)
                File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                // The info area: the left half, down to where the launcher rows start
                Bitmap.createBitmap(bitmap, 0, 0, 960, 900).also { bitmap.recycle() }
            }

            // Contact sheets of 6 combinations, at half size, each with its number and label
            crops.chunked(6).forEachIndexed { sheetIndex, chunk ->
                val tileWidth = 480
                val tileHeight = 450
                val labelHeight = 36
                val sheet = Bitmap.createBitmap(tileWidth * 3, (tileHeight + labelHeight) * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 20f }
                chunk.forEachIndexed { i, crop ->
                    val number = sheetIndex * 6 + i
                    val left = (i % 3) * tileWidth
                    val top = (i / 3) * (tileHeight + labelHeight)
                    canvas.drawText("${number + 1}. ${combos[number].label}", left + 8f, top + 26f, paint)
                    canvas.drawBitmap(crop, null, Rect(left, top + labelHeight, left + tileWidth, top + labelHeight + tileHeight),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                }
                File(dir, "sheet${sheetIndex + 1}.jpg").outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        } finally {
            Settings.showCast = false
            Settings.showCastPhotos = true
            Settings.style = Settings.STYLE_FULL
            Settings.overviewLines = 4
        }
    }
}
