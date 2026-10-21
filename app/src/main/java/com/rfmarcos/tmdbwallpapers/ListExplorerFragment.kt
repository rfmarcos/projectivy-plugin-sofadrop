package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Pick a list to see what it contains. */
class ListExplorerFragment : BaseSettingsFragment() {

    private var lists: List<ListSource> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_explore_title),
        getString(R.string.explore_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val selected = Settings.lists
        // Selected lists first
        lists = Sources.available().sortedByDescending { it.key in selected }
        lists.forEachIndexed { index, source ->
            actions += action(
                index.toLong(),
                if (source.key in selected) R.drawable.ic_act_check else R.drawable.ic_act_list,
                Sources.label(requireContext(), source),
                getString(if (source.key in selected) R.string.explore_selected else R.string.explore_not_selected),
            ).hasNext(true).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val source = lists.getOrNull(action.id.toInt()) ?: return
        add(parentFragmentManager, ListContentsFragment.newInstance(source.key))
    }
}

/** Titles of one list, in order, with what happens to each one. */
class ListContentsFragment : BaseSettingsFragment() {

    private val sourceKey get() = requireArguments().getString(ARG_KEY).orEmpty()
    private val source get() = Sources.available().firstOrNull { it.key == sourceKey }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        source?.let { Sources.label(requireContext(), it) } ?: sourceKey,
        getString(R.string.explore_loading),
        getString(R.string.setting_explore_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val list = source ?: return
        val context = requireContext().applicationContext
        val perList = Settings.titlesPerList
        background({
            val factory = WallpaperFactory(context, 1280)
            val plan = factory.plan(list, perList)
            val usable = plan.usable.take(perList).toSet()
            plan.candidates.mapIndexed { index, candidate ->
                val (title, year) = factory.catalog.summary(candidate.ref) ?: ("TMDB ${candidate.ref.tmdbId}" to "")
                val status = when {
                    candidate.exclusion != null -> exclusionText(context, candidate.exclusion)
                    plan.random -> context.getString(R.string.explore_random)
                    candidate.ref in usable -> context.getString(R.string.explore_used)
                    else -> context.getString(R.string.explore_reserve)
                }
                val watched = when (val watch = plan.statuses[candidate.ref]) {
                    is WatchStatus.Watched -> " · " + context.getString(R.string.explore_watched)
                    is WatchStatus.InProgress -> " · " + context.getString(R.string.explore_watching, watch.completed, watch.aired)
                    null -> ""
                }
                val icon = when {
                    candidate.exclusion != null -> R.drawable.ic_act_block
                    candidate.ref in usable || plan.random -> R.drawable.ic_act_check
                    else -> R.drawable.ic_act_list
                }
                Triple("${index + 1}. $title${if (year.isNotBlank()) " ($year)" else ""}", status + watched, icon)
            }
        }) { result ->
            result.onSuccess { rows ->
                guidanceStylist.descriptionView?.text = getString(
                    if (Settings.selectionMode == Settings.SELECTION_RANDOM) R.string.explore_summary_random else R.string.explore_summary,
                    rows.size, perList,
                )
                setActions(rows.mapIndexed { index, (title, status, icon) ->
                    action(index.toLong(), icon, title, status).multilineDescription(true).build()
                })
            }.onFailure {
                guidanceStylist.descriptionView?.text = getString(R.string.preview_error, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun exclusionText(context: android.content.Context, exclusion: Exclusion) = context.getString(
        when (exclusion) {
            Exclusion.LOCAL_MARKET -> R.string.exclusion_local_market
            Exclusion.TOO_OLD -> R.string.exclusion_too_old
            Exclusion.KEYWORD -> R.string.exclusion_keyword
            Exclusion.WATCHED -> R.string.exclusion_watched
            Exclusion.NO_IMAGE -> R.string.exclusion_no_image
            Exclusion.DUPLICATE -> R.string.exclusion_duplicate
        }
    )

    companion object {
        private const val ARG_KEY = "list_key"

        fun newInstance(key: String) = ListContentsFragment().apply { arguments = Bundle().apply { putString(ARG_KEY, key) } }
    }
}
