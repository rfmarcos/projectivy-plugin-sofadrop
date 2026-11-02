package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentManager
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The setup wizard: a few questions with the preview in view, opened by itself on the first run and from
 * Settings. Each answer is saved as it is given, in the wallpapers' settings; the finer ones stay in Settings.
 */
object Wizard {

    enum class Step { WELCOME, STYLE, LISTS, AMOUNT, CLICK, SCREENSAVER, DONE }

    /** Steps on this device: what a wallpaper opens only matters with Projectivy. */
    fun steps(context: Context) = Step.entries.filter { it != Step.CLICK || Projectivy.isInstalled(context) }

    private fun fragment(step: Step): WizardStepFragment = when (step) {
        Step.WELCOME -> WizardWelcomeFragment()
        Step.STYLE -> WizardStyleFragment()
        Step.LISTS -> WizardListsFragment()
        Step.AMOUNT -> WizardAmountFragment()
        Step.CLICK -> WizardClickFragment()
        Step.SCREENSAVER -> WizardScreensaverFragment()
        Step.DONE -> WizardDoneFragment()
    }

    /** From the welcome screen until it closes: Settings doesn't make a set with the defaults meanwhile. */
    var open = false
        private set

    /** Whether the screensaver is wanted too (its step), for the last step. */
    var screensaver = true

    /** A fresh install, the first time (even when Projectivy already made a set with the defaults). */
    fun shouldOffer() = !Settings.wizardShown && Settings.freshInstall

    /** Opened again from About: skipping it leaves everything as it is. */
    var reopened = false
        private set

    fun start(manager: FragmentManager) {
        open = true
        reopened = Settings.wizardShown
        Settings.wizardShown = true
        GuidedStepSupportFragment.add(manager, fragment(Step.WELCOME))
    }

    fun next(from: WizardStepFragment) {
        val steps = steps(from.requireContext())
        steps.getOrNull(steps.indexOf(from.step) + 1)?.let { GuidedStepSupportFragment.add(from.parentFragmentManager, fragment(it)) }
    }

    /** Back to the screen the wizard was opened from, and make the set with the answers. */
    fun finish(from: WizardStepFragment) {
        generate(from.requireContext())
        from.popBackStackToGuidedStepSupportFragment(WizardWelcomeFragment::class.java, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    fun closed() {
        open = false
    }

    /** Make the wallpapers' set now, unless it is being made already. */
    fun generate(context: Context) {
        val profile = Profile.WALLPAPER
        if (profile.generating || Settings.token.isBlank() || Sources.selected(profile).isEmpty()) return
        if (profile.lastGeneration == 0L || profile.hasPendingChanges) GenerateWallpapersWorker.runNow(context, replace = true)
    }

    /** Lists picked together in the lists step; the platforms' ones are MDBList's public lists (no account). */
    class ListPreset(val id: Long, val title: String, val description: String, val keys: List<String>, val mdbLists: List<MdbListRef> = emptyList())

    fun listPresets(context: Context): List<ListPreset> {
        fun mdbList(id: Long, platform: String, vararg paths: String): ListPreset {
            val refs = MdbListClient.SUGGESTED.filter { it.path in paths }
            return ListPreset(id, platform, context.getString(R.string.wizard_lists_platform_desc, platform), refs.map { it.key }, refs)
        }
        return listOf(
            ListPreset(10, context.getString(R.string.wizard_lists_trending), context.getString(R.string.wizard_lists_trending_desc),
                listOf("tmdb:trending_movies", "tmdb:trending_tv")),
            mdbList(11, "Netflix", "garycrawfordgc/netflix-movies", "garycrawfordgc/netflix-shows"),
            mdbList(12, "HBO Max", "garycrawfordgc/hbo-shows"),
            mdbList(13, "Disney+", "garycrawfordgc/disney-shows"),
            ListPreset(14, context.getString(R.string.wizard_lists_top_rated), context.getString(R.string.wizard_lists_top_rated_desc),
                listOf("tmdb:top_rated_movies", "tmdb:top_rated_tv")),
            ListPreset(15, context.getString(R.string.wizard_lists_upcoming), context.getString(R.string.wizard_lists_upcoming_desc),
                listOf("tmdb:upcoming")),
            ListPreset(16, context.getString(R.string.wizard_lists_anime), context.getString(R.string.wizard_lists_anime_desc),
                listOf("simkl:trending_anime")),
        )
    }
}

/** One step: "Step 2 of 7" over its question, its text always in view under the preview, and Next at the end. */
abstract class WizardStepFragment : PreviewSettingsFragment() {

    abstract val step: Wizard.Step
    protected abstract val titleRes: Int
    protected abstract fun text(): String

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        val steps = Wizard.steps(requireContext())
        return Guidance(getString(titleRes), text(), getString(R.string.wizard_step, steps.indexOf(step) + 1, steps.size),
            AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = View.VISIBLE
    }

    protected fun nextRow(): GuidedAction = action(ID_NEXT, R.drawable.ic_act_check, getString(R.string.wizard_next), null).build()

    /** One of the step's options, picked like a radio button. */
    protected fun radio(id: Long, title: CharSequence, description: CharSequence?, checked: Boolean) =
        GuidedAction.Builder(requireContext()).id(id).icon(ColorDrawable(Color.TRANSPARENT)).title(title).description(description)
            .checkSetId(RADIO_SET).checked(checked).multilineDescription(true)

    /** Whether the step can be left with Next; says why not. */
    protected open fun ready() = true

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_NEXT && ready()) Wizard.next(this)
    }

