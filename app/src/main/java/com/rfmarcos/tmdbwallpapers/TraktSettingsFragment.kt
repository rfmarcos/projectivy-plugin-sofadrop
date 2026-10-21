package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Trakt app credentials and account connection. */
class TraktSettingsFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_trakt_title),
        getString(R.string.trakt_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildActions()
    }

    override fun onResume() {
        super.onResume()
        actions = buildActions() // back from the login screen
    }

    private fun buildActions(): List<GuidedAction> = buildList {
        add(action(ID_STATUS, R.drawable.ic_act_status, getString(R.string.status_title), statusText()).focusable(false).build())
        add(editable(ID_CLIENT_ID, R.drawable.ic_act_key, getString(R.string.trakt_client_id), Settings.traktClientId, masked(Settings.traktClientId)))
        add(editable(ID_SECRET, R.drawable.ic_act_key, getString(R.string.trakt_client_secret), Settings.traktClientSecret, masked(Settings.traktClientSecret)))
        if (Settings.traktConnected) {
            add(action(ID_DISCONNECT, R.drawable.ic_act_logout, getString(R.string.trakt_disconnect), Settings.traktUser).build())
        } else {
            add(action(ID_CONNECT, R.drawable.ic_act_trakt, getString(R.string.trakt_connect), getString(R.string.trakt_connect_desc)).build())
        }
    }

    private fun statusText(): String = when {
        Settings.traktConnected -> getString(R.string.trakt_connected, Settings.traktUser.ifBlank { "Trakt" })
        Settings.traktConfigured -> getString(R.string.trakt_public_only)
        else -> getString(R.string.trakt_not_configured)
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val value = action.editDescription?.toString().orEmpty().trim()
        when (action.id) {
            ID_CLIENT_ID -> {
                if (value != Settings.traktClientId) Settings.clearTraktSession()
                Settings.traktClientId = value
            }
            ID_SECRET -> Settings.traktClientSecret = value
        }
        actions = buildActions()
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_CONNECT -> {
                if (Settings.traktClientId.isBlank() || Settings.traktClientSecret.isBlank()) {
                    Toast.makeText(requireContext(), R.string.trakt_need_credentials, Toast.LENGTH_LONG).show()
                } else {
                    add(parentFragmentManager, TraktLoginFragment())
                }
            }
            ID_DISCONNECT -> {
                Settings.clearTraktSession()
                // Personal lists are no longer available
                Settings.lists = Settings.lists.filterNot { key -> Sources.TRAKT.any { it.key == key && it.needsTraktAccount } }.toSet()
                actions = buildActions()
            }
        }
    }

    companion object {
        private const val ID_STATUS = 1L
        private const val ID_CLIENT_ID = 2L
        private const val ID_SECRET = 3L
        private const val ID_CONNECT = 4L
        private const val ID_DISCONNECT = 5L
    }
}
