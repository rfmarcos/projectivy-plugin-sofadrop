package com.rfmarcos.sofadrop

import android.util.Log

/** What the user has watched, according to Trakt and/or Simkl. */
sealed class WatchStatus {
    data object Watched : WatchStatus()
    data class InProgress(val completed: Int, val aired: Int) : WatchStatus()
}

/**
 * Watched titles of the connected trackers, loaded once per generation. With both connected a title
 * counts as watched if either says so, and the furthest episode progress wins.
 */
class WatchedContent private constructor(
    private val trakt: TraktClient?,
    private val traktMovies: Set<Int>,
    private val traktShows: Map<Int, Int>,
    private val simkl: Map<TitleRef, WatchStatus>,
) {
    private val traktProgress = mutableMapOf<Int, WatchStatus?>()

    fun status(ref: TitleRef): WatchStatus? {
        val fromTrakt = traktStatus(ref)
        val fromSimkl = simkl[ref]
        return when {
            fromTrakt is WatchStatus.Watched || fromSimkl is WatchStatus.Watched -> WatchStatus.Watched
            fromTrakt is WatchStatus.InProgress && fromSimkl is WatchStatus.InProgress ->
                if (fromSimkl.completed > fromTrakt.completed) fromSimkl else fromTrakt
            else -> fromTrakt ?: fromSimkl
        }
    }

    private fun traktStatus(ref: TitleRef): WatchStatus? = when (ref.type) {
        MediaType.MOVIE -> if (ref.tmdbId in traktMovies) WatchStatus.Watched else null
        MediaType.TV -> {
            val traktId = traktShows[ref.tmdbId]
            val client = trakt
            if (traktId == null || client == null) null
            else traktProgress.getOrPut(ref.tmdbId) {
                val (completed, aired) = runCatching { client.showProgress(traktId) }.getOrNull() ?: return@getOrPut null
                when {
                    aired > 0 && completed >= aired -> WatchStatus.Watched
                    completed > 0 -> WatchStatus.InProgress(completed, aired)
                    else -> null
                }
            }
        }
    }

    companion object {
        /** Null when no tracker is connected or the user wants to see everything as is. */
        fun load(trakt: TraktClient?, simkl: SimklClient?): WatchedContent? {
            if (!Settings.trackerConnected) return null
            val traktData = trakt?.takeIf { Settings.traktConnected }?.let { client ->
                runCatching { client.watchedMovies() to client.watchedShows() }
                    .onFailure { Log.w("WatchedContent", "Could not load Trakt history", it) }.getOrNull()
            }
            val simklData = if (simkl == null || !Settings.simklConnected) null else runCatching { simkl.watched() }
                .onFailure { Log.w("WatchedContent", "Could not load Simkl history", it) }.getOrNull()
            if (traktData == null && simklData == null) return null
            return WatchedContent(trakt.takeIf { traktData != null }, traktData?.first.orEmpty(), traktData?.second.orEmpty(), simklData.orEmpty())
        }
    }
}
