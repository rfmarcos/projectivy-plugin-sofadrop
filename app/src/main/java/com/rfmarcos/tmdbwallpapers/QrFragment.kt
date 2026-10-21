package com.rfmarcos.tmdbwallpapers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Screen with a big QR code on the left, to finish a login or a setup from the phone. */
abstract class QrFragment : BaseSettingsFragment() {

    private var qrImage: ImageView? = null
    private var qrProgress: ProgressBar? = null
    private var qrCode: TextView? = null

    @Volatile
    protected var active = false

    override fun onCreateGuidanceStylist(): GuidanceStylist = object : GuidanceStylist() {
        override fun onProvideLayoutId() = R.layout.qr_guidance

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, guidance: Guidance): View {
            val view = super.onCreateView(inflater, container, guidance)
            qrImage = view.findViewById(R.id.qr_image)
            qrProgress = view.findViewById(R.id.qr_progress)
            qrCode = view.findViewById(R.id.qr_code)
            return view
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: android.os.Bundle?) {
        actions += action(ID_CLOSE, R.drawable.ic_act_logout, getString(R.string.close), null).build()
    }

    override fun onResume() {
        super.onResume()
        if (!active) {
            active = true
            start()
        }
    }

    override fun onPause() {
        active = false
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_CLOSE) {
            active = false
            parentFragmentManager.popBackStack()
        }
    }

    /** Called when the screen is shown: request what is needed and call [showQr]. */
    protected abstract fun start()

    protected fun showQr(url: String, title: CharSequence, description: CharSequence, code: String? = null) {
        qrImage?.setImageBitmap(Qr.bitmap(url, 480))
        qrProgress?.visibility = View.GONE
        qrCode?.apply {
            text = code
            visibility = if (code.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        guidanceStylist.titleView?.text = title
        guidanceStylist.descriptionView?.text = description
    }

    protected fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    protected fun finishWith(message: String) {
        if (!isAdded) return
        toast(message)
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ID_CLOSE = 1L
    }
}
