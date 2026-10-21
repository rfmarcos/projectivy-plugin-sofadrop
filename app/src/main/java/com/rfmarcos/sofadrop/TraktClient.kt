package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** A title found on an external list, resolved to TMDB. */
data class TitleRef(val type: MediaType, val tmdbId: Int)

/**
 * Trakt API v2 (https://trakt.docs.apiary.io). Public lists only need the app's client id;
 * personal lists (recommendations, watchlist) need the user to connect with the device flow.
 * Apps created since October 2026 get no client secret: the device flow uses PKCE instead.
 */
class TraktClient(private val clientId: String, private val clientSecret: String) {

    data class DeviceCode(
        val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresIn: Int, val interval: Int,
        val codeVerifier: String,
    )

    sealed class Poll {
        data object Pending : Poll()
        data object SlowDown : Poll()
        data object Connected : Poll()
        data class Failed(val code: Int) : Poll()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json".toMediaType()

    // --- Device authentication ---

    fun requestDeviceCode(): DeviceCode {
        val verifier = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), PKCE_BASE64)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), PKCE_BASE64)
        val response = post("oauth/device/code", buildJsonObject {
            put("client_id", clientId)
            put("code_challenge", challenge)
            put("code_challenge_method", "S256")
        })
        val body = response.second as? JsonObject ?: throw IOException("Trakt device code failed (${response.first})")
        return DeviceCode(
            deviceCode = body.str("device_code") ?: throw IOException("Missing device_code"),
            userCode = body.str("user_code").orEmpty(),
            verificationUrl = body.str("verification_url") ?: "https://trakt.tv/activate",
            expiresIn = body.int("expires_in") ?: 600,
            interval = body.int("interval") ?: 5,
            codeVerifier = verifier,
        )
    }

    /** Poll once for the token. 400 = still pending, 429 = polling too fast, others are final. */
    fun pollDeviceToken(device: DeviceCode): Poll {
        val (code, body) = post("oauth/device/token", buildJsonObject {
            put("code", device.deviceCode)
            put("client_id", clientId)
            put("code_verifier", device.codeVerifier)
            if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
        })
        return when (code) {
            200 -> {
                saveTokens(body as JsonObject)
                Settings.traktUser = username().orEmpty()
                Poll.Connected
            }
            400 -> Poll.Pending
            429 -> Poll.SlowDown
            else -> Poll.Failed(code)
        }
    }

    /** Refresh the access token when it expires in less than a day (tokens last 7 days). */
    private fun refreshIfNeeded() {
        if (Settings.traktRefreshToken.isBlank()) return
        if (Settings.traktExpiresAt - System.currentTimeMillis() > 24 * 3_600_000L) return
        val (code, body) = post("oauth/token", buildJsonObject {
            put("refresh_token", Settings.traktRefreshToken)
            put("client_id", clientId)
            if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
            put("redirect_uri", REDIRECT_URI)
            put("grant_type", "refresh_token")
        })
        if (code == 200) saveTokens(body as JsonObject)
        else if (code == 400 || code == 401) Settings.clearTraktSession()
    }

    private fun saveTokens(body: JsonObject) {
        Settings.traktAccessToken = body.str("access_token").orEmpty()
        Settings.traktRefreshToken = body.str("refresh_token").orEmpty()
        val createdAt = (body.double("created_at") ?: (System.currentTimeMillis() / 1000.0)).toLong()
        Settings.traktExpiresAt = (createdAt + (body.int("expires_in") ?: 0)) * 1000L
    }

    fun username(): String? =
        (get("users/settings", auth = true) as? JsonObject)?.obj("user")?.str("username")

    // --- Lists ---

    /** TMDB ids of a Trakt list key such as "trakt:trending_movies". */
    fun titles(listKey: String, limit: Int): List<TitleRef> {
        val (path, type, auth) = when (listKey) {
            "trakt:trending_movies" -> Triple("movies/trending", MediaType.MOVIE, false)
            "trakt:trending_shows" -> Triple("shows/trending", MediaType.TV, false)
            "trakt:popular_movies" -> Triple("movies/popular", MediaType.MOVIE, false)
            "trakt:popular_shows" -> Triple("shows/popular", MediaType.TV, false)
            "trakt:anticipated_movies" -> Triple("movies/anticipated", MediaType.MOVIE, false)
            "trakt:anticipated_shows" -> Triple("shows/anticipated", MediaType.TV, false)
            "trakt:boxoffice" -> Triple("movies/boxoffice", MediaType.MOVIE, false)
            "trakt:recommended_movies" -> Triple("recommendations/movies?ignore_collected=true", MediaType.MOVIE, true)
            "trakt:recommended_shows" -> Triple("recommendations/shows?ignore_collected=true", MediaType.TV, true)
            "trakt:watchlist_movies" -> Triple("sync/watchlist/movies/rank/asc", MediaType.MOVIE, true)
            "trakt:watchlist_shows" -> Triple("sync/watchlist/shows/rank/asc", MediaType.TV, true)
            else -> return emptyList()
        }
        if (auth && !Settings.traktConnected) return emptyList()
        // Ask for extra items: some have no TMDB id and some are filtered out later
        val separator = if ('?' in path) '&' else '?'
        val items = get("$path${separator}limit=${limit * 2}", auth) as? JsonArray ?: return emptyList()
        return parseTitles(items, type)
    }

    // --- Watched content ---

    /** TMDB ids of watched movies. */
    fun watchedMovies(): Set<Int> =
        (get("sync/watched/movies", auth = true) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it.obj("movie")?.obj("ids")?.int("tmdb") }.toSet()

    /** Shows with at least one watched episode: TMDB id -> Trakt id. */
    fun watchedShows(): Map<Int, Int> =
        (get("sync/watched/shows?extended=noseasons", auth = true) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { item ->
                val ids = item.obj("show")?.obj("ids") ?: return@mapNotNull null
                val tmdb = ids.int("tmdb") ?: return@mapNotNull null
                val trakt = ids.int("trakt") ?: return@mapNotNull null
                tmdb to trakt
            }.toMap()

    /** Episodes (completed, aired) of a show. */
    fun showProgress(traktId: Int): Pair<Int, Int>? {
        val progress = get("shows/$traktId/progress/watched", auth = true) as? JsonObject ?: return null
        return (progress.int("completed") ?: 0) to (progress.int("aired") ?: 0)
    }

    // --- HTTP ---

    private fun request(path: String, auth: Boolean) = Request.Builder()
        .url("$API_URL$path")
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", clientId)
        .header("User-Agent", USER_AGENT)
        .apply {
            if (auth && Settings.traktAccessToken.isNotBlank()) header("Authorization", "Bearer ${Settings.traktAccessToken}")
        }

    private fun get(path: String, auth: Boolean): JsonElement? {
        if (auth) refreshIfNeeded()
        http.newCall(request(path, auth).build()).execute().use { response ->
            if (response.code == 401 && auth) {
                Settings.clearTraktSession()
                return null
            }
            if (!response.isSuccessful) throw IOException("Trakt HTTP ${response.code} for $path")
            return response.body?.string()?.let { json.parseToJsonElement(it) }
        }
    }

    private fun post(path: String, body: JsonObject): Pair<Int, JsonElement?> {
        val call = request(path, auth = false).post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(call).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = if (response.isSuccessful && text.isNotBlank()) json.parseToJsonElement(text) else null
            return response.code to parsed
        }
    }

    companion object {
        private const val API_URL = "https://api.trakt.tv/"
        private const val USER_AGENT = "Sofadrop/1.0"
        private const val PKCE_BASE64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        // Never opened (device flow), but the token refresh must send the URI registered for the Trakt app
        const val REDIRECT_URI = "https://github.com/rfmarcos/projectivy-plugin-sofadrop"

        /** Items are either `{ "movie": {...} }` / `{ "show": {...} }` or the object itself. */
        fun parseTitles(items: JsonArray, type: MediaType): List<TitleRef> =
            items.filterIsInstance<JsonObject>().mapNotNull { item ->
                val media = item.obj("movie") ?: item.obj("show") ?: item
                media.obj("ids")?.int("tmdb")?.let { TitleRef(type, it) }
            }
    }
}
