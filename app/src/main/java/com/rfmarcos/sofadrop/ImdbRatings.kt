package com.rfmarcos.sofadrop

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * IMDb ratings without any account, from IMDb's official dataset (title.ratings.tsv.gz, ~9 MB,
 * updated daily; free for personal, non-commercial use). It is downloaded only when a wallpaper needs
 * an IMDb rating that MDBList didn't give, again once a week, and sooner (at most daily) when a
 * title is missing, so new releases show up.
 */
object ImdbRatings {

    private const val TAG = "ImdbRatings"
    private const val URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
    private const val MAX_AGE = 7 * 86_400_000L
    private const val MIN_AGE_FOR_MISSING = 86_400_000L
    private const val RETRY_AFTER = 3_600_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Sorted (numeric id shl 8 or rating x10): ~13 MB for the whole dataset, loaded once per process
    private var index: LongArray? = null
    private var indexDate = 0L
    private var failedAt = 0L

    private fun file(context: Context) = File(context.filesDir, "imdb/title.ratings.tsv.gz")

    /** When the dataset in use was downloaded; 0 if it never was. */
    fun downloadedAt(context: Context): Long = file(context).lastModified()

    /** IMDb rating of a title ("tt0137523"), or null when IMDb has none (or it can't be downloaded). */
    @Synchronized
    fun rating(context: Context, imdbId: String): Double? {
        val id = imdbId.removePrefix("tt").toIntOrNull() ?: return null
        val file = file(context)
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE) tryDownload(file)
        find(load(file) ?: return null, id)?.let { return it }
        // A title too new for our copy: try a newer one, at most once a day
        if (System.currentTimeMillis() - file.lastModified() < MIN_AGE_FOR_MISSING || !tryDownload(file)) return null
        return find(load(file) ?: return null, id)
    }

    /** Offline or IMDb down: don't try again for every wallpaper. */
    private fun tryDownload(file: File): Boolean {
        if (System.currentTimeMillis() - failedAt < RETRY_AFTER) return false
        return runCatching { download(file) }
            .onFailure { failedAt = System.currentTimeMillis(); Log.w(TAG, "IMDb dataset download failed", it) }
            .isSuccess
    }

    /** Download the dataset now (the "update" row of the settings). */
    @Synchronized
    fun refresh(context: Context) {
        download(file(context))
        load(file(context))
    }

    private fun download(file: File) {
        val temp = File(file.parentFile, "${file.name}.part").apply { parentFile?.mkdirs() }
        http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("IMDb dataset HTTP ${response.code}")
            temp.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Could not save the IMDb dataset")
        }
        Log.i(TAG, "IMDb dataset downloaded (${file.length() / 1024} KB)")
    }

    private fun load(file: File): LongArray? {
        if (!file.exists()) return null
        if (index != null && indexDate == file.lastModified()) return index
        index = null // let the old copy go before building the new one
        val start = System.currentTimeMillis()
        return runCatching { GZIPInputStream(file.inputStream(), 1 shl 16).use(::parse) }
            .onFailure { Log.w(TAG, "Unreadable IMDb dataset", it); file.delete() }
            .getOrNull()
            ?.also {
                index = it
                indexDate = file.lastModified()
                Log.i(TAG, "${it.size} IMDb ratings loaded in ${System.currentTimeMillis() - start} ms")
            }
    }

    /**
     * "tt0137523\t8.8\t2675558" lines (after a header) into the sorted index. Read byte by byte: 1.7
     * million lines as Strings took half a minute on a Chromecast.
     */
    internal fun parse(input: InputStream): LongArray {
        var entries = LongArray(2_000_000)
        var size = 0
        val buffer = ByteArray(1 shl 16)
        var header = true
        var field = 0
        var id = 0L
        var rating = 0L
        var decimals = false
        var valid = true
        fun endLine() {
            // Ratings come with one decimal ("8.8", "10.0"): their digits are the rating x10
            if (!header && valid && field >= 1 && id > 0) {
                if (size == entries.size) entries = entries.copyOf(size * 3 / 2)
                entries[size++] = (id shl 8) or (if (decimals) rating else rating * 10)
            }
            header = false; field = 0; id = 0; rating = 0; decimals = false; valid = true
        }
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            for (i in 0 until read) {
                val c = buffer[i].toInt()
                when {
                    c == '\n'.code -> endLine()
                    header || !valid -> Unit
                    c == '\t'.code -> field++
                    field == 0 -> if (c in '0'.code..'9'.code) id = id * 10 + (c - '0'.code) else if (c != 't'.code) valid = false
                    field == 1 -> if (c in '0'.code..'9'.code) rating = rating * 10 + (c - '0'.code) else if (c == '.'.code) decimals = true else valid = false
                }
            }
        }
        endLine()
        return entries.copyOf(size).apply { sort() }
    }

    internal fun find(index: LongArray, id: Int): Double? {
        var low = 0
        var high = index.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midId = index[mid] shr 8
            when {
                midId < id -> low = mid + 1
                midId > id -> high = mid - 1
                else -> return (index[mid] and 0xFF) / 10.0
            }
        }
        return null
    }
}
