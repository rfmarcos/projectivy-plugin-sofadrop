package com.rfmarcos.tmdbwallpapers

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import java.util.concurrent.Executors

/**
 * Centered QR dialog (in the style of apps like Nuvio): scan the code with the phone and approve,
 * nothing to type on the TV. Subclasses start their flow in [start] and call [showQr] / [finish].
 */
abstract class QrDialog : DialogFragment() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var instructions: TextView
    private lateinit var qr: ImageView
    private lateinit var qrProgress: ProgressBar
    private lateinit var code: TextView
    private lateinit var url: TextView
    private lateinit var countdown: TextView
    private lateinit var status: TextView
    private lateinit var statusProgress: ProgressBar
    private var expiresAt = 0L

    @Volatile
    protected var active = false

    protected abstract val titleRes: Int
    protected abstract val iconRes: Int
    protected open val buttonRes: Int = R.string.cancel

    /** Request what is needed and call [showQr]; runs when the dialog is shown. */
    protected abstract fun start()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), R.style.Theme_TmdbWallpapers_QrDialog)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.qr_dialog, container, false)
        title = view.findViewById(R.id.dialog_title)
        instructions = view.findViewById(R.id.dialog_instructions)
        qr = view.findViewById(R.id.dialog_qr)
        qrProgress = view.findViewById(R.id.dialog_qr_progress)
        code = view.findViewById(R.id.dialog_code)
        url = view.findViewById(R.id.dialog_url)
        countdown = view.findViewById(R.id.dialog_countdown)
        status = view.findViewById(R.id.dialog_status)
        statusProgress = view.findViewById(R.id.dialog_status_progress)
        view.findViewById<ImageView>(R.id.dialog_icon).setImageDrawable(AppCompatResources.getDrawable(requireContext(), iconRes))
        title.setText(titleRes)
        status.setText(R.string.qr_preparing)
        view.findViewById<Button>(R.id.dialog_cancel).apply {
            setText(buttonRes)
            setOnClickListener { dismissAllowingStateLoss() }
            requestFocus()
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (!active) {
            active = true
            start()
        }
    }

    override fun onStop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        // Tell the settings screen to refresh (account connected, keys saved…)
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
        super.onDestroy()
    }

    protected fun showQr(target: String, instructionsText: CharSequence, userCode: String?, displayUrl: String, expiresInSeconds: Int?) {
        if (!isAdded) return
        qr.setImageBitmap(Qr.bitmap(target, 440))
        qrProgress.visibility = View.GONE
        instructions.text = instructionsText
        code.text = userCode
        code.visibility = if (userCode.isNullOrBlank()) View.GONE else View.VISIBLE
        url.text = displayUrl
        setStatus(getString(R.string.qr_waiting))
        if (expiresInSeconds != null) {
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L
            tick()
        }
    }

    private fun tick() {
        if (!isAdded) return
        val left = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        countdown.text = getString(R.string.qr_expires, left / 60, left % 60)
        if (left > 0) handler.postDelayed(::tick, 1000)
    }

    protected fun setStatus(text: CharSequence, working: Boolean = true) {
        if (!isAdded) return
        status.text = text
        statusProgress.visibility = if (working) View.VISIBLE else View.GONE
    }

    protected fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    protected fun finish(message: String) {
        if (!isAdded) return
        toast(message)
        dismissAllowingStateLoss()
    }

    /** Run [task] off the main thread and deliver the result while the dialog is still shown. */
    protected fun <T> background(task: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(task)
            handler.post { if (isAdded) done(result) }
        }
    }

    companion object {
        const val RESULT_KEY = "qr_dialog_closed"
        private val executor = Executors.newCachedThreadPool()
    }
}

