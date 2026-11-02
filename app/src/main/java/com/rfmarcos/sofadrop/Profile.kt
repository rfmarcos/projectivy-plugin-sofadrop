package com.rfmarcos.sofadrop

import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The settings of one set of wallpapers: [WALLPAPER], the one Projectivy gets (with the keys every version
 * used), or [SCREENSAVER]. The screensaver uses the wallpaper's lists and look until the user gives it its
 * own ([Settings.screensaverOwnLists], [Settings.screensaverOwnLook]); then they're stored apart, with "ss_".
 * Accounts, keys, language, blocked titles and what a click opens are shared, in [Settings].
 */
class Profile private constructor(val id: String) {

    val isScreensaver: Boolean get() = this === SCREENSAVER

    /** The screensaver has its own set: lists or look of its own. */
    val separate: Boolean get() = isScreensaver && (Settings.screensaverOwnLists || Settings.screensaverOwnLook)

    /** The profile whose set this one shows: the screensaver shows the wallpapers' unless it has its own. */
    val source: Profile get() = if (isScreensaver && !separate) WALLPAPER else this

    private val preferences get() = Settings.preferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun lists(key: String) = if (isScreensaver && Settings.screensaverOwnLists) OWN_PREFIX + key else key
    private fun look(key: String) = if (isScreensaver && Settings.screensaverOwnLook) OWN_PREFIX + key else key
    // "_last_generation" -> "_ss_last_generation"
    private fun state(key: String) = if (isScreensaver) "_ss$key" else key

    private fun string(key: String, default: String = "") = preferences.getString(key, null)?.trim().orEmpty().ifEmpty { default }

    // --- Lists and filters ---

    /** Selected list keys, e.g. "tmdb:trending_movies", "trakt:watchlist_shows", "mdblist:1234". */
    var lists: Set<String>
        get() = preferences.getStringSet(lists(KEY_LISTS), null)?.toSet() ?: Settings.DEFAULT_LISTS
        set(value) = preferences.edit { putStringSet(lists(KEY_LISTS), value) }

    var titlesPerList: Int
        get() = preferences.getInt(lists(KEY_COUNT), 10)
        set(value) = preferences.edit { putInt(lists(KEY_COUNT), value) }

    var globalHitsOnly: Boolean
        get() = preferences.getBoolean(lists(KEY_GLOBAL_HITS), true)
        set(value) = preferences.edit { putBoolean(lists(KEY_GLOBAL_HITS), value) }

    /**
     * Skip titles released (or, for TV, last aired) longer ago than this many months, or released
     * before this year (see [Settings.isYear]); 0 = no limit.
     */
    var maxAgeMonths: Int
        get() = preferences.getInt(lists(KEY_MAX_AGE), 0)
        set(value) = preferences.edit { putInt(lists(KEY_MAX_AGE), value) }

    var excludeAdult: Boolean
        get() = preferences.getBoolean(lists(KEY_EXCLUDE_ADULT), true)
        set(value) = preferences.edit { putBoolean(lists(KEY_EXCLUDE_ADULT), value) }

    /** Skip titles certified for older viewers than this, in every list (see [Certifications.minimumAge]); 0 = no limit. */
    var maxRatingAge: Int
        get() = preferences.getInt(lists(KEY_MAX_RATING_AGE), 0)
        set(value) = preferences.edit { putInt(lists(KEY_MAX_RATING_AGE), value) }

    /** Skip titles with a lower TMDB score (titles with too few votes to tell pass); 0 = no limit. */
    var minRating: Double
        get() = preferences.getFloat(lists(KEY_MIN_RATING), 0f).toDouble()
        set(value) = preferences.edit { putFloat(lists(KEY_MIN_RATING), value.toFloat()) }

    /** Default genre filter; like the age, it only applies by default to automatic lists (see [filtersFor]). */
    var genreFilter: GenreFilter
        get() = try {
            json.decodeFromString<GenreFilter>(preferences.getString(lists(KEY_GENRE_FILTER), null) ?: "{}")
        } catch (_: Exception) {
            GenreFilter()
        }
        set(value) = preferences.edit { putString(lists(KEY_GENRE_FILTER), json.encodeToString(value)) }

