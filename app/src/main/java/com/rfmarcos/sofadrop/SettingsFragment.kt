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

    /** Without Projectivy, Sofadrop is only a screensaver: the rows about Projectivy go. */
    private val hasProjectivy by lazy { Projectivy.isInstalled(requireContext()) }

    override val showsProjectivyNotice get() = hasProjectivy

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Delivered on the way back from the notes, before onResume resets the update row: install after it
        parentFragmentManager.setFragmentResultListener(UpdateFragment.RESULT_INSTALL, this) { _, _ -> handler.post { installUpdate() } }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        Settings.init(context)

        // First run: Sofadrop does nothing until it is Projectivy's wallpaper, so say where to pick it
        if (hasProjectivy && Settings.projectivyAskedAt == 0L && !WallpaperStore(context).everServed) {
            actions += action(ID_USE_IN_PROJECTIVY, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title),
                getString(R.string.use_in_projectivy_desc)).multilineDescription(true).build()
        }
        // Without Projectivy, until the screensaver has been shown once: that's what Sofadrop is for there
        if (!hasProjectivy && Settings.screensaverShownAt == 0L) {
            actions += action(ID_SCREENSAVER_ONLY, R.drawable.ic_act_screensaver, getString(R.string.screensaver_only_title),
                getString(R.string.screensaver_only_hint)).multilineDescription(true).build()
        }
        if (Updater.available) actions += updateAction()
        // "Update now" also carries the status, so there is a single place to look
        actions += action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), statusText())
            .multilineDescription(true).build()
        actions += action(ID_PREVIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
            .enabled(WallpaperStore(context).current().isNotEmpty()).build()
        actions += action(ID_LISTS, R.drawable.ic_act_movie, getString(R.string.setting_lists_title), ListsFragment.summary(context, profile))
            .hasNext(true).build()
        actions += action(ID_DETAILS, R.drawable.ic_act_tv, getString(R.string.setting_details_title), DetailsFragment.summary(context, profile))
            .hasNext(true).build()
        actions += action(ID_SCREENSAVER, R.drawable.ic_act_screensaver, getString(R.string.screensaver_title), ScreensaverFragment.summary(context))
            .hasNext(true).build()
        if (hasProjectivy) actions += action(ID_CLICK, R.drawable.ic_act_play, getString(R.string.click_action_title), ClickActionFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_ACCOUNTS, R.drawable.ic_act_account, getString(R.string.setting_accounts_title), AccountsFragment.summary(context))
            .hasNext(true).build()
        actions += choice(ID_REFRESH, R.drawable.ic_act_schedule, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
            Settings.REFRESH_OPTIONS.map(::refreshLabel))
        actions += action(ID_WIZARD, R.drawable.ic_act_check, getString(R.string.wizard_title), getString(R.string.wizard_desc)).build()
        actions += action(ID_ABOUT, R.drawable.ic_act_info, getString(R.string.setting_about_title),
            getString(R.string.setting_about_desc, BuildConfig.VERSION_NAME)).hasNext(true).build()
    }

    override fun onResume() {
        super.onResume()
        // Sub-screens may have changed lists, filters or details
        val context = requireContext()
        updateDescription(ID_LISTS, ListsFragment.summary(context, profile))
        updateDescription(ID_DETAILS, DetailsFragment.summary(context, profile))
        updateDescription(ID_SCREENSAVER, ScreensaverFragment.summary(context))
        updateDescription(ID_CLICK, ClickActionFragment.summary(context))
        updateDescription(ID_ACCOUNTS, AccountsFragment.summary(context))
        handler.post(statusRefresher)
        // Back from the system installer (cancelled, or blocked by Play Protect): offer the update again
        updateDescription(ID_APP_UPDATE, updateText())
        background({ Updater.check() }) { showUpdate() }
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        val context = requireContext()
        GenerateWallpapersWorker.schedule(context, settingsChanged = true)
        // First run: nothing to show in Projectivy yet, so generate right away (the wizard does it with its answers)
        if (profile.lastGeneration == 0L && Settings.token.isNotBlank() && !Wizard.open) GenerateWallpapersWorker.runNow(context)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_GENERATE -> {
                if (Sources.selected(profile).isEmpty()) {
                    Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
                    return
                }
                GenerateWallpapersWorker.runNow(requireContext(), replace = true)
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            ID_PREVIEW -> startActivity(Intent(requireContext(), PreviewActivity::class.java))
            // What's new first, when the release says
            ID_APP_UPDATE -> if (hasNotes()) open(UpdateFragment()) else installUpdate()
            ID_USE_IN_PROJECTIVY -> Projectivy.openWallpaperSettings(requireContext())
            ID_LISTS -> open(ListsFragment())
            ID_DETAILS -> open(DetailsFragment())
            ID_SCREENSAVER, ID_SCREENSAVER_ONLY -> add(parentFragmentManager, ScreensaverFragment().forProfile(Profile.SCREENSAVER))
            ID_CLICK -> open(ClickActionFragment())
            ID_ACCOUNTS -> open(AccountsFragment())
            ID_WIZARD -> Wizard.start(parentFragmentManager)
            ID_ABOUT -> open(AboutFragment())
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

    private fun hasNotes() = Updater.releaseNotes(Settings.updateNotes).isNotEmpty()

    private fun updateText() = getString(if (hasNotes()) R.string.update_desc_notes else R.string.update_desc,
        Settings.updateVersion, BuildConfig.VERSION_NAME)

    // Opens the notes first when the release has them (onGuidedActionClicked)
    private fun updateAction(): GuidedAction =
        action(ID_APP_UPDATE, R.drawable.ic_act_refresh, getString(R.string.update_title), updateText()).multilineDescription(true).build()

    /** Offer an update found after the screen opened, below the first-run hint, without reopening the screen. */
    private fun showUpdate() {
        if (!isAdded || !Updater.available || findActionById(ID_APP_UPDATE) != null) return
        val index = if (findActionById(ID_USE_IN_PROJECTIVY) != null || findActionById(ID_SCREENSAVER_ONLY) != null) 1 else 0
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

    private fun statusText(): String = generationStatus()

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
        private const val ID_CLICK = 24L
        private const val ID_SCREENSAVER = 25L
        private const val ID_SCREENSAVER_ONLY = 26L
        private const val ID_WIZARD = 27L
    }
}
