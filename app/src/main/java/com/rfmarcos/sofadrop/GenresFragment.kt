package com.rfmarcos.sofadrop

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Genre filter: the default one (no list key) or one list's own. Checked genres are the only ones used
 * or, with "All but the checked genres", the ones skipped; nothing checked means no filter. A list
 * starts on "Default (…)", which shows the default's genres without letting them be changed.
 */
class GenresFragment : BaseSettingsFragment() {

    private val listKey get() = requireArguments().getString(ARG_KEY)
    private val source get() = listKey?.let { key -> Sources.available().firstOrNull { it.key == key } }
    private var options: List<GenreOption> = emptyList()

    /** This list's own filter, or null when it uses the default (always set for the default filter). */
    private var own: GenreFilter?
        get() = if (listKey == null) Settings.genreFilter else Settings.listFilters[listKey]?.genres
        set(value) {
            val key = listKey
            if (key == null) Settings.genreFilter = value ?: GenreFilter()
            else Settings.listFilters = Settings.listFilters + (key to (Settings.listFilters[key] ?: ListFilters()).copy(genres = value))
        }

    private val default get() = source?.let { Settings.defaultGenres(it) } ?: GenreFilter()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.genres_title),
        getString(R.string.genres_description),
        source?.let { Sources.label(requireContext(), it) } ?: getString(R.string.setting_explore_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_LOADING, R.drawable.ic_act_movie, getString(R.string.explore_loading), null).focusable(false).build()
        val client = TmdbClient(Settings.token, requireContext().cacheDir)
        val language = Settings.language
        background({ Genres.options(client, language) }) { result ->
            val fetched = result.getOrNull()
            if (fetched.isNullOrEmpty()) {
                setActions(listOf(action(ID_ERROR, R.drawable.ic_act_info, getString(R.string.genres_load_failed), null).focusable(false).build()))
                return@background
            }
            options = fetched
            setActions(rows())
        }
    }

    /** "Default (…)" (lists only), "Only the checked genres", "All but the checked genres". */
    private fun modeLabels(): List<String> = listOfNotNull(
        source?.let { getString(R.string.list_filter_default, Filters.genresLabel(requireContext(), default)) },
        getString(R.string.genres_mode_only),
        getString(R.string.genres_mode_except),
    )

    private fun rows(): List<GuidedAction> {
        val labels = modeLabels()
        val custom = own
        val shown = custom ?: default
        val mode = labels[labels.size - 2 + (if (shown.exclude) 1 else 0)].takeIf { custom != null } ?: labels[0]
        return listOf(choice(ID_MODE, R.drawable.ic_act_filter, R.string.genres_mode_title, mode, labels)) +
            options.mapIndexed { index, genre ->
                checkbox(ID_GENRE_BASE + index, genre.name, null, genre.ids.any { it in shown.ids }).enabled(custom != null).build()
            }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val genre = options.getOrNull((action.id - ID_GENRE_BASE).toInt()) ?: return
        val current = own ?: return
        own = current.copy(ids = if (action.isChecked) current.ids + genre.ids else current.ids - genre.ids)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE != ID_MODE) return true
        val labels = modeLabels()
        val index = (action.id % SUB_ID_BASE).toInt()
        // A list's own filter starts from the default's genres
        own = if (labels.size == 3 && index == 0) null else (own ?: default).copy(exclude = index == labels.size - 1)
        refreshActions(rows()) { newInstance(listKey) }
        return true
    }

    companion object {
        private const val ARG_KEY = "list_key"
        private const val ID_LOADING = -1L
        private const val ID_ERROR = -2L
        private const val ID_MODE = 1L
        private const val ID_GENRE_BASE = 100L

        /** The default filter with a null [listKey], else that list's own. */
        fun newInstance(listKey: String?) = GenresFragment().apply { arguments = Bundle().apply { putString(ARG_KEY, listKey) } }
    }
}
