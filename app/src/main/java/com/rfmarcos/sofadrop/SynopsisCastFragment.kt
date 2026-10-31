package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** The text under the header: how long the synopsis is, and the cast line under it (with or without faces). */
class SynopsisCastFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.synopsis_cast_title),
        getString(R.string.synopsis_cast_desc),
        getString(R.string.setting_details_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        return listOf(
            choice(ID_SYNOPSIS, R.drawable.ic_act_count, R.string.synopsis_title, synopsisLabel(context, profile.overviewLines),
                Settings.OVERVIEW_OPTIONS.map { synopsisLabel(context, it) }),
            action(ID_CAST, R.drawable.ic_act_account, getString(R.string.cast_title), castLabel(context, profile.showCast))
                .subActions(options(ID_CAST, listOf(false, true).map { castLabel(context, it) }, castLabel(context, profile.showCast),
                    listOf(null, getString(R.string.cast_shown_desc)))).build(),
            castPhotosAction(),
        )
    }

    /** Faces next to the cast names; only meaningful while the cast is shown (kept visible so rows don't jump). */
    private fun castPhotosAction(): GuidedAction {
        val context = requireContext()
        val action = action(ID_CAST_PHOTOS, R.drawable.ic_act_account, getString(R.string.cast_photos_title),
            if (profile.showCast) castPhotosLabel(context, profile.castPhotos) else getString(R.string.cast_photos_disabled))
            .subActions(options(ID_CAST_PHOTOS, Settings.CAST_PHOTO_MODES.map { castPhotosLabel(context, it) }, castPhotosLabel(context, profile.castPhotos),
                listOf(null, getString(R.string.cast_photos_grouped_desc), getString(R.string.cast_photos_each_desc)))).build()
        action.isEnabled = profile.showCast
        return action
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_SYNOPSIS -> {
                profile.overviewLines = Settings.OVERVIEW_OPTIONS[index]
                updateDescription(ID_SYNOPSIS, synopsisLabel(requireContext(), profile.overviewLines))
            }
            ID_CAST -> {
                profile.showCast = index == 1
                updateDescription(ID_CAST, castLabel(requireContext(), profile.showCast))
                refreshActions(rows())
            }
            ID_CAST_PHOTOS -> {
                profile.castPhotos = Settings.CAST_PHOTO_MODES[index]
                updateDescription(ID_CAST_PHOTOS, castPhotosLabel(requireContext(), profile.castPhotos))
            }
        }
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_SYNOPSIS = 1L
        private const val ID_CAST = 2L
        private const val ID_CAST_PHOTOS = 3L

        fun synopsisLabel(context: Context, lines: Int) = context.getString(
            when (lines) {
                0 -> R.string.synopsis_hidden
                2 -> R.string.synopsis_short
                else -> R.string.synopsis_full
            }
        )

        fun castLabel(context: Context, shown: Boolean) = context.getString(if (shown) R.string.cast_shown else R.string.cast_hidden)

        fun castPhotosLabel(context: Context, mode: String) = context.getString(
            when (mode) {
                Settings.CAST_PHOTOS_GROUPED -> R.string.cast_photos_grouped
                Settings.CAST_PHOTOS_EACH -> R.string.cast_photos_each
                else -> R.string.cast_photos_hidden
            }
        )

        /** "Full synopsis · cast with faces", for the details screen. */
        fun summary(context: Context, profile: Profile): String = listOf(
            context.getString(R.string.synopsis_cast_summary, synopsisLabel(context, profile.overviewLines)),
            context.getString(
                when {
                    !profile.showCast -> R.string.cast_summary_hidden
                    profile.castPhotos == Settings.CAST_PHOTOS_NONE -> R.string.cast_summary_names
                    else -> R.string.cast_summary_photos
                }
            ),
        ).joinToString(" · ")
    }
}
