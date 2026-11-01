package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Sofascreen, the screensaver (opened for [Profile.SCREENSAVER]): switching it on, whether it shows the
 * wallpapers' set or one with its own lists and look, the slideshow and the clock. After PR #13 by schumi2004.
 */
class ScreensaverFragment : BaseSettingsFragment() {

    /** The own set's progress, and "Try it" once there is a set; row by row, so an open list of choices stays open. */
    private val statusRefresher = object : Runnable {
        override fun run() {
            if (profile.separate) updateDescription(ID_GENERATE, generationStatus())
            findActionById(ID_TRY)?.let {
                val enabled = WallpaperStore(requireContext(), profile).current().isNotEmpty()
                if (it.isEnabled != enabled) {
                    it.isEnabled = enabled
                    notifyActionChanged(findActionPositionById(ID_TRY))
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.screensaver_title),
        getString(R.string.screensaver_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
        val context = requireContext()
        explanations[ID_SYSTEM] = listOfNotNull(
            getString(if (ScreensaverSetupFragment.hasSystemPicker(context)) R.string.screensaver_system_explain else R.string.screensaver_system_explain_steps),
            getString(R.string.screensaver_projectivy_tip).takeIf { Projectivy.isInstalled(context) },
        ).joinToString("\n\n")
        for (id in listOf(ID_OWN_LISTS, ID_OWN_LOOK, ID_GENERATE)) explanations[id] = getString(R.string.screensaver_own_explain)
        explanations[ID_POSITION] = getString(R.string.screensaver_clock_explain)
        explanations[ID_WEATHER] = getString(R.string.screensaver_weather_explain)
        explanations[ID_LOCATION] = getString(R.string.screensaver_weather_explain)
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val clockOn = Settings.screensaverClock != Settings.CLOCK_OFF
        val ownLists = Settings.screensaverOwnLists
        val ownLook = Settings.screensaverOwnLook
        val shownAt = Settings.screensaverShownAt
        return listOfNotNull(
            action(ID_SYSTEM, R.drawable.ic_act_tv, getString(R.string.screensaver_system_title),
                (if (shownAt == 0L) getString(R.string.screensaver_system_never) else getString(R.string.screensaver_system_shown, timeAgo(shownAt))) +
                    " · " + getString(if (ScreensaverSetupFragment.hasSystemPicker(context)) R.string.screensaver_system_opens_settings
                    else R.string.screensaver_system_opens_steps))
                .build(),
            action(ID_TRY, R.drawable.ic_act_preview, getString(R.string.screensaver_try), getString(R.string.screensaver_try_desc))
                .enabled(WallpaperStore(context, profile).current().isNotEmpty()).build(),

            header(ID_HEADER_SET, getString(R.string.screensaver_set_header)),
            choice(ID_OWN_LISTS, R.drawable.ic_act_movie, R.string.screensaver_lists_title, ownLabel(context, ownLists, lists = true),
                listOf(false, true).map { ownLabel(context, it, lists = true) }),
            action(ID_LISTS, R.drawable.ic_act_list, getString(R.string.screensaver_edit_lists), ListsFragment.summary(context, profile))
                .hasNext(true).build().takeIf { ownLists },
            choice(ID_OWN_LOOK, R.drawable.ic_act_tv, R.string.screensaver_look_title, ownLabel(context, ownLook, lists = false),
                listOf(false, true).map { ownLabel(context, it, lists = false) }),
            action(ID_DETAILS, R.drawable.ic_act_visibility, getString(R.string.screensaver_edit_look), DetailsFragment.summary(context, profile))
                .hasNext(true).build().takeIf { ownLook },
            // Its own set has its own state; the wallpapers' is on the main screen
            action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.screensaver_generate), generationStatus())
                .multilineDescription(true).build().takeIf { profile.separate },

            header(ID_HEADER_SLIDESHOW, getString(R.string.screensaver_slideshow_header)),
            choice(ID_SECONDS, R.drawable.ic_act_schedule, R.string.screensaver_seconds_title, secondsLabel(context, Settings.screensaverSeconds),
                Settings.SCREENSAVER_SECONDS_OPTIONS.map { secondsLabel(context, it) }),
            choice(ID_TRANSITION, R.drawable.ic_act_visibility, R.string.screensaver_transition_title,
                transitionLabel(context, Settings.screensaverTransition), Settings.TRANSITIONS.map { transitionLabel(context, it) }),
            action(ID_ZOOM, R.drawable.ic_act_search, getString(R.string.screensaver_zoom_title), getString(R.string.screensaver_zoom_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverZoom).build(),
            choice(ID_ORDER, R.drawable.ic_act_shuffle, R.string.screensaver_order_title, orderLabel(context, Settings.screensaverShuffle),
                listOf(true, false).map { orderLabel(context, it) }),
            choice(ID_BRIGHTNESS, R.drawable.ic_act_status, R.string.screensaver_brightness_title,
                getString(R.string.screensaver_brightness, Settings.screensaverBrightness),
                Settings.SCREENSAVER_BRIGHTNESS_OPTIONS.map { getString(R.string.screensaver_brightness, it) }),

            header(ID_HEADER_CLOCK, getString(R.string.screensaver_clock_header)),
            choice(ID_CLOCK, R.drawable.ic_act_schedule, R.string.screensaver_clock_title, clockLabel(context, Settings.screensaverClock),
                Settings.CLOCK_STYLES.map { clockLabel(context, it) }),
            action(ID_DATE, R.drawable.ic_act_schedule, getString(R.string.screensaver_date_title), null)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverDate).enabled(clockOn).build(),
            choice(ID_FORMAT, R.drawable.ic_act_schedule, R.string.screensaver_clock_format_title, formatLabel(context, Settings.screensaverClockFormat),
                Settings.CLOCK_FORMATS.map { formatLabel(context, it) }).also { it.isEnabled = clockOn },
            choice(ID_POSITION, R.drawable.ic_act_place, R.string.screensaver_clock_position_title, positionLabel(context, Settings.screensaverClockBottom),
                listOf(false, true).map { positionLabel(context, it) }).also { it.isEnabled = clockOn },
            action(ID_WEATHER, R.drawable.ic_act_status, getString(R.string.screensaver_weather_title),
                Settings.weatherPlace.ifEmpty { getString(R.string.screensaver_weather_no_place) })
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverWeather)
                .enabled(clockOn && Settings.weatherPlace.isNotEmpty()).build(),
            editable(ID_LOCATION, R.drawable.ic_act_search, getString(R.string.screensaver_location_title), "",
                Settings.weatherPlace.ifEmpty { getString(R.string.not_set) }),
        )
    }

