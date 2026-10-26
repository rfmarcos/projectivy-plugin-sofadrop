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
 * The line under the logo: its blocks in order (ratings, watched badge, year
 * with its age certification, runtime, genres), how many ratings always stay, and which platforms'
 * scores, in order. Block and platform rows open a list of positions (or "don't show"), so any move
 * takes a couple of clicks.
 */
class HeaderFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.header_title),
        getString(R.string.header_fit_desc),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // What gets dropped when the line is full, always in view under the preview
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = View.VISIBLE
    }

    // Back from Accounts: MDBList's platforms become available (or not)
    override fun onResume() {
        super.onResume()
        refreshActions(rows()) { HeaderFragment() }
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val shownBlocks = shownBlocks()
        // Rows go by position (ids too), so a move updates them in place and keeps leanback's state
        val blocks = blockRows().mapIndexed { index, block ->
            val position = shownBlocks.indexOf(block)
            val builder = GuidedAction.Builder(context).id(ID_BLOCK + index).icon(ColorDrawable(Color.TRANSPARENT))
                .title(blockTitle(context, block))
            // The badge needs a watch history; kept visible (disabled) so rows don't jump
            if (block == Settings.HEADER_WATCHED && Settings.watchSource == null) {
                return@mapIndexed builder.description(getString(R.string.header_watched_needs)).enabled(false).build()
            }
            // Ratings are hidden by choosing no platform below
            val canHide = block != Settings.HEADER_RATINGS
            val slots = if (position >= 0) shownBlocks.size else shownBlocks.size + 1
            val positions = (1..slots).map {
                getString(if (position < 0 && it == slots) R.string.ratings_option_last else R.string.ratings_option_position, it)
            }
            val labels = if (canHide) listOf(getString(R.string.ratings_dont_show)) + positions else positions
            val current = if (position >= 0) positions[position] else labels.first()
            builder.description(if (position >= 0) getString(R.string.ratings_position, position + 1, shownBlocks.size) else getString(R.string.ratings_hidden))
                .subActions(options(ID_BLOCK + index, labels, current)).build()
        }
        val certification = checkbox(ID_CERTIFICATION, getString(R.string.header_certification),
            getString(R.string.header_certification_desc, Languages.countryName(Settings.region)),
            Settings.showsInHeader(Settings.HEADER_CERTIFICATION)).build()
        val minLabels = Settings.MIN_RATINGS_OPTIONS.map { it.toString() }
        val minRatings = action(ID_MIN_RATINGS, R.drawable.ic_act_count, getString(R.string.header_min_ratings),
            getString(R.string.header_min_ratings_desc, Settings.minRatings))
            .subActions(options(ID_MIN_RATINGS, minLabels, Settings.minRatings.toString())).build()

        val source = action(ID_SOURCE, R.drawable.ic_act_info, getString(R.string.ratings_source_title), sourceLabel(context))
            .hasNext(true).build()
        val shown = shownPlatforms()
        val platforms = platformRows().mapIndexed { index, platform ->
            val position = shown.indexOf(platform)
            val builder = GuidedAction.Builder(context).id(ID_PLATFORM + index).icon(ColorDrawable(Color.TRANSPARENT))
                .title(platform.title(context))
            if (!available(platform)) return@mapIndexed builder.description(getString(R.string.ratings_needs_mdblist)).enabled(false).build()
            val slots = if (position >= 0) shown.size else shown.size + 1
            val labels = listOf(getString(R.string.ratings_dont_show)) + (1..slots).map {
                getString(if (position < 0 && it == slots) R.string.ratings_option_last else R.string.ratings_option_position, it)
            }
            builder.description(if (position >= 0) getString(R.string.ratings_position, position + 1, shown.size) else getString(R.string.ratings_hidden))
                .subActions(options(ID_PLATFORM + index, labels, labels[position + 1])).build()
        }
        return listOf(header(ID_BLOCKS_HEADER, getString(R.string.header_show))) + blocks + certification + minRatings +
            header(ID_RATINGS_HEADER, getString(R.string.ratings_header)) + source + platforms
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_SOURCE -> add(parentFragmentManager, AccountsFragment.newInstance(SourceProvider.MDBLIST))
            ID_CERTIFICATION -> {
                Settings.headerHidden = if (action.isChecked) Settings.headerHidden - Settings.HEADER_CERTIFICATION
                else Settings.headerHidden + Settings.HEADER_CERTIFICATION
                schedulePreview()
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val choice = (action.id % SUB_ID_BASE).toInt()
        when {
            parent == ID_MIN_RATINGS -> Settings.minRatings = Settings.MIN_RATINGS_OPTIONS[choice]
            parent >= ID_PLATFORM -> return movePlatform(platformRows().getOrNull((parent - ID_PLATFORM).toInt()) ?: return true, choice)
            parent >= ID_BLOCK -> return moveBlock(blockRows().getOrNull((parent - ID_BLOCK).toInt()) ?: return true, choice)
        }
        collapseSubActions()
        refreshActions(rows()) { HeaderFragment() }
        schedulePreview()
        return true
    }

    /** A position for a block: it is taken out and put back there (or hidden). */
    private fun moveBlock(block: String, choice: Int): Boolean {
        // The ratings row has no "don't show"
        val position = if (block == Settings.HEADER_RATINGS) choice + 1 else choice
        val shown = shownBlocks() - block
        if (position == 0) {
            Settings.headerHidden = Settings.headerHidden + block
        } else {
            Settings.headerHidden = Settings.headerHidden - block
            val order = shown.toMutableList().apply { add((position - 1).coerceAtMost(size), block) }
            // Hidden blocks keep their place after the shown ones
            Settings.headerOrder = order + (Settings.headerOrder - order.toSet())
        }
        afterMove(ID_BLOCK + blockRows().indexOf(block))
        return true
    }

    /** A position for a platform: it is taken out and put back there (or left out). */
    private fun movePlatform(platform: RatingSource, choice: Int): Boolean {
        val shown = shownPlatforms() - platform
        val order = if (choice == 0) shown else shown.toMutableList().apply { add((choice - 1).coerceAtMost(size), platform) }
        // Chosen scores that need MDBList stay chosen while it's disconnected
        Settings.ratingSources = order + Settings.ratingSources.filterNot(::available)
        afterMove(ID_PLATFORM + platformRows().indexOf(platform))
        return true
    }

    /** Focus follows the moved row to its new place, once the options have closed. */
    private fun afterMove(rowId: Long) {
        collapseSubActions()
        refreshActions(rows()) { HeaderFragment() }
        val row = findActionPositionById(rowId)
        handler.postDelayed({ if (isAdded) setSelectedActionPosition(row) }, 150)
        schedulePreview()
    }

    private fun available(platform: RatingSource) = !platform.needsMdbList || Settings.mdbListAvailable

    private fun shownPlatforms() = Settings.ratingSources.filter(::available)

    /** Shown platforms in order, then the other available ones, then those that need MDBList. */
    private fun platformRows(): List<RatingSource> {
        val shown = shownPlatforms()
        val rest = RatingSource.entries - shown.toSet()
        return shown + rest.filter(::available) + rest.filterNot(::available)
    }

    /** Blocks drawn, in order (the badge only with a watch history). */
    private fun shownBlocks() = Settings.headerOrder.filter { block ->
        (block == Settings.HEADER_RATINGS || Settings.showsInHeader(block)) && (block != Settings.HEADER_WATCHED || Settings.watchSource != null)
    }

    /** Shown blocks in order, then the hidden ones. */
    private fun blockRows(): List<String> = shownBlocks().let { it + (Settings.headerOrder - it.toSet()) }

    companion object {
        private const val ID_SOURCE = 1L
        private const val ID_BLOCKS_HEADER = 2L
        private const val ID_RATINGS_HEADER = 3L
        private const val ID_CERTIFICATION = 5L
        private const val ID_MIN_RATINGS = 6L
        private const val ID_BLOCK = 10L
        private const val ID_PLATFORM = 100L

        private fun blockTitle(context: Context, block: String, short: Boolean = false) = context.getString(
            when (block) {
                Settings.HEADER_RATINGS -> if (short) R.string.header_ratings_short else R.string.header_ratings
                Settings.HEADER_YEAR -> if (short) R.string.header_year_short else R.string.header_year
                Settings.HEADER_RUNTIME -> if (short) R.string.header_runtime_short else R.string.header_runtime
                Settings.HEADER_GENRES -> if (short) R.string.header_genres_short else R.string.header_genres
                else -> if (short) R.string.header_watched_short else R.string.header_watched
            }
        )

        fun sourceLabel(context: Context): String = when {
            !Settings.mdbListAvailable -> context.getString(R.string.ratings_source_tmdb)
            Settings.mdbListUser.isNotBlank() -> context.getString(R.string.ratings_source_mdblist_user, Settings.mdbListUser)
            else -> context.getString(R.string.ratings_source_mdblist)
        }

        /** "IMDb RT · year [age] · runtime" for the details screen, in the header's order. */
        fun summary(context: Context): String {
            val parts = Settings.headerOrder.mapNotNull { block ->
                when (block) {
                    Settings.HEADER_RATINGS -> Settings.ratingSources.joinToString(" ") { it.shortTitle(context) }.ifEmpty { null }
                    Settings.HEADER_WATCHED -> blockTitle(context, block, short = true).takeIf { Settings.showsInHeader(block) && Settings.watchSource != null }
                    Settings.HEADER_YEAR -> listOfNotNull(
                        blockTitle(context, block, short = true).takeIf { Settings.showsInHeader(block) },
                        context.getString(R.string.header_certification_short).takeIf { Settings.showsInHeader(Settings.HEADER_CERTIFICATION) },
                    ).joinToString(" ").ifEmpty { null }
                    else -> blockTitle(context, block, short = true).takeIf { Settings.showsInHeader(block) }
                }
            }
            return parts.joinToString(" · ").ifEmpty { context.getString(R.string.header_empty) }
        }
    }
}
