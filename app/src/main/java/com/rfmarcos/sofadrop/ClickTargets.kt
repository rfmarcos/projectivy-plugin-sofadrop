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

    /** Intents that open [wallpaper], best first; only those an installed app takes. */
    fun candidates(context: Context, wallpaper: StoredWallpaper): List<Intent> {
        val platforms = Platform.pick(Settings.clickPlatforms, wallpaper.providerIds, wallpaper.platformLinks) { it.installedPackage(context) != null }
            .map { (platform, link) -> Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(platform.installedPackage(context)) }
        val fallback = Settings.clickFallback
        val app = Settings.clickApp(fallback)?.let { app -> app.installedPackage(context)?.let { app.intent(context, it, wallpaper) } }
        // Also after an app, harmless: only the first one is served
        val trailer = wallpaper.trailerKey?.takeIf { fallback != Settings.CLICK_NONE }?.let { key ->
            // Several apps open YouTube links on a TV (browsers, Downloader…): YouTube's own, without asking
            Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$key".toUri())
                .apply { if (isInstalled(context, YOUTUBE_TV)) setPackage(YOUTUBE_TV) }
        }
        // An app that doesn't take that kind of link would fail silently. Not the trailer: whatever opens
        // YouTube links (its phone app, a browser) is hidden from Sofadrop, and something always does
        val apps = (platforms + listOfNotNull(app)).filter { it.resolveActivity(context.packageManager) != null }
        return (apps + listOfNotNull(trailer)).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun isInstalled(context: Context, packageName: String) =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
}
