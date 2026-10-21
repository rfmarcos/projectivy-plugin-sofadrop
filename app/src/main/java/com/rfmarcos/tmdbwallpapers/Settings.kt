package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

enum class ContentType(val key: String) {
    BOTH("both"), MOVIES("movies"), TV("tv");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: BOTH
    }
}

/** User preferences plus a few internal values describing the last generation. */
object Settings {
    private const val KEY_TOKEN = "tmdb_token"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_REGION = "region"
    private const val KEY_CONTENT = "content"
    private const val KEY_COUNT = "titles_per_type"
    private const val KEY_REFRESH_HOURS = "refresh_hours"
    private const val KEY_WIDTH = "output_width"
    private const val KEY_GLOBAL_HITS = "global_hits_only"
    private const val KEY_PROVIDERS = "show_providers"

    // Internal state, not exported
    private const val KEY_LAST_GENERATION = "_last_generation"
    private const val KEY_LAST_SIGNATURE = "_last_signature"
    private const val KEY_LAST_ERROR = "_last_error"
    private const val KEY_GENERATING = "_generating"

    val COUNT_OPTIONS = listOf(5, 10, 15, 20)
    val REFRESH_OPTIONS = listOf(6, 12, 24, 72, 168)
    val WIDTH_OPTIONS = listOf(1920, 3840)

    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        if (!::preferences.isInitialized) {
            preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    var token: String
        get() = preferences.getString(KEY_TOKEN, null)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: BuildConfig.DEFAULT_TMDB_TOKEN
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

    var content: ContentType
        get() = ContentType.from(preferences.getString(KEY_CONTENT, null))
        set(value) = preferences.edit { putString(KEY_CONTENT, value.key) }

    var titlesPerType: Int
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
            token.hashCode(), language, region, content.key, titlesPerType,
            outputWidth, globalHitsOnly, showProviders
        ).joinToString("|")

    val refreshIntervalMillis: Long
        get() = refreshHours * 3_600_000L

    // --- Projectivy backup (getPreferences / setPreferences) ---

    private val json by lazy { Json { ignoreUnknownKeys = true; isLenient = true } }

    fun export(): String = buildJsonObject {
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("_")) return@forEach
            when (value) {
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is String -> put(key, JsonPrimitive(value))
            }
        }
    }.toString()

    fun import(prefs: String): Boolean = try {
        val root = json.parseToJsonElement(prefs) as JsonObject
        preferences.edit {
            root.forEach { (key, value) ->
                if (key.startsWith("_") || value !is JsonPrimitive) return@forEach
                when {
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
