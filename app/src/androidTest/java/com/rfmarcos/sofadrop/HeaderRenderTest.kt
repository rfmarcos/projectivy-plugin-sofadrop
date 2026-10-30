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
        Settings.titleLanguage = Settings.LANGUAGE_AUTO
        Settings.synopsisLanguage = Settings.LANGUAGE_AUTO
        // A killed test run may not have saved the restored values
        Settings.headerOrder = Settings.HEADER_BLOCKS
        Settings.minRatings = 2
        Settings.showCast = false
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

        // Cast under the synopsis (Squid Game skips its Hangul-only names), full and short synopsis, and Swedish;
        // with the photos together, and the movie (four short names) with each photo next to its name too
        Settings.showCast = true
        Settings.castPhotos = Settings.CAST_PHOTOS_GROUPED
        try {
            val squidGame = WallpaperFactory(context, 1920).catalog.build(TitleRef(MediaType.TV, 93405))!!
            val movie = WallpaperFactory(context, 1920).catalog.build(TitleRef(MediaType.MOVIE, 1061474))!!.copy(ratings = ratings)
            listOf(
                "cast_squid_game" to squidGame,
                "cast_last_of_us" to WallpaperFactory(context, 1920).catalog.build(TitleRef(MediaType.TV, 100088))!!,
                "cast_movie" to movie,
            ).forEach { (name, info) ->
                val bitmap = WallpaperFactory(context, 1920).render(info)
                File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            Settings.castPhotos = Settings.CAST_PHOTOS_EACH
            WallpaperFactory(context, 1920).render(movie).let { bitmap ->
                File(dir, "cast_movie_each.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            Settings.castPhotos = Settings.CAST_PHOTOS_GROUPED
            Settings.overviewLines = 2
            WallpaperFactory(context, 1920).render(squidGame).let { bitmap ->
                File(dir, "cast_short.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            Settings.overviewLines = 4
            Settings.language = "sv-SE"
            Settings.regionOverride = ""
            WallpaperFactory(context, 1920).let { swedish ->
                val bitmap = swedish.render(swedish.catalog.build(TitleRef(MediaType.TV, 66732))!!)
                File(dir, "swedish.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        } finally {
            Settings.showCast = false
            Settings.overviewLines = 4
            Settings.language = "es-ES"
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
