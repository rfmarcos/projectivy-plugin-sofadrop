package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** TMDB, Trakt or Simkl: the provider's lists as checkboxes, under a shortcut to its account. */
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

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += buildRows()
    }

    private fun buildRows(): List<GuidedAction> {
        val selected = Settings.lists
        val publicUsable = provider != SourceProvider.TRAKT || Settings.traktConfigured
        // Trakt users' lists (public ones and the account's) have their own screen
        val userLists = listOfNotNull(
            action(ID_USER_LISTS, R.drawable.ic_act_list, getString(R.string.trakt_user_lists_title), TraktListsFragment.summary(requireContext()))
                .hasNext(true).enabled(publicUsable).build().takeIf { provider == SourceProvider.TRAKT },
        )
        return listOf(accountAction()) + userLists + lists.mapIndexed { index, source ->
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

    private val connected get() = AccountsFragment.connected(provider)

    /** The account is managed in Accounts; this row shows its state and goes there. */
    private fun accountAction(): GuidedAction {
        val (icon, title) = when (provider) {
            SourceProvider.TRAKT -> R.drawable.ic_act_trakt to R.string.account_title_trakt
            SourceProvider.SIMKL -> R.drawable.ic_act_account to R.string.account_title_simkl
            else -> R.drawable.ic_act_account to R.string.account_title_tmdb
        }
        return action(ID_ACCOUNT, icon, getString(title), AccountsFragment.status(requireContext(), provider)).hasNext(true).build()
    }

    // Back from Accounts: lists that need the account unlock (or lock)
    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_ACCOUNT -> add(parentFragmentManager, AccountsFragment.newInstance(provider))
            action.id == ID_USER_LISTS -> add(parentFragmentManager, TraktListsFragment())
            action.id >= ID_LIST_BASE -> {
                val key = lists.getOrNull((action.id - ID_LIST_BASE).toInt())?.key ?: return
                Settings.lists = if (action.isChecked) Settings.lists + key else Settings.lists - key
            }
        }
    }

    companion object {
        private const val ARG_PROVIDER = "provider"
        private const val ID_ACCOUNT = 1L
        private const val ID_USER_LISTS = 2L
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
