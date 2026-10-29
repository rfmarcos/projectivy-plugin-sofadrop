package com.rfmarcos.sofadrop

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** The last generation's log ([GenerationLog]), with a QR code to send its summary from the phone. */
class GenerationLogFragment : BaseSettingsFragment() {

    private val log by lazy { GenerationLog.read(requireContext()).orEmpty() }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.about_log),
        getString(R.string.log_desc),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_QR, R.drawable.ic_act_phone, getString(R.string.log_qr), getString(R.string.log_qr_desc)).build()
        // Lines are focusable rows so the remote can scroll through them; they do nothing when chosen.
        // "tmdb:trending_movies: 20 read, …" shows the list (or title, failure) as the row's title.
        log.lines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val title = line.substringBefore(": ").trim()
            actions += GuidedAction.Builder(requireContext()).id(ID_LINES + index).icon(ColorDrawable(Color.TRANSPARENT))
                .title(title).description(line.substringAfter(": ", "").takeIf { it.isNotEmpty() }).multilineDescription(true).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_QR) TextQrDialog.newInstance(GenerationLog.summary(log)).show(parentFragmentManager, "log")
    }

    companion object {
        private const val ID_QR = 1L
        private const val ID_LINES = 100L
    }
}
