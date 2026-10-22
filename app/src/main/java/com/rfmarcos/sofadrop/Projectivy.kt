package com.rfmarcos.sofadrop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Projectivy Launcher, from the plugin's side. */
object Projectivy {
    const val PACKAGE = "com.spocky.projengmenu"

    /**
     * Open Projectivy's settings with the path to pick Sofadrop as the wallpaper: Projectivy has no
     * action to set a wallpaper provider, only its settings screen (APPLICATION_PREFERENCES).
     */
    fun openWallpaperSettings(context: Context) {
        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Toast.makeText(context, R.string.use_in_projectivy_path, Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.projectivy_not_installed, Toast.LENGTH_LONG).show()
        }
    }
}
