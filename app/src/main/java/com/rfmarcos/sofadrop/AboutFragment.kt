package com.rfmarcos.sofadrop

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Version, support link, source code and credits. */
class AboutFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_about_title),
        getString(R.string.plugin_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_VERSION, R.drawable.ic_act_status, getString(R.string.about_version), BuildConfig.VERSION_NAME)
            .focusable(false).build()
        if (BuildConfig.SUPPORT_URL.isNotBlank()) {
            actions += action(ID_SUPPORT, R.drawable.ic_act_heart, getString(R.string.about_support), getString(R.string.about_support_desc)).build()
        }
        if (BuildConfig.SOURCE_URL.isNotBlank()) {
            actions += action(ID_SOURCE, R.drawable.ic_act_code, getString(R.string.about_source), BuildConfig.SOURCE_URL.removePrefix("https://")).build()
        }
        actions += action(ID_CREDITS, R.drawable.ic_act_movie, getString(R.string.about_credits), getString(R.string.about_credits_short)).build()
        explanations[ID_CREDITS] = getString(R.string.about_credits_desc)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_SUPPORT -> LinkQrDialog.newInstance(BuildConfig.SUPPORT_URL, R.string.about_support, R.string.about_support_scan)
                .show(parentFragmentManager, "support")
            ID_SOURCE -> LinkQrDialog.newInstance(BuildConfig.SOURCE_URL, R.string.about_source, R.string.about_source_scan)
                .show(parentFragmentManager, "source")
        }
    }

    companion object {
        private const val ID_VERSION = 1L
        private const val ID_SUPPORT = 2L
        private const val ID_SOURCE = 3L
        private const val ID_CREDITS = 4L
    }
}
