package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Trakt device authentication: shows a code to enter on trakt.tv/activate and polls until the
 * user approves it, then closes by itself.
 */
class TraktLoginFragment : BaseSettingsFragment() {

    @Volatile
    private var active = false

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.trakt_login_waiting),
        "",
        getString(R.string.setting_trakt_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_CANCEL, R.drawable.ic_act_logout, getString(R.string.cancel), null).build()
    }

    override fun onResume() {
        super.onResume()
        if (active) return
        active = true
        val client = TraktClient(Settings.traktClientId, Settings.traktClientSecret)
        background({ client.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                guidanceStylist.titleView?.text = getString(R.string.trakt_login_title, code.userCode)
                guidanceStylist.descriptionView?.text = getString(R.string.trakt_login_description, code.verificationUrl)
                poll(client, code)
            }.onFailure { finishWith(getString(R.string.trakt_login_failed, it.message)) }
        }
    }

    override fun onPause() {
        active = false
        super.onPause()
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
                TraktClient.Poll.Connected -> finishWith(getString(R.string.trakt_login_ok, Settings.traktUser.ifBlank { "Trakt" }))
                is TraktClient.Poll.Failed -> finishWith(
                    when (outcome.code) {
                        410 -> getString(R.string.trakt_login_expired)
                        418 -> getString(R.string.trakt_login_denied)
                        else -> getString(R.string.trakt_login_failed, "HTTP ${outcome.code}")
                    }
                )
                null -> finishWith(getString(R.string.trakt_login_failed, result.exceptionOrNull()?.message))
                else -> if (active) finishWith(getString(R.string.trakt_login_expired))
            }
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        parentFragmentManager.popBackStack()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_CANCEL) {
            active = false
            parentFragmentManager.popBackStack()
        }
    }

    companion object {
        private const val ID_CANCEL = 1L
    }
}
