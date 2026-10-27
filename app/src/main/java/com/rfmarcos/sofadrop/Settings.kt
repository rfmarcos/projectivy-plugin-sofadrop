package com.rfmarcos.sofadrop

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Genre filter: titles with none of [ids] or, with [only], only titles with at least one of them. No
 * ids = no filter. Movies and TV shows use different TMDB ids for the same genre (see [Genres]).
 */
@Serializable
data class GenreFilter(val ids: Set<Int> = emptySet(), val only: Boolean = false) {
    val isOff: Boolean get() = ids.isEmpty()

    fun allows(genreIds: Collection<Int>): Boolean = isOff || genreIds.any { it in ids } == only
}

/** Filters one list overrides; null = use the default. */
@Serializable
data class ListFilters(
    val maxAge: Int? = null,
    val globalHits: Boolean? = null,
    val watched: String? = null,
    val genres: GenreFilter? = null,
) {
    val isDefault: Boolean get() = maxAge == null && globalHits == null && watched == null && genres == null
}

/** The filters a list ends up using. */
data class FilterValues(val maxAgeMonths: Int, val globalHits: Boolean, val watched: String, val genres: GenreFilter = GenreFilter())

/** An MDBList list: one of the account's (by [id]) or a public one added by its "user/slug" [path]. */
@Serializable
data class MdbListRef(val id: Long, val name: String, val path: String = "") {
    val key: String get() = "mdblist:" + path.ifEmpty { id.toString() }
}

/** User preferences plus a few internal values describing the last generation. */
object Settings {
    private const val KEY_TOKEN = "tmdb_token"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_REGION = "region"
    private const val KEY_LISTS = "lists"
    private const val KEY_COUNT = "titles_per_list"
    private const val KEY_REFRESH_HOURS = "refresh_hours"
    private const val KEY_WIDTH = "output_width"
    private const val KEY_GLOBAL_HITS = "global_hits_only"
    private const val KEY_MAX_AGE = "max_age_months"
    private const val KEY_EXCLUDE_ADULT = "exclude_adult"
    private const val KEY_MAX_RATING_AGE = "max_rating_age"
    private const val KEY_GENRE_FILTER = "genre_filter"
    private const val KEY_PROVIDERS = "show_providers"
    private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
    private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
    private const val KEY_MDBLIST_KEY = "mdblist_api_key"
    private const val KEY_MDBLIST_LISTS = "mdblist_lists"
    private const val KEY_RATINGS = "rating_sources"
    private const val KEY_HEADER_HIDDEN = "header_hidden"
    private const val KEY_HEADER_ORDER = "header_order"
    private const val KEY_MIN_RATINGS = "min_ratings"
    private const val KEY_MDBLIST_ACCESS = "_mdblist_access_token"
    private const val KEY_MDBLIST_REFRESH = "_mdblist_refresh_token"
    private const val KEY_MDBLIST_EXPIRES = "_mdblist_expires_at"
    private const val KEY_MDBLIST_USER = "_mdblist_user"
    private const val KEY_SELECTION = "selection_mode"
    private const val KEY_OVERVIEW_LINES = "overview_lines"
    private const val KEY_CAST = "show_cast"
    private const val KEY_SHOW_SOURCE = "show_source"
    private const val KEY_LIST_FILTERS = "list_filters"
    private val OLD_FILTER_KEYS = listOf("filter_scope", "age_all_lists", "global_hits_all_lists", "adult_all_lists", "filtered_lists",
        "launcher_guide", "_launcher_layout")
    private const val KEY_SOURCE_FALLBACK = "source_fallback"
    private const val KEY_PROGRESS = "_generation_progress"
    private const val KEY_PREVIEW_PIN = "_preview_pin"
    private const val KEY_PREVIEW_PIN_TITLE = "_preview_pin_title"
    private const val KEY_WATCHED = "watched_mode"
    private const val KEY_WATCH_SOURCE = "watch_source"
    private const val KEY_SKIP_DUPLICATES = "skip_duplicates"
    private const val KEY_MIX_LISTS = "mix_lists"
    private const val KEY_REQUIRE_LOGO = "require_logo"
    private const val KEY_LOGO_FALLBACK = "logo_fallback"
    private const val KEY_LOGO_MODE = "logo_mode"
    private const val KEY_SYNOPSIS_MODE = "synopsis_mode"
    private const val KEY_TITLE_LANGUAGE = "title_language"
    private const val KEY_SYNOPSIS_LANGUAGE = "synopsis_language"
    private const val KEY_STYLE = "style"
    private const val KEY_TMDB_SESSION = "_tmdb_session_id"
    private const val KEY_TMDB_ACCOUNT = "_tmdb_account_id"
    private const val KEY_TMDB_USER = "_tmdb_user"

