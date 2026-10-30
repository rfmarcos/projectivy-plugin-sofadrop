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
 * What Projectivy opens from a wallpaper: the trailer (in which language, and what instead when there is
 * none in it), the title in an app (see [TitleApp]) or nothing; but first the title on the user's streaming
 * platforms, tried in their order (see [Platform]).
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

    // The explanations, and where Projectivy opens it from, always in view under the preview
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

    /**
     * The steps in the order they're tried, numbered: "1. Apple TV", "Add a platform", "2. Open in Nuvio".
     * Only the platforms in use are rows; the others are in "Add a platform".
     */
    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val order = Settings.clickPlatforms
        val rows = order.mapIndexed { index, platform -> platformRow(platform, index, order.size) }.toMutableList()
        rows += addRow(order)

        val fallbacks = fallbacks()
        val fallback = Settings.clickFallback
        val missing = Settings.clickApp(fallback)?.takeIf { it.installedPackage(context) == null }
        explain(ID_FALLBACK, getString(R.string.click_target_explanation))
        val label = clickLabel(context, fallback)
        val description = listOfNotNull(
            getString(if (order.isEmpty()) R.string.click_target_title else R.string.click_if_none),
            getString(R.string.click_app_missing_short).takeIf { missing != null },
        ).joinToString(" · ")
        rows += action(ID_FALLBACK, R.drawable.ic_act_play, if (order.isEmpty()) label else step(order.size + 1, label), description)
            .subActions(options(ID_FALLBACK, fallbacks.map { clickLabel(context, it) }, label,
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
        return rows
    }

    /** A row's explanation, followed by where Projectivy opens it from: the explanation replaces the guidance. */
    private fun explain(id: Long, text: String) {
        explanations[id] = text + "\n\n" + getString(R.string.click_action_desc)
    }

    private fun step(position: Int, label: String) = getString(R.string.click_step, position, label)

    /** "1. Apple TV": moves to the position picked or stops being used. */
    private fun platformRow(platform: Platform, position: Int, count: Int): GuidedAction {
        val id = platformId(platform)
        val labels = (1..count).map { getString(R.string.click_position, it) } + getString(R.string.click_dont_use)
        explain(id, getString(R.string.click_platform_explanation, platform.label))
        val installed = platform.installedPackage(requireContext()) != null
        return action(id, R.drawable.ic_act_tv, step(position + 1, platform.label),
            getString(if (installed) R.string.click_if_on_it else R.string.click_not_installed_skipped))
            .subActions(options(id, labels, labels[position])).build()
    }

    /**
     * The platforms not in use (a regional one only in its countries), each added after the others: the
     * installed ones, then the rest, which can't be picked (it would always be skipped).
     */
    private fun addRow(order: List<Platform>): GuidedAction {
        val context = requireContext()
        val (installed, missing) = addable(context, order)
        explain(ID_ADD, getString(R.string.click_add_explanation))
        val names = installed.map { it.label }
        val description = when {
            names.isEmpty() -> getString(R.string.click_add_none)
            names.size > 2 -> getString(R.string.click_summary_more, names.take(2).joinToString(", "), names.size - 2)
            else -> names.joinToString(", ")
        }
        val options = (installed + missing).mapIndexed { index, platform ->
            GuidedAction.Builder(context).id(ID_ADD * SUB_ID_BASE + index).title(platform.label)
                .description(getString(if (platform in installed) R.string.click_installed else R.string.click_not_installed))
                .enabled(platform in installed).build()
        }
        return action(ID_ADD, R.drawable.ic_act_add, getString(R.string.click_add_platform), description)
            .subActions(options).enabled(options.isNotEmpty()).build()
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val index = (action.id % SUB_ID_BASE).toInt()
        val platform = Platform.entries.firstOrNull { platformId(it) == parent }
        when {
            platform != null -> {
                // Positions first, then "don't use"
                val others = Settings.clickPlatforms - platform
                Settings.clickPlatforms = if (index > others.size) others else others.toMutableList().apply { add(index, platform) }
            }
            parent == ID_ADD -> addable(requireContext(), Settings.clickPlatforms).first.getOrNull(index)
                ?.let { Settings.clickPlatforms += it }
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
        private const val ID_ADD = 2L
        private const val ID_FALLBACK = 3L
        private const val ID_TRAILER_LANGUAGE = 4L
        private const val ID_TRAILER_FALLBACK = 5L
        private const val ID_PLATFORM_BASE = 10L

        private fun platformId(platform: Platform) = ID_PLATFORM_BASE + platform.ordinal

        /** The platforms that can be added (installed) and those that can't yet (not installed). */
        private fun addable(context: Context, order: List<Platform>) =
            Platform.entries.filter { it !in order && it.offeredIn(Settings.region, context) }
                .partition { it.installedPackage(context) != null }

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

        /**
         * "Apple TV; otherwise Nuvio", "Netflix, HBO Max and 1 more; otherwise the trailer", "Only Apple TV", or
         * without platforms (installed ones: the others are skipped) "See the trailer · Español (España)" / "Open in Nuvio".
         */
        fun summary(context: Context): String {
            val platforms = Settings.clickPlatforms.filter { it.installedPackage(context) != null }.map { it.label }
            val fallback = Settings.clickFallback
            if (platforms.isEmpty()) {
                val action = clickLabel(context, fallback, long = true)
                if (fallback != Settings.CLICK_TRAILER) return action
                val language = Settings.trailerLanguage
                return "$action · " + if (language == Settings.LANGUAGE_AUTO) Languages.displayName(Settings.language)
                else PartLanguagesFragment.languageLabel(context, language)
            }
            val shown = if (platforms.size > 2) context.getString(R.string.click_summary_more, platforms.take(2).joinToString(", "), platforms.size - 2)
            else platforms.joinToString(", ")
            val app = Settings.clickApp(fallback)
            return when {
                fallback == Settings.CLICK_NONE -> context.getString(R.string.click_summary_only, shown)
                app != null && app.installedPackage(context) != null -> context.getString(R.string.click_summary_else, shown, app.label)
                else -> context.getString(R.string.click_summary_else, shown, context.getString(R.string.click_summary_trailer))
            }
        }
    }
}
