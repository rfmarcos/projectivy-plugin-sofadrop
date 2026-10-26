package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders real titles in each style, and with no header line, into files/style_test/ for visual checks. */
@RunWith(AndroidJUnit4::class)
class StyleRenderTest {

    @Test
    fun rendersStyles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        val dir = File(context.filesDir, "style_test").apply { mkdirs() }
        fun save(name: String, bitmap: Bitmap) =
            File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        try {
            for (style in Settings.STYLES) {
                Settings.style = style
                val factory = WallpaperFactory(context, 1920)
                val thrones = factory.build(TitleRef(MediaType.TV, 1399))
                val superman = factory.build(TitleRef(MediaType.MOVIE, 1061474))
                assertNotNull(thrones)
                assertNotNull(superman)
                // Hidden parts aren't fetched
                if (style != Settings.STYLE_FULL) assertNull(thrones!!.providerLogoPaths.firstOrNull())
                if (style == Settings.STYLE_BACKDROP) assertNull(thrones!!.logoPath)
                save("${style}_series", factory.render(thrones!!))
                save("${style}_movie", factory.render(superman!!))
                // The title written when there is no logo
                if (style == Settings.STYLE_LOGO) save("logo_title", factory.render(superman.copy(logoPath = null)))
            }

            // No header line: the synopsis moves up under the logo
            Settings.style = Settings.STYLE_FULL
            val factory = WallpaperFactory(context, 1920)
            val info = factory.catalog.build(TitleRef(MediaType.TV, 1399))!!
            save("no_header", factory.render(info.copy(ratings = emptyList(), year = "", extraInfo = "", genres = "", certification = null)))
        } finally {
            Settings.style = Settings.STYLE_FULL
        }
    }
}