    private fun refresh() = refreshActions(rows())

    override fun onResume() {
        super.onResume()
        // Back from its lists or details, or a set generated meanwhile ("Try it")
        refresh()
        handler.post(statusRefresher)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_TRY -> startActivity(Intent(requireContext(), SofascreenActivity::class.java))
            ID_LISTS -> open(ListsFragment())
            ID_DETAILS -> open(DetailsFragment())
            ID_GENERATE -> {
                if (Sources.selected(profile).isEmpty()) {
                    Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
                    return
                }
                GenerateWallpapersWorker.runNow(requireContext(), profile, replace = true)
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refresh()
            }
            ID_ZOOM -> Settings.screensaverZoom = action.isChecked
            ID_DATE -> Settings.screensaverDate = action.isChecked
            ID_WEATHER -> Settings.screensaverWeather = action.isChecked
            ID_SYSTEM -> ScreensaverSetupFragment.open(this)
        }
    }

    /** A place typed for the weather: looked up, and the weather switched on when found; empty removes it. */
    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id != ID_LOCATION) return GuidedAction.ACTION_ID_CURRENT
        val typed = action.editDescription?.toString().orEmpty().trim()
        action.editDescription = ""
        if (typed.isEmpty()) {
            Settings.setWeatherPlace(null)
            Settings.screensaverWeather = false
            refresh()
            return GuidedAction.ACTION_ID_CURRENT
        }
        background({ Weather.find(typed) }) { result ->
            val place = result.getOrNull()
            val message = when {
                result.isFailure -> getString(R.string.screensaver_location_failed, result.exceptionOrNull()?.message)
                place == null -> getString(R.string.screensaver_location_not_found, typed)
                else -> place.name.also {
                    Settings.setWeatherPlace(place)
                    Settings.screensaverWeather = true
                }
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            refresh()
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_OWN_LISTS -> setOwn { Settings.screensaverOwnLists = index == 1 }
            ID_OWN_LOOK -> setOwn { Settings.screensaverOwnLook = index == 1 }
            ID_SECONDS -> Settings.screensaverSeconds = Settings.SCREENSAVER_SECONDS_OPTIONS[index]
            ID_TRANSITION -> Settings.screensaverTransition = Settings.TRANSITIONS[index]
            ID_ORDER -> Settings.screensaverShuffle = index == 0
            ID_CLOCK -> Settings.screensaverClock = Settings.CLOCK_STYLES[index]
            ID_POSITION -> Settings.screensaverClockBottom = index == 1
            ID_FORMAT -> Settings.screensaverClockFormat = Settings.CLOCK_FORMATS[index]
            ID_BRIGHTNESS -> Settings.screensaverBrightness = Settings.SCREENSAVER_BRIGHTNESS_OPTIONS[index]
        }
        refresh() // the clock's rows follow the clock, the lists and details rows their choice
        return true
    }

    /**
     * Turn the screensaver's own lists or look on or off. Back to the wallpapers' set, its own goes (disk),
     * and it is made again from scratch if it comes back; a new one is made when the settings close.
     */
    private fun setOwn(change: () -> Unit) {
        change()
        if (!profile.separate) {
            WallpaperStore.deleteScreensaverSet(requireContext())
            profile.lastGeneration = 0L
            profile.lastSignature = ""
            profile.lastError = ""
        }
    }

    companion object {
        private const val ID_SYSTEM = 1L
        private const val ID_TRY = 2L
        private const val ID_HEADER_SET = 3L
        private const val ID_OWN_LISTS = 4L
        private const val ID_LISTS = 5L
        private const val ID_OWN_LOOK = 6L
        private const val ID_DETAILS = 7L
        private const val ID_GENERATE = 8L
        private const val ID_HEADER_SLIDESHOW = 9L
        private const val ID_SECONDS = 10L
        private const val ID_TRANSITION = 11L
        private const val ID_ZOOM = 12L
        private const val ID_ORDER = 13L
        private const val ID_BRIGHTNESS = 14L
        private const val ID_HEADER_CLOCK = 15L
        private const val ID_CLOCK = 16L
        private const val ID_DATE = 17L
        private const val ID_FORMAT = 18L
        private const val ID_POSITION = 19L
        private const val ID_WEATHER = 20L
        private const val ID_LOCATION = 21L

        /** "com.rfmarcos.sofadrop/com.rfmarcos.sofadrop.SofascreenService", for the adb command. */
        fun component(context: Context) = "${context.packageName}/${SofascreenService::class.java.name}"

        private fun ownLabel(context: Context, own: Boolean, lists: Boolean) = context.getString(
            when {
                lists && own -> R.string.screensaver_own_lists
                lists -> R.string.screensaver_same_as_wallpapers
                own -> R.string.screensaver_own_look
                else -> R.string.screensaver_same_look
            }
        )

        fun secondsLabel(context: Context, seconds: Int) =
            if (seconds < 60) context.getString(R.string.screensaver_seconds, seconds)
            else context.getString(R.string.screensaver_minutes, seconds / 60)

        fun transitionLabel(context: Context, transition: String) = context.getString(
            when (transition) {
                Settings.TRANSITION_BLACK -> R.string.transition_black
                Settings.TRANSITION_SLIDE -> R.string.transition_slide
                Settings.TRANSITION_ZOOM -> R.string.transition_zoom
                Settings.TRANSITION_RANDOM -> R.string.transition_random
                else -> R.string.transition_fade
            }
        )

        fun clockLabel(context: Context, style: String) = context.getString(
            when (style) {
                Settings.CLOCK_STACKED -> R.string.clock_stacked
                Settings.CLOCK_LINE -> R.string.clock_line
                else -> R.string.clock_off
            }
        )

        private fun orderLabel(context: Context, shuffle: Boolean) =
            context.getString(if (shuffle) R.string.screensaver_order_random else R.string.screensaver_order_set)

        /** "System (24 hours)", "24 hours", "12 hours". */
        private fun formatLabel(context: Context, format: String) = when (format) {
            Settings.CLOCK_FORMAT_24 -> context.getString(R.string.clock_format_24)
            Settings.CLOCK_FORMAT_12 -> context.getString(R.string.clock_format_12)
            else -> context.getString(R.string.clock_format_system, context.getString(
                if (android.text.format.DateFormat.is24HourFormat(context)) R.string.clock_format_24 else R.string.clock_format_12))
        }

        private fun positionLabel(context: Context, bottom: Boolean) =
            context.getString(if (bottom) R.string.clock_bottom else R.string.clock_top)

        /** "Own lists · 30 s · Crossfade · clock" for the main screen. */
        fun summary(context: Context): String = listOfNotNull(
            context.getString(
                when {
                    Settings.screensaverOwnLists && Settings.screensaverOwnLook -> R.string.screensaver_summary_own_both
                    Settings.screensaverOwnLists -> R.string.screensaver_summary_own_lists
                    Settings.screensaverOwnLook -> R.string.screensaver_summary_own_look
                    else -> R.string.screensaver_summary_same
                }
            ),
            secondsLabel(context, Settings.screensaverSeconds),
            transitionLabel(context, Settings.screensaverTransition),
            context.getString(R.string.screensaver_summary_clock).takeIf { Settings.screensaverClock != Settings.CLOCK_OFF },
            context.getString(R.string.screensaver_summary_weather).takeIf {
                Settings.screensaverClock != Settings.CLOCK_OFF && Settings.screensaverWeather && Settings.weatherPlace.isNotEmpty()
            },
        ).joinToString(" · ")
    }
}
