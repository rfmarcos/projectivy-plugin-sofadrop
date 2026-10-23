package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Scores of an MDBList title (sample trimmed from POST /tmdb/movie) and which ones get drawn. */
class RatingsTest {

    private val fightClub = Json.parseToJsonElement(
        """{"title":"Fight Club","ids":{"imdb":"tt0137523","tmdb":550},"ratings":[
            {"source":"imdb","value":8.8,"score":88,"votes":2675558},
            {"source":"metacritic","value":67,"score":67,"votes":36},
            {"source":"metacriticuser","value":8.9,"score":89.0,"votes":2248},
            {"source":"tomatoes","value":81,"score":81,"votes":251,"fresh":1},
            {"source":"popcorn","value":96,"score":96,"votes":73863},
            {"source":"letterboxd","value":4.3,"score":86,"votes":6207193},
            {"source":"rogerebert","value":2.0,"score":null,"votes":null},
            {"source":"myanimelist","value":null,"score":null,"votes":null},
            {"source":"trakt","value":0,"score":0,"votes":0}]}"""
    ) as JsonObject

    private val ratings = Rating.parse(fightClub)

    @Test
    fun parseKeepsKnownSourcesWithAValue() {
        assertEquals(
            listOf(
                Rating(RatingSource.IMDB, 8.8), Rating(RatingSource.METACRITIC, 67.0), Rating(RatingSource.TOMATOES, 81.0),
                Rating(RatingSource.POPCORN, 96.0), Rating(RatingSource.LETTERBOXD, 4.3),
            ),
            ratings,
        )
        assertEquals(listOf("8.8", "67", "81%", "96%", "4.3"), ratings.map { it.text })
    }

    @Test
    fun pickFollowsTheUserOrder() {
        val selected = listOf(RatingSource.LETTERBOXD, RatingSource.TMDB, RatingSource.TRAKT, RatingSource.IMDB, RatingSource.POPCORN)
        // Trakt is missing, so IMDb moves up (how many fit is the renderer's call)
        assertEquals(
            listOf(Rating(RatingSource.LETTERBOXD, 4.3), Rating(RatingSource.TMDB, 8.4), Rating(RatingSource.IMDB, 8.8), Rating(RatingSource.POPCORN, 96.0)),
            Rating.pick(selected, ratings, tmdbVote = 8.4),
        )
    }

    @Test
    fun tmdbStandsInWhenNoChosenScoreIsThere() {
        val selected = listOf(RatingSource.IMDB, RatingSource.TOMATOES)
        assertEquals(listOf(Rating(RatingSource.TMDB, 8.4)), Rating.pick(selected, emptyList(), tmdbVote = 8.4))
        assertEquals(emptyList<Rating>(), Rating.pick(selected, emptyList(), tmdbVote = 0.0))
        // Nothing chosen: no ratings at all
        assertEquals(emptyList<Rating>(), Rating.pick(emptyList(), ratings, tmdbVote = 8.4))
    }

    @Test
    fun rottenTomatoesFreshFromSixty() {
        assertEquals(listOf(true, false), listOf(60.0, 59.0).map { Rating(RatingSource.TOMATOES, it).positive })
    }
}
