package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders real titles with the watched badges into files/badge_test/ for visual checks. */
@RunWith(AndroidJUnit4::class)
class BadgeRenderTest {

    @Test
    fun rendersWatchedBadges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        val factory = WallpaperFactory(context, 1920)
        val dir = File(context.filesDir, "badge_test").apply { mkdirs() }

        val movie = factory.catalog.build(TitleRef(MediaType.MOVIE, 1084244))
        val show = factory.catalog.build(TitleRef(MediaType.TV, 153312))
        assertNotNull(movie)
        assertNotNull(show)

        val scores = listOf(
            Rating(RatingSource.IMDB, 8.8), Rating(RatingSource.TOMATOES, 81.0), Rating(RatingSource.POPCORN, 96.0),
            Rating(RatingSource.METACRITIC, 67.0), Rating(RatingSource.LETTERBOXD, 4.3), Rating(RatingSource.TRAKT, 87.0),
            Rating(RatingSource.MYANIMELIST, 8.5), Rating(RatingSource.TMDB, 8.4),
        )
        listOf(
            "movie_watched" to movie!!.copy(watchStatus = WatchStatus.Watched),
            "show_in_progress" to show!!.copy(watchStatus = WatchStatus.InProgress(3, 8)),
            // Every logo; ratings take the room the other details leave
            "ratings_1" to movie.copy(ratings = scores.subList(0, 3)),
            "ratings_2" to movie.copy(ratings = scores.subList(3, 6)),
            "ratings_3" to show.copy(ratings = scores.subList(6, 8) + Rating(RatingSource.TOMATOES, 40.0)),
            "ratings_watched" to show.copy(ratings = listOf(scores[0], Rating(RatingSource.POPCORN, 45.0), scores[3]), watchStatus = WatchStatus.InProgress(3, 8)),
            "ratings_all" to movie.copy(ratings = scores),
            "ratings_only" to movie.copy(ratings = scores, year = "", extraInfo = "", genres = ""),
            "ratings_year" to show.copy(ratings = scores, extraInfo = "", genres = "", watchStatus = WatchStatus.Watched),
        ).forEach { (name, info) ->
            val bitmap = factory.render(info)
            File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }
}