    /** A title already taken by an earlier list is skipped there, so that list uses its next one. */
    var skipDuplicates: Boolean
        get() = preferences.getBoolean(lists(KEY_SKIP_DUPLICATES), true)
        set(value) = preferences.edit { putBoolean(lists(KEY_SKIP_DUPLICATES), value) }

    /** "order": first titles of each list; "random": random picks among the best ones. */
    var selectionMode: String
        get() = preferences.getString(lists(KEY_SELECTION), Settings.SELECTION_RANDOM) ?: Settings.SELECTION_RANDOM
        set(value) = preferences.edit { putString(lists(KEY_SELECTION), value) }

    /** What to do with titles in the watch history: "show", "mark" or "hide". */
    var watchedMode: String
        get() = preferences.getString(lists(KEY_WATCHED), Settings.WATCHED_MARK) ?: Settings.WATCHED_MARK
        set(value) = preferences.edit { putString(lists(KEY_WATCHED), value) }

    /** Per-list overrides of the default filters (list key -> values), set on each list's page in "Filters & explore lists". */
    var listFilters: Map<String, ListFilters>
        get() = try {
            json.decodeFromString<Map<String, ListFilters>>(preferences.getString(lists(KEY_LIST_FILTERS), null) ?: "{}")
        } catch (_: Exception) {
            emptyMap()
        }
        set(value) = preferences.edit { putString(lists(KEY_LIST_FILTERS), json.encodeToString(value.filterValues { !it.isDefault })) }

    /**
     * Filters a list really uses: its overrides, else the defaults. Age, global hits and genres only apply
     * by default to automatic lists (trending, popular…), so watchlists and favourites aren't emptied.
     */
    fun filtersFor(source: ListSource): FilterValues {
        val custom = listFilters[source.key]
        return FilterValues(
            maxAgeMonths = custom?.maxAge ?: defaultMaxAge(source),
            globalHits = (custom?.globalHits ?: defaultGlobalHits(source)) && source.localMarketFilter,
            watched = custom?.watched ?: watchedMode,
            genres = custom?.genres ?: defaultGenres(source),
            minRating = custom?.minRating ?: defaultMinRating(source),
        )
    }

    fun defaultMaxAge(source: ListSource) = if (source.automatic) maxAgeMonths else 0
    fun defaultGlobalHits(source: ListSource) = source.automatic && globalHitsOnly
    fun defaultGenres(source: ListSource) = if (source.automatic) genreFilter else GenreFilter()
    fun defaultMinRating(source: ListSource) = if (source.automatic) minRating else 0.0

    // --- Look ---

    var outputWidth: Int
        get() = preferences.getInt(look(KEY_WIDTH), 1920)
        set(value) = preferences.edit { putInt(look(KEY_WIDTH), value) }

    /** See [Settings.STYLES]; the settings of the parts a style hides are kept for when they come back. */
    var style: String
        get() = preferences.getString(look(KEY_STYLE), null)?.takeIf { it in Settings.STYLES } ?: Settings.STYLE_FULL
        set(value) = preferences.edit { putString(look(KEY_STYLE), value) }

    /** See [Settings.SHADES]. */
    var shade: String
        get() = preferences.getString(look(KEY_SHADE), null)?.takeIf { it in Settings.SHADES } ?: Settings.SHADE_TINT
        set(value) = preferences.edit { putString(look(KEY_SHADE), value) }

    /** See [Settings.POSITIONS]; Projectivy's rows may cover the bottom ones, depending on the user's layout. */
    var position: String
        get() = preferences.getString(look(KEY_POSITION), null)?.takeIf { it in Settings.POSITIONS } ?: Settings.POSITION_TOP_LEFT
        set(value) = preferences.edit { putString(look(KEY_POSITION), value) }

