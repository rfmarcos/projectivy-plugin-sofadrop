package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/** The titles a list contributes, see [WallpaperFactory.plan]. */
data class ListPlan(
    val candidates: List<Candidate>,
    val usable: List<TitleRef>,
    val statuses: Map<TitleRef, WatchStatus>,
    val random: Boolean,
    val source: ListSource,
    /** MDBList scores of the usable titles, when the chosen ratings need them. */
    val ratings: Map<TitleRef, List<Rating>> = emptyMap(),
)

/** Shared pipeline for the background generation, the settings preview and the list explorer. */
class WallpaperFactory(context: Context, private val width: Int) {

    private val appContext = context.applicationContext

    val client = TmdbClient(Settings.token, context.cacheDir)
    val catalog = TmdbCatalog(client, Settings.language, Settings.region, Settings.showProviders)
    private val renderer = WallpaperRenderer(context, width, Languages.texts(Settings.language), Settings.overviewLines)

    val trakt: TraktClient? = if (Settings.traktConfigured) TraktClient(Settings.traktClientId, Settings.traktClientSecret) else null
    val simkl = SimklClient(Settings.simklClientId, client)
    val mdbList: MdbListClient? = MdbListClient.fromSettings()

    /** Trakt and Simkl history, loaded on first use (null when not needed). */
    val watched: WatchedContent? by lazy { WatchedContent.load(trakt, simkl) }

    /**
     * Titles of a list with every rule applied: filters, watched content, titles already taken by a
     * previous list and the selection mode. [ListPlan.usable] is in the order they should be tried.
     */
    fun plan(source: ListSource, perList: Int, taken: Set<TitleRef> = emptySet()): ListPlan {
        val random = Settings.selectionMode == Settings.SELECTION_RANDOM
        val watchedMode = Settings.filtersFor(source).watched
        val hideWatched = watchedMode == Settings.WATCHED_HIDE
        val statuses = mutableMapOf<TitleRef, WatchStatus>()
        val candidates = catalog.candidates(source, perList * 2, trakt, simkl, mdbList, shuffle = random).map { candidate ->
            if (candidate.exclusion != null) return@map candidate
            if (Settings.skipDuplicates && candidate.ref in taken) return@map candidate.copy(exclusion = Exclusion.DUPLICATE)
            // History is only loaded when some list shows or hides watched titles
            val status = if (watchedMode == Settings.WATCHED_SHOW) null else watched?.status(candidate.ref)?.also { statuses[candidate.ref] = it }
            if (hideWatched && status is WatchStatus.Watched) candidate.copy(exclusion = Exclusion.WATCHED) else candidate
        }
        val usable = candidates.filter { it.exclusion == null }.map { it.ref }
        return ListPlan(candidates, usable, statuses, random, source, mdbListRatings(usable))
    }

    /**
     * MDBList scores of [refs] in one request per type, only when a chosen rating needs them. Any
     * failure (no account, quota used up, network) leaves the wallpapers with TMDB's score.
     */
    private fun mdbListRatings(refs: List<TitleRef>): Map<TitleRef, List<Rating>> {
        val client = mdbList ?: return emptyMap()
        if (refs.isEmpty() || Settings.ratingSources.none { it.needsMdbList }) return emptyMap()
        return refs.groupBy { it.type }.flatMap { (type, group) ->
            runCatching { client.ratings(type, group.map { it.tmdbId }) }
                .onFailure { Log.w(TAG, "MDBList ratings failed", it) }
                .getOrDefault(emptyMap())
                .map { (id, ratings) -> TitleRef(type, id) to ratings }
        }.toMap()
    }

    /** Wallpaper data of a title, with its source list and its watched badge when the user wants it marked. */
    fun build(ref: TitleRef, plan: ListPlan? = null, source: ListSource? = plan?.source): WallpaperInfo? {
        val info = catalog.build(ref)?.let { info ->
            // "Streaming, or list source": the source line only when no service has the title
            if (source == null || !Settings.showSource || (Settings.sourceFallback && Settings.showProviders && info.providerLogoPaths.isNotEmpty())) info
            else info.copy(sourceLabel = Languages.sourceLabel(Settings.language, source), sourceProvider = source.provider)
        } ?: return null
        val status = if (plan != null) plan.statuses[ref] else watched?.status(ref)
        val mode = source?.let { Settings.filtersFor(it).watched } ?: Settings.watchedMode
        val fromMdbList = if (plan != null) plan.ratings[ref] else mdbListRatings(listOf(ref))[ref]
        val ratings = Rating.pick(Settings.ratingSources, fromMdbList ?: keylessRatings(ref, info), info.rating)
        // Header items the user hid are left empty, and the renderer skips empty ones
        return info.copy(
            watchStatus = status?.takeIf { mode == Settings.WATCHED_MARK && Settings.showsInHeader(Settings.HEADER_WATCHED) },
            ratings = ratings,
            year = info.year.takeIf { Settings.showsInHeader(Settings.HEADER_YEAR) }.orEmpty(),
            extraInfo = info.extraInfo.takeIf { Settings.showsInHeader(Settings.HEADER_RUNTIME) }.orEmpty(),
            genres = info.genres.takeIf { Settings.showsInHeader(Settings.HEADER_GENRES) }.orEmpty(),
        )
    }

    /** IMDb (its public dataset) and Trakt scores, for titles MDBList didn't give: no account needed. */
    private fun keylessRatings(ref: TitleRef, info: WallpaperInfo): List<Rating> {
        val selected = Settings.ratingSources
        val imdb = info.imdbId?.takeIf { RatingSource.IMDB in selected }
            ?.let { id -> runCatching { ImdbRatings.rating(appContext, id) }.getOrNull() }
        val trakt = trakt?.takeIf { RatingSource.TRAKT in selected }
            ?.let { client -> runCatching { client.rating(ref) }.onFailure { Log.w(TAG, "Trakt rating failed", it) }.getOrNull() }
        return listOfNotNull(imdb?.let { Rating(RatingSource.IMDB, it) }, trakt?.let { Rating(RatingSource.TRAKT, it * 10) })
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
        private const val TAG = "WallpaperFactory"

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
