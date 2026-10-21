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
    private const val KEY_FILTER_SCOPE = "filter_scope"
    private const val KEY_PROVIDERS = "show_providers"
    private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
    private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
    private const val KEY_MDBLIST_KEY = "mdblist_api_key"
    private const val KEY_MDBLIST_LISTS = "mdblist_lists"
    private const val KEY_MDBLIST_ACCESS = "_mdblist_access_token"
    private const val KEY_MDBLIST_REFRESH = "_mdblist_refresh_token"
    private const val KEY_MDBLIST_EXPIRES = "_mdblist_expires_at"
    private const val KEY_MDBLIST_USER = "_mdblist_user"
    private const val KEY_SELECTION = "selection_mode"
    private const val KEY_OVERVIEW_LINES = "overview_lines"
    private const val KEY_PROGRESS = "_generation_progress"
    private const val KEY_PREVIEW_PIN = "_preview_pin"
    private const val KEY_WATCHED = "watched_mode"
    private const val KEY_TMDB_SESSION = "_tmdb_session_id"
    private const val KEY_TMDB_ACCOUNT = "_tmdb_account_id"
    private const val KEY_TMDB_USER = "_tmdb_user"

    const val SELECTION_ORDER = "order"
    const val SELECTION_RANDOM = "random"
    const val SCOPE_AUTOMATIC = "automatic"
    const val SCOPE_ALL = "all"
    /** Months; 0 means no limit. */
    val MAX_AGE_OPTIONS = listOf(6, 12, 24, 60, 0)
    const val WATCHED_SHOW = "show"
    const val WATCHED_MARK = "mark"
    const val WATCHED_HIDE = "hide"

    // Internal state, not exported in Projectivy backups
    private const val KEY_LAST_GENERATION = "_last_generation"
    private const val KEY_LAST_SIGNATURE = "_last_signature"
    private const val KEY_LAST_ERROR = "_last_error"
    private const val KEY_GENERATING = "_generating"
    private const val KEY_TRAKT_ACCESS = "_trakt_access_token"
    private const val KEY_TRAKT_REFRESH = "_trakt_refresh_token"
    private const val KEY_TRAKT_EXPIRES = "_trakt_expires_at"
    private const val KEY_TRAKT_USER = "_trakt_user"
    private const val KEY_SIMKL_ACCESS = "_simkl_access_token"
    private const val KEY_SIMKL_REFRESH = "_simkl_refresh_token"
    private const val KEY_SIMKL_EXPIRES = "_simkl_expires_at"
    private const val KEY_SIMKL_USER = "_simkl_user"

    val COUNT_OPTIONS = listOf(5, 10, 15, 20)
    val REFRESH_OPTIONS = listOf(6, 12, 24, 72, 168)
    val WIDTH_OPTIONS = listOf(1920, 3840)
    val DEFAULT_LISTS = setOf("tmdb:trending_movies", "tmdb:trending_tv")

    private lateinit var preferences: SharedPreferences
    private val json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    fun init(context: Context) {
        if (!::preferences.isInitialized) {
            preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
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
        get() = preferences.getInt(KEY_MAX_AGE, 12)
        set(value) = preferences.edit { putInt(KEY_MAX_AGE, value) }

    var excludeAdult: Boolean
        get() = preferences.getBoolean(KEY_EXCLUDE_ADULT, true)
        set(value) = preferences.edit { putBoolean(KEY_EXCLUDE_ADULT, value) }

    /** "automatic": only trending/popular-like lists; "all": also the user's own lists. */
    var filterScope: String
        get() = preferences.getString(KEY_FILTER_SCOPE, SCOPE_AUTOMATIC) ?: SCOPE_AUTOMATIC
        set(value) = preferences.edit { putString(KEY_FILTER_SCOPE, value) }

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

    /** Watched titles can be marked or hidden when at least one tracker is connected. */
    val trackerConnected: Boolean get() = traktConnected || simklConnected

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
        get() = preferences.getString(KEY_SELECTION, SELECTION_ORDER) ?: SELECTION_ORDER
        set(value) = preferences.edit { putString(KEY_SELECTION, value) }

    /** Synopsis lines on the wallpaper: fewer lines keep the info block clear of the launcher rows. */
    var overviewLines: Int
        get() = preferences.getInt(KEY_OVERVIEW_LINES, 4)
        set(value) = preferences.edit { putInt(KEY_OVERVIEW_LINES, value) }

    val OVERVIEW_OPTIONS = listOf(4, 2, 0)

    /** Wallpaper shown by the settings preview as "type:tmdbId" (e.g. "movie:123"); empty = automatic. */
    var previewPin: String
        get() = string(KEY_PREVIEW_PIN)
        set(value) = preferences.edit { putString(KEY_PREVIEW_PIN, value) }

    /** What to do with titles already watched on Trakt: "show", "mark" or "hide". */
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
            outputWidth, globalHitsOnly, showProviders, selectionMode, if (trackerConnected) watchedMode else "",
            maxAgeMonths, excludeAdult, filterScope,
        // Only when changed, so existing installs don't see a pending change after updating
        ).plus(listOfNotNull(overviewLines.takeIf { it != 4 })).joinToString("|")

    /** True when the current wallpapers were generated with different settings. */
    val hasPendingChanges: Boolean
        get() = lastGeneration > 0 && lastSignature != signature

    val refreshIntervalMillis: Long
        get() = refreshHours * 3_600_000L

    // --- Projectivy backup (getPreferences / setPreferences) ---

    fun export(): String = buildJsonObject {
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("_")) return@forEach
            when (value) {
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is String -> put(key, JsonPrimitive(value))
                is Set<*> -> put(key, JsonArray(value.map { JsonPrimitive(it.toString()) }))
            }
        }
    }.toString()

    fun import(prefs: String): Boolean = try {
        val root = json.parseToJsonElement(prefs) as JsonObject
        preferences.edit {
            root.forEach { (key, value) ->
                if (key.startsWith("_")) return@forEach
                when {
                    value is JsonArray -> putStringSet(key, value.map { it.jsonPrimitive.content }.toSet())
                    value !is JsonPrimitive -> Unit
                    value.isString -> putString(key, value.content)
                    value.booleanOrNull != null -> putBoolean(key, value.booleanOrNull!!)
                    value.intOrNull != null -> putInt(key, value.intOrNull!!)
                    value.longOrNull != null -> putLong(key, value.longOrNull!!)
                }
            }
        }
        true
    } catch (e: Exception) {
        Log.e("Settings", "Error importing preferences", e)
        false
    }
}
