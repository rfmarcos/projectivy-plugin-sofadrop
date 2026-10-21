package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Shared pipeline for the background generation and the settings preview. */
class WallpaperFactory(context: Context, private val width: Int) {

    val client = TmdbClient(Settings.token, context.cacheDir)
    val catalog = TmdbCatalog(client, Settings.language, Settings.region, Settings.globalHitsOnly, Settings.showProviders)
    private val renderer = WallpaperRenderer(context, width, Languages.texts(Settings.language))

    val trakt: TraktClient? = if (Settings.traktConfigured) TraktClient(Settings.traktClientId, Settings.traktClientSecret) else null
    val mdbList: MdbListClient? = Settings.mdbListKey.takeIf { it.isNotBlank() }?.let { MdbListClient(it) }

    fun titles(source: ListSource, limit: Int): List<TitleRef> = catalog.titles(source, limit, trakt, mdbList)

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
