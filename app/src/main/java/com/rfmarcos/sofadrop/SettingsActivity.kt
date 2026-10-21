package com.rfmarcos.sofadrop

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
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
    }

    override fun onPause() {
        super.onPause()
        // Leaving the settings applies what changed instead of waiting for the next scheduled update
        if (isFinishing && Settings.hasPendingChanges && !Settings.generating) GenerateWallpapersWorker.runNow(this, replace = true)
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
