package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance

/**
 * Trakt device authentication: a QR code opens trakt.tv/activate/<code> with the code already
 * filled in (as recommended by the Trakt API docs); the screen polls until the user approves it.
 */
class TraktLoginFragment : QrFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.trakt_login_waiting),
        "",
        getString(R.string.setting_trakt_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun start() {
        val client = TraktClient(Settings.traktClientId, Settings.traktClientSecret)
        background({ client.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                showQr(
                    url = "${code.verificationUrl.trimEnd('/')}/${code.userCode}",
                    title = getString(R.string.trakt_login_scan),
                    description = getString(R.string.trakt_login_description, code.verificationUrl),
                    code = code.userCode,
                )
                poll(client, code)
            }.onFailure { finishWith(getString(R.string.trakt_login_failed, it.message)) }
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
}
