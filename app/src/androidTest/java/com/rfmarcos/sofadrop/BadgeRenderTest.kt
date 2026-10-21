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

        listOf(
            "movie_watched" to movie!!.copy(watchStatus = WatchStatus.Watched),
            "show_in_progress" to show!!.copy(watchStatus = WatchStatus.InProgress(3, 8)),
        ).forEach { (name, info) ->
            val bitmap = factory.render(info)
            File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }
}
