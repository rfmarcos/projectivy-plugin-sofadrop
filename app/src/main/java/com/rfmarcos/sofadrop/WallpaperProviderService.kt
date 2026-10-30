package com.rfmarcos.sofadrop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
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

        /**
         * What Projectivy opens from [wallpaper] (see [ClickTargets]); applied when served, without
         * regenerating (except the platforms' pages). Decided when clicked, it is [OpenActivity] for every
         * wallpaper that has something to open now.
         */
        fun actionUri(context: Context, wallpaper: StoredWallpaper): String? {
            val target = ClickTargets.candidates(context, wallpaper).firstOrNull() ?: return null
            return (if (Settings.clickAtTap) OpenActivity.intent(context, wallpaper) else target).toUri(Intent.URI_INTENT_SCHEME)
        }
    }
}
