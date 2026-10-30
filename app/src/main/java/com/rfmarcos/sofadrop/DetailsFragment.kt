package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * How each wallpaper looks: style and resolution here, and a screen for the rest (languages, header,
 * synopsis and cast, footer). Rows of the parts the style hides stay in place, disabled.
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
            screen(ID_LANGUAGE, R.drawable.ic_act_language, R.string.setting_language_title, PartLanguagesFragment.summary(context)),
            screen(ID_HEADER, R.drawable.ic_act_heart, R.string.header_title, HeaderFragment.summary(context)).hiddenBy(Settings.drawsDetails),
            screen(ID_SYNOPSIS_CAST, R.drawable.ic_act_count, R.string.synopsis_cast_title, SynopsisCastFragment.summary(context))
                .hiddenBy(Settings.drawsDetails),
            screen(ID_FOOTER, R.drawable.ic_act_list, R.string.footer_title, FooterFragment.summary(context)).hiddenBy(Settings.drawsDetails),
            action(ID_RESOLUTION, R.drawable.ic_act_hd, getString(R.string.setting_resolution_title), resolutionLabel(context, Settings.outputWidth))
                .subActions(options(ID_RESOLUTION, Settings.WIDTH_OPTIONS.map { resolutionLabel(context, it) }, resolutionLabel(context, Settings.outputWidth),
                    Settings.WIDTH_OPTIONS.map { width ->
                        if (width > 1920 && DeviceMemory.tightFor4k(context)) getString(R.string.resolution_low_memory) else null
                    }))
                .build(),
            screen(ID_PREVIEW_PIN, R.drawable.ic_act_preview, R.string.preview_pin_title, PreviewPinFragment.summary(context)),
        )
    }

    /** Row opening a sub-screen, with the summary of its settings. */
    private fun screen(id: Long, icon: Int, title: Int, summary: String) = action(id, icon, getString(title), summary).hasNext(true).build()

    // Back from a sub-screen: its summary may have changed
    override fun onResume() {
        super.onResume()
        refreshActions(rows()) { DetailsFragment() }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_LANGUAGE -> add(parentFragmentManager, PartLanguagesFragment())
            ID_HEADER -> add(parentFragmentManager, HeaderFragment())
            ID_SYNOPSIS_CAST -> add(parentFragmentManager, SynopsisCastFragment())
            ID_FOOTER -> add(parentFragmentManager, FooterFragment())
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
            }
            ID_RESOLUTION -> Settings.outputWidth = Settings.WIDTH_OPTIONS[index]
        }
        refreshActions(rows()) { DetailsFragment() }
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_LANGUAGE = 1L
        private const val ID_RESOLUTION = 3L
        private const val ID_FOOTER = 5L
        private const val ID_HEADER = 8L
        private const val ID_PREVIEW_PIN = 9L
        private const val ID_STYLE = 11L
        private const val ID_SYNOPSIS_CAST = 14L

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
