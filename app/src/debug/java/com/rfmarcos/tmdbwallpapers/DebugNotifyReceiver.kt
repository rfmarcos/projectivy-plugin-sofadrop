package com.rfmarcos.tmdbwallpapers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract

/**
 * Debug builds only: lets adb ask the plugin to send the update broadcast to Projectivy
 * with a custom provider id / reason, to check what Projectivy reacts to.
 *
 * adb shell am broadcast -n com.rfmarcos.tmdbwallpapers/.DebugNotifyReceiver --es id <id> --ei reason <1|2>
 */
class DebugNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: context.getString(R.string.plugin_uuid)
        val reason = intent.getIntExtra("reason", WallpaperProviderContract.UpdateReason.DATA_CHANGED)
        context.sendBroadcast(Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
            `package` = "com.spocky.projengmenu"
            putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, id)
            putExtra(WallpaperProviderContract.EXTRA_UPDATE_REASON, reason)
        })
        Log.i("DebugNotify", "Sent update broadcast id=$id reason=$reason")
    }
}
