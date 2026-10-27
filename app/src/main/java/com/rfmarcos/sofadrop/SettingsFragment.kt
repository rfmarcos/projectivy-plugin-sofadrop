package com.rfmarcos.sofadrop

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Main settings: what to do now, where titles come from, and how often. Details live in sub-screens. */
class SettingsFragment : PreviewSettingsFragment() {

    override val showsProjectivyNotice = true

    private val statusRefresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
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

        // First run: Sofadrop does nothing until it is Projectivy's wallpaper, so say where to pick it
        if (Settings.projectivyAskedAt == 0L && !WallpaperStore(context).everServed) {
            actions += action(ID_USE_IN_PROJECTIVY, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title),
                getString(R.string.use_in_projectivy_desc)).multilineDescription(true).build()
        }
        if (Updater.available) actions += updateAction()
        // "Update now" also carries the status, so there is a single place to look
        actions += action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), statusText())
            .multilineDescription(true).build()
        actions += action(ID_PREVIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
            .enabled(WallpaperStore(context).current().isNotEmpty()).build()
        actions += action(ID_LISTS, R.drawable.ic_act_movie, getString(R.string.setting_lists_title), ListsFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_DETAILS, R.drawable.ic_act_tv, getString(R.string.setting_details_title), DetailsFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_ACCOUNTS, R.drawable.ic_act_account, getString(R.string.setting_accounts_title), AccountsFragment.summary(context))
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
        updateDescription(ID_DETAILS, DetailsFragment.summary(context))
        updateDescription(ID_ACCOUNTS, AccountsFragment.summary(context))
        handler.post(statusRefresher)
        // Back from the system installer (cancelled, or blocked by Play Protect): offer the update again
        updateDescription(ID_APP_UPDATE, getString(R.string.update_desc, Settings.updateVersion, BuildConfig.VERSION_NAME))
        background({ Updater.check() }) { showUpdate() }
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
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
            ID_APP_UPDATE -> installUpdate()
            ID_USE_IN_PROJECTIVY -> Projectivy.openWallpaperSettings(requireContext())
            ID_LISTS -> add(parentFragmentManager, ListsFragment())
            ID_DETAILS -> add(parentFragmentManager, DetailsFragment())
            ID_ACCOUNTS -> add(parentFragmentManager, AccountsFragment())
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

    private fun updateAction(): GuidedAction =
        action(ID_APP_UPDATE, R.drawable.ic_act_refresh, getString(R.string.update_title),
            getString(R.string.update_desc, Settings.updateVersion, BuildConfig.VERSION_NAME)).multilineDescription(true).build()

    /** Offer an update found after the screen opened, below the first-run hint, without reopening the screen. */
    private fun showUpdate() {
        if (!Updater.available || findActionById(ID_APP_UPDATE) != null) return
        val index = if (findActionById(ID_USE_IN_PROJECTIVY) != null) 1 else 0
        actions = actions.toMutableList().apply { add(index, updateAction()) }
    }

    /** Download and install the new version; Android first asks to allow Sofadrop to install apps. */
    private fun installUpdate() {
        val context = requireContext().applicationContext
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.update_allow, Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")))
            return
        }
        updateDescription(ID_APP_UPDATE, getString(R.string.update_downloading, Settings.updateVersion))
        background({ Updater.install(context) }) { result ->
            result.onFailure {
                updateDescription(ID_APP_UPDATE, getString(R.string.update_failed, it.message ?: it.javaClass.simpleName))
            }
        }
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
        refreshPending()
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
                // "0 minutes ago" reads oddly; mid-sentence, Spanish and others capitalise it ("Hace 3 minutos")
                if (System.currentTimeMillis() - Settings.lastGeneration < DateUtils.MINUTE_IN_MILLIS) getString(R.string.time_just_now)
                else DateUtils.getRelativeTimeSpanString(Settings.lastGeneration, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    .toString().replaceFirstChar { it.lowercase() },
            )
        }
    }

    // --- Labels ---

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    companion object {
        private const val ID_GENERATE = 2L
        private const val ID_PREVIEW = 3L
        private const val ID_LISTS = 4L
        private const val ID_REFRESH = 8L
        private const val ID_ABOUT = 18L
        private const val ID_DETAILS = 20L
        private const val ID_APP_UPDATE = 21L
        private const val ID_USE_IN_PROJECTIVY = 22L
        private const val ID_ACCOUNTS = 23L
    }
}
