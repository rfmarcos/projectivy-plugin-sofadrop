package com.rfmarcos.sofadrop

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import java.net.Inet4Address

/**
 * How to make Sofascreen the screensaver where Android lists no other apps' ones (Google TV): once, with adb
 * from a computer. The QR opens the same guide, with pictures, on a phone.
 */
class ScreensaverSetupFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setup_title),
        getString(R.string.setup_text, ipAddress(requireContext()) ?: getString(R.string.setup_ip_unknown)),
        getString(R.string.screensaver_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateGuidanceStylist(): GuidanceStylist = object : GuidanceStylist() {
        override fun onProvideLayoutId() = R.layout.setup_guidance

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, guidance: Guidance): View =
            super.onCreateView(inflater, container, guidance).also {
                it.findViewById<ImageView>(R.id.setup_qr).setImageDrawable(Qr.drawable(resources, GUIDE_URL))
            }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val ip = ipAddress(context) ?: "IP"
        val steps = listOfNotNull(
            getString(R.string.setup_developer_title) to getString(R.string.setup_developer_desc),
            getString(R.string.setup_debugging_title) to getString(R.string.setup_debugging_desc),
            getString(R.string.setup_adb_title) to getString(R.string.setup_adb_desc, ip, ScreensaverFragment.component(context)),
            (getString(R.string.setup_projectivy_title) to getString(R.string.setup_projectivy_desc)).takeIf { Projectivy.isInstalled(context) },
        )
        actions += steps.mapIndexed { index, (title, description) -> row(index + 1L, getString(R.string.click_step, index + 1, title), description) }
        actions += row(ID_UNDO, getString(R.string.setup_undo_title), getString(R.string.setup_undo_desc, GOOGLE_SCREENSAVER))
    }

    // Rows only to read: focusable, so a long one can be scrolled to
    private fun row(id: Long, title: String, description: String) =
        GuidedAction.Builder(requireContext()).id(id).icon(ColorDrawable(Color.TRANSPARENT)).title(title).description(description)
            .multilineDescription(true).build()

    companion object {
        private const val ID_UNDO = 10L
        private const val GUIDE_URL = "https://github.com/rfmarcos/projectivy-plugin-sofadrop#screensaver"
        private const val GOOGLE_SCREENSAVER = "com.google.android.apps.tv.dreamx/.service.Backdrop"

        /** Whether Android has a screensaver picker that lists Sofascreen (not on Google TV). */
        fun hasSystemPicker(context: Context) =
            Intent(android.provider.Settings.ACTION_DREAM_SETTINGS).resolveActivity(context.packageManager) != null

        /** Sofascreen is Android's screensaver; null where Android doesn't let apps read that setting. */
        fun isActive(context: Context): Boolean? = runCatching {
            val resolver = context.contentResolver
            val components = android.provider.Settings.Secure.getString(resolver, "screensaver_components").orEmpty()
            val ours = ComponentName(context, SofascreenService::class.java)
            android.provider.Settings.Secure.getInt(resolver, "screensaver_enabled", 1) == 1 &&
                components.split(',').any { ComponentName.unflattenFromString(it.trim()) == ours }
        }.getOrNull()

        /** "On", or "Not on yet · how to switch it on" (Android's settings where it has a picker). */
        fun status(context: Context): String {
            val how = context.getString(if (hasSystemPicker(context)) R.string.screensaver_system_opens_settings else R.string.screensaver_system_opens_steps)
            return when (isActive(context)) {
                true -> context.getString(R.string.screensaver_system_on)
                false -> context.getString(R.string.screensaver_system_off) + " · " + how
                null -> how.replaceFirstChar { it.uppercase() }
            }
        }

        /** Android's screensaver picker, or these steps where there is none. */
        fun open(from: GuidedStepSupportFragment) {
            val context = from.requireContext()
            if (hasSystemPicker(context)) {
                try {
                    from.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS))
                    return
                } catch (_: ActivityNotFoundException) {
                }
            }
            GuidedStepSupportFragment.add(from.parentFragmentManager, ScreensaverSetupFragment())
        }

        /** The TV's address on the local network, for adb. */
        private fun ipAddress(context: Context): String? {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val addresses = manager.getLinkProperties(manager.activeNetwork)?.linkAddresses ?: return null
            return addresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }
}
