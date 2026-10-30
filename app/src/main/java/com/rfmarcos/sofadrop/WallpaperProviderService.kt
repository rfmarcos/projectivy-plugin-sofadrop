package com.rfmarcos.sofadrop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.net.toUri
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {

        override fun getWallpapers(event: Event?): List<Wallpaper> {
            Log.d(TAG, "getWallpapers(${event?.javaClass?.simpleName})")
            Settings.projectivyAskedAt = System.currentTimeMillis()
            if (event !is Event.TimeElapsed) return emptyList()

            // Never block Projectivy: return what we have and refresh in the background if needed
            val store = WallpaperStore(this@WallpaperProviderService)
            val current = store.current()
            // Settings changes wait for the next scheduled update (or "Update now"): only an empty
            // or expired set is regenerated from here, as a safety net for the periodic job
            val stale = System.currentTimeMillis() - Settings.lastGeneration > Settings.refreshIntervalMillis
            if (current.isEmpty() || stale) {
                GenerateWallpapersWorker.runNow(this@WallpaperProviderService)
            }
            GenerateWallpapersWorker.schedule(this@WallpaperProviderService)

            Log.i(TAG, "getWallpapers: ${current.size} wallpapers, stale=$stale, pendingChanges=${Settings.hasPendingChanges}")
            store.markServed()
            return (if (Settings.mixLists) current.shuffled() else current).mapNotNull { wallpaper ->
                val uri = store.uriOf(wallpaper) ?: return@mapNotNull null
                Wallpaper(
                    uri = uri.toString(),
                    type = WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = wallpaper.title,
                    source = "https://www.themoviedb.org/${wallpaper.type}/${wallpaper.tmdbId}",
                    author = "TMDB",
                    // "Watch" in Projectivy's "About this wallpaper" and a play button on its status bar
                    actionUri = actionUri(this@WallpaperProviderService, wallpaper),
                )
            }
        }

        override fun getPreferences(): String = Settings.export()

        override fun setPreferences(params: String) {
            Settings.import(params)
        }

    }

    companion object {
        private const val TAG = "SofadropService"

        private const val YOUTUBE_TV = "com.google.android.youtube.tv"

        /**
         * What Projectivy opens from [wallpaper] (see [Settings.clickAction]); applied when served, without
         * regenerating. An app that isn't installed falls back to the trailer.
         */
        fun actionUri(context: Context, wallpaper: StoredWallpaper): String? {
            if (Settings.clickAction == Settings.CLICK_NONE) return null
            Settings.clickApp()?.let { app ->
                app.installedPackage(context)?.let { app.intent(context, it, wallpaper) }?.let { return it.toUri(Intent.URI_INTENT_SCHEME) }
            }
            return wallpaper.trailerKey?.let { key ->
                // Several apps open YouTube links on a TV (browsers, Downloader…): YouTube's own, without asking
                Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$key".toUri())
                    .apply { if (isInstalled(context, YOUTUBE_TV)) setPackage(YOUTUBE_TV) }
                    .toUri(Intent.URI_INTENT_SCHEME)
            }
        }

        private fun isInstalled(context: Context, packageName: String) =
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }
}
