package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Which titles are skipped. Each filter applies either to the "filtered lists" (picked in their own
 * screen; trending/popular ones by default) or to all lists. Watched titles use Trakt/Simkl history.
 */
class FiltersFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_filters_title),
        getString(R.string.filters_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        actions += filter(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title, Settings.MAX_AGE_OPTIONS.map { ageLabel(context, it) })
        actions += filter(ID_GLOBAL_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title, onOff(context))
        actions += filter(ID_ADULT, R.drawable.ic_act_block, R.string.filters_adult_title, onOff(context))
        actions += action(ID_WATCHED, R.drawable.ic_act_visibility, getString(R.string.setting_watched_title), watchedDescription(context))
            .multilineDescription(true)
            .subActions(WATCHED_MODES.mapIndexed { index, mode -> sub(ID_WATCHED, index, watchedLabel(context, mode)) }).build()
        actions += action(ID_LISTS, R.drawable.ic_act_list, getString(R.string.filtered_lists_title), filteredListsDescription(context))
            .hasNext(true).build()
    }

    override fun onResume() {
        super.onResume()
        updateDescription(ID_LISTS, filteredListsDescription(requireContext()))
    }

    /** A filter row: its values, then "Applies to" with the two scopes (filtered lists / all lists). */
    private fun filter(id: Long, icon: Int, title: Int, values: List<String>): GuidedAction {
        val subs = values.mapIndexed { index, label -> sub(id, index, label) } +
            GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + values.size).title(R.string.filters_applies_to)
                .enabled(false).focusable(false).build() +
            sub(id, values.size + 1, getString(R.string.filters_scope_filtered)) +
            sub(id, values.size + 2, getString(R.string.filters_scope_all_lists))
        return action(id, icon, getString(title), filterDescription(requireContext(), id)).multilineDescription(true).subActions(subs).build()
    }

    private fun sub(parent: Long, index: Int, title: String) =
        GuidedAction.Builder(requireContext()).id(parent * SUB_ID_BASE + index).title(title).build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_LISTS) add(parentFragmentManager, FilteredListsFragment())
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val index = (action.id % SUB_ID_BASE).toInt()
        if (parent == ID_WATCHED) {
            Settings.watchedMode = WATCHED_MODES[index]
            updateDescription(ID_WATCHED, watchedDescription(requireContext()))
            return true
        }
        val count = if (parent == ID_AGE) Settings.MAX_AGE_OPTIONS.size else 2
        when {
            index < count -> when (parent) {
                ID_AGE -> Settings.maxAgeMonths = Settings.MAX_AGE_OPTIONS[index]
                ID_GLOBAL_HITS -> Settings.globalHitsOnly = index == 1
                ID_ADULT -> Settings.excludeAdult = index == 1
            }
            index > count -> {
                val all = index == count + 2
                when (parent) {
                    ID_AGE -> Settings.ageAllLists = all
                    ID_GLOBAL_HITS -> Settings.globalHitsAllLists = all
                    ID_ADULT -> Settings.adultAllLists = all
                }
            }
        }
        updateDescription(parent, filterDescription(requireContext(), parent))
        return true
    }

    companion object {
        private const val ID_AGE = 2L
        private const val ID_GLOBAL_HITS = 3L
        private const val ID_ADULT = 4L
        private const val ID_WATCHED = 5L
        private const val ID_LISTS = 6L
        private val WATCHED_MODES = listOf(Settings.WATCHED_SHOW, Settings.WATCHED_MARK, Settings.WATCHED_HIDE)

        private fun onOff(context: Context) = listOf(context.getString(R.string.filter_off), context.getString(R.string.filter_on))

        /** "1 year · filtered lists" and, below, what the filter does. */
        fun filterDescription(context: Context, id: Long): String {
            val (value, all, explain) = when (id) {
                ID_AGE -> Triple(ageLabel(context, Settings.maxAgeMonths), Settings.ageAllLists, R.string.filters_age_explain)
                ID_GLOBAL_HITS -> Triple(onOff(context)[if (Settings.globalHitsOnly) 1 else 0], Settings.globalHitsAllLists, R.string.setting_global_hits_desc)
                else -> Triple(onOff(context)[if (Settings.excludeAdult) 1 else 0], Settings.adultAllLists, R.string.filters_adult_desc)
            }
            val scope = context.getString(if (all) R.string.filters_scope_all_short else R.string.filters_scope_filtered_short)
            return "$value · $scope\n${context.getString(explain)}"
        }

        fun watchedLabel(context: Context, mode: String) = context.getString(
            when (mode) {
                Settings.WATCHED_HIDE -> R.string.watched_hide
                Settings.WATCHED_SHOW -> R.string.watched_show
                else -> R.string.watched_mark
            }
        )

        /** "Show with a Watched badge · from Trakt + Simkl" (or how to enable it) and what it does. */
        fun watchedDescription(context: Context): String {
            val trackers = listOfNotNull(
                context.getString(R.string.setting_trakt_title).takeIf { Settings.traktConnected },
                context.getString(R.string.setting_simkl_title).takeIf { Settings.simklConnected },
            )
            val label = watchedLabel(context, Settings.watchedMode)
            val first = if (trackers.isEmpty()) context.getString(R.string.watched_no_tracker, label)
            else context.getString(R.string.watched_from, label, trackers.joinToString(" + "))
            return "$first\n${context.getString(R.string.watched_explain)}"
        }

        fun filteredListsDescription(context: Context): String {
            val selected = Sources.selected()
            return context.getString(R.string.filtered_lists_desc, selected.count(Settings::isFiltered), selected.size)
        }

        fun ageLabel(context: Context, months: Int) = when {
            months == 0 -> context.getString(R.string.filters_age_none)
            months % 12 == 0 -> context.resources.getQuantityString(R.plurals.filters_age_years, months / 12, months / 12)
            else -> context.getString(R.string.filters_age_months, months)
        }

        /** One-line summary for the main settings screen. */
        fun summary(context: Context): String = listOfNotNull(
            ageLabel(context, Settings.maxAgeMonths),
            context.getString(R.string.setting_global_hits_title).takeIf { Settings.globalHitsOnly },
            context.getString(R.string.watched_summary_hidden).takeIf { Settings.trackerConnected && Settings.watchedMode == Settings.WATCHED_HIDE },
        ).joinToString(" · ")
    }
}

/** The active lists that filters set to "Filtered lists" apply to. */
class FilteredListsFragment : BaseSettingsFragment() {

    private var lists: List<ListSource> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.filtered_lists_title),
        getString(R.string.filtered_lists_description),
        getString(R.string.setting_filters_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        lists = Sources.selected()
        if (lists.isEmpty()) {
            actions += action(-1L, R.drawable.ic_act_info, getString(R.string.explore_empty_title), getString(R.string.explore_empty_desc))
                .focusable(false).build()
        }
        lists.forEachIndexed { index, source ->
            actions += checkbox(index.toLong(), Sources.label(requireContext(), source), null, Settings.isFiltered(source)).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val source = lists.getOrNull(action.id.toInt()) ?: return
        // The first change turns the default (trending/popular lists) into an explicit choice
        val current = Settings.filteredLists ?: lists.filter { it.automatic }.map { it.key }.toSet()
        Settings.filteredLists = if (action.isChecked) current + source.key else current - source.key
    }
}
