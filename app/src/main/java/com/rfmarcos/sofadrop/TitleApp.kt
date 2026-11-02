package com.rfmarcos.sofadrop

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri

/**
 * Apps a wallpaper can open its title in (see [Settings.clickFallback]). Only some of them can go to the
 * title itself; the platforms (Netflix, Prime Video…) have no public way to, so they aren't here.
 */
enum class TitleApp(val key: String, val label: String, private val packages: List<String>, val reach: Reach) {
    /** nuvio://tmdb/movie/603, nuvio://tmdb/series/1399 (its DeepLinkParser). */
    NUVIO("nuvio", "Nuvio", listOf("com.nuvio.tv", "com.nuvio.app"), Reach.TITLE),
    /** stremio:///detail/movie/tt0133093 (Stremio's deep links), by IMDb id; its search without one. */
    STREMIO("stremio", "Stremio", listOf("com.stremio.one"), Reach.TITLE),
    /** Its search screen with the title, through ACTION_SEARCH; items need the server's own ids. */
    JELLYFIN("jellyfin", "Jellyfin", listOf("org.jellyfin.androidtv"), Reach.SEARCH);
    // Plex and Kodi have no way in to a title: only opening the app, which isn't worth a wallpaper click

    /** How close to the title the app gets. */
    enum class Reach { TITLE, SEARCH }

    /** The installed package of this app, or null. */
    fun installedPackage(context: Context): String? = packages.firstOrNull { name ->
        try {
            context.packageManager.getPackageInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** What opens [wallpaper] in this app ([packageName], installed); null when the app can't be opened. */
    fun intent(context: Context, packageName: String, wallpaper: StoredWallpaper): Intent? {
        val series = wallpaper.type == MediaType.TV.path
        return when (this) {
            NUVIO -> Intent(Intent.ACTION_VIEW, "nuvio://tmdb/${if (series) "series" else "movie"}/${wallpaper.tmdbId}".toUri())
            STREMIO -> Intent(Intent.ACTION_VIEW, (wallpaper.imdbId?.let { "stremio:///detail/${if (series) "series" else "movie"}/$it" }
                ?: "stremio:///search?search=${Uri.encode(wallpaper.title)}").toUri())
            JELLYFIN -> Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, wallpaper.title)
        }.setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        fun of(key: String): TitleApp? = entries.firstOrNull { it.key == key }

        /** The apps installed on this device, in [entries] order. */
        fun installed(context: Context): List<TitleApp> = entries.filter { it.installedPackage(context) != null }
    }
}
