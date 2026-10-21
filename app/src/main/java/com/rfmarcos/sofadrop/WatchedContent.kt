package com.rfmarcos.sofadrop

import android.util.Log

/** What the user has watched, according to Trakt. */
sealed class WatchStatus {
    data object Watched : WatchStatus()
    data class InProgress(val completed: Int, val aired: Int) : WatchStatus()
}

/** Watched movies and shows of the connected Trakt account, loaded once per generation. */
class WatchedContent private constructor(
    private val trakt: TraktClient,
    private val movies: Set<Int>,
    private val shows: Map<Int, Int>,
) {
    private val progress = mutableMapOf<Int, WatchStatus?>()

    fun status(ref: TitleRef): WatchStatus? = when (ref.type) {
        MediaType.MOVIE -> if (ref.tmdbId in movies) WatchStatus.Watched else null
        MediaType.TV -> {
            val traktId = shows[ref.tmdbId]
            if (traktId == null) null
            else progress.getOrPut(ref.tmdbId) {
                val (completed, aired) = runCatching { trakt.showProgress(traktId) }.getOrNull() ?: return@getOrPut null
                when {
                    aired > 0 && completed >= aired -> WatchStatus.Watched
                    completed > 0 -> WatchStatus.InProgress(completed, aired)
                    else -> null
                }
            }
        }
    }

    companion object {
        /** Null when Trakt is not connected or the user wants to see everything as is. */
        fun load(trakt: TraktClient?): WatchedContent? {
            if (trakt == null || !Settings.traktConnected || Settings.watchedMode == Settings.WATCHED_SHOW) return null
            return try {
                WatchedContent(trakt, trakt.watchedMovies(), trakt.watchedShows())
            } catch (e: Exception) {
                Log.w("WatchedContent", "Could not load Trakt history", e)
                null
            }
        }
    }
}
