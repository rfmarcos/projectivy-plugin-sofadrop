package com.rfmarcos.sofadrop

import androidx.core.text.HtmlCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MDBList API (https://api.mdblist.com/docs). Works with the account connected through the OAuth
 * device flow (QR code) or, as a fallback, with an API key.
 */
class MdbListClient(private val apiKey: String = "", private val accessToken: String = "") {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Lists owned by the account. */
    fun userLists(): List<MdbListRef> =
        (get("lists/user") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { list ->
            val id = list.int("id")?.toLong() ?: return@mapNotNull null
            MdbListRef(id, list.str("name") ?: list.str("slug") ?: id.toString())
        }

    /** Account name, shown in the settings. */
    fun username(): String? = (get("user") as? JsonObject)?.let { it.str("username") ?: it.str("name") }

    /** Titles of a list; items without a TMDB id are resolved from their IMDb id. */
    fun titles(listId: Long, limit: Int, tmdb: TmdbClient): List<TitleRef> {
        val body = get("lists/$listId/items", "limit" to (limit * 2).toString()) as? JsonObject ?: return emptyList()
        return parseItems(body).mapNotNull { item ->
            item.ref ?: item.imdbId?.let { resolveImdb(tmdb, it, item.type) }
        }
    }

    private fun resolveImdb(tmdb: TmdbClient, imdbId: String, type: MediaType): TitleRef? {
        val found = tmdb.get("find/$imdbId?external_source=imdb_id") ?: return null
        val key = if (type == MediaType.MOVIE) "movie_results" else "tv_results"
        return found.objects(key).firstOrNull()?.int("id")?.let { TitleRef(type, it) }
    }

    private fun get(path: String, vararg params: Pair<String, String>): JsonElement? {
        val url = "$API_URL$path".toHttpUrl().newBuilder().apply {
            if (accessToken.isBlank()) addQueryParameter("apikey", apiKey)
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).apply {
            if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken")
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("MDBList HTTP ${response.code} for $path")
            return response.body?.string()?.let { json.parseToJsonElement(it) }
        }
    }

    /** A list item: its TMDB reference when known, otherwise its IMDb id. */
    data class Item(val type: MediaType, val ref: TitleRef?, val imdbId: String?)

    // --- OAuth device flow ---

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed class Poll {
        data object Pending : Poll()
        data object SlowDown : Poll()
        data object Connected : Poll()
        data class Failed(val error: String) : Poll()
    }

    /** Device flow of the app registered on mdblist.com/developer (only a client id is needed). */
    class Auth(private val clientId: String) {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun requestDeviceCode(): DeviceCode {
            val body = post("oauth/device-authorization/", "client_id" to clientId, "scope" to "write").second
                ?: throw IOException("MDBList did not return a device code")
            val verification = body.str("verification_uri") ?: "https://mdblist.com/oauth/device/"
            val userCode = body.str("user_code").orEmpty()
            return DeviceCode(
                deviceCode = body.str("device_code") ?: throw IOException("Missing device_code"),
                userCode = userCode,
                verificationUri = verification,
                verificationUriComplete = body.str("verification_uri_complete") ?: "$verification?user_code=$userCode",
                expiresIn = body.int("expires_in") ?: 1800,
                interval = body.int("interval") ?: 5,
            )
        }

        fun poll(deviceCode: String): Poll {
            val (code, body) = post(
                "oauth/token/",
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to deviceCode,
                "client_id" to clientId,
            )
            if (code == 200 && body?.str("access_token") != null) {
                save(body)
                val client = MdbListClient(accessToken = Settings.mdbListAccessToken)
                Settings.mdbListUser = runCatching { client.username() }.getOrNull().orEmpty()
                runCatching { Settings.mdbLists = (client.userLists() + Settings.mdbLists).distinctBy { it.key } }
                return Poll.Connected
            }
            return when (val error = body?.str("error")) {
                "authorization_pending" -> Poll.Pending
                "slow_down" -> Poll.SlowDown
                else -> Poll.Failed(error ?: "HTTP $code")
            }
        }

        /** Access tokens last 30 days; refresh them when less than two days are left. */
        fun refreshIfNeeded() {
            if (Settings.mdbListRefreshToken.isBlank()) return
            if (Settings.mdbListExpiresAt - System.currentTimeMillis() > 2 * 86_400_000L) return
            val (code, body) = post(
                "oauth/token/",
                "grant_type" to "refresh_token",
                "refresh_token" to Settings.mdbListRefreshToken,
                "client_id" to clientId,
            )
            if (code == 200 && body?.str("access_token") != null) save(body)
            else if (code == 400 || code == 401) Settings.clearMdbListSession()
        }

        private fun save(body: JsonObject) {
            Settings.mdbListAccessToken = body.str("access_token").orEmpty()
            Settings.mdbListRefreshToken = body.str("refresh_token").orEmpty()
            Settings.mdbListExpiresAt = System.currentTimeMillis() + (body.int("expires_in") ?: 2_592_000) * 1000L
        }

        private fun post(path: String, vararg fields: Pair<String, String>): Pair<Int, JsonObject?> {
            val form = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
            http.newCall(Request.Builder().url("$API_URL$path").post(form).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                return response.code to runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            }
        }
    }

    companion object {
        private const val API_URL = "https://api.mdblist.com/"
        private const val SITE_URL = "https://mdblist.com/lists/"
        private val PATH_PART = Regex("[A-Za-z0-9_.-]+")
        private val TITLE = Regex("<title>([^<]*)</title>")

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Client for the configured account (refreshing its token) or API key; null when not configured. */
        fun fromSettings(): MdbListClient? = when {
            Settings.mdbListConnected -> {
                if (Settings.mdbListClientId.isNotBlank()) runCatching { Auth(Settings.mdbListClientId).refreshIfNeeded() }
                Settings.mdbListAccessToken.takeIf { it.isNotBlank() }?.let { MdbListClient(accessToken = it) }
            }
            Settings.mdbListKey.isNotBlank() -> MdbListClient(apiKey = Settings.mdbListKey)
            else -> null
        }

        /** Popular public lists offered in the MDBList screen (from mdblist.com/toplists). */
        val SUGGESTED = listOf(
            MdbListRef(0, "Top Movies of the Week", "garycrawfordgc/top-movies-of-the-week"),
            MdbListRef(0, "IMDb Moviemeter (Top 100)", "linaspurinis/imdb-moviemeter-top-100"),
            MdbListRef(0, "Latest Streaming Movies", "snoak/latest-movies-digital-release"),
            MdbListRef(0, "Netflix Movies", "garycrawfordgc/netflix-movies"),
            MdbListRef(0, "Netflix Shows", "garycrawfordgc/netflix-shows"),
            MdbListRef(0, "HBO Shows", "garycrawfordgc/hbo-shows"),
            MdbListRef(0, "Disney+ Shows", "garycrawfordgc/disney-shows"),
            MdbListRef(0, "Trending Anime Shows", "snoak/trending-anime-shows"),
            MdbListRef(0, "Top 250 Movies (IMDb)", "snoak/imdb-top-250-movies"),
            MdbListRef(0, "Top 250 Shows (IMDb)", "snoak/imdb-top-250-shows"),
        )

        /** "user/slug" of a public list from "user/slug" or a https://mdblist.com/lists/user/slug URL. */
        fun publicPath(reference: String): String? {
            val trimmed = reference.trim().trimEnd('/')
            val parts = trimmed.substringAfter("mdblist.com/lists/", trimmed).split('/').filter { it.isNotBlank() }
            return if (parts.size == 2 && parts.all { it.matches(PATH_PART) }) parts.joinToString("/") else null
        }

        /** Find a public list (no account or key needed); null when it does not exist or is private. */
        fun findPublicList(reference: String): MdbListRef? {
            val path = publicPath(reference) ?: return null
            if (fetchPublic(path, 1) == null) return null
            // The page title reads "<name>, a list by <user> - mdblist.com"
            val name = runCatching {
                http.newCall(Request.Builder().url("$SITE_URL$path").build()).execute().use { it.body?.string() }
                    ?.let { TITLE.find(it)?.groupValues?.get(1) }?.let { HtmlCompat.fromHtml(it, 0).toString() }?.substringBefore(", a list by ")?.trim()
            }.getOrNull()
            return MdbListRef(0, name?.takeIf { it.isNotEmpty() } ?: path.substringAfter('/').replace('-', ' '), path)
        }

        /** Titles of a public list from its JSON export; its ids are TMDB ids. */
        fun publicTitles(path: String, limit: Int): List<TitleRef> =
            fetchPublic(path, limit).orEmpty().filterIsInstance<JsonObject>().mapNotNull { item ->
                val type = if (item.str("mediatype") == "show") MediaType.TV else MediaType.MOVIE
                item.int("id")?.let { TitleRef(type, it) }
            }

        private fun fetchPublic(path: String, limit: Int): JsonArray? {
            val url = "$SITE_URL$path/json".toHttpUrl().newBuilder().addQueryParameter("limit", limit.toString()).build()
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw IOException("MDBList HTTP ${response.code} for $path")
                return response.body?.string()?.let { Json.parseToJsonElement(it) as? JsonArray }
            }
        }

        /** Items come grouped as `{ "movies": [...], "shows": [...] }`. */
        fun parseItems(body: JsonObject): List<Item> {
            val movies = body.objects("movies").map { MediaType.MOVIE to it }
            val shows = body.objects("shows").map { MediaType.TV to it }
            return (movies + shows).map { (type, item) ->
                // For movies the top-level id is the TMDB id; shows only trust ids.tmdb
                val tmdbId = item.obj("ids")?.int("tmdb") ?: (if (type == MediaType.MOVIE) item.int("id") else null)
                Item(type, tmdbId?.let { TitleRef(type, it) }, item.str("imdb_id") ?: item.obj("ids")?.str("imdb"))
            }
        }
    }
}
