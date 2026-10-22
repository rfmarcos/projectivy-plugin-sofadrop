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
import java.util.concurrent.TimeUnit

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
        if (missing != null) {
            Settings.lastError = applicationContext.getString(missing)
            Settings.generating = false
            return Result.failure()
        }

        val signature = Settings.signature
        val store = WallpaperStore(applicationContext)
        val generation = store.newGeneration()
        Settings.generating = true
        return try {
            val stored = generate(generation)
            if (stored.isEmpty()) throw TmdbException(applicationContext.getString(R.string.error_no_titles))
            store.publish(generation, stored)
            Settings.lastGeneration = System.currentTimeMillis()
            Settings.lastSignature = signature
            Settings.lastError = ""
            notifyProjectivy(applicationContext)
            Log.i(TAG, "Generated ${stored.size} wallpapers (${Settings.language}), Projectivy notified")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            store.discard(generation)
            Settings.lastError = e.message ?: e.javaClass.simpleName
            if (e !is TmdbException && runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            Settings.generating = false
            Settings.generationProgress = null
        }
    }

    private fun generate(generation: File): List<StoredWallpaper> {
        val factory = WallpaperFactory(applicationContext, Settings.outputWidth)
        val perList = Settings.titlesPerList
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
                continue
            }
            var added = 0
            for (ref in plan.usable) {
                if (isStopped) return stored
                if (added >= perList) break
                // Without skipping duplicates, a title already drawn for another list still counts for this one
                if (!seen.add(ref)) { added++; continue }
                try {
                    val info = factory.build(ref, plan) ?: continue
                    val bitmap = factory.render(info)
                    val name = "${info.type.path}-${info.id}.jpg"
                    FileOutputStream(File(generation, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    bitmap.recycle()
                    stored += StoredWallpaper(name, info.title, info.type.path, info.id, source.key)
                    added++
                    Settings.generationProgress = Triple(stored.size, index + 1, sources.size)
                } catch (e: TmdbException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not render ${ref.type.path}/${ref.tmdbId}", e)
                }
            }
            Log.i(TAG, "${source.key}: $added wallpapers")
        }
        return stored
    }

    companion object {
        private const val TAG = "GenerateWallpapers"
        private const val JPEG_QUALITY = 90
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
