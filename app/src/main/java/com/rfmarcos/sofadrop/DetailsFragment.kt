package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** How each wallpaper looks: language, synopsis, footer (list source / streaming services) and resolution. */
class DetailsFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_details_title),
        getString(R.string.details_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        actions += choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.setting_language_title, Languages.displayName(Settings.language),
            Languages.OPTIONS.values.toList())
        actions += choice(ID_SYNOPSIS, R.drawable.ic_act_count, R.string.synopsis_title, synopsisLabel(context, Settings.overviewLines),
            Settings.OVERVIEW_OPTIONS.map { synopsisLabel(context, it) })
        actions += action(ID_FOOTER, R.drawable.ic_act_list, getString(R.string.footer_title), footerLabel(context, footerIndex()))
            // the fallback option is the one that needs explaining
            .subActions(options(ID_FOOTER, (0..4).map { footerLabel(context, it) }, footerLabel(context, footerIndex()),
                listOf(null, null, null, getString(R.string.footer_fallback_desc)))).build()
        actions += countryAction()
        actions += action(ID_LOGO_LANGUAGE, R.drawable.ic_act_language, getString(R.string.logo_mode_title), logoModeLabel(context, Settings.logoMode))
            .subActions(options(ID_LOGO_LANGUAGE, Settings.LOGO_MODES.map { logoModeLabel(context, it) }, logoModeLabel(context, Settings.logoMode),
                listOf(R.string.logo_mode_text_desc, R.string.logo_mode_original_desc, R.string.logo_mode_original_skip_desc, R.string.logo_mode_skip_desc).map(::getString)))
            .build()
        actions += action(ID_SYNOPSIS_LANGUAGE, R.drawable.ic_act_language, getString(R.string.synopsis_mode_title), synopsisModeLabel(context, Settings.synopsisMode))
            .subActions(options(ID_SYNOPSIS_LANGUAGE, Settings.SYNOPSIS_MODES.map { synopsisModeLabel(context, it) }, synopsisModeLabel(context, Settings.synopsisMode),
                listOf(getString(R.string.synopsis_mode_hide_desc), null, getString(R.string.synopsis_mode_skip_desc))))
            .build()
        actions += choice(ID_RESOLUTION, R.drawable.ic_act_hd, R.string.setting_resolution_title, resolutionLabel(context, Settings.outputWidth),
            Settings.WIDTH_OPTIONS.map { resolutionLabel(context, it) })
    }

    /** Country of the streaming services; only meaningful while the footer shows them (kept visible so rows don't jump). */
    private fun countryAction(): GuidedAction {
        val action = choice(ID_COUNTRY, R.drawable.ic_act_tv, R.string.streaming_country_title,
            if (Settings.showProviders) countryLabel(requireContext()) else getString(R.string.streaming_country_disabled),
            listOf(getString(R.string.region_auto, Languages.regionOf(Settings.language))) + Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" })
        action.isEnabled = Settings.showProviders
        return action
    }

    private fun replaceCountryRow() {
        val position = findActionPositionById(ID_COUNTRY)
        refreshActions(actions.toMutableList().also { it[position] = countryAction() }) { DetailsFragment() }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_LANGUAGE -> {
                Settings.language = Languages.OPTIONS.keys.elementAt(index)
                updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
                replaceCountryRow() // "Automatic (XX)" follows the language
            }
            ID_SYNOPSIS -> {
                Settings.overviewLines = Settings.OVERVIEW_OPTIONS[index]
                updateDescription(ID_SYNOPSIS, synopsisLabel(requireContext(), Settings.overviewLines))
            }
            ID_FOOTER -> {
                Settings.showSource = index == 1 || index >= 3
                Settings.showProviders = index >= 2
                Settings.sourceFallback = index == 3
                updateDescription(ID_FOOTER, footerLabel(requireContext(), index))
                replaceCountryRow()
            }
            ID_COUNTRY -> {
                Settings.regionOverride = if (index == 0) "" else Languages.REGIONS[index - 1]
                updateDescription(ID_COUNTRY, countryLabel(requireContext()))
            }
            ID_LOGO_LANGUAGE -> {
                Settings.logoMode = Settings.LOGO_MODES[index]
                updateDescription(ID_LOGO_LANGUAGE, logoModeLabel(requireContext(), Settings.logoMode))
            }
            ID_SYNOPSIS_LANGUAGE -> {
                Settings.synopsisMode = Settings.SYNOPSIS_MODES[index]
                updateDescription(ID_SYNOPSIS_LANGUAGE, synopsisModeLabel(requireContext(), Settings.synopsisMode))
            }
            ID_RESOLUTION -> {
                Settings.outputWidth = Settings.WIDTH_OPTIONS[index]
                updateDescription(ID_RESOLUTION, resolutionLabel(requireContext(), Settings.outputWidth))
            }
        }
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_LANGUAGE = 1L
        private const val ID_COUNTRY = 2L
        private const val ID_RESOLUTION = 3L
        private const val ID_SYNOPSIS = 4L
        private const val ID_FOOTER = 5L
        private const val ID_LOGO_LANGUAGE = 6L
        private const val ID_SYNOPSIS_LANGUAGE = 7L

        /** 0 nothing, 1 list source, 2 streaming services, 3 streaming or list source, 4 both. */
        private fun footerIndex() = when {
            !Settings.showProviders -> if (Settings.showSource) 1 else 0
            !Settings.showSource -> 2
            Settings.sourceFallback -> 3
            else -> 4
        }

        fun footerLabel(context: Context, index: Int) = context.getString(
            when (index) {
                1 -> R.string.footer_source
                2 -> R.string.footer_streaming
                3 -> R.string.footer_fallback
                4 -> R.string.footer_both
                else -> R.string.footer_none
            }
        )

        fun logoModeLabel(context: Context, mode: String) = context.getString(
            when (mode) {
                Settings.LOGO_ORIGINAL -> R.string.logo_mode_original
                Settings.LOGO_ORIGINAL_OR_SKIP -> R.string.logo_mode_original_skip
                Settings.LOGO_SKIP -> R.string.logo_mode_skip
                else -> R.string.logo_mode_text
            }
        )

        fun synopsisModeLabel(context: Context, mode: String) = context.getString(
            when (mode) {
                Settings.SYNOPSIS_ENGLISH -> R.string.synopsis_mode_english
                Settings.SYNOPSIS_SKIP -> R.string.synopsis_mode_skip
                else -> R.string.synopsis_mode_hide
            }
        )

        fun synopsisLabel(context: Context, lines: Int) = context.getString(
            when (lines) {
                0 -> R.string.synopsis_hidden
                2 -> R.string.synopsis_short
                else -> R.string.synopsis_full
            }
        )

        fun countryLabel(context: Context): String =
            if (Settings.regionOverride.isEmpty()) context.getString(R.string.region_auto, Languages.regionOf(Settings.language))
            else "${Languages.countryName(Settings.regionOverride)} (${Settings.regionOverride})"

        fun resolutionLabel(context: Context, width: Int) =
            context.getString(if (width > 1920) R.string.resolution_2160 else R.string.resolution_1080)

        /** "English (US) · short synopsis · streaming in US · 1080p" for the main screen. */
        fun summary(context: Context): String = listOfNotNull(
            Languages.displayName(Settings.language),
            when (Settings.overviewLines) {
                0 -> context.getString(R.string.synopsis_summary_hidden)
                2 -> context.getString(R.string.synopsis_summary_short)
                else -> null
            },
            if (Settings.showProviders) context.getString(R.string.streaming_summary, Settings.region)
            else context.getString(R.string.streaming_summary_none),
            context.getString(if (Settings.outputWidth > 1920) R.string.resolution_short_2160 else R.string.resolution_short_1080),
        ).joinToString(" · ")
    }
}