    const val SELECTION_ORDER = "order"
    const val SELECTION_RANDOM = "random"
    /** Months; 0 means no limit. */
    val MAX_AGE_OPTIONS = listOf(6, 12, 24, 60, 0)
    /** Years; 0 means no limit. */
    val MAX_RATING_AGE_OPTIONS = listOf(0, 7, 12, 16)
    const val WATCHED_SHOW = "show"
    const val WATCHED_MARK = "mark"
    const val WATCHED_HIDE = "hide"
    const val WATCH_TRAKT = "trakt"
    const val WATCH_SIMKL = "simkl"
    const val WATCH_NONE = "none"
    /** With no logo in the logo language: write the title, use the original-language (or English) one, or skip the title. */
    const val LOGO_TEXT = "text"
    const val LOGO_ORIGINAL = "original"
    const val LOGO_ORIGINAL_OR_SKIP = "original_skip"
    const val LOGO_SKIP = "skip"
    val LOGO_MODES = listOf(LOGO_TEXT, LOGO_ORIGINAL, LOGO_ORIGINAL_OR_SKIP, LOGO_SKIP)
    /** With no synopsis in the synopsis language (any country): leave it out, use the English one, or skip the title. */
    const val SYNOPSIS_HIDE = "hide"
    const val SYNOPSIS_ENGLISH = "english"
    const val SYNOPSIS_SKIP = "skip"
    val SYNOPSIS_MODES = listOf(SYNOPSIS_HIDE, SYNOPSIS_ENGLISH, SYNOPSIS_SKIP)
    /** Language of the logo (and of the title written instead) and of the synopsis: the general one, the title's own or a locale. */
    const val LANGUAGE_AUTO = "auto"
    const val LANGUAGE_ORIGINAL = "original"
    val PART_LANGUAGES = listOf(LANGUAGE_AUTO, LANGUAGE_ORIGINAL) + Languages.OPTIONS.keys
    /** What is drawn over the backdrop: everything, the logo (or title) alone, or nothing. */
    const val STYLE_FULL = "full"
    const val STYLE_LOGO = "logo"
    const val STYLE_BACKDROP = "backdrop"
    val STYLES = listOf(STYLE_FULL, STYLE_LOGO, STYLE_BACKDROP)

    // Internal state, not exported in Projectivy backups
    private const val KEY_LAST_GENERATION = "_last_generation"
    private const val KEY_LAST_SIGNATURE = "_last_signature"
    private const val KEY_LAST_ERROR = "_last_error"
    private const val KEY_GENERATING = "_generating"
    private const val KEY_UPDATE_VERSION = "_update_version"
    private const val KEY_PROJECTIVY_ASKED = "_projectivy_asked_at"
    private const val KEY_UPDATE_APK = "_update_apk"
    private const val KEY_UPDATE_CHECKED = "_update_checked_at"
    private const val KEY_TRAKT_ACCESS = "_trakt_access_token"
    private const val KEY_TRAKT_REFRESH = "_trakt_refresh_token"
    private const val KEY_TRAKT_EXPIRES = "_trakt_expires_at"
    private const val KEY_TRAKT_USER = "_trakt_user"
    private const val KEY_SIMKL_ACCESS = "_simkl_access_token"
    private const val KEY_SIMKL_REFRESH = "_simkl_refresh_token"
    private const val KEY_SIMKL_EXPIRES = "_simkl_expires_at"
    private const val KEY_SIMKL_USER = "_simkl_user"

    val COUNT_OPTIONS = listOf(5, 10, 20, 50, 100)

    /** "All": lists are read up to this many titles (TMDB pages, Trakt/Simkl/MDBList limits). */
    const val COUNT_ALL = 100
    val REFRESH_OPTIONS = listOf(6, 12, 24, 72, 168)
    val WIDTH_OPTIONS = listOf(1920, 3840)
    val DEFAULT_LISTS = setOf("tmdb:trending_movies", "tmdb:trending_tv")

    private lateinit var preferences: SharedPreferences
    private val json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    fun init(context: Context) {
        if (!::preferences.isInitialized) {
            preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            // Scopes of earlier versions (replaced by per-list filters) and the dropped launcher guide
            preferences.edit { OLD_FILTER_KEYS.forEach(::remove) }
        }
    }

    private fun string(key: String, default: String = "") = preferences.getString(key, null)?.trim().orEmpty().ifEmpty { default }

