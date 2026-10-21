package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** TMDB or Trakt: the account (QR login), Trakt's watched option, and the provider's lists as checkboxes. */
class ProviderListsFragment : BaseSettingsFragment() {

    private val provider get() = SourceProvider.valueOf(requireArguments().getString(ARG_PROVIDER)!!)
    private val trakt get() = provider == SourceProvider.TRAKT
    private val lists get() = if (trakt) Sources.TRAKT else Sources.TMDB

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        ListsFragment.providerName(requireContext(), provider),
        getString(if (trakt) R.string.trakt_lists_description else R.string.tmdb_lists_description),
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
        val rows = mutableListOf(accountAction())
        if (trakt) rows += choice(ID_WATCHED, R.drawable.ic_act_visibility, R.string.setting_watched_title, watchedDescription(),
            WATCHED_MODES.map(::watchedLabel))
        val selected = Settings.lists
        rows += lists.mapIndexed { index, source ->
            val usable = !(trakt && !Settings.traktConfigured) && !(source.needsAccount && !connected)
            checkbox(ID_LIST_BASE + index, Sources.label(requireContext(), source).substringAfter(" · "),
                if (usable) null else getString(R.string.list_needs_account), usable && source.key in selected)
                .enabled(usable)
                .build()
        }
        return rows
    }

    private fun refreshRows() {
        if (isAdded) actions = buildRows()
    }

    private val connected get() = if (trakt) Settings.traktConnected else Settings.tmdbConnected

    private fun accountAction(): GuidedAction {
        val description = when {
            connected -> getString(R.string.tmdb_account_connected, (if (trakt) Settings.traktUser else Settings.tmdbUser).ifBlank { "—" })
            trakt && !Settings.traktLoginAvailable -> getString(R.string.trakt_unavailable)
            else -> getString(R.string.account_not_connected)
        }
        val builder = action(ID_ACCOUNT, if (trakt) R.drawable.ic_act_trakt else R.drawable.ic_act_account,
            getString(if (trakt) R.string.account_title_trakt else R.string.account_title_tmdb), description)
        // Connected: expands into "Disconnect". Not connected: a click opens the QR login.
        if (connected) builder.subActions(listOf(
            GuidedAction.Builder(requireContext()).id(ID_ACCOUNT * SUB_ID_BASE).title(R.string.tmdb_disconnect).build()
        ))
        return builder.build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_ACCOUNT && !connected -> when {
                trakt && Settings.traktLoginAvailable -> TraktLoginDialog().show(childFragmentManager, "TraktLoginDialog")
                !trakt && Settings.token.isNotBlank() -> TmdbLoginDialog().show(childFragmentManager, "TmdbLoginDialog")
                else -> Toast.makeText(requireContext(), R.string.trakt_unavailable, Toast.LENGTH_LONG).show()
            }
            action.id >= ID_LIST_BASE -> {
                val key = lists.getOrNull((action.id - ID_LIST_BASE).toInt())?.key ?: return
                Settings.lists = if (action.isChecked) Settings.lists + key else Settings.lists - key
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_ACCOUNT -> {
                if (trakt) Settings.clearTraktSession() else Settings.clearTmdbSession()
                Settings.lists -= lists.filter { it.needsAccount }.map { it.key }.toSet()
                refreshRows()
            }
            ID_WATCHED -> {
                Settings.watchedMode = WATCHED_MODES[index]
                updateDescription(ID_WATCHED, watchedDescription())
            }
        }
        return true
    }

    private fun watchedLabel(mode: String) = getString(
        when (mode) {
            Settings.WATCHED_HIDE -> R.string.watched_hide
            Settings.WATCHED_SHOW -> R.string.watched_show
            else -> R.string.watched_mark
        }
    )

    private fun watchedDescription(): String {
        val label = watchedLabel(Settings.watchedMode)
        return if (Settings.traktConnected) label else getString(R.string.watched_needs_trakt, label)
    }

    companion object {
        private const val ARG_PROVIDER = "provider"
        private const val ID_ACCOUNT = 1L
        private const val ID_WATCHED = 2L
        private const val ID_LIST_BASE = 100L
        private val WATCHED_MODES = listOf(Settings.WATCHED_SHOW, Settings.WATCHED_MARK, Settings.WATCHED_HIDE)

        private val ListSource.needsAccount get() = needsTraktAccount || needsTmdbAccount

        fun newInstance(provider: SourceProvider) =
            ProviderListsFragment().apply { arguments = bundleOf(ARG_PROVIDER to provider.name) }

        /** "2 selected · connected as X" for the Lists screen. */
        fun summary(context: Context, provider: SourceProvider): String {
            val count = Sources.selected().count { it.provider == provider }
            val user = when (provider) {
                SourceProvider.TRAKT -> Settings.traktUser.takeIf { Settings.traktConnected }
                else -> Settings.tmdbUser.takeIf { Settings.tmdbConnected }
            }
            return if (user != null) context.getString(R.string.provider_summary_connected, count, user.ifBlank { "—" })
            else context.getString(R.string.provider_summary_disconnected, count)
        }
    }
}
