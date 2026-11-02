package com.rfmarcos.sofadrop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
            val file = settingsFile(intent)
            val reconnect = intent.getStringArrayExtra(EXTRA_RECONNECT).orEmpty()
                .mapNotNull { name -> SourceProvider.entries.firstOrNull { it.name == name } }
            // A settings file opened with Sofadrop (from a file manager): asks before importing it
            if (file != null) GuidedStepSupportFragment.add(supportFragmentManager, SettingsImportFragment.newInstance(file))
            // Just imported: the accounts the other TV had, to connect again
            else if (reconnect.isNotEmpty()) GuidedStepSupportFragment.add(supportFragmentManager, AccountsFragment.reconnect(reconnect))
            // First run: the wizard over the settings, which are there when it ends
            else if (Wizard.shouldOffer()) Wizard.start(supportFragmentManager)
        }

        // Back on the first screen finishes the settings. Otherwise Android 12+ moves a launcher task to
        // the back instead, and on the Chromecast's Android 14 that path can crash the whole system
        // (system_server restart: "positionChildAt ... is not a child", Projectivy loses accessibility)
        // when the task goes away mid-transition. Sub-screens still go back as usual.
        onBackPressedDispatcher.addCallback(this) {
            if (supportFragmentManager.backStackEntryCount == 0) {
                finish()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    companion object {
        /** Services to connect again after importing a settings file (see SettingsImportFragment). */
        const val EXTRA_RECONNECT = "reconnect"
    }

    @Suppress("DEPRECATION")
    private fun settingsFile(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    override fun onPause() {
        super.onPause()
        // Leaving the settings applies what changed instead of waiting for the next scheduled update
        if (!isFinishing) return
        for (profile in listOf(Profile.WALLPAPER, Profile.SCREENSAVER).filter { it.source == it }) {
            // The screensaver's own set is made the first time here too
            val due = profile.hasPendingChanges || (profile.isScreensaver && profile.lastGeneration == 0L)
            if (due && !profile.generating && Sources.selected(profile).isNotEmpty()) GenerateWallpapersWorker.runNow(this, profile, replace = true)
        }
    }
}
