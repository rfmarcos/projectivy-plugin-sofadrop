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
 * What Projectivy opens from a wallpaper: the title on the user's streaming platforms, tried in their
 * order (see [Platform]); else the fallback: the trailer (in which language, and what instead when there is
 * none in it), the title in an app (see [TitleApp]) or nothing. Also whether that is decided when clicked.
 */
class ClickActionFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.click_action_title),
        guidanceText(),
        getString(R.string.plugin_name),
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

    /** Where it opens from, and when the trailer is picked if it can open. */
    private fun guidanceText() = listOfNotNull(getString(R.string.click_action_desc),
        getString(R.string.click_trailer_note).takeIf { showsTrailer(requireContext()) }).joinToString(" ")

    /** The trailer, every app (one not installed opens the trailer) and nothing. */
    private fun fallbacks(): List<String> =
        listOf(Settings.CLICK_TRAILER) + TitleApp.entries.map { Settings.CLICK_APP_PREFIX + it.key } + Settings.CLICK_NONE

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val order = Settings.clickPlatforms
        val rows = mutableListOf(header(ID_PLATFORMS_HEADER, getString(R.string.click_platforms_header)))
        // The ones in use by their position, then the rest
        rows += (order + Platform.entries.filter { it !in order }).map { platformRow(it, order) }

        rows += header(ID_FALLBACK_HEADER, getString(R.string.click_fallback_header))
        val fallbacks = fallbacks()
        explanations[ID_FALLBACK] = getString(R.string.click_fallback_explanation)
        rows += action(ID_FALLBACK, R.drawable.ic_act_play, getString(R.string.click_target_title), clickLabel(context, Settings.clickFallback, long = true))
            .subActions(options(ID_FALLBACK, fallbacks.map { clickLabel(context, it) }, clickLabel(context, Settings.clickFallback),
                fallbacks.map { clickDescription(context, it) })).build()
        // The trailer's language only matters when the wallpaper can open it
        if (showsTrailer(context)) {
            val languages = Settings.PART_LANGUAGES
            rows += action(ID_TRAILER_LANGUAGE, R.drawable.ic_act_language, getString(R.string.trailer_language_title),
                PartLanguagesFragment.languageLabel(context, Settings.trailerLanguage, long = true))
                .subActions(options(ID_TRAILER_LANGUAGE, languages.map { PartLanguagesFragment.languageLabel(context, it) },
                    PartLanguagesFragment.languageLabel(context, Settings.trailerLanguage),
                    languages.map { if (it == Settings.LANGUAGE_AUTO) Languages.displayName(Settings.language) else null })).build()
            // No icon: it reads as part of the language row above
            rows += GuidedAction.Builder(context).id(ID_TRAILER_FALLBACK).icon(ColorDrawable(Color.TRANSPARENT))
                .title(getString(R.string.trailer_fallback_title)).description(fallbackLabel(context, Settings.trailerFallback))
                .subActions(options(ID_TRAILER_FALLBACK, Settings.TRAILER_FALLBACKS.map { fallbackLabel(context, it) },
                    fallbackLabel(context, Settings.trailerFallback), Settings.TRAILER_FALLBACKS.map { fallbackDescription(context, it) })).build()
        }

        rows += header(ID_ADVANCED_HEADER, getString(R.string.click_advanced_header))
        explanations[ID_AT_TAP] = getString(R.string.click_at_tap_explanation)
        rows += checkbox(ID_AT_TAP, getString(R.string.click_at_tap_title), getString(R.string.click_at_tap_desc), Settings.clickAtTap).build()
        return rows
    }

    /**
     * A platform in use ("1. Netflix") moves to the position picked or stops being used; one not in use
     * ("Prime Video") is added at the position picked.
     */
    private fun platformRow(platform: Platform, order: List<Platform>): GuidedAction {
        val context = requireContext()
        val id = platformId(platform)
        val position = order.indexOf(platform)
        val used = position >= 0
        val positions = (1..if (used) order.size else order.size + 1)
            .map { getString(if (used) R.string.click_position else R.string.click_use_at, it) }
        val labels = positions + listOfNotNull(getString(R.string.click_dont_use).takeIf { used })
        explanations[id] = getString(R.string.click_platform_explanation, platform.label)
        val description = when {
            !used -> getString(R.string.click_unused)
            platform.installedPackage(context) != null -> getString(R.string.click_installed)
            else -> getString(R.string.click_not_installed_skipped)
        }
        // No icon: there's no logo of each service in the app, and the numbers already line them up
        return GuidedAction.Builder(context).id(id).icon(ColorDrawable(Color.TRANSPARENT))
            .title(if (used) "${position + 1}. ${platform.label}" else platform.label).description(description)
            .subActions(options(id, labels, if (used) labels[position] else "")).build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_AT_TAP) Settings.clickAtTap = action.isChecked
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val index = (action.id % SUB_ID_BASE).toInt()
        val platform = Platform.entries.firstOrNull { platformId(it) == parent }
        when {
            platform != null -> {
                // Positions first, then "don't use" (only on a platform in use)
                val others = Settings.clickPlatforms - platform
                Settings.clickPlatforms = if (index > others.size) others else others.toMutableList().apply { add(index, platform) }
            }
            parent == ID_FALLBACK -> Settings.clickFallback = fallbacks()[index]
            parent == ID_TRAILER_LANGUAGE -> Settings.trailerLanguage = Settings.PART_LANGUAGES[index]
            parent == ID_TRAILER_FALLBACK -> Settings.trailerFallback = Settings.TRAILER_FALLBACKS[index]
        }
        setGuidanceText(guidanceText())
        refreshActions(rows())
        // A platform that moved keeps the focus; adding the first one, or the trailer, needs a new set
        handler.post { if (isAdded) findActionPositionById(parent).takeIf { it >= 0 }?.let(::setSelectedActionPosition) }
        refreshPending()
        return true
    }

    companion object {
        private const val ID_PLATFORMS_HEADER = 1L
        private const val ID_FALLBACK_HEADER = 2L
        private const val ID_FALLBACK = 3L
        private const val ID_TRAILER_LANGUAGE = 4L
        private const val ID_TRAILER_FALLBACK = 5L
        private const val ID_ADVANCED_HEADER = 6L
        private const val ID_AT_TAP = 7L
        private const val ID_PLATFORM_BASE = 10L

        private fun platformId(platform: Platform) = ID_PLATFORM_BASE + platform.ordinal

        /** The trailer opens when it is the fallback or the fallback's app isn't installed. */
        private fun showsTrailer(context: Context): Boolean {
            val fallback = Settings.clickFallback
            if (fallback == Settings.CLICK_NONE) return false
            return Settings.clickApp(fallback)?.installedPackage(context) == null
        }

        /** "See the trailer", "Open in Nuvio" or "Nothing"; [long] says when the app isn't installed. */
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

        /** "Netflix › HBO Max › Trailer", or without platforms "See the trailer · Español (España)" / "Open in Nuvio". */
        fun summary(context: Context): String {
            val platforms = Settings.clickPlatforms.map { it.label }
            val fallback = Settings.clickFallback
            if (platforms.isEmpty()) {
                val action = clickLabel(context, fallback, long = true)
                if (fallback != Settings.CLICK_TRAILER) return action
                val language = Settings.trailerLanguage
                return "$action · " + if (language == Settings.LANGUAGE_AUTO) Languages.displayName(Settings.language)
                else PartLanguagesFragment.languageLabel(context, language)
            }
            val shown = if (platforms.size > 2) context.getString(R.string.click_summary_more, platforms.take(2).joinToString(", "), platforms.size - 2)
            else platforms.joinToString(" › ")
            val app = Settings.clickApp(fallback)
            val last = when {
                fallback == Settings.CLICK_NONE -> null
                app != null && app.installedPackage(context) != null -> app.label
                else -> context.getString(R.string.click_summary_trailer)
            }
            return listOfNotNull(shown, last).joinToString(" › ")
        }
    }
}