    companion object {
        const val ID_NEXT = 99L
        private const val RADIO_SET = 50
    }
}

class WizardWelcomeFragment : WizardStepFragment() {
    override val step = Wizard.Step.WELCOME
    override val titleRes = R.string.wizard_welcome_title
    override fun text() = getString(R.string.wizard_welcome_text)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_START, R.drawable.ic_act_play, getString(R.string.wizard_start), getString(R.string.wizard_start_desc)).build()
        actions += choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.general_language_title, Languages.displayName(Settings.language),
            Languages.OPTIONS.values.toList())
        // Another TV already set up: its settings file instead of the questions (Google TV has no picker)
        val canPick = SettingsFile.canPick(requireContext())
        actions += action(ID_IMPORT, R.drawable.ic_act_add, getString(R.string.wizard_import),
            getString(if (canPick) R.string.settings_file_import_desc else R.string.settings_file_import_no_picker, SettingsFile.NAME))
            .multilineDescription(true).focusable(canPick).build()
        actions += if (Wizard.reopened) action(ID_SKIP, R.drawable.ic_act_refresh, getString(R.string.wizard_leave), null).build()
            else action(ID_SKIP, R.drawable.ic_act_refresh, getString(R.string.wizard_skip), getString(R.string.wizard_skip_desc))
                .multilineDescription(true).build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_START -> Wizard.next(this)
            ID_SKIP -> Wizard.finish(this)
            ID_IMPORT -> runCatching { pick.launch(arrayOf("*/*")) }
        }
    }

    private val pick = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(SettingsImportFragment.newInstance(uri))
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_LANGUAGE) {
            Settings.language = Languages.OPTIONS.keys.elementAt((action.id % SUB_ID_BASE).toInt())
            updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
            schedulePreview(300)
        }
        return true
    }

    override fun onDestroy() {
        Wizard.closed()
        super.onDestroy()
    }

    private companion object {
        const val ID_START = 1L
        const val ID_LANGUAGE = 2L
        const val ID_SKIP = 3L
        const val ID_IMPORT = 4L
    }
}

class WizardStyleFragment : WizardStepFragment() {
    override val step = Wizard.Step.STYLE
    override val titleRes = R.string.wizard_style_title
    override fun text() = getString(R.string.wizard_style_text)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val position = DetailsFragment.positionLabel(context, profile.position)
        return Settings.STYLES.mapIndexed { index, style ->
            // "As set below" means the Details screen: here, that it can be set later
            val description = if (style == Settings.STYLE_FULL) getString(R.string.wizard_style_full_desc) else DetailsFragment.styleDescription(context, style)
            radio(ID_STYLE_BASE + index, DetailsFragment.styleLabel(context, style), description, profile.style == style).build()
        } + action(ID_POSITION, R.drawable.ic_act_place, getString(R.string.position_title), position)
            .subActions(options(ID_POSITION, Settings.POSITIONS.map { DetailsFragment.positionLabel(context, it) }, position,
                Settings.POSITIONS.map { if (it.startsWith("top")) null else getString(R.string.position_rows_note) }))
            .build().hiddenBy(profile.drawsLogo) + nextRow()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val style = Settings.STYLES.getOrNull((action.id - ID_STYLE_BASE).toInt())
        if (style == null) return super.onGuidedActionClicked(action)
        profile.style = style
        refreshActions(rows())
        schedulePreview(300)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_POSITION) {
            profile.position = Settings.POSITIONS[(action.id % SUB_ID_BASE).toInt()]
            refreshActions(rows())
            schedulePreview(300)
        }
        return true
    }

    private companion object {
        const val ID_STYLE_BASE = 10L
        const val ID_POSITION = 20L
    }
}

