package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** How each wallpaper looks: language, streaming services (and their country) and resolution. */
class DetailsFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_details_title),
        getString(R.string.details_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.setting_language_title, Languages.displayName(Settings.language),
            Languages.OPTIONS.values.toList())
        actions += streamingAction()
        actions += choice(ID_SYNOPSIS, R.drawable.ic_act_count, R.string.synopsis_title, synopsisLabel(requireContext(), Settings.overviewLines),
            Settings.OVERVIEW_OPTIONS.map { synopsisLabel(requireContext(), it) })
        actions += action(ID_SOURCE, R.drawable.ic_act_list, getString(R.string.show_source_title), getString(R.string.show_source_desc))
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.showSource).build()
        actions += choice(ID_RESOLUTION, R.drawable.ic_act_hd, R.string.setting_resolution_title, resolutionLabel(Settings.outputWidth),
            Settings.WIDTH_OPTIONS.map(::resolutionLabel))
    }

    /** "Don't show", "Automatic (US)" and every country: hiding the services and their country are one choice. */
    private fun streamingAction() = choice(ID_STREAMING, R.drawable.ic_act_tv, R.string.streaming_title, streamingLabel(requireContext()),
        listOf(getString(R.string.streaming_none), getString(R.string.region_auto, Languages.regionOf(Settings.language))) +
            Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" })

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_SOURCE) Settings.showSource = action.isChecked
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_LANGUAGE -> {
                Settings.language = Languages.OPTIONS.keys.elementAt(index)
                updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
                // "Automatic (XX)" follows the language
                val position = findActionPositionById(ID_STREAMING)
                actions = actions.toMutableList().also { it[position] = streamingAction() }
            }
            ID_STREAMING -> {
                Settings.showProviders = index > 0
                if (index > 0) Settings.regionOverride = if (index == 1) "" else Languages.REGIONS[index - 2]
                updateDescription(ID_STREAMING, streamingLabel(requireContext()))
            }
            ID_SYNOPSIS -> {
                Settings.overviewLines = Settings.OVERVIEW_OPTIONS[index]
                updateDescription(ID_SYNOPSIS, synopsisLabel(requireContext(), Settings.overviewLines))
            }
            ID_RESOLUTION -> {
                Settings.outputWidth = Settings.WIDTH_OPTIONS[index]
                updateDescription(ID_RESOLUTION, resolutionLabel(Settings.outputWidth))
            }
        }
        return true
    }

    private fun resolutionLabel(width: Int) = resolutionLabel(requireContext(), width)

    companion object {
        private const val ID_LANGUAGE = 1L
        private const val ID_STREAMING = 2L
        private const val ID_RESOLUTION = 3L
        private const val ID_SYNOPSIS = 4L
        private const val ID_SOURCE = 5L

        fun synopsisLabel(context: Context, lines: Int) = context.getString(
            when (lines) {
                0 -> R.string.synopsis_hidden
                2 -> R.string.synopsis_short
                else -> R.string.synopsis_full
            }
        )

        fun streamingLabel(context: Context): String = when {
            !Settings.showProviders -> context.getString(R.string.streaming_none)
            Settings.regionOverride.isEmpty() -> context.getString(R.string.region_auto, Languages.regionOf(Settings.language))
            else -> "${Languages.countryName(Settings.regionOverride)} (${Settings.regionOverride})"
        }

        fun resolutionLabel(context: Context, width: Int) =
            context.getString(if (width > 1920) R.string.resolution_2160 else R.string.resolution_1080)

        /** "English (US) · streaming in US · 1080p" for the main screen. */
        fun summary(context: Context): String = listOfNotNull(
            Languages.displayName(Settings.language),
            if (Settings.showProviders) context.getString(R.string.streaming_summary, Settings.region)
            else context.getString(R.string.streaming_summary_none),
            when (Settings.overviewLines) {
                0 -> context.getString(R.string.synopsis_summary_hidden)
                2 -> context.getString(R.string.synopsis_summary_short)
                else -> null
            },
            context.getString(if (Settings.outputWidth > 1920) R.string.resolution_short_2160 else R.string.resolution_short_1080),
        ).joinToString(" · ")
    }
}
