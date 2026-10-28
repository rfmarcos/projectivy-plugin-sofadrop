package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The default filters of every list (watch history first, then what to do with watched titles), and the
 * selected lists: open one to see what it contains and give it its own filters.
 */
class ListExplorerFragment : BaseSettingsFragment() {

    private var lists: List<ListSource> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_explore_title),
        getString(R.string.explore_description),
        getString(R.string.setting_lists_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    override fun onResume() {
        super.onResume()
        refresh() // a list's filters or the connected trackers may have changed
    }

    // Replacing the actions breaks leanback's sub-action expansion, so rows are updated in place
    private fun refresh() = refreshActions(buildRows()) { ListExplorerFragment() }

    private fun buildRows(): List<GuidedAction> {
        val context = requireContext()
        val trackers = Settings.connectedTrackers
        val rows = mutableListOf(
            choiceRow(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title, ageOptions(Settings.maxAgeMonths).map { Filters.ageLabel(context, it) },
                Filters.ageLabel(context, Settings.maxAgeMonths)),
            yearRow(ID_FROM_YEAR, Settings.maxAgeMonths),
            choiceRow(ID_GLOBAL_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title, Filters.onOff(context),
                Filters.onOff(context)[if (Settings.globalHitsOnly) 1 else 0]),
            if (trackers.isEmpty()) action(ID_WATCH_SOURCE, R.drawable.ic_act_history, getString(R.string.watch_source_title),
                describe(context, ID_WATCH_SOURCE)).hasNext(true).build()
            else choiceRow(ID_WATCH_SOURCE, R.drawable.ic_act_history, R.string.watch_source_title,
                (listOf(Settings.WATCH_NONE) + trackers).map { Filters.watchSourceLabel(context, it) },
                Filters.watchSourceLabel(context, Settings.watchSource)),
            choiceRow(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title, Filters.WATCHED_MODES.map { Filters.watchedLabel(context, it) },
                Filters.watchedLabel(context, Settings.watchedMode)).also { it.isEnabled = Settings.watchSource != null },
            action(ID_ADULT, R.drawable.ic_act_block, getString(R.string.filters_adult_title), null)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.excludeAdult).build(),
            choiceRow(ID_RATING_AGE, R.drawable.ic_act_filter, R.string.filters_rating_age_title,
                Settings.MAX_RATING_AGE_OPTIONS.map { Filters.ratingAgeLabel(context, it) }, Filters.ratingAgeLabel(context, Settings.maxRatingAge)),
            action(ID_GENRES, R.drawable.ic_act_movie, getString(R.string.genres_title), Filters.genresLabel(context, Settings.genreFilter))
                .hasNext(true).build(),
            action(ID_DUPLICATES, R.drawable.ic_act_list, getString(R.string.filters_duplicates_title), null)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.skipDuplicates).build(),
        )
        explanations.putAll(filterExplanations(context))
        // Only selected lists: duplicates (and so what a list really brings) depend on the others
        lists = Sources.selected()
        rows += if (lists.isEmpty()) {
            action(ID_EMPTY, R.drawable.ic_act_info, getString(R.string.explore_empty_title), getString(R.string.explore_empty_desc)).focusable(false).build()
        } else {
            header(ID_HEADER, getString(R.string.explore_lists_header))
        }
        rows += lists.mapIndexed { index, source ->
            action(LIST_BASE + index, R.drawable.ic_act_list, Sources.label(context, source), Filters.listFiltersSummary(context, source)).hasNext(true).build()
        }
        return rows
    }

    private fun choiceRow(id: Long, icon: Int, title: Int, values: List<String>, current: String) =
        action(id, icon, getString(title), describe(requireContext(), id))
            .subActions(options(id, values, current)).build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_ADULT -> Settings.excludeAdult = action.isChecked
            ID_WATCH_SOURCE -> if (Settings.connectedTrackers.isEmpty()) add(parentFragmentManager, AccountsFragment.newInstance(SourceProvider.TRAKT))
            ID_DUPLICATES -> Settings.skipDuplicates = action.isChecked
            ID_GENRES -> { add(parentFragmentManager, GenresFragment.newInstance(null)); return }
        }
        val source = lists.getOrNull((action.id - LIST_BASE).toInt()) ?: return
        add(parentFragmentManager, ListContentsFragment.newInstance(source.key))
    }

    /** A typed year becomes the age limit; emptied, a year limit goes. */
    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id != ID_FROM_YEAR) return GuidedAction.ACTION_ID_CURRENT
        typedYear(requireContext(), action, Settings.maxAgeMonths)?.let { Settings.maxAgeMonths = it.getOrNull() ?: 0 }
        refresh()
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_AGE -> Settings.maxAgeMonths = ageOptions(Settings.maxAgeMonths)[index]
            ID_GLOBAL_HITS -> Settings.globalHitsOnly = index == 1
            ID_WATCH_SOURCE -> Settings.setWatchSource((listOf(Settings.WATCH_NONE) + Settings.connectedTrackers)[index])
            ID_WATCHED -> Settings.watchedMode = Filters.WATCHED_MODES[index]
            ID_RATING_AGE -> Settings.maxRatingAge = Settings.MAX_RATING_AGE_OPTIONS[index]
        }
        refresh() // the watched row follows the watch history
        return true
    }

    companion object {
        private const val ID_EMPTY = -1L
        private const val ID_AGE = 2L
        private const val ID_GLOBAL_HITS = 3L
        private const val ID_ADULT = 4L
        private const val ID_WATCHED = 5L
        private const val ID_WATCH_SOURCE = 6L
        private const val ID_HEADER = 7L
        private const val ID_DUPLICATES = 8L
        private const val ID_RATING_AGE = 9L
        private const val ID_GENRES = 10L
        private const val ID_FROM_YEAR = 11L
        private const val LIST_BASE = 100L

        /** The age limits to choose from, with the typed year when [current] is one. */
        fun ageOptions(current: Int?) = Settings.MAX_AGE_OPTIONS + listOfNotNull(current?.takeIf { Settings.isYear(it) })

        /**
         * The year typed in [action] (which is then emptied): success with the year, success with null when
         * emptied while [current] is a year, or null to change nothing (with a message when it isn't a year).
         */
        fun typedYear(context: Context, action: GuidedAction, current: Int?): Result<Int?>? {
            val text = action.editDescription?.toString().orEmpty().trim()
            action.editDescription = ""
            if (text.isEmpty()) return if (current != null && Settings.isYear(current)) Result.success(null) else null
            val year = Settings.parseYear(text)
            if (year == null) Toast.makeText(context, context.getString(R.string.filters_year_invalid, text), Toast.LENGTH_LONG).show()
            return year?.let { Result.success(it) }
        }

        /** Value of a default filter (what it does is shown in the left pane, see [filterExplanations]). */
        private fun describe(context: Context, id: Long): String = when (id) {
            ID_AGE -> Filters.ageLabel(context, Settings.maxAgeMonths)
            ID_GLOBAL_HITS -> Filters.onOff(context)[if (Settings.globalHitsOnly) 1 else 0]
            ID_RATING_AGE -> Filters.ratingAgeLabel(context, Settings.maxRatingAge)
            ID_WATCH_SOURCE -> if (Settings.connectedTrackers.isEmpty()) context.getString(R.string.watch_source_connect)
                else Filters.watchSourceLabel(context, Settings.watchSource)
            else -> if (Settings.watchSource == null) context.getString(R.string.watched_need_source)
                else Filters.watchedLabel(context, Settings.watchedMode)
        }

        /** What each filter does, for the left pane; the list pages use the same ids for their own filters. */
        fun filterExplanations(context: Context): Map<Long, String> {
            val automatic = context.getString(R.string.filters_automatic_note)
            return mapOf(
                ID_AGE to context.getString(R.string.filters_age_explain) + "\n\n" + automatic,
                ID_FROM_YEAR to context.getString(R.string.filters_year_explain) + "\n\n" + automatic,
                ID_GLOBAL_HITS to context.getString(R.string.setting_global_hits_desc) + "\n\n" + automatic,
                ID_WATCH_SOURCE to context.getString(R.string.watch_source_explain),
                ID_WATCHED to context.getString(R.string.watched_explain),
                ID_ADULT to context.getString(R.string.filters_adult_desc),
                ID_RATING_AGE to context.getString(R.string.filters_rating_age_desc, Languages.countryName(Settings.region)),
                ID_GENRES to context.getString(R.string.genres_explain) + "\n\n" + automatic,
                ID_DUPLICATES to context.getString(R.string.filters_duplicates_desc),
            )
        }
    }
}