    /** See [Settings.LOGO_MODES]; earlier versions stored "logo_fallback" and "require_logo" (read until changed). */
    var logoMode: String
        get() = preferences.getString(look(KEY_LOGO_MODE), null) ?: run {
            val fallback = preferences.getBoolean(look(KEY_LOGO_FALLBACK), false)
            val required = preferences.getBoolean(look(KEY_REQUIRE_LOGO), false)
            when {
                fallback && required -> Settings.LOGO_ORIGINAL_OR_SKIP
                required -> Settings.LOGO_SKIP
                fallback -> Settings.LOGO_ORIGINAL
                else -> Settings.LOGO_TEXT
            }
        }
        set(value) = preferences.edit { putString(look(KEY_LOGO_MODE), value); remove(look(KEY_REQUIRE_LOGO)) }

    /** See [Settings.SYNOPSIS_MODES]; "logo_fallback" briefly covered the synopsis too. */
    var synopsisMode: String
        get() = preferences.getString(look(KEY_SYNOPSIS_MODE), null)
            ?: if (preferences.getBoolean(look(KEY_LOGO_FALLBACK), false)) Settings.SYNOPSIS_ENGLISH else Settings.SYNOPSIS_HIDE
        set(value) = preferences.edit { putString(look(KEY_SYNOPSIS_MODE), value) }

    /** See [Settings.PART_LANGUAGES]; [logoMode] applies when there is no logo in this language. */
    var titleLanguage: String
        get() = Settings.partLanguage(look(KEY_TITLE_LANGUAGE))
        set(value) = preferences.edit { putString(look(KEY_TITLE_LANGUAGE), value) }

    /** See [Settings.PART_LANGUAGES]; [synopsisMode] applies when there is no synopsis in this language. */
    var synopsisLanguage: String
        get() = Settings.partLanguage(look(KEY_SYNOPSIS_LANGUAGE))
        set(value) = preferences.edit { putString(look(KEY_SYNOPSIS_LANGUAGE), value) }

    /** English instead of the original language (or an untranslated title) when the original is in another alphabet. */
    var latinOriginals: Boolean
        get() = preferences.getBoolean(look(KEY_LATIN_ORIGINALS), false)
        set(value) = preferences.edit { putBoolean(look(KEY_LATIN_ORIGINALS), value) }

    /** [titleLanguage] and [synopsisLanguage] as TMDB locales. */
    val partLanguages: PartLanguages
        get() = PartLanguages(Settings.resolve(titleLanguage), Settings.resolve(synopsisLanguage))

    /** The style draws the logo (or the title instead). */
    val drawsLogo: Boolean get() = style != Settings.STYLE_BACKDROP

    /** The style draws the header, synopsis, cast and footer (each as set). */
    val drawsDetails: Boolean get() = style == Settings.STYLE_FULL

    // A part the style hides doesn't fetch anything or skip titles
    val logoFallback: Boolean get() = drawsLogo && (logoMode == Settings.LOGO_ORIGINAL || logoMode == Settings.LOGO_ORIGINAL_OR_SKIP)
    val requireLogo: Boolean get() = drawsLogo && (logoMode == Settings.LOGO_ORIGINAL_OR_SKIP || logoMode == Settings.LOGO_SKIP)
    val requireSynopsis: Boolean get() = drawsDetails && synopsisMode == Settings.SYNOPSIS_SKIP

    var showProviders: Boolean
        get() = preferences.getBoolean(look(KEY_PROVIDERS), true)
        set(value) = preferences.edit { putBoolean(look(KEY_PROVIDERS), value) }

    /** Synopsis lines on the wallpaper: fewer lines keep the info block clear of the launcher rows. */
    var overviewLines: Int
        get() = preferences.getInt(look(KEY_OVERVIEW_LINES), 4)
        set(value) = preferences.edit { putInt(look(KEY_OVERVIEW_LINES), value) }

    /** Main cast under the synopsis; off by default, it pushes the footer down. */
    var showCast: Boolean
        get() = preferences.getBoolean(look(KEY_CAST), false)
        set(value) = preferences.edit { putBoolean(look(KEY_CAST), value) }

