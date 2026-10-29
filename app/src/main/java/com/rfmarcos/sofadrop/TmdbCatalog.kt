package com.rfmarcos.sofadrop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class MediaType(val path: String) { MOVIE("movie"), TV("tv") }

/** Everything needed to draw one wallpaper. */
data class WallpaperInfo(
    val type: MediaType,
    val id: Int,
    val title: String,
    val overview: String,
    val year: String,
    /** TMDB's own score, 0-10. */
    val rating: Double,
    val extraInfo: String,
    val genres: String,
    val backdropPath: String,
    val logoPath: String?,
    val providerLogoPaths: List<String>,
    val watchStatus: WatchStatus? = null,
    /** Scores drawn on the wallpaper, in order (see [Rating.pick]). */
    val ratings: List<Rating> = emptyList(),
    /** For the IMDb rating without MDBList. */
    val imdbId: String? = null,
    /** List the title was taken from, for the footer ("Trending · TMDB"). */
    val sourceLabel: String? = null,
    val sourceProvider: SourceProvider? = null,
    /** Age certification of the streaming country ("16", "TV-MA"), see [Certifications]. */
    val certification: String? = null,
    /** Main cast, most important first (see [TmdbCatalog.castNames]); empty unless shown. */
    val cast: List<String> = emptyList(),
)

