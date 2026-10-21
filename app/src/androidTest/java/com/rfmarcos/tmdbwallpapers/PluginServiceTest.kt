package com.rfmarcos.tmdbwallpapers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of what Projectivy does: generate, bind the plugin service through its
 * public intent action, request wallpapers and decode every returned URI.
 * Needs network access and a TMDB key (tmdb.token in local.properties).
 */
@RunWith(AndroidJUnit4::class)
class PluginServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatesAndServesSpanishWallpapers() {
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        Settings.lists = Settings.DEFAULT_LISTS
        Settings.titlesPerList = 3
        Settings.outputWidth = 1920

        val before = Settings.lastGeneration
        GenerateWallpapersWorker.runNow(context, replace = true)
        val deadline = System.currentTimeMillis() + 180_000
        while (Settings.lastGeneration == before && Settings.lastError.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
        }
        assertEquals("", Settings.lastError)
        assertTrue("generation did not finish", Settings.lastGeneration > before)

        val service = bind()
        val wallpapers = service.getWallpapers(Event.TimeElapsed)
        assertEquals(6, wallpapers.size)
        for (wallpaper in wallpapers) {
            Log.i(TAG, "${wallpaper.title} -> ${wallpaper.uri} (${wallpaper.source})")
            val bitmap = context.contentResolver.openInputStream(Uri.parse(wallpaper.uri)).use { BitmapFactory.decodeStream(it) }
            assertNotNull("cannot decode ${wallpaper.uri}", bitmap)
            assertEquals(1920, bitmap.width)
            assertEquals(1080, bitmap.height)
        }

        // Projectivy backup round-trip
        val exported = service.preferences
        assertTrue(exported.contains("\"language\":\"es-ES\""))
        service.setPreferences(exported.replace("\"titles_per_list\":3", "\"titles_per_list\":5"))
        assertEquals(5, Settings.titlesPerList)
        assertEquals(Settings.DEFAULT_LISTS, Settings.lists)
        Settings.titlesPerList = 3
    }

    private fun bind(): IWallpaperProviderService {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent("tv.projectivy.plugin.WALLPAPER_PROVIDER").setPackage(context.packageName)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service not connected", latch.await(10, TimeUnit.SECONDS))
        return IWallpaperProviderService.Stub.asInterface(binder)
    }

    companion object {
        private const val TAG = "PluginServiceTest"
    }
}
