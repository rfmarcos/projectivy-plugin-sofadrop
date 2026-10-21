package com.rfmarcos.tmdbwallpapers

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
