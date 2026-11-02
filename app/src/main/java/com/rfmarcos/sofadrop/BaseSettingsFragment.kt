package com.rfmarcos.sofadrop

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import java.util.concurrent.Executors

/** Shared look and helpers of the settings screens (styled like Projectivy's own settings). */
abstract class BaseSettingsFragment : GuidedStepSupportFragment() {

    protected val handler = Handler(Looper.getMainLooper())

    /** Whose lists and look this screen edits (see [forProfile]): the wallpapers' unless opened for the screensaver. */
    protected val profile: Profile get() = Profile.of(arguments?.getString(ARG_PROFILE))

    /** Open [screen] over this one, for the same profile. */
    protected fun open(screen: GuidedStepSupportFragment) {
        add(parentFragmentManager, screen.forProfile(profile))
    }

    override fun onProvideTheme(): Int = R.style.Theme_Sofadrop_GuidedStep

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The screensaver's lists and details look like the wallpapers': the breadcrumb tells them apart
        if (profile.isScreensaver && this !is ScreensaverFragment) guidanceStylist.breadcrumbView?.text = getString(R.string.screensaver_title)
        // Rows matched by id, so a row added or removed by refreshActions doesn't rebuild the list
        setActionsDiffCallback(object : DiffCallback<GuidedAction>() {
            override fun areItemsTheSame(oldItem: GuidedAction, newItem: GuidedAction) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: GuidedAction, newItem: GuidedAction) = false
        })
    }

    /** Ids of the section headers of this screen (see [header]). */
    private val headerIds = mutableSetOf<Long>()

    /** Long explanations shown in the left pane while their row is focused, so rows show only their value. */
    protected val explanations = mutableMapOf<Long, CharSequence>()
    private var guidanceText: CharSequence? = null

    override fun onCreateActionsStylist(): GuidedActionsStylist = object : GuidedActionsStylist() {
        // Checkbox / radio at the end of the row (see settings_action_item.xml)
        override fun onProvideItemLayoutId() = R.layout.settings_action_item

        override fun getItemViewType(action: GuidedAction) = if (action.id in headerIds) VIEW_TYPE_HEADER else super.getItemViewType(action)

        override fun onProvideItemLayoutId(viewType: Int) =
            if (viewType == VIEW_TYPE_HEADER) R.layout.settings_action_header else super.onProvideItemLayoutId(viewType)

        override fun onBindViewHolder(vh: ViewHolder, action: GuidedAction) {
            super.onBindViewHolder(vh, action)
            vh.itemView.findViewById<View>(R.id.header_divider)?.visibility =
                if (actions.firstOrNull()?.id == action.id) View.GONE else View.VISIBLE
        }

        // Choices open on their current value instead of the first option
        override fun onUpdateExpandedViewHolder(avh: ViewHolder?) {
            super.onUpdateExpandedViewHolder(avh)
            val index = avh?.action?.subActions?.indexOfFirst { it.isChecked } ?: return
            if (index > 0) subActionsGridView?.post { subActionsGridView?.selectedPosition = index }
        }
    }

    override fun onGuidedActionFocused(action: GuidedAction) {
        super.onGuidedActionFocused(action)
        val view = guidanceStylist.descriptionView ?: return
        if (guidanceText == null) guidanceText = view.text
        view.text = explanations[action.id] ?: guidanceText
    }

    /** Change the left pane's text, which it shows again when a row without an explanation is focused. */
    protected fun setGuidanceText(text: CharSequence) {
        guidanceText = text
        guidanceStylist.descriptionView?.text = text
    }

    /** Non-focusable section header between rows. */
    protected fun header(id: Long, title: CharSequence): GuidedAction {
        headerIds += id
        return GuidedAction.Builder(requireContext()).id(id).title(title).focusable(false).build()
    }

    /** Setting icon inside a translucent circle. */
    protected fun actionIcon(iconRes: Int): Drawable {
        val context = requireContext()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.action_icon_background))
        }
        val icon = AppCompatResources.getDrawable(context, iconRes)!!
        val inset = (resources.displayMetrics.density * 11).toInt()
        return LayerDrawable(arrayOf(circle, icon)).apply { setLayerInset(1, inset, inset, inset, inset) }
    }

    protected fun action(id: Long, icon: Int, title: CharSequence, description: CharSequence?) =
        GuidedAction.Builder(requireContext()).id(id).icon(actionIcon(icon)).title(title).description(description)

    /**
     * Checkbox row of a list. A column of identical icons is only noise, so an empty one keeps the text
     * aligned with the other rows.
     */
    protected fun checkbox(id: Long, title: CharSequence, description: CharSequence?, checked: Boolean) =
        GuidedAction.Builder(requireContext()).id(id).icon(ColorDrawable(Color.TRANSPARENT)).title(title).description(description)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(checked)

    /**
     * Row that expands into single-choice options with ids `id * SUB_ID_BASE + index`; [selected] is the
     * current option's label when the row describes it differently ("10 · about 20 wallpapers").
     */
    protected fun choice(id: Long, icon: Int, title: Int, current: String, options: List<String>, selected: String = current): GuidedAction =
        action(id, icon, getString(title), current).subActions(options(id, options, selected)).build()

    /** Single-choice options of row [id], the one labelled [current] marked as a radio button. */
    protected fun options(id: Long, labels: List<String>, current: String, descriptions: List<String?> = emptyList()) =
        labels.mapIndexed { index, label ->
            GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).description(descriptions.getOrNull(index))
                .checkSetId(RADIO_SET_BASE + id.toInt()).checked(label == current).build()
        }

    /** Text field edited with the on-screen keyboard; [shown] is what the row displays. */
    protected fun editable(
        id: Long, icon: Int, title: CharSequence, value: String, shown: CharSequence,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    ) = action(id, icon, title, shown)
        .editDescription(value)
        .descriptionEditable(true)
        .descriptionEditInputType(inputType)
        .build()

    /** "From year" row under an age limit: the year typed, if any, else "Not set". */
    protected fun yearRow(id: Long, maxAge: Int?) = editable(id, R.drawable.ic_act_schedule, getString(R.string.filters_year_title), "",
        maxAge?.takeIf { Settings.isYear(it) }?.toString() ?: getString(R.string.not_set), InputType.TYPE_CLASS_NUMBER)

    /**
     * Show [fresh] rows. Leanback stops expanding sub-actions once rows that have them are swapped for
     * new objects, so rows with the same ids are updated in place; rows that come or go are inserted or
     * removed around them (see the diff callback in [onViewCreated]), keeping the focus where it was.
     */
    protected fun refreshActions(fresh: List<GuidedAction>) {
        if (!isAdded) return
        confirming = null
        val current = actions
        fun GuidedAction.update(row: GuidedAction) = apply {
            // The icon too: some follow the value ("Nothing" opens nothing)
            icon = row.icon
            title = row.title
            description = row.description
            isEnabled = row.isEnabled
            isChecked = row.isChecked
            subActions = row.subActions
        }
        if (current.map { it.id } != fresh.map { it.id }) {
            setActions(fresh.map { row -> current.firstOrNull { it.id == row.id }?.update(row) ?: row })
            return
        }
        current.forEachIndexed { index, action ->
            action.update(fresh[index])
            notifyActionChanged(index)
        }
    }

    /** The option waiting for a second press (see [confirmed]); any refresh forgets it. */
    private var confirming: Long? = null

    /**
     * True when the option [action], which loses something, is chosen a second time in a row. The first
     * time it says [warning] and its row stays open (return false from onSubGuidedActionClicked then); a radio
     * option gives its check back to the option at [inUse], the one still in use.
     */
    protected fun confirmed(action: GuidedAction, warning: CharSequence, inUse: Int = -1): Boolean {
        if (confirming == action.id) return true
        confirming = action.id
        action.description = warning
        val subActions = guidedActionsStylist.expandedAction?.subActions ?: return false
        if (inUse >= 0) subActions.forEachIndexed { i, sub -> sub.isChecked = i == inUse }
        val adapter = guidedActionsStylist.subActionsGridView?.adapter ?: return false
        if (inUse >= 0) adapter.notifyItemRangeChanged(0, subActions.size)
        else subActions.indexOf(action).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
        return false
    }

    protected fun updateDescription(id: Long, description: CharSequence?) {
        val action = findActionById(id) ?: return
        if (action.description == description) return
        action.description = description
        notifyActionChanged(findActionPositionById(id))
    }

    /** State of [profile]'s set: progress, error, pending changes or when it was made. */
    protected fun generationStatus(): String {
        val count = WallpaperStore(requireContext(), profile).current().size
        return when {
            profile.generating -> profile.generationProgress?.let { (done, list, lists) ->
                getString(R.string.status_progress, resources.getQuantityString(R.plurals.wallpapers_count, done, done), list, lists)
            // Not started: the generation waits for the network (WorkManager's constraint)
            } ?: getString(if (isOnline()) R.string.status_generating else R.string.status_waiting_network)
            Sources.selected(profile).isEmpty() -> getString(R.string.status_select_list)
            // A "no lists" error is stale once lists are picked again
            profile.lastError.isNotEmpty() && profile.lastError != getString(R.string.error_no_lists) ->
                getString(R.string.status_error, profile.lastError)
            count == 0 -> getString(R.string.status_never)
            profile.hasPendingChanges -> getString(R.string.status_pending, count)
            else -> getString(R.string.status_ok, count, timeAgo(profile.lastGeneration)) +
                profile.lastNotice.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        }
    }

    private fun isOnline(): Boolean {
        val connectivity = requireContext().getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** "just now" or "3 minutes ago", to go mid-sentence. */
    protected fun timeAgo(time: Long): String =
        // "0 minutes ago" reads oddly; mid-sentence, Spanish and others capitalise it ("Hace 3 minutos")
        if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) getString(R.string.time_just_now)
        else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString().replaceFirstChar { it.lowercase() }

    /** Masked display of a secret value. */
    protected fun masked(value: String): String =
        if (value.isBlank()) getString(R.string.not_set) else "…" + value.takeLast(4)

    /** Run [task] off the main thread and deliver the result while the screen is still shown. */
    protected fun <T> background(task: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(task)
            handler.post { if (isAdded) done(result) }
        }
    }

    companion object {
        /** Sub-action ids are `parent * SUB_ID_BASE + index`. */
        const val SUB_ID_BASE = 1000L
        private const val RADIO_SET_BASE = 100
        private const val VIEW_TYPE_HEADER = 1
        private val executor = Executors.newCachedThreadPool()
        private const val ARG_PROFILE = "profile"

        /** This screen for [profile]'s settings (see [profile]). */
        fun <T : GuidedStepSupportFragment> T.forProfile(profile: Profile): T = apply {
            arguments = (arguments ?: Bundle()).apply { putString(ARG_PROFILE, profile.id) }
        }
    }
}
