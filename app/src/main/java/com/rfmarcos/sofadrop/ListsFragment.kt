package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Where titles come from: how many per list and in what order, one screen per provider, and the explorer. */
class ListsFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_lists_title),
        getString(R.string.lists_hub_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        // How lists are read: they apply to every provider below
        actions += choice(ID_COUNT, R.drawable.ic_act_count, R.string.setting_count_title, countDescription(context),
            Settings.COUNT_OPTIONS.map { countLabel(context, it) }, selected = countLabel(context, Settings.titlesPerList))
        actions += choice(ID_ORDER, R.drawable.ic_act_shuffle, R.string.setting_selection_title, orderLabel(Settings.selectionMode),
            ORDER_MODES.map(::orderLabel))
        actions += choice(ID_MIX, R.drawable.ic_act_shuffle, R.string.setting_mix_title, mixLabel(Settings.mixLists),
            listOf(mixLabel(true), mixLabel(false)))
        actions += provider(ID_TMDB, R.drawable.badge_tmdb, "TMDB", ProviderListsFragment.summary(context, SourceProvider.TMDB))
        actions += provider(ID_TRAKT, R.drawable.badge_trakt, getString(R.string.setting_trakt_title),
            ProviderListsFragment.summary(context, SourceProvider.TRAKT))
        actions += provider(ID_SIMKL, R.drawable.badge_simkl, getString(R.string.setting_simkl_title),
            ProviderListsFragment.summary(context, SourceProvider.SIMKL))
        actions += provider(ID_MDBLIST, R.drawable.badge_mdblist, getString(R.string.setting_mdblist_title), MdbListFragment.summary(context))
        actions += action(ID_EXPLORE, R.drawable.ic_act_filter, getString(R.string.setting_explore_title),
            Filters.summary(context)).hasNext(true).build()
    }

    /** Provider row with the provider's own round badge instead of a generic icon in the grey circle. */
    private fun provider(id: Long, badge: Int, title: String, description: String) =
        GuidedAction.Builder(requireContext()).id(id).icon(AppCompatResources.getDrawable(requireContext(), badge))
            .title(title).description(description).hasNext(true).build()

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        updateDescription(ID_COUNT, countDescription(context))
        updateDescription(ID_TMDB, ProviderListsFragment.summary(context, SourceProvider.TMDB))
        updateDescription(ID_TRAKT, ProviderListsFragment.summary(context, SourceProvider.TRAKT))
        updateDescription(ID_SIMKL, ProviderListsFragment.summary(context, SourceProvider.SIMKL))
        updateDescription(ID_MDBLIST, MdbListFragment.summary(context))
        updateDescription(ID_EXPLORE, Filters.summary(context))
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_TMDB -> add(parentFragmentManager, ProviderListsFragment.newInstance(SourceProvider.TMDB))
            ID_TRAKT -> add(parentFragmentManager, ProviderListsFragment.newInstance(SourceProvider.TRAKT))
            ID_SIMKL -> add(parentFragmentManager, ProviderListsFragment.newInstance(SourceProvider.SIMKL))
            ID_MDBLIST -> add(parentFragmentManager, MdbListFragment())
            ID_EXPLORE -> add(parentFragmentManager, ListExplorerFragment())
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_COUNT -> {
                Settings.titlesPerList = Settings.COUNT_OPTIONS[index]
                updateDescription(ID_COUNT, countDescription(requireContext()))
            }
            ID_ORDER -> {
                Settings.selectionMode = ORDER_MODES[index]
                updateDescription(ID_ORDER, orderLabel(Settings.selectionMode))
            }
            ID_MIX -> {
                Settings.mixLists = index == 0
                updateDescription(ID_MIX, mixLabel(Settings.mixLists))
            }
        }
        return true
    }

    private fun mixLabel(mixed: Boolean) = getString(if (mixed) R.string.mix_on else R.string.mix_off)

    private fun orderLabel(mode: String) =
        getString(if (mode == Settings.SELECTION_RANDOM) R.string.selection_random else R.string.selection_order)

    companion object {
        private const val ID_TMDB = 1L
        private const val ID_TRAKT = 2L
        private const val ID_MDBLIST = 3L
        private const val ID_EXPLORE = 4L
        private const val ID_SIMKL = 5L
        private const val ID_COUNT = 6L
        private const val ID_ORDER = 7L
        private const val ID_MIX = 8L
        private val ORDER_MODES = listOf(Settings.SELECTION_ORDER, Settings.SELECTION_RANDOM)

        fun countLabel(context: Context, count: Int) =
            if (count >= Settings.COUNT_ALL) context.getString(R.string.titles_all, Settings.COUNT_ALL) else count.toString()

        /** "10 · about 80 wallpapers" (the live total of the selected lists). */
        fun countDescription(context: Context): String {
            val count = Settings.titlesPerList
            val total = Sources.selected().size * count
            return if (count >= Settings.COUNT_ALL) context.getString(R.string.titles_total_all, countLabel(context, count), total)
            else context.getString(R.string.titles_total, countLabel(context, count), total)
        }

        /** "8 lists · TMDB 2 · Trakt 4 · MDBList 2" for the main screen. */
        fun summary(context: Context): String {
            val selected = Sources.selected()
            if (selected.isEmpty()) return context.getString(R.string.error_no_lists)
            val perProvider = selected.groupingBy { it.provider }.eachCount().entries
                .joinToString(" · ") { (provider, count) -> "${providerName(context, provider)} $count" }
            return context.getString(R.string.lists_summary, listsCount(context, selected.size), perProvider)
        }

        fun listsCount(context: Context, count: Int): String =
            context.resources.getQuantityString(R.plurals.lists_count, count, count)

        fun providerName(context: Context, provider: SourceProvider) = when (provider) {
            SourceProvider.TMDB -> "TMDB"
            SourceProvider.TRAKT -> context.getString(R.string.setting_trakt_title)
            SourceProvider.SIMKL -> context.getString(R.string.setting_simkl_title)
            SourceProvider.MDBLIST -> context.getString(R.string.setting_mdblist_title)
        }
    }
}
