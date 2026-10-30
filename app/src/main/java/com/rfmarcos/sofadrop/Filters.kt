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

    /** "All genres", "All but 2 genres" or "Only 3 genres". */
    fun genresLabel(context: Context, filter: GenreFilter): String = when {
        filter.isOff -> context.getString(R.string.genres_all)
        else -> context.resources.getQuantityString(
            if (filter.only) R.plurals.genres_only else R.plurals.genres_except, filter.ids.size, filter.ids.size)
    }

    /** "No limit", "6 months", "5 years" or "From 2000". */
    fun ageLabel(context: Context, months: Int) = when {
        months == 0 -> context.getString(R.string.filters_age_none)
        Settings.isYear(months) -> context.getString(R.string.filters_age_from_year, months)
        months % 12 == 0 -> context.resources.getQuantityString(R.plurals.filters_age_years, months / 12, months / 12)
        else -> context.getString(R.string.filters_age_months, months)
    }

    /** "No limit" or "7.5+" (TMDB score). */
    fun ratingLabel(context: Context, rating: Double) =
        if (rating <= 0) context.getString(R.string.filters_age_none) else context.getString(R.string.filters_min_rating_value, rating)

    /** "No limit" or "Up to 12 years old" (age certification). */
    fun ratingAgeLabel(context: Context, years: Int) =
        if (years == 0) context.getString(R.string.filters_age_none) else context.getString(R.string.filters_rating_age_up_to, years)

    /** "Own filters: no age limit, hide" or "Default filters", for list rows. */
    fun listFiltersSummary(context: Context, source: ListSource): String {
        val custom = Settings.listFilters[source.key]?.takeUnless { it.isDefault }
            ?: return context.getString(R.string.list_filters_default)
        val parts = listOfNotNull(
            custom.maxAge?.let { ageLabel(context, it) },
            custom.globalHits?.let { context.getString(if (it) R.string.list_filters_hits_on else R.string.list_filters_hits_off) },
            custom.watched?.takeIf { Settings.watchSource != null }?.let { watchedLabel(context, it) },
            custom.genres?.let { genresLabel(context, it) },
            custom.minRating?.let { context.getString(R.string.filters_min_rating_summary, ratingLabel(context, it)) },
        )
        return context.getString(R.string.list_filters_custom, parts.joinToString(", "))
    }

    /** One-line summary of the default filters, for the Lists screen. */
    fun summary(context: Context): String = listOfNotNull(
        ageLabel(context, Settings.maxAgeMonths),
        context.getString(R.string.setting_global_hits_title).takeIf { Settings.globalHitsOnly },
        Settings.maxRatingAge.takeIf { it > 0 }?.let { context.getString(R.string.filters_rating_age_summary, it) },
        Settings.genreFilter.takeUnless { it.isOff }?.let { genresLabel(context, it) },
        Settings.minRating.takeIf { it > 0 }?.let { context.getString(R.string.filters_min_rating_summary, ratingLabel(context, it)) },
        context.getString(R.string.watched_summary_hidden).takeIf { Settings.watchSource != null && Settings.watchedMode == Settings.WATCHED_HIDE },
        customised().size.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.lists_customised, it, it) },
    ).joinToString(" · ")
}
