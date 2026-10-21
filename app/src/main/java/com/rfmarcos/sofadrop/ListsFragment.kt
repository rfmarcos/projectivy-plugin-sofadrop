package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Where titles come from: one screen per provider (lists + its account) and the list explorer. */
class ListsFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_lists_title),
        getString(R.string.lists_hub_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        actions += action(ID_TMDB, R.drawable.ic_act_movie, "TMDB", ProviderListsFragment.summary(context, SourceProvider.TMDB))
            .hasNext(true).build()
        actions += action(ID_TRAKT, R.drawable.ic_act_trakt, getString(R.string.setting_trakt_title),
            ProviderListsFragment.summary(context, SourceProvider.TRAKT)).hasNext(true).build()
        actions += action(ID_MDBLIST, R.drawable.ic_act_list, getString(R.string.setting_mdblist_title), MdbListFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_EXPLORE, R.drawable.ic_act_search, getString(R.string.setting_explore_title),
            getString(R.string.setting_explore_desc)).hasNext(true).build()
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        updateDescription(ID_TMDB, ProviderListsFragment.summary(context, SourceProvider.TMDB))
        updateDescription(ID_TRAKT, ProviderListsFragment.summary(context, SourceProvider.TRAKT))
        updateDescription(ID_MDBLIST, MdbListFragment.summary(context))
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_TMDB -> add(parentFragmentManager, ProviderListsFragment.newInstance(SourceProvider.TMDB))
            ID_TRAKT -> add(parentFragmentManager, ProviderListsFragment.newInstance(SourceProvider.TRAKT))
            ID_MDBLIST -> add(parentFragmentManager, MdbListFragment())
            ID_EXPLORE -> add(parentFragmentManager, ListExplorerFragment())
        }
    }

    companion object {
        private const val ID_TMDB = 1L
        private const val ID_TRAKT = 2L
        private const val ID_MDBLIST = 3L
        private const val ID_EXPLORE = 4L

        /** "8 lists · TMDB 2 · Trakt 4 · MDBList 2" for the main screen. */
        fun summary(context: Context): String {
            val selected = Sources.selected()
            val perProvider = selected.groupingBy { it.provider }.eachCount().entries
                .joinToString(" · ") { (provider, count) -> "${providerName(context, provider)} $count" }
            return context.getString(R.string.lists_summary, listsCount(context, selected.size), perProvider)
        }

        fun listsCount(context: Context, count: Int): String =
            context.resources.getQuantityString(R.plurals.lists_count, count, count)

        fun providerName(context: Context, provider: SourceProvider) = when (provider) {
            SourceProvider.TMDB -> "TMDB"
            SourceProvider.TRAKT -> context.getString(R.string.setting_trakt_title)
            SourceProvider.MDBLIST -> context.getString(R.string.setting_mdblist_title)
        }
    }
}
