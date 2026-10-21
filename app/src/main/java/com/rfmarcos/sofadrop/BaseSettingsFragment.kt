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
import java.util.concurrent.Executors

/** Shared look and helpers of the settings screens (styled like Projectivy's own settings). */
abstract class BaseSettingsFragment : GuidedStepSupportFragment() {

    protected val handler = Handler(Looper.getMainLooper())

    override fun onProvideTheme(): Int = R.style.Theme_Sofadrop_GuidedStep

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

    /** Text field edited with the on-screen keyboard; [shown] is what the row displays. */
    protected fun editable(id: Long, icon: Int, title: CharSequence, value: String, shown: CharSequence) =
        action(id, icon, title, shown)
            .editDescription(value)
            .descriptionEditable(true)
            .descriptionEditInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            .build()

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
        private val executor = Executors.newCachedThreadPool()
    }
}
