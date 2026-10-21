package com.rfmarcos.sofadrop

enum class SourceProvider { TMDB, TRAKT, MDBLIST }

/** A list the user can pick as a source of titles. */
data class ListSource(
    val key: String,
    val provider: SourceProvider,
    val labelRes: Int = 0,
    val label: String? = null,
    val needsTraktAccount: Boolean = false,
    val needsTmdbAccount: Boolean = false,
) {
    /** Lists built automatically by a service (trending, popular…), as opposed to the user's own lists. */
    val automatic: Boolean
        get() = provider != SourceProvider.MDBLIST && !needsTraktAccount && !needsTmdbAccount
}

object Sources {
    val TMDB = listOf(
        ListSource("tmdb:trending_movies", SourceProvider.TMDB, R.string.list_tmdb_trending_movies),
        ListSource("tmdb:trending_tv", SourceProvider.TMDB, R.string.list_tmdb_trending_tv),
        ListSource("tmdb:popular_movies", SourceProvider.TMDB, R.string.list_tmdb_popular_movies),
        ListSource("tmdb:popular_tv", SourceProvider.TMDB, R.string.list_tmdb_popular_tv),
        ListSource("tmdb:now_playing", SourceProvider.TMDB, R.string.list_tmdb_now_playing),
        ListSource("tmdb:upcoming", SourceProvider.TMDB, R.string.list_tmdb_upcoming),
        ListSource("tmdb:top_rated_movies", SourceProvider.TMDB, R.string.list_tmdb_top_rated_movies),
        ListSource("tmdb:top_rated_tv", SourceProvider.TMDB, R.string.list_tmdb_top_rated_tv),
        ListSource("tmdb:on_the_air", SourceProvider.TMDB, R.string.list_tmdb_on_the_air),
        ListSource("tmdb:watchlist_movies", SourceProvider.TMDB, R.string.list_tmdb_watchlist_movies, needsTmdbAccount = true),
        ListSource("tmdb:watchlist_tv", SourceProvider.TMDB, R.string.list_tmdb_watchlist_tv, needsTmdbAccount = true),
        ListSource("tmdb:favorite_movies", SourceProvider.TMDB, R.string.list_tmdb_favorite_movies, needsTmdbAccount = true),
        ListSource("tmdb:favorite_tv", SourceProvider.TMDB, R.string.list_tmdb_favorite_tv, needsTmdbAccount = true),
    )

    val TRAKT = listOf(
        ListSource("trakt:trending_movies", SourceProvider.TRAKT, R.string.list_trakt_trending_movies),
        ListSource("trakt:trending_shows", SourceProvider.TRAKT, R.string.list_trakt_trending_shows),
        ListSource("trakt:popular_movies", SourceProvider.TRAKT, R.string.list_trakt_popular_movies),
        ListSource("trakt:popular_shows", SourceProvider.TRAKT, R.string.list_trakt_popular_shows),
        ListSource("trakt:anticipated_movies", SourceProvider.TRAKT, R.string.list_trakt_anticipated_movies),
        ListSource("trakt:anticipated_shows", SourceProvider.TRAKT, R.string.list_trakt_anticipated_shows),
        ListSource("trakt:boxoffice", SourceProvider.TRAKT, R.string.list_trakt_boxoffice),
        ListSource("trakt:recommended_movies", SourceProvider.TRAKT, R.string.list_trakt_recommended_movies, needsTraktAccount = true),
        ListSource("trakt:recommended_shows", SourceProvider.TRAKT, R.string.list_trakt_recommended_shows, needsTraktAccount = true),
        ListSource("trakt:watchlist_movies", SourceProvider.TRAKT, R.string.list_trakt_watchlist_movies, needsTraktAccount = true),
        ListSource("trakt:watchlist_shows", SourceProvider.TRAKT, R.string.list_trakt_watchlist_shows, needsTraktAccount = true),
    )

    fun label(context: android.content.Context, source: ListSource): String =
        source.label?.let { context.getString(R.string.list_mdblist, it) } ?: context.getString(source.labelRes)

    /** Lists available right now, depending on the configured services. */
    fun available(): List<ListSource> {
        val lists = TMDB.filter { !it.needsTmdbAccount || Settings.tmdbConnected }.toMutableList()
        if (Settings.traktConfigured) lists += TRAKT.filter { !it.needsTraktAccount || Settings.traktConnected }
        // Public lists need no account; the account's own lists only while it is connected
        lists += Settings.mdbLists.filter { it.path.isNotEmpty() || Settings.mdbListAvailable }
            .map { ListSource(it.key, SourceProvider.MDBLIST, label = it.name) }
        return lists
    }

    /** Selected lists that can actually be used (e.g. not a Trakt list after disconnecting). */
    fun selected(): List<ListSource> {
        val chosen = Settings.lists
        return available().filter { it.key in chosen }.ifEmpty { TMDB.take(2) }
    }
}
