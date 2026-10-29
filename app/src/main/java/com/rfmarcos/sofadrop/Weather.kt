package com.rfmarcos.sofadrop

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** A place found for the location typed in the screensaver settings. */
data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Current temperature (in the unit the locale uses) and WMO weather code. */
data class CurrentWeather(val temperature: Int, val code: Int)

/**
 * The weather for Sofascreen's clock, from Open-Meteo (free, no account or key, so nothing secret
 * to keep out of the backups). The last reading is kept for [MAX_AGE], so starting the screensaver
 * again doesn't ask again.
 */
object Weather {

    private const val TAG = "Weather"
    private const val MAX_AGE = 30 * 60_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var cached: CurrentWeather? = null
    private var cachedFor: Pair<Double, Double>? = null
    private var cachedAt = 0L

    /** The first place Open-Meteo knows by [name], named in the system language; null when there is none. */
    fun find(name: String): Place? {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", name.trim())
            .addQueryParameter("count", "1")
            .addQueryParameter("language", Locale.getDefault().language)
            .build()
        val result = get(url.toString())["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val label = listOfNotNull(result.text("name"), result.text("admin1"), result.text("country_code"))
            .distinct().joinToString(", ")
        return Place(label, result["latitude"]!!.jsonPrimitive.double, result["longitude"]!!.jsonPrimitive.double)
    }

    /** The weather now at [coordinates]; the last reading when it is recent, null when it can't be had. */
    @Synchronized
    fun current(coordinates: Pair<Double, Double>): CurrentWeather? {
        if (cachedFor == coordinates && System.currentTimeMillis() - cachedAt < MAX_AGE) return cached
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", coordinates.first.toString())
            .addQueryParameter("longitude", coordinates.second.toString())
            .addQueryParameter("current", "temperature_2m,weather_code")
            .apply { if (fahrenheit) addQueryParameter("temperature_unit", "fahrenheit") }
            .build()
        return runCatching {
            val now = get(url.toString())["current"]!!.jsonObject
            CurrentWeather(now["temperature_2m"]!!.jsonPrimitive.double.roundToInt(), now["weather_code"]!!.jsonPrimitive.int)
        }.onFailure { Log.w(TAG, "Weather failed", it) }.getOrNull()?.also {
            cached = it
            cachedFor = coordinates
            cachedAt = System.currentTimeMillis()
        }
    }

    /** Countries that use °F. */
    private val fahrenheit: Boolean get() = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

    /** The text of a WMO weather code, as Open-Meteo gives them. */
    fun description(code: Int): Int = when (code) {
        0 -> R.string.weather_clear
        1 -> R.string.weather_mostly_clear
        2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45, 48 -> R.string.weather_fog
        in 51..57 -> R.string.weather_drizzle
        in 61..67, in 80..82 -> R.string.weather_rain
        in 71..77, 85, 86 -> R.string.weather_snow
        in 95..99 -> R.string.weather_thunderstorm
        else -> R.string.weather_overcast
    }

    private fun get(url: String): JsonObject =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Open-Meteo HTTP ${response.code}")
            Json.parseToJsonElement(response.body!!.string()).jsonObject
        }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
