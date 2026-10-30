package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** The bottom line of the wallpaper: the list it came from and/or the streaming services, and their country. */
class FooterFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.footer_title),
        getString(R.string.footer_desc),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        return listOf(
            action(ID_FOOTER, R.drawable.ic_act_list, getString(R.string.footer_content_title), footerLabel(context, footerIndex()))
                // the fallback option is the one that needs explaining
                .subActions(options(ID_FOOTER, (0..4).map { footerLabel(context, it) }, footerLabel(context, footerIndex()),
                    listOf(null, null, null, getString(R.string.footer_fallback_desc)))).build(),
            countryAction(),
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

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_FOOTER -> {
                Settings.showSource = index == 1 || index >= 3
                Settings.showProviders = index >= 2
                Settings.sourceFallback = index == 3
                refreshActions(rows()) { FooterFragment() }
            }
            ID_COUNTRY -> {
                Settings.regionOverride = if (index == 0) "" else Languages.REGIONS[index - 1]
                updateDescription(ID_COUNTRY, countryLabel(requireContext()))
            }
        }
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_FOOTER = 1L
        private const val ID_COUNTRY = 2L

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

        fun countryLabel(context: Context): String =
            if (Settings.regionOverride.isEmpty()) context.getString(R.string.region_auto, Languages.regionOf(Settings.language))
            else "${Languages.countryName(Settings.regionOverride)} (${Settings.regionOverride})"

        /** "Streaming, or list source · ES", for the details screen. */
        fun summary(context: Context): String =
            footerLabel(context, footerIndex()) + if (Settings.showProviders) " · ${Settings.region}" else ""
    }
}