    /** The user's own TMDB key (set from the phone), else the personal debug key, else the app's built-in key. */
    var token: String
        get() = string(KEY_TOKEN, BuildConfig.DEFAULT_TMDB_TOKEN.ifEmpty { BuildConfig.TMDB_APP_KEY })
        set(value) = preferences.edit { putString(KEY_TOKEN, value.trim()) }

    /** True when the TMDB key in use is the one built into the app. */
    val tokenIsBuiltIn: Boolean
        get() = string(KEY_TOKEN).isEmpty() && BuildConfig.DEFAULT_TMDB_TOKEN.isEmpty() && BuildConfig.TMDB_APP_KEY.isNotEmpty()

    var language: String
        get() = preferences.getString(KEY_LANGUAGE, null) ?: Languages.defaultFor(Locale.getDefault())
        set(value) = preferences.edit { putString(KEY_LANGUAGE, value) }

    /** Empty means "derive from the language". */
    var regionOverride: String
        get() = preferences.getString(KEY_REGION, "") ?: ""
        set(value) = preferences.edit { putString(KEY_REGION, value) }

    val region: String
        get() = regionOverride.ifEmpty { Languages.regionOf(language) }

    /** Selected list keys, e.g. "tmdb:trending_movies", "trakt:watchlist_shows", "mdblist:1234". */
    var lists: Set<String>
        get() = preferences.getStringSet(KEY_LISTS, null)?.toSet() ?: DEFAULT_LISTS
        set(value) = preferences.edit { putStringSet(KEY_LISTS, value) }

    var titlesPerList: Int
        get() = preferences.getInt(KEY_COUNT, 10)
        set(value) = preferences.edit { putInt(KEY_COUNT, value) }

    var refreshHours: Int
        get() = preferences.getInt(KEY_REFRESH_HOURS, 24)
        set(value) = preferences.edit { putInt(KEY_REFRESH_HOURS, value) }

    var outputWidth: Int
        get() = preferences.getInt(KEY_WIDTH, 1920)
        set(value) = preferences.edit { putInt(KEY_WIDTH, value) }

    var globalHitsOnly: Boolean
        get() = preferences.getBoolean(KEY_GLOBAL_HITS, true)
        set(value) = preferences.edit { putBoolean(KEY_GLOBAL_HITS, value) }

    // --- Filters ---

    /** Skip titles released (or, for TV, last aired) longer ago than this; 0 = no limit. */
    var maxAgeMonths: Int
        get() = preferences.getInt(KEY_MAX_AGE, 0)
        set(value) = preferences.edit { putInt(KEY_MAX_AGE, value) }

    var excludeAdult: Boolean
        get() = preferences.getBoolean(KEY_EXCLUDE_ADULT, true)
        set(value) = preferences.edit { putBoolean(KEY_EXCLUDE_ADULT, value) }

    /** Skip titles certified for older viewers than this, in every list (see [Certifications.minimumAge]); 0 = no limit. */
    var maxRatingAge: Int
        get() = preferences.getInt(KEY_MAX_RATING_AGE, 0)
        set(value) = preferences.edit { putInt(KEY_MAX_RATING_AGE, value) }

    /** Default genre filter; like the age, it only applies by default to automatic lists (see [filtersFor]). */
    var genreFilter: GenreFilter
        get() = try {
            json.decodeFromString<GenreFilter>(preferences.getString(KEY_GENRE_FILTER, null) ?: "{}")
        } catch (_: Exception) {
            GenreFilter()
        }
        set(value) = preferences.edit { putString(KEY_GENRE_FILTER, json.encodeToString(value)) }

    /**
     * Hand Projectivy the wallpapers shuffled (again on every request) instead of list after list, so
     * a list of movies isn't followed by a run of shows whichever way Projectivy walks the list.
     */
    var mixLists: Boolean
        get() = preferences.getBoolean(KEY_MIX_LISTS, true)
        set(value) = preferences.edit { putBoolean(KEY_MIX_LISTS, value) }

    /** A title already taken by an earlier list is skipped there, so that list uses its next one. */
    var skipDuplicates: Boolean
        get() = preferences.getBoolean(KEY_SKIP_DUPLICATES, true)
        set(value) = preferences.edit { putBoolean(KEY_SKIP_DUPLICATES, value) }


    /** See [LOGO_MODES]; earlier versions stored "logo_fallback" and "require_logo" (read until changed). */
    var logoMode: String
        get() = preferences.getString(KEY_LOGO_MODE, null) ?: run {
            val fallback = preferences.getBoolean(KEY_LOGO_FALLBACK, false)
            val required = preferences.getBoolean(KEY_REQUIRE_LOGO, false)
            when {
                fallback && required -> LOGO_ORIGINAL_OR_SKIP
                required -> LOGO_SKIP
                fallback -> LOGO_ORIGINAL
                else -> LOGO_TEXT
            }
        }
        set(value) = preferences.edit { putString(KEY_LOGO_MODE, value); remove(KEY_REQUIRE_LOGO) }

