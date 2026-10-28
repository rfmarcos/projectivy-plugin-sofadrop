package com.rfmarcos.sofadrop

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import java.util.concurrent.Executors

/** Shared look and helpers of the settings screens (styled like Projectivy's own settings). */
abstract class BaseSettingsFragment : GuidedStepSupportFragment() {

    protected val handler = Handler(Looper.getMainLooper())

    override fun onProvideTheme(): Int = R.style.Theme_Sofadrop_GuidedStep

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
     * new objects, so rows with the same ids are updated in place; a different set of rows reopens
     * this screen with [reopen] (sub-screens only).
     */
    protected fun refreshActions(fresh: List<GuidedAction>, reopen: () -> GuidedStepSupportFragment) {
        if (!isAdded) return
        val current = actions
        if (current.map { it.id } != fresh.map { it.id }) {
            parentFragmentManager.popBackStack()
            add(parentFragmentManager, reopen())
            return
        }
        current.forEachIndexed { index, action ->
            val row = fresh[index]
            action.title = row.title
            action.description = row.description
            action.isEnabled = row.isEnabled
            action.isChecked = row.isChecked
            action.subActions = row.subActions
            notifyActionChanged(index)
        }
    }

    protected fun updateDescription(id: Long, description: CharSequence?) {
        val action = findActionById(id) ?: return
        if (action.description == description) return
        action.description = description
        notifyActionChanged(findActionPositionById(id))
    }

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
    }
}
