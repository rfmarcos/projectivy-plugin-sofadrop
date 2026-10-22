package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Default filters for every list (each list can override them in "Explore & tune lists"), adult content
 * (always global) and the lists that have their own filters.
 */
class FiltersFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_filters_title),
        getString(R.string.filters_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    private fun buildRows(): List<GuidedAction> {
        val context = requireContext()
        val rows = mutableListOf(
            choiceRow(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title, Settings.MAX_AGE_OPTIONS.map { ageLabel(context, it) }),
            choiceRow(ID_GLOBAL_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title, onOff(context)),
            choiceRow(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title, WATCHED_MODES.map { watchedLabel(context, it) }),
            action(ID_ADULT, R.drawable.ic_act_block, getString(R.string.filters_adult_title), getString(R.string.filters_adult_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.excludeAdult).multilineDescription(true).build(),
        )
        customised().takeIf { it.isNotEmpty() }?.let { lists ->
            rows += action(ID_CUSTOM, R.drawable.ic_act_list, getString(R.string.filters_custom_title),
                ListsFragment.listsCount(context, lists.size)).hasNext(true).build()
        }
        return rows
    }

    private fun choiceRow(id: Long, icon: Int, title: Int, values: List<String>) =
        action(id, icon, getString(title), describe(requireContext(), id)).multilineDescription(true)
            .subActions(values.mapIndexed { index, label -> GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).build() })
            .build()

    override fun onResume() {
        super.onResume()
        // Replacing the actions breaks leanback's sub-action expansion, so only rebuild when the
        // "lists with their own filters" row has to appear or disappear
        val count = customised().size
        when {
            (count > 0) != (findActionById(ID_CUSTOM) != null) -> refreshActions(buildRows()) { FiltersFragment() }
            count > 0 -> updateDescription(ID_CUSTOM, ListsFragment.listsCount(requireContext(), count))
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_ADULT -> Settings.excludeAdult = action.isChecked
            ID_CUSTOM -> add(parentFragmentManager, ListExplorerFragment.newInstance(customOnly = true))
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val index = (action.id % SUB_ID_BASE).toInt()
        when (parent) {
            ID_AGE -> Settings.maxAgeMonths = Settings.MAX_AGE_OPTIONS[index]
            ID_GLOBAL_HITS -> Settings.globalHitsOnly = index == 1
            ID_WATCHED -> Settings.watchedMode = WATCHED_MODES[index]
        }
        updateDescription(parent, describe(requireContext(), parent))
        return true
    }

    companion object {
        private const val ID_AGE = 2L
        private const val ID_GLOBAL_HITS = 3L
        private const val ID_ADULT = 4L
        private const val ID_WATCHED = 5L
        private const val ID_CUSTOM = 6L
        val WATCHED_MODES = listOf(Settings.WATCHED_SHOW, Settings.WATCHED_MARK, Settings.WATCHED_HIDE)

        fun onOff(context: Context) = listOf(context.getString(R.string.filter_off), context.getString(R.string.filter_on))

        /** Selected lists with their own filters. */
        fun customised() = Sources.selected().filter { Settings.listFilters[it.key]?.isDefault == false }

        /** Value of a default filter and, below, what it does. */
        private fun describe(context: Context, id: Long): String = when (id) {
            ID_AGE -> "${ageLabel(context, Settings.maxAgeMonths)}\n${context.getString(R.string.filters_age_explain)}"
            ID_GLOBAL_HITS -> "${onOff(context)[if (Settings.globalHitsOnly) 1 else 0]}\n${context.getString(R.string.setting_global_hits_desc)}"
            else -> watchedDescription(context, Settings.watchedMode)
        }

        fun watchedLabel(context: Context, mode: String) = context.getString(
            when (mode) {
                Settings.WATCHED_HIDE -> R.string.watched_hide
                Settings.WATCHED_SHOW -> R.string.watched_show
                else -> R.string.watched_mark
            }
        )

        /** "Show with a Watched badge · from Trakt + Simkl" (or how to enable it) and what it does. */
        fun watchedDescription(context: Context, mode: String): String {
            val trackers = listOfNotNull(
                context.getString(R.string.setting_trakt_title).takeIf { Settings.traktConnected },
                context.getString(R.string.setting_simkl_title).takeIf { Settings.simklConnected },
            )
            val label = watchedLabel(context, mode)
            val first = if (trackers.isEmpty()) context.getString(R.string.watched_no_tracker, label)
            else context.getString(R.string.watched_from, label, trackers.joinToString(" + "))
            return "$first\n${context.getString(R.string.watched_explain)}"
        }

        fun ageLabel(context: Context, months: Int) = when {
            months == 0 -> context.getString(R.string.filters_age_none)
            months % 12 == 0 -> context.resources.getQuantityString(R.plurals.filters_age_years, months / 12, months / 12)
            else -> context.getString(R.string.filters_age_months, months)
        }

        /** "Custom: no age limit, hide watched" or "Default filters", for list rows. */
        fun listFiltersSummary(context: Context, source: ListSource): String {
            val custom = Settings.listFilters[source.key]?.takeUnless { it.isDefault }
                ?: return context.getString(R.string.list_filters_default)
            val parts = listOfNotNull(
                custom.maxAge?.let { ageLabel(context, it) },
                custom.globalHits?.let { context.getString(if (it) R.string.list_filters_hits_on else R.string.list_filters_hits_off) },
                custom.watched?.let { watchedLabel(context, it) },
            )
            return context.getString(R.string.list_filters_custom, parts.joinToString(", "))
        }

        /** One-line summary for the main settings screen. */
        fun summary(context: Context): String = listOfNotNull(
            ageLabel(context, Settings.maxAgeMonths),
            context.getString(R.string.setting_global_hits_title).takeIf { Settings.globalHitsOnly },
            context.getString(R.string.watched_summary_hidden).takeIf { Settings.trackerConnected && Settings.watchedMode == Settings.WATCHED_HIDE },
            customised().size.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.lists_customised, it, it) },
        ).joinToString(" · ")
    }
}
