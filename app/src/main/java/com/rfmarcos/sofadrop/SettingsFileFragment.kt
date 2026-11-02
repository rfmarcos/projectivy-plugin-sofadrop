package com.rfmarcos.sofadrop

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** The settings to and from a file (see [SettingsFile]), to set up another TV the same way. */
class SettingsFileFragment : BaseSettingsFragment() {

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(SettingsImportFragment.newInstance(uri))
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else toast(getString(R.string.settings_file_save_failed))
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.settings_file_title),
        getString(R.string.settings_file_explanation),
        getString(R.string.setting_about_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_SAVE, R.drawable.ic_act_download, getString(R.string.settings_file_save),
            getString(R.string.settings_file_in_downloads, SettingsFile.NAME)).build()
        actions += action(ID_SHARE, R.drawable.ic_act_public, getString(R.string.settings_file_share),
            getString(R.string.settings_file_share_desc)).multilineDescription(true).build()
        // Google TV has no file picker: the file is opened with Sofadrop from a file manager instead
        val canPick = SettingsFile.canPick(requireContext())
        actions += action(ID_IMPORT, R.drawable.ic_act_add, getString(R.string.settings_file_import),
            getString(if (canPick) R.string.settings_file_import_desc else R.string.settings_file_import_no_picker, SettingsFile.NAME))
            .multilineDescription(true).focusable(canPick).build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_SAVE -> if (SettingsFile.savingNeedsPermission && !hasStoragePermission()) {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                save()
            }
            ID_SHARE -> SettingsFile.shareIntent(requireContext())?.let { startActivity(it) }
                ?: toast(getString(R.string.settings_file_share_none))
            ID_IMPORT -> runCatching { pick.launch(arrayOf("*/*")) }
        }
    }

    private fun hasStoragePermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun save() {
        val context = requireContext().applicationContext
        background({ SettingsFile.saveToDownloads(context) }) { result ->
            result.onSuccess { name ->
                updateDescription(ID_SAVE, getString(R.string.settings_file_saved, name))
                toast(getString(R.string.settings_file_saved, name))
            }.onFailure { toast(getString(R.string.settings_file_save_failed)) }
        }
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    companion object {
        private const val ID_SAVE = 1L
        private const val ID_SHARE = 2L
        private const val ID_IMPORT = 3L
    }
}

/** "Import these settings?" for the file at the uri argument, opened from the settings or a file manager. */
class SettingsImportFragment : BaseSettingsFragment() {

    private val uri: Uri get() = requireArguments().getParcelableCompat(ARG_URI)!!
    private var text: String? = null

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.settings_import_title),
        getString(R.string.settings_import_desc),
        getString(R.string.settings_file_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += action(ID_IMPORT, R.drawable.ic_act_check, getString(R.string.settings_import_confirm), getString(R.string.settings_import_reading))
            .enabled(false).build()
        actions += action(ID_CANCEL, R.drawable.ic_act_block, getString(R.string.cancel), null).build()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        background({ SettingsFile.read(context, uri) to SettingsFile.displayName(context, uri) }) { result ->
            val (content, name) = result.getOrNull() ?: (null to null)
            val version = content?.let(SettingsFile::version)
            text = content.takeIf { version != null }
            findActionById(ID_IMPORT)?.let { action ->
                action.description = when {
                    content == null -> getString(R.string.settings_import_unreadable)
                    version == null -> getString(R.string.settings_import_invalid)
                    else -> getString(R.string.settings_import_from, name ?: SettingsFile.NAME, version)
                }
                action.isEnabled = version != null
                notifyActionChanged(findActionPositionById(ID_IMPORT))
                if (version != null) setSelectedActionPosition(findActionPositionById(ID_IMPORT))
            }
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_CANCEL -> parentFragmentManager.popBackStack()
            ID_IMPORT -> {
                val activity = requireActivity()
                if (text?.let { SettingsFile.import(activity, it) } != true) {
                    Toast.makeText(activity, R.string.settings_import_invalid, Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(activity, R.string.settings_import_done, Toast.LENGTH_LONG).show()
                // The settings again from the start, showing the imported values (without older screens behind
                // with the old ones, also when opened from a file manager); leaving makes the wallpapers again
                activity.startActivity(android.content.Intent(activity, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                activity.finish()
            }
        }
    }

    companion object {
        private const val ID_IMPORT = 1L
        private const val ID_CANCEL = 2L
        private const val ARG_URI = "uri"

        fun newInstance(uri: Uri) = SettingsImportFragment().apply { arguments = bundleOf(ARG_URI to uri) }

        @Suppress("DEPRECATION")
        private fun Bundle.getParcelableCompat(key: String): Uri? = getParcelable(key)
    }
}
