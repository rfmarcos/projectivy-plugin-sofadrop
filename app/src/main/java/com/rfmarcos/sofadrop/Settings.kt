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
    val minRating: Double? = null,
) {
    val isDefault: Boolean get() = maxAge == null && globalHits == null && watched == null && genres == null && minRating == null
}

/** The filters a list ends up using. */
data class FilterValues(
    val maxAgeMonths: Int, val globalHits: Boolean, val watched: String, val genres: GenreFilter = GenreFilter(), val minRating: Double = 0.0,
)

/** An MDBList list: one of the account's (by [id]) or a public one added by its "user/slug" [path]. */
@Serializable
data class MdbListRef(val id: Long, val name: String, val path: String = "") {
    val key: String get() = "mdblist:" + path.ifEmpty { id.toString() }
}

/** A Trakt user's list by its "user/slug" [path]: a public one added by the user, or one of the connected account's ([own]). */
@Serializable
data class TraktListRef(val path: String, val name: String, val own: Boolean = false) {
    val key: String get() = TraktClient.USER_LIST + path
}

/** User preferences plus a few internal values describing the last generation. */
object Settings {
    private const val KEY_TOKEN = "tmdb_token"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_REGION = "region"
    private const val KEY_REFRESH_HOURS = "refresh_hours"
    private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
    private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
    private const val KEY_MDBLIST_KEY = "mdblist_api_key"
    private const val KEY_MDBLIST_LISTS = "mdblist_lists"
    private const val KEY_TRAKT_LISTS = "trakt_lists"
    private const val KEY_MDBLIST_ACCESS = "_mdblist_access_token"
    private const val KEY_MDBLIST_REFRESH = "_mdblist_refresh_token"
    private const val KEY_MDBLIST_EXPIRES = "_mdblist_expires_at"
    private const val KEY_MDBLIST_USER = "_mdblist_user"
    private val OLD_FILTER_KEYS = listOf("filter_scope", "age_all_lists", "global_hits_all_lists", "adult_all_lists", "filtered_lists",
        "launcher_guide", "_launcher_layout")
    private const val KEY_PREVIEW_PIN = "_preview_pin"
    private const val KEY_PREVIEW_PIN_TITLE = "_preview_pin_title"
    private const val KEY_WATCH_SOURCE = "watch_source"
    private const val KEY_BLOCKED = "blocked_titles"
    private const val KEY_MIX_LISTS = "mix_lists"
    private const val KEY_SCREENSAVER_OWN_LISTS = "screensaver_own_lists"
    private const val KEY_SCREENSAVER_OWN_LOOK = "screensaver_own_look"
    private const val KEY_SCREENSAVER_SECONDS = "screensaver_seconds"
    private const val KEY_SCREENSAVER_TRANSITION = "screensaver_transition"
    private const val KEY_SCREENSAVER_ZOOM = "screensaver_zoom"
    private const val KEY_SCREENSAVER_SHUFFLE = "screensaver_shuffle"
    private const val KEY_SCREENSAVER_CLOCK = "screensaver_clock"
    private const val KEY_SCREENSAVER_DATE = "screensaver_date"
    private const val KEY_SCREENSAVER_CLOCK_BOTTOM = "screensaver_clock_bottom"
    private const val KEY_SCREENSAVER_CLOCK_FORMAT = "screensaver_clock_format"
    private const val KEY_SCREENSAVER_BRIGHTNESS = "screensaver_brightness"
    private const val KEY_SCREENSAVER_WEATHER = "screensaver_weather"
    private const val KEY_WEATHER_PLACE = "weather_place"
    private const val KEY_WEATHER_COORDINATES = "weather_coordinates"
    private const val KEY_CLICK_PLATFORMS = "click_platforms"
    private const val KEY_CLICK_FALLBACK = "click_fallback"
    private const val KEY_TRAILER_LANGUAGE = "trailer_language"
    private const val KEY_TRAILER_FALLBACK = "trailer_fallback"
    private const val KEY_TMDB_SESSION = "_tmdb_session_id"
    private const val KEY_TMDB_ACCOUNT = "_tmdb_account_id"
    private const val KEY_TMDB_USER = "_tmdb_user"

    const val SELECTION_ORDER = "order"
    const val SELECTION_RANDOM = "random"
    /** Months; 0 means no limit. A year (see [isYear]) can be typed as well. */
    val MAX_AGE_OPTIONS = listOf(6, 12, 24, 60, 120, 0)
    private const val FIRST_YEAR = 1900

