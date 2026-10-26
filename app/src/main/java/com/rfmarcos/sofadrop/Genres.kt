package com.rfmarcos.sofadrop

import kotlinx.serialization.json.JsonObject
import java.text.Collator
import java.util.Locale

/**
 * One genre as the filter screen shows it. TMDB uses separate ids for movies and TV shows, even when
 * the name is identical (e.g. "Comedy"); a genre only on one side (Kids, War) has the other id null.
 */
data class GenreOption(val name: String, val movieId: Int?, val tvId: Int?) {
    /** The id(s) this checkbox adds to or removes from a [GenreFilter]. */
    val ids: Set<Int> get() = setOfNotNull(movieId, tvId)
}

object Genres {

    /**
     * Movie and TV genres from TMDB, in the configured language, merged by name into one list so the
     * screen shows one checkbox per genre instead of two near-duplicates ("Comedy" from movies and
     * "Comedy" from TV). Sorted alphabetically; null on a network failure (shown as an error, not an
     * empty list, so it isn't mistaken for "TMDB has no genres").
     */
    fun options(client: TmdbClient, language: String): List<GenreOption>? {
        val movies = client.get("genre/movie/list?language=$language")?.objects("genres") ?: return null
        val shows = client.get("genre/tv/list?language=$language")?.objects("genres") ?: return null
        val movieIds = linkedMapOf<String, Int>()
        val tvIds = linkedMapOf<String, Int>()
        // Named like the wallpapers name them (TMDB leaves some untranslated or, in Norwegian, blank)
        fun name(genre: JsonObject) = Languages.genreName(language, genre.int("id"), genre.str("name"))
        movies.forEach { g -> val name = name(g) ?: return@forEach; g.int("id")?.let { movieIds[name] = it } }
        shows.forEach { g -> val name = name(g) ?: return@forEach; g.int("id")?.let { tvIds[name] = it } }
        return (movieIds.keys + tvIds.keys).distinct().sortedWith(Collator.getInstance(Locale.forLanguageTag(language)))
            .map { name -> GenreOption(name, movieIds[name], tvIds[name]) }
    }
}
