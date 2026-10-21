package com.rfmarcos.tmdbwallpapers

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
    val watchStatus: WatchStatus? = null,
)

/** Why a title of a list is not used. */
enum class Exclusion { LOCAL_MARKET, TOO_OLD, ADULT, WATCHED, NO_IMAGE, DUPLICATE }

/** A title of a list and, when it is filtered out, the reason. */
data class Candidate(val ref: TitleRef, val exclusion: Exclusion? = null)

/**
 * Title selection and wallpaper data. TMDB lists use the same rules as TMDB.py
 * (androidtvbackground-reddit); every wallpaper is built from TMDB details whatever list it comes from.
 */
class TmdbCatalog(
    private val client: TmdbClient,
    private val language: String,
    private val region: String,
    private val showProviders: Boolean,
) {
    private val languageCode = Languages.code(language)
    private val texts = Languages.texts(language)
    private val details = mutableMapOf<TitleRef, JsonObject>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val today = dateFormat.format(Calendar.getInstance().time)
    /** Oldest release / last air date allowed, or null without age limit. */
    private val cutoff: String? = Settings.maxAgeMonths.takeIf { it > 0 }?.let { months ->
        dateFormat.format(Calendar.getInstance().apply { add(Calendar.MONTH, -months) }.time)
    }

    /**
     * Candidate titles of a list, in the order they should be tried, including the ones filtered out
     * (with the reason). Filters apply to automatic lists, or to every list if the user chose so.
     * With [shuffle] the whole list is shuffled first (random selection).
     */
    fun candidates(source: ListSource, pool: Int, trakt: TraktClient?, mdbList: MdbListClient?, shuffle: Boolean = false): List<Candidate> {
        val fetch = if (shuffle) RANDOM_POOL else pool + 5
        var refs = when (source.provider) {
            SourceProvider.TMDB -> tmdbRefs(source.key, fetch)
            SourceProvider.TRAKT -> trakt?.titles(source.key, fetch).orEmpty()
            SourceProvider.MDBLIST -> source.key.removePrefix("mdblist:").toLongOrNull()
                ?.let { mdbList?.titles(it, fetch, client) }.orEmpty()
        }.distinct()
        if (shuffle) refs = refs.shuffled()
        val filtered = Settings.filterScope == Settings.SCOPE_ALL || source.automatic
        return refs.asSequence()
            .map { Candidate(it, if (filtered) exclusion(it) else null) }
            .takeIncluded(pool)
    }

    /** Title and year of a title in the configured language (for listing a list's contents). */
    fun summary(ref: TitleRef): Pair<String, String>? {
        val item = details(ref) ?: return null
        val title = item.str(if (ref.type == MediaType.MOVIE) "title" else "name") ?: return null
        return title to item.str(if (ref.type == MediaType.MOVIE) "release_date" else "first_air_date").orEmpty().take(4)
    }

    // --- TMDB lists ---

    private fun tmdbRefs(key: String, count: Int): List<TitleRef> {
        val account = "account/${Settings.tmdbAccountId}"
        val session = "session_id=${Settings.tmdbSessionId}&sort_by=created_at.desc"
        return when (key) {
            "tmdb:trending_movies" -> trending(MediaType.MOVIE)
            "tmdb:trending_tv" -> trending(MediaType.TV)
            "tmdb:popular_movies" -> pages("movie/popular?region=$region", MediaType.MOVIE, count)
            "tmdb:popular_tv" -> pages("tv/popular", MediaType.TV, count)
            "tmdb:now_playing" -> pages("movie/now_playing?region=$region", MediaType.MOVIE, count)
            "tmdb:upcoming" -> pages("movie/upcoming?region=$region", MediaType.MOVIE, count)
            "tmdb:top_rated_movies" -> pages("movie/top_rated", MediaType.MOVIE, count)
            "tmdb:top_rated_tv" -> pages("tv/top_rated", MediaType.TV, count)
            "tmdb:on_the_air" -> pages("tv/on_the_air", MediaType.TV, count)
            "tmdb:watchlist_movies" -> pages("$account/watchlist/movies?$session", MediaType.MOVIE, count)
            "tmdb:watchlist_tv" -> pages("$account/watchlist/tv?$session", MediaType.TV, count)
            "tmdb:favorite_movies" -> pages("$account/favorite/movies?$session", MediaType.MOVIE, count)
            "tmdb:favorite_tv" -> pages("$account/favorite/tv?$session", MediaType.TV, count)
            else -> emptyList()
        }
    }

    /** Weekly trending + popular titles within the age limit, ranked by weighted rating (as TMDB.py). */
    private fun trending(type: MediaType): List<TitleRef> {
        val dates = cutoff?.let {
            if (type == MediaType.MOVIE) "&release_date.gte=$it&release_date.lte=$today" else "&first_air_date.gte=$it&first_air_date.lte=$today"
        }.orEmpty()
        val discover = if (type == MediaType.MOVIE) {
            "discover/movie?sort_by=popularity.desc&language=$language&include_adult=false&page=1" +
                "&with_release_type=4|5|6&include_video=false&vote_average.gte=1&vote_count.gte=50" +
                "&with_runtime.gte=15&without_genres=99$dates"
        } else {
            "discover/tv?sort_by=popularity.desc&language=$language&include_adult=false&page=1" +
                "&vote_average.gte=1&vote_count.gte=50&with_runtime.gte=15&without_genres=99|16$dates"
        }
        val items = linkedMapOf<Int, JsonObject>()
        listOf("trending/${type.path}/week?language=$language", discover).forEach { query ->
            client.get(query)?.objects("results")?.forEach { item ->
                item.int("id")?.let { items.putIfAbsent(it, item) }
            }
        }
        return items.values.sortedByDescending(::weightedRating).mapNotNull { item -> item.int("id")?.let { TitleRef(type, it) } }
    }

    /** Pages of a paginated TMDB list (20 titles per page). */
    private fun pages(path: String, type: MediaType, count: Int): List<TitleRef> {
        val separator = if ('?' in path) '&' else '?'
        val pageCount = ((count + 19) / 20).coerceIn(1, 5)
        return (1..pageCount).flatMap { page ->
            client.get("$path${separator}language=$language&page=$page")?.objects("results").orEmpty()
        }.mapNotNull { item -> item.int("id")?.let { TitleRef(type, it) } }
    }

    /** Keep candidates until [count] of them are usable (filtered ones are kept to explain them). */
    private fun Sequence<Candidate>.takeIncluded(count: Int): List<Candidate> {
        val result = mutableListOf<Candidate>()
        var included = 0
        for (candidate in this) {
            result += candidate
            if (candidate.exclusion == null && ++included >= count) break
        }
        return result
    }

    /** Bayesian average: few votes pull the score towards a typical TMDB rating. */
    private fun weightedRating(item: JsonObject): Double {
        val votes = item.int("vote_count") ?: 0
        val average = item.double("vote_average") ?: 0.0
        return (votes * average + RANKING_MIN_VOTES * RANKING_PRIOR) / (votes + RANKING_MIN_VOTES)
    }

    // --- Filters (from the TMDB details of each title) ---

    private fun exclusion(ref: TitleRef): Exclusion? {
        val item = details(ref) ?: return null
        return when {
            isLocalMarket(item) -> Exclusion.LOCAL_MARKET
            isTooOld(ref.type, item) -> Exclusion.TOO_OLD
            Settings.excludeAdult && isAdult(ref, item) -> Exclusion.ADULT
            else -> null
        }
    }

    /** Local-market titles only pass when they are worldwide hits. */
    private fun isLocalMarket(item: JsonObject): Boolean {
        if (!Settings.globalHitsOnly) return false
        val originalLanguage = item.str("original_language")?.lowercase().orEmpty()
        return originalLanguage !in Languages.MAINSTREAM && originalLanguage != languageCode &&
            (item.int("vote_count") ?: 0) < MIN_VOTES_OTHER_LANGUAGES
    }

    /** Released (movies) or last aired (TV) before the age limit. */
    private fun isTooOld(type: MediaType, item: JsonObject): Boolean {
        val limit = cutoff ?: return false
        val date = item.str(if (type == MediaType.MOVIE) "release_date" else "last_air_date")
        return !date.isNullOrEmpty() && date < limit
    }

    private fun isAdult(ref: TitleRef, item: JsonObject): Boolean =
        item.bool("adult") == true || client.get("${ref.type.path}/${ref.tmdbId}/keywords")
            ?.objects(if (ref.type == MediaType.MOVIE) "keywords" else "results")
            ?.any { it.str("name")?.lowercase() in EXCLUDED_KEYWORDS } == true

    private fun details(ref: TitleRef): JsonObject? =
        details[ref] ?: client.get("${ref.type.path}/${ref.tmdbId}?language=$language")?.also { details[ref] = it }

    // --- Wallpaper data ---

    /** Build the wallpaper data from TMDB details; null when the title has no usable backdrop. */
    fun build(ref: TitleRef): WallpaperInfo? {
        val type = ref.type
        val item = details(ref) ?: return null
        val title = item.str(if (type == MediaType.MOVIE) "title" else "name") ?: return null
        val backdrop = textlessBackdrop(ref, item.str("backdrop_path")) ?: return null

        val extraInfo = if (type == MediaType.MOVIE) {
            val runtime = item.int("runtime") ?: 0
            if (runtime > 0) "${runtime / 60}h ${runtime % 60}min" else ""
        } else {
            val seasons = item.int("number_of_seasons") ?: 0
            if (seasons > 0) "$seasons ${if (seasons == 1) texts.season else texts.seasons}" else ""
        }

        // Untranslated synopsis: fall back to English rather than leaving it empty
        val overview = item.str("overview").orEmpty().ifBlank {
            client.get("${type.path}/${ref.tmdbId}?language=en-US")?.str("overview").orEmpty()
        }

        return WallpaperInfo(
            type = type,
            id = ref.tmdbId,
            title = title,
            overview = overview,
            year = item.str(if (type == MediaType.MOVIE) "release_date" else "first_air_date").orEmpty().take(4),
            rating = item.double("vote_average") ?: 0.0,
            extraInfo = extraInfo,
            genres = item.objects("genres").mapNotNull { it.str("name") }
                .map { if (type == MediaType.TV) Languages.translateTvGenre(language, it) else it }
                .take(2)
                .joinToString(", "),
            backdropPath = backdrop,
            logoPath = logoPath(ref, item, title),
            providerLogoPaths = if (showProviders) providerLogoPaths(ref) else emptyList(),
        )
    }

    /** Prefer a backdrop without baked-in text, so it doesn't clash with the logo. */
    private fun textlessBackdrop(ref: TitleRef, default: String?): String? {
        val textless = client.get("${ref.type.path}/${ref.tmdbId}/images?include_image_language=null")
            ?.objects("backdrops")
            ?.filter { it.str("iso_639_1") == null && (it.int("width") ?: 0) >= 1920 }
            ?.mapNotNull { it.str("file_path") }
            .orEmpty()
        return if (default in textless || textless.isEmpty()) default else textless.first()
    }

    private fun logoPath(ref: TitleRef, item: JsonObject, title: String): String? {
        val type = ref.type
        val original = item.str(if (type == MediaType.MOVIE) "original_title" else "original_name").orEmpty()
        val originalLanguage = item.str("original_language").orEmpty()

        var logo = if (similarity(title, original) > 0.9) {
            multiLanguageLogo(ref, originalLanguage)
        } else {
            logoIn(ref, languageCode)
        }

        if (logo == null && originalLanguage != "en") {
            val english = client.get("${type.path}/${ref.tmdbId}?language=en-US")
                ?.str(if (type == MediaType.MOVIE) "title" else "name")
                .orEmpty()
            if (similarity(title, english) > 0.9) logo = logoIn(ref, "en")
        }
        return logo
    }

    private fun logoIn(ref: TitleRef, code: String): String? =
        client.get("${ref.type.path}/${ref.tmdbId}/images?language=$code")?.objects("logos")
            ?.firstOrNull { it.str("iso_639_1") == code && it.str("file_path")?.endsWith(".png") == true }
            ?.str("file_path")

    private fun multiLanguageLogo(ref: TitleRef, originalLanguage: String): String? {
        val logos = client.get("${ref.type.path}/${ref.tmdbId}/images?include_image_language=$languageCode,$originalLanguage")
            ?.objects("logos")
            ?.filter { it.str("file_path")?.endsWith(".png") == true }
            .orEmpty()
        return (logos.firstOrNull { it.str("iso_639_1") == languageCode }
            ?: logos.firstOrNull { it.str("iso_639_1") == originalLanguage })
            ?.str("file_path")
    }

    private fun providerLogoPaths(ref: TitleRef): List<String> {
        val providers = client.get("${ref.type.path}/${ref.tmdbId}/watch/providers")
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
        private const val RANKING_MIN_VOTES = 300
        private const val RANKING_PRIOR = 6.5
        private const val MIN_VOTES_OTHER_LANGUAGES = 1000
        private const val MAX_PROVIDERS = 4
        private const val RANDOM_POOL = 100
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
