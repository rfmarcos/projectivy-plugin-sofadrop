package com.rfmarcos.sofadrop

import android.app.Service
import android.content.Intent
import android.os.Binder
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

            val callers = callerPackages()
            Log.i(TAG, "getWallpapers from $callers: ${current.size} wallpapers, stale=$stale, pendingChanges=${Settings.hasPendingChanges}")
            return current.mapNotNull { wallpaper ->
                val uri = store.uriOf(wallpaper, callers) ?: return@mapNotNull null
                Wallpaper(
                    uri = uri.toString(),
                    type = WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = wallpaper.title,
                    source = "https://www.themoviedb.org/${wallpaper.type}/${wallpaper.tmdbId}",
                    author = "TMDB",
                )
            }
        }

        override fun getPreferences(): String = Settings.export()

        override fun setPreferences(params: String) {
            Settings.import(params)
        }

        /** Packages behind the binder call (Projectivy), so they can read our content:// URIs. */
        private fun callerPackages(): Set<String> {
            val packages = packageManager.getPackagesForUid(Binder.getCallingUid())?.toSet().orEmpty()
            return packages + PROJECTIVY_PACKAGE
        }
    }

    companion object {
        private const val TAG = "SofadropService"
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
    }
}
