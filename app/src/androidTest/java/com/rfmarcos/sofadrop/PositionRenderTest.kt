package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders a title with the info block in each position, and logo only / title only on the right, into files/position_test/. */
@RunWith(AndroidJUnit4::class)
class PositionRenderTest {

    @Test
    fun rendersPositions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        val dir = File(context.filesDir, "position_test").apply { mkdirs() }
        fun save(name: String, bitmap: Bitmap) =
            File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        try {
            Profile.WALLPAPER.style = Settings.STYLE_FULL
            for (position in Settings.POSITIONS) {
                Profile.WALLPAPER.position = position
                val factory = WallpaperFactory(context, 1920, Profile.WALLPAPER)
                save(position, factory.render(factory.build(TitleRef(MediaType.TV, 1399))!!))
            }
            Profile.WALLPAPER.position = "bottom_right"
            Profile.WALLPAPER.style = Settings.STYLE_LOGO
            val factory = WallpaperFactory(context, 1920, Profile.WALLPAPER)
            val superman = factory.build(TitleRef(MediaType.MOVIE, 1061474))!!
            save("logo_bottom_right", factory.render(superman))
            save("title_bottom_right", factory.render(superman.copy(logoPath = null)))
        } finally {
            Profile.WALLPAPER.style = Settings.STYLE_FULL
            Profile.WALLPAPER.position = Settings.POSITION_TOP_LEFT
        }
    }
}
