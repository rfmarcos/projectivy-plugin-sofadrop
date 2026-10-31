package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the list's line with each service's logo (TMDB, Trakt, Simkl, MDBList), under the services and on
 * their line, into files/source_logos/, to compare the logos' sizes.
 */
@RunWith(AndroidJUnit4::class)
class SourceLogoRenderTest {

    @Test
    fun rendersEachServiceLogo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        Settings.style = Settings.STYLE_FULL
        Settings.overviewLines = 0
        Settings.showCast = false
        Settings.showProviders = true
        Settings.sourceFallback = false
        val dir = File(context.filesDir, "source_logos").apply { deleteRecursively(); mkdirs() }
        val base = WallpaperFactory(context, 1920).catalog.build(TitleRef(MediaType.TV, 1399))
        assertNotNull(base)
        for (inline in listOf(false, true)) {
            Settings.sourceInline = inline
            for (provider in SourceProvider.entries) {
                val bitmap = WallpaperFactory(context, 1920).render(base!!.copy(sourceLabel = "Tendencias", sourceProvider = provider))
                val name = "${if (inline) "inline" else "below"}-${provider.name.lowercase()}"
                File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
        }
    }
}