    /** True when an age limit is a year ("from 2000") rather than months. */
    fun isYear(maxAge: Int) = maxAge >= FIRST_YEAR

    /** A typed year, when it is one (1900 to next year). */
    fun parseYear(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in FIRST_YEAR..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1 }
    /** Years; 0 means no limit. */
    val MAX_RATING_AGE_OPTIONS = listOf(0, 7, 12, 16)
    /** TMDB score out of 10; 0 means no limit. */
    val MIN_RATING_OPTIONS = listOf(5.0, 6.0, 6.5, 7.0, 7.5, 8.0, 0.0)
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
    /** Colour of the shading behind the text and over the launcher rows: the backdrop's own, darkened, or black for OLED screens. */
    const val SHADE_TINT = "tint"
    const val SHADE_BLACK = "black"
    val SHADES = listOf(SHADE_TINT, SHADE_BLACK)
    /** Where the info block goes on the wallpaper. */
    const val POSITION_TOP_LEFT = "top_left"
    val POSITIONS = listOf(POSITION_TOP_LEFT, "center_left", "bottom_left", "top_right", "center_right", "bottom_right")
    /** What Projectivy opens from a wallpaper ("Watch" in About this wallpaper, play on its status bar). */
    const val CLICK_TRAILER = "trailer"
    const val CLICK_NONE = "none"
    val CLICK_FALLBACKS = listOf(CLICK_TRAILER, CLICK_NONE)
    /** Click action opening the title in a [TitleApp]: "app:nuvio". */
    const val CLICK_APP_PREFIX = "app:"
    /**
     * With no official trailer in the trailer language: the English one, the original-language one (then
     * any trailer for both), any trailer, or none.
     */
    const val TRAILER_ENGLISH = "english"
    const val TRAILER_ORIGINAL = "original"
    const val TRAILER_ANY = "any"
    const val TRAILER_NONE = "none"
    val TRAILER_FALLBACKS = listOf(TRAILER_ENGLISH, TRAILER_ORIGINAL, TRAILER_ANY, TRAILER_NONE)
    /** Cast photos: none, all together before the names (overlapping), or each one before its name. */
    const val CAST_PHOTOS_NONE = "none"
    const val CAST_PHOTOS_GROUPED = "grouped"
    const val CAST_PHOTOS_EACH = "each"
    val CAST_PHOTO_MODES = listOf(CAST_PHOTOS_NONE, CAST_PHOTOS_GROUPED, CAST_PHOTOS_EACH)

    // Internal state, not exported in Projectivy backups
    private const val KEY_UPDATE_VERSION = "_update_version"
    private const val KEY_PROJECTIVY_ASKED = "_projectivy_asked_at"
    private const val KEY_WIZARD_SHOWN = "_wizard_shown"
    private const val KEY_SCREENSAVER_SHOWN = "_screensaver_shown_at"
    private const val KEY_UPDATE_APK = "_update_apk"
    private const val KEY_UPDATE_NOTES = "_update_notes"
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

    /**
     * Wallpapers per generation, shared among the lists. Projectivy shows 100 of them, picked at random
     * again on each request: more only costs time, requests and disk (4K above all).
     */
    const val MAX_WALLPAPERS = 200
    val REFRESH_OPTIONS = listOf(6, 12, 24, 72, 168)
    val WIDTH_OPTIONS = listOf(1920, 3840)
    val DEFAULT_LISTS = setOf("tmdb:trending_movies", "tmdb:trending_tv")

    internal lateinit var preferences: SharedPreferences
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

    var refreshHours: Int
        get() = preferences.getInt(KEY_REFRESH_HOURS, 24)
        set(value) = preferences.edit { putInt(KEY_REFRESH_HOURS, value) }

    // --- Filters ---

    /**
     * Hand Projectivy the wallpapers shuffled (again on every request) instead of list after list, so
     * a list of movies isn't followed by a run of shows whichever way Projectivy walks the list.
     */
    var mixLists: Boolean
        get() = preferences.getBoolean(KEY_MIX_LISTS, true)
        set(value) = preferences.edit { putBoolean(KEY_MIX_LISTS, value) }

