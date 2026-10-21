package com.rfmarcos.tmdbwallpapers

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

class SettingsFragment : GuidedStepSupportFragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val statusRefresher = object : Runnable {
        override fun run() {
            updateDescription(ID_STATUS, statusText())
            findActionById(ID_PREVIEW)?.let {
                it.isEnabled = WallpaperStore(requireContext()).current().isNotEmpty()
                notifyActionChanged(findActionPositionById(ID_PREVIEW))
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        getString(R.string.plugin_name),
        "v${BuildConfig.VERSION_NAME}\n\n${getString(R.string.plugin_description)}",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.drawable.ic_plugin),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        Settings.init(requireContext())
        val context = requireContext()

        actions += GuidedAction.Builder(context).id(ID_STATUS)
            .title(R.string.status_title).description(statusText())
            .multilineDescription(true).focusable(false).build()

        actions += GuidedAction.Builder(context).id(ID_GENERATE)
            .title(R.string.action_generate_now).description(R.string.action_generate_now_desc).build()

        actions += GuidedAction.Builder(context).id(ID_PREVIEW)
            .title(R.string.action_preview).description(R.string.action_preview_desc)
            .enabled(WallpaperStore(context).current().isNotEmpty()).build()

        actions += GuidedAction.Builder(context).id(ID_TOKEN)
            .title(R.string.setting_token_title).description(tokenDescription())
            .editDescription(Settings.token).descriptionEditable(true)
            .descriptionEditInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            .build()

        actions += choice(ID_LANGUAGE, R.string.setting_language_title, Languages.displayName(Settings.language),
            Languages.OPTIONS.values.toList())

        actions += choice(ID_REGION, R.string.setting_region_title, regionDescription(),
            listOf(getString(R.string.region_auto, Languages.regionOf(Settings.language))) +
                Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" })

        actions += choice(ID_CONTENT, R.string.setting_content_title, contentLabel(Settings.content),
            ContentType.entries.map(::contentLabel))

        actions += choice(ID_COUNT, R.string.setting_count_title, Settings.titlesPerType.toString(),
            Settings.COUNT_OPTIONS.map(Int::toString))

        actions += choice(ID_REFRESH, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
            Settings.REFRESH_OPTIONS.map(::refreshLabel))

        actions += choice(ID_RESOLUTION, R.string.setting_resolution_title, resolutionLabel(Settings.outputWidth),
            Settings.WIDTH_OPTIONS.map(::resolutionLabel))

        actions += GuidedAction.Builder(context).id(ID_GLOBAL_HITS)
            .title(R.string.setting_global_hits_title).description(R.string.setting_global_hits_desc)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.globalHitsOnly).build()

        actions += GuidedAction.Builder(context).id(ID_PROVIDERS)
            .title(R.string.setting_providers_title).description(R.string.setting_providers_desc)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.showProviders).build()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusRefresher)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        // Apply the new settings once the user leaves the screen
        val context = requireContext()
        GenerateWallpapersWorker.schedule(context, settingsChanged = true)
        if (Settings.token.isNotBlank() && Settings.signature != Settings.lastSignature) {
            GenerateWallpapersWorker.runNow(context, replace = true)
        }
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_GENERATE -> {
                GenerateWallpapersWorker.runNow(requireContext(), replace = true)
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                updateDescription(ID_STATUS, statusText())
            }
            ID_PREVIEW -> startActivity(Intent(requireContext(), PreviewActivity::class.java))
            ID_TOKEN -> {
                Settings.token = action.editDescription?.toString().orEmpty()
                action.editDescription = Settings.token
                updateDescription(ID_TOKEN, tokenDescription())
            }
            ID_GLOBAL_HITS -> Settings.globalHitsOnly = action.isChecked
            ID_PROVIDERS -> Settings.showProviders = action.isChecked
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val index = (action.id % SUB_ID_BASE).toInt()
        when (parent) {
            ID_LANGUAGE -> {
                Settings.language = Languages.OPTIONS.keys.elementAt(index)
                updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
                updateDescription(ID_REGION, regionDescription())
            }
            ID_REGION -> {
                Settings.regionOverride = if (index == 0) "" else Languages.REGIONS[index - 1]
                updateDescription(ID_REGION, regionDescription())
            }
            ID_CONTENT -> {
                Settings.content = ContentType.entries[index]
                updateDescription(ID_CONTENT, contentLabel(Settings.content))
            }
            ID_COUNT -> {
                Settings.titlesPerType = Settings.COUNT_OPTIONS[index]
                updateDescription(ID_COUNT, Settings.titlesPerType.toString())
            }
            ID_REFRESH -> {
                Settings.refreshHours = Settings.REFRESH_OPTIONS[index]
                updateDescription(ID_REFRESH, refreshLabel(Settings.refreshHours))
            }
            ID_RESOLUTION -> {
                Settings.outputWidth = Settings.WIDTH_OPTIONS[index]
                updateDescription(ID_RESOLUTION, resolutionLabel(Settings.outputWidth))
            }
        }
        return true
    }

    // --- Helpers ---

    private fun choice(id: Long, title: Int, current: String, options: List<String>): GuidedAction {
        val subActions = options.mapIndexed { index, label ->
            GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).build()
        }
        return GuidedAction.Builder(requireContext()).id(id).title(title).description(current)
            .subActions(subActions).build()
    }

    private fun updateDescription(id: Long, description: CharSequence) {
        val action = findActionById(id) ?: return
        action.description = description
        notifyActionChanged(findActionPositionById(id))
    }

    private fun statusText(): String {
        val lastError = Settings.lastError
        val count = WallpaperStore(requireContext()).current().size
        return when {
            Settings.generating -> getString(R.string.status_generating)
            lastError.isNotEmpty() -> getString(R.string.status_error, lastError)
            count == 0 -> getString(R.string.status_never)
            else -> getString(
                R.string.status_ok, count,
                DateUtils.getRelativeTimeSpanString(Settings.lastGeneration, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
    }

    private fun tokenDescription(): String {
        val token = Settings.token
        return if (token.isBlank()) getString(R.string.setting_token_missing)
        else getString(R.string.setting_token_set, token.takeLast(4))
    }

    private fun regionDescription(): String {
        val override = Settings.regionOverride
        return if (override.isEmpty()) getString(R.string.region_auto, Languages.regionOf(Settings.language))
        else "${Languages.countryName(override)} ($override)"
    }

    private fun contentLabel(type: ContentType) = getString(
        when (type) {
            ContentType.BOTH -> R.string.content_both
            ContentType.MOVIES -> R.string.content_movies
            ContentType.TV -> R.string.content_tv
        }
    )

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    private fun resolutionLabel(width: Int) =
        getString(if (width > 1920) R.string.resolution_2160 else R.string.resolution_1080)

    companion object {
        private const val ID_STATUS = 1L
        private const val ID_GENERATE = 2L
        private const val ID_PREVIEW = 3L
        private const val ID_TOKEN = 4L
        private const val ID_LANGUAGE = 5L
        private const val ID_REGION = 6L
        private const val ID_CONTENT = 7L
        private const val ID_COUNT = 8L
        private const val ID_REFRESH = 9L
        private const val ID_RESOLUTION = 10L
        private const val ID_GLOBAL_HITS = 11L
        private const val ID_PROVIDERS = 12L
        private const val SUB_ID_BASE = 1000L
    }
}
