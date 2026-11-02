package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Simkl's recommendations are a PRO and VIP feature: a free account can't read them. */
class SimklPremiumException : IOException("Simkl recommendations need a PRO or VIP account")

/**
 * Simkl API (https://api.simkl.org). Trending lists come from its public CDN (no key); the user's
 * library (plan to watch, watching, completed) and recommendations need the account connected with the device flow.
 */
class SimklClient(private val clientId: String, private val tmdb: TmdbClient? = null) {

    /** TMDB ids of a Simkl list key such as "simkl:trending_anime". */
    fun titles(listKey: String, limit: Int): List<TitleRef> = when (listKey) {
        "simkl:trending_anime" -> trending("anime")
        "simkl:trending_movies" -> trending("movies")
        "simkl:trending_shows" -> trending("tv")
        "simkl:plantowatch" -> library("plantowatch").sortedByDescending { it.addedAt }.asSequence().map { it.ref }
        "simkl:watching" -> library("watching").sortedByDescending { it.watchedAt }.asSequence().map { it.ref }
        "simkl:recommended_movies" -> recommendations("movies").asSequence()
        "simkl:recommended_shows" -> recommendations("tv").asSequence()
        "simkl:recommended_anime" -> recommendations("anime").asSequence()
        else -> emptySequence()
    }.distinct().take(limit * 2).toList()

    /** Weekly trending file of a type ("movies", "tv" or "anime"); lazy, so anime ids are only resolved as needed. */
    private fun trending(type: String): Sequence<TitleRef> {
        val items = fetch("$CDN_URL/discover/trending/$type/week_100.json") as? JsonArray ?: return emptySequence()
        return items.asSequence().filterIsInstance<JsonObject>().mapNotNull { item ->
            val ids = item.obj("ids")
            val movie = type == "movies" || (type == "anime" && item.str("anime_type") == "movie")
            ref(if (movie) MediaType.MOVIE else MediaType.TV, ids?.str("tmdb"), ids?.str("imdb"), anime = type == "anime")
        }
    }

    /**
     * Simkl links anime seasons to TMDB *season* ids, so anime series go through their IMDb id
     * (which names the whole show) and only fall back to the TMDB id. Titles without a TMDB id
     * (custom lists don't always have one) are looked up by their IMDb id.
     */
    private fun ref(type: MediaType, tmdbId: String?, imdbId: String?, anime: Boolean): TitleRef? {
        val byImdb = { tmdb?.takeIf { imdbId != null }?.get("find/$imdbId?external_source=imdb_id")
            ?.objects(if (type == MediaType.MOVIE) "movie_results" else "tv_results")?.firstOrNull()?.int("id")
            ?.let { TitleRef(type, it) } }
        if (anime && type == MediaType.TV) byImdb()?.let { return it }
        return tmdbId?.toIntOrNull()?.let { TitleRef(type, it) } ?: byImdb()
    }

    // --- Recommendations (PRO and VIP) ---

    private val recommendationCache = ConcurrentHashMap<String, List<TitleRef>>()

    /**
     * The account's personal recommendations of a media type ("movies", "tv" or "anime"): Simkl keeps them as
     * custom lists of type "recommendations", two per type (V1 and V2), here taken in turns. Read once per
     * generation (a list read further asks again); a free account throws [SimklPremiumException].
     */
    private fun recommendations(mediaType: String): List<TitleRef> = recommendationCache.getOrPut(mediaType) {
        if (!Settings.simklConnected) return emptyList()
        val account = (get("users/settings") as? JsonObject)?.obj("account")
        if (account?.str("type") == "free") throw SimklPremiumException()
        val userId = account?.int("id") ?: return emptyList()
        val lists = get("lists/user/$userId", "limit" to "100") as? JsonObject ?: return emptyList()
        // A free account gets a 200 with this error instead of lists
        if (lists.str("error") == "premium_only") throw SimklPremiumException()
        val items = parseRecommendationLists(lists, mediaType).map { id ->
            val list = get("lists/$id", "limit" to "500") as? JsonObject
            if (list?.str("error") == "premium_only") throw SimklPremiumException()
            list?.let { parseListItems(it) }.orEmpty()
        }
        // V1, V2, V1, V2…: both engines' best picks first
        val turns = (0 until (items.maxOfOrNull { it.size } ?: 0)).flatMap { i -> items.mapNotNull { it.getOrNull(i) } }
        turns.distinct().mapNotNull { ref(it.type, it.tmdbId, it.imdbId, it.anime) }.distinct()
    }

