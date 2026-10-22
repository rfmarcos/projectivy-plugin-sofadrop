package com.rfmarcos.sofadrop

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)

        if (!packageManager.isApplicationInstalled(PROJECTIVY_PACKAGE)) {
            Toast.makeText(this, R.string.projectivy_not_installed, Toast.LENGTH_LONG).show()
        }

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
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

    override fun onPause() {
        super.onPause()
        // Leaving the settings applies what changed instead of waiting for the next scheduled update
        if (isFinishing && Settings.hasPendingChanges && !Settings.generating && Sources.selected().isNotEmpty()) GenerateWallpapersWorker.runNow(this, replace = true)
    }

    private fun PackageManager.isApplicationInstalled(packageName: String): Boolean = try {
        getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
    }
}
