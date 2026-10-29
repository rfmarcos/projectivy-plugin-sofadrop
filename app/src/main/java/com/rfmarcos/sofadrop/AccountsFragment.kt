package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.view.View
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The one place to connect and disconnect services (QR login): they feed lists, ratings and the
 * watch history, so they no longer live in each provider's list screen. Plus the IMDb ratings
 * that need no account.
 */
class AccountsFragment : BaseSettingsFragment() {

    private var downloading = false

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_accounts_title),
        getString(R.string.accounts_description),
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The QR logins are shown in this fragment's own manager, so their result comes back here
        childFragmentManager.setFragmentResultListener(QrDialog.RESULT_KEY, this) { _, _ -> refreshRows() }
    }

    // Opened from a list, Header or Filters: start on the service the user came for
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val focus = arguments?.getString(ARG_FOCUS)?.let(SourceProvider::valueOf) ?: return
        if (savedInstanceState == null) view.post { if (isAdded) setSelectedActionPosition(findActionPositionById(idOf(focus))) }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
        // What each service is for, in the left pane while its row is focused
        explanations += mapOf(
            ID_TMDB to getString(R.string.enables_tmdb),
            ID_TRAKT to getString(R.string.enables_trakt),
            ID_SIMKL to getString(R.string.enables_simkl),
            ID_MDBLIST to getString(R.string.enables_mdblist),
            ID_IMDB to getString(R.string.enables_imdb),
        )
    }

    private fun rows(): List<GuidedAction> = SERVICES.map(::serviceAction) + header(ID_NO_ACCOUNT, getString(R.string.accounts_no_account)) +
        action(ID_IMDB, R.drawable.ic_act_refresh, getString(R.string.imdb_data_title), imdbLabel(requireContext())).build()

    private fun refreshRows() = refreshActions(rows()) { AccountsFragment() }

    private fun serviceAction(provider: SourceProvider): GuidedAction {
        val context = requireContext()
        val builder = GuidedAction.Builder(context).id(idOf(provider))
            .icon(AppCompatResources.getDrawable(context, BADGES.getValue(provider)))
            .title(ListsFragment.providerName(context, provider)).description(status(context, provider))
        // Connected: expands into its actions. Not connected: a click opens the QR login.
        if (connected(provider)) builder.subActions(
            listOfNotNull(
                GuidedAction.Builder(context).id(idOf(provider) * SUB_ID_BASE + SUB_RELOAD).title(R.string.mdblist_refresh).build()
                    .takeIf { provider == SourceProvider.MDBLIST },
                GuidedAction.Builder(context).id(idOf(provider) * SUB_ID_BASE + SUB_DISCONNECT).title(R.string.tmdb_disconnect).build(),
            )
        )
        return builder.build()
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_IMDB) return downloadImdb()
        val provider = SERVICES.firstOrNull { idOf(it) == action.id } ?: return
        if (connected(provider)) return
        val dialog = when (provider) {
            SourceProvider.TRAKT -> TraktLoginDialog().takeIf { Settings.traktLoginAvailable }
            SourceProvider.SIMKL -> SimklLoginDialog().takeIf { Settings.simklLoginAvailable }
            SourceProvider.MDBLIST -> MdbListLoginDialog().takeIf { Settings.mdbListLoginAvailable }
            SourceProvider.TMDB -> TmdbLoginDialog().takeIf { Settings.token.isNotBlank() }
        }
        if (dialog == null) Toast.makeText(requireContext(), R.string.account_unavailable, Toast.LENGTH_LONG).show()
        else dialog.show(childFragmentManager, dialog.javaClass.simpleName)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val provider = SERVICES.firstOrNull { idOf(it) == action.id / SUB_ID_BASE } ?: return true
        // Close the expanded options first: the row loses them and leanback would leave them drawn
        collapseSubActions()
        if (action.id % SUB_ID_BASE == SUB_RELOAD) {
            background({ MdbListClient.fromSettings()?.userLists().orEmpty() }) { result ->
                result.onSuccess { lists -> Settings.mdbLists = (lists + Settings.mdbLists).distinctBy { it.key } }
                refreshRows()
            }
            return true
        }
        disconnect(provider)
        refreshRows()
        return true
    }

    /** Sign out and drop the lists that only work with that account, so wallpapers don't keep using them. */
    private fun disconnect(provider: SourceProvider) {
        when (provider) {
            SourceProvider.TRAKT -> {
                Settings.clearTraktSession()
                // Like MDBList: the account's own lists go, the public ones stay
                val own = Settings.traktLists.filter { it.own }
                Settings.traktLists -= own.toSet()
                Settings.lists -= own.map { it.key }.toSet()
            }
            SourceProvider.SIMKL -> Settings.clearSimklSession()
            SourceProvider.TMDB -> Settings.clearTmdbSession()
            SourceProvider.MDBLIST -> {
                // The account's own lists go, the public ones stay
                val accountKeys = Settings.mdbLists.filter { it.path.isEmpty() }.map { it.key }.toSet()
                Settings.clearMdbListSession()
                Settings.mdbListKey = ""
                Settings.mdbLists = Settings.mdbLists.filter { it.path.isNotEmpty() }
                Settings.lists -= accountKeys
                return
            }
        }
        val lists = when (provider) {
            SourceProvider.TRAKT -> Sources.TRAKT
            SourceProvider.SIMKL -> Sources.SIMKL
            else -> Sources.TMDB
        }
        Settings.lists -= lists.filter { it.needsAccount }.map { it.key }.toSet()
    }

    private fun downloadImdb() {
        if (downloading) return
        downloading = true
        updateDescription(ID_IMDB, getString(R.string.imdb_data_downloading))
        background({ ImdbRatings.refresh(requireContext().applicationContext) }) { result ->
            downloading = false
            updateDescription(ID_IMDB, result.exceptionOrNull()?.let { getString(R.string.imdb_data_failed, it.message) } ?: imdbLabel(requireContext()))
        }
    }

    companion object {
        private const val ID_TMDB = 1L
        private const val ID_TRAKT = 2L
        private const val ID_SIMKL = 3L
        private const val ID_MDBLIST = 4L
        private const val ID_NO_ACCOUNT = 5L
        private const val ID_IMDB = 6L
        private const val SUB_RELOAD = 0L
        private const val SUB_DISCONNECT = 1L
        private const val ARG_FOCUS = "focus"

        /** Accounts with [provider]'s row selected. */
        fun newInstance(provider: SourceProvider) = AccountsFragment().apply { arguments = bundleOf(ARG_FOCUS to provider.name) }

        private val SERVICES = listOf(SourceProvider.TMDB, SourceProvider.TRAKT, SourceProvider.SIMKL, SourceProvider.MDBLIST)
        private val BADGES = mapOf(
            SourceProvider.TMDB to R.drawable.badge_tmdb, SourceProvider.TRAKT to R.drawable.badge_trakt,
            SourceProvider.SIMKL to R.drawable.badge_simkl, SourceProvider.MDBLIST to R.drawable.badge_mdblist,
        )

        private fun idOf(provider: SourceProvider) = when (provider) {
            SourceProvider.TMDB -> ID_TMDB
            SourceProvider.TRAKT -> ID_TRAKT
            SourceProvider.SIMKL -> ID_SIMKL
            SourceProvider.MDBLIST -> ID_MDBLIST
        }

        fun connected(provider: SourceProvider) = when (provider) {
            SourceProvider.TRAKT -> Settings.traktConnected
            SourceProvider.SIMKL -> Settings.simklConnected
            SourceProvider.TMDB -> Settings.tmdbConnected
            SourceProvider.MDBLIST -> Settings.mdbListAvailable
        }

        /** "Connected as X", "API key", or how to connect; shared with the shortcut rows of the list screens. */
        fun status(context: Context, provider: SourceProvider): String {
            val user = when (provider) {
                SourceProvider.TRAKT -> Settings.traktUser
                SourceProvider.SIMKL -> Settings.simklUser
                SourceProvider.TMDB -> Settings.tmdbUser
                SourceProvider.MDBLIST -> Settings.mdbListUser
            }
            val loginAvailable = when (provider) {
                SourceProvider.TRAKT -> Settings.traktLoginAvailable
                SourceProvider.SIMKL -> Settings.simklLoginAvailable
                SourceProvider.TMDB -> Settings.token.isNotBlank()
                SourceProvider.MDBLIST -> Settings.mdbListLoginAvailable
            }
            return when {
                provider == SourceProvider.MDBLIST && !Settings.mdbListConnected && Settings.mdbListKey.isNotBlank() -> context.getString(R.string.accounts_api_key)
                connected(provider) -> context.getString(R.string.tmdb_account_connected, user.ifBlank { "—" })
                !loginAvailable -> context.getString(R.string.account_unavailable)
                else -> context.getString(R.string.account_not_connected)
            }
        }

        /** "Connected: Trakt, MDBList" for the main screen. */
        fun summary(context: Context): String =
            SERVICES.filter(::connected).joinToString(", ") { ListsFragment.providerName(context, it) }
                .let { if (it.isEmpty()) context.getString(R.string.accounts_none) else context.getString(R.string.accounts_summary, it) }

        fun imdbLabel(context: Context): String {
            if (RatingSource.IMDB !in Settings.ratingSources || Settings.mdbListAvailable) return context.getString(R.string.imdb_data_unused)
            val date = ImdbRatings.downloadedAt(context)
            return if (date == 0L) context.getString(R.string.imdb_data_never)
            else context.getString(R.string.imdb_data_date, DateUtils.formatDateTime(context, date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))
        }
    }
}