    /** Titles that never show, from any list, with the name they were blocked with ("Title (2020)"). */
    val blocked: Map<TitleRef, String>
        get() = preferences.getStringSet(KEY_BLOCKED, null).orEmpty().mapNotNull { entry ->
            // "movie:123<tab>Title (2020)"
            val (key, name) = entry.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val type = MediaType.entries.firstOrNull { it.path == key.substringBefore(':') } ?: return@mapNotNull null
            key.substringAfter(':').toIntOrNull()?.let { TitleRef(type, it) to name }
        }.toMap()

    val blockedTitles: Set<TitleRef> get() = blocked.keys

    /** Block or unblock one title; [name] is what the list of blocked titles shows for it. */
    fun setBlocked(ref: TitleRef, block: Boolean, name: String = "") {
        val titles = blocked.toMutableMap()
        if (block) titles[ref] = name else titles -= ref
        preferences.edit { putStringSet(KEY_BLOCKED, titles.map { (r, n) -> "${r.type.path}:${r.tmdbId}\t$n" }.toSet()) }
    }

    fun clearBlocked() = preferences.edit { remove(KEY_BLOCKED) }


    internal fun partLanguage(key: String) =
        preferences.getString(key, null)?.takeIf { it == LANGUAGE_ORIGINAL || it in Languages.OPTIONS } ?: LANGUAGE_AUTO

    /** A [PART_LANGUAGES] value as a TMDB locale; null for the title's original language. */
    fun resolve(partLanguage: String): String? = when (partLanguage) {
        LANGUAGE_AUTO -> language
        LANGUAGE_ORIGINAL -> null
        else -> partLanguage
    }

    /**
     * Streaming platforms the user pays for, in the order a wallpaper tries them (see [ClickTargets]); their
     * pages are looked up when generating, so adding the first one needs a new set.
     */
    var clickPlatforms: List<Platform>
        get() = preferences.getString(KEY_CLICK_PLATFORMS, null).orEmpty().split(',').mapNotNull(Platform::of).distinct()
        set(value) = preferences.edit { putString(KEY_CLICK_PLATFORMS, value.joinToString(",") { it.key }) }

    /**
     * What a wallpaper opens when its title is on none of [clickPlatforms]: see [CLICK_FALLBACKS] and
     * [CLICK_APP_PREFIX] (an app not installed opens the trailer). Applied when served, without a new set.
     */
    var clickFallback: String
        get() = preferences.getString(KEY_CLICK_FALLBACK, null)?.takeIf { it in CLICK_FALLBACKS || clickApp(it) != null } ?: CLICK_TRAILER
        set(value) = preferences.edit { putString(KEY_CLICK_FALLBACK, value) }

    /** The app of a click fallback that opens the title in one ("app:nuvio"), else null. */
    fun clickApp(action: String = clickFallback): TitleApp? =
        action.takeIf { it.startsWith(CLICK_APP_PREFIX) }?.let { TitleApp.of(it.removePrefix(CLICK_APP_PREFIX)) }

    /** See [PART_LANGUAGES]: "Automatic" is the wallpaper language; [trailerFallback] applies when there is none in it. */
    var trailerLanguage: String
        get() = partLanguage(KEY_TRAILER_LANGUAGE)
        set(value) = preferences.edit { putString(KEY_TRAILER_LANGUAGE, value) }

    /** See [TRAILER_FALLBACKS]. */
    var trailerFallback: String
        get() = preferences.getString(KEY_TRAILER_FALLBACK, null)?.takeIf { it in TRAILER_FALLBACKS } ?: TRAILER_ENGLISH
        set(value) = preferences.edit { putString(KEY_TRAILER_FALLBACK, value) }

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

    /** Trakt users' lists: the public ones added by the user plus the account's own (refreshed from its screen). */
    var traktLists: List<TraktListRef>
        get() = try {
            json.decodeFromString<List<TraktListRef>>(preferences.getString(KEY_TRAKT_LISTS, null) ?: "[]")
        } catch (_: Exception) {
            emptyList()
        }
        set(value) = preferences.edit { putString(KEY_TRAKT_LISTS, json.encodeToString(value)) }

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

    val OVERVIEW_OPTIONS = listOf(4, 2, 0)

    /** Last time Projectivy asked for wallpapers (0 = never: Sofadrop isn't its wallpaper yet). */
    var projectivyAskedAt: Long
        get() = preferences.getLong(KEY_PROJECTIVY_ASKED, 0)
        set(value) = preferences.edit { putLong(KEY_PROJECTIVY_ASKED, value) }

