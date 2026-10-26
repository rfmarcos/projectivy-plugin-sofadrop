package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * How each wallpaper looks: style, language (and per part), synopsis, footer (list source / streaming
 * services) and resolution. Rows of the parts the style hides stay in place, disabled.
 */
class DetailsFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_details_title),
        getString(R.string.details_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val styleLabels = Settings.STYLES.map { styleLabel(context, it) }
        return listOf(
            action(ID_STYLE, R.drawable.ic_act_visibility, getString(R.string.style_title), styleLabel(context, Settings.style))
                .subActions(options(ID_STYLE, styleLabels, styleLabel(context, Settings.style),
                    Settings.STYLES.map { styleDescription(context, it) })).build(),
            choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.setting_language_title, Languages.displayName(Settings.language),
                Languages.OPTIONS.values.toList()),
            action(ID_PART_LANGUAGES, R.drawable.ic_act_language, getString(R.string.part_languages_title), PartLanguagesFragment.summary(context))
                .hasNext(true).build().hiddenBy(Settings.drawsLogo),
            action(ID_HEADER, R.drawable.ic_act_heart, getString(R.string.header_title), HeaderFragment.summary(context)).hasNext(true).build()
                .hiddenBy(Settings.drawsDetails),
            choice(ID_SYNOPSIS, R.drawable.ic_act_count, R.string.synopsis_title, synopsisLabel(context, Settings.overviewLines),
                Settings.OVERVIEW_OPTIONS.map { synopsisLabel(context, it) }).hiddenBy(Settings.drawsDetails),
            action(ID_CAST, R.drawable.ic_act_account, getString(R.string.cast_title), castLabel(context, Settings.showCast))
                .subActions(options(ID_CAST, listOf(false, true).map { castLabel(context, it) }, castLabel(context, Settings.showCast),
                    listOf(null, getString(R.string.cast_shown_desc)))).build().hiddenBy(Settings.drawsDetails),
            action(ID_FOOTER, R.drawable.ic_act_list, getString(R.string.footer_title), footerLabel(context, footerIndex()))
                // the fallback option is the one that needs explaining
                .subActions(options(ID_FOOTER, (0..4).map { footerLabel(context, it) }, footerLabel(context, footerIndex()),
                    listOf(null, null, null, getString(R.string.footer_fallback_desc)))).build().hiddenBy(Settings.drawsDetails),
            countryAction().hiddenBy(Settings.drawsDetails),
            choice(ID_RESOLUTION, R.drawable.ic_act_hd, R.string.setting_resolution_title, resolutionLabel(context, Settings.outputWidth),
                Settings.WIDTH_OPTIONS.map { resolutionLabel(context, it) }),
            action(ID_PREVIEW_PIN, R.drawable.ic_act_preview, getString(R.string.preview_pin_title), PreviewPinFragment.summary(context))
                .hasNext(true).build(),
        )
    }

    /** Country of the streaming services; only meaningful while the footer shows them (kept visible so rows don't jump). */
    private fun countryAction(): GuidedAction {
        val action = choice(ID_COUNTRY, R.drawable.ic_act_tv, R.string.streaming_country_title,
            if (Settings.showProviders) countryLabel(requireContext()) else getString(R.string.streaming_country_disabled),
            listOf(getString(R.string.region_auto, Languages.regionOf(Settings.language))) + Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" },
            selected = countryLabel(requireContext()))
        action.isEnabled = Settings.showProviders
        return action
    }

    private fun replaceCountryRow() {
        val position = findActionPositionById(ID_COUNTRY)
        refreshActions(actions.toMutableList().also { it[position] = countryAction().hiddenBy(Settings.drawsDetails) }) { DetailsFragment() }
    }

    // Back from a sub-screen: its summary may have changed
    override fun onResume() {
        super.onResume()
        refreshActions(rows()) { DetailsFragment() }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_PART_LANGUAGES -> add(parentFragmentManager, PartLanguagesFragment())
            ID_HEADER -> add(parentFragmentManager, HeaderFragment())
            ID_PREVIEW_PIN -> add(parentFragmentManager, PreviewPinFragment())
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_STYLE -> {
                Settings.style = Settings.STYLES[index]
                // Rows of the parts it hides (or brings back) change
                collapseSubActions()
                refreshActions(rows()) { DetailsFragment() }
            }
            ID_LANGUAGE -> {
                Settings.language = Languages.OPTIONS.keys.elementAt(index)
                updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
                updateDescription(ID_PART_LANGUAGES, PartLanguagesFragment.summary(requireContext()))
                replaceCountryRow() // "Automatic (XX)" follows the language
            }
            ID_SYNOPSIS -> {
                Settings.overviewLines = Settings.OVERVIEW_OPTIONS[index]
                updateDescription(ID_SYNOPSIS, synopsisLabel(requireContext(), Settings.overviewLines))
            }
            ID_CAST -> {
                Settings.showCast = index == 1
                updateDescription(ID_CAST, castLabel(requireContext(), Settings.showCast))
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
        private const val ID_PART_LANGUAGES = 6L
        private const val ID_HEADER = 8L
        private const val ID_PREVIEW_PIN = 9L
        private const val ID_CAST = 10L
        private const val ID_STYLE = 11L

        fun styleLabel(context: Context, style: String) = context.getString(
            when (style) {
                Settings.STYLE_LOGO -> R.string.style_logo
                Settings.STYLE_BACKDROP -> R.string.style_backdrop
                else -> R.string.style_full
            }
        )

        private fun styleDescription(context: Context, style: String) = context.getString(
            when (style) {
                Settings.STYLE_LOGO -> R.string.style_logo_desc
                Settings.STYLE_BACKDROP -> R.string.style_backdrop_desc
                else -> R.string.style_full_desc
            }
        )

        fun castLabel(context: Context, shown: Boolean) = context.getString(if (shown) R.string.cast_shown else R.string.cast_hidden)

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

        /** "English (US) · short synopsis · streaming in US · 1080p", or "Logo only · English (US) · 1080p", for the main screen. */
        fun summary(context: Context): String = listOfNotNull(
            styleLabel(context, Settings.style).takeIf { !Settings.drawsDetails },
            Languages.displayName(Settings.language),
            when (Settings.overviewLines) {
                0 -> context.getString(R.string.synopsis_summary_hidden)
                2 -> context.getString(R.string.synopsis_summary_short)
                else -> null
            }?.takeIf { Settings.drawsDetails },
            (if (Settings.showProviders) context.getString(R.string.streaming_summary, Settings.region)
            else context.getString(R.string.streaming_summary_none)).takeIf { Settings.drawsDetails },
            context.getString(if (Settings.outputWidth > 1920) R.string.resolution_short_2160 else R.string.resolution_short_1080),
        ).joinToString(" · ")
    }
}