/** Trakt device flow; the QR opens trakt.tv/activate/<code> with the code filled in. */
class TraktLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_trakt_title
    override val iconRes = R.drawable.ic_act_trakt

    override fun start() {
        val client = TraktClient(Settings.traktClientId, Settings.traktClientSecret)
        background({ client.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                showQr(
                    "${code.verificationUrl.trimEnd('/')}/${code.userCode}",
                    getString(R.string.qr_instructions_code, code.verificationUrl.removePrefix("https://")),
                    code.userCode, code.verificationUrl, code.expiresIn,
                )
                poll(client, code)
            }.onFailure { finish(getString(R.string.trakt_login_failed, it.message)) }
        }
    }

    private fun poll(client: TraktClient, code: TraktClient.DeviceCode) {
        background({
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            var outcome: TraktClient.Poll = TraktClient.Poll.Pending
            while (active && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                outcome = client.pollDeviceToken(code.deviceCode)
                when (outcome) {
                    TraktClient.Poll.Pending -> continue
                    TraktClient.Poll.SlowDown -> interval += 1000
                    else -> break
                }
            }
            outcome
        }) { result ->
            when (val outcome = result.getOrNull()) {
                TraktClient.Poll.Connected -> finish(getString(R.string.trakt_login_ok, Settings.traktUser.ifBlank { "Trakt" }))
                is TraktClient.Poll.Failed -> finish(
                    when (outcome.code) {
                        410 -> getString(R.string.trakt_login_expired)
                        418 -> getString(R.string.trakt_login_denied)
                        else -> getString(R.string.trakt_login_failed, "HTTP ${outcome.code}")
                    }
                )
                null -> finish(getString(R.string.trakt_login_failed, result.exceptionOrNull()?.message))
                else -> if (active) finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/** MDBList device flow (app registered on mdblist.com/developer). */
class MdbListLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_mdblist_title
    override val iconRes = R.drawable.ic_act_list

    override fun start() {
        val auth = MdbListClient.Auth(Settings.mdbListClientId)
        background({ auth.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                showQr(
                    code.verificationUriComplete,
                    getString(R.string.qr_instructions_code, code.verificationUri.removePrefix("https://")),
                    code.userCode, code.verificationUri, code.expiresIn,
                )
                poll(auth, code)
            }.onFailure { finish(getString(R.string.mdblist_failed, it.message)) }
        }
    }

    private fun poll(auth: MdbListClient.Auth, code: MdbListClient.DeviceCode) {
        background({
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            var outcome: MdbListClient.Poll = MdbListClient.Poll.Pending
            while (active && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                outcome = auth.poll(code.deviceCode)
                when (outcome) {
                    MdbListClient.Poll.Pending -> continue
                    MdbListClient.Poll.SlowDown -> interval += 5000
                    else -> break
                }
            }
            outcome
        }) { result ->
            when (val outcome = result.getOrNull()) {
                MdbListClient.Poll.Connected -> finish(getString(R.string.mdblist_login_ok, Settings.mdbListUser.ifBlank { "MDBList" }))
                is MdbListClient.Poll.Failed -> finish(
                    when (outcome.error) {
                        "expired_token" -> getString(R.string.trakt_login_expired)
                        "access_denied" -> getString(R.string.trakt_login_denied)
                        else -> getString(R.string.mdblist_failed, outcome.error)
                    }
                )
                null -> finish(getString(R.string.mdblist_failed, result.exceptionOrNull()?.message))
                else -> if (active) finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/** TMDB account: the QR opens the page where the user approves a request token. */
class TmdbLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_tmdb_account_title
    override val iconRes = R.drawable.ic_act_account

    override fun start() {
        val account = TmdbAccount(TmdbClient(Settings.token, requireContext().cacheDir))
        background({ account.requestToken() }) { result ->
            result.onSuccess { (token, url) ->
                showQr(url, getString(R.string.tmdb_login_description), null, "themoviedb.org", 10 * 60)
                poll(account, token)
            }.onFailure { finish(getString(R.string.tmdb_login_failed, it.message)) }
        }
    }

    private fun poll(account: TmdbAccount, token: String) {
        background({
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            var connected = false
            while (active && !connected && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                connected = runCatching { account.tryCreateSession(token) }.getOrDefault(false)
            }
            connected
        }) { result ->
            when {
                result.getOrNull() == true -> finish(getString(R.string.tmdb_login_ok, Settings.tmdbUser.ifBlank { "TMDB" }))
                active -> finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/** QR to a temporary local web page where the phone can paste keys or add public lists. */
class PhoneSetupDialog : QrDialog() {
    override val titleRes = R.string.setting_phone_title
    override val iconRes = R.drawable.ic_act_phone
    override val buttonRes = R.string.close

    private var server: PhoneSetupServer? = null

    override fun start() {
        val setup = PhoneSetupServer(requireContext().applicationContext) { changed ->
            view?.post { setStatus(getString(R.string.phone_saved_toast, changed.joinToString(", ")), working = false) }
        }
        server = setup
        background({ setup.start() }) { result ->
            val url = result.getOrNull() ?: return@background finish(getString(R.string.phone_no_network))
            showQr(url, getString(R.string.phone_scan_desc_short), null, url.substringBefore("?"), null)
            setStatus(getString(R.string.phone_waiting))
        }
    }

    override fun onStop() {
        server?.stop()
        server = null
        super.onStop()
    }
}