    /** The setup wizard opened by itself once (see [Wizard.offer]). */
    var wizardShown: Boolean
        get() = preferences.getBoolean(KEY_WIZARD_SHOWN, false)
        set(value) = preferences.edit { putBoolean(KEY_WIZARD_SHOWN, value) }

    /**
     * Latest release found by [Updater.check] ("1.0.1", empty until checked), its APK, its release notes
     * (GitHub markdown, may be empty) and when it was checked.
     */
    var updateVersion: String
        get() = string(KEY_UPDATE_VERSION)
        set(value) = preferences.edit { putString(KEY_UPDATE_VERSION, value) }

    var updateApkUrl: String
        get() = string(KEY_UPDATE_APK)
        set(value) = preferences.edit { putString(KEY_UPDATE_APK, value) }

    var updateNotes: String
        get() = string(KEY_UPDATE_NOTES)
        set(value) = preferences.edit { putString(KEY_UPDATE_NOTES, value) }

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

    /** Header items under the title: everything is shown unless hidden here. */
    const val HEADER_YEAR = "year"
    const val HEADER_RUNTIME = "runtime"
    const val HEADER_GENRES = "genres"
    const val HEADER_WATCHED = "watched"
    /** Drawn right after the year (or in its place), so it has no position of its own. */
    const val HEADER_CERTIFICATION = "certification"
    val HEADER_ITEMS = listOf(HEADER_YEAR, HEADER_CERTIFICATION, HEADER_RUNTIME, HEADER_GENRES, HEADER_WATCHED)

    /** The ratings, as one block of the header; hidden by choosing no platform. */
    const val HEADER_RATINGS = "ratings"
    /** Blocks of the header line in their default order; the user can move them (see [headerOrder]). */
    val HEADER_BLOCKS = listOf(HEADER_RATINGS, HEADER_WATCHED, HEADER_YEAR, HEADER_RUNTIME, HEADER_GENRES)

    val MIN_RATINGS_OPTIONS = listOf(1, 2, 3)

    val refreshIntervalMillis: Long
        get() = refreshHours * 3_600_000L

    // --- Screensaver ---