/**
 * One list: its own filters (each "Default (…)" or a value) on top, then its titles in order with what
 * happens to each one, recomputed whenever a filter changes.
 */
class ListContentsFragment : BaseSettingsFragment() {

    private val sourceKey get() = requireArguments().getString(ARG_KEY).orEmpty()
    private val source get() = Sources.available().firstOrNull { it.key == sourceKey }
    private var loadJob = 0
    private var filterActions: List<GuidedAction> = emptyList()
    /** The genre filter the titles were last loaded with. */
    private var shownGenres: GenreFilter? = null

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        source?.let { Sources.label(requireContext(), it) } ?: sourceKey,
        getString(R.string.explore_loading),
        getString(R.string.setting_explore_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val list = source ?: return
        filterActions = filterRows(list)
        actions += filterActions
        explanations.putAll(ListExplorerFragment.filterExplanations(requireContext()))
        shownGenres = Settings.filtersFor(list).genres
        loadTitles(list)
    }

    override fun onResume() {
        super.onResume()
        // Back from this list's genres
        val list = source ?: return
        val genres = Settings.filtersFor(list).genres
        if (shownGenres == null || genres == shownGenres) return
        shownGenres = genres
        filterRows(list).forEach { fresh -> updateDescription(fresh.id, fresh.description) }
        loadTitles(list)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_GENRES) add(parentFragmentManager, GenresFragment.newInstance(sourceKey))
    }

    /** A typed year becomes this list's age limit; emptied, its year limit goes back to the default. */
    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val list = source ?: return GuidedAction.ACTION_ID_CURRENT
        if (action.id != ID_FROM_YEAR) return GuidedAction.ACTION_ID_CURRENT
        val current = Settings.listFilters[list.key] ?: ListFilters()
        val typed = ListExplorerFragment.typedYear(requireContext(), action, current.maxAge)
        if (typed != null) Settings.listFilters = Settings.listFilters + (list.key to current.copy(maxAge = typed.getOrNull()))
        updateFilterRows(list)
        if (typed != null) loadTitles(list)
        return GuidedAction.ACTION_ID_CURRENT
    }

    /** The filter rows' values and options, in place (replacing them would break the sub-action expansion). */
    private fun updateFilterRows(list: ListSource) {
        for (fresh in filterRows(list)) {
            val row = findActionById(fresh.id) ?: continue
            row.description = fresh.description
            row.subActions = fresh.subActions
            notifyActionChanged(findActionPositionById(fresh.id))
        }
    }

    // --- The list's filters ---

    private fun filterRows(list: ListSource): List<GuidedAction> {
        val context = requireContext()
        val custom = Settings.listFilters[list.key] ?: ListFilters()
        fun default(label: String) = getString(R.string.list_filter_default, label)
        val defaultAge = Filters.ageLabel(context, Settings.defaultMaxAge(list))
        val defaultHits = Filters.onOff(context)[if (Settings.defaultGlobalHits(list)) 1 else 0]
        val defaultWatched = Filters.watchedLabel(context, Settings.watchedMode)
        val rows = mutableListOf(
            filterRow(ID_AGE, R.drawable.ic_act_schedule, R.string.filters_age_title,
                custom.maxAge?.let { Filters.ageLabel(context, it) } ?: default(defaultAge),
                listOf(default(defaultAge)) + ListExplorerFragment.ageOptions(custom.maxAge).map { Filters.ageLabel(context, it) }),
            yearRow(ID_FROM_YEAR, custom.maxAge),
        )
        // Global hits never applies to lists that are niche on purpose (anime)
        if (list.localMarketFilter) rows += filterRow(ID_HITS, R.drawable.ic_act_public, R.string.setting_global_hits_title,
            custom.globalHits?.let { Filters.onOff(context)[if (it) 1 else 0] } ?: default(defaultHits),
            listOf(default(defaultHits)) + Filters.onOff(context))
        // What to do with watched titles needs a watch history (chosen in the default filters)
        if (Settings.watchSource != null) rows += filterRow(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title,
            custom.watched?.let { Filters.watchedLabel(context, it) } ?: default(defaultWatched),
            listOf(default(defaultWatched)) + Filters.WATCHED_MODES.map { Filters.watchedLabel(context, it) })
        rows += action(ID_GENRES, R.drawable.ic_act_movie, getString(R.string.genres_title),
            custom.genres?.let { Filters.genresLabel(context, it) } ?: default(Filters.genresLabel(context, Settings.defaultGenres(list))))
            .hasNext(true).build()
        return rows
    }

    private fun filterRow(id: Long, icon: Int, title: Int, value: String, options: List<String>) =
        action(id, icon, getString(title), value)
            .subActions(options(id, options, value))
            .build()

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val list = source ?: return true
        val index = (action.id % SUB_ID_BASE).toInt()
        val current = Settings.listFilters[list.key] ?: ListFilters()
        // Option 0 is always "Default (…)"
        val updated = when (action.id / SUB_ID_BASE) {
            ID_AGE -> current.copy(maxAge = if (index == 0) null else ListExplorerFragment.ageOptions(current.maxAge)[index - 1])
            ID_HITS -> current.copy(globalHits = if (index == 0) null else index == 2)
            ID_WATCHED -> current.copy(watched = if (index == 0) null else Filters.WATCHED_MODES[index - 1])
            else -> return true
        }
        Settings.listFilters = Settings.listFilters + (list.key to updated)
        updateFilterRows(list)
        loadTitles(list)
        return true
    }

    // --- The titles ---

    private fun loadTitles(list: ListSource) {
        val context = requireContext().applicationContext
        val perList = Settings.titlesPerList
        val job = ++loadJob
        setGuidanceText(getString(R.string.explore_loading))
        background({
            val factory = WallpaperFactory(context, 1280)
            val plan = factory.plan(list, perList)
            val usable = plan.usable.take(perList).toSet()
            plan.usable.size to plan.candidates.mapIndexed { index, candidate ->
                val (title, year) = factory.catalog.summary(candidate.ref) ?: ("TMDB ${candidate.ref.tmdbId}" to "")
                val status = when {
                    candidate.exclusion != null -> exclusionText(context, candidate.exclusion)
                    plan.random -> context.getString(R.string.explore_random)
                    candidate.ref in usable -> context.getString(R.string.explore_used)
                    else -> context.getString(R.string.explore_reserve)
                }
                val watched = when (val watch = plan.statuses[candidate.ref]) {
                    is WatchStatus.Watched -> " · " + context.getString(R.string.explore_watched)
                    is WatchStatus.InProgress -> " · " + context.getString(R.string.explore_watching, watch.completed, watch.aired)
                    null -> ""
                }
                val icon = when {
                    candidate.exclusion != null -> R.drawable.ic_act_block
                    candidate.ref in usable || plan.random -> R.drawable.ic_act_check
                    else -> R.drawable.ic_act_list
                }
                Triple("${index + 1}. $title${if (year.isNotBlank()) " ($year)" else ""}", status + watched, icon)
            }
        }) { result ->
            if (job != loadJob) return@background
            result.onSuccess { (usableCount, rows) ->
                setGuidanceText(when {
                    // Fewer usable titles than asked for: all of them are used, whatever the order
                    usableCount <= perList -> getString(R.string.explore_summary_all, rows.size, usableCount)
                    Settings.selectionMode == Settings.SELECTION_RANDOM -> getString(R.string.explore_summary_random, rows.size, perList)
                    else -> getString(R.string.explore_summary, rows.size, perList)
                })
                setActions(filterActions + header(TITLES_HEADER, getString(R.string.explore_titles_header, rows.size)) + rows.mapIndexed { index, (title, status, icon) ->
                    action(TITLE_BASE + index, icon, title, status).multilineDescription(true).focusable(true).build()
                })
            }.onFailure {
                setGuidanceText(getString(R.string.preview_error, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun exclusionText(context: android.content.Context, exclusion: Exclusion) = context.getString(
        when (exclusion) {
            Exclusion.LOCAL_MARKET -> R.string.exclusion_local_market
            Exclusion.TOO_OLD -> R.string.exclusion_too_old
            Exclusion.ADULT -> R.string.exclusion_adult
            Exclusion.AGE_RATING -> R.string.exclusion_rating_age
            Exclusion.GENRE -> R.string.exclusion_genre
            Exclusion.NO_LOGO -> R.string.exclusion_no_logo
            Exclusion.NO_SYNOPSIS -> R.string.exclusion_no_synopsis
            Exclusion.WATCHED -> R.string.exclusion_watched
            Exclusion.NO_IMAGE -> R.string.exclusion_no_image
            Exclusion.DUPLICATE -> R.string.exclusion_duplicate
        }
    )

    companion object {
        private const val ARG_KEY = "list_key"
        // Same ids as the explorer's default filters, whose explanations they share
        private const val ID_AGE = 2L
        private const val ID_HITS = 3L
        private const val ID_WATCHED = 5L
        private const val ID_GENRES = 10L
        private const val ID_FROM_YEAR = 11L
        private const val TITLE_BASE = 100L
        private const val TITLES_HEADER = 99L

        fun newInstance(key: String) = ListContentsFragment().apply { arguments = Bundle().apply { putString(ARG_KEY, key) } }
    }
}
