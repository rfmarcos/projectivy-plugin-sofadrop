package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * T9 prototype, debug builds only: draws the first titles of the current set in layers (backdrop JPEG and
 * info PNG), composes them in every position and times each step, into files/layers_bench/<width>/
 * (results.txt and the images). Started by [DebugNotifyReceiver] with `--ez layers true`.
 */
object LayersBench {
    private const val TAG = "LayersBench"
    private const val JPEG_QUALITY = 90

    fun run(context: Context, width: Int, count: Int) {
        Settings.init(context)
        val dir = File(context.filesDir, "layers_bench/$width").apply { deleteRecursively(); mkdirs() }
        val lines = mutableListOf<String>()
        fun log(line: String) {
            Log.i(TAG, line)
            lines += line
        }
        fun now() = System.nanoTime() / 1_000_000
        fun write(bitmap: Bitmap, name: String, format: Bitmap.CompressFormat): Long {
            File(dir, name).outputStream().use { bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else JPEG_QUALITY, it) }
            return File(dir, name).length() / 1024
        }

        val factory = WallpaperFactory(context, width)
        log("width $width · style ${Settings.style} · cast photos ${Settings.castPhotos} · ${Settings.overviewLines} lines")
        for ((index, wallpaper) in WallpaperStore(context).current().take(count).withIndex()) {
            val ref = TitleRef(if (wallpaper.type == MediaType.TV.path) MediaType.TV else MediaType.MOVIE, wallpaper.tmdbId)
            val source = Sources.available().firstOrNull { it.key == wallpaper.source }
            val info = factory.build(ref, source = source) ?: continue
            // Images from the HTTP cache from here on: both renders pay the same for them
            factory.render(info).recycle()
            val name = "%02d".format(index + 1)

            var start = now()
            val full = factory.render(info)
            val fullMs = now() - start
            start = now()
            val layers = factory.renderLayers(info)
            val layersMs = now() - start
            val fullKb = write(full, "$name-full.jpg", Bitmap.CompressFormat.JPEG)
            start = now()
            val bgKb = write(layers.background, "$name-bg.jpg", Bitmap.CompressFormat.JPEG)
            val bgMs = now() - start
            start = now()
            val infoKb = layers.info?.let { write(it, "$name-info.png", Bitmap.CompressFormat.PNG) } ?: 0
            val pngMs = now() - start
            val infoSize = layers.info?.let { "${it.width}x${it.height}" } ?: "none"
            // The info block as WebP: lossless, and lossy (keeps the alpha)
            start = now()
            @Suppress("DEPRECATION")
            val webpKb = layers.info?.let { write(it, "$name-info.webp", Bitmap.CompressFormat.WEBP_LOSSLESS) } ?: 0
            val webpMs = now() - start
            start = now()
            val webpLossyKb = layers.info?.let { write(it, "$name-info-lossy.webp", Bitmap.CompressFormat.WEBP_LOSSY) } ?: 0
            val webpLossyMs = now() - start

            // The same wallpaper as now? Mean difference of the top-left composition, 0-255
            val same = factory.compose(layers, "top_left")
            val difference = meanDifference(full, same)
            same.recycle()
            full.recycle()
            layers.background.recycle()
            layers.info?.recycle()

            val composed = WallpaperRenderer.POSITIONS.map { position ->
                start = now()
                val background = BitmapFactory.decodeFile(File(dir, "$name-bg.jpg").path)
                val block = File(dir, "$name-info.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                val decodeMs = now() - start
                start = now()
                val output = factory.compose(WallpaperRenderer.Layers(background, layers.tint, block, layers.infoLeft, layers.infoTop), position)
                val drawMs = now() - start
                val mutable = BitmapFactory.Options().apply { inMutable = true }
                val onto = BitmapFactory.decodeFile(File(dir, "$name-bg.jpg").path, mutable)
                start = now()
                factory.composeOnto(WallpaperRenderer.Layers(onto, layers.tint, block, layers.infoLeft, layers.infoTop), position, sideOnly = false)
                val ontoMs = now() - start
                onto.recycle()
                val sideOnly = BitmapFactory.decodeFile(File(dir, "$name-bg.jpg").path, mutable)
                start = now()
                factory.composeOnto(WallpaperRenderer.Layers(sideOnly, layers.tint, block, layers.infoLeft, layers.infoTop), position, sideOnly = true)
                val sideMs = now() - start
                sideOnly.recycle()
                start = now()
                write(output, "$name-$position.jpg", Bitmap.CompressFormat.JPEG)
                val encodeMs = now() - start
                output.recycle()
                background.recycle()
                block?.recycle()
                listOf(decodeMs, drawMs, ontoMs, sideMs, encodeMs)
            }
            log("$name ${wallpaper.title}: render ${fullMs} ms, in layers ${layersMs} ms · full.jpg ${fullKb} KB, " +
                "bg.jpg ${bgKb} KB (${bgMs} ms), info.png ${infoKb} KB $infoSize (${pngMs} ms), webp ${webpKb} KB (${webpMs} ms), " +
                "lossy webp ${webpLossyKb} KB (${webpLossyMs} ms) · top_left vs now: $difference · " +
                "compose (decode/draw/onto/onto side only/encode): " + composed.joinToString(", ") { it.joinToString("/") } + " ms")
        }
        File(dir, "results.txt").writeText(lines.joinToString("\n"))
        log("done")
    }

    private fun meanDifference(a: Bitmap, b: Bitmap): String {
        var sum = 0L
        var count = 0
        for (y in 0 until a.height step 4) for (x in 0 until a.width step 4) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs((p shr 16 and 255) - (q shr 16 and 255)) + abs((p shr 8 and 255) - (q shr 8 and 255)) + abs((p and 255) - (q and 255))
            count += 3
        }
        return "%.2f".format(sum.toDouble() / count)
    }
}