    /** Faces of the actors shown, see [Settings.CAST_PHOTO_MODES] (together by default); only images, no API requests. */
    var castPhotos: String
        get() = preferences.getString(look(KEY_CAST_PHOTO_MODE), null)?.takeIf { it in Settings.CAST_PHOTO_MODES }
            ?: if (preferences.getBoolean(look(KEY_CAST_PHOTOS), true)) Settings.CAST_PHOTOS_GROUPED else Settings.CAST_PHOTOS_NONE
        set(value) = preferences.edit { putString(look(KEY_CAST_PHOTO_MODE), value).remove(look(KEY_CAST_PHOTOS)) }

    /**
     * With both footer parts on, show the list source only when the title has no streaming service.
     * Default for new installs; existing installs keep showing both (decided once, on first read).
     */
    var sourceFallback: Boolean
        get() {
            if (!preferences.contains(look(KEY_SOURCE_FALLBACK))) sourceFallback = lastGeneration == 0L
            return preferences.getBoolean(look(KEY_SOURCE_FALLBACK), false)
        }
        set(value) = preferences.edit { putBoolean(look(KEY_SOURCE_FALLBACK), value) }

    /** With both footer parts always on, the list source next to the streaming services instead of under them (its name shortened to fit). */
    var sourceInline: Boolean
        get() = preferences.getBoolean(look(KEY_SOURCE_INLINE), false)
        set(value) = preferences.edit { putBoolean(look(KEY_SOURCE_INLINE), value) }

    /** Footer line naming the list each title came from ("Trending · TMDB"). */
    var showSource: Boolean
        get() = preferences.getBoolean(look(KEY_SHOW_SOURCE), true)
        set(value) = preferences.edit { putBoolean(look(KEY_SHOW_SOURCE), value) }

    /** Scores shown on the wallpapers, in order; by default TMDB's, as before they could be chosen. */
    var ratingSources: List<RatingSource>
        get() = ratingSourcesKey.split(',').mapNotNull(RatingSource::of)
        set(value) = preferences.edit { putString(look(KEY_RATINGS), value.joinToString(",") { it.key }) }

    // Read without string(): empty means "no ratings", not the default
    private val ratingSourcesKey: String get() = preferences.getString(look(KEY_RATINGS), null) ?: RatingSource.TMDB.key

    var headerHidden: Set<String>
        get() = preferences.getStringSet(look(KEY_HEADER_HIDDEN), null)?.toSet() ?: emptySet()
        set(value) = preferences.edit { putStringSet(look(KEY_HEADER_HIDDEN), value) }

    fun showsInHeader(item: String) = item !in headerHidden

    /** [Settings.HEADER_BLOCKS] in the user's order (blocks added in later versions go last). */
    var headerOrder: List<String>
        get() {
            val saved = string(look(KEY_HEADER_ORDER)).split(',').filter { it in Settings.HEADER_BLOCKS }.distinct()
            return saved + (Settings.HEADER_BLOCKS - saved.toSet())
        }
        set(value) = preferences.edit { putString(look(KEY_HEADER_ORDER), value.joinToString(",")) }

    /** Ratings that stay when the line is full (the details give way first); more only where there's room. */
    var minRatings: Int
        get() = preferences.getInt(look(KEY_MIN_RATINGS), 2)
        set(value) = preferences.edit { putInt(look(KEY_MIN_RATINGS), value) }

    // --- Generation state ---

    var lastGeneration: Long
        get() = preferences.getLong(state(KEY_LAST_GENERATION), 0L)
        set(value) = preferences.edit { putLong(state(KEY_LAST_GENERATION), value) }

    /** Settings signature used for the last successful generation. */
    var lastSignature: String
        get() = preferences.getString(state(KEY_LAST_SIGNATURE), "") ?: ""
        set(value) = preferences.edit { putString(state(KEY_LAST_SIGNATURE), value) }

    var lastError: String
        get() = preferences.getString(state(KEY_LAST_ERROR), "") ?: ""
        set(value) = preferences.edit { putString(state(KEY_LAST_ERROR), value) }

    /** Something to know about the last generation that went well (e.g. drawn in 1080p for lack of memory). */
    var lastNotice: String
        get() = preferences.getString(state(KEY_LAST_NOTICE), "") ?: ""
        set(value) = preferences.edit { putString(state(KEY_LAST_NOTICE), value) }

