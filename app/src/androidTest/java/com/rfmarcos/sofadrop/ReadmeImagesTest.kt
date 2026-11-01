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
 * that need no account (IMDb from its dataset, Trakt with the app's client id). screensaver_en.jpg and setup_en.jpg
 * are screenshots of the release build on the emulator ("Try it" and About → Guided setup), without the system bars.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeImagesTest {

    @Test
    fun rendersReadmeWallpapers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.regionOverride = ""
        Profile.WALLPAPER.style = Settings.STYLE_FULL
        Profile.WALLPAPER.titleLanguage = Settings.LANGUAGE_AUTO
        Profile.WALLPAPER.synopsisLanguage = Settings.LANGUAGE_AUTO
        Profile.WALLPAPER.headerOrder = Settings.HEADER_BLOCKS
        Profile.WALLPAPER.headerHidden = emptySet()
        Profile.WALLPAPER.minRatings = 2
        Profile.WALLPAPER.overviewLines = 4
        Profile.WALLPAPER.showProviders = true
        Profile.WALLPAPER.showSource = true
        Profile.WALLPAPER.sourceFallback = true
        Profile.WALLPAPER.ratingSources = listOf(RatingSource.IMDB, RatingSource.TRAKT)
        val dir = File(context.filesDir, "readme").apply { deleteRecursively(); mkdirs() }
        val trendingMovies = Sources.TMDB.first { it.key == "tmdb:trending_movies" }

        fun save(
            name: String, language: String, ref: TitleRef, cast: Boolean = false,
            position: String = Settings.POSITION_TOP_LEFT, shade: String = Settings.SHADE_TINT,
            castPhotos: String = Settings.CAST_PHOTOS_GROUPED, change: (WallpaperInfo) -> WallpaperInfo = { it },
        ) {
            Settings.language = language
            Profile.WALLPAPER.showCast = cast
            Profile.WALLPAPER.castPhotos = castPhotos
            Profile.WALLPAPER.position = position
            Profile.WALLPAPER.shade = shade
            val factory = WallpaperFactory(context, 1920, Profile.WALLPAPER)
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
            // New in 1.2.0: each face next to its name, the text on the right, black shading for OLED
            save("wallpaper_en_cast", "en-US", TitleRef(MediaType.TV, 66732), cast = true, castPhotos = Settings.CAST_PHOTOS_EACH)
            save("wallpaper_en_right", "en-US", TitleRef(MediaType.MOVIE, 872585), position = "center_right")
            save("wallpaper_en_oled", "en-US", TitleRef(MediaType.TV, 83867), cast = true, shade = Settings.SHADE_BLACK)
        } finally {
            Settings.language = "en-US"
            Profile.WALLPAPER.showCast = false
            Profile.WALLPAPER.position = Settings.POSITION_TOP_LEFT
            Profile.WALLPAPER.shade = Settings.SHADE_TINT
            Profile.WALLPAPER.ratingSources = listOf(RatingSource.TMDB)
        }
    }
}
