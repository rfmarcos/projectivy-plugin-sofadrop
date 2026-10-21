package com.rfmarcos.tmdbwallpapers

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

class SettingsFragment : BaseSettingsFragment() {

    private var listOptions: List<ListSource> = emptyList()

    // Live preview
    private var previewImage: ImageView? = null
    private var previewProgress: ProgressBar? = null
    private var previewLabel: TextView? = null
    private var pendingNotice: TextView? = null
    private var previewJob = 0
    private val previewRunnable = Runnable { startPreview() }

    private val statusRefresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateGuidanceStylist(): GuidanceStylist = object : GuidanceStylist() {
        override fun onProvideLayoutId() = R.layout.settings_guidance

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, guidance: Guidance): View {
            val view = super.onCreateView(inflater, container, guidance)
            previewImage = view.findViewById(R.id.preview_image)
            previewProgress = view.findViewById(R.id.preview_progress)
            previewLabel = view.findViewById(R.id.preview_label)
            pendingNotice = view.findViewById(R.id.pending_notice)
            view.findViewById<View>(R.id.preview_frame).clipToOutline = true
            cachedPreview?.let { (key, bitmap, title) -> if (key == previewKey()) showPreview(bitmap, title) }
            return view
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        getString(R.string.plugin_name),
        "v${BuildConfig.VERSION_NAME} · ${getString(R.string.plugin_description)}",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        Settings.init(requireContext())

        actions += action(ID_STATUS, R.drawable.ic_act_status, getString(R.string.status_title), statusText())
            .multilineDescription(true).focusable(false).build()
        actions += action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), generateDescription()).build()
        actions += action(ID_PREVIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
            .enabled(WallpaperStore(requireContext()).current().isNotEmpty()).build()

        actions += action(ID_LISTS, R.drawable.ic_act_movie, getString(R.string.setting_lists_title), listsDescription())
            .subActions(listSubActions()).build()
        actions += action(ID_EXPLORE, R.drawable.ic_act_search, getString(R.string.setting_explore_title),
            getString(R.string.setting_explore_desc)).hasNext(true).build()
        actions += choice(ID_COUNT, R.drawable.ic_act_count, R.string.setting_count_title, Settings.titlesPerList.toString(),
            Settings.COUNT_OPTIONS.map(Int::toString))
        actions += choice(ID_SELECTION, R.drawable.ic_act_shuffle, R.string.setting_selection_title, selectionLabel(Settings.selectionMode),
            SELECTION_MODES.map(::selectionLabel))
        actions += choice(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title, watchedDescription(),
            WATCHED_MODES.map(::watchedLabel))
        actions += choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.setting_language_title, Languages.displayName(Settings.language),
            Languages.OPTIONS.values.toList())
        actions += choice(ID_REGION, R.drawable.ic_act_place, R.string.setting_region_title, regionDescription(),
            listOf(getString(R.string.region_auto, Languages.regionOf(Settings.language))) +
                Languages.REGIONS.map { "${Languages.countryName(it)} ($it)" })
        actions += choice(ID_REFRESH, R.drawable.ic_act_schedule, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
            Settings.REFRESH_OPTIONS.map(::refreshLabel))
        actions += choice(ID_RESOLUTION, R.drawable.ic_act_hd, R.string.setting_resolution_title, resolutionLabel(Settings.outputWidth),
            Settings.WIDTH_OPTIONS.map(::resolutionLabel))
        actions += action(ID_GLOBAL_HITS, R.drawable.ic_act_public, getString(R.string.setting_global_hits_title),
            getString(R.string.setting_global_hits_desc))
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.globalHitsOnly).build()
        actions += action(ID_PROVIDERS, R.drawable.ic_act_tv, getString(R.string.setting_providers_title),
            getString(R.string.setting_providers_desc))
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.showProviders).build()

        actions += action(ID_PHONE, R.drawable.ic_act_phone, getString(R.string.setting_phone_title),
            getString(R.string.setting_phone_desc)).hasNext(true).build()
        actions += editable(ID_TOKEN, R.drawable.ic_act_key, getString(R.string.setting_token_title), Settings.token, tokenDescription())
        actions += action(ID_TMDB_ACCOUNT, R.drawable.ic_act_account, getString(R.string.setting_tmdb_account_title), tmdbAccountDescription())
            .subActions(tmdbAccountSubActions()).build()
        actions += action(ID_TRAKT, R.drawable.ic_act_trakt, getString(R.string.setting_trakt_title), traktDescription())
            .hasNext(true).build()
        actions += action(ID_MDBLIST, R.drawable.ic_act_list, getString(R.string.setting_mdblist_title), mdbListDescription())
            .hasNext(true).build()
    }

    override fun onResume() {
        super.onResume()
        // Trakt / MDBList may have changed in their own screens
        findActionById(ID_LISTS)?.let {
            it.subActions = listSubActions()
            it.description = listsDescription()
            notifyActionChanged(findActionPositionById(ID_LISTS))
        }
        refreshAccounts()
        handler.post(statusRefresher)
        schedulePreview(0)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        handler.removeCallbacks(previewRunnable)
        val context = requireContext()
        GenerateWallpapersWorker.schedule(context, settingsChanged = true)
        // First run: nothing to show in Projectivy yet, so generate right away
        if (Settings.lastGeneration == 0L && Settings.token.isNotBlank()) GenerateWallpapersWorker.runNow(context)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_GENERATE -> {
                GenerateWallpapersWorker.runNow(requireContext(), replace = true)
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            ID_PREVIEW -> startActivity(Intent(requireContext(), PreviewActivity::class.java))
            ID_GLOBAL_HITS -> settingChanged { Settings.globalHitsOnly = action.isChecked }
            ID_PROVIDERS -> settingChanged { Settings.showProviders = action.isChecked }
            ID_TRAKT -> add(parentFragmentManager, TraktSettingsFragment())
            ID_EXPLORE -> add(parentFragmentManager, ListExplorerFragment())
            ID_PHONE -> add(parentFragmentManager, PhoneSetupFragment())
            ID_TMDB_ACCOUNT -> if (!Settings.tmdbConnected && Settings.token.isNotBlank()) add(parentFragmentManager, TmdbLoginFragment())
            ID_MDBLIST -> add(parentFragmentManager, MdbListSettingsFragment())
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ID_TOKEN) {
            settingChanged { Settings.token = action.editDescription?.toString().orEmpty() }
            action.editDescription = Settings.token
            updateDescription(ID_TOKEN, tokenDescription())
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val parent = action.id / SUB_ID_BASE
        val index = (action.id % SUB_ID_BASE).toInt()
        when (parent) {
            ID_LISTS -> {
                val key = listOptions.getOrNull(index)?.key ?: return false
                settingChanged { Settings.lists = if (action.isChecked) Settings.lists + key else Settings.lists - key }
                updateDescription(ID_LISTS, listsDescription())
                return false // keep the list open to pick several
            }
            ID_COUNT -> settingChanged {
                Settings.titlesPerList = Settings.COUNT_OPTIONS[index]
                updateDescription(ID_COUNT, Settings.titlesPerList.toString())
            }
            ID_LANGUAGE -> settingChanged {
                Settings.language = Languages.OPTIONS.keys.elementAt(index)
                updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
                updateDescription(ID_REGION, regionDescription())
            }
            ID_REGION -> settingChanged {
                Settings.regionOverride = if (index == 0) "" else Languages.REGIONS[index - 1]
                updateDescription(ID_REGION, regionDescription())
            }
            ID_SELECTION -> settingChanged {
                Settings.selectionMode = SELECTION_MODES[index]
                updateDescription(ID_SELECTION, selectionLabel(Settings.selectionMode))
            }
            ID_WATCHED -> settingChanged {
                Settings.watchedMode = WATCHED_MODES[index]
                updateDescription(ID_WATCHED, watchedDescription())
            }
            ID_TMDB_ACCOUNT -> {
                if (Settings.tmdbConnected) {
                    Settings.clearTmdbSession()
                    Settings.lists = Settings.lists.filterNot { key -> Sources.TMDB.any { it.key == key && it.needsTmdbAccount } }.toSet()
                } else {
                    add(parentFragmentManager, TmdbLoginFragment())
                }
                refreshAccounts()
            }
            ID_REFRESH -> {
                Settings.refreshHours = Settings.REFRESH_OPTIONS[index]
                updateDescription(ID_REFRESH, refreshLabel(Settings.refreshHours))
                refreshStatus()
            }
            ID_RESOLUTION -> settingChanged {
                Settings.outputWidth = Settings.WIDTH_OPTIONS[index]
                updateDescription(ID_RESOLUTION, resolutionLabel(Settings.outputWidth))
            }
        }
        return true
    }

    /** Apply a change that affects the wallpapers: refresh the preview and the pending notice. */
    private fun settingChanged(change: () -> Unit) {
        change()
        refreshStatus()
        schedulePreview()
    }

    // --- Status and pending changes ---

    private fun refreshStatus() {
        if (!isAdded) return
        updateDescription(ID_STATUS, statusText())
        updateDescription(ID_GENERATE, generateDescription())
        findActionById(ID_PREVIEW)?.let {
            val enabled = WallpaperStore(requireContext()).current().isNotEmpty()
            if (it.isEnabled != enabled) {
                it.isEnabled = enabled
                notifyActionChanged(findActionPositionById(ID_PREVIEW))
            }
        }
        pendingNotice?.let { notice ->
            val pending = Settings.hasPendingChanges && !Settings.generating
            notice.visibility = if (pending) View.VISIBLE else View.GONE
            if (pending) notice.text = getString(R.string.pending_notice, nextUpdateText())
        }
    }

    private fun nextUpdateText(): CharSequence {
        val now = System.currentTimeMillis()
        val next = Settings.nextScheduledUpdate
        return if (next <= now + 60_000) getString(R.string.next_update_soon)
        else DateUtils.getRelativeTimeSpanString(next, now, DateUtils.MINUTE_IN_MILLIS).toString()
            .replaceFirstChar { it.lowercase() } // used mid-sentence ("in 5 hours")
    }

    private fun statusText(): String {
        val count = WallpaperStore(requireContext()).current().size
        return when {
            Settings.generating -> getString(R.string.status_generating)
            Settings.lastError.isNotEmpty() -> getString(R.string.status_error, Settings.lastError)
            count == 0 -> getString(R.string.status_never)
            Settings.hasPendingChanges -> getString(R.string.status_pending, count)
            else -> getString(
                R.string.status_ok, count,
                DateUtils.getRelativeTimeSpanString(Settings.lastGeneration, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
    }

    private fun generateDescription() = getString(
        if (Settings.hasPendingChanges) R.string.action_generate_now_pending else R.string.action_generate_now_desc
    )

    // --- Live preview ---

    /** Everything that changes how the preview looks. */
    private fun previewKey() = listOf(
        Settings.token.hashCode(), Settings.language, Settings.region, Settings.lists.sorted(),
        Settings.globalHitsOnly, Settings.showProviders, Settings.traktConnected, Settings.mdbLists.size,
        Settings.tmdbConnected, Settings.selectionMode, Settings.watchedMode,
    ).joinToString("|")

    private fun schedulePreview(delay: Long = 700) {
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, delay)
    }

    private fun startPreview() {
        val key = previewKey()
        if (cachedPreview?.first == key && previewImage?.drawable != null) return
        if (Settings.token.isBlank()) {
            showPreviewMessage(getString(R.string.preview_no_token))
            return
        }
        val job = ++previewJob
        previewProgress?.visibility = View.VISIBLE
        previewLabel?.text = getString(R.string.preview_loading)
        val context = requireContext().applicationContext
        background({ renderPreview(context) }) { result ->
            if (job != previewJob) return@background
            previewProgress?.visibility = View.GONE
            result.onSuccess { preview ->
                if (preview == null) {
                    showPreviewMessage(getString(R.string.preview_no_titles))
                } else {
                    cachedPreview = Triple(key, preview.first, preview.second)
                    showPreview(preview.first, preview.second)
                }
            }.onFailure {
                showPreviewMessage(getString(R.string.preview_error, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun showPreview(bitmap: Bitmap, title: String) {
        previewImage?.setImageBitmap(bitmap)
        previewLabel?.text = getString(R.string.preview_label, title)
    }

    private fun showPreviewMessage(message: String) {
        previewProgress?.visibility = View.GONE
        previewImage?.setImageDrawable(null)
        previewImage?.minimumHeight = (resources.displayMetrics.density * 200).toInt()
        previewLabel?.text = message
    }

    // --- Descriptions ---

    private fun listSubActions(): List<GuidedAction> {
        listOptions = Sources.available()
        val selected = Settings.lists
        return listOptions.mapIndexed { index, source ->
            GuidedAction.Builder(requireContext())
                .id(ID_LISTS * SUB_ID_BASE + index)
                .title(listLabel(source))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(source.key in selected)
                .build()
        }
    }

    private fun listLabel(source: ListSource) = Sources.label(requireContext(), source)

    /** Rows that depend on connected accounts (after login screens or phone setup). */
    private fun refreshAccounts() {
        findActionById(ID_LISTS)?.let {
            it.subActions = listSubActions()
            it.description = listsDescription()
            notifyActionChanged(findActionPositionById(ID_LISTS))
        }
        findActionById(ID_TMDB_ACCOUNT)?.let {
            it.subActions = tmdbAccountSubActions()
            it.description = tmdbAccountDescription()
            notifyActionChanged(findActionPositionById(ID_TMDB_ACCOUNT))
        }
        updateDescription(ID_TOKEN, tokenDescription())
        findActionById(ID_TOKEN)?.editDescription = Settings.token
        updateDescription(ID_WATCHED, watchedDescription())
        updateDescription(ID_TRAKT, traktDescription())
        updateDescription(ID_MDBLIST, mdbListDescription())
    }

    private fun tmdbAccountDescription(): String =
        if (Settings.tmdbConnected) getString(R.string.tmdb_account_connected, Settings.tmdbUser.ifBlank { "TMDB" })
        else getString(R.string.tmdb_account_desc)

    /** Connected: a "Disconnect" option. Not connected: no sub-actions, a click opens the QR login. */
    private fun tmdbAccountSubActions(): List<GuidedAction>? =
        if (!Settings.tmdbConnected) null
        else listOf(GuidedAction.Builder(requireContext()).id(ID_TMDB_ACCOUNT * SUB_ID_BASE).title(R.string.tmdb_disconnect).build())

    private fun selectionLabel(mode: String) =
        getString(if (mode == Settings.SELECTION_RANDOM) R.string.selection_random else R.string.selection_order)

    private fun watchedLabel(mode: String) = getString(
        when (mode) {
            Settings.WATCHED_HIDE -> R.string.watched_hide
            Settings.WATCHED_SHOW -> R.string.watched_show
            else -> R.string.watched_mark
        }
    )

    private fun watchedDescription(): String {
        val label = watchedLabel(Settings.watchedMode)
        return if (Settings.traktConnected) label else getString(R.string.watched_needs_trakt, label)
    }

    private fun listsDescription(): String {
        val selected = Sources.selected()
        return getString(R.string.lists_selected, selected.size, selected.joinToString(", ") { listLabel(it) })
    }

    private fun choice(id: Long, icon: Int, title: Int, current: String, options: List<String>): GuidedAction {
        val subActions = options.mapIndexed { index, label ->
            GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).build()
        }
        return action(id, icon, getString(title), current).subActions(subActions).build()
    }

    private fun tokenDescription(): String =
        if (Settings.token.isBlank()) getString(R.string.setting_token_missing)
        else getString(R.string.setting_token_set, Settings.token.takeLast(4))

    private fun traktDescription(): String = when {
        Settings.traktConnected -> getString(R.string.trakt_connected, Settings.traktUser.ifBlank { "Trakt" })
        Settings.traktConfigured -> getString(R.string.trakt_public_only)
        else -> getString(R.string.trakt_not_configured)
    }

    private fun mdbListDescription(): String =
        if (Settings.mdbListKey.isBlank()) getString(R.string.mdblist_not_configured)
        else getString(R.string.mdblist_configured, Settings.mdbLists.size)

    private fun regionDescription(): String {
        val override = Settings.regionOverride
        return if (override.isEmpty()) getString(R.string.region_auto, Languages.regionOf(Settings.language))
        else "${Languages.countryName(override)} ($override)"
    }

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    private fun resolutionLabel(width: Int) =
        getString(if (width > 1920) R.string.resolution_2160 else R.string.resolution_1080)

    companion object {
        private const val ID_STATUS = 1L
        private const val ID_GENERATE = 2L
        private const val ID_PREVIEW = 3L
        private const val ID_LISTS = 4L
        private const val ID_COUNT = 5L
        private const val ID_LANGUAGE = 6L
        private const val ID_REGION = 7L
        private const val ID_REFRESH = 8L
        private const val ID_RESOLUTION = 9L
        private const val ID_GLOBAL_HITS = 10L
        private const val ID_PROVIDERS = 11L
        private const val ID_TOKEN = 12L
        private const val ID_TRAKT = 13L
        private const val ID_MDBLIST = 14L
        private const val ID_EXPLORE = 15L
        private const val ID_SELECTION = 16L
        private const val ID_WATCHED = 17L
        private const val ID_PHONE = 18L
        private const val ID_TMDB_ACCOUNT = 19L
        private val SELECTION_MODES = listOf(Settings.SELECTION_ORDER, Settings.SELECTION_RANDOM)
        private val WATCHED_MODES = listOf(Settings.WATCHED_SHOW, Settings.WATCHED_MARK, Settings.WATCHED_HIDE)
        private const val SUB_ID_BASE = 1000L
        private const val PREVIEW_WIDTH = 1280

        /** Last preview (key, image, title), kept while the process lives so reopening is instant. */
        private var cachedPreview: Triple<String, Bitmap, String>? = null

        /** Render one wallpaper of the first selected list that has a usable title. */
        fun renderPreview(context: android.content.Context): Pair<Bitmap, String>? {
            Settings.init(context)
            val factory = WallpaperFactory(context, PREVIEW_WIDTH)
            for (source in Sources.selected()) {
                val plan = runCatching { factory.plan(source, 3) }.getOrNull() ?: continue
                for (ref in plan.usable.take(4)) {
                    val info = factory.build(ref, plan) ?: continue
                    return factory.render(info) to info.title
                }
            }
            return null
        }
    }
}
