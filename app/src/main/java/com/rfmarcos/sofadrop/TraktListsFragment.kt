package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Trakt users' lists: public ones (typed or suggested, no account needed) and the connected account's own lists. */
class TraktListsFragment : BaseSettingsFragment() {

    /** Public lists shown as checkboxes: the suggested ones plus any the user typed. */
    private var publicOptions: List<TraktListRef> = emptyList()
    private var ownOptions: List<TraktListRef> = emptyList()

    private val client get() = TraktClient(Settings.traktClientId, Settings.traktClientSecret)

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.trakt_user_lists_title),
        getString(R.string.trakt_user_lists_description),
        getString(R.string.setting_trakt_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    // Back from Accounts: the account's own lists appear (or go)
    override fun onResume() {
        super.onResume()
        refreshRows()
        loadOwnLists()
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += accountAction()
        actions += ownActions()
        actions += editable(ID_ADD, R.drawable.ic_act_search, getString(R.string.mdblist_add), "", getString(R.string.trakt_add_desc))
        actions += publicActions()
    }

    /** The account's lists change on trakt.tv: reload them now and then while the screen is opened. */
    private fun loadOwnLists() {
        if (!Settings.traktConnected || System.currentTimeMillis() - ownLoadedAt < OWN_RELOAD_MILLIS) return
        ownLoadedAt = System.currentTimeMillis()
        background({ client.refreshOwnLists() }) { result ->
            result.onSuccess { refreshRows() }.onFailure {
                ownLoadedAt = 0
                Toast.makeText(requireContext(), getString(R.string.trakt_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The account's own lists, picked like any other list. */
    private fun ownActions(): List<GuidedAction> {
        ownOptions = if (Settings.traktConnected) Settings.traktLists.filter { it.own } else emptyList()
        return ownOptions.mapIndexed { index, list ->
            checkbox(ID_OWN_BASE + index, list.name, getString(R.string.mdblist_own_list), list.key in Settings.lists).build()
        }
    }

    /** The account is managed in Accounts; this row shows its state and goes there. */
    private fun accountAction(): GuidedAction =
        action(ID_ACCOUNT, R.drawable.ic_act_trakt, getString(R.string.account_title_trakt), accountDescription()).hasNext(true).build()

    private fun publicActions(): List<GuidedAction> {
        val added = Settings.traktLists.filterNot { it.own }
        val own = ownOptions.map { it.key }.toSet()
        publicOptions = (TraktClient.SUGGESTED + added).distinctBy { it.key }.filterNot { it.key in own }
        return publicOptions.mapIndexed { index, list ->
            checkbox(ID_PUBLIC_BASE + index, list.name, list.path, added.any { it.key == list.key }).build()
        }
    }

    private fun refreshRows() {
        if (!isAdded) return
        refreshActions(listOf(accountAction()) + ownActions() + findActionById(ID_ADD)!! + publicActions()) { TraktListsFragment() }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_ACCOUNT -> add(parentFragmentManager, AccountsFragment.newInstance(SourceProvider.TRAKT))
            action.id >= ID_OWN_BASE -> {
                val key = ownOptions.getOrNull((action.id - ID_OWN_BASE).toInt())?.key ?: return
                Settings.lists = if (action.isChecked) Settings.lists + key else Settings.lists - key
            }
            action.id >= ID_PUBLIC_BASE -> {
                val list = publicOptions.getOrNull((action.id - ID_PUBLIC_BASE).toInt()) ?: return
                setPublic(list, action.isChecked)
            }
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id != ID_ADD) return GuidedAction.ACTION_ID_CURRENT
        val reference = action.editDescription?.toString().orEmpty()
        action.editDescription = ""
        if (reference.isBlank()) return GuidedAction.ACTION_ID_CURRENT
        background({ client.findList(reference) }) { result ->
            val list = result.getOrNull()
            val message = when {
                result.isFailure -> getString(R.string.trakt_failed, result.exceptionOrNull()?.message)
                list == null -> getString(R.string.trakt_list_not_found)
                else -> getString(R.string.mdblist_added, list.name).also { setPublic(list, true); refreshRows() }
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    /** Adding a public list also selects it; removing it unselects it. */
    private fun setPublic(list: TraktListRef, enabled: Boolean) {
        Settings.traktLists = Settings.traktLists.filterNot { it.key == list.key } + listOfNotNull(list.takeIf { enabled })
        Settings.lists = if (enabled) Settings.lists + list.key else Settings.lists - list.key
    }

    private fun accountDescription(): String =
        if (Settings.traktConnected) getString(R.string.mdblist_connected, Settings.traktUser.ifBlank { "Trakt" },
            ListsFragment.listsCount(requireContext(), Settings.traktLists.count { it.own }))
        else getString(R.string.trakt_user_lists_account_hint)

    companion object {
        private const val ID_ACCOUNT = 1L
        private const val ID_ADD = 2L
        private const val ID_PUBLIC_BASE = 100L
        private const val ID_OWN_BASE = 500L
        private const val OWN_RELOAD_MILLIS = 5 * 60_000L

        /** When the account's lists were last reloaded (for every instance: the screen reopens itself when rows change). */
        @Volatile
        private var ownLoadedAt = 0L

        /** One-line summary for the Trakt screen: how many users' lists are selected. */
        fun summary(context: Context): String {
            val selected = Sources.selected().count { it.key.startsWith(TraktClient.USER_LIST) }
            return context.getString(R.string.trakt_user_lists_summary, ListsFragment.listsCount(context, selected))
        }
    }
}
