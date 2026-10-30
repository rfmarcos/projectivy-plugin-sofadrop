package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The bottom line of the wallpaper: the streaming services (and their country), the list it came from
 * (always, or only when no service has the title) and, with both, whether the list goes under them or
 * beside them. A row that only matters with another one's value is hidden, and comes last.
 */
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
        val services = servicesLabel(context)
        val sources = sourceModes()
        val source = sourceLabel(context, sourceMode())
        val rows = mutableListOf(
            action(ID_SERVICES, R.drawable.ic_act_tv, getString(R.string.footer_streaming), services)
                .subActions(options(ID_SERVICES, serviceOptions(context), services)).build(),
            action(ID_SOURCE, R.drawable.ic_act_list, getString(R.string.footer_source), source)
                .subActions(options(ID_SOURCE, sources.map { sourceLabel(context, it) }, source,
                    sources.map { if (it == SOURCE_FALLBACK) getString(R.string.footer_fallback_desc) else null })).build(),
        )
        if (bothShown()) {
            val layouts = listOf(false, true)
            val layout = layoutLabel(context, Settings.sourceInline)
            // No icon: it reads as part of the list source row above
            rows += GuidedAction.Builder(context).id(ID_LAYOUT).icon(ColorDrawable(Color.TRANSPARENT))
                .title(getString(R.string.footer_layout_title)).description(layout)
                .subActions(options(ID_LAYOUT, layouts.map { layoutLabel(context, it) }, layout, listOf(
                    getString(R.string.footer_layout_lines_desc), getString(R.string.footer_layout_inline_desc)))).build()
        }
        return rows
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_SERVICES -> {
                // Hiding them keeps the country for when they come back
                Settings.showProviders = index > 0
                if (index > 0) Settings.regionOverride = if (index == 1) "" else Languages.REGIONS[index - 2]
            }
            ID_SOURCE -> {
                val mode = sourceModes()[index]
                Settings.showSource = mode != SOURCE_NONE
                if (mode != SOURCE_NONE) Settings.sourceFallback = mode == SOURCE_FALLBACK
            }
            ID_LAYOUT -> Settings.sourceInline = index == 1
        }
        refreshActions(rows()) { FooterFragment() }
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_SERVICES = 1L
        private const val ID_SOURCE = 2L
        private const val ID_LAYOUT = 3L

        private const val SOURCE_NONE = 0
        private const val SOURCE_ALWAYS = 1
        private const val SOURCE_FALLBACK = 2

        /** "Only without streaming services" only means something while they are shown. */
        private fun sourceModes() =
            if (Settings.showProviders) listOf(SOURCE_NONE, SOURCE_ALWAYS, SOURCE_FALLBACK) else listOf(SOURCE_NONE, SOURCE_ALWAYS)

        /** What is drawn: without the services, a fallback list is always there. */
        private fun sourceMode() = when {
            !Settings.showSource -> SOURCE_NONE
            Settings.showProviders && Settings.sourceFallback -> SOURCE_FALLBACK
            else -> SOURCE_ALWAYS
        }

        /** Both parts on every wallpaper, so they can share a line. */
        private fun bothShown() = Settings.showProviders && sourceMode() == SOURCE_ALWAYS

        private fun serviceOptions(context: Context) = listOf(context.getString(R.string.ratings_dont_show),
            context.getString(R.string.region_auto, Languages.regionOf(Settings.language))) +
            Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" }

        /** "Don't show", "Automatic (ES)" or "Spain (ES)". */
        private fun servicesLabel(context: Context): String = when {
            !Settings.showProviders -> context.getString(R.string.ratings_dont_show)
            Settings.regionOverride.isEmpty() -> context.getString(R.string.region_auto, Languages.regionOf(Settings.language))
            else -> "${Languages.countryName(Settings.regionOverride)} (${Settings.regionOverride})"
        }

        private fun sourceLabel(context: Context, mode: Int) = context.getString(
            when (mode) {
                SOURCE_ALWAYS -> R.string.footer_source_always
                SOURCE_FALLBACK -> R.string.footer_source_fallback
                else -> R.string.ratings_dont_show
            }
        )

        private fun layoutLabel(context: Context, inline: Boolean) =
            context.getString(if (inline) R.string.footer_layout_inline else R.string.footer_layout_lines)

        /** "Streaming in ES · list source, on one line", "List source" or "Nothing", for the details screen. */
        fun summary(context: Context): String {
            val source = when (sourceMode()) {
                SOURCE_FALLBACK -> context.getString(R.string.footer_summary_fallback)
                SOURCE_ALWAYS -> context.getString(R.string.footer_summary_source).let {
                    if (bothShown() && Settings.sourceInline) context.getString(R.string.footer_summary_inline, it) else it
                }
                else -> null
            }
            val parts = listOfNotNull(context.getString(R.string.footer_summary_services, Settings.region).takeIf { Settings.showProviders }, source)
            return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { context.getString(R.string.footer_none) }
        }
    }
}
