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
 * The line under the title: which details it shows and which platforms' scores, in order. Each
 * platform row opens a list of positions (or "don't show"), so any move takes a couple of clicks.
 * Scores use the room the details leave.
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
        val items = Settings.HEADER_ITEMS.mapIndexed { index, item ->
            // The badge needs a watch history; kept visible (disabled) so rows don't jump
            val usable = item != Settings.HEADER_WATCHED || Settings.watchSource != null
            checkbox(ID_ITEM + index, itemTitle(context, item), if (usable) null else getString(R.string.header_watched_needs),
                Settings.showsInHeader(item)).enabled(usable).build()
        }
        val source = action(ID_SOURCE, R.drawable.ic_act_info, getString(R.string.ratings_source_title), sourceLabel(context))
            .hasNext(true).build()
        val shown = shownPlatforms()
        // Rows go by position (ids too), so a move updates them in place and keeps leanback's state
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
        return listOf(header(ID_DETAILS_HEADER, getString(R.string.header_show))) + items +
            header(ID_RATINGS_HEADER, getString(R.string.ratings_header)) + source + platforms
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_SOURCE -> add(parentFragmentManager, AccountsFragment.newInstance(SourceProvider.MDBLIST))
            in ID_ITEM until ID_ITEM + Settings.HEADER_ITEMS.size -> {
                val item = Settings.HEADER_ITEMS[(action.id - ID_ITEM).toInt()]
                Settings.headerHidden = if (action.isChecked) Settings.headerHidden - item else Settings.headerHidden + item
                schedulePreview()
            }
        }
    }

    /** A position for a platform: it is taken out and put back there (or left out). */
    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val platform = platformRows().getOrNull((action.id / SUB_ID_BASE - ID_PLATFORM).toInt()) ?: return true
        val choice = (action.id % SUB_ID_BASE).toInt()
        val shown = shownPlatforms() - platform
        val order = if (choice == 0) shown else shown.toMutableList().apply { add((choice - 1).coerceAtMost(size), platform) }
        // Chosen scores that need MDBList stay chosen while it's disconnected
        Settings.ratingSources = order + Settings.ratingSources.filterNot(::available)
        collapseSubActions()
        refreshActions(rows()) { HeaderFragment() }
        // Focus follows the platform to its new row, once the options have closed
        val row = findActionPositionById(ID_PLATFORM + platformRows().indexOf(platform))
        handler.postDelayed({ if (isAdded) setSelectedActionPosition(row) }, 150)
        schedulePreview()
        return true
    }

    private fun available(platform: RatingSource) = !platform.needsMdbList || Settings.mdbListAvailable

    private fun shownPlatforms() = Settings.ratingSources.filter(::available)

    /** Shown platforms in order, then the other available ones, then those that need MDBList. */
    private fun platformRows(): List<RatingSource> {
        val shown = shownPlatforms()
        val rest = RatingSource.entries - shown.toSet()
        return shown + rest.filter(::available) + rest.filterNot(::available)
    }

    companion object {
        private const val ID_SOURCE = 1L
        private const val ID_DETAILS_HEADER = 2L
        private const val ID_RATINGS_HEADER = 3L
        private const val ID_ITEM = 10L
        private const val ID_PLATFORM = 100L

        private fun itemTitle(context: Context, item: String, short: Boolean = false) = context.getString(
            when (item) {
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

        /** "IMDb · RT · year · genres" for the details screen. */
        fun summary(context: Context): String =
            (Settings.ratingSources.map { it.shortTitle(context) } +
                Settings.HEADER_ITEMS.filter(Settings::showsInHeader).map { itemTitle(context, it, short = true) })
                .joinToString(" · ").ifEmpty { context.getString(R.string.header_empty) }
    }
}
