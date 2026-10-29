package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trakt users' lists: references typed by the user and responses taken from the API (October 2026). */
class TraktListsTest {

    @Test
    fun listReferences() {
        val path = "justin/imdb-top-rated-movies"
        assertEquals(path, TraktClient.listPath(" justin/imdb-top-rated-movies "))
        assertEquals(path, TraktClient.listPath("https://trakt.tv/users/justin/lists/imdb-top-rated-movies"))
        assertEquals(path, TraktClient.listPath("https://trakt.tv/users/justin/lists/imdb-top-rated-movies/"))
        assertEquals(path, TraktClient.listPath("https://trakt.tv/users/justin/lists/imdb-top-rated-movies?sort=rank,asc"))
        assertEquals(path, TraktClient.listPath("trakt.tv/users/justin/lists/imdb-top-rated-movies#top"))
        // The share links of the new web app
        assertEquals(path, TraktClient.listPath("https://app.trakt.tv/users/justin/lists/imdb-top-rated-movies"))
        // Lists can also be named by their numeric id, and users by slugs with dots or underscores
        assertEquals("fidel.cb/2142753", TraktClient.listPath("https://trakt.tv/users/fidel.cb/lists/2142753"))
        assertNull(TraktClient.listPath("justin"))
        assertNull(TraktClient.listPath("justin/lists/extra"))
        assertNull(TraktClient.listPath("user/list?x=1"))
        assertNull(TraktClient.listPath("https://trakt.tv/users/justin"))
        assertNull(TraktClient.listPath("https://trakt.tv/movies/tron"))
        assertEquals("trakt:list:justin/imdb-top-rated-movies", TraktListRef(path, "IMDB: Top Rated Movies").key)
    }

    @Test
    fun listItems() {
        // users/{user}/lists/{slug}/items/movie,show: a season and a movie without TMDB id are skipped
        val items = Json.parseToJsonElement(
            """[{"type":"movie","movie":{"ids":{"imdb":"tt1966604","plex":{"guid":"5d7768d296b655001fdc27c7","slug":"the-imposter-2012"},"slug":"the-imposter-2012","tmdb":84287,"trakt":63169},"year":2012,"title":"The Imposter"},"rank":3,"id":1556763864,"listed_at":"2026-10-26T17:09:33.000Z","notes":null,"my_rating":null},
                {"type":"show","show":{"ids":{"slug":"making-a-murderer","tmdb":64439,"trakt":98380},"year":2015,"title":"Making a Murderer"},"rank":4},
                {"type":"season","season":{"number":1,"ids":{"tmdb":3624}},"show":{"ids":{"tmdb":1399}},"rank":5},
                {"type":"movie","movie":{"ids":{"slug":"no-tmdb","trakt":5},"title":"No TMDB"},"rank":6}]"""
        ) as JsonArray
        assertEquals(
            listOf(TitleRef(MediaType.MOVIE, 84287), TitleRef(MediaType.TV, 64439)),
            TraktClient.parseListItems(items),
        )
    }

    @Test
    fun listDetails() {
        // users/{user}/lists/{slug}: the path uses the slugs, which can differ from what the user typed
        val list = Json.parseToJsonElement(
            """{"name":"A24","description":"","privacy":"public","share_link":"https://app.trakt.tv/users/fidel-cb/lists/a24",
                "type":"personal","item_count":216,"ids":{"slug":"a24","trakt":5171116},
                "user":{"ids":{"slug":"fidel-cb","trakt":879337},"username":"Fidel.cb","private":false}}"""
        ) as JsonObject
        assertEquals(TraktListRef("fidel-cb/a24", "A24"), TraktClient.parseList(list))
        // Trakt's official lists have no user slug: they can't be read as a user's list
        val official = Json.parseToJsonElement(
            """{"name":"Star Wars Collection","ids":{"slug":"star-wars-collection","trakt":6},"user":{"ids":{"slug":null},"username":"Trakt"}}"""
        ) as JsonObject
        assertNull(TraktClient.parseList(official))
        assertTrue(TraktClient.SUGGESTED.all { TraktClient.listPath(it.path) == it.path })
    }

    @Test
    fun userListsAreNotAutomatic() {
        // Same default filters as MDBList's lists (no age, global hits or genre filter), and named on the wallpaper
        val source = ListSource("trakt:list:fidel-cb/a24", SourceProvider.TRAKT, label = "A24")
        assertFalse(source.automatic)
        assertFalse(ListSource("mdblist:a/b", SourceProvider.MDBLIST, label = "B").automatic)
        assertTrue(Sources.TRAKT.first { it.key == "trakt:trending_movies" }.automatic)
        assertEquals("A24", Languages.sourceLabel("es-ES", source))
    }

    @Test
    fun unavailableListsSayWhich() {
        val error = ListUnavailableException("A24", 403)
        assertEquals("A24", error.name)
        assertEquals("ListUnavailableException: Trakt list \"A24\" was deleted or is private (HTTP 403)", GenerationLog.describe(error))
    }
}
