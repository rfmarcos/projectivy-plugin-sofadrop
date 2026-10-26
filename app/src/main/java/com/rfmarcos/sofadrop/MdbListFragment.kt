package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** MDBList: public lists (typed or suggested, no account needed) and the optional account's own lists. */
class MdbListFragment : BaseSettingsFragment() {

    /** Public lists shown as checkboxes: the suggested ones plus any the user typed. */
    private var publicOptions: List<MdbListRef> = emptyList()
    private var ownOptions: List<MdbListRef> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_mdblist_title),
        getString(R.string.mdblist_screen_description),
        getString(R.string.setting_lists_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    // Back from Accounts: the account's own lists appear (or go)
    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += accountAction()
        actions += ownActions()
        actions += editable(ID_ADD, R.drawable.ic_act_search, getString(R.string.mdblist_add), "", getString(R.string.mdblist_add_desc))
        actions += publicActions()
    }

    /** The account's own lists, picked like any other list. */
    private fun ownActions(): List<GuidedAction> {
        ownOptions = if (Settings.mdbListAvailable) Settings.mdbLists.filter { it.path.isEmpty() } else emptyList()
        return ownOptions.mapIndexed { index, list ->
            checkbox(ID_OWN_BASE + index, list.name, getString(R.string.mdblist_own_list), list.key in Settings.lists).build()
        }
    }

    /** The account is managed in Accounts; this row shows its state and goes there. */
    private fun accountAction(): GuidedAction =
        action(ID_ACCOUNT, R.drawable.ic_act_account, getString(R.string.mdblist_account_title), accountDescription()).hasNext(true).build()

    private fun publicActions(): List<GuidedAction> {
        val added = Settings.mdbLists.filter { it.path.isNotEmpty() }
        publicOptions = (MdbListClient.SUGGESTED + added).distinctBy { it.key }
        return publicOptions.mapIndexed { index, list ->
            checkbox(ID_PUBLIC_BASE + index, list.name, list.path, added.any { it.key == list.key }).build()
        }
    }

    private fun refreshRows() {
        if (!isAdded) return
        refreshActions(listOf(accountAction()) + ownActions() + findActionById(ID_ADD)!! + publicActions()) { MdbListFragment() }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_ACCOUNT -> add(parentFragmentManager, AccountsFragment.newInstance(SourceProvider.MDBLIST))
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
        background({ MdbListClient.findPublicList(reference) }) { result ->
            val list = result.getOrNull()
            val message = when {
                result.isFailure -> getString(R.string.mdblist_failed, result.exceptionOrNull()?.message)
                list == null -> getString(R.string.mdblist_not_found)
                else -> getString(R.string.mdblist_added, list.name).also { setPublic(list, true); refreshRows() }
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    /** Adding a public list also selects it; removing it unselects it. */
    private fun setPublic(list: MdbListRef, enabled: Boolean) {
        Settings.mdbLists = Settings.mdbLists.filterNot { it.key == list.key } + listOfNotNull(list.takeIf { enabled })
        Settings.lists = if (enabled) Settings.lists + list.key else Settings.lists - list.key
    }

    private fun accountDescription(): String {
        val own = Settings.mdbLists.count { it.path.isEmpty() }
        return when {
            Settings.mdbListConnected -> getString(R.string.mdblist_connected, Settings.mdbListUser.ifBlank { "MDBList" }, ListsFragment.listsCount(requireContext(), own))
            Settings.mdbListKey.isNotBlank() -> getString(R.string.mdblist_key_mode, ListsFragment.listsCount(requireContext(), own))
            Settings.mdbListLoginAvailable -> getString(R.string.mdblist_account_hint)
            else -> getString(R.string.mdblist_unavailable)
        }
    }

    companion object {
        private const val ID_ACCOUNT = 1L
        private const val ID_ADD = 2L
        private const val ID_PUBLIC_BASE = 100L
        private const val ID_OWN_BASE = 500L

        /** One-line summary for the main settings screen. */
        fun summary(context: Context): String {
            val available = Sources.available().count { it.provider == SourceProvider.MDBLIST }
            return when {
                Settings.mdbListConnected -> context.getString(R.string.mdblist_connected, Settings.mdbListUser.ifBlank { "MDBList" }, ListsFragment.listsCount(context, available))
                available > 0 -> ListsFragment.listsCount(context, available)
                else -> context.getString(R.string.mdblist_summary_empty)
            }
        }
    }
}
