package com.rfmarcos.sofadrop

import android.content.Context

/** Labels and summaries of the filters (defaults in "Filters & explore lists", overrides on each list's page). */
object Filters {
    val WATCHED_MODES = listOf(Settings.WATCHED_SHOW, Settings.WATCHED_MARK, Settings.WATCHED_HIDE)

    fun onOff(context: Context) = listOf(context.getString(R.string.filter_off), context.getString(R.string.filter_on))

    /** Selected lists with their own filters. */
    fun customised() = Sources.selected().filter { Settings.listFilters[it.key]?.isDefault == false }

    fun watchedLabel(context: Context, mode: String) = context.getString(
        when (mode) {
            Settings.WATCHED_HIDE -> R.string.watched_hide
            Settings.WATCHED_SHOW -> R.string.watched_show
            else -> R.string.watched_mark
        }
    )

    /** "Trakt", "Simkl" or "Don't use". */
    fun watchSourceLabel(context: Context, source: String?) = context.getString(
        when (source) {
            Settings.WATCH_TRAKT -> R.string.setting_trakt_title
            Settings.WATCH_SIMKL -> R.string.setting_simkl_title
            else -> R.string.watch_source_off
        }
    )

    /** "All genres", "Custom selection" or "Except: custom selection", for the default-filters row. */
    fun genresLabel(context: Context) = when {
        Settings.allowedGenres.isEmpty() -> context.getString(R.string.genres_summary_all)
        Settings.excludeSelectedGenres -> context.getString(R.string.genres_summary_custom_except)
        else -> context.getString(R.string.genres_summary_custom)
    }

    fun ageLabel(context: Context, months: Int) = when {
        months == 0 -> context.getString(R.string.filters_age_none)
        months % 12 == 0 -> context.resources.getQuantityString(R.plurals.filters_age_years, months / 12, months / 12)
        else -> context.getString(R.string.filters_age_months, months)
    }

    /** "Own filters: no age limit, hide" or "Default filters", for list rows. */
    fun listFiltersSummary(context: Context, source: ListSource): String {
        val custom = Settings.listFilters[source.key]?.takeUnless { it.isDefault }
            ?: return context.getString(R.string.list_filters_default)
        val parts = listOfNotNull(
            custom.maxAge?.let { ageLabel(context, it) },
            custom.globalHits?.let { context.getString(if (it) R.string.list_filters_hits_on else R.string.list_filters_hits_off) },
            custom.watched?.takeIf { Settings.watchSource != null }?.let { watchedLabel(context, it) },
        )
        return context.getString(R.string.list_filters_custom, parts.joinToString(", "))
    }

    /** One-line summary of the default filters, for the Lists screen. */
    fun summary(context: Context): String = listOfNotNull(
        ageLabel(context, Settings.maxAgeMonths),
        context.getString(R.string.setting_global_hits_title).takeIf { Settings.globalHitsOnly },
        genresLabel(context).takeIf { Settings.allowedGenres.isNotEmpty() },
        context.getString(R.string.watched_summary_hidden).takeIf { Settings.watchSource != null && Settings.watchedMode == Settings.WATCHED_HIDE },
        customised().size.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.lists_customised, it, it) },
    ).joinToString(" · ")
}
