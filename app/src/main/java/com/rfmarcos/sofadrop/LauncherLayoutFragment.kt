package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** One-time setup of the layout reader, so the preview shows the user's own Projectivy home. */
class LauncherLayoutFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.launcher_layout_title),
        getString(R.string.launcher_layout_description),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    override fun onResume() {
        super.onResume()
        // The reader may have been turned on, or a layout captured, while away
        refreshActions(buildRows()) { LauncherLayoutFragment() }
    }

    private fun buildRows(): List<GuidedAction> {
        val context = requireContext()
        val enabled = LayoutReaderService.isEnabled(context)
        val layout = Settings.launcherLayout
        val rows = mutableListOf(
            action(ID_OPEN, R.drawable.ic_act_visibility, getString(R.string.layout_reader_open),
                getString(if (enabled) R.string.layout_reader_on else R.string.layout_reader_off)).multilineDescription(true).build(),
            action(ID_STATUS, R.drawable.ic_act_status, getString(R.string.layout_status_title),
                if (layout == null) getString(R.string.layout_status_none)
                else getString(R.string.layout_status_ok, layout.rows.size, layout.cards.size, capturedLabel(context, layout.capturedAt)))
                .multilineDescription(true).focusable(false).build(),
        )
        if (layout != null) rows += action(ID_FORGET, R.drawable.ic_act_block, getString(R.string.layout_forget),
            getString(R.string.layout_forget_desc)).build()
        return rows
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_OPEN -> runCatching { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { updateDescription(ID_OPEN, getString(R.string.layout_reader_path)) }
            ID_FORGET -> {
                Settings.launcherLayout = null
                refreshActions(buildRows()) { LauncherLayoutFragment() }
            }
        }
    }

    companion object {
        private const val ID_OPEN = 1L
        private const val ID_STATUS = 2L
        private const val ID_FORGET = 3L

        /** "9:38", or "12 Oct, 9:38" for an older capture. */
        fun capturedLabel(context: Context, time: Long): String = DateUtils.formatDateTime(context, time,
            DateUtils.FORMAT_SHOW_TIME or (if (DateUtils.isToday(time)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))

        /** Description of the "Launcher layout" row: where the preview's layout comes from. */
        fun summary(context: Context): String = Settings.launcherLayout?.let {
            context.getString(R.string.launcher_layout_real, capturedLabel(context, it.capturedAt))
        } ?: context.getString(R.string.launcher_layout_generic)
    }
}