    // --- The user's library ---

    data class LibraryItem(
        val ref: TitleRef,
        val addedAt: String,
        val watchedAt: String,
        val watched: Int,
        val total: Int,
        val anime: Boolean = false,
        val imdbId: String? = null,
    )

    /** Movies, shows and anime of the account with a status; items without a TMDB link are skipped. */
    fun library(status: String): List<LibraryItem> {
        if (!Settings.simklConnected) return emptyList()
        val body = get("sync/all-items/all/$status") as? JsonObject ?: return emptyList()
        // shortcut: each anime series costs one TMDB lookup (HTTP-cached); fine for personal libraries
        return parseLibrary(body).mapNotNull { item ->
            if (!item.anime) item else ref(item.ref.type, item.ref.tmdbId.toString(), item.imdbId, anime = true)?.let { item.copy(ref = it) }
        }
    }

    /** What the account has watched: completed titles and episode progress of the ones being watched. */
    fun watched(): Map<TitleRef, WatchStatus> {
        val completed = library("completed").associate { it.ref to WatchStatus.Watched }
        val watching = library("watching").filter { it.watched > 0 }.associate { it.ref to WatchStatus.InProgress(it.watched, it.total) }
        return watching + completed
    }

    /** Account name, shown in the settings. */
    fun username(): String? = (get("users/settings") as? JsonObject)?.obj("user")?.str("name")

    // --- HTTP ---

