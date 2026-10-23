package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Which title the settings preview shows, to try settings on it: automatic, one of the generated
 * wallpapers (picked in the full-screen viewer) or any TMDB id.
 */
class PreviewPinFragment : PreviewSettingsFragment() {

    /** Pins of a typed number that is both a movie and a series, shown as rows to choose from. */
    private var candidates: List<Pair<String, WallpaperInfo>> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.preview_pin_title),
        getString(R.string.preview_pin_desc),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // The explanation stays in view under the preview
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = View.VISIBLE
    }

    // Back from the viewer: it may have picked a title
    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun rows(): List<GuidedAction> {
        val pick = action(ID_PICK, R.drawable.ic_act_preview, getString(R.string.preview_pin_pick), getString(R.string.preview_pin_pick_desc)).build()
        pick.isEnabled = WallpaperStore(requireContext()).current().isNotEmpty()
        return listOf(
            action(ID_AUTO, R.drawable.ic_act_shuffle, getString(R.string.preview_pin_automatic), autoDescription()).build(),
            pick,
            editable(ID_TMDB, R.drawable.ic_act_search, getString(R.string.preview_pin_id), Settings.previewPin, tmdbDescription()),
        ) + candidates.mapIndexed { index, (_, info) ->
            val series = info.type == MediaType.TV
            action(ID_CANDIDATE_BASE + index, if (series) R.drawable.ic_act_tv else R.drawable.ic_act_movie,
                listOf(info.title, info.year).filter { it.isNotEmpty() }.joinToString(" · "),
                getString(if (series) R.string.preview_pin_series else R.string.preview_pin_movie)).build()
        }
    }

    private fun autoDescription() =
        getString(if (Settings.previewPin.isEmpty()) R.string.preview_pin_auto_on else R.string.preview_pin_auto_desc)

    private fun tmdbDescription() =
        if (Settings.previewPin.isEmpty()) getString(R.string.preview_pin_id_desc)
        else listOf(Settings.previewPinTitle, Settings.previewPin).filter { it.isNotEmpty() }.joinToString(" · ")

    private fun refreshRows() {
        updateDescription(ID_AUTO, autoDescription())
        updateDescription(ID_TMDB, tmdbDescription())
        findActionById(ID_TMDB)?.editDescription = Settings.previewPin
    }

    private fun setPin(pin: String, title: String) {
        Settings.pinPreview(pin, title)
        if (candidates.isNotEmpty()) {
            candidates = emptyList()
            actions = rows()
        } else {
            refreshRows()
        }
        schedulePreview(0)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_AUTO -> setPin("", "")
            action.id == ID_PICK -> startActivity(Intent(requireContext(), PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_PICK, true))
            action.id >= ID_CANDIDATE_BASE -> candidates.getOrNull((action.id - ID_CANDIDATE_BASE).toInt())?.let { (pin, info) -> setPin(pin, info.title) }
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id != ID_TMDB) return GuidedAction.ACTION_ID_CURRENT
        val text = action.editDescription?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            setPin("", "")
            return GuidedAction.ACTION_ID_CURRENT
        }
        val pins = pinCandidates(text)
        val context = requireContext().applicationContext
        // Checked before saving, so a typo doesn't replace the current pin
        background({ pinInfos(context, pins) }) { result ->
            val found = result.getOrDefault(emptyList())
            when {
                found.size == 1 -> setPin(found[0].first, found[0].second.title)
                found.size > 1 -> {
                    candidates = found
                    actions = rows()
                    setSelectedActionPosition(actions.size - found.size)
                }
                else -> {
                    refreshRows()
                    val message = result.exceptionOrNull()?.let { getString(R.string.preview_error, it.message ?: it.javaClass.simpleName) }
                        ?: getString(R.string.preview_pin_not_found, text)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    companion object {
        private const val ID_AUTO = 1L
        private const val ID_PICK = 2L
        private const val ID_TMDB = 3L
        private const val ID_CANDIDATE_BASE = 10L

        /** What the preview shows, for the row that opens this screen. */
        fun summary(context: Context) =
            Settings.previewPinTitle.ifEmpty { Settings.previewPin }.ifEmpty { context.getString(R.string.preview_pin_automatic) }
    }
}