    var generating: Boolean
        get() = preferences.getBoolean(state(KEY_GENERATING), false)
        set(value) = preferences.edit { putBoolean(state(KEY_GENERATING), value) }

    /** While generating: wallpapers made, current list and number of lists. */
    var generationProgress: Triple<Int, Int, Int>?
        get() = preferences.getString(state(KEY_PROGRESS), null)?.split('|')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) }
        set(value) = preferences.edit {
            if (value == null) remove(state(KEY_PROGRESS)) else putString(state(KEY_PROGRESS), "${value.first}|${value.second}|${value.third}")
        }

    /** Everything that changes the generated wallpapers (except the refresh interval). */
    val signature: String
        get() = with(Settings) {
            listOf(
                token.hashCode(), language, region, lists.sorted().joinToString(","), titlesPerList,
                outputWidth, globalHitsOnly, showProviders, selectionMode, watchSource?.let { "$it:$watchedMode" }.orEmpty(),
                // Without the genre field lists don't use, so updating doesn't show a pending change
                maxAgeMonths, excludeAdult, listFilters.toSortedMap().toString().replace(", genres=null", "").replace(", minRating=null", ""),
            // Only when changed, so existing installs don't see a pending change after updating
            ).plus(listOfNotNull(overviewLines.takeIf { it != 4 }, "nosource".takeIf { !showSource }, "fallback".takeIf { showSource && showProviders && sourceFallback },
                "inline".takeIf { showSource && showProviders && !sourceFallback && sourceInline },
                // Its pages are looked up when generating; nothing opens from the screensaver
                "platform".takeIf { clickPlatforms.isNotEmpty() && !this@Profile.isScreensaver },
                "dups".takeIf { !skipDuplicates }, "logo:$logoMode".takeIf { logoMode != LOGO_TEXT }, "synopsis:$synopsisMode".takeIf { synopsisMode != SYNOPSIS_HIDE },
                "titlelang:$titleLanguage".takeIf { titleLanguage != LANGUAGE_AUTO }, "synopsislang:$synopsisLanguage".takeIf { synopsisLanguage != LANGUAGE_AUTO },
                "ratings:$ratingSourcesKey:$mdbListAvailable".takeIf { ratingSourcesKey != RatingSource.TMDB.key },
                "header:${headerHidden.sorted()}".takeIf { headerHidden.isNotEmpty() },
                "ratingage:$maxRatingAge".takeIf { maxRatingAge > 0 }, "minrating:$minRating".takeIf { minRating > 0 },
                "order:${headerOrder.joinToString(",")}".takeIf { headerOrder != HEADER_BLOCKS },
                "minratings:$minRatings".takeIf { minRatings != 2 }, "cast".takeIf { showCast }, "castphotos:$castPhotos".takeIf { showCast && castPhotos != CAST_PHOTOS_GROUPED }, "style:$style".takeIf { style != STYLE_FULL },
                "position:$position".takeIf { position != POSITION_TOP_LEFT && drawsLogo }, "shade:$shade".takeIf { shade != SHADE_TINT },
                "latin".takeIf { latinOriginals },
                "trailerlang:$trailerLanguage".takeIf { trailerLanguage != LANGUAGE_AUTO && !this@Profile.isScreensaver },
                "trailerfallback:$trailerFallback".takeIf { trailerFallback != TRAILER_ENGLISH && !this@Profile.isScreensaver },
                "genres:${genreFilter.only}:${genreFilter.ids.sorted()}".takeIf { !genreFilter.isOff },
                "blocked:${blockedTitles.map { "${it.type.path}:${it.tmdbId}" }.sorted().hashCode()}".takeIf { blockedTitles.isNotEmpty() })).joinToString("|")
        }

    /** True when the current wallpapers were generated with different settings. */
    val hasPendingChanges: Boolean
        get() = lastGeneration > 0 && lastSignature != signature

    companion object {
        val WALLPAPER = Profile("wallpaper")
        val SCREENSAVER = Profile("screensaver")

        fun of(id: String?): Profile = if (id == SCREENSAVER.id) SCREENSAVER else WALLPAPER

        /** Before the keys of the screensaver's own lists and look ("ss_lists"). */
        const val OWN_PREFIX = "ss_"

        private const val KEY_LISTS = "lists"
        private const val KEY_COUNT = "titles_per_list"
        private const val KEY_GLOBAL_HITS = "global_hits_only"
        private const val KEY_MAX_AGE = "max_age_months"
        private const val KEY_EXCLUDE_ADULT = "exclude_adult"
        private const val KEY_MAX_RATING_AGE = "max_rating_age"
        private const val KEY_MIN_RATING = "min_rating"
        private const val KEY_GENRE_FILTER = "genre_filter"
        private const val KEY_SKIP_DUPLICATES = "skip_duplicates"
        private const val KEY_SELECTION = "selection_mode"
        private const val KEY_WATCHED = "watched_mode"
        private const val KEY_LIST_FILTERS = "list_filters"

        private const val KEY_WIDTH = "output_width"
        private const val KEY_STYLE = "style"
        private const val KEY_POSITION = "block_position"
        private const val KEY_SHADE = "shade"
        private const val KEY_LATIN_ORIGINALS = "latin_originals"
        private const val KEY_LOGO_MODE = "logo_mode"
        private const val KEY_LOGO_FALLBACK = "logo_fallback"
        private const val KEY_REQUIRE_LOGO = "require_logo"
        private const val KEY_SYNOPSIS_MODE = "synopsis_mode"
        private const val KEY_TITLE_LANGUAGE = "title_language"
        private const val KEY_SYNOPSIS_LANGUAGE = "synopsis_language"
        private const val KEY_PROVIDERS = "show_providers"
        private const val KEY_OVERVIEW_LINES = "overview_lines"
        private const val KEY_CAST = "show_cast"
        /** Old on/off switch of the cast photos, read until [castPhotos] is first set. */
        private const val KEY_CAST_PHOTOS = "cast_photos"
        private const val KEY_CAST_PHOTO_MODE = "cast_photo_mode"
        private const val KEY_SOURCE_FALLBACK = "source_fallback"
        private const val KEY_SOURCE_INLINE = "source_inline"
        private const val KEY_SHOW_SOURCE = "show_source"
        private const val KEY_RATINGS = "rating_sources"
        private const val KEY_HEADER_HIDDEN = "header_hidden"
        private const val KEY_HEADER_ORDER = "header_order"
        private const val KEY_MIN_RATINGS = "min_ratings"

        private const val KEY_LAST_GENERATION = "_last_generation"
        private const val KEY_LAST_SIGNATURE = "_last_signature"
        private const val KEY_LAST_ERROR = "_last_error"
        private const val KEY_LAST_NOTICE = "_last_notice"
        private const val KEY_GENERATING = "_generating"
        private const val KEY_PROGRESS = "_generation_progress"

        /** What "own lists" and "own look" copy from the wallpapers when they're turned on. */
        val LISTS_KEYS = listOf(KEY_LISTS, KEY_COUNT, KEY_GLOBAL_HITS, KEY_MAX_AGE, KEY_EXCLUDE_ADULT, KEY_MAX_RATING_AGE, KEY_MIN_RATING,
            KEY_GENRE_FILTER, KEY_SKIP_DUPLICATES, KEY_SELECTION, KEY_WATCHED, KEY_LIST_FILTERS)
        val LOOK_KEYS = listOf(KEY_WIDTH, KEY_STYLE, KEY_POSITION, KEY_LOGO_MODE, KEY_LOGO_FALLBACK, KEY_REQUIRE_LOGO, KEY_SYNOPSIS_MODE,
            KEY_TITLE_LANGUAGE, KEY_SYNOPSIS_LANGUAGE, KEY_PROVIDERS, KEY_OVERVIEW_LINES, KEY_CAST, KEY_CAST_PHOTOS, KEY_CAST_PHOTO_MODE,
            KEY_SOURCE_FALLBACK, KEY_SOURCE_INLINE, KEY_SHOW_SOURCE, KEY_RATINGS, KEY_HEADER_HIDDEN, KEY_HEADER_ORDER, KEY_MIN_RATINGS,
            KEY_SHADE, KEY_LATIN_ORIGINALS)
    }
}
