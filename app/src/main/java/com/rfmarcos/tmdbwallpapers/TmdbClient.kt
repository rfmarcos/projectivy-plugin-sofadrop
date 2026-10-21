package com.rfmarcos.tmdbwallpapers

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class TmdbException(message: String) : IOException(message)

/**
 * Minimal TMDB client. Accepts either a v3 API key (32 hex chars, easy to type with a remote)
 * or a v4 "API Read Access Token".
 */
class TmdbClient(token: String, cacheDir: File) {

    private val token = token.trim()
    private val isApiKey = Regex("^[0-9a-fA-F]{32}$").matches(this.token)

    private val http = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "http"), 150L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** GET an API path such as `trending/movie/week?language=es-ES`. Returns null on 404. */
    fun get(path: String): JsonObject? {
        val separator = if ('?' in path) '&' else '?'
        val url = if (isApiKey) "$API_URL$path${separator}api_key=$token" else "$API_URL$path"
        val builder = Request.Builder().url(url).header("accept", "application/json")
        if (!isApiKey) builder.header("Authorization", "Bearer $token")
        val body = execute(builder.build(), path) ?: return null
        return json.parseToJsonElement(body.decodeToString()) as? JsonObject
    }

    /** POST a JSON body; returns the HTTP code and the parsed body (TMDB answers errors in JSON too). */
    fun post(path: String, body: JsonObject): Pair<Int, JsonObject?> {
        val separator = if ('?' in path) '&' else '?'
        val url = if (isApiKey) "$API_URL$path${separator}api_key=$token" else "$API_URL$path"
        val builder = Request.Builder().url(url)
            .header("accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (!isApiKey) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return response.code to (runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull())
        }
    }

    /** Download a TMDB image, e.g. size `original` or `w500`. */
    fun image(filePath: String, size: String = "original"): ByteArray? =
        try {
            execute(Request.Builder().url("$IMAGE_URL$size$filePath").build(), filePath)
        } catch (e: IOException) {
            Log.w(TAG, "Image download failed for $filePath: ${e.message}")
            null
        }

    private fun execute(request: Request, label: String): ByteArray? {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> return response.body?.bytes()
                        response.code == 404 -> return null
                        response.code == 401 -> throw TmdbException("TMDB rejected the API key (401)")
                        response.code == 429 || response.code >= 500 ->
                            lastError = IOException("HTTP ${response.code} for $label")
                        else -> throw IOException("HTTP ${response.code} for $label")
                    }
                }
            } catch (e: TmdbException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
            Thread.sleep(1000L * (attempt + 1))
        }
        throw lastError ?: IOException("Request failed for $label")
    }

    companion object {
        private const val TAG = "TmdbClient"
        private const val API_URL = "https://api.themoviedb.org/3/"
        private const val IMAGE_URL = "https://image.tmdb.org/t/p/"
        private const val MAX_ATTEMPTS = 3
    }
}

// --- Small JSON helpers ---

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

fun JsonObject.objects(key: String): List<JsonObject> = array(key).filterIsInstance<JsonObject>()
