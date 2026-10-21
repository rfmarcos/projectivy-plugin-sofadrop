package com.rfmarcos.sofadrop

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

/** Main settings: what to do now, where titles come from, and how often. Details live in sub-screens. */
class SettingsFragment : BaseSettingsFragment() {

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
        "",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        Settings.init(context)

        // "Update now" also carries the status, so there is a single place to look
        actions += action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), statusText())
            .multilineDescription(true).build()
        actions += action(ID_PREVIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
            .enabled(WallpaperStore(context).current().isNotEmpty()).build()
        actions += action(ID_LISTS, R.drawable.ic_act_movie, getString(R.string.setting_lists_title), ListsFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_FILTERS, R.drawable.ic_act_filter, getString(R.string.setting_filters_title), FiltersFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_DETAILS, R.drawable.ic_act_tv, getString(R.string.setting_details_title), DetailsFragment.summary(context))
            .hasNext(true).build()
        actions += choice(ID_REFRESH, R.drawable.ic_act_schedule, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
            Settings.REFRESH_OPTIONS.map(::refreshLabel))
        actions += action(ID_ABOUT, R.drawable.ic_act_info, getString(R.string.setting_about_title),
            getString(R.string.setting_about_desc, BuildConfig.VERSION_NAME)).hasNext(true).build()
    }

    override fun onResume() {
        super.onResume()
        // Sub-screens may have changed lists, filters or details
        val context = requireContext()
        updateDescription(ID_LISTS, ListsFragment.summary(context))
        updateDescription(ID_FILTERS, FiltersFragment.summary(context))
        updateDescription(ID_DETAILS, DetailsFragment.summary(context))
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
                if (Sources.selected().isEmpty()) {
                    Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
                    return
                }
                GenerateWallpapersWorker.runNow(requireContext(), replace = true)
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            ID_PREVIEW -> startActivity(Intent(requireContext(), PreviewActivity::class.java))
            ID_LISTS -> add(parentFragmentManager, ListsFragment())
            ID_FILTERS -> add(parentFragmentManager, FiltersFragment())
            ID_DETAILS -> add(parentFragmentManager, DetailsFragment())
            ID_ABOUT -> add(parentFragmentManager, AboutFragment())
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_REFRESH -> {
                Settings.refreshHours = Settings.REFRESH_OPTIONS[index]
                updateDescription(ID_REFRESH, refreshLabel(Settings.refreshHours))
                refreshStatus()
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
        updateDescription(ID_GENERATE, statusText())
        findActionById(ID_PREVIEW)?.let {
            val enabled = WallpaperStore(requireContext()).current().isNotEmpty()
            if (it.isEnabled != enabled) {
                it.isEnabled = enabled
                notifyActionChanged(findActionPositionById(ID_PREVIEW))
            }
        }
        val pending = Settings.hasPendingChanges && !Settings.generating && Sources.selected().isNotEmpty()
        pendingNotice?.visibility = if (pending) View.VISIBLE else View.GONE
    }

    private fun statusText(): String {
        val count = WallpaperStore(requireContext()).current().size
        return when {
            Settings.generating -> Settings.generationProgress?.let { (done, list, lists) ->
                getString(R.string.status_progress, resources.getQuantityString(R.plurals.wallpapers_count, done, done), list, lists)
            } ?: getString(R.string.status_generating)
            Sources.selected().isEmpty() -> getString(R.string.status_select_list)
            // A "no lists" error is stale once lists are picked again
            Settings.lastError.isNotEmpty() && Settings.lastError != getString(R.string.error_no_lists) ->
                getString(R.string.status_error, Settings.lastError)
            count == 0 -> getString(R.string.status_never)
            Settings.hasPendingChanges -> getString(R.string.status_pending, count)
            else -> getString(
                R.string.status_ok, count,
                DateUtils.getRelativeTimeSpanString(Settings.lastGeneration, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
    }

    // --- Live preview ---

    /** Everything that changes how the preview looks. */
    private fun previewKey() = listOf(
        Settings.token.hashCode(), Settings.language, Settings.region, Settings.lists.sorted(),
        Settings.globalHitsOnly, Settings.maxAgeMonths, Settings.excludeAdult, Settings.filterScope, Settings.showProviders, Settings.traktConnected, Settings.mdbLists.size,
        Settings.tmdbConnected, Settings.selectionMode, Settings.watchedMode, Settings.overviewLines, Settings.showSource, Settings.previewPin,
        WallpaperStore(requireContext()).current().size,
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
        if (Sources.selected().isEmpty()) {
            showPreviewMessage(getString(R.string.error_no_lists))
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
        val pinned = Settings.previewPin.isNotEmpty()
        previewLabel?.text = getString(if (pinned) R.string.preview_label_pinned else R.string.preview_label, title)
    }

    private fun showPreviewMessage(message: String) {
        previewProgress?.visibility = View.GONE
        previewImage?.setImageDrawable(null)
        previewImage?.minimumHeight = (resources.displayMetrics.density * 200).toInt()
        previewLabel?.text = message
    }

    // --- Labels ---

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    companion object {
        private const val ID_GENERATE = 2L
        private const val ID_PREVIEW = 3L
        private const val ID_LISTS = 4L
        private const val ID_REFRESH = 8L
        private const val ID_FILTERS = 10L
        private const val ID_ABOUT = 18L
        private const val ID_DETAILS = 20L
        private const val PREVIEW_WIDTH = 1280

        /** The pinned title while it is still in the current set; a pin left behind by a new set is dropped. */
        private fun pinnedRef(context: android.content.Context): Pair<TitleRef, String>? {
            val pin = Settings.previewPin.ifEmpty { return null }
            val stored = WallpaperStore(context).current().firstOrNull { "${it.type}:${it.tmdbId}" == pin }
            val type = MediaType.entries.firstOrNull { it.path == stored?.type }
            if (stored == null || type == null) {
                Settings.previewPin = ""
                return null
            }
            return TitleRef(type, stored.tmdbId) to stored.source
        }

        /** Last preview (key, image, title), kept while the process lives so reopening is instant. */
        private var cachedPreview: Triple<String, Bitmap, String>? = null

        /** Render the pinned wallpaper, else one of the first selected list that has a usable title. */
        fun renderPreview(context: android.content.Context): Pair<Bitmap, String>? {
            Settings.init(context)
            val factory = WallpaperFactory(context, PREVIEW_WIDTH)
            pinnedRef(context)?.let { (ref, sourceKey) ->
                val source = Sources.available().firstOrNull { it.key == sourceKey }
                factory.build(ref, source = source)?.let { info -> return factory.render(info) to info.title }
            }
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
