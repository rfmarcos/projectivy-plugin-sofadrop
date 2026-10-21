package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance

/** Connect the user's TMDB account (watchlist and favourites) by approving a request token from a QR. */
class TmdbLoginFragment : QrFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.trakt_login_waiting),
        "",
        getString(R.string.setting_tmdb_account_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun start() {
        val account = TmdbAccount(TmdbClient(Settings.token, requireContext().cacheDir))
        background({ account.requestToken() }) { result ->
            result.onSuccess { (token, url) ->
                showQr(url, getString(R.string.tmdb_login_scan), getString(R.string.tmdb_login_description))
                poll(account, token)
            }.onFailure { finishWith(getString(R.string.tmdb_login_failed, it.message)) }
        }
    }

    private fun poll(account: TmdbAccount, token: String) {
        background({
            // Request tokens are valid for 60 minutes; give the user 10
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            var connected = false
            while (active && !connected && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                connected = runCatching { account.tryCreateSession(token) }.getOrDefault(false)
            }
            connected
        }) { result ->
            when {
                result.getOrNull() == true -> finishWith(getString(R.string.tmdb_login_ok, Settings.tmdbUser.ifBlank { "TMDB" }))
                active -> finishWith(getString(R.string.trakt_login_expired))
            }
        }
    }
}
