package com.rfmarcos.sofadrop

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Default genre filter, applied to every list. By default only titles with at least one checked
 * genre are used; "Except these genres" flips that to hide titles with a checked genre instead.
 * Nothing checked means no genre filter either way -- see [Settings.allowedGenres].
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

    private fun modeLabels() = listOf(getString(R.string.genres_mode_only), getString(R.string.genres_mode_except))

    private fun modeRow(): GuidedAction {
        val labels = modeLabels()
        return choice(ID_MODE, R.drawable.ic_act_filter, R.string.genres_mode_title, labels[if (Settings.excludeSelectedGenres) 1 else 0], labels)
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
            setActions(listOf(modeRow()) + options.mapIndexed { index, genre ->
                checkbox(ID_GENRE_BASE + index, genre.name, null, genre.ids.any { it in selected }).build()
            })
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val genre = options.getOrNull((action.id - ID_GENRE_BASE).toInt()) ?: return
        Settings.allowedGenres = if (action.isChecked) Settings.allowedGenres + genre.ids else Settings.allowedGenres - genre.ids
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE != ID_MODE) return true
        Settings.excludeSelectedGenres = (action.id % SUB_ID_BASE).toInt() == 1
        updateDescription(ID_MODE, modeLabels()[if (Settings.excludeSelectedGenres) 1 else 0])
        return true
    }

    companion object {
        private const val ID_LOADING = -1L
        private const val ID_ERROR = -2L
        private const val ID_MODE = 1L
        private const val ID_GENRE_BASE = 100L
    }
}
