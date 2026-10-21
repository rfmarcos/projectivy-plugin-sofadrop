package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance

/** Shows a QR code that opens a setup form on the phone (served by [PhoneSetupServer]). */
class PhoneSetupFragment : QrFragment() {

    private var server: PhoneSetupServer? = null

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_phone_title),
        "",
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun start() {
        val context = requireContext().applicationContext
        val setup = PhoneSetupServer(context) { changed ->
            handler.post { if (isAdded) toast(getString(R.string.phone_saved_toast, changed.joinToString(", "))) }
        }
        server = setup
        background({ setup.start() }) { result ->
            val url = result.getOrNull()
            if (url == null) {
                finishWith(getString(R.string.phone_no_network))
            } else {
                showQr(url, getString(R.string.phone_scan), getString(R.string.phone_scan_desc, url.substringBefore("?")))
            }
        }
    }

    override fun onPause() {
        server?.stop()
        server = null
        super.onPause()
    }
}
