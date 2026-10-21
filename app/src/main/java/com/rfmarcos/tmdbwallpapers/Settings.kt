package com.rfmarcos.tmdbwallpapers

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

/** A list the user picked on MDBList. */
@Serializable
data class MdbListRef(val id: Long, val name: String)

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
    private const val KEY_PROVIDERS = "show_providers"
    private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
    private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
    private const val KEY_MDBLIST_KEY = "mdblist_api_key"
    private const val KEY_MDBLIST_LISTS = "mdblist_lists"

    // Internal state, not exported in Projectivy backups
    private const val KEY_LAST_GENERATION = "_last_generation"
    private const val KEY_LAST_SIGNATURE = "_last_signature"
    private const val KEY_LAST_ERROR = "_last_error"
    private const val KEY_GENERATING = "_generating"
    private const val KEY_TRAKT_ACCESS = "_trakt_access_token"
    private const val KEY_TRAKT_REFRESH = "_trakt_refresh_token"
    private const val KEY_TRAKT_EXPIRES = "_trakt_expires_at"
    private const val KEY_TRAKT_USER = "_trakt_user"

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

    var token: String
        get() = string(KEY_TOKEN, BuildConfig.DEFAULT_TMDB_TOKEN)
        set(value) = preferences.edit { putString(KEY_TOKEN, value.trim()) }

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

    // --- MDBList ---

    var mdbListKey: String
        get() = string(KEY_MDBLIST_KEY, BuildConfig.DEFAULT_MDBLIST_KEY)
        set(value) = preferences.edit { putString(KEY_MDBLIST_KEY, value.trim()) }

    /** Lists available on the user's MDBList account (refreshed from the settings screen). */
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

    /** Everything that changes the generated wallpapers (except the refresh interval). */
    val signature: String
        get() = listOf(
            token.hashCode(), language, region, lists.sorted().joinToString(","), titlesPerList,
            outputWidth, globalHitsOnly, showProviders,
        ).joinToString("|")

    /** True when the current wallpapers were generated with different settings. */
    val hasPendingChanges: Boolean
        get() = lastGeneration > 0 && lastSignature != signature

    val refreshIntervalMillis: Long
        get() = refreshHours * 3_600_000L

    val nextScheduledUpdate: Long
        get() = lastGeneration + refreshIntervalMillis

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
