package com.rfmarcos.sofadrop

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** What's new in the release found by [Updater.check], before installing it (the main screen installs it). */
class UpdateFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.update_notes_title, Settings.updateVersion),
        getString(R.string.update_notes_desc, BuildConfig.VERSION_NAME),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_INSTALL, R.drawable.ic_act_download, getString(R.string.update_install), null).build()
        actions += action(ID_NOT_NOW, R.drawable.ic_act_schedule, getString(R.string.update_not_now), null).build()
        // Notes are focusable rows so the remote can scroll through them; they do nothing when chosen
        Updater.releaseNotes(Settings.updateNotes).forEachIndexed { index, note ->
            val id = ID_NOTES + index
            actions += if (note.heading) header(id, note.title)
            else GuidedAction.Builder(requireContext()).id(id).icon(ColorDrawable(Color.TRANSPARENT))
                .title(note.title.ifEmpty { note.text }).description(note.text.takeIf { note.title.isNotEmpty() })
                .multilineDescription(true).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_INSTALL -> {
                parentFragmentManager.setFragmentResult(RESULT_INSTALL, bundleOf())
                parentFragmentManager.popBackStack()
            }
            ID_NOT_NOW -> parentFragmentManager.popBackStack()
        }
    }

    companion object {
        /** Fragment result asking the main screen to install the update. */
        const val RESULT_INSTALL = "update_install"
        private const val ID_INSTALL = 1L
        private const val ID_NOT_NOW = 2L
        private const val ID_NOTES = 100L
    }
}
