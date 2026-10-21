package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        val token = Settings.token
        if (token.isBlank()) {
            Settings.lastError = applicationContext.getString(R.string.error_no_token)
            return Result.failure()
        }

        val signature = Settings.signature
        val store = WallpaperStore(applicationContext)
        val generation = store.newGeneration()
        Settings.generating = true
        return try {
            val stored = generate(token, generation)
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
        }
    }

    private fun generate(token: String, generation: File): List<StoredWallpaper> {
        val language = Settings.language
        val client = TmdbClient(token, applicationContext.cacheDir)
        val catalog = TmdbCatalog(client, language, Settings.region, Settings.globalHitsOnly, Settings.showProviders)
        val width = Settings.outputWidth
        val renderer = WallpaperRenderer(applicationContext, width, Languages.texts(language))
        val count = Settings.titlesPerType

        val types = when (Settings.content) {
            ContentType.BOTH -> listOf(MediaType.MOVIE, MediaType.TV)
            ContentType.MOVIES -> listOf(MediaType.MOVIE)
            ContentType.TV -> listOf(MediaType.TV)
        }

        val stored = mutableListOf<StoredWallpaper>()
        for (type in types) {
            for (info in catalog.select(type, count)) {
                if (isStopped) return stored
                try {
                    stored += renderOne(client, renderer, info, width, generation)
                } catch (e: TmdbException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not render ${info.title}", e)
                }
            }
        }
        return stored
    }

    private fun renderOne(
        client: TmdbClient,
        renderer: WallpaperRenderer,
        info: WallpaperInfo,
        width: Int,
        generation: File,
    ): StoredWallpaper {
        val backdropBytes = client.image(info.backdropPath) ?: error("No backdrop")
        val backdrop = decode(backdropBytes, width) ?: error("Invalid backdrop")

        val logo = info.logoPath
            ?.let { client.image(it, if (width > 1920) "original" else "w500") }
            ?.let { decode(it, 0) }
            ?.let { WallpaperRenderer.brightenDarkLogo(WallpaperRenderer.trimTransparent(it)) }

        val providers = info.providerLogoPaths.mapNotNull { path -> client.image(path, "w154")?.let { decode(it, 0) } }

        val bitmap = renderer.render(info, backdrop, logo, providers)
        val name = "${info.type.path}-${info.id}.jpg"
        FileOutputStream(File(generation, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }

        bitmap.recycle()
        backdrop.recycle()
        logo?.recycle()
        providers.forEach { it.recycle() }
        return StoredWallpaper(name, info.title, info.type.path, info.id)
    }

    /** Decode an image, subsampling large backdrops close to the target width to save memory. */
    private fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
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