    /** See [SYNOPSIS_MODES]; "logo_fallback" briefly covered the synopsis too. */
    var synopsisMode: String
        get() = preferences.getString(KEY_SYNOPSIS_MODE, null)
            ?: if (preferences.getBoolean(KEY_LOGO_FALLBACK, false)) SYNOPSIS_ENGLISH else SYNOPSIS_HIDE
        set(value) = preferences.edit { putString(KEY_SYNOPSIS_MODE, value) }

    /** See [PART_LANGUAGES]; [logoMode] applies when there is no logo in this language. */
    var titleLanguage: String
        get() = partLanguage(KEY_TITLE_LANGUAGE)
        set(value) = preferences.edit { putString(KEY_TITLE_LANGUAGE, value) }

    /** See [PART_LANGUAGES]; [synopsisMode] applies when there is no synopsis in this language. */
    var synopsisLanguage: String
        get() = partLanguage(KEY_SYNOPSIS_LANGUAGE)
        set(value) = preferences.edit { putString(KEY_SYNOPSIS_LANGUAGE, value) }

    private fun partLanguage(key: String) =
        preferences.getString(key, null)?.takeIf { it == LANGUAGE_ORIGINAL || it in Languages.OPTIONS } ?: LANGUAGE_AUTO

    /** [titleLanguage] and [synopsisLanguage] as TMDB locales. */
    val partLanguages: PartLanguages
        get() = PartLanguages(resolve(titleLanguage), resolve(synopsisLanguage))

    /** A [PART_LANGUAGES] value as a TMDB locale; null for the title's original language. */
    fun resolve(partLanguage: String): String? = when (partLanguage) {
        LANGUAGE_AUTO -> language
        LANGUAGE_ORIGINAL -> null
        else -> partLanguage
    }

    /** See [STYLES]; the settings of the parts a style hides are kept for when they come back. */
    var style: String
        get() = preferences.getString(KEY_STYLE, null)?.takeIf { it in STYLES } ?: STYLE_FULL
        set(value) = preferences.edit { putString(KEY_STYLE, value) }

    /** The style draws the logo (or the title instead). */
    val drawsLogo: Boolean get() = style != STYLE_BACKDROP

    /** The style draws the header, synopsis, cast and footer (each as set). */
    val drawsDetails: Boolean get() = style == STYLE_FULL

    // A part the style hides doesn't fetch anything or skip titles
    val logoFallback: Boolean get() = drawsLogo && (logoMode == LOGO_ORIGINAL || logoMode == LOGO_ORIGINAL_OR_SKIP)
    val requireLogo: Boolean get() = drawsLogo && (logoMode == LOGO_ORIGINAL_OR_SKIP || logoMode == LOGO_SKIP)
    val requireSynopsis: Boolean get() = drawsDetails && synopsisMode == SYNOPSIS_SKIP

