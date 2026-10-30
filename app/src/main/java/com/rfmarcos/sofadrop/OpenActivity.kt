package com.rfmarcos.sofadrop

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * "Decide when clicked" ([Settings.clickAtTap]): Projectivy gets this activity for every wallpaper and it
 * opens the first of [ClickTargets.candidates] with the settings and apps of that moment, or the next one
 * when an app refuses. It shows nothing and, being exported, only takes a type and a TMDB id, looked up in
 * the current set.
 */
class OpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        val type = intent.getStringExtra(EXTRA_TYPE)
        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        val wallpaper = WallpaperStore(this).current().firstOrNull { it.type == type && it.tmdbId == tmdbId }
        val opened = wallpaper?.let { ClickTargets.candidates(this, it) }.orEmpty().firstOrNull { target ->
            try {
                startActivity(target)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Could not open $target", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not open $target", e)
                false
            }
        }
        Log.i(TAG, "$type/$tmdbId (${wallpaper?.title ?: "not in the current set"}): ${opened?.toUri(0) ?: "nothing to open"}")
        // Theme.NoDisplay: it must finish before it would be shown
        finish()
    }

    companion object {
        private const val TAG = "SofadropOpen"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TMDB_ID = "tmdb_id"

        fun intent(context: Context, wallpaper: StoredWallpaper): Intent =
            Intent().setClassName(context, OpenActivity::class.java.name)
                .putExtra(EXTRA_TYPE, wallpaper.type)
                .putExtra(EXTRA_TMDB_ID, wallpaper.tmdbId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
