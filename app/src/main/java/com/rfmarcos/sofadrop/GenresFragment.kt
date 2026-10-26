package com.rfmarcos.sofadrop

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Default genre filter, applied to every list: only titles with at least one checked genre are used.
 * Nothing checked means no genre filter (every genre allowed) — see [Settings.allowedGenres].
 */
class GenresFragment : BaseSettingsFragment() {

    private var options: List<GenreOption> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_genres_title),
        getString(R.string.genres_description),
        getString(R.string.setting_explore_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_LOADING, R.drawable.ic_act_movie, getString(R.string.explore_loading), null).focusable(false).build()
        load()
    }

    private fun load() {
        val client = TmdbClient(Settings.token, requireContext().cacheDir)
        val language = Settings.language
        background({ Genres.options(client, language) }) { result ->
            val fetched = result.getOrNull()
            if (fetched.isNullOrEmpty()) {
                setActions(listOf(action(ID_ERROR, R.drawable.ic_act_info, getString(R.string.genres_load_failed), null).focusable(false).build()))
                return@background
            }
            options = fetched
            val selected = Settings.allowedGenres
            setActions(options.mapIndexed { index, genre ->
                checkbox(ID_GENRE_BASE + index, genre.name, null, genre.ids.any { it in selected }).build()
            })
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val genre = options.getOrNull((action.id - ID_GENRE_BASE).toInt()) ?: return
        Settings.allowedGenres = if (action.isChecked) Settings.allowedGenres + genre.ids else Settings.allowedGenres - genre.ids
    }

    companion object {
        private const val ID_LOADING = -1L
        private const val ID_ERROR = -2L
        private const val ID_GENRE_BASE = 100L
    }
}
