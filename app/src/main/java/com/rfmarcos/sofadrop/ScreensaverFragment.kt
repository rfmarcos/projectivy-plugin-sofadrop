package com.rfmarcos.sofadrop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Sofascreen, the screensaver: try it, how wallpapers change, the clock, and where to switch it on. */
class ScreensaverFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.screensaver_title),
        getString(R.string.screensaver_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
        explanations[ID_SYSTEM] = getString(R.string.screensaver_system_explain, component(requireContext()))
        explanations[ID_INFO] = getString(R.string.screensaver_info_explain)
        for (id in listOf(ID_STYLE, ID_SYNOPSIS, ID_CAST, ID_FOOTER)) explanations[id] = getString(R.string.screensaver_content_explain)
        explanations[ID_WEATHER] = getString(R.string.screensaver_weather_explain)
        explanations[ID_LOCATION] = getString(R.string.screensaver_weather_explain)
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val clockOn = Settings.screensaverClock != Settings.CLOCK_OFF
        // What the screensaver's own copies can change, for the style they have
        val text = Settings.screensaverEffectiveStyle != Settings.STYLE_BACKDROP
        val details = Settings.screensaverEffectiveStyle == Settings.STYLE_FULL
        return listOf(
            action(ID_TRY, R.drawable.ic_act_preview, getString(R.string.screensaver_try), getString(R.string.screensaver_try_desc))
                .enabled(WallpaperStore(context).current().isNotEmpty()).build(),
            choice(ID_SECONDS, R.drawable.ic_act_schedule, R.string.screensaver_seconds_title, secondsLabel(context, Settings.screensaverSeconds),
                Settings.SCREENSAVER_SECONDS_OPTIONS.map { secondsLabel(context, it) }),
            choice(ID_TRANSITION, R.drawable.ic_act_visibility, R.string.screensaver_transition_title,
                transitionLabel(context, Settings.screensaverTransition), Settings.TRANSITIONS.map { transitionLabel(context, it) }),
            action(ID_ZOOM, R.drawable.ic_act_search, getString(R.string.screensaver_zoom_title), getString(R.string.screensaver_zoom_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverZoom).build(),
            choice(ID_STYLE, R.drawable.ic_act_visibility, R.string.style_title, styleLabel(context, Settings.screensaverStyle),
                (listOf("") + Settings.screensaverStyles).map { styleLabel(context, it) }).also { it.isEnabled = Settings.screensaverStyles.size > 1 },
            choice(ID_INFO, R.drawable.ic_act_place, R.string.screensaver_info_title, infoLabel(context, Settings.screensaverInfoBottom),
                listOf(false, true).map { infoLabel(context, it) }).also { it.isEnabled = text },
            choice(ID_SYNOPSIS, R.drawable.ic_act_count, R.string.synopsis_title, synopsisLabel(context, Settings.screensaverOverviewLines),
                (listOf(-1) + Settings.OVERVIEW_OPTIONS).map { synopsisLabel(context, it) }).also { it.isEnabled = details },
            action(ID_CAST, R.drawable.ic_act_account, getString(R.string.cast_title), null)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverCast).enabled(details && Settings.showCast).build(),
            action(ID_FOOTER, R.drawable.ic_act_tv, getString(R.string.screensaver_footer_title), null)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverFooter).enabled(details).build(),
            choice(ID_ORDER, R.drawable.ic_act_shuffle, R.string.screensaver_order_title, orderLabel(context, Settings.screensaverShuffle),
                listOf(true, false).map { orderLabel(context, it) }),
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
            choice(ID_BRIGHTNESS, R.drawable.ic_act_status, R.string.screensaver_brightness_title,
                getString(R.string.screensaver_brightness, Settings.screensaverBrightness),
                Settings.SCREENSAVER_BRIGHTNESS_OPTIONS.map { getString(R.string.screensaver_brightness, it) }),
            action(ID_SYSTEM, R.drawable.ic_act_tv, getString(R.string.screensaver_system_title), getString(R.string.screensaver_system_desc)).build(),
        )
    }

    private fun refresh() = refreshActions(rows()) { ScreensaverFragment() }

    override fun onResume() {
        super.onResume()
        refresh() // a set may have been generated meanwhile ("Try it")
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_TRY -> startActivity(Intent(requireContext(), SofascreenActivity::class.java))
            ID_ZOOM -> Settings.screensaverZoom = action.isChecked
            ID_DATE -> Settings.screensaverDate = action.isChecked
            ID_WEATHER -> Settings.screensaverWeather = action.isChecked
            ID_CAST -> Settings.screensaverCast = action.isChecked
            ID_FOOTER -> Settings.screensaverFooter = action.isChecked
            ID_SYSTEM -> try {
                startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Google TV has no screensaver picker for other apps: the left pane gives the adb command
                Toast.makeText(requireContext(), R.string.screensaver_system_missing, Toast.LENGTH_LONG).show()
            }
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
            ID_SECONDS -> Settings.screensaverSeconds = Settings.SCREENSAVER_SECONDS_OPTIONS[index]
            ID_TRANSITION -> Settings.screensaverTransition = Settings.TRANSITIONS[index]
            ID_ORDER -> Settings.screensaverShuffle = index == 0
            ID_CLOCK -> Settings.screensaverClock = Settings.CLOCK_STYLES[index]
            ID_POSITION -> Settings.screensaverClockBottom = index == 1
            ID_FORMAT -> Settings.screensaverClockFormat = Settings.CLOCK_FORMATS[index]
            ID_INFO -> Settings.screensaverInfoBottom = index == 1
            ID_STYLE -> Settings.screensaverStyle = (listOf("") + Settings.screensaverStyles)[index]
            ID_SYNOPSIS -> Settings.screensaverOverviewLines = (listOf(-1) + Settings.OVERVIEW_OPTIONS)[index]
            ID_BRIGHTNESS -> Settings.screensaverBrightness = Settings.SCREENSAVER_BRIGHTNESS_OPTIONS[index]
        }
        refresh() // the date and position rows follow the clock, the content rows the style
        return true
    }

    companion object {
        private const val ID_TRY = 1L
        private const val ID_SECONDS = 2L
        private const val ID_TRANSITION = 3L
        private const val ID_ZOOM = 4L
        private const val ID_ORDER = 5L
        private const val ID_CLOCK = 6L
        private const val ID_DATE = 7L
        private const val ID_POSITION = 8L
        private const val ID_FORMAT = 18L
        private const val ID_BRIGHTNESS = 9L
        private const val ID_SYSTEM = 10L
        private const val ID_INFO = 11L
        private const val ID_WEATHER = 12L
        private const val ID_LOCATION = 13L
        private const val ID_STYLE = 14L
        private const val ID_SYNOPSIS = 15L
        private const val ID_CAST = 16L
        private const val ID_FOOTER = 17L

        /** "com.rfmarcos.sofadrop/com.rfmarcos.sofadrop.SofascreenService", for the adb command. */
        fun component(context: Context) = "${context.packageName}/${SofascreenService::class.java.name}"

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

        /** The screensaver's style; "" is the wallpapers' one. */
        private fun styleLabel(context: Context, style: String) =
            if (style.isEmpty()) context.getString(R.string.screensaver_same, DetailsFragment.styleLabel(context, Settings.style))
            else DetailsFragment.styleLabel(context, style)

        /** The screensaver's synopsis; -1 is the wallpapers' one. */
        private fun synopsisLabel(context: Context, lines: Int) =
            if (lines < 0) context.getString(R.string.screensaver_same, DetailsFragment.synopsisLabel(context, Settings.overviewLines))
            else DetailsFragment.synopsisLabel(context, lines)

        private fun infoLabel(context: Context, bottom: Boolean) =
            context.getString(if (bottom) R.string.screensaver_info_bottom else R.string.screensaver_info_top)

        /** "System (24 hours)", "24 hours", "12 hours". */
        private fun formatLabel(context: Context, format: String) = when (format) {
            Settings.CLOCK_FORMAT_24 -> context.getString(R.string.clock_format_24)
            Settings.CLOCK_FORMAT_12 -> context.getString(R.string.clock_format_12)
            else -> context.getString(R.string.clock_format_system, context.getString(
                if (android.text.format.DateFormat.is24HourFormat(context)) R.string.clock_format_24 else R.string.clock_format_12))
        }

        private fun positionLabel(context: Context, bottom: Boolean) =
            context.getString(if (bottom) R.string.clock_bottom else R.string.clock_top)

        /** "30 s · Crossfade · clock" for the main screen. */
        fun summary(context: Context): String = listOfNotNull(
            secondsLabel(context, Settings.screensaverSeconds),
            transitionLabel(context, Settings.screensaverTransition),
            context.getString(R.string.screensaver_summary_clock).takeIf { Settings.screensaverClock != Settings.CLOCK_OFF },
            context.getString(R.string.screensaver_summary_weather).takeIf {
                Settings.screensaverClock != Settings.CLOCK_OFF && Settings.screensaverWeather && Settings.weatherPlace.isNotEmpty()
            },
        ).joinToString(" · ")
    }
}
