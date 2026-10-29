package com.rfmarcos.sofadrop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import java.io.File
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
            Log.i(TAG, "${wallpaper.title} -> ${wallpaper.uri} (${wallpaper.source}) action=${wallpaper.actionUri}")
            val bitmap = context.contentResolver.openInputStream(Uri.parse(wallpaper.uri)).use { BitmapFactory.decodeStream(it) }
            assertNotNull("cannot decode ${wallpaper.uri}", bitmap)
            assertEquals(1920, bitmap.width)
            assertEquals(1080, bitmap.height)
        }

        // Trailers: "Watch" in Projectivy opens YouTube; a title without one has no action
        assertEquals(Settings.CLICK_TRAILER, Settings.clickAction)
        val trailers = wallpapers.mapNotNull { it.actionUri }
        assertTrue("no wallpaper has a trailer", trailers.isNotEmpty())
        for (actionUri in trailers) {
            val intent = Intent.parseUri(actionUri, Intent.URI_INTENT_SCHEME)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(actionUri, intent.dataString.orEmpty().startsWith("https://www.youtube.com/watch?v="))
        }
        Settings.clickAction = Settings.CLICK_NONE
        try {
            assertTrue(service.getWallpapers(Event.TimeElapsed).all { it.actionUri == null })
        } finally {
            Settings.clickAction = Settings.CLICK_TRAILER
        }

        // Projectivy backup round-trip
        val exported = service.preferences
        assertTrue(exported.contains("\"language\":\"es-ES\""))
        service.setPreferences(exported.replace("\"titles_per_list\":3", "\"titles_per_list\":5"))
        assertEquals(5, Settings.titlesPerList)
        assertEquals(Settings.DEFAULT_LISTS, Settings.lists)
        Settings.titlesPerList = 3
    }

    /**
     * The trailer stored with a wallpaper reaches Projectivy as actionUri, from an index.json written by hand
     * (no TMDB key or network): one entry with a trailer, one in the format of sets made before it.
     */
    @Test
    fun servesTrailersAsActionUri() {
        Settings.init(context)
        val store = WallpaperStore(context)
        val generation = store.newGeneration()
        for (name in listOf("movie-1.jpg", "movie-2.jpg")) {
            File(generation, name).outputStream().use { Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        store.publish(generation, emptyList())
        File(generation, "index.json").writeText(
            """[{"file":"movie-1.jpg","title":"With trailer","type":"movie","tmdbId":1,"source":"tmdb:trending_movies","trailerKey":"dQw4w9WgXcQ"},""" +
                """{"file":"movie-2.jpg","title":"Older set","type":"movie","tmdbId":2}]"""
        )
        val lastGeneration = Settings.lastGeneration
        Settings.lastGeneration = System.currentTimeMillis() // not stale: no generation from getWallpapers
        try {
            val service = bind()
            val wallpapers = service.getWallpapers(Event.TimeElapsed).associateBy { it.title }
            Log.i(TAG, "actionUri: ${wallpapers.mapValues { it.value.actionUri }}")
            assertEquals(null, wallpapers.getValue("Older set").actionUri)
            val intent = Intent.parseUri(wallpapers.getValue("With trailer").actionUri, Intent.URI_INTENT_SCHEME)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", intent.dataString)

            Settings.clickAction = Settings.CLICK_NONE
            assertTrue(service.getWallpapers(Event.TimeElapsed).all { it.actionUri == null })
        } finally {
            Settings.clickAction = Settings.CLICK_TRAILER
            Settings.lastGeneration = lastGeneration
        }
    }

    /** Any installed app can call getPreferences / setPreferences: keys and connections never go through them. */
    @Test
    fun backupLeavesOutKeysAndConnections() {
        Settings.init(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val secrets = listOf("tmdb_token", "trakt_client_id", "trakt_client_secret", "mdblist_api_key",
            "_trakt_access_token", "_simkl_access_token", "_mdblist_access_token", "_tmdb_session_id")
            .associateWith { "test-secret-$it" }
        val saved = secrets.keys.associateWith { prefs.getString(it, null) }
        prefs.edit(commit = true) { secrets.forEach { (key, value) -> putString(key, value) } }
        Settings.titlesPerList = Settings.titlesPerList
        try {
            val service = bind()
            val exported = service.preferences
            Log.i(TAG, "Backup: $exported")
            secrets.keys.forEach { assertFalse("$it exported", exported.contains("\"$it\"")) }
            assertFalse(exported.contains("test-secret"))

            service.setPreferences(exported.removeSuffix("}") + secrets.keys.joinToString("") { ",\"$it\":\"injected\"" } + "}")
            secrets.forEach { (key, value) -> assertEquals(key, value, prefs.getString(key, null)) }
        } finally {
            prefs.edit(commit = true) { saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }
        }
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
