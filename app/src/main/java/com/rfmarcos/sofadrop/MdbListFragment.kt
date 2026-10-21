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

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_mdblist_title),
        getString(R.string.mdblist_screen_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The QR login is shown in this fragment's own manager so the main screen keeps its listener
        childFragmentManager.setFragmentResultListener(QrDialog.RESULT_KEY, this) { _, _ -> refreshRows() }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += accountAction()
        actions += editable(ID_ADD, R.drawable.ic_act_search, getString(R.string.mdblist_add), "", getString(R.string.mdblist_add_desc))
        actions += publicActions()
    }

    private fun accountAction(): GuidedAction {
        val builder = action(ID_ACCOUNT, R.drawable.ic_act_account, getString(R.string.mdblist_account_title), accountDescription())
        if (Settings.mdbListAvailable) builder.subActions(listOf(
            GuidedAction.Builder(requireContext()).id(ID_ACCOUNT * SUB_ID_BASE).title(R.string.mdblist_refresh).build(),
            GuidedAction.Builder(requireContext()).id(ID_ACCOUNT * SUB_ID_BASE + 1).title(R.string.tmdb_disconnect).build(),
        ))
        return builder.build()
    }

    private fun publicActions(): List<GuidedAction> {
        val added = Settings.mdbLists.filter { it.path.isNotEmpty() }
        publicOptions = (MdbListClient.SUGGESTED + added).distinctBy { it.key }
        return publicOptions.mapIndexed { index, list ->
            action(ID_PUBLIC_BASE + index, R.drawable.ic_act_list, list.name, list.path)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(added.any { it.key == list.key })
                .build()
        }
    }

    private fun refreshRows() {
        if (!isAdded) return
        actions = listOf(accountAction(), findActionById(ID_ADD)!!) + publicActions()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_ACCOUNT -> if (!Settings.mdbListAvailable) {
                if (Settings.mdbListLoginAvailable) MdbListLoginDialog().show(childFragmentManager, "MdbListLoginDialog")
                else Toast.makeText(requireContext(), R.string.mdblist_unavailable, Toast.LENGTH_LONG).show()
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

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE != ID_ACCOUNT) return true
        if (action.id % SUB_ID_BASE == 0L) {
            background({ MdbListClient.fromSettings()?.userLists().orEmpty() }) { result ->
                result.onSuccess { lists -> Settings.mdbLists = (lists + Settings.mdbLists).distinctBy { it.key } }
                updateDescription(ID_ACCOUNT, accountDescription())
            }
        } else {
            // Disconnect: drop the account's lists, keep the public ones
            val accountKeys = Settings.mdbLists.filter { it.path.isEmpty() }.map { it.key }.toSet()
            Settings.clearMdbListSession()
            Settings.mdbListKey = ""
            Settings.mdbLists = Settings.mdbLists.filter { it.path.isNotEmpty() }
            Settings.lists -= accountKeys
            refreshRows()
        }
        return true
    }

    private fun accountDescription(): String {
        val own = Settings.mdbLists.count { it.path.isEmpty() }
        return when {
            Settings.mdbListConnected -> getString(R.string.mdblist_connected, Settings.mdbListUser.ifBlank { "MDBList" }, own)
            Settings.mdbListKey.isNotBlank() -> getString(R.string.mdblist_key_mode, own)
            Settings.mdbListLoginAvailable -> getString(R.string.mdblist_account_hint)
            else -> getString(R.string.mdblist_unavailable)
        }
    }

    companion object {
        private const val ID_ACCOUNT = 1L
        private const val ID_ADD = 2L
        private const val ID_PUBLIC_BASE = 100L
        private const val SUB_ID_BASE = 1000L

        /** One-line summary for the main settings screen. */
        fun summary(context: Context): String {
            val available = Sources.available().count { it.provider == SourceProvider.MDBLIST }
            return when {
                Settings.mdbListConnected -> context.getString(R.string.mdblist_connected, Settings.mdbListUser.ifBlank { "MDBList" }, available)
                available > 0 -> context.getString(R.string.mdblist_configured, available)
                else -> context.getString(R.string.mdblist_summary_empty)
            }
        }
    }
}
