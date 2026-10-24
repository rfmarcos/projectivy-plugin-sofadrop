package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders real titles with the header line (ratings, badge, year and age rating…) into files/header_test/ for visual checks. */
@RunWith(AndroidJUnit4::class)
class HeaderRenderTest {

    @Test
    fun rendersHeaderLine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        val factory = WallpaperFactory(context, 1920)
        val dir = File(context.filesDir, "header_test").apply { mkdirs() }

        val ringsOfPower = factory.catalog.build(TitleRef(MediaType.TV, 84773))
        val thrones = factory.catalog.build(TitleRef(MediaType.TV, 1399))
        val superman = factory.catalog.build(TitleRef(MediaType.MOVIE, 1061474))
        listOf(ringsOfPower, thrones, superman).forEach(::assertNotNull)

        val ratings = listOf(Rating(RatingSource.IMDB, 6.9), Rating(RatingSource.TOMATOES, 84.0), Rating(RatingSource.POPCORN, 49.0))
        listOf(
            "series" to thrones!!.copy(ratings = ratings),
            "movie" to superman!!.copy(ratings = ratings),
            // The tightest line: the extra ratings and then the genres give way
            "crowded" to ringsOfPower!!.copy(ratings = ratings + Rating(RatingSource.METACRITIC, 71.0), watchStatus = WatchStatus.InProgress(15, 16)),
            "no_ratings" to thrones.copy(ratings = emptyList()),
        ).forEach { (name, info) ->
            val bitmap = factory.render(info)
            File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }

        // Another order and a single fixed rating: the rest only where there's room
        Settings.headerOrder = listOf(Settings.HEADER_YEAR, Settings.HEADER_RUNTIME, Settings.HEADER_GENRES, Settings.HEADER_RATINGS, Settings.HEADER_WATCHED)
        Settings.minRatings = 1
        try {
            val bitmap = WallpaperFactory(context, 1920).render(ringsOfPower.copy(ratings = ratings, watchStatus = WatchStatus.InProgress(15, 16)))
            File(dir, "custom_order.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            Settings.headerOrder = Settings.HEADER_BLOCKS
            Settings.minRatings = 2
        }
    }
}
