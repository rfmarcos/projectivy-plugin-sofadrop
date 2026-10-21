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
        actions += choice(ID_RESOLUTION, R.drawable.ic_act_hd, R.string.setting_resolution_title, resolutionLabel(Settings.outputWidth),
            Settings.WIDTH_OPTIONS.map(::resolutionLabel))
    }

    /** "Don't show", "Automatic (US)" and every country: hiding the services and their country are one choice. */
    private fun streamingAction() = choice(ID_STREAMING, R.drawable.ic_act_tv, R.string.streaming_title, streamingLabel(requireContext()),
        listOf(getString(R.string.streaming_none), getString(R.string.region_auto, Languages.regionOf(Settings.language))) +
            Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" })

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

        fun streamingLabel(context: Context): String = when {
            !Settings.showProviders -> context.getString(R.string.streaming_none)
            Settings.regionOverride.isEmpty() -> context.getString(R.string.region_auto, Languages.regionOf(Settings.language))
            else -> "${Languages.countryName(Settings.regionOverride)} (${Settings.regionOverride})"
        }

        fun resolutionLabel(context: Context, width: Int) =
            context.getString(if (width > 1920) R.string.resolution_2160 else R.string.resolution_1080)

        /** "English (US) · streaming in US · 1080p" for the main screen. */
        fun summary(context: Context): String = listOf(
            Languages.displayName(Settings.language),
            if (Settings.showProviders) context.getString(R.string.streaming_summary, Settings.region)
            else context.getString(R.string.streaming_summary_none),
            context.getString(if (Settings.outputWidth > 1920) R.string.resolution_short_2160 else R.string.resolution_short_1080),
        ).joinToString(" · ")
    }
}
