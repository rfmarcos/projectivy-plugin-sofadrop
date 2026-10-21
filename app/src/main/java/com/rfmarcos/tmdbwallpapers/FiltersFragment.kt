package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Which titles are skipped: age limit, local-market titles, adult content, and where they apply. */
class FiltersFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_filters_title),
        getString(R.string.filters_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        actions += action(ID_SCOPE, R.drawable.ic_act_list, getString(R.string.filters_scope_title), scopeLabel(context, Settings.filterScope))
            .subActions(SCOPES.mapIndexed { index, scope -> sub(ID_SCOPE, index, scopeLabel(context, scope)) }).build()
        actions += action(ID_AGE, R.drawable.ic_act_schedule, getString(R.string.filters_age_title), ageLabel(context, Settings.maxAgeMonths))
            .subActions(Settings.MAX_AGE_OPTIONS.mapIndexed { index, months -> sub(ID_AGE, index, ageLabel(context, months)) }).build()
        actions += action(ID_GLOBAL_HITS, R.drawable.ic_act_public, getString(R.string.setting_global_hits_title),
            getString(R.string.setting_global_hits_desc))
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.globalHitsOnly).build()
        actions += action(ID_ADULT, R.drawable.ic_act_block, getString(R.string.filters_adult_title),
            getString(R.string.filters_adult_desc))
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.excludeAdult).build()
    }

    private fun sub(parent: Long, index: Int, title: String) =
        GuidedAction.Builder(requireContext()).id(parent * SUB_ID_BASE + index).title(title).build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_GLOBAL_HITS -> Settings.globalHitsOnly = action.isChecked
            ID_ADULT -> Settings.excludeAdult = action.isChecked
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_SCOPE -> {
                Settings.filterScope = SCOPES[index]
                updateDescription(ID_SCOPE, scopeLabel(requireContext(), Settings.filterScope))
            }
            ID_AGE -> {
                Settings.maxAgeMonths = Settings.MAX_AGE_OPTIONS[index]
                updateDescription(ID_AGE, ageLabel(requireContext(), Settings.maxAgeMonths))
            }
        }
        return true
    }

    companion object {
        private const val ID_SCOPE = 1L
        private const val ID_AGE = 2L
        private const val ID_GLOBAL_HITS = 3L
        private const val ID_ADULT = 4L
        private const val SUB_ID_BASE = 1000L
        private val SCOPES = listOf(Settings.SCOPE_AUTOMATIC, Settings.SCOPE_ALL)

        fun scopeLabel(context: Context, scope: String) = context.getString(
            if (scope == Settings.SCOPE_ALL) R.string.filters_scope_all else R.string.filters_scope_automatic
        )

        fun ageLabel(context: Context, months: Int) = when {
            months == 0 -> context.getString(R.string.filters_age_none)
            months % 12 == 0 -> context.resources.getQuantityString(R.plurals.filters_age_years, months / 12, months / 12)
            else -> context.getString(R.string.filters_age_months, months)
        }

        /** One-line summary for the main settings screen. */
        fun summary(context: Context): String = listOfNotNull(
            ageLabel(context, Settings.maxAgeMonths),
            context.getString(R.string.setting_global_hits_title).takeIf { Settings.globalHitsOnly },
            scopeLabel(context, Settings.filterScope),
        ).joinToString(" · ")
    }
}