class WizardListsFragment : WizardStepFragment() {
    override val step = Wizard.Step.LISTS
    override val titleRes = R.string.wizard_lists_title
    // The lists by name: "2 lists" after ticking one option reads like a mistake
    override fun text(): String {
        val context = requireContext()
        val selected = Sources.selected(profile)
        val now = if (selected.isEmpty() || selected.size > 4) ListsFragment.listsCount(context, selected.size)
            else selected.joinToString(", ") { Sources.label(context, it) }
        return getString(R.string.wizard_lists_text) + "\n\n" + getString(R.string.wizard_lists_now, now)
    }

    private val presets by lazy { Wizard.listPresets(requireContext()) }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // Back from the accounts or the lists: a list may have been connected or picked
    override fun onResume() {
        super.onResume()
        refreshActions(rows())
        setGuidanceText(text())
    }

    /** The watchlist of the connected Trakt or Simkl account, if any. */
    private fun ownKeys() = when {
        Settings.traktConnected -> listOf("trakt:watchlist_movies", "trakt:watchlist_shows")
        Settings.simklConnected -> listOf("simkl:plantowatch")
        else -> emptyList()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val chosen = profile.lists
        val rows = presets.map { checkbox(it.id, it.title, it.description, it.keys.all(chosen::contains)).build() }.toMutableList()
        val own = ownKeys()
        rows += if (own.isEmpty()) {
            action(ID_CONNECT, R.drawable.ic_act_account, getString(R.string.wizard_lists_own), getString(R.string.wizard_lists_connect))
                .hasNext(true).build()
        } else {
            val labels = Sources.available().filter { it.key in own }.joinToString(" · ") { Sources.label(context, it) }
            checkbox(ID_OWN, getString(R.string.wizard_lists_own), labels, own.all(chosen::contains)).build()
        }
        rows += action(ID_BY_HAND, R.drawable.ic_act_list, getString(R.string.wizard_lists_by_hand), ListsFragment.summary(context, profile))
            .hasNext(true).build()
        rows += nextRow()
        return rows
    }

    override fun ready(): Boolean {
        if (Sources.selected(profile).isNotEmpty()) return true
        Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val preset = presets.firstOrNull { it.id == action.id }
        when {
            preset != null -> {
                if (action.isChecked) Settings.mdbLists += preset.mdbLists.filter { ref -> Settings.mdbLists.none { it.key == ref.key } }
                pick(preset.keys, action.isChecked)
            }
            action.id == ID_OWN -> pick(ownKeys(), action.isChecked)
            action.id == ID_CONNECT -> open(AccountsFragment())
            action.id == ID_BY_HAND -> open(ListsFragment())
            else -> super.onGuidedActionClicked(action)
        }
    }

    private fun pick(keys: List<String>, picked: Boolean) {
        profile.lists = if (picked) profile.lists + keys else profile.lists - keys.toSet()
        refreshActions(rows())
        setGuidanceText(text())
        schedulePreview()
    }

    private companion object {
        const val ID_OWN = 30L
        const val ID_CONNECT = 31L
        const val ID_BY_HAND = 32L
    }
}

class WizardAmountFragment : WizardStepFragment() {
    override val step = Wizard.Step.AMOUNT
    override val titleRes = R.string.wizard_amount_title

