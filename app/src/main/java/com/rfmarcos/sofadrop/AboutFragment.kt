package com.rfmarcos.sofadrop

import android.os.Bundle
import android.widget.Toast
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
        // Where updates are possible, pressing the version checks for one right away
        actions += action(ID_VERSION, R.drawable.ic_act_status, getString(R.string.about_version), versionText())
            .focusable(Updater.canCheck).build()
        actions += action(ID_USE_IN_PROJECTIVY, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title),
            getString(R.string.use_in_projectivy_desc)).multilineDescription(true).build()
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
            ID_VERSION -> checkForUpdate()
            ID_USE_IN_PROJECTIVY -> Projectivy.openWallpaperSettings(requireContext())
            ID_SUPPORT -> LinkQrDialog.newInstance(BuildConfig.SUPPORT_URL, R.string.about_support, R.string.about_support_scan)
                .show(parentFragmentManager, "support")
            ID_SOURCE -> LinkQrDialog.newInstance(BuildConfig.SOURCE_URL, R.string.about_source, R.string.about_source_scan)
                .show(parentFragmentManager, "source")
        }
    }

    private fun versionText(): String = when {
        Updater.available -> getString(R.string.about_version_update, BuildConfig.VERSION_NAME, Settings.updateVersion)
        Updater.canCheck -> getString(R.string.about_version_check, BuildConfig.VERSION_NAME)
        else -> BuildConfig.VERSION_NAME
    }

    /** Check now, whenever the last check was; a new version is then offered at the top of the settings. */
    private fun checkForUpdate() {
        val checkedBefore = Settings.updateCheckedAt
        updateDescription(ID_VERSION, getString(R.string.update_checking))
        background({ Updater.check(force = true) }) {
            updateDescription(ID_VERSION, versionText())
            val message = when {
                Settings.updateCheckedAt == checkedBefore -> getString(R.string.update_check_failed)
                Updater.available -> getString(R.string.update_found, Settings.updateVersion)
                else -> getString(R.string.update_none)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ID_VERSION = 1L
        private const val ID_SUPPORT = 2L
        private const val ID_SOURCE = 3L
        private const val ID_CREDITS = 4L
        private const val ID_USE_IN_PROJECTIVY = 5L
    }
}
