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
 * What Projectivy opens from a wallpaper: its trailer (in which language, and what instead when there is
 * none in it), the title in one of the apps installed (see [TitleApp]) or nothing.
 */
class ClickActionFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.click_action_title),
        guidanceText(),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // Where Projectivy opens it from, always in view under the preview
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = View.VISIBLE
    }

    /** Where it opens from, and when the trailer is picked if that's the action. */
    private fun guidanceText() = listOfNotNull(getString(R.string.click_action_desc),
        getString(R.string.click_trailer_note).takeIf { Settings.clickAction == Settings.CLICK_TRAILER }).joinToString(" ")

    /** The trailer, every app (those not installed can't be picked) and nothing. */
    private fun clickActions(): List<String> =
        listOf(Settings.CLICK_TRAILER) + TitleApp.entries.map { Settings.CLICK_APP_PREFIX + it.key } + Settings.CLICK_NONE

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val clickActions = clickActions()
        val options = options(ID_ACTION, clickActions.map { clickLabel(context, it) }, clickLabel(context, Settings.clickAction),
            clickActions.map { clickDescription(context, it) })
        options.zip(clickActions).forEach { (option, action) ->
            option.isEnabled = Settings.clickApp(action)?.let { it.installedPackage(context) != null } ?: true
        }
        val target = action(ID_ACTION, R.drawable.ic_act_movie, getString(R.string.click_target_title), clickLabel(context, Settings.clickAction, long = true))
            .subActions(options).build()
        // The trailer's language only matters when the wallpaper opens it
        if (Settings.clickAction != Settings.CLICK_TRAILER) return listOf(target)
        val languages = Settings.PART_LANGUAGES
        val language = action(ID_TRAILER_LANGUAGE, R.drawable.ic_act_language, getString(R.string.trailer_language_title),
            PartLanguagesFragment.languageLabel(context, Settings.trailerLanguage, long = true))
            .subActions(options(ID_TRAILER_LANGUAGE, languages.map { PartLanguagesFragment.languageLabel(context, it) },
                PartLanguagesFragment.languageLabel(context, Settings.trailerLanguage),
                languages.map { if (it == Settings.LANGUAGE_AUTO) Languages.displayName(Settings.language) else null })).build()
        // No icon: it reads as part of the language row above
        val fallback = GuidedAction.Builder(context).id(ID_TRAILER_FALLBACK).icon(ColorDrawable(Color.TRANSPARENT))
            .title(getString(R.string.trailer_fallback_title)).description(fallbackLabel(context, Settings.trailerFallback))
            .subActions(options(ID_TRAILER_FALLBACK, Settings.TRAILER_FALLBACKS.map { fallbackLabel(context, it) },
                fallbackLabel(context, Settings.trailerFallback), Settings.TRAILER_FALLBACKS.map { fallbackDescription(context, it) })).build()
        return listOf(target, language, fallback)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_ACTION -> {
                // Projectivy gets it with the next request; nothing to preview or regenerate
                Settings.clickAction = clickActions()[index]
                setGuidanceText(guidanceText())
                refreshActions(rows()) { ClickActionFragment() }
                return true
            }
            ID_TRAILER_LANGUAGE -> Settings.trailerLanguage = Settings.PART_LANGUAGES[index]
            ID_TRAILER_FALLBACK -> Settings.trailerFallback = Settings.TRAILER_FALLBACKS[index]
        }
        // The trailer is chosen when generating: a new set picks it up
        refreshActions(rows()) { ClickActionFragment() }
        refreshPending()
        return true
    }

    companion object {
        private const val ID_ACTION = 1L
        private const val ID_TRAILER_LANGUAGE = 2L
        private const val ID_TRAILER_FALLBACK = 3L

        /** "See the trailer", "Open in Nuvio" or "Nothing"; [long] says when the app isn't installed any more. */
        fun clickLabel(context: Context, action: String, long: Boolean = false): String {
            val app = Settings.clickApp(action)
            return when {
                app != null && long && app.installedPackage(context) == null -> context.getString(R.string.click_app_missing, app.label)
                app != null -> context.getString(R.string.click_open_app, app.label)
                action == Settings.CLICK_NONE -> context.getString(R.string.click_none)
                else -> context.getString(R.string.click_trailer)
            }
        }

        private fun clickDescription(context: Context, action: String): String? {
            val app = Settings.clickApp(action)
            val reach = when (app?.reach) {
                TitleApp.Reach.TITLE -> context.getString(R.string.click_app_title_desc)
                TitleApp.Reach.SEARCH -> context.getString(R.string.click_app_search_desc)
                TitleApp.Reach.APP -> context.getString(R.string.click_app_launch_desc)
                null -> return if (action == Settings.CLICK_TRAILER) context.getString(R.string.click_trailer_desc) else null
            }
            return if (app.installedPackage(context) == null) context.getString(R.string.click_app_not_installed, reach) else reach
        }

        fun fallbackLabel(context: Context, fallback: String) = context.getString(
            when (fallback) {
                Settings.TRAILER_ORIGINAL -> R.string.trailer_fallback_original
                Settings.TRAILER_ANY -> R.string.trailer_fallback_any
                Settings.TRAILER_NONE -> R.string.trailer_fallback_none
                else -> R.string.trailer_fallback_english
            }
        )

        private fun fallbackDescription(context: Context, fallback: String): String? = when (fallback) {
            Settings.TRAILER_ENGLISH, Settings.TRAILER_ORIGINAL -> context.getString(R.string.trailer_fallback_then_any)
            Settings.TRAILER_NONE -> context.getString(R.string.trailer_fallback_none_desc)
            else -> null
        }

        /** "See the trailer (Español (España))" or "Open in Nuvio", for the details screen. */
        fun summary(context: Context): String {
            val action = clickLabel(context, Settings.clickAction, long = true)
            if (Settings.clickAction != Settings.CLICK_TRAILER) return action
            val language = Settings.trailerLanguage
            return "$action · " + if (language == Settings.LANGUAGE_AUTO) Languages.displayName(Settings.language)
            else PartLanguagesFragment.languageLabel(context, language)
        }
    }
}