    private fun get(path: String, vararg params: Pair<String, String>): JsonElement? {
        refreshIfNeeded()
        val url = "$API_URL$path".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("app-name", APP_NAME)
            .addQueryParameter("app-version", BuildConfig.VERSION_NAME)
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer ${Settings.simklAccessToken}").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) {
                Settings.clearSimklSession()
                return null
            }
            if (!response.isSuccessful) throw IOException("Simkl HTTP ${response.code} for $path")
            return response.body?.string()?.let { json.parseToJsonElement(it) }
        }
    }

    // --- Device flow (AUTH V2: only the app's client id, no secret) ---

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String,
                          val verificationUriComplete: String, val expiresIn: Int, val interval: Int)

    sealed class Poll {
        data object Pending : Poll()
        data object SlowDown : Poll()
        data object Connected : Poll()
        data class Failed(val error: String) : Poll()
    }

    fun requestDeviceCode(): DeviceCode {
        val body = post("oauth2/device", "client_id" to clientId, "scope" to "media:read").second
            ?: throw IOException("Simkl did not return a device code")
        val verification = body.str("verification_uri") ?: "https://simkl.com/pin"
        val userCode = body.str("user_code").orEmpty()
        return DeviceCode(
            deviceCode = body.str("device_code") ?: throw IOException(body.str("error") ?: "Missing device_code"),
            userCode = userCode,
            verificationUri = verification,
            verificationUriComplete = body.str("verification_uri_complete") ?: "$verification?user_code=$userCode",
            expiresIn = body.int("expires_in") ?: 900,
            interval = body.int("interval") ?: 5,
        )
    }

    fun poll(device: DeviceCode): Poll {
        val (code, body) = post(
            "oauth2/token",
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to device.deviceCode,
            "client_id" to clientId,
        )
        if (code == 200 && body?.str("access_token") != null) {
            save(body)
            Settings.simklUser = runCatching { username() }.getOrNull().orEmpty()
            return Poll.Connected
        }
        return when (val error = body?.str("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            else -> Poll.Failed(error ?: "HTTP $code")
        }
    }

    /** Access tokens last 7 days, refresh tokens 180; refresh when less than a day is left. */
    private fun refreshIfNeeded() {
        if (Settings.simklRefreshToken.isBlank()) return
        if (Settings.simklExpiresAt - System.currentTimeMillis() > 86_400_000L) return
        val (code, body) = post(
            "oauth2/token",
            "grant_type" to "refresh_token",
            "refresh_token" to Settings.simklRefreshToken,
            "client_id" to clientId,
        )
        if (code == 200 && body?.str("access_token") != null) save(body)
        else if (code == 400 || code == 401) Settings.clearSimklSession()
    }

    private fun save(body: JsonObject) {
        Settings.simklAccessToken = body.str("access_token").orEmpty()
        Settings.simklRefreshToken = body.str("refresh_token").orEmpty()
        Settings.simklExpiresAt = System.currentTimeMillis() + (body.int("expires_in") ?: 604_800) * 1000L
    }

    private fun post(path: String, vararg fields: Pair<String, String>): Pair<Int, JsonObject?> {
        val form = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val request = Request.Builder().url("$API_URL$path").header("User-Agent", USER_AGENT).post(form).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return response.code to runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
    }

    private fun fetch(url: String): JsonElement? {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Simkl HTTP ${response.code} for $url")
            return response.body?.string()?.let { json.parseToJsonElement(it) }
        }
    }

    companion object {
        private const val API_URL = "https://api.simkl.com/"
        private const val CDN_URL = "https://data.simkl.in"
        private const val APP_NAME = "sofadrop"
        private const val USER_AGENT = "Sofadrop/1.0"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** A title of a custom list, before it's linked to TMDB. */
        data class ListItem(val type: MediaType, val tmdbId: String?, val imdbId: String?, val anime: Boolean)

        /** Ids of the recommendation lists of [mediaType] ("movies", "tv" or "anime") in `GET /lists/user/{id}`. */
        fun parseRecommendationLists(body: JsonObject, mediaType: String): List<Long> =
            body.objects("lists").filter { it.str("type") == "recommendations" && it.str("media_type") == mediaType }
                .mapNotNull { (it["id"] as? JsonPrimitive)?.longOrNull }

        /** The items of `GET /lists/{id}`: movies, shows and anime (an anime film is a movie). */
        fun parseListItems(body: JsonObject): List<ListItem> = body.objects("items").mapNotNull { item ->
            val ids = item.obj("ids") ?: return@mapNotNull null
            val anime = item.str("type") == "anime"
            val movie = item.str("type") == "movie" || (anime && item.str("anime_type") == "movie")
            ListItem(if (movie) MediaType.MOVIE else MediaType.TV, ids.str("tmdb"), ids.str("imdb"), anime)
                .takeIf { it.tmdbId != null || it.imdbId != null }
        }

        /** Library items come grouped as `{ "movies": [...], "shows": [...], "anime": [...] }`. */
        fun parseLibrary(body: JsonObject): List<LibraryItem> {
            val movies = body.objects("movies").map { Triple(it, it.obj("movie"), false) }
            val shows = body.objects("shows").map { Triple(it, it.obj("show"), false) }
            val anime = body.objects("anime").map { Triple(it, it.obj("show"), true) }
            return (movies + shows + anime).mapNotNull { (item, media, isAnime) ->
                // shortcut: anime without a TMDB link on Simkl is skipped; resolve via MAL ids if users ask
                val tmdb = media?.obj("ids")?.str("tmdb")?.toIntOrNull() ?: return@mapNotNull null
                val movie = item.obj("movie") != null || item.str("anime_type") == "movie"
                val total = (item.int("total_episodes_count") ?: 0) - (item.int("not_aired_episodes_count") ?: 0)
                LibraryItem(
                    TitleRef(if (movie) MediaType.MOVIE else MediaType.TV, tmdb),
                    item.str("added_to_watchlist_at").orEmpty(),
                    item.str("last_watched_at").orEmpty(),
                    item.int("watched_episodes_count") ?: 0,
                    total,
                    isAnime,
                    media.obj("ids")?.str("imdb"),
                )
            }
        }
    }
}
