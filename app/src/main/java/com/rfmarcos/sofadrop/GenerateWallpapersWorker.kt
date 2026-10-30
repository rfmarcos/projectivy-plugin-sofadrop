package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/** Downloads TMDB data, renders the wallpapers and publishes them to Projectivy. */
class GenerateWallpapersWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Settings.init(applicationContext)
        Updater.check()
        // Nothing to build from: keep the current wallpapers and say why (runNow already set "generating")
        val missing = when {
            Settings.token.isBlank() -> R.string.error_no_token
            Sources.selected().isEmpty() -> R.string.error_no_lists
            else -> null
        }
        val log = GenerationLog(applicationContext)
        if (missing != null) {
            Settings.lastError = applicationContext.getString(missing)
            Settings.generating = false
            log.finish("Not started: ${Settings.lastError}")
            return Result.failure()
        }

        val signature = Settings.signature
        val store = WallpaperStore(applicationContext)
        val generation = store.newGeneration()
        Settings.generating = true
        return try {
            val start = System.currentTimeMillis()
            val requests = TmdbClient.requestCounts()
            val stored = withPlatformLinks(generate(generation, log), log)
            // Every list failing (e.g. no network) or no title fitting in memory is that error, not "no titles"
            if (stored.isEmpty()) throw when {
                listErrors.isNotEmpty() -> listErrors.first()
                outOfMemory -> OutOfMemoryError("No title fitted in memory")
                else -> TmdbException(applicationContext.getString(R.string.error_no_titles))
            }
            store.publish(generation, stored)
            Settings.lastGeneration = System.currentTimeMillis()
            Settings.lastSignature = signature
            Settings.lastError = ""
            // A deleted or private Trakt list doesn't fail the generation, but the status says which one it was
            val unavailable = listErrors.filterIsInstance<ListUnavailableException>().map { it.name }
            Settings.lastNotice = listOfNotNull(
                applicationContext.getString(R.string.notice_memory_1080).takeIf { memoryErrors.get() > 0 },
                applicationContext.getString(R.string.notice_list_unavailable, unavailable.joinToString(", ")).takeIf { unavailable.isNotEmpty() },
            ).joinToString(" · ")
            notifyProjectivy(applicationContext)
            Log.i(TAG, "Generated ${stored.size} wallpapers (${Settings.language}) in ${System.currentTimeMillis() - start} ms, Projectivy notified")
            // What a generation costs TMDB (a preview open at the same time counts too)
            val (api, images, network) = TmdbClient.requestCounts().zip(requests) { now, before -> now - before }
            log.add("TMDB: $api API requests, $images images, $network not cached")
            log.finish("Generated ${stored.size} wallpapers")
            Result.success()
        } catch (e: Throwable) {
            // Errors too (out of memory): the status must always say why, not keep the last one
            Log.e(TAG, "Generation failed", e)
            store.discard(generation)
            Settings.lastError = errorMessage(applicationContext, e)
            log.finish("Failed: ${GenerationLog.describe(e)}")
            if (e is Exception && e !is TmdbException && e !is ListUnavailableException && runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            Settings.generating = false
            Settings.generationProgress = null
        }
    }

    /** Lists that couldn't be read in this run. */
    private val listErrors = mutableListOf<Exception>()

    /** Width the wallpapers are drawn at: the chosen one, or 1920 once 4K ran out of memory twice. */
    @Volatile
    private var width = 0
    private val memoryErrors = AtomicInteger()

    /** Some title didn't fit in memory even at 1920. */
    @Volatile
    private var outOfMemory = false

    private fun generate(generation: File, log: GenerationLog): List<StoredWallpaper> {
        val factory = WallpaperFactory(applicationContext, Settings.outputWidth)
        width = factory.width
        val perList = Settings.titlesPerList
        // Finished 4K wallpapers are ~33 MB each until compressed: one title at a time there
        val parallelTitles = if (factory.width > FALLBACK_WIDTH) 1 else perList
        // Titles that became a wallpaper; later lists skip them (unless duplicates are allowed)
        val taken = mutableSetOf<TitleRef>()
        val stored = mutableListOf<StoredWallpaper>()
        val sources = Sources.selected()

        /** Draw each title with its list's plan, in parallel; how many became a wallpaper. */
        fun draw(titles: List<Pair<ListPlan, TitleRef>>, list: Int): Int {
            var made = 0
            for ((ref, wallpaper) in WallpaperFactory.parallel(titles) { (plan, ref) -> ref to store(factory, plan, ref, generation, log) }) {
                if (wallpaper == null) continue
                // Only a title that really became a wallpaper is taken: a failed one stays free for other lists
                taken += ref
                stored += wallpaper
                made++
                Settings.generationProgress = Triple(stored.size, list, sources.size)
            }
            return made
        }

        val plans = linkedMapOf<ListSource, ListPlan>()
        for ((index, source) in sources.withIndex()) {
            Settings.generationProgress = Triple(stored.size, index + 1, sources.size)
            // Within Settings.MAX_WALLPAPERS: what's left shared among the lists to go (a short one leaves more)
            val left = Settings.MAX_WALLPAPERS - stored.size
            val quota = minOf(perList, (left + sources.size - index - 1) / (sources.size - index))
            if (quota <= 0) break
            val plan = try {
                factory.plan(source, perList, taken)
            } catch (e: TmdbException) {
                throw e
            } catch (e: Exception) {
                // One failing service (e.g. Trakt down) must not stop the others
                Log.w(TAG, "Could not read list ${source.key}", e)
                log.listFailed(source.key, e)
                listErrors += e
                continue
            }
            plans[source] = plan
            var added = 0
            var next = 0
            // In parallel, as many titles as still missing: the same ones a title-by-title loop would pick
            while (added < quota && next < plan.usable.size) {
                if (isStopped) {
                    // Android stops workers after 10 minutes: worth knowing when 4K on a slow TV fails
                    log.add("Stopped by Android after ${stored.size} wallpapers")
                    return stored
                }
                val batch = mutableListOf<Pair<ListPlan, TitleRef>>()
                while (added + batch.size < quota && batch.size < parallelTitles && next < plan.usable.size) {
                    val ref = plan.usable[next++]
                    // Without skipping duplicates, a title already drawn for another list still counts for this one
                    if (ref in taken) added++ else batch += plan to ref
                }
                added += draw(batch, index + 1)
            }
            Log.i(TAG, if (added < quota) "${source.key}: $added of $quota wallpapers (${plan.usable.size} usable titles)"
                else "${source.key}: $added wallpapers")
            log.list(source.key, plan.candidates.size, plan.candidates.count { it.exclusion != null }, added)
        }
        // Up to "titles per list" x lists, within Settings.MAX_WALLPAPERS
        val target = minOf(perList * sources.size, Settings.MAX_WALLPAPERS)
        topUp(factory, plans, target - stored.size, perList, taken, log) { titles ->
            // As many at a time as above (one at 4K)
            titles.chunked(parallelTitles).sumOf { draw(it, sources.size) }
        }
        // "One list after another" keeps each list's wallpapers together, extra ones included
        return stored.sortedBy { wallpaper -> sources.indexOfFirst { it.key == wallpaper.source } }
    }

    /**
     * Lists that came up short (too few titles left after filters and duplicates) are made up for by
     * the other lists, one extra title from each in turn, so the set still has about "titles per list"
     * x lists wallpapers. With "All" there is no number to reach.
     */
    private fun topUp(
        factory: WallpaperFactory, plans: Map<ListSource, ListPlan>, missing: Int, perList: Int, taken: Set<TitleRef>, log: GenerationLog,
        draw: (List<Pair<ListPlan, TitleRef>>) -> Int,
    ) {
        if (missing <= 0 || perList >= Settings.COUNT_ALL || plans.isEmpty()) return
        Log.i(TAG, "$missing wallpapers short, topping up from the other lists")
        log.add("$missing short: topping up from the other lists")
        // Each list read further than its own share; titles already in the set are skipped
        val extra = plans.map { (source, plan) ->
            val bigger = try {
                factory.plan(source, perList + missing, taken)
            } catch (e: TmdbException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read list ${source.key} again", e)
                plan
            }
            bigger to bigger.usable.iterator()
        }
        var left = missing
        while (left > 0 && !isStopped) {
            // One title from each list that has one left, drawn together
            val round = extra.mapNotNull { (plan, refs) ->
                generateSequence { if (refs.hasNext()) refs.next() else null }.firstOrNull { it !in taken }?.let { plan to it }
            }.take(left)
            if (round.isEmpty()) break
            left -= draw(round)
        }
        if (left > 0) Log.i(TAG, "Still $left wallpapers short: the lists have no more usable titles")
        log.add("Topped up ${missing - left}" + if (left > 0) ", still $left short" else "")
    }

    /**
     * [stored] with their pages on the streaming platforms that have them, when the wallpaper opens its
     * platform. Without Wikidata they open the fallback instead: not worth failing the generation for.
     */
    private fun withPlatformLinks(stored: List<StoredWallpaper>, log: GenerationLog): List<StoredWallpaper> {
        if (Settings.clickPlatforms.isEmpty()) return stored
        val refs = stored.filter { wallpaper -> wallpaper.providerIds.any { Platform.ofProvider(it) != null } }
            .associateWith { wallpaper -> TitleRef(MediaType.entries.first { it.path == wallpaper.type }, wallpaper.tmdbId) }
        if (refs.isEmpty()) return stored
        val ids = try {
            Wikidata.ids(refs.values)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the platform ids", e)
            log.add("Platform links failed: ${GenerationLog.describe(e)}")
            return stored
        }
        val linked = stored.map { wallpaper ->
            val ref = refs[wallpaper] ?: return@map wallpaper
            wallpaper.copy(platformLinks = Platform.links(ref.type, ids[ref].orEmpty()))
        }
        log.add("Platform links: ${linked.count { it.platformLinks.isNotEmpty() }} of ${refs.size} titles on a known platform")
        return linked
    }

    /** Render one title into [generation]; null when it can't be drawn. */
    private fun store(factory: WallpaperFactory, plan: ListPlan, ref: TitleRef, generation: File, log: GenerationLog): StoredWallpaper? =
        try {
            factory.build(ref, plan)?.let { info ->
                val bitmap = render(factory, info, ref, log) ?: return null
                val name = "${info.type.path}-${info.id}.jpg"
                try {
                    FileOutputStream(File(generation, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                } finally {
                    bitmap.recycle()
                }
                StoredWallpaper(name, info.title, info.type.path, info.id, plan.source.key, info.trailerKey, info.imdbId, info.providerIds)
            }
        } catch (e: TmdbException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not render ${ref.type.path}/${ref.tmdbId}", e)
            log.titleFailed(ref, e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory saving ${ref.type.path}/${ref.tmdbId}", e)
            log.titleFailed(ref, e)
            null
        }

    /**
     * [info] drawn at [width]. A 4K wallpaper that doesn't fit in memory is drawn again at 1920, and after
     * the second one the rest of the generation goes straight to 1920. Null when not even that fits.
     */
    private fun render(factory: WallpaperFactory, info: WallpaperInfo, ref: TitleRef, log: GenerationLog): Bitmap? {
        val width = this.width
        try {
            return factory.render(info, width)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory drawing ${ref.type.path}/${ref.tmdbId} at $width px", e)
            log.titleFailed(ref, e, width)
            if (width <= FALLBACK_WIDTH) {
                outOfMemory = true
                return null
            }
            if (memoryErrors.incrementAndGet() >= 2 && this.width > FALLBACK_WIDTH) {
                this.width = FALLBACK_WIDTH
                log.add("Out of memory twice at $width px: the rest at $FALLBACK_WIDTH px")
            }
        }
        return try {
            factory.render(info, FALLBACK_WIDTH)
        } catch (e: OutOfMemoryError) {
            log.titleFailed(ref, e, FALLBACK_WIDTH)
            outOfMemory = true
            null
        }
    }

    companion object {
        private const val TAG = "GenerateWallpapers"
        private const val JPEG_QUALITY = 90
        /** What 4K falls back to when the TV runs out of memory. */
        private const val FALLBACK_WIDTH = 1920
        private const val UNIQUE_NOW = "generate-now"
        private const val UNIQUE_PERIODIC = "generate-periodic"
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Generate as soon as possible. [replace] restarts a generation already running. */
        fun runNow(context: Context, replace: Boolean = false) {
            Settings.init(context)
            Settings.generating = true
            val request = OneTimeWorkRequestBuilder<GenerateWallpapersWorker>()
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** Keep the periodic refresh in sync with the "update every" setting. */
        fun schedule(context: Context, settingsChanged: Boolean = false) {
            Settings.init(context)
            val hours = Settings.refreshHours.toLong()
            val request = PeriodicWorkRequestBuilder<GenerateWallpapersWorker>(hours, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .addTag("every-$hours-h")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                if (settingsChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** What the status line says about a failed generation: the cause in the user's words when it's known. */
        fun errorMessage(context: Context, error: Throwable): String = when {
            error is OutOfMemoryError -> context.getString(R.string.error_memory)
            error is TmdbException && error.keyRejected -> context.getString(R.string.error_tmdb_key)
            error is TmdbException -> error.message.orEmpty()
            error is ListUnavailableException -> context.getString(R.string.error_list_unavailable, error.name)
            error is UnknownHostException || error is ConnectException || error is SocketTimeoutException || error is SSLException ->
                context.getString(R.string.error_network)
            else -> GenerationLog.describe(error)
        }

        /** Ask Projectivy to request the wallpaper list again. */
        fun notifyProjectivy(context: Context) {
            val intent = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
                `package` = PROJECTIVY_PACKAGE
                putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, context.getString(R.string.plugin_uuid))
                putExtra(
                    WallpaperProviderContract.EXTRA_UPDATE_REASON,
                    WallpaperProviderContract.UpdateReason.DATA_CHANGED,
                )
            }
            context.sendBroadcast(intent)
        }
    }
}