    override fun text(): String {
        val context = requireContext()
        val lists = Sources.selected(profile).size
        val total = minOf(lists * profile.titlesPerList, Settings.MAX_WALLPAPERS)
        // Over the most per update, the product isn't what's made: say it's the most
        val capped = lists * profile.titlesPerList > Settings.MAX_WALLPAPERS
        return getString(if (capped) R.string.wizard_amount_total_capped else R.string.wizard_amount_total, ListsFragment.listsCount(context, lists),
            ListsFragment.countLabel(context, profile.titlesPerList), resources.getQuantityString(R.plurals.wallpapers_count, total, total)) +
            "\n" + getString(R.string.wizard_amount_text)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val resolution = DetailsFragment.resolutionLabel(context, profile.outputWidth)
        return listOf(
            choice(ID_COUNT, R.drawable.ic_act_count, R.string.wizard_amount_count, ListsFragment.countLabel(context, profile.titlesPerList),
                Settings.COUNT_OPTIONS.map { ListsFragment.countLabel(context, it) }),
            action(ID_RESOLUTION, R.drawable.ic_act_hd, getString(R.string.setting_resolution_title), resolution)
                .subActions(options(ID_RESOLUTION, Settings.WIDTH_OPTIONS.map { DetailsFragment.resolutionLabel(context, it) }, resolution,
                    Settings.WIDTH_OPTIONS.map { width ->
                        when {
                            width <= 1920 -> null
                            DeviceMemory.tightFor4k(context) -> getString(R.string.resolution_low_memory)
                            else -> getString(R.string.wizard_amount_4k)
                        }
                    }))
                .build(),
            choice(ID_REFRESH, R.drawable.ic_act_schedule, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
                Settings.REFRESH_OPTIONS.map(::refreshLabel)),
            nextRow(),
        )
    }

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_COUNT -> profile.titlesPerList = Settings.COUNT_OPTIONS[index]
            ID_RESOLUTION -> profile.outputWidth = Settings.WIDTH_OPTIONS[index]
            ID_REFRESH -> Settings.refreshHours = Settings.REFRESH_OPTIONS[index]
        }
        refreshActions(rows())
        setGuidanceText(text())
        return true
    }

    private companion object {
        const val ID_COUNT = 1L
        const val ID_RESOLUTION = 2L
        const val ID_REFRESH = 3L
    }
}

class WizardClickFragment : WizardStepFragment() {
    override val step = Wizard.Step.CLICK
    override val titleRes = R.string.wizard_click_title
    override fun text() = getString(R.string.click_action_desc)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // Back from all the options
    override fun onResume() {
        super.onResume()
        refreshActions(rows())
    }

    private fun platforms(context: Context) =
        Platform.entries.filter { it.offeredIn(Settings.region, context) && it.installedPackage(context) != null }

    private fun apps(context: Context) = TitleApp.entries.filter { it.installedPackage(context) != null }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val fallback = Settings.clickFallback
        val usesPlatforms = Settings.clickPlatforms.isNotEmpty()
        val platforms = platforms(context)
        val rows = mutableListOf(
            radio(ID_TRAILER, getString(R.string.click_trailer), getString(R.string.click_trailer_desc),
                !usesPlatforms && fallback == Settings.CLICK_TRAILER).build(),
            radio(ID_PLATFORMS, getString(R.string.wizard_click_platforms),
                if (platforms.isEmpty()) getString(R.string.wizard_click_no_platforms)
                else getString(R.string.wizard_click_platforms_desc, platforms.joinToString(", ") { it.label }),
                usesPlatforms).enabled(platforms.isNotEmpty()).build(),
        )
        val apps = apps(context)
        rows += apps.map { app ->
            val action = Settings.CLICK_APP_PREFIX + app.key
            radio(ID_APP_BASE + app.ordinal, ClickActionFragment.clickLabel(context, action), null, !usesPlatforms && fallback == action).build()
        }
        if (apps.isEmpty()) {
            rows += radio(ID_NO_APP, getString(R.string.wizard_click_app), getString(R.string.wizard_click_no_app,
                TitleApp.entries.joinToString(", ") { it.label }), false).enabled(false).build()
        }
        rows += radio(ID_NONE, getString(R.string.click_none), null, !usesPlatforms && fallback == Settings.CLICK_NONE).build()
        rows += action(ID_MORE, R.drawable.ic_act_play, getString(R.string.wizard_more_options), ClickActionFragment.summary(context))
            .hasNext(true).build()
        rows += nextRow()
        return rows
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val context = requireContext()
        val app = TitleApp.entries.firstOrNull { ID_APP_BASE + it.ordinal == action.id }
        when {
            action.id == ID_TRAILER -> set(emptyList(), Settings.CLICK_TRAILER)
            action.id == ID_PLATFORMS -> set(platforms(context), Settings.CLICK_TRAILER)
            action.id == ID_NONE -> set(emptyList(), Settings.CLICK_NONE)
            app != null -> set(emptyList(), Settings.CLICK_APP_PREFIX + app.key)
            action.id == ID_MORE -> open(ClickActionFragment())
            else -> super.onGuidedActionClicked(action)
        }
    }

    private fun set(platforms: List<Platform>, fallback: String) {
        Settings.clickPlatforms = platforms
        Settings.clickFallback = fallback
        refreshActions(rows())
    }

    private companion object {
        const val ID_TRAILER = 1L
        const val ID_PLATFORMS = 2L
        const val ID_NONE = 3L
        const val ID_NO_APP = 4L
        const val ID_MORE = 5L
        const val ID_APP_BASE = 10L
    }
}

