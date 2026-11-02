package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * What a wallpaper opens: the user's platforms that have the title, in their order (see [Platform]), then
 * the fallback ([Settings.clickFallback]): an app (the trailer when it isn't installed), the trailer or
 * nothing.
 */
object ClickTargets {
    private const val YOUTUBE_TV = "com.google.android.youtube.tv"

    /**
     * The apps installed on the TV, looked up once for a whole set ([candidates] of a hundred wallpapers
     * would otherwise ask the package manager for every platform again and again).
     */
    class Apps(private val context: Context) {
        private val packages = mutableMapOf<Any, String?>()
        private val opens = mutableMapOf<String, Boolean>()

        fun of(platform: Platform): String? = packages.cached(platform) { platform.installedPackage(context) }
        fun of(app: TitleApp): String? = packages.cached(app) { app.installedPackage(context) }
        val youTube: Boolean by lazy { runCatching { context.packageManager.getPackageInfo(YOUTUBE_TV, 0) }.isSuccess }

        /** An installed app takes [intent]; links of one app and kind are taken alike. */
        fun opens(intent: Intent): Boolean {
            val data = intent.data
            val key = "${intent.`package`}|${intent.action}|${data?.scheme}|${data?.host}|${data?.pathSegments?.firstOrNull()}"
            return opens.getOrPut(key) { intent.resolveActivity(context.packageManager) != null }
        }

        private fun <K> MutableMap<K, String?>.cached(key: K, find: () -> String?): String? =
            if (key in this) this[key] else find().also { this[key] = it }
    }

    /** Intents that open [wallpaper], best first; only those an installed app takes. */
    fun candidates(context: Context, wallpaper: StoredWallpaper, apps: Apps = Apps(context)): List<Intent> {
        val platforms = Platform.pick(Settings.clickPlatforms, wallpaper.providerIds, wallpaper.platformLinks) { apps.of(it) != null }
            .map { (platform, link) -> Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(apps.of(platform)) }
        val fallback = Settings.clickFallback
        val app = Settings.clickApp(fallback)?.let { app -> apps.of(app)?.let { app.intent(context, it, wallpaper) } }
        // Also after an app, harmless: only the first one is served
        val trailer = wallpaper.trailerKey?.takeIf { fallback != Settings.CLICK_NONE }?.let { key ->
            // Several apps open YouTube links on a TV (browsers, Downloader…): YouTube's own, without asking
            Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$key".toUri())
                .apply { if (apps.youTube) setPackage(YOUTUBE_TV) }
        }
        // An app that doesn't take that kind of link would fail silently. Not the trailer: whatever opens
        // YouTube links (its phone app, a browser) is hidden from Sofadrop, and something always does
        val usable = (platforms + listOfNotNull(app)).filter { apps.opens(it) }
        return (usable + listOfNotNull(trailer)).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
