package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The wallpapers of the README (docs/images/wallpaper_*.jpg) at 1600x900, into files/readme/, with the ratings
 * that need no account (IMDb from its dataset, Trakt with the app's client id).
 */
@RunWith(AndroidJUnit4::class)
class ReadmeImagesTest {

    @Test
    fun rendersReadmeWallpapers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.regionOverride = ""
        Settings.style = Settings.STYLE_FULL
        Settings.titleLanguage = Settings.LANGUAGE_AUTO
        Settings.synopsisLanguage = Settings.LANGUAGE_AUTO
        Settings.headerOrder = Settings.HEADER_BLOCKS
        Settings.headerHidden = emptySet()
        Settings.minRatings = 2
        Settings.overviewLines = 4
        Settings.showProviders = true
        Settings.showSource = true
        Settings.sourceFallback = true
        Settings.ratingSources = listOf(RatingSource.IMDB, RatingSource.TRAKT)
        val dir = File(context.filesDir, "readme").apply { deleteRecursively(); mkdirs() }
        val trendingMovies = Sources.TMDB.first { it.key == "tmdb:trending_movies" }

        fun save(name: String, language: String, ref: TitleRef, cast: Boolean = false, change: (WallpaperInfo) -> WallpaperInfo = { it }) {
            Settings.language = language
            Settings.showCast = cast
            val factory = WallpaperFactory(context, 1920)
            val info = factory.build(ref, source = trendingMovies)
            assertNotNull(info)
            val bitmap = factory.render(change(info!!))
            val scaled = Bitmap.createScaledBitmap(bitmap, 1600, 900, true)
            File(dir, "$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }

        try {
            // Not released yet: no ratings, so the cast row and the list it came from
            save("wallpaper_en_avengers", "en-US", TitleRef(MediaType.MOVIE, 1003596), cast = true)
            save("wallpaper_en_watched", "en-US", TitleRef(MediaType.MOVIE, 1339713)) { it.copy(watchStatus = WatchStatus.Watched) }
            save("wallpaper_en_streaming", "en-US", TitleRef(MediaType.TV, 1416))
            save("wallpaper_es_avatar", "es-ES", TitleRef(MediaType.MOVIE, 980431))
            save("wallpaper_es_mandalorian", "es-ES", TitleRef(MediaType.MOVIE, 1228710))
        } finally {
            Settings.language = "en-US"
            Settings.showCast = false
            Settings.ratingSources = listOf(RatingSource.TMDB)
        }
    }
}
