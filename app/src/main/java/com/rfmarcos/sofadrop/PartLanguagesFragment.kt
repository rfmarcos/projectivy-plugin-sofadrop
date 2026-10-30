package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The general language, then the language of the logo (and of the title written instead) and of the
 * synopsis, each followed by what to do when TMDB has none in it. "Automatic" follows the wallpaper
 * language, like everything else drawn.
 */
class PartLanguagesFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_language_title),
        getString(R.string.part_languages_desc, Languages.displayName(Settings.language)),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // What Automatic means, always in view under the preview
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = View.VISIBLE
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val originalLogo = Settings.partLanguages.title == null
        val logoModes = Settings.LOGO_MODES.map { logoModeLabel(context, it, originalLogo) }
        val synopsisModes = synopsisModes()
        val synopsisMode = shownSynopsisMode()
        return listOf(
            choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.general_language_title, Languages.displayName(Settings.language),
                Languages.OPTIONS.values.toList()),
            languageRow(ID_TITLE_LANGUAGE, R.string.logo_language_title, Settings.titleLanguage).hiddenBy(Settings.drawsLogo),
            fallbackRow(ID_LOGO_MODE, R.string.logo_mode_title, logoModeLabel(context, Settings.logoMode, originalLogo), logoModes,
                Settings.LOGO_MODES.map { logoModeDescription(context, it, originalLogo) }).hiddenBy(Settings.drawsLogo),
            languageRow(ID_SYNOPSIS_LANGUAGE, R.string.synopsis_language_title, Settings.synopsisLanguage).hiddenBy(Settings.drawsDetails),
            fallbackRow(ID_SYNOPSIS_MODE, R.string.synopsis_mode_title, synopsisModeLabel(context, synopsisMode),
                synopsisModes.map { synopsisModeLabel(context, it) },
                synopsisModes.map { mode -> synopsisModeDescription(context, mode) }).hiddenBy(Settings.drawsDetails),
        )
    }

    private fun languageRow(id: Long, title: Int, current: String): GuidedAction {
        val context = requireContext()
        val labels = Settings.PART_LANGUAGES.map { languageLabel(context, it) }
        val descriptions = Settings.PART_LANGUAGES.map {
            when (it) {
                Settings.LANGUAGE_AUTO -> Languages.displayName(Settings.language)
                Settings.LANGUAGE_ORIGINAL -> getString(R.string.language_original_desc)
                else -> null
            }
        }
        return action(id, R.drawable.ic_act_language, getString(title), languageLabel(context, current, long = true))
            .subActions(options(id, labels, languageLabel(context, current), descriptions)).build()
    }

    // No icon: it reads as part of the language row above
    private fun fallbackRow(id: Long, title: Int, current: String, labels: List<String>, descriptions: List<String?>) =
        GuidedAction.Builder(requireContext()).id(id).icon(ColorDrawable(Color.TRANSPARENT)).title(getString(title)).description(current)
            .subActions(options(id, labels, current, descriptions)).build()

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_LANGUAGE -> {
                Settings.language = Languages.OPTIONS.keys.elementAt(index)
                // What Automatic stands for
                setGuidanceText(getString(R.string.part_languages_desc, Languages.displayName(Settings.language)))
            }
            ID_TITLE_LANGUAGE -> Settings.titleLanguage = Settings.PART_LANGUAGES[index]
            ID_LOGO_MODE -> Settings.logoMode = Settings.LOGO_MODES[index]
            ID_SYNOPSIS_LANGUAGE -> Settings.synopsisLanguage = Settings.PART_LANGUAGES[index]
            ID_SYNOPSIS_MODE -> Settings.synopsisMode = synopsisModes()[index]
        }
        // A language changes the options of its fallback row, and the wallpaper's what Automatic says
        collapseSubActions()
        refreshActions(rows()) { PartLanguagesFragment() }
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_TITLE_LANGUAGE = 1L
        private const val ID_LOGO_MODE = 2L
        private const val ID_SYNOPSIS_LANGUAGE = 3L
        private const val ID_SYNOPSIS_MODE = 4L
        private const val ID_LANGUAGE = 6L

        /** A synopsis already in English has no English one to fall back to. */
        private fun englishSynopsis() = Settings.partLanguages.synopsis?.let(Languages::code) == "en"

        private fun synopsisModes() =
            if (englishSynopsis()) Settings.SYNOPSIS_MODES - Settings.SYNOPSIS_ENGLISH else Settings.SYNOPSIS_MODES

        /** "Show it in English" works as "Leave it out" for an English synopsis. */
        private fun shownSynopsisMode() =
            Settings.synopsisMode.takeIf { it in synopsisModes() } ?: Settings.SYNOPSIS_HIDE

        /** "Automatic", "Original language" or the language; [long] adds what Automatic stands for. */
        fun languageLabel(context: Context, value: String, long: Boolean = false): String = when (value) {
            Settings.LANGUAGE_AUTO ->
                if (long) context.getString(R.string.language_auto_current, Languages.displayName(Settings.language))
                else context.getString(R.string.language_auto)
            Settings.LANGUAGE_ORIGINAL -> context.getString(R.string.language_original)
            else -> Languages.displayName(value)
        }

        /** With an original-language logo there is no other original one: the fallback is the English logo. */
        fun logoModeLabel(context: Context, mode: String, originalLogo: Boolean = false) = context.getString(
            when (mode) {
                Settings.LOGO_ORIGINAL -> if (originalLogo) R.string.logo_mode_english else R.string.logo_mode_original
                Settings.LOGO_ORIGINAL_OR_SKIP -> if (originalLogo) R.string.logo_mode_english_skip else R.string.logo_mode_original_skip
                Settings.LOGO_SKIP -> R.string.logo_mode_skip
                else -> R.string.logo_mode_text
            }
        )

        private fun logoModeDescription(context: Context, mode: String, originalLogo: Boolean) = context.getString(
            when (mode) {
                Settings.LOGO_ORIGINAL -> if (originalLogo) R.string.logo_mode_english_desc else R.string.logo_mode_original_desc
                Settings.LOGO_ORIGINAL_OR_SKIP -> if (originalLogo) R.string.logo_mode_english_skip_desc else R.string.logo_mode_original_skip_desc
                Settings.LOGO_SKIP -> R.string.logo_mode_skip_desc
                else -> R.string.logo_mode_text_desc
            }
        )

        fun synopsisModeLabel(context: Context, mode: String) = context.getString(
            when (mode) {
                Settings.SYNOPSIS_ENGLISH -> R.string.synopsis_mode_english
                Settings.SYNOPSIS_SKIP -> R.string.synopsis_mode_skip
                else -> R.string.synopsis_mode_hide
            }
        )

        private fun synopsisModeDescription(context: Context, mode: String) = when (mode) {
            Settings.SYNOPSIS_HIDE -> context.getString(R.string.synopsis_mode_hide_desc)
            Settings.SYNOPSIS_SKIP -> context.getString(R.string.synopsis_mode_skip_desc)
            else -> null
        }

        /** "Español (España)", plus the parts that differ: "Español (España) · Logo: Original language · skips titles", for the details screen. */
        fun summary(context: Context): String = listOfNotNull(
            Languages.displayName(Settings.language),
            Settings.titleLanguage.takeIf { it != Settings.LANGUAGE_AUTO && Settings.drawsLogo }
                ?.let { context.getString(R.string.part_summary_logo, languageLabel(context, it)) },
            Settings.synopsisLanguage.takeIf { it != Settings.LANGUAGE_AUTO && Settings.drawsDetails }
                ?.let { context.getString(R.string.part_summary_synopsis, languageLabel(context, it)) },
            context.getString(R.string.part_summary_skips).takeIf { Settings.requireLogo || Settings.requireSynopsis },
        ).joinToString(" · ")
    }
}
