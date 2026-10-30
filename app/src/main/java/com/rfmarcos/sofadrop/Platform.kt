package com.rfmarcos.sofadrop

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Streaming services a wallpaper can open its title on (the ones the user picks, in their order: see
 * [Settings.clickPlatforms]). TMDB says which ones have it in the streaming country (by provider id),
 * Wikidata gives the title's id on each one (when someone added it: TMDB and JustWatch don't share them)
 * and the service's app opens that page.
 */
enum class Platform(
    val key: String,
    val label: String,
    /** TMDB watch provider ids, with their ad tiers and older ids (not channels inside another service). */
    private val providerIds: Set<Int>,
    /** Android TV app first. */
    private val packages: List<String>,
    /** Wikidata properties of its ids, best first. */
    val ids: List<WikidataId>,
    /** Countries where TMDB lists it, for a service of a few; null: most of the world. */
    private val countries: Set<String>? = null,
) {
    NETFLIX("netflix", "Netflix", setOf(8, 175, 1796), listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        listOf(WikidataId("P1874") { "https://www.netflix.com/title/$it" })),
    PRIME("prime", "Prime Video", setOf(9, 119, 2100), listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        listOf(
            WikidataId("P14462") { "https://app.primevideo.com/detail?gti=$it" },
            WikidataId("P14440") { "https://www.primevideo.com/detail/$it" },
            WikidataId("P8055") { "https://www.primevideo.com/detail/$it" },
        )),
    DISNEY("disney", "Disney+", setOf(337), listOf("com.disney.disneyplus"),
        listOf(
            WikidataId("P13902") { "https://www.disneyplus.com/browse/$it" },
            WikidataId("P7595", series = false) { "https://www.disneyplus.com/movies/wd/$it" },
            WikidataId("P7596", movies = false) { "https://www.disneyplus.com/series/wp/$it" },
        )),
    MAX("max", "HBO Max", setOf(384, 1899), listOf("com.wbd.stream"),
        listOf(WikidataId("P8298") { "https://play.hbomax.com/$it" })),
    APPLE("apple", "Apple TV", setOf(350), listOf("com.apple.atve.androidtv.appletv"),
        listOf(
            WikidataId("P9586", series = false) { "https://tv.apple.com/movie/$it" },
            WikidataId("P9751", movies = false) { "https://tv.apple.com/show/$it" },
        )),
    MUBI("mubi", "MUBI", setOf(11), listOf("com.mubi"),
        listOf(WikidataId("P7299") { "https://mubi.com/films/$it" })),
    CRUNCHYROLL("crunchyroll", "Crunchyroll", setOf(283), listOf("com.crunchyroll.crunchyroid"),
        listOf(WikidataId("P11330") { "https://www.crunchyroll.com/series/$it" })),
    HULU("hulu", "Hulu", setOf(15), listOf("com.hulu.livingroomplus", "com.hulu.plus"),
        listOf(
            WikidataId("P6466", series = false) { "https://www.hulu.com/movie/$it" },
            WikidataId("P6467", movies = false) { "https://www.hulu.com/series/$it" },
        ), setOf("US")),
    TUBI("tubi", "Tubi", setOf(73), listOf("com.tubitv"),
        listOf(
            WikidataId("P7760", series = false) { "https://tubitv.com/movies/$it" },
            WikidataId("P7761", movies = false) { "https://tubitv.com/series/$it" },
        ), setOf("AU", "CA", "GB", "IN", "MX", "US")),
    KANOPY("kanopy", "Kanopy", setOf(191), listOf("com.kanopy"),
        listOf(WikidataId("P7985") { "https://www.kanopy.com/product/$it" }), setOf("US")),
    HOOPLA("hoopla", "Hoopla", setOf(212), listOf("com.hoopladigital.android"),
        listOf(WikidataId("P5680") { "https://www.hoopladigital.com/title/$it" }), setOf("AU", "CA", "NZ", "US")),
    UNEXT("unext", "U-NEXT", setOf(84), listOf("jp.unext.mediaplayer", "jp.unext.tv.player"),
        listOf(WikidataId("P14567") { "https://video.unext.jp/title/$it" }), setOf("JP")),
    NRK("nrk", "NRK TV", setOf(442), listOf("no.nrk.tv"),
        listOf(WikidataId("P9979") { "https://tv.nrk.no/$it" }), setOf("NO")),
    SVT("svt", "SVT Play", setOf(493), listOf("se.svt.android.svtplay"),
        listOf(WikidataId("P6817") { "https://www.svtplay.se/$it" }), setOf("SE")),
    YLE("yle", "Yle Areena", setOf(323), listOf("com.yle.webtv"),
        listOf(WikidataId("P3414") { "https://areena.yle.fi/$it" }), setOf("FI"));

    /** A Wikidata property holding the title's id on a platform, and the page of an id. */
    class WikidataId(val property: String, val movies: Boolean = true, val series: Boolean = true, val url: (String) -> String)

    /** Worth offering in [region]: TMDB lists it there, or its app is installed anyway. */
    fun offeredIn(region: String, context: Context) = countries == null || region in countries || installedPackage(context) != null

    fun installedPackage(context: Context): String? = packages.firstOrNull { name ->
        try {
            context.packageManager.getPackageInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Page of the title on this platform, from its Wikidata [ids] (property to value). */
    fun link(type: MediaType, ids: Map<String, String>): String? = this.ids
        .filter { if (type == MediaType.TV) it.series else it.movies }
        .firstNotNullOfOrNull { id -> ids[id.property]?.let(id.url) }

    companion object {
        fun of(key: String): Platform? = entries.firstOrNull { it.key == key }

        fun ofProvider(providerId: Int): Platform? = entries.firstOrNull { providerId in it.providerIds }

        /** Pages of a title on every platform Wikidata knows it on, by [key]. */
        fun links(type: MediaType, ids: Map<String, String>): Map<String, String> =
            entries.mapNotNull { platform -> platform.link(type, ids)?.let { platform.key to it } }.toMap()

        /**
         * The platforms of [order] (the user's, best first) that have the title in the streaming country
         * ([providerIds]), a page for it in [links] and are [installed], with that page.
         */
        fun pick(order: List<Platform>, providerIds: List<Int>, links: Map<String, String>, installed: (Platform) -> Boolean): List<Pair<Platform, String>> {
            val available = providerIds.mapNotNull(::ofProvider).toSet()
            return order.filter { it in available && installed(it) }.mapNotNull { platform -> links[platform.key]?.let { platform to it } }
        }
    }
}

/** Ids of titles on the streaming platforms, from Wikidata's query service (one request per media type). */
object Wikidata {
    private const val URL = "https://query.wikidata.org/sparql"
    // Wikidata asks for a user agent that says who is calling
    private val USER_AGENT = "Sofadrop/${BuildConfig.VERSION_NAME} (https://github.com/rfmarcos/projectivy-plugin-sofadrop)"
    private val PROPERTIES = Platform.entries.flatMap { platform -> platform.ids.map { it.property } }.distinct()
    /** Wikidata properties of TMDB ids. */
    private val TMDB_PROPERTY = mapOf(MediaType.MOVIE to "P4947", MediaType.TV to "P4983")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Platform ids of [refs] (Wikidata property to value), only for the titles Wikidata knows. */
    fun ids(refs: Collection<TitleRef>): Map<TitleRef, Map<String, String>> =
        refs.groupBy { it.type }.flatMap { (type, ofType) ->
            ofType.chunked(BATCH).flatMap { batch ->
                val body = FormBody.Builder().add("query", query(TMDB_PROPERTY.getValue(type), batch.map { it.tmdbId })).build()
                val request = Request.Builder().url(URL).post(body)
                    .header("User-Agent", USER_AGENT).header("Accept", "application/sparql-results+json").build()
                val text = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("Wikidata: HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                parse(text).map { (tmdbId, ids) -> TitleRef(type, tmdbId) to ids }
            }
        }.toMap()

    /** One row per title: its TMDB id ([tmdbProperty]) and a sample value of each platform property. */
    internal fun query(tmdbProperty: String, tmdbIds: List<Int>): String {
        val values = tmdbIds.joinToString(" ") { "\"$it\"" }
        val samples = PROPERTIES.joinToString(" ") { "(SAMPLE(?$it) AS ?${it}_)" }
        val optionals = PROPERTIES.joinToString(" ") { "OPTIONAL { ?item wdt:$it ?$it }" }
        return "SELECT ?tmdb $samples WHERE { VALUES ?tmdb { $values } ?item wdt:$tmdbProperty ?tmdb . $optionals } GROUP BY ?tmdb"
    }

    /** TMDB id to its platform ids, from a SPARQL JSON result of [query]. */
    internal fun parse(text: String): Map<Int, Map<String, String>> {
        val rows = (Json.parseToJsonElement(text) as? JsonObject)?.obj("results")?.get("bindings") as? JsonArray ?: return emptyMap()
        return rows.filterIsInstance<JsonObject>().mapNotNull { row ->
            val tmdbId = row.obj("tmdb")?.str("value")?.toIntOrNull() ?: return@mapNotNull null
            tmdbId to row.keys.filter { it.endsWith("_") }.mapNotNull { key -> row.obj(key)?.str("value")?.let { key.removeSuffix("_") to it } }.toMap()
        }.toMap()
    }

    private const val BATCH = 100
}
