package com.rfmarcos.sofadrop

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Pick a selected list to see what it contains and tune its filters. */
class ListExplorerFragment : BaseSettingsFragment() {

    private var lists: List<ListSource> = emptyList()
    private val customOnly get() = arguments?.getBoolean(ARG_CUSTOM_ONLY) == true

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(if (customOnly) R.string.filters_custom_title else R.string.setting_explore_title),
        getString(R.string.explore_description),
        getString(if (customOnly) R.string.setting_filters_title else R.string.setting_lists_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    override fun onResume() {
        super.onResume()
        actions = buildRows() // a list's filters may have changed on its page
    }

    private fun buildRows(): List<GuidedAction> {
        // Only selected lists: duplicates (and so what a list really brings) depend on the others
        lists = if (customOnly) FiltersFragment.customised() else Sources.selected()
        if (lists.isEmpty()) {
            return listOf(action(EMPTY, R.drawable.ic_act_info, getString(R.string.explore_empty_title), getString(R.string.explore_empty_desc))
                .focusable(false).build())
        }
        return lists.mapIndexed { index, source ->
            action(index.toLong(), R.drawable.ic_act_list, Sources.label(requireContext(), source),
                FiltersFragment.listFiltersSummary(requireContext(), source)).hasNext(true).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == EMPTY) return
        val source = lists.getOrNull(action.id.toInt()) ?: return
        add(parentFragmentManager, ListContentsFragment.newInstance(source.key))
    }

    companion object {
        private const val EMPTY = -1L
        private const val ARG_CUSTOM_ONLY = "custom_only"

        fun newInstance(customOnly: Boolean = false) = ListExplorerFragment().apply { arguments = bundleOf(ARG_CUSTOM_ONLY to customOnly) }
    }
}

/**
 * One list: its own filters (each "Default (…)" or a value) on top, then its titles in order with what
 * happens to each one, recomputed whenever a filter changes.
 */
class ListContentsFragment : BaseSettingsFragment() {

    private val sourceKey get() = requireArguments().getString(ARG_KEY).orEmpty()
    private val source get() = Sources.available().firstOrNull { it.key == sourceKey }
    private var loadJob = 0
    private var filterActions: List<GuidedAction> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        source?.let { Sources.label(requireContext(), it) } ?: sourceKey,
        getString(R.string.explore_loading),
        getString(R.string.setting_explore_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val list = source ?: return
        filterActions = filterRows(list)
        actions += filterActions
        loadTitles(list)
    }

    // --- The list's filters ---

    private fun filterRows(list: ListSource): List<GuidedAction> {
        val context = requireContext()
        val custom = Settings.listFilters[list.key] ?: ListFilters()
        fun default(label: String) = getString(R.string.list_filter_default, label)
        val defaultAge = FiltersFragment.ageLabel(context, Settings.defaultMaxAge(list))
        val defaultHits = FiltersFragment.onOff(context)[if (Settings.defaultGlobalHits(list)) 1 else 0]
        val defaultWatched = FiltersFragment.watchedLabel(context, Settings.watchedMode)
        val rows = mutableListOf(
            filterRow(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title,
                custom.maxAge?.let { FiltersFragment.ageLabel(context, it) } ?: default(defaultAge),
                listOf(default(defaultAge)) + Settings.MAX_AGE_OPTIONS.map { FiltersFragment.ageLabel(context, it) }),
        )
        // Global hits never applies to lists that are niche on purpose (anime)
        if (list.localMarketFilter) rows += filterRow(ID_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title,
            custom.globalHits?.let { FiltersFragment.onOff(context)[if (it) 1 else 0] } ?: default(defaultHits),
            listOf(default(defaultHits)) + FiltersFragment.onOff(context))
        rows += filterRow(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title,
            custom.watched?.let { FiltersFragment.watchedLabel(context, it) } ?: default(defaultWatched),
            listOf(default(defaultWatched)) + FiltersFragment.WATCHED_MODES.map { FiltersFragment.watchedLabel(context, it) })
        return rows
    }

    private fun filterRow(id: Long, icon: Int, title: Int, value: String, options: List<String>) =
        action(id, icon, getString(title), value)
            .subActions(options.mapIndexed { index, label -> GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).build() })
            .build()

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val list = source ?: return true
        val index = (action.id % SUB_ID_BASE).toInt()
        val current = Settings.listFilters[list.key] ?: ListFilters()
        // Option 0 is always "Default (…)"
        val updated = when (action.id / SUB_ID_BASE) {
            ID_AGE -> current.copy(maxAge = if (index == 0) null else Settings.MAX_AGE_OPTIONS[index - 1])
            ID_HITS -> current.copy(globalHits = if (index == 0) null else index == 2)
            ID_WATCHED -> current.copy(watched = if (index == 0) null else FiltersFragment.WATCHED_MODES[index - 1])
            else -> return true
        }
        Settings.listFilters = Settings.listFilters + (list.key to updated)
        // Update the rows in place (replacing them would break the sub-action expansion)
        filterRows(list).forEach { fresh -> updateDescription(fresh.id, fresh.description) }
        loadTitles(list)
        return true
    }

    // --- The titles ---

    private fun loadTitles(list: ListSource) {
        val context = requireContext().applicationContext
        val perList = Settings.titlesPerList
        val job = ++loadJob
        guidanceStylist.descriptionView?.text = getString(R.string.explore_loading)
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
            if (job != loadJob) return@background
            result.onSuccess { rows ->
                guidanceStylist.descriptionView?.text = getString(
                    if (Settings.selectionMode == Settings.SELECTION_RANDOM) R.string.explore_summary_random else R.string.explore_summary,
                    rows.size, perList,
                )
                setActions(filterActions + rows.mapIndexed { index, (title, status, icon) ->
                    action(TITLE_BASE + index, icon, title, status).multilineDescription(true).focusable(true).build()
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
            Exclusion.ADULT -> R.string.exclusion_adult
            Exclusion.WATCHED -> R.string.exclusion_watched
            Exclusion.NO_IMAGE -> R.string.exclusion_no_image
            Exclusion.DUPLICATE -> R.string.exclusion_duplicate
        }
    )

    companion object {
        private const val ARG_KEY = "list_key"
        private const val ID_AGE = 1L
        private const val ID_HITS = 2L
        private const val ID_WATCHED = 3L
        private const val TITLE_BASE = 100L

        fun newInstance(key: String) = ListContentsFragment().apply { arguments = Bundle().apply { putString(ARG_KEY, key) } }
    }
}
