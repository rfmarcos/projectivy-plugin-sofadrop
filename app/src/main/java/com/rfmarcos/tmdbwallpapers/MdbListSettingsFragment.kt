package com.rfmarcos.tmdbwallpapers

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** MDBList API key, the user's own lists and public lists added by name. */
class MdbListSettingsFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_mdblist_title),
        getString(R.string.mdblist_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += editable(ID_KEY, R.drawable.ic_act_key, getString(R.string.mdblist_key), Settings.mdbListKey, masked(Settings.mdbListKey))
        actions += action(ID_REFRESH, R.drawable.ic_act_refresh, getString(R.string.mdblist_refresh),
            getString(R.string.mdblist_refresh_desc, Settings.mdbLists.size)).build()
        actions += editable(ID_ADD, R.drawable.ic_act_add, getString(R.string.mdblist_add), "", getString(R.string.mdblist_add_desc))
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val value = action.editDescription?.toString().orEmpty().trim()
        when (action.id) {
            ID_KEY -> {
                Settings.mdbListKey = value
                updateDescription(ID_KEY, masked(value))
                if (value.isNotBlank()) reloadLists()
            }
            ID_ADD -> {
                action.editDescription = ""
                updateDescription(ID_ADD, getString(R.string.mdblist_add_desc))
                if (value.isNotBlank()) addPublicList(value)
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_REFRESH) reloadLists()
    }

    private fun reloadLists() {
        val key = Settings.mdbListKey.takeIf { it.isNotBlank() } ?: return
        background({ MdbListClient(key).userLists() }) { result ->
            result.onSuccess { lists ->
                // Keep public lists added by hand
                Settings.mdbLists = (lists + Settings.mdbLists).distinctBy { it.id }
                updateDescription(ID_REFRESH, getString(R.string.mdblist_refresh_desc, Settings.mdbLists.size))
            }.onFailure { toast(getString(R.string.mdblist_failed, it.message)) }
        }
    }

    private fun addPublicList(reference: String) {
        val key = Settings.mdbListKey.takeIf { it.isNotBlank() } ?: return toast(getString(R.string.mdblist_not_configured))
        background({ MdbListClient(key).findList(reference) }) { result ->
            result.onSuccess { list ->
                if (list == null) return@onSuccess toast(getString(R.string.mdblist_not_found))
                Settings.mdbLists = (Settings.mdbLists + list).distinctBy { it.id }
                Settings.lists = Settings.lists + "mdblist:${list.id}"
                updateDescription(ID_REFRESH, getString(R.string.mdblist_refresh_desc, Settings.mdbLists.size))
                toast(getString(R.string.mdblist_added, list.name))
            }.onFailure { toast(getString(R.string.mdblist_failed, it.message)) }
        }
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    companion object {
        private const val ID_KEY = 1L
        private const val ID_REFRESH = 2L
        private const val ID_ADD = 3L
    }
}
