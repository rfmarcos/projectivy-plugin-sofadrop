package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The titles a list contributes, see [WallpaperFactory.plan]. */
data class ListPlan(
    val candidates: List<Candidate>,
    val usable: List<TitleRef>,
    val statuses: Map<TitleRef, WatchStatus>,
    val random: Boolean,
)

/** Shared pipeline for the background generation, the settings preview and the list explorer. */
class WallpaperFactory(context: Context, private val width: Int) {

    val client = TmdbClient(Settings.token, context.cacheDir)
    val catalog = TmdbCatalog(client, Settings.language, Settings.region, Settings.globalHitsOnly, Settings.showProviders)
    private val renderer = WallpaperRenderer(context, width, Languages.texts(Settings.language))

    val trakt: TraktClient? = if (Settings.traktConfigured) TraktClient(Settings.traktClientId, Settings.traktClientSecret) else null
    val mdbList: MdbListClient? = MdbListClient.fromSettings()

    /** Trakt history, loaded on first use (null when not needed). */
    val watched: WatchedContent? by lazy { WatchedContent.load(trakt) }

    /**
     * Titles of a list with every rule applied: filters, watched content, titles already taken by a
     * previous list and the selection mode. [ListPlan.usable] is in the order they should be tried.
     */
    fun plan(source: ListSource, perList: Int, taken: Set<TitleRef> = emptySet()): ListPlan {
        val random = Settings.selectionMode == Settings.SELECTION_RANDOM
        val hideWatched = Settings.watchedMode == Settings.WATCHED_HIDE
        val pool = if (random) perList * 3 else perList * 2
        val statuses = mutableMapOf<TitleRef, WatchStatus>()
        val candidates = catalog.candidates(source, pool, trakt, mdbList).map { candidate ->
            if (candidate.exclusion != null) return@map candidate
            if (candidate.ref in taken) return@map candidate.copy(exclusion = Exclusion.DUPLICATE)
            val status = watched?.status(candidate.ref)?.also { statuses[candidate.ref] = it }
            if (hideWatched && status is WatchStatus.Watched) candidate.copy(exclusion = Exclusion.WATCHED) else candidate
        }
        var usable = candidates.filter { it.exclusion == null }.map { it.ref }
        if (random) usable = usable.take(pool).shuffled()
        return ListPlan(candidates, usable, statuses, random)
    }

    /** Wallpaper data of a title, with its watched badge when the user wants it marked. */
    fun build(ref: TitleRef, plan: ListPlan): WallpaperInfo? {
        val info = catalog.build(ref) ?: return null
        val status = plan.statuses[ref]
        return if (status != null && Settings.watchedMode == Settings.WATCHED_MARK) info.copy(watchStatus = status) else info
    }

    /** Download the images of [info] and draw the wallpaper. */
    fun render(info: WallpaperInfo): Bitmap {
        val backdrop = client.image(info.backdropPath)?.let { decode(it, width) } ?: error("No backdrop for ${info.title}")
        val logo = info.logoPath
            ?.let { client.image(it, if (width > 1920) "original" else "w500") }
            ?.let { decode(it, 0) }
            ?.let { WallpaperRenderer.brightenDarkLogo(WallpaperRenderer.trimTransparent(it)) }
        val providers = info.providerLogoPaths.mapNotNull { path -> client.image(path, "w154")?.let { decode(it, 0) } }

        return try {
            renderer.render(info, backdrop, logo, providers)
        } finally {
            backdrop.recycle()
            logo?.recycle()
            providers.forEach { it.recycle() }
        }
    }

    companion object {
        /** Decode an image, subsampling large backdrops close to the target width to save memory. */
        fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
            val options = BitmapFactory.Options()
            if (targetWidth > 0) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                var sample = 1
                while (options.outWidth / (sample * 2) >= targetWidth) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
            }
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }
}
