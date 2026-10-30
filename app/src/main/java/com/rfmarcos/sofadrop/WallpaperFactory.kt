package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

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
class WallpaperFactory(context: Context, val width: Int) {

    private val appContext = context.applicationContext

    val client = TmdbClient(Settings.token, context.cacheDir)
    val catalog = TmdbCatalog(client, Settings.language, Settings.region, Settings.drawsDetails && Settings.showProviders,
        Settings.partLanguages, Settings.drawsDetails && Settings.showCast, Settings.resolve(Settings.trailerLanguage), Settings.trailerFallback)
    /** Faces next to the cast names: only their images are downloaded, and only then. */
    private val castPhotos = if (Settings.drawsDetails) Settings.castPhotos else Settings.CAST_PHOTOS_NONE
    private val renderers = mutableMapOf<Int, WallpaperRenderer>()

    /** Renderer of [width]-pixel wallpapers: this factory's width, or 1920 when 4K runs out of memory. */
    private fun renderer(width: Int) = synchronized(renderers) {
        renderers.getOrPut(width) {
            WallpaperRenderer(appContext, width, Languages.texts(Settings.language), Settings.overviewLines,
                Settings.headerOrder, Settings.minRatings, Settings.style, castPhotos,
                Settings.showProviders && !Settings.sourceFallback && Settings.sourceInline)
        }
    }

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
        val skipTaken = Settings.skipDuplicates && taken.isNotEmpty()
        // Titles an earlier list took and hidden watched ones are excluded inside the catalog's pool, so a
        // list makes up for them instead of coming up short
        val runExclusion = { ref: TitleRef ->
            when {
                skipTaken && ref in taken -> Exclusion.DUPLICATE
                hideWatched && watched?.status(ref) is WatchStatus.Watched -> Exclusion.WATCHED
                else -> null
            }
        }.takeIf { skipTaken || hideWatched }
        val candidates = catalog.candidates(source, perList * 2, trakt, simkl, mdbList, shuffle = random, runExclusion = runExclusion)
        // History is only loaded when some list shows or hides watched titles
        if (watchedMode != Settings.WATCHED_SHOW) candidates.forEach { candidate ->
            if (candidate.exclusion == null) watched?.status(candidate.ref)?.let { statuses[candidate.ref] = it }
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
        if (refs.isEmpty() || !Settings.drawsDetails || Settings.ratingSources.none { it.needsMdbList }) return emptyMap()
        return refs.groupBy { it.type }.flatMap { (type, group) ->
            runCatching { client.ratings(type, group.map { it.tmdbId }) }
                .onFailure { Log.w(TAG, "MDBList ratings failed", it) }
                .getOrDefault(emptyMap())
                .map { (id, ratings) -> TitleRef(type, id) to ratings }
        }.toMap()
    }

    /** Wallpaper data of a title, with its source list and its watched badge when the user wants it marked. */
    fun build(ref: TitleRef, plan: ListPlan? = null, source: ListSource? = plan?.source): WallpaperInfo? {
        val genres = source?.let { Settings.filtersFor(it).genres }?.takeIf { it.only }?.ids.orEmpty()
        // Logo or backdrop alone: no source line, badge or ratings (nor their requests)
        if (!Settings.drawsDetails) return catalog.build(ref, genres)
        val info = catalog.build(ref, genres)?.let { info ->
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
            certification = info.certification.takeIf { Settings.showsInHeader(Settings.HEADER_CERTIFICATION) },
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

    /**
     * Download the images of [info] and draw the wallpaper, [width] pixels wide. Downloads overlap other
     * titles; decoding and drawing go one at a time: in the background Android runs the app on a single
     * core of the TV.
     */
    fun render(info: WallpaperInfo, width: Int = this.width): Bitmap {
        // Previews are 1280 px wide: TMDB's 1280 size instead of decoding the (often 4K) original
        val backdropBytes = client.image(info.backdropPath, if (width <= 1280) "w1280" else "original")
        val logoBytes = info.logoPath?.let { client.image(it, if (width > 1920) "original" else "w500") }
        val providerBytes = info.providerLogoPaths.mapNotNull { client.image(it, "w154") }
        // Only the images (~10 KB each): the photo paths came with the credits. One at a time, as this
        // already runs on the pool, alongside other titles
        val faceBytes = if (castPhotos != Settings.CAST_PHOTOS_NONE) info.cast.take(WallpaperRenderer.MAX_CAST_PHOTOS).map { member ->
            member.profilePath?.let { client.image(it, "w185") }
        } else emptyList()

        synchronized(drawing) {
            // Released even when one of them doesn't fit in memory: the next title (or 1920) still can
            var backdrop: Bitmap? = null
            var logo: Bitmap? = null
            val providers = mutableListOf<Bitmap>()
            val faces = mutableListOf<Bitmap?>()
            try {
                backdrop = backdropBytes?.let { decode(it, width, width * 9 / 16) } ?: error("No backdrop for ${info.title}")
                // Originals can be several thousand pixels wide: no bigger than the box it's drawn in
                val box = WallpaperRenderer.logoBox(width)
                logo = logoBytes?.let { decode(it, box.width, box.height, fit = true) }?.let { decoded ->
                    val trimmed = WallpaperRenderer.trimTransparent(decoded).also { if (it !== decoded) decoded.recycle() }
                    WallpaperRenderer.brightenDarkLogo(trimmed).also { if (it !== trimmed) trimmed.recycle() }
                }
                providerBytes.mapNotNullTo(providers) { decode(it) }
                val faceSize = WallpaperRenderer.castPhotoSize(width)
                faceBytes.mapTo(faces) { bytes -> bytes?.let { decode(it, faceSize, faceSize) } }
                return renderer(width).render(info, backdrop, logo, providers, faces)
            } finally {
                backdrop?.recycle()
                logo?.recycle()
                providers.forEach { it.recycle() }
                faces.forEach { it?.recycle() }
            }
        }
    }

    companion object {
        private const val TAG = "WallpaperFactory"

        /** Shared by every factory; only callers outside it may wait on it (a task waiting on another could deadlock). */
        private val pool = Executors.newFixedThreadPool(4)
        private val drawing = Any()

        /**
         * [transform] of every item on [pool], in the items' order; each result is returned as soon as
         * it and the ones before it are done. Errors are thrown as they were thrown inside.
         */
        fun <T, R> parallel(items: List<T>, transform: (T) -> R): Sequence<R> {
            val futures = items.map { item -> pool.submit(Callable { transform(item) }) }
            return futures.asSequence().map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        }

        /**
         * Decode an image, subsampled (by powers of two) to save memory while it still covers [minWidth] x
         * [minHeight], like a backdrop filling the screen; with [fit], while either side still fills that box,
         * like a logo drawn inside it.
         */
        fun decode(bytes: ByteArray, minWidth: Int = 0, minHeight: Int = 0, fit: Boolean = false): Bitmap? {
            val options = BitmapFactory.Options()
            if (minWidth > 0 && minHeight > 0) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                fun fills(sample: Int): Boolean {
                    val wide = options.outWidth / sample >= minWidth
                    val tall = options.outHeight / sample >= minHeight
                    return if (fit) wide || tall else wide && tall
                }
                var sample = 1
                while (fills(sample * 2)) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
            }
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }
}
