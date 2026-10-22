package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** TMDB, Trakt or Simkl: the account (QR login) and the provider's lists as checkboxes. */
class ProviderListsFragment : BaseSettingsFragment() {

    private val provider get() = SourceProvider.valueOf(requireArguments().getString(ARG_PROVIDER)!!)

    private val lists get() = when (provider) {
        SourceProvider.TRAKT -> Sources.TRAKT
        SourceProvider.SIMKL -> Sources.SIMKL
        else -> Sources.TMDB
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        ListsFragment.providerName(requireContext(), provider),
        getString(when (provider) {
            SourceProvider.TRAKT -> R.string.trakt_lists_description
            SourceProvider.SIMKL -> R.string.simkl_lists_description
            else -> R.string.tmdb_lists_description
        }),
        getString(R.string.setting_lists_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The QR login is shown in this fragment's own manager, so its result comes back here
        childFragmentManager.setFragmentResultListener(QrDialog.RESULT_KEY, this) { _, _ -> refreshRows() }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    private fun buildRows(): List<GuidedAction> {
        val selected = Settings.lists
        val publicUsable = provider != SourceProvider.TRAKT || Settings.traktConfigured
        return listOf(accountAction()) + lists.mapIndexed { index, source ->
            val usable = publicUsable && !(source.needsAccount && !connected)
            checkbox(ID_LIST_BASE + index, Sources.label(requireContext(), source).substringAfter(" · "),
                when {
                    !usable -> getString(R.string.list_needs_account)
                    Settings.listFilters[source.key]?.isDefault == false -> Filters.listFiltersSummary(requireContext(), source)
                    else -> null
                }, usable && source.key in selected)
                .enabled(usable)
                .build()
        }
    }

    private fun refreshRows() {
        refreshActions(buildRows()) { newInstance(provider) }
    }

    private val connected get() = when (provider) {
        SourceProvider.TRAKT -> Settings.traktConnected
        SourceProvider.SIMKL -> Settings.simklConnected
        else -> Settings.tmdbConnected
    }

    private val loginAvailable get() = when (provider) {
        SourceProvider.TRAKT -> Settings.traktLoginAvailable
        SourceProvider.SIMKL -> Settings.simklLoginAvailable
        else -> Settings.token.isNotBlank()
    }

    private fun accountAction(): GuidedAction {
        val description = when {
            connected -> getString(R.string.tmdb_account_connected, accountUser(provider).ifBlank { "—" })
            !loginAvailable -> getString(R.string.account_unavailable)
            else -> getString(R.string.account_not_connected)
        }
        val (icon, title) = when (provider) {
            SourceProvider.TRAKT -> R.drawable.ic_act_trakt to R.string.account_title_trakt
            SourceProvider.SIMKL -> R.drawable.ic_act_account to R.string.account_title_simkl
            else -> R.drawable.ic_act_account to R.string.account_title_tmdb
        }
        val builder = action(ID_ACCOUNT, icon, getString(title), description)
        // Connected: expands into "Disconnect". Not connected: a click opens the QR login.
        if (connected) builder.subActions(listOf(
            GuidedAction.Builder(requireContext()).id(ID_ACCOUNT * SUB_ID_BASE).title(R.string.tmdb_disconnect).build()
        ))
        return builder.build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_ACCOUNT && !connected -> {
                if (!loginAvailable) {
                    Toast.makeText(requireContext(), R.string.account_unavailable, Toast.LENGTH_LONG).show()
                    return
                }
                val dialog = when (provider) {
                    SourceProvider.TRAKT -> TraktLoginDialog()
                    SourceProvider.SIMKL -> SimklLoginDialog()
                    else -> TmdbLoginDialog()
                }
                dialog.show(childFragmentManager, dialog.javaClass.simpleName)
            }
            action.id >= ID_LIST_BASE -> {
                val key = lists.getOrNull((action.id - ID_LIST_BASE).toInt())?.key ?: return
                Settings.lists = if (action.isChecked) Settings.lists + key else Settings.lists - key
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_ACCOUNT) {
            when (provider) {
                SourceProvider.TRAKT -> Settings.clearTraktSession()
                SourceProvider.SIMKL -> Settings.clearSimklSession()
                else -> Settings.clearTmdbSession()
            }
            Settings.lists -= lists.filter { it.needsAccount }.map { it.key }.toSet()
            refreshRows()
        }
        return true
    }

    companion object {
        private const val ARG_PROVIDER = "provider"
        private const val ID_ACCOUNT = 1L
        private const val ID_LIST_BASE = 100L

        fun newInstance(provider: SourceProvider) =
            ProviderListsFragment().apply { arguments = bundleOf(ARG_PROVIDER to provider.name) }

        /** User name of the provider's connected account, null when not connected. */
        private fun accountUser(provider: SourceProvider): String = when (provider) {
            SourceProvider.TRAKT -> Settings.traktUser
            SourceProvider.SIMKL -> Settings.simklUser
            else -> Settings.tmdbUser
        }

        /** "2 selected · connected as X" for the Lists screen. */
        fun summary(context: Context, provider: SourceProvider): String {
            val count = Sources.selected().count { it.provider == provider }
            val connected = when (provider) {
                SourceProvider.TRAKT -> Settings.traktConnected
                SourceProvider.SIMKL -> Settings.simklConnected
                else -> Settings.tmdbConnected
            }
            return if (connected) context.getString(R.string.provider_summary_connected, count, accountUser(provider).ifBlank { "—" })
            else context.getString(R.string.provider_summary_disconnected, count)
        }
    }
}
