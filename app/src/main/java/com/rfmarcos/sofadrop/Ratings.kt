package com.rfmarcos.sofadrop

import android.content.Context
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** A platform whose score can be shown on the wallpapers; [key] is MDBList's "source" id. */
enum class RatingSource(val key: String, private val title: Int, private val shortTitle: Int = title) {
    IMDB("imdb", R.string.rating_imdb),
    TOMATOES("tomatoes", R.string.rating_tomatoes, R.string.rating_tomatoes_short),
    POPCORN("popcorn", R.string.rating_popcorn, R.string.rating_popcorn_short),
    METACRITIC("metacritic", R.string.rating_metacritic),
    LETTERBOXD("letterboxd", R.string.rating_letterboxd),
    TMDB("tmdb", R.string.rating_tmdb),
    TRAKT("trakt", R.string.rating_trakt),
    MYANIMELIST("myanimelist", R.string.rating_myanimelist);

    fun title(context: Context) = context.getString(title)
    fun shortTitle(context: Context) = context.getString(shortTitle)

    /** TMDB, IMDb (its public dataset) and Trakt need no account; the others come from MDBList. */
    val needsMdbList: Boolean get() = this !in setOf(TMDB, IMDB, TRAKT)

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** A score in the platform's own scale: IMDb 8.8, Rotten Tomatoes 81 (%), Letterboxd 4.3... */
data class Rating(val source: RatingSource, val value: Double) {

    /** "8.8", "81%", "67", "4.3". */
    val text: String
        get() = when (source) {
            RatingSource.TOMATOES, RatingSource.POPCORN, RatingSource.TRAKT -> "${value.toInt()}%"
            RatingSource.METACRITIC -> value.toInt().toString()
            else -> String.format(Locale.US, "%.1f", value)
        }

    /** Rotten Tomatoes' own rule: 60% or more is fresh (tomato, full popcorn bucket). */
    val positive: Boolean get() = value >= 60

    companion object {
        /** Scores of one MDBList title; missing and zero values are left out. */
        fun parse(item: JsonObject): List<Rating> = item.objects("ratings").mapNotNull { rating ->
            val source = rating.str("source")?.let(RatingSource::of) ?: return@mapNotNull null
            rating.double("value")?.takeIf { it > 0 }?.let { Rating(source, it) }
        }

        /**
         * The scores to draw, in the user's order, among those the title has (the renderer draws as
         * many as fit). [found] comes from MDBList or, without it, from IMDb's dataset and Trakt.
         * TMDB's score comes from TMDB ([tmdbVote]), and it stands in when none of the chosen ones is
         * there (MDBList unreachable, a title nobody has rated yet).
         */
        fun pick(selected: List<RatingSource>, found: List<Rating>, tmdbVote: Double): List<Rating> {
            val tmdb = tmdbVote.takeIf { it > 0 }?.let { Rating(RatingSource.TMDB, it) }
            val picked = selected.mapNotNull { source ->
                if (source == RatingSource.TMDB) tmdb else found.firstOrNull { it.source == source }
            }
            return picked.ifEmpty { listOfNotNull(tmdb.takeIf { selected.isNotEmpty() }) }
        }
    }
}
