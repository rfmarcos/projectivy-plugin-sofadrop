package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The default filters of every list (watch history first, then what to do with watched titles), and the
 * selected lists: open one to see what it contains and give it its own filters.
 */
class ListExplorerFragment : BaseSettingsFragment() {

    private var lists: List<ListSource> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_explore_title),
        getString(R.string.explore_description),
        getString(R.string.setting_lists_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    override fun onResume() {
        super.onResume()
        refresh() // a list's filters or the connected trackers may have changed
    }

    // Replacing the actions breaks leanback's sub-action expansion, so rows are updated in place
    private fun refresh() = refreshActions(buildRows()) { ListExplorerFragment() }

    private fun buildRows(): List<GuidedAction> {
        val context = requireContext()
        val trackers = Settings.connectedTrackers
        val rows = mutableListOf(
            choiceRow(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title, Settings.MAX_AGE_OPTIONS.map { Filters.ageLabel(context, it) }),
            choiceRow(ID_GLOBAL_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title, Filters.onOff(context)),
            choiceRow(ID_WATCH_SOURCE, R.drawable.ic_act_history, R.string.watch_source_title,
                (listOf(Settings.WATCH_NONE) + trackers).map { Filters.watchSourceLabel(context, it) }).also { it.isEnabled = trackers.isNotEmpty() },
            choiceRow(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title, Filters.WATCHED_MODES.map { Filters.watchedLabel(context, it) })
                .also { it.isEnabled = Settings.watchSource != null },
            action(ID_ADULT, R.drawable.ic_act_block, getString(R.string.filters_adult_title), getString(R.string.filters_adult_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.excludeAdult).multilineDescription(true).build(),
            action(ID_DUPLICATES, R.drawable.ic_act_list, getString(R.string.filters_duplicates_title), getString(R.string.filters_duplicates_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.skipDuplicates).multilineDescription(true).build(),
            action(ID_LOGO, R.drawable.ic_act_hd, getString(R.string.filters_logo_title), getString(R.string.filters_logo_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.requireLogo).multilineDescription(true).build(),
        )
        // Only selected lists: duplicates (and so what a list really brings) depend on the others
        lists = Sources.selected()
        rows += if (lists.isEmpty()) {
            action(ID_EMPTY, R.drawable.ic_act_info, getString(R.string.explore_empty_title), getString(R.string.explore_empty_desc)).focusable(false).build()
        } else {
            action(ID_HEADER, R.drawable.ic_act_list, getString(R.string.explore_lists_header), getString(R.string.explore_lists_header_desc)).focusable(false).build()
        }
        rows += lists.mapIndexed { index, source ->
            action(LIST_BASE + index, R.drawable.ic_act_list, Sources.label(context, source), Filters.listFiltersSummary(context, source)).hasNext(true).build()
        }
        return rows
    }

    private fun choiceRow(id: Long, icon: Int, title: Int, values: List<String>) =
        action(id, icon, getString(title), describe(requireContext(), id)).multilineDescription(true)
            .subActions(values.mapIndexed { index, label -> GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).build() })
            .build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_ADULT -> Settings.excludeAdult = action.isChecked
            ID_DUPLICATES -> Settings.skipDuplicates = action.isChecked
            ID_LOGO -> Settings.requireLogo = action.isChecked
        }
        val source = lists.getOrNull((action.id - LIST_BASE).toInt()) ?: return
        add(parentFragmentManager, ListContentsFragment.newInstance(source.key))
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_AGE -> Settings.maxAgeMonths = Settings.MAX_AGE_OPTIONS[index]
            ID_GLOBAL_HITS -> Settings.globalHitsOnly = index == 1
            ID_WATCH_SOURCE -> Settings.setWatchSource((listOf(Settings.WATCH_NONE) + Settings.connectedTrackers)[index])
            ID_WATCHED -> Settings.watchedMode = Filters.WATCHED_MODES[index]
        }
        refresh() // the watched row follows the watch history
        return true
    }

    companion object {
        private const val ID_EMPTY = -1L
        private const val ID_AGE = 2L
        private const val ID_GLOBAL_HITS = 3L
        private const val ID_ADULT = 4L
        private const val ID_WATCHED = 5L
        private const val ID_WATCH_SOURCE = 6L
        private const val ID_HEADER = 7L
        private const val ID_DUPLICATES = 8L
        private const val ID_LOGO = 9L
        private const val LIST_BASE = 100L

        /** Value of a default filter and, below, what it does. */
        private fun describe(context: Context, id: Long): String = when (id) {
            ID_AGE -> "${Filters.ageLabel(context, Settings.maxAgeMonths)}\n${context.getString(R.string.filters_age_explain)}"
            ID_GLOBAL_HITS -> "${Filters.onOff(context)[if (Settings.globalHitsOnly) 1 else 0]}\n${context.getString(R.string.setting_global_hits_desc)}"
            ID_WATCH_SOURCE -> if (Settings.connectedTrackers.isEmpty()) context.getString(R.string.watch_source_connect)
                else "${Filters.watchSourceLabel(context, Settings.watchSource)}\n${context.getString(R.string.watch_source_explain)}"
            else -> if (Settings.watchSource == null) context.getString(R.string.watched_need_source)
                else "${Filters.watchedLabel(context, Settings.watchedMode)}\n${context.getString(R.string.watched_explain)}"
        }
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
        val defaultAge = Filters.ageLabel(context, Settings.defaultMaxAge(list))
        val defaultHits = Filters.onOff(context)[if (Settings.defaultGlobalHits(list)) 1 else 0]
        val defaultWatched = Filters.watchedLabel(context, Settings.watchedMode)
        val rows = mutableListOf(
            filterRow(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title,
                custom.maxAge?.let { Filters.ageLabel(context, it) } ?: default(defaultAge),
                listOf(default(defaultAge)) + Settings.MAX_AGE_OPTIONS.map { Filters.ageLabel(context, it) }),
        )
        // Global hits never applies to lists that are niche on purpose (anime)
        if (list.localMarketFilter) rows += filterRow(ID_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title,
            custom.globalHits?.let { Filters.onOff(context)[if (it) 1 else 0] } ?: default(defaultHits),
            listOf(default(defaultHits)) + Filters.onOff(context))
        // What to do with watched titles needs a watch history (chosen in the default filters)
        if (Settings.watchSource != null) rows += filterRow(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title,
            custom.watched?.let { Filters.watchedLabel(context, it) } ?: default(defaultWatched),
            listOf(default(defaultWatched)) + Filters.WATCHED_MODES.map { Filters.watchedLabel(context, it) })
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
            ID_WATCHED -> current.copy(watched = if (index == 0) null else Filters.WATCHED_MODES[index - 1])
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
            Exclusion.NO_LOGO -> R.string.exclusion_no_logo
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