    /** The screensaver has lists and filters of its own (see [Profile]); they start as the wallpapers'. */
    var screensaverOwnLists: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_OWN_LISTS, false)
        set(value) = preferences.edit {
            if (value && !screensaverOwnLists) copyToScreensaver(Profile.LISTS_KEYS)
            putBoolean(KEY_SCREENSAVER_OWN_LISTS, value)
        }

    /** The screensaver has a look of its own (see [Profile]); it starts as the wallpapers'. */
    var screensaverOwnLook: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_OWN_LOOK, false)
        set(value) = preferences.edit {
            if (value && !screensaverOwnLook) copyToScreensaver(Profile.LOOK_KEYS)
            putBoolean(KEY_SCREENSAVER_OWN_LOOK, value)
        }

    // How Sofascreen shows the set (see SofascreenView), after PR #13 by schumi2004
    val SCREENSAVER_SECONDS_OPTIONS = listOf(10, 20, 30, 60, 120, 300)
    const val TRANSITION_FADE = "fade"
    const val TRANSITION_BLACK = "black"
    const val TRANSITION_SLIDE = "slide"
    const val TRANSITION_ZOOM = "zoom"
    const val TRANSITION_RANDOM = "random"
    val TRANSITIONS = listOf(TRANSITION_FADE, TRANSITION_BLACK, TRANSITION_SLIDE, TRANSITION_ZOOM, TRANSITION_RANDOM)
    const val CLOCK_OFF = "off"
    const val CLOCK_STACKED = "stacked"
    const val CLOCK_LINE = "line"
    val CLOCK_STYLES = listOf(CLOCK_OFF, CLOCK_STACKED, CLOCK_LINE)
    const val CLOCK_FORMAT_SYSTEM = "system"
    const val CLOCK_FORMAT_24 = "24"
    const val CLOCK_FORMAT_12 = "12"
    val CLOCK_FORMATS = listOf(CLOCK_FORMAT_SYSTEM, CLOCK_FORMAT_24, CLOCK_FORMAT_12)
    val SCREENSAVER_BRIGHTNESS_OPTIONS = listOf(100, 75, 50)

    /** How long each wallpaper stays on screen. */
    var screensaverSeconds: Int
        get() = preferences.getInt(KEY_SCREENSAVER_SECONDS, 30)
        set(value) = preferences.edit { putInt(KEY_SCREENSAVER_SECONDS, value) }

    /** See [TRANSITIONS]. */
    var screensaverTransition: String
        get() = preferences.getString(KEY_SCREENSAVER_TRANSITION, null)?.takeIf { it in TRANSITIONS } ?: TRANSITION_FADE
        set(value) = preferences.edit { putString(KEY_SCREENSAVER_TRANSITION, value) }

    /** A slow zoom while each wallpaper is shown. */
    var screensaverZoom: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_ZOOM, true)
        set(value) = preferences.edit { putBoolean(KEY_SCREENSAVER_ZOOM, value) }

    /** Random order instead of the set's. */
    var screensaverShuffle: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_SHUFFLE, true)
        set(value) = preferences.edit { putBoolean(KEY_SCREENSAVER_SHUFFLE, value) }

    /** See [CLOCK_STYLES]. */
    var screensaverClock: String
        get() = preferences.getString(KEY_SCREENSAVER_CLOCK, null)?.takeIf { it in CLOCK_STYLES } ?: CLOCK_STACKED
        set(value) = preferences.edit { putString(KEY_SCREENSAVER_CLOCK, value) }

    var screensaverDate: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_DATE, true)
        set(value) = preferences.edit { putBoolean(KEY_SCREENSAVER_DATE, value) }

    /** 24 or 12 hours, or as Android is set (see [CLOCK_FORMATS]). */
    var screensaverClockFormat: String
        get() = preferences.getString(KEY_SCREENSAVER_CLOCK_FORMAT, null)?.takeIf { it in CLOCK_FORMATS } ?: CLOCK_FORMAT_SYSTEM
        set(value) = preferences.edit { putString(KEY_SCREENSAVER_CLOCK_FORMAT, value) }

    /** The clock at the bottom of its side instead of the top. */
    var screensaverClockBottom: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_CLOCK_BOTTOM, false)
        set(value) = preferences.edit { putBoolean(KEY_SCREENSAVER_CLOCK_BOTTOM, value) }

    /** Percentage, see [SCREENSAVER_BRIGHTNESS_OPTIONS]. */
    var screensaverBrightness: Int
        get() = preferences.getInt(KEY_SCREENSAVER_BRIGHTNESS, 100)
        set(value) = preferences.edit { putInt(KEY_SCREENSAVER_BRIGHTNESS, value) }

    /** When Android last started Sofascreen as the screensaver (0: never), to tell whether it's switched on. */
    var screensaverShownAt: Long
        get() = preferences.getLong(KEY_SCREENSAVER_SHOWN, 0L)
        set(value) = preferences.edit { putLong(KEY_SCREENSAVER_SHOWN, value) }

    /** The weather at [weatherPlace] under the clock (Open-Meteo, no account). */
    var screensaverWeather: Boolean
        get() = preferences.getBoolean(KEY_SCREENSAVER_WEATHER, false)
        set(value) = preferences.edit { putBoolean(KEY_SCREENSAVER_WEATHER, value) }

    /** "Amsterdam, North Holland": the place found for the location the user typed; empty when not set. */
    val weatherPlace: String get() = preferences.getString(KEY_WEATHER_PLACE, null).orEmpty()

    /** Latitude and longitude of [weatherPlace]. */
    val weatherCoordinates: Pair<Double, Double>?
        get() = preferences.getString(KEY_WEATHER_COORDINATES, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun setWeatherPlace(place: Place?) = preferences.edit {
        if (place == null) {
            remove(KEY_WEATHER_PLACE)
            remove(KEY_WEATHER_COORDINATES)
        } else {
            putString(KEY_WEATHER_PLACE, place.name)
            putString(KEY_WEATHER_COORDINATES, "${place.latitude},${place.longitude}")
        }
    }

    /** The wallpapers' values of [keys] as the screensaver's own. */
    private fun SharedPreferences.Editor.copyToScreensaver(keys: List<String>) {
        val values = preferences.all
        for (key in keys) {
            val own = Profile.OWN_PREFIX + key
            when (val value = values[key]) {
                is String -> putString(own, value)
                is Boolean -> putBoolean(own, value)
                is Int -> putInt(own, value)
                is Long -> putLong(own, value)
                is Float -> putFloat(own, value)
                is Set<*> -> putStringSet(own, value.map { it.toString() }.toSet())
                else -> remove(own)
            }
        }
    }

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
