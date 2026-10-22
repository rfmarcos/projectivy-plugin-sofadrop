package com.rfmarcos.sofadrop

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service, limited to Projectivy's package (see res/xml/layout_reader.xml): when its
 * home screen shows, notes where rows, app cards and the clock sit so the settings preview can draw them.
 * It only reads; it never performs actions or sends anything anywhere.
 */
class LayoutReaderService : AccessibilityService() {

    private var lastCapture = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != PROJECTIVY) return
        // Home redraws often (focus, clock): one look every few seconds is plenty
        val now = SystemClock.uptimeMillis()
        if (now - lastCapture < 3000) return
        lastCapture = now
        val root = rootInActiveWindow ?: return
        if (root.packageName != PROJECTIVY) return
        val layout = LauncherLayout.capture(root, System.currentTimeMillis()) ?: return
        Settings.init(this)
        Settings.launcherLayout = layout
    }

    override fun onInterrupt() = Unit

    companion object {
        const val PROJECTIVY = "com.spocky.projengmenu"

        fun isEnabled(context: Context): Boolean {
            val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, LayoutReaderService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
