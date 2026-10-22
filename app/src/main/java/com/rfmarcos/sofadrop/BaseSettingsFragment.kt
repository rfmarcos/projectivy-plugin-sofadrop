package com.rfmarcos.sofadrop

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

    // Choices open on their current value instead of the first option
    override fun onCreateActionsStylist(): GuidedActionsStylist = object : GuidedActionsStylist() {
        override fun onUpdateExpandedViewHolder(avh: ViewHolder?) {
            super.onUpdateExpandedViewHolder(avh)
            val index = avh?.action?.subActions?.indexOfFirst { it.isChecked } ?: return
            if (index > 0) subActionsGridView?.post { subActionsGridView?.selectedPosition = index }
        }
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

    /** Checkbox row of a list (no icon: a column of identical icons is only noise). */
    protected fun checkbox(id: Long, title: CharSequence, description: CharSequence?, checked: Boolean) =
        GuidedAction.Builder(requireContext()).id(id).title(title).description(description)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(checked)

    /** Row that expands into single-choice options with ids `id * SUB_ID_BASE + index`. */
    protected fun choice(id: Long, icon: Int, title: Int, current: String, options: List<String>): GuidedAction =
        action(id, icon, getString(title), current).subActions(options(id, options, current)).build()

    /** Single-choice options of row [id], the one labelled [current] marked as a radio button. */
    protected fun options(id: Long, labels: List<String>, current: String, descriptions: List<String?> = emptyList()) =
        labels.mapIndexed { index, label ->
            GuidedAction.Builder(requireContext()).id(id * SUB_ID_BASE + index).title(label).description(descriptions.getOrNull(index))
                .checkSetId(RADIO_SET_BASE + id.toInt()).checked(label == current).build()
        }

    /** Text field edited with the on-screen keyboard; [shown] is what the row displays. */
    protected fun editable(id: Long, icon: Int, title: CharSequence, value: String, shown: CharSequence) =
        action(id, icon, title, shown)
            .editDescription(value)
            .descriptionEditable(true)
            .descriptionEditInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            .build()

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
        private val executor = Executors.newCachedThreadPool()
    }
}