/** Why a title of a list is not used. */
enum class Exclusion { LOCAL_MARKET, TOO_OLD, ADULT, AGE_RATING, GENRE, NO_LOGO, NO_SYNOPSIS, WATCHED, NO_IMAGE, DUPLICATE }

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
    /** The logo and synopsis may use other languages than the rest of the wallpaper. */
    private val parts: PartLanguages = PartLanguages(language, language),
    private val showCast: Boolean = false,
) {
    private val languageCode = Languages.code(language)
    private val texts = Languages.texts(language)
    private val details = ConcurrentHashMap<TitleRef, JsonObject>()
    private val translations = ConcurrentHashMap<TitleRef, List<JsonObject>>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val today = dateFormat.format(Calendar.getInstance().time)
    /** Oldest release / air date allowed for an age limit (months, or a year), or null without one. */
    private fun cutoff(maxAge: Int): String? = when {
        maxAge <= 0 -> null
        Settings.isYear(maxAge) -> "$maxAge-01-01"
        else -> dateFormat.format(Calendar.getInstance().apply { add(Calendar.MONTH, -maxAge) }.time)
    }

    /**
     * Candidate titles of a list, in the order they should be tried, including the ones filtered out
     * (with the reason), using the list's own filters (see [Settings.filtersFor]).
     * With [shuffle] the whole list is shuffled first (random selection).
     */
    fun candidates(source: ListSource, pool: Int, trakt: TraktClient?, simkl: SimklClient?, mdbList: MdbListClient?, shuffle: Boolean = false): List<Candidate> {
        val fetch = if (shuffle) RANDOM_POOL else pool + 5
        val filters = Settings.filtersFor(source)
        val cutoff = cutoff(filters.maxAgeMonths)
        var refs = when (source.provider) {
            SourceProvider.TMDB -> tmdbRefs(source.key, fetch, cutoff)
            SourceProvider.TRAKT -> trakt?.titles(source.key, fetch).orEmpty()
            SourceProvider.SIMKL -> simkl?.titles(source.key, fetch).orEmpty()
            SourceProvider.MDBLIST -> source.key.removePrefix("mdblist:").let { list ->
                if ('/' in list) MdbListClient.publicTitles(list, fetch)
                else list.toLongOrNull()?.let { mdbList?.titles(it, fetch, client) }.orEmpty()
            }
        }.distinct()
        if (shuffle) refs = refs.shuffled()
        val check = cutoff != null || filters.globalHits || Settings.excludeAdult || Settings.maxRatingAge > 0 ||
            !filters.genres.isOff || Settings.requireLogo || Settings.requireSynopsis
        // Checked a batch at a time in parallel: at most one batch more than needed
        return refs.asSequence()
            .chunked(CHECK_BATCH)
            .flatMap { batch -> WallpaperFactory.parallel(batch) { Candidate(it, if (check) exclusion(it, cutoff, filters) else null) } }
            .takeIncluded(pool)
    }

    /** Title and year of a title in the configured language (for listing a list's contents). */
    fun summary(ref: TitleRef): Pair<String, String>? {
        val item = details(ref) ?: return null
        val title = item.str(if (ref.type == MediaType.MOVIE) "title" else "name") ?: return null
        return title to item.str(if (ref.type == MediaType.MOVIE) "release_date" else "first_air_date").orEmpty().take(4)
    }

    // --- TMDB lists ---

    private fun tmdbRefs(key: String, count: Int, cutoff: String?): List<TitleRef> {
        val account = "account/${Settings.tmdbAccountId}"
        val session = "session_id=${Settings.tmdbSessionId}&sort_by=created_at.desc"
        return when (key) {
            "tmdb:trending_movies" -> trending(MediaType.MOVIE, cutoff, count)
            "tmdb:trending_tv" -> trending(MediaType.TV, cutoff, count)
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
    private fun trending(type: MediaType, cutoff: String?, count: Int): List<TitleRef> {
        val dates = cutoff?.let {
            if (type == MediaType.MOVIE) "&release_date.gte=$it&release_date.lte=$today" else "&first_air_date.gte=$it&first_air_date.lte=$today"
        }.orEmpty()
        val discover = if (type == MediaType.MOVIE) {
            "discover/movie?sort_by=popularity.desc&language=$language&include_adult=false" +
                "&with_release_type=4|5|6&include_video=false&vote_average.gte=1&vote_count.gte=50" +
                "&with_runtime.gte=15&without_genres=99$dates"
        } else {
            "discover/tv?sort_by=popularity.desc&language=$language&include_adult=false" +
                "&vote_average.gte=1&vote_count.gte=50&with_runtime.gte=15&without_genres=99|16$dates"
        }
        val items = linkedMapOf<Int, JsonObject>()
        // Both sources overlap, so read about as many pages of each as the list needs (20 titles per page)
        val pageCount = ((count + 19) / 20).coerceIn(1, 5)
        for (page in 1..pageCount) {
            val queries = listOf("trending/${type.path}/week?language=$language&page=$page", "$discover&page=$page")
            WallpaperFactory.parallel(queries) { client.get(it) }.forEach { page ->
                page?.objects("results")?.forEach { item ->
                    item.int("id")?.let { items.putIfAbsent(it, item) }
                }
            }
            if (items.size >= count) break
        }
        return items.values.sortedByDescending(::weightedRating).mapNotNull { item -> item.int("id")?.let { TitleRef(type, it) } }
    }

    /** Pages of a paginated TMDB list (20 titles per page). */
    private fun pages(path: String, type: MediaType, count: Int): List<TitleRef> {
        val separator = if ('?' in path) '&' else '?'
        val pageCount = ((count + 19) / 20).coerceIn(1, 5)
        return WallpaperFactory.parallel((1..pageCount).toList()) { page ->
            client.get("$path${separator}language=$language&page=$page")?.objects("results").orEmpty()
        }.flatten().toList().mapNotNull { item -> item.int("id")?.let { TitleRef(type, it) } }
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

    private fun exclusion(ref: TitleRef, cutoff: String?, filters: FilterValues): Exclusion? {
        val item = details(ref) ?: return null
        return when {
            filters.globalHits && isLocalMarket(item) -> Exclusion.LOCAL_MARKET
            isTooOld(ref.type, item, cutoff, Settings.isYear(filters.maxAgeMonths)) -> Exclusion.TOO_OLD
            Settings.excludeAdult && isAdult(ref, item) -> Exclusion.ADULT
            // Unrated titles (mostly not released yet) pass
            Settings.maxRatingAge > 0 && (Certifications.minimumAge(ref.type, item, region) ?: 0) > Settings.maxRatingAge -> Exclusion.AGE_RATING
            !filters.genres.allows(item.objects("genres").mapNotNull { it.int("id") }) -> Exclusion.GENRE
            // Last: it costs an images request per title
            Settings.requireLogo && logoPath(ref, item) == null -> Exclusion.NO_LOGO
            Settings.requireSynopsis && localOverview(ref, item).isBlank() -> Exclusion.NO_SYNOPSIS
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

    /**
     * Released (movies) or last aired (TV) before the age limit. From a year, TV counts from its first
     * air date, the year the wallpaper shows: a show from 1963 that still airs isn't "from 2000".
     */
    private fun isTooOld(type: MediaType, item: JsonObject, cutoff: String?, fromYear: Boolean): Boolean {
        val limit = cutoff ?: return false
        val date = item.str(when {
            type == MediaType.MOVIE -> "release_date"
            fromYear -> "first_air_date"
            else -> "last_air_date"
        })
        return !date.isNullOrEmpty() && date < limit
    }

    private fun isAdult(ref: TitleRef, item: JsonObject): Boolean =
        item.bool("adult") == true || item.obj("keywords")
            ?.objects(if (ref.type == MediaType.MOVIE) "keywords" else "results")
            ?.any { it.str("name")?.lowercase() in EXCLUDED_KEYWORDS } == true

    // Series only have their IMDb id in external_ids (movies carry imdb_id); keywords for the adult filter;
    // release dates and content ratings for the age certification
    private fun details(ref: TitleRef): JsonObject? =
        details[ref] ?: client.get("${ref.type.path}/${ref.tmdbId}?language=$language&append_to_response=keywords," +
            if (ref.type == MediaType.TV) "external_ids,content_ratings" else "release_dates")
            ?.also { details[ref] = it }

    // --- Wallpaper data ---

    /**
     * Build the wallpaper data from TMDB details; null when the title has no usable backdrop.
     * [matchedGenres] (the genres its list only allows) are shown first.
     */
    fun build(ref: TitleRef, matchedGenres: Set<Int> = emptySet()): WallpaperInfo? {
        val type = ref.type
        val item = details(ref) ?: return null
        if (item.str(titleKey(type)) == null) return null
        val backdrop = textlessBackdrop(ref, item.str("backdrop_path")) ?: return null

        val extraInfo = if (type == MediaType.MOVIE) {
            val runtime = item.int("runtime") ?: 0
            if (runtime > 0) "${runtime / 60}h ${runtime % 60}min" else ""
        } else {
            val seasons = item.int("number_of_seasons") ?: 0
            if (seasons > 0) texts.seasonCount(seasons) else ""
        }

        // Untranslated synopsis: English only when the user prefers it to none
        val overview = if (!Settings.drawsDetails) "" else localOverview(ref, item).ifBlank {
            if (Settings.synopsisMode == Settings.SYNOPSIS_ENGLISH) translated(translations(ref), "en", "US", "overview") else ""
        }

        return WallpaperInfo(
            type = type,
            id = ref.tmdbId,
            title = titleIn(type, item, { translations(ref) }, parts.title, language),
            overview = overview,
            year = item.str(if (type == MediaType.MOVIE) "release_date" else "first_air_date").orEmpty().take(4),
            rating = item.double("vote_average") ?: 0.0,
            extraInfo = extraInfo,
            // The genres a list was filtered by first: TMDB's own order is arbitrary, so the ones shown
            // could otherwise be ones that played no part in it
            genres = item.objects("genres")
                .sortedByDescending { it.int("id") in matchedGenres }
                .mapNotNull { Languages.genreName(language, it.int("id"), it.str("name")) }
                .take(2)
                .joinToString(", "),
            backdropPath = backdrop,
            logoPath = if (Settings.drawsLogo) logoPath(ref, item) else null,
            providerLogoPaths = if (showProviders) providerLogoPaths(ref) else emptyList(),
            imdbId = item.str("imdb_id") ?: item.obj("external_ids")?.str("imdb_id"),
            certification = Certifications.of(type, item, region)?.let { Certifications.label(region, it) },
            cast = if (showCast) cast(ref) else emptyList(),
        )
    }

    // Its own request, only for the titles drawn: a series' aggregate credits (whole run, by episodes)
    // can be 300 KB, and its plain credits only list the latest season (no Pedro Pascal in The Last of Us)
    private fun cast(ref: TitleRef): List<String> = castNames(
        client.get("${ref.type.path}/${ref.tmdbId}/${if (ref.type == MediaType.TV) "aggregate_credits" else "credits"}?language=$language")
    )

    /** Prefer a backdrop without baked-in text, so it doesn't clash with the logo. */
    private fun textlessBackdrop(ref: TitleRef, default: String?): String? {
        val textless = client.get("${ref.type.path}/${ref.tmdbId}/images?include_image_language=null")
            ?.objects("backdrops")
            ?.filter { it.str("iso_639_1") == null && (it.int("width") ?: 0) >= 1920 }
            ?.mapNotNull { it.str("file_path") }
            .orEmpty()
        return if (default in textless || textless.isEmpty()) default else textless.first()
    }

    private fun logoPath(ref: TitleRef, item: JsonObject): String? {
        val originalLanguage = item.str("original_language").orEmpty()
        val code = parts.title?.let(Languages::code) ?: originalLanguage
        val logos = client.get("${ref.type.path}/${ref.tmdbId}/images?include_image_language=$code,$originalLanguage,en")
            ?.objects("logos").orEmpty()
        return pickLogo(logos, item, ref.type, { translations(ref) }, parts.title, language, Settings.logoFallback)
    }

    /** Synopsis in the synopsis language, from another country if needed (es-MX for es-ES); empty when there is none. */
    private fun localOverview(ref: TitleRef, item: JsonObject): String = overviewIn(item, { translations(ref) }, parts.synopsis, language)

    /** TMDB translations of a title (title and synopsis), one per language and country. */
    private fun translations(ref: TitleRef): List<JsonObject> = translations.getOrPut(ref) {
        client.get("${ref.type.path}/${ref.tmdbId}/translations")?.objects("translations").orEmpty()
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
        private const val CHECK_BATCH = 8
        private val EXCLUDED_KEYWORDS = setOf("adult")
        /** Names kept for the cast row; the renderer draws the ones that fit on its line. */
        private const val MAX_CAST = 4

        /**
         * First names of TMDB credits (already ordered by importance, or by episodes for a series),
         * skipping the ones only written in another script: TMDB gives a person's name in Latin letters
         * when it has it ("Lee Jung-jae") and in Hangul or kanji otherwise, which every wallpaper language
         * (all in Latin script) would show as foreign text.
         */
        fun castNames(credits: JsonObject?): List<String> = credits?.objects("cast").orEmpty()
            .mapNotNull { it.str("name")?.trim()?.takeIf(String::isNotEmpty) }
            .filter { name -> name.all { !it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } }
            .distinct()
            .take(MAX_CAST)

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

        private fun titleKey(type: MediaType) = if (type == MediaType.MOVIE) "title" else "name"

        private fun originalKey(type: MediaType) = if (type == MediaType.MOVIE) "original_title" else "original_name"

        /** Language of the title's own translation: TMDB marks Cantonese titles "cn" and translates them as "zh". */
        private fun originalCode(item: JsonObject) = item.str("original_language").orEmpty().let { if (it == "cn") "zh" else it }

        /** First non-blank [field] of the translations in [code], those made for [country] first. */
        fun translated(translations: List<JsonObject>, code: String, country: String?, field: String): String =
            translations.filter { it.str("iso_639_1") == code }
                .sortedByDescending { country != null && it.str("iso_3166_1") == country }
                .firstNotNullOfOrNull { it.obj("data")?.str(field)?.takeIf(String::isNotBlank) }.orEmpty()

        /**
         * Title in [locale] (null: the original title). Like TMDB's own `?language=`, an untranslated
         * title is the original one; [general] is the language [item] was fetched in.
         */
        fun titleIn(type: MediaType, item: JsonObject, translations: () -> List<JsonObject>, locale: String?, general: String): String {
            val title = item.str(titleKey(type)).orEmpty()
            val original = item.str(originalKey(type)).orEmpty()
            return when (locale) {
                general -> title
                null -> original
                else -> translations()
                    .firstOrNull { it.str("iso_639_1") == Languages.code(locale) && it.str("iso_3166_1") == Languages.regionOf(locale) }
                    ?.obj("data")?.str(titleKey(type))?.takeIf(String::isNotBlank) ?: original
            }.ifBlank { title }
        }

        /** Synopsis in [locale] (null: the original language), from another country if needed; empty when there is none. */
        fun overviewIn(item: JsonObject, translations: () -> List<JsonObject>, locale: String?, general: String): String = when (locale) {
            general -> item.str("overview").orEmpty().ifBlank { translated(translations(), Languages.code(general), null, "overview") }
            null -> translated(translations(), originalCode(item), (item.array("origin_country").firstOrNull() as? JsonPrimitive)?.content, "overview")
            else -> translated(translations(), Languages.code(locale), Languages.regionOf(locale), "overview")
        }

        /**
         * Logo in [locale] (null: the original language), else one in another language whose title reads
         * the same, else with [fallback] the original-language or English one; null to write the title.
         */
        fun pickLogo(
            logos: List<JsonObject>, item: JsonObject, type: MediaType, translations: () -> List<JsonObject>,
            locale: String?, general: String, fallback: Boolean,
        ): String? {
            val original = item.str(originalKey(type)).orEmpty()
            val originalLanguage = item.str("original_language").orEmpty()
            val code = locale?.let(Languages::code) ?: originalLanguage
            val title = titleIn(type, item, translations, locale, general)
            val pngs = logos.filter { it.str("file_path")?.endsWith(".png") == true }
            fun inLanguage(code: String) = pngs.filter { it.str("iso_639_1") == code }

            val local = inLanguage(code)
            // Made for the locale's country (or for none), any country for the original language
            var logo = local.firstOrNull { it.str("iso_3166_1").let { c -> c == null || locale == null || c == Languages.regionOf(locale) } }
                ?.str("file_path")
            // Another country's only when its title is ours too: an "es-MX" logo can read "Código: Venganza" where Spain's title is "El motín"
            if (logo == null && local.isNotEmpty()) {
                val titles = translations().filter { it.str("iso_639_1") == code }.mapNotNull { t ->
                    val name = t.obj("data")?.str(titleKey(type))?.takeIf { it.isNotBlank() }
                    t.str("iso_3166_1")?.let { c -> name?.let { c to it } }
                }.toMap()
                logo = local.firstOrNull { l -> titles[l.str("iso_3166_1")]?.let { similarity(it, title) > 0.9 } == true }?.str("file_path")
            }
            // An untranslated title reads the same on the original-language logo
            if (logo == null && similarity(title, original) > 0.9) logo = inLanguage(originalLanguage).firstOrNull()?.str("file_path")
            if (logo == null && originalLanguage != "en" && similarity(title, titleIn(type, item, translations, "en-US", general)) > 0.9) {
                logo = inLanguage("en").firstOrNull()?.str("file_path")
            }
            // Optional: a logo in another language beats the title written as plain text
            if (logo == null && fallback) logo = (inLanguage(originalLanguage) + inLanguage("en")).firstOrNull()?.str("file_path")
            return logo
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
