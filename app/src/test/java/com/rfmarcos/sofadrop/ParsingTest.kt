package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response samples taken from the official Trakt and MDBList API docs. */
class ParsingTest {

    private fun array(text: String) = Json.parseToJsonElement(text) as JsonArray
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun traktWrappedItems() {
        // /movies/trending, /movies/anticipated, /movies/boxoffice, /sync/watchlist
        val items = array(
            """[{"watchers":21,"movie":{"title":"TRON: Legacy","year":2010,"ids":{"trakt":1,"slug":"tron-legacy-2010","imdb":"tt1104001","tmdb":20526}}},
                {"list_count":5362,"movie":{"title":"Mockingjay 2","ids":{"trakt":89543,"tmdb":131634}}},
                {"watchers":3,"movie":{"title":"No TMDB","ids":{"trakt":5}}}]"""
        )
        assertEquals(
            listOf(TitleRef(MediaType.MOVIE, 20526), TitleRef(MediaType.MOVIE, 131634)),
            TraktClient.parseTitles(items, MediaType.MOVIE),
        )
    }

    @Test
    fun traktPlainItems() {
        // /movies/popular, /recommendations/movies, and shows wrapped in "show"
        val plain = array("""[{"title":"Blackfish","year":2013,"ids":{"trakt":58,"tmdb":158999}}]""")
        assertEquals(listOf(TitleRef(MediaType.MOVIE, 158999)), TraktClient.parseTitles(plain, MediaType.MOVIE))
        val shows = array("""[{"watchers":5,"show":{"title":"Breaking Bad","ids":{"trakt":1,"tmdb":1396}}}]""")
        assertEquals(listOf(TitleRef(MediaType.TV, 1396)), TraktClient.parseTitles(shows, MediaType.TV))
    }

    @Test
    fun mdbListItems() {
        val body = obj(
            """{"movies":[
                 {"id":917496,"title":"Beetlejuice Beetlejuice","imdb_id":"tt2049403","ids":{"imdb":"tt2049403","tmdb":917496},"mediatype":"movie"},
                 {"id":646097,"title":"Rebel Ridge","imdb_id":"tt11301886","mediatype":"movie"}],
                "shows":[
                 {"id":1234,"title":"Show with tmdb","ids":{"tmdb":1396,"imdb":"tt0903747"},"mediatype":"show"},
                 {"id":999,"title":"Show without tmdb","imdb_id":"tt0944947","mediatype":"show"}]}"""
        )
        val items = MdbListClient.parseItems(body)
        assertEquals(TitleRef(MediaType.MOVIE, 917496), items[0].ref)
        assertEquals(TitleRef(MediaType.MOVIE, 646097), items[1].ref) // top-level id is the TMDB id for movies
        assertEquals(TitleRef(MediaType.TV, 1396), items[2].ref)
        assertNull(items[3].ref) // shows without ids.tmdb are resolved through IMDb
        assertEquals("tt0944947", items[3].imdbId)
    }

    @Test
    fun providerVariantsShareBaseName() {
        val base = TmdbCatalog.providerBaseName("Paramount Plus")
        listOf("Paramount Plus Premium", "Paramount Plus Essential", "Paramount+ Amazon Channel", "Paramount Plus Apple TV Channel")
            .forEach { assertEquals(base, TmdbCatalog.providerBaseName(it)) }
        assertEquals("netflix", TmdbCatalog.providerBaseName("Netflix Standard with Ads"))
        assertEquals("apple tv", TmdbCatalog.providerBaseName("Apple TV Amazon Channel"))
    }

    @Test
    fun titleSimilarity() {
        assertEquals(1.0, TmdbCatalog.similarity("Silo", "SILO"), 0.0)
        assertTrue(TmdbCatalog.similarity("La Odisea", "The Odyssey") < 0.9)
    }

    @Test
    fun mdbListPublicPath() {
        assertEquals("snoak/imdb-top-250-movies", MdbListClient.publicPath(" snoak/imdb-top-250-movies "))
        assertEquals("snoak/imdb-top-250-movies", MdbListClient.publicPath("https://mdblist.com/lists/snoak/imdb-top-250-movies/"))
        assertNull(MdbListClient.publicPath("snoak"))
        assertNull(MdbListClient.publicPath("https://mdblist.com/lists/snoak/list/extra"))
        assertNull(MdbListClient.publicPath("user/list?x=1"))
        assertEquals("mdblist:snoak/x", MdbListRef(0, "X", "snoak/x").key)
        assertEquals("mdblist:42", MdbListRef(42, "Mine").key)
    }

    @Test
    fun simklLibrary() {
        // Shape from the Simkl OpenAPI example: shows carry a TMDB id, this anime does not
        val body = Json.parseToJsonElement("""
            {"movies": [{"status": "plantowatch", "added_to_watchlist_at": "2026-01-02T00:00:00Z",
                         "movie": {"title": "Dune", "ids": {"simkl": 1, "tmdb": "438631"}}}],
             "shows": [{"status": "watching", "watched_episodes_count": 3, "total_episodes_count": 10, "not_aired_episodes_count": 2,
                        "show": {"title": "Charmed", "ids": {"simkl": 297, "tmdb": "1981"}}}],
             "anime": [{"status": "completed", "anime_type": "tv", "show": {"title": "Cowboy Bebop", "ids": {"simkl": 37089, "mal": "1"}}}]}
        """).let { it as JsonObject }
        val items = SimklClient.parseLibrary(body)
        assertEquals(listOf(TitleRef(MediaType.MOVIE, 438631), TitleRef(MediaType.TV, 1981)), items.map { it.ref })
        assertEquals(3, items[1].watched)
        assertEquals(8, items[1].total) // aired episodes only
    }
}
