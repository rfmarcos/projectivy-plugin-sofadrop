package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerTest {

    /** Spanish, then the English fallback (the default). */
    private val ES_EN = listOf("es", "en")

    private fun video(key: String, type: String = "Trailer", language: String? = "en", official: Boolean = true,
                      published: String = "2024-05-01T16:00:00.000Z", site: String = "YouTube") =
        """{"iso_639_1":${language?.let { "\"$it\"" }},"iso_3166_1":"US","key":"$key","site":"$site","type":"$type","official":$official,"published_at":"$published"}"""

    private fun videos(vararg results: String) = Json.parseToJsonElement("""{"results":[${results.joinToString(",")}]}""") as JsonObject

    @Test
    fun officialTrailerInTheSynopsisLanguageFirst() {
        val videos = videos(
            video("en-old", published = "2024-01-01T00:00:00.000Z"),
            video("es-old", language = "es", published = "2024-01-01T00:00:00.000Z"),
            video("es-new", language = "es", published = "2024-06-01T00:00:00.000Z"),
            video("es-fan", language = "es", official = false, published = "2024-09-01T00:00:00.000Z"),
            video("es-teaser", type = "Teaser", language = "es", published = "2024-09-01T00:00:00.000Z"),
            video("vimeo", language = "es", site = "Vimeo", published = "2024-10-01T00:00:00.000Z"),
        )
        assertEquals("es-new", TmdbCatalog.trailerKey(videos, ES_EN))
    }

    @Test
    fun officialEnglishTrailerWhenNoneInTheSynopsisLanguage() {
        val videos = videos(
            video("es-fan", language = "es", official = false, published = "2024-09-01T00:00:00.000Z"),
            video("en-old", published = "2024-01-01T00:00:00.000Z"),
            video("en-new", published = "2024-03-01T00:00:00.000Z"),
        )
        assertEquals("en-new", TmdbCatalog.trailerKey(videos, ES_EN))
        // A title in another language: its official trailer only counts in the synopsis language or English
        assertEquals("en-new", TmdbCatalog.trailerKey(videos(video("ko", language = "ko"), video("en-new")), ES_EN))
    }

    @Test
    fun anyTrailerThenATeaser() {
        // No official trailer: the newest trailer, whatever its language
        val unofficial = videos(
            video("ko-teaser", type = "Teaser", language = "ko", published = "2024-08-01T00:00:00.000Z"),
            video("ko", language = "ko", official = false, published = "2024-02-01T00:00:00.000Z"),
            video("none", language = null, official = false, published = "2024-04-01T00:00:00.000Z"),
            video("clip", type = "Clip", published = "2024-09-01T00:00:00.000Z"),
        )
        assertEquals("none", TmdbCatalog.trailerKey(unofficial, ES_EN))

        val teasers = videos(
            video("teaser-old", type = "Teaser", published = "2024-01-01T00:00:00.000Z"),
            video("teaser-new", type = "Teaser", language = "fr", official = false, published = "2024-02-01T00:00:00.000Z"),
            video("featurette", type = "Featurette", published = "2024-09-01T00:00:00.000Z"),
        )
        assertEquals("teaser-new", TmdbCatalog.trailerKey(teasers, ES_EN))
    }

    @Test
    fun fallbacks() {
        val videos = videos(
            video("ko", language = "ko", published = "2024-01-01T00:00:00.000Z"),
            video("en", published = "2024-02-01T00:00:00.000Z"),
            video("fr-fan", language = "fr", official = false, published = "2024-09-01T00:00:00.000Z"),
        )
        // Original language as the fallback (a Korean title in Spanish)
        assertEquals("ko", TmdbCatalog.trailerKey(videos, listOf("es", "ko")))
        // Any trailer: the newest, official or not
        assertEquals("fr-fan", TmdbCatalog.trailerKey(videos, listOf("es")))
        // None: only the trailer language
        assertNull(TmdbCatalog.trailerKey(videos, listOf("es"), anyTrailer = false))
        assertEquals("en", TmdbCatalog.trailerKey(videos, listOf("en"), anyTrailer = false))
    }

    @Test
    fun nothingToOpen() {
        assertNull(TmdbCatalog.trailerKey(null, ES_EN))
        assertNull(TmdbCatalog.trailerKey(videos(), ES_EN))
        assertNull(TmdbCatalog.trailerKey(videos(video("clip", type = "Clip"), video("vimeo", site = "Vimeo")), ES_EN))
        assertNull(TmdbCatalog.trailerKey(Json.parseToJsonElement("""{"results":[{"site":"YouTube","type":"Trailer","key":""}]}""") as JsonObject, ES_EN))
    }
}