    /** Per-list overrides of the default filters (list key -> values), set on each list's page in "Filters & explore lists". */
    var listFilters: Map<String, ListFilters>
        get() = try {
            json.decodeFromString<Map<String, ListFilters>>(preferences.getString(KEY_LIST_FILTERS, null) ?: "{}")
        } catch (_: Exception) {
            emptyMap()
        }
        set(value) = preferences.edit { putString(KEY_LIST_FILTERS, json.encodeToString(value.filterValues { !it.isDefault })) }

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
        )
    }

    fun defaultMaxAge(source: ListSource) = if (source.automatic) maxAgeMonths else 0
    fun defaultGlobalHits(source: ListSource) = source.automatic && globalHitsOnly
    fun defaultGenres(source: ListSource) = if (source.automatic) genreFilter else GenreFilter()

    var showProviders: Boolean
        get() = preferences.getBoolean(KEY_PROVIDERS, true)
        set(value) = preferences.edit { putBoolean(KEY_PROVIDERS, value) }

    // --- Trakt ---

    var traktClientId: String
        get() = string(KEY_TRAKT_CLIENT_ID, BuildConfig.DEFAULT_TRAKT_CLIENT_ID)
        set(value) = preferences.edit { putString(KEY_TRAKT_CLIENT_ID, value.trim()) }

    var traktClientSecret: String
        get() = string(KEY_TRAKT_CLIENT_SECRET, BuildConfig.DEFAULT_TRAKT_CLIENT_SECRET)
        set(value) = preferences.edit { putString(KEY_TRAKT_CLIENT_SECRET, value.trim()) }

    val traktConfigured: Boolean get() = traktClientId.isNotBlank()

    /** The QR login needs the app's client id and secret. */
    val traktLoginAvailable: Boolean get() = traktClientId.isNotBlank()

    var traktAccessToken: String
        get() = string(KEY_TRAKT_ACCESS)
        set(value) = preferences.edit { putString(KEY_TRAKT_ACCESS, value) }

    var traktRefreshToken: String
        get() = string(KEY_TRAKT_REFRESH)
        set(value) = preferences.edit { putString(KEY_TRAKT_REFRESH, value) }

    var traktExpiresAt: Long
        get() = preferences.getLong(KEY_TRAKT_EXPIRES, 0L)
        set(value) = preferences.edit { putLong(KEY_TRAKT_EXPIRES, value) }

    var traktUser: String
        get() = string(KEY_TRAKT_USER)
        set(value) = preferences.edit { putString(KEY_TRAKT_USER, value) }

    val traktConnected: Boolean get() = traktConfigured && traktAccessToken.isNotBlank()

    fun clearTraktSession() = preferences.edit {
        remove(KEY_TRAKT_ACCESS); remove(KEY_TRAKT_REFRESH); remove(KEY_TRAKT_EXPIRES); remove(KEY_TRAKT_USER)
    }

    // --- Simkl (trending needs nothing; the account is connected with a QR code) ---

    val simklClientId: String get() = BuildConfig.SIMKL_CLIENT_ID

    val simklLoginAvailable: Boolean get() = simklClientId.isNotBlank()

    var simklAccessToken: String
        get() = string(KEY_SIMKL_ACCESS)
        set(value) = preferences.edit { putString(KEY_SIMKL_ACCESS, value) }

    var simklRefreshToken: String
        get() = string(KEY_SIMKL_REFRESH)
        set(value) = preferences.edit { putString(KEY_SIMKL_REFRESH, value) }

    var simklExpiresAt: Long
        get() = preferences.getLong(KEY_SIMKL_EXPIRES, 0L)
        set(value) = preferences.edit { putLong(KEY_SIMKL_EXPIRES, value) }

    var simklUser: String
        get() = string(KEY_SIMKL_USER)
        set(value) = preferences.edit { putString(KEY_SIMKL_USER, value) }

    val simklConnected: Boolean get() = simklLoginAvailable && simklAccessToken.isNotBlank()

    fun clearSimklSession() = preferences.edit {
        remove(KEY_SIMKL_ACCESS); remove(KEY_SIMKL_REFRESH); remove(KEY_SIMKL_EXPIRES); remove(KEY_SIMKL_USER)
    }

    /** Connected trackers that can provide the watch history. */
    val connectedTrackers: List<String> get() = listOfNotNull(WATCH_TRAKT.takeIf { traktConnected }, WATCH_SIMKL.takeIf { simklConnected })

    /**
     * Tracker whose history marks or hides watched titles: the chosen one while connected, null when off
     * or disconnected. Until chosen, the first connected tracker (what earlier versions used).
     */
    val watchSource: String?
        get() = preferences.getString(KEY_WATCH_SOURCE, null).let { chosen ->
            if (chosen == null) connectedTrackers.firstOrNull() else chosen.takeIf { it in connectedTrackers }
        }

    fun setWatchSource(value: String) = preferences.edit { putString(KEY_WATCH_SOURCE, value) }

    // --- TMDB account (optional, adds the user's watchlist / favourites) ---

    var tmdbSessionId: String
        get() = string(KEY_TMDB_SESSION)
        set(value) = preferences.edit { putString(KEY_TMDB_SESSION, value) }

    var tmdbAccountId: Int
        get() = preferences.getInt(KEY_TMDB_ACCOUNT, 0)
        set(value) = preferences.edit { putInt(KEY_TMDB_ACCOUNT, value) }

    var tmdbUser: String
        get() = string(KEY_TMDB_USER)
        set(value) = preferences.edit { putString(KEY_TMDB_USER, value) }

    val tmdbConnected: Boolean get() = tmdbSessionId.isNotBlank() && tmdbAccountId != 0

    fun clearTmdbSession() = preferences.edit { remove(KEY_TMDB_SESSION); remove(KEY_TMDB_ACCOUNT); remove(KEY_TMDB_USER) }

    // --- Selection and watched content ---

    /** "order": first titles of each list; "random": random picks among the best ones. */
    var selectionMode: String
        get() = preferences.getString(KEY_SELECTION, SELECTION_RANDOM) ?: SELECTION_RANDOM
        set(value) = preferences.edit { putString(KEY_SELECTION, value) }

    /** Synopsis lines on the wallpaper: fewer lines keep the info block clear of the launcher rows. */
    var overviewLines: Int
        get() = preferences.getInt(KEY_OVERVIEW_LINES, 4)
        set(value) = preferences.edit { putInt(KEY_OVERVIEW_LINES, value) }

    val OVERVIEW_OPTIONS = listOf(4, 2, 0)

    /** Main cast under the synopsis; off by default, it pushes the footer down. */
    var showCast: Boolean
        get() = preferences.getBoolean(KEY_CAST, false)
        set(value) = preferences.edit { putBoolean(KEY_CAST, value) }

    /**
     * With both footer parts on, show the list source only when the title has no streaming service.
     * Default for new installs; existing installs keep showing both (decided once, on first read).
     */
    var sourceFallback: Boolean
        get() {
            if (!preferences.contains(KEY_SOURCE_FALLBACK)) sourceFallback = lastGeneration == 0L
            return preferences.getBoolean(KEY_SOURCE_FALLBACK, false)
        }
        set(value) = preferences.edit { putBoolean(KEY_SOURCE_FALLBACK, value) }

    /** Footer line naming the list each title came from ("Trending · TMDB"). */
    var showSource: Boolean
        get() = preferences.getBoolean(KEY_SHOW_SOURCE, true)
        set(value) = preferences.edit { putBoolean(KEY_SHOW_SOURCE, value) }

    /** Last time Projectivy asked for wallpapers (0 = never: Sofadrop isn't its wallpaper yet). */
    var projectivyAskedAt: Long
        get() = preferences.getLong(KEY_PROJECTIVY_ASKED, 0)
        set(value) = preferences.edit { putLong(KEY_PROJECTIVY_ASKED, value) }

    /** Latest release found by [Updater.check] ("1.0.1", empty until checked), its APK and when it was checked. */
    var updateVersion: String
        get() = string(KEY_UPDATE_VERSION)
        set(value) = preferences.edit { putString(KEY_UPDATE_VERSION, value) }

    var updateApkUrl: String
        get() = string(KEY_UPDATE_APK)
        set(value) = preferences.edit { putString(KEY_UPDATE_APK, value) }

    var updateCheckedAt: Long
        get() = preferences.getLong(KEY_UPDATE_CHECKED, 0)
        set(value) = preferences.edit { putLong(KEY_UPDATE_CHECKED, value) }

    /** Wallpaper shown by the settings preview as "type:tmdbId" (e.g. "movie:123"); empty = automatic. */
    val previewPin: String get() = string(KEY_PREVIEW_PIN)

    /** Name of [previewPin], for the settings rows; empty for pins saved before it existed. */
    val previewPinTitle: String get() = string(KEY_PREVIEW_PIN_TITLE)

    fun pinPreview(pin: String, title: String) = preferences.edit {
        putString(KEY_PREVIEW_PIN, pin)
        putString(KEY_PREVIEW_PIN_TITLE, title)
    }

    /** What to do with titles in the watch history: "show", "mark" or "hide". */
    var watchedMode: String
        get() = preferences.getString(KEY_WATCHED, WATCHED_MARK) ?: WATCHED_MARK
        set(value) = preferences.edit { putString(KEY_WATCHED, value) }

    // --- MDBList (account connected with a QR code, or an API key set from the phone) ---

    val mdbListClientId: String get() = BuildConfig.MDBLIST_CLIENT_ID

    var mdbListAccessToken: String
        get() = string(KEY_MDBLIST_ACCESS)
        set(value) = preferences.edit { putString(KEY_MDBLIST_ACCESS, value) }

    var mdbListRefreshToken: String
        get() = string(KEY_MDBLIST_REFRESH)
        set(value) = preferences.edit { putString(KEY_MDBLIST_REFRESH, value) }

    var mdbListExpiresAt: Long
        get() = preferences.getLong(KEY_MDBLIST_EXPIRES, 0L)
        set(value) = preferences.edit { putLong(KEY_MDBLIST_EXPIRES, value) }

    var mdbListUser: String
        get() = string(KEY_MDBLIST_USER)
        set(value) = preferences.edit { putString(KEY_MDBLIST_USER, value) }

    val mdbListConnected: Boolean get() = mdbListAccessToken.isNotBlank()

    /** MDBList can be used with a connected account or with an API key. */
    val mdbListAvailable: Boolean get() = mdbListConnected || mdbListKey.isNotBlank()

    /** The QR login needs the app's MDBList client id. */
    val mdbListLoginAvailable: Boolean get() = mdbListClientId.isNotBlank()

    fun clearMdbListSession() = preferences.edit {
        remove(KEY_MDBLIST_ACCESS); remove(KEY_MDBLIST_REFRESH); remove(KEY_MDBLIST_EXPIRES); remove(KEY_MDBLIST_USER)
    }


    var mdbListKey: String
        get() = string(KEY_MDBLIST_KEY, BuildConfig.DEFAULT_MDBLIST_KEY)
        set(value) = preferences.edit { putString(KEY_MDBLIST_KEY, value.trim()) }

    /** The account's MDBList lists (refreshed from the settings screen) plus the public lists added by the user. */
    var mdbLists: List<MdbListRef>
        get() = try {
            json.decodeFromString<List<MdbListRef>>(preferences.getString(KEY_MDBLIST_LISTS, null) ?: "[]")
        } catch (_: Exception) {
            emptyList()
        }
        set(value) = preferences.edit { putString(KEY_MDBLIST_LISTS, json.encodeToString(value)) }

    /** Scores shown on the wallpapers, in order; by default TMDB's, as before they could be chosen. */
    var ratingSources: List<RatingSource>
        get() = ratingSourcesKey.split(',').mapNotNull(RatingSource::of)
        set(value) = preferences.edit { putString(KEY_RATINGS, value.joinToString(",") { it.key }) }

    // Read without string(): empty means "no ratings", not the default
    private val ratingSourcesKey: String get() = preferences.getString(KEY_RATINGS, null) ?: RatingSource.TMDB.key

    /** Header items under the title: everything is shown unless hidden here. */
    const val HEADER_YEAR = "year"
    const val HEADER_RUNTIME = "runtime"
    const val HEADER_GENRES = "genres"
    const val HEADER_WATCHED = "watched"
    /** Drawn right after the year (or in its place), so it has no position of its own. */
    const val HEADER_CERTIFICATION = "certification"
    val HEADER_ITEMS = listOf(HEADER_YEAR, HEADER_CERTIFICATION, HEADER_RUNTIME, HEADER_GENRES, HEADER_WATCHED)

    var headerHidden: Set<String>
        get() = preferences.getStringSet(KEY_HEADER_HIDDEN, null)?.toSet() ?: emptySet()
        set(value) = preferences.edit { putStringSet(KEY_HEADER_HIDDEN, value) }

    fun showsInHeader(item: String) = item !in headerHidden

    /** The ratings, as one block of the header; hidden by choosing no platform. */
    const val HEADER_RATINGS = "ratings"
    /** Blocks of the header line in their default order; the user can move them (see [headerOrder]). */
    val HEADER_BLOCKS = listOf(HEADER_RATINGS, HEADER_WATCHED, HEADER_YEAR, HEADER_RUNTIME, HEADER_GENRES)

    /** [HEADER_BLOCKS] in the user's order (blocks added in later versions go last). */
    var headerOrder: List<String>
        get() {
            val saved = string(KEY_HEADER_ORDER).split(',').filter { it in HEADER_BLOCKS }.distinct()
            return saved + (HEADER_BLOCKS - saved.toSet())
        }
        set(value) = preferences.edit { putString(KEY_HEADER_ORDER, value.joinToString(",")) }

    /** Ratings that stay when the line is full (the details give way first); more only where there's room. */
    var minRatings: Int
        get() = preferences.getInt(KEY_MIN_RATINGS, 2)
        set(value) = preferences.edit { putInt(KEY_MIN_RATINGS, value) }

    val MIN_RATINGS_OPTIONS = listOf(1, 2, 3)

    // --- Generation state ---

    var lastGeneration: Long
        get() = preferences.getLong(KEY_LAST_GENERATION, 0L)
        set(value) = preferences.edit { putLong(KEY_LAST_GENERATION, value) }

    /** Settings signature used for the last successful generation. */
    var lastSignature: String
        get() = preferences.getString(KEY_LAST_SIGNATURE, "") ?: ""
        set(value) = preferences.edit { putString(KEY_LAST_SIGNATURE, value) }

    var lastError: String
        get() = preferences.getString(KEY_LAST_ERROR, "") ?: ""
        set(value) = preferences.edit { putString(KEY_LAST_ERROR, value) }

    var generating: Boolean
        get() = preferences.getBoolean(KEY_GENERATING, false)
        set(value) = preferences.edit { putBoolean(KEY_GENERATING, value) }

    /** While generating: wallpapers made, current list and number of lists. */
    var generationProgress: Triple<Int, Int, Int>?
        get() = preferences.getString(KEY_PROGRESS, null)?.split('|')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) }
        set(value) = preferences.edit {
            if (value == null) remove(KEY_PROGRESS) else putString(KEY_PROGRESS, "${value.first}|${value.second}|${value.third}")
        }

    /** Everything that changes the generated wallpapers (except the refresh interval). */
    val signature: String
        get() = listOf(
            token.hashCode(), language, region, lists.sorted().joinToString(","), titlesPerList,
            outputWidth, globalHitsOnly, showProviders, selectionMode, watchSource?.let { "$it:$watchedMode" }.orEmpty(),
            // Without the genre field lists don't use, so updating doesn't show a pending change
            maxAgeMonths, excludeAdult, listFilters.toSortedMap().toString().replace(", genres=null", ""),
        // Only when changed, so existing installs don't see a pending change after updating
        ).plus(listOfNotNull(overviewLines.takeIf { it != 4 }, "nosource".takeIf { !showSource }, "fallback".takeIf { showSource && showProviders && sourceFallback },
            "dups".takeIf { !skipDuplicates }, "logo:$logoMode".takeIf { logoMode != LOGO_TEXT }, "synopsis:$synopsisMode".takeIf { synopsisMode != SYNOPSIS_HIDE },
            "titlelang:$titleLanguage".takeIf { titleLanguage != LANGUAGE_AUTO }, "synopsislang:$synopsisLanguage".takeIf { synopsisLanguage != LANGUAGE_AUTO },
            "ratings:$ratingSourcesKey:$mdbListAvailable".takeIf { ratingSourcesKey != RatingSource.TMDB.key },
            "header:${headerHidden.sorted()}".takeIf { headerHidden.isNotEmpty() },
            "ratingage:$maxRatingAge".takeIf { maxRatingAge > 0 },
            "order:${headerOrder.joinToString(",")}".takeIf { headerOrder != HEADER_BLOCKS },
            "minratings:$minRatings".takeIf { minRatings != 2 }, "cast".takeIf { showCast }, "style:$style".takeIf { style != STYLE_FULL },
            "genres:${genreFilter.only}:${genreFilter.ids.sorted()}".takeIf { !genreFilter.isOff })).joinToString("|")

    /** True when the current wallpapers were generated with different settings. */
    val hasPendingChanges: Boolean
        get() = lastGeneration > 0 && lastSignature != signature

    val refreshIntervalMillis: Long
        get() = refreshHours * 3_600_000L

    // --- Projectivy backup (getPreferences / setPreferences) ---

    /**
     * API keys and app credentials, never in a backup. The service that answers getPreferences and
     * setPreferences is exported for Projectivy, so any installed app can call it: it must not hand
     * these out, nor let them be replaced. Connections (tokens, "_" keys) aren't backed up either.
     */
    private val SECRET_KEYS = setOf(KEY_TOKEN, KEY_TRAKT_CLIENT_ID, KEY_TRAKT_CLIENT_SECRET, KEY_MDBLIST_KEY)

    /** True for the preferences a Projectivy backup carries: the user's choices, not internal state or keys. */
    fun inBackup(key: String): Boolean = !key.startsWith("_") && key !in SECRET_KEYS

    fun export(): String = exportJson(preferences.all)

    fun import(prefs: String): Boolean {
        val values = try {
            importValues(prefs)
        } catch (e: Exception) {
            Log.e("Settings", "Error importing preferences", e)
            return false
        }
        preferences.edit {
            values.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Set<*> -> putStringSet(key, value as Set<String>)
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                }
            }
        }
        return true
    }

    /** The backup of [values] (the stored preferences) as JSON. */
    fun exportJson(values: Map<String, *>): String = buildJsonObject {
        values.forEach { (key, value) ->
            if (!inBackup(key)) return@forEach
            when (value) {
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is String -> put(key, JsonPrimitive(value))
                is Set<*> -> put(key, JsonArray(value.map { JsonPrimitive(it.toString()) }))
            }
        }
    }.toString()

    /** The preferences to restore from the backup [prefs], by key; throws when it isn't a valid backup. */
    fun importValues(prefs: String): Map<String, Any> {
        val root = json.parseToJsonElement(prefs) as JsonObject
        return root.filterKeys(::inBackup).mapNotNull { (key, value) ->
            when {
                value is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
                value !is JsonPrimitive -> null
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull!!
                value.intOrNull != null -> value.intOrNull!!
                value.longOrNull != null -> value.longOrNull!!
                else -> null
            }?.let { key to it }
        }.toMap()
    }
}
