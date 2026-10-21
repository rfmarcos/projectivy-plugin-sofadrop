package com.rfmarcos.tmdbwallpapers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** MDBList API (https://mdblist.docs.apiary.io). */
class MdbListClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Lists owned by the API key's account. */
    fun userLists(): List<MdbListRef> =
        (get("lists/user") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { list ->
            val id = list.int("id")?.toLong() ?: return@mapNotNull null
            MdbListRef(id, list.str("name") ?: list.str("slug") ?: id.toString())
        }

    /** Resolve a public list from "user/list" or a https://mdblist.com/lists/user/list URL. */
    fun findList(reference: String): MdbListRef? {
        val path = reference.trim().trimEnd('/')
            .substringAfter("mdblist.com/lists/", reference.trim().trimEnd('/'))
            .split('/').filter { it.isNotBlank() }
        if (path.size < 2) return null
        val response = get("lists/${path[0]}/${path[1]}")
        val list = (response as? JsonArray)?.firstOrNull() as? JsonObject ?: response as? JsonObject ?: return null
        val id = list.int("id")?.toLong() ?: return null
        return MdbListRef(id, list.str("name") ?: path[1])
    }

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
            addQueryParameter("apikey", apiKey)
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("MDBList HTTP ${response.code} for $path")
            return response.body?.string()?.let { json.parseToJsonElement(it) }
        }
    }

    /** A list item: its TMDB reference when known, otherwise its IMDb id. */
    data class Item(val type: MediaType, val ref: TitleRef?, val imdbId: String?)

    companion object {
        private const val API_URL = "https://api.mdblist.com/"

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