class WizardScreensaverFragment : WizardStepFragment() {
    override val step = Wizard.Step.SCREENSAVER
    override val titleRes = R.string.wizard_screensaver_title

    override fun text() = getString(R.string.wizard_screensaver_text) +
        if (ScreensaverSetupFragment.needsComputer(requireContext())) "\n\n" + getString(R.string.wizard_screensaver_google_tv) else ""

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val clock = ScreensaverFragment.clockLabel(context, Settings.screensaverClock)
        return listOf(
            radio(ID_YES, getString(R.string.wizard_screensaver_yes), getString(R.string.wizard_screensaver_yes_desc), Wizard.screensaver).build(),
            radio(ID_NO, getString(R.string.wizard_screensaver_no), null, !Wizard.screensaver).build(),
            choice(ID_CLOCK, R.drawable.ic_act_schedule, R.string.screensaver_clock_title, clock, Settings.CLOCK_STYLES.map { ScreensaverFragment.clockLabel(context, it) })
                .also { it.isEnabled = Wizard.screensaver },
            action(ID_MORE, R.drawable.ic_act_screensaver, getString(R.string.wizard_more_options), ScreensaverFragment.summary(context))
                .hasNext(true).enabled(Wizard.screensaver).build(),
            nextRow(),
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_YES, ID_NO -> {
                Wizard.screensaver = action.id == ID_YES
                refreshActions(rows())
            }
            ID_MORE -> GuidedStepSupportFragment.add(parentFragmentManager, ScreensaverFragment().forProfile(Profile.SCREENSAVER))
            else -> super.onGuidedActionClicked(action)
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_CLOCK) {
            Settings.screensaverClock = Settings.CLOCK_STYLES[(action.id % SUB_ID_BASE).toInt()]
            refreshActions(rows())
        }
        return true
    }

    private companion object {
        const val ID_YES = 1L
        const val ID_NO = 2L
        const val ID_CLOCK = 3L
        const val ID_MORE = 4L
    }
}

class WizardDoneFragment : WizardStepFragment() {
    override val step = Wizard.Step.DONE
    override val titleRes = R.string.wizard_done_title

    override fun text(): String {
        val context = requireContext()
        return listOfNotNull(
            getString(R.string.wizard_done_lists, ListsFragment.summary(context, profile)),
            // The summary leaves out the usual style; here it's one of the answers
            getString(R.string.wizard_done_look, listOfNotNull(DetailsFragment.styleLabel(context, profile.style).takeIf { profile.drawsDetails },
                DetailsFragment.summary(context, profile)).joinToString(" · ")),
            getString(R.string.wizard_done_click, ClickActionFragment.summary(context)).takeIf { Projectivy.isInstalled(context) },
            getString(R.string.wizard_done_screensaver, getString(if (Wizard.screensaver) R.string.wizard_done_yes else R.string.wizard_done_no)),
        ).joinToString("\n")
    }

    private val statusRefresher = object : Runnable {
        override fun run() {
            updateDescription(ID_STATUS, generationStatus())
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Wizard.generate(requireContext())
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        if (Projectivy.isInstalled(context)) {
            actions += action(ID_PROJECTIVY, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title), getString(R.string.use_in_projectivy_desc))
                .multilineDescription(true).build()
        }
        if (Wizard.screensaver) {
            actions += action(ID_SCREENSAVER, R.drawable.ic_act_screensaver, getString(R.string.wizard_done_switch_on),
                ScreensaverSetupFragment.status(context)).build()
        }
        actions += action(ID_STATUS, R.drawable.ic_act_refresh, getString(R.string.wizard_done_wallpapers), generationStatus())
            .multilineDescription(true).build()
        actions += action(ID_SETTINGS, R.drawable.ic_act_check, getString(R.string.wizard_finish), null).build()
    }

    // Finish first: the rows above are there to read, or for later
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) selectedActionPosition = findActionPositionById(ID_SETTINGS)
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's screensaver settings
        if (Wizard.screensaver) updateDescription(ID_SCREENSAVER, ScreensaverSetupFragment.status(requireContext()))
        handler.post(statusRefresher)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_PROJECTIVY -> Projectivy.openWallpaperSettings(requireContext())
            ID_SCREENSAVER -> ScreensaverSetupFragment.open(this)
            ID_SETTINGS -> Wizard.finish(this)
        }
    }

    private companion object {
        const val ID_PROJECTIVY = 1L
        const val ID_SCREENSAVER = 2L
        const val ID_STATUS = 3L
        const val ID_SETTINGS = 4L
    }
}
