package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Title, logo and synopsis picked per language, from trimmed TMDB details, translations and images. */
class PartLanguagesTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun objects(text: String) = (Json.parseToJsonElement(text) as JsonArray).map { it as JsonObject }

    // A Japanese film fetched in es-ES: Spain has no synopsis of its own, Mexico does
    private val item = obj(
        """{"title":"El viaje de Chihiro","original_title":"千と千尋の神隠し","original_language":"ja","origin_country":["JP"],"overview":""}"""
    )
    private val translations = objects(
        """[{"iso_639_1":"es","iso_3166_1":"ES","data":{"title":"El viaje de Chihiro","overview":""}},
            {"iso_639_1":"es","iso_3166_1":"MX","data":{"title":"El viaje de Chihiro","overview":"Chihiro y sus padres (MX)."}},
            {"iso_639_1":"en","iso_3166_1":"GB","data":{"title":"Spirited Away","overview":"Chihiro and her parents (GB)."}},
            {"iso_639_1":"en","iso_3166_1":"US","data":{"title":"Spirited Away","overview":"Chihiro and her parents (US)."}},
            {"iso_639_1":"fr","iso_3166_1":"FR","data":{"title":"","overview":"Chihiro et ses parents."}},
            {"iso_639_1":"ja","iso_3166_1":"JP","data":{"title":"","overview":"千尋と両親。"}}]"""
    )
    private val logos = objects(
        """[{"iso_639_1":"es","iso_3166_1":"MX","file_path":"/es-mx.png"},
            {"iso_639_1":"ja","iso_3166_1":"JP","file_path":"/ja.png"},
            {"iso_639_1":"en","iso_3166_1":"US","file_path":"/en.svg"},
            {"iso_639_1":"en","iso_3166_1":"US","file_path":"/en.png"}]"""
    )
    private val all = { translations }
    private val never: () -> List<JsonObject> = { error("translations not needed") }

    private fun title(locale: String?) = TmdbCatalog.titleIn(MediaType.MOVIE, item, all, locale, "es-ES")
    private fun overview(locale: String?, of: JsonObject = item) = TmdbCatalog.overviewIn(of, all, locale, "es-ES")
    private fun logo(locale: String?, fallback: Boolean = false, from: List<JsonObject> = logos) =
        TmdbCatalog.pickLogo(from, item, MediaType.MOVIE, all, locale, "es-ES", fallback)

    @Test
    fun titleFollowsItsLanguage() {
        assertEquals("El viaje de Chihiro", TmdbCatalog.titleIn(MediaType.MOVIE, item, never, "es-ES", "es-ES"))
        assertEquals("Spirited Away", title("en-US"))
        assertEquals("千と千尋の神隠し", title(null))
        // Untranslated, as TMDB itself does: the original title
        assertEquals("千と千尋の神隠し", title("fr-FR"))
        assertEquals("千と千尋の神隠し", title("de-DE"))
    }

    @Test
    fun synopsisFromAnotherCountryOfTheLanguage() {
        assertEquals("Chihiro y sus padres (MX).", overview("es-ES"))
        assertEquals("Chihiro and her parents (US).", overview("en-US"))
        assertEquals("Chihiro and her parents (GB).", overview("en-GB"))
        assertEquals("Chihiro et ses parents.", overview("fr-FR"))
        assertEquals("", overview("de-DE"))
        // The general language's own synopsis needs no translations
        assertEquals("Ya traducida.", TmdbCatalog.overviewIn(obj("""{"overview":"Ya traducida."}"""), never, "es-ES", "es-ES"))
    }

    @Test
    fun originalSynopsis() {
        assertEquals("千尋と両親。", overview(null))
        // Cantonese titles are "cn" but translated as "zh"
        val cantonese = obj("""{"original_language":"cn","origin_country":["HK"]}""")
        val zh = objects("""[{"iso_639_1":"zh","iso_3166_1":"CN","data":{"overview":"CN"}},{"iso_639_1":"zh","iso_3166_1":"HK","data":{"overview":"HK"}}]""")
        assertEquals("HK", TmdbCatalog.overviewIn(cantonese, { zh }, null, "es-ES"))
        // No language at all: nothing, so the fallback applies
        assertEquals("", TmdbCatalog.overviewIn(obj("""{"original_language":"xx"}"""), all, null, "es-ES"))
    }

    @Test
    fun logoOfAnotherCountryOnlyWithTheSameTitle() {
        // es-MX logo: Mexico's title reads like Spain's
        assertEquals("/es-mx.png", logo("es-ES"))
        val different = objects(
            """[{"iso_639_1":"es","iso_3166_1":"ES","data":{"title":"El motín"}},{"iso_639_1":"es","iso_3166_1":"MX","data":{"title":"Código: Venganza"}}]"""
        )
        val motin = obj("""{"title":"El motín","original_title":"Mutiny","original_language":"en"}""")
        assertNull(TmdbCatalog.pickLogo(logos.take(1), motin, MediaType.MOVIE, { different }, "es-ES", "es-ES", fallback = false))
    }

    @Test
    fun logoInOriginalLanguageOrEnglish() {
        assertEquals("/ja.png", logo(null))
        // English title for an English logo; only PNGs
        assertEquals("/en.png", logo("en-GB"))
        // German: untranslated title, which reads like the original: original-language logo
        assertEquals("/ja.png", logo("de-DE"))
        // Only the fallback brings a logo that reads differently
        val noJapanese = logos.filter { it.toString().contains("\"en\"") }
        assertNull(logo("es-ES", from = noJapanese))
        assertEquals("/en.png", logo("es-ES", fallback = true, from = noJapanese))
        // Original logo wanted, none: the fallback is the English one
        assertNull(logo(null, from = noJapanese))
        assertEquals("/en.png", logo(null, fallback = true, from = noJapanese))
    }
}
