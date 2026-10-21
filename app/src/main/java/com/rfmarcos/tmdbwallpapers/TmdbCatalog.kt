package com.rfmarcos.tmdbwallpapers

import android.util.Log
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class MediaType(val path: String) { MOVIE("movie"), TV("tv") }

/** Everything needed to draw one wallpaper. */
data class WallpaperInfo(
    val type: MediaType,
    val id: Int,
    val title: String,
    val overview: String,
    val year: String,
    val rating: Double,
    val extraInfo: String,
    val genres: String,
    val backdropPath: String,
    val logoPath: String?,
    val providerLogoPaths: List<String>,
)

/** Port of the title selection in TMDB.py (androidtvbackground-reddit). */
class TmdbCatalog(
    private val client: TmdbClient,
    private val language: String,
    private val region: String,
    private val globalHitsOnly: Boolean,
    private val showProviders: Boolean,
) {
    private val languageCode = Languages.code(language)
    private val texts = Languages.texts(language)
    private val genres = mutableMapOf<MediaType, Map<Int, String>>()
    private val tvDetails = mutableMapOf<Int, JsonObject>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val today = dateFormat.format(Calendar.getInstance().time)
    private val oneYearAgo = dateFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -365) }.time)

    fun select(type: MediaType, limit: Int): List<WallpaperInfo> {
        val path = type.path
        val discover = if (type == MediaType.MOVIE) {
            "discover/movie?sort_by=popularity.desc&language=$language&include_adult=false&page=1" +
                "&with_release_type=4|5|6&include_video=false&vote_average.gte=1&vote_count.gte=50" +
                "&with_runtime.gte=15&without_genres=99&release_date.gte=$oneYearAgo&release_date.lte=$today"
        } else {
            "discover/tv?sort_by=popularity.desc&language=$language&include_adult=false&page=1" +
                "&vote_average.gte=1&vote_count.gte=50&with_runtime.gte=15&without_genres=99|16" +
                "&first_air_date.gte=$oneYearAgo&first_air_date.lte=$today"
        }

        // Merge trending + discover without duplicates
        val candidates = linkedMapOf<Int, JsonObject>()
        listOf("trending/$path/week?language=$language", discover).forEach { query ->
            client.get(query)?.objects("results")?.forEach { item ->
                item.int("id")?.let { candidates.putIfAbsent(it, item) }
            }
        }

        val selected = candidates.values
            .sortedByDescending(::weightedRating)
            .asSequence()
            .filterNot { shouldExclude(type, it) }
            .take(limit)
            .toList()

        return selected.mapNotNull { item ->
            try {
                build(type, item)
            } catch (e: TmdbException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipping ${item.int("id")}: ${e.message}")
                null
            }
        }
    }

    // --- Selection ---

    /** Bayesian average: few votes pull the score towards a typical TMDB rating. */
    private fun weightedRating(item: JsonObject): Double {
        val votes = item.int("vote_count") ?: 0
        val average = item.double("vote_average") ?: 0.0
        return (votes * average + RANKING_MIN_VOTES * RANKING_PRIOR) / (votes + RANKING_MIN_VOTES)
    }

    private fun shouldExclude(type: MediaType, item: JsonObject): Boolean {
        val id = item.int("id") ?: return true
        val originalLanguage = item.str("original_language")?.lowercase() ?: ""

        // Local-market titles only pass when they are worldwide hits
        if (globalHitsOnly && originalLanguage !in Languages.MAINSTREAM && originalLanguage != languageCode &&
            (item.int("vote_count") ?: 0) < MIN_VOTES_OTHER_LANGUAGES
        ) return true

        // Released / last aired within the last year
        val date = if (type == MediaType.MOVIE) item.str("release_date")
        else tvDetails(id)?.str("last_air_date")
        if (!date.isNullOrEmpty() && date < oneYearAgo) return true

        // Excluded keywords
        val keywords = client.get("${type.path}/$id/keywords")
            ?.objects(if (type == MediaType.MOVIE) "keywords" else "results")
            ?.mapNotNull { it.str("name")?.lowercase() }
            .orEmpty()
        return keywords.any { it in EXCLUDED_KEYWORDS }
    }

    private fun tvDetails(id: Int): JsonObject? =
        tvDetails[id] ?: client.get("tv/$id?language=$language")?.also { tvDetails[id] = it }

    // --- Wallpaper data ---

    private fun build(type: MediaType, item: JsonObject): WallpaperInfo? {
        val id = item.int("id") ?: return null
        val title = item.str(if (type == MediaType.MOVIE) "title" else "name") ?: return null
        val backdrop = textlessBackdrop(type, id, item.str("backdrop_path")) ?: return null

        val extraInfo = if (type == MediaType.MOVIE) {
            val runtime = client.get("movie/$id?language=$language")?.int("runtime") ?: 0
            if (runtime > 0) "${runtime / 60}h ${runtime % 60}min" else ""
        } else {
            val seasons = tvDetails(id)?.int("number_of_seasons") ?: 0
            if (seasons > 0) "$seasons ${if (seasons == 1) texts.season else texts.seasons}" else ""
        }

        return WallpaperInfo(
            type = type,
            id = id,
            title = title,
            overview = item.str("overview").orEmpty(),
            year = item.str(if (type == MediaType.MOVIE) "release_date" else "first_air_date").orEmpty().take(4),
            rating = item.double("vote_average") ?: 0.0,
            extraInfo = extraInfo,
            genres = genreNames(type, item),
            backdropPath = backdrop,
            logoPath = logoPath(type, id, item, title),
            providerLogoPaths = if (showProviders) providerLogoPaths(type, id) else emptyList(),
        )
    }

    private fun genreNames(type: MediaType, item: JsonObject): String {
        val map = genres.getOrPut(type) {
            client.get("genre/${type.path}/list?language=$language")?.objects("genres")
                ?.mapNotNull { genre -> genre.int("id")?.let { it to genre.str("name").orEmpty() } }
                ?.toMap()
                .orEmpty()
        }
        return item.array("genre_ids")
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            .mapNotNull { map[it] }
            .map { if (type == MediaType.TV) Languages.translateTvGenre(language, it) else it }
            .take(2)
            .joinToString(", ")
    }

    /** Prefer a backdrop without baked-in text, so it doesn't clash with the logo. */
    private fun textlessBackdrop(type: MediaType, id: Int, default: String?): String? {
        val textless = client.get("${type.path}/$id/images?include_image_language=null")
            ?.objects("backdrops")
            ?.filter { it.str("iso_639_1") == null && (it.int("width") ?: 0) >= 1920 }
            ?.mapNotNull { it.str("file_path") }
            .orEmpty()
        return if (default in textless || textless.isEmpty()) default else textless.first()
    }

    private fun logoPath(type: MediaType, id: Int, item: JsonObject, title: String): String? {
        val original = item.str(if (type == MediaType.MOVIE) "original_title" else "original_name").orEmpty()
        val originalLanguage = item.str("original_language").orEmpty()

        var logo = if (similarity(title, original) > 0.9) {
            multiLanguageLogo(type, id, originalLanguage)
        } else {
            logoIn(type, id, languageCode)
        }

        if (logo == null && originalLanguage != "en") {
            val english = client.get("${type.path}/$id?language=en-US")
                ?.str(if (type == MediaType.MOVIE) "title" else "name")
                .orEmpty()
            if (similarity(title, english) > 0.9) logo = logoIn(type, id, "en")
        }
        return logo
    }

    private fun logoIn(type: MediaType, id: Int, code: String): String? =
        client.get("${type.path}/$id/images?language=$code")?.objects("logos")
            ?.firstOrNull { it.str("iso_639_1") == code && it.str("file_path")?.endsWith(".png") == true }
            ?.str("file_path")

    private fun multiLanguageLogo(type: MediaType, id: Int, originalLanguage: String): String? {
        val logos = client.get("${type.path}/$id/images?include_image_language=$languageCode,$originalLanguage")
            ?.objects("logos")
            ?.filter { it.str("file_path")?.endsWith(".png") == true }
            .orEmpty()
        return (logos.firstOrNull { it.str("iso_639_1") == languageCode }
            ?: logos.firstOrNull { it.str("iso_639_1") == originalLanguage })
            ?.str("file_path")
    }

    private fun providerLogoPaths(type: MediaType, id: Int): List<String> {
        val providers = client.get("${type.path}/$id/watch/providers")
            ?.obj("results")?.obj(region)?.objects("flatrate")
            ?.sortedBy { it.int("display_priority") ?: 999 }
            .orEmpty()

        val logos = mutableListOf<String>()
        val seenNames = mutableListOf<String>()
        for (provider in providers) {
            val logo = provider.str("logo_path") ?: continue
            val name = providerBaseName(provider.str("provider_name").orEmpty())
            // Skip tiers and channels of a service already shown
            if (logo in logos || seenNames.any { name.isNotEmpty() && (name.startsWith(it) || it.startsWith(name)) }) continue
            logos += logo
            seenNames += name
            if (logos.size == MAX_PROVIDERS) break
        }
        return logos
    }

    companion object {
        private const val TAG = "TmdbCatalog"
        private const val RANKING_MIN_VOTES = 300
        private const val RANKING_PRIOR = 6.5
        private const val MIN_VOTES_OTHER_LANGUAGES = 1000
        private const val MAX_PROVIDERS = 4
        private val EXCLUDED_KEYWORDS = setOf("adult")

        private val PROVIDER_VARIANT_SUFFIX = Regex(
            "\\s+(amazon channel|apple tv channel|roku premium channel|channel" +
                "|standard with ads|basic with ads|with ads|premium|essential|basic|standard)$"
        )

        /** "Paramount+ Amazon Channel" / "Paramount Plus Essential" -> "paramount plus" */
        fun providerBaseName(name: String): String {
            var base = name.lowercase().replace("+", " plus").replace(Regex("\\s+"), " ").trim()
            while (true) {
                val stripped = PROVIDER_VARIANT_SUFFIX.replace(base, "")
                if (stripped == base) return base
                base = stripped
            }
        }

        /** Similarity ratio in [0, 1] based on the Levenshtein distance. */
        fun similarity(a: String, b: String): Double {
            val s = a.lowercase()
            val t = b.lowercase()
            if (s.isEmpty() && t.isEmpty()) return 1.0
            var previous = IntArray(t.length + 1) { it }
            for (i in 1..s.length) {
                val current = IntArray(t.length + 1)
                current[0] = i
                for (j in 1..t.length) {
                    val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                }
                previous = current
            }
            return 1.0 - previous[t.length].toDouble() / maxOf(s.length, t.length)
        }
    }
}
