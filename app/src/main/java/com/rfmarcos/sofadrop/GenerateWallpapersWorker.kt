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
            val stored = generate(generation, log)
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
            Settings.lastNotice = if (memoryErrors.get() > 0) applicationContext.getString(R.string.notice_memory_1080) else ""
            notifyProjectivy(applicationContext)
            Log.i(TAG, "Generated ${stored.size} wallpapers (${Settings.language}) in ${System.currentTimeMillis() - start} ms, Projectivy notified")
            log.finish("Generated ${stored.size} wallpapers")
            Result.success()
        } catch (e: Throwable) {
            // Errors too (out of memory): the status must always say why, not keep the last one
            Log.e(TAG, "Generation failed", e)
            store.discard(generation)
            Settings.lastError = errorMessage(applicationContext, e)
            log.finish("Failed: ${GenerationLog.describe(e)}")
            if (e is Exception && e !is TmdbException && runAttemptCount < 2) Result.retry() else Result.failure()
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
        val seen = mutableSetOf<TitleRef>()
        val stored = mutableListOf<StoredWallpaper>()

        val sources = Sources.selected()
        for ((index, source) in sources.withIndex()) {
            Settings.generationProgress = Triple(stored.size, index + 1, sources.size)
            val plan = try {
                factory.plan(source, perList, seen)
            } catch (e: TmdbException) {
                throw e
            } catch (e: Exception) {
                // One failing service (e.g. Trakt down) must not stop the others
                Log.w(TAG, "Could not read list ${source.key}", e)
                log.listFailed(source.key, e)
                listErrors += e
                continue
            }
            var added = 0
            var next = 0
            // In parallel, as many titles as still missing: the same ones a title-by-title loop would pick
            while (added < perList && next < plan.usable.size) {
                if (isStopped) {
                    // Android stops workers after 10 minutes: worth knowing when 4K on a slow TV fails
                    log.add("Stopped by Android after ${stored.size} wallpapers")
                    return stored
                }
                val batch = mutableListOf<TitleRef>()
                while (added + batch.size < perList && batch.size < parallelTitles && next < plan.usable.size) {
                    val ref = plan.usable[next++]
                    // Without skipping duplicates, a title already drawn for another list still counts for this one
                    if (seen.add(ref)) batch += ref else added++
                }
                for (wallpaper in WallpaperFactory.parallel(batch) { ref -> store(factory, plan, ref, generation, log) }) {
                    if (wallpaper == null) continue
                    stored += wallpaper
                    added++
                    Settings.generationProgress = Triple(stored.size, index + 1, sources.size)
                }
            }
            Log.i(TAG, "${source.key}: $added wallpapers")
            log.list(source.key, plan.candidates.size, plan.candidates.count { it.exclusion != null }, added)
        }
        return stored
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
                StoredWallpaper(name, info.title, info.type.path, info.id, plan.source.key)
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
