package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Settings screen whose left pane is a live preview of one wallpaper rendered with the current settings
 * (plus the "not applied yet" state). Call [schedulePreview] after a change.
 */
abstract class PreviewSettingsFragment : BaseSettingsFragment() {

    private var previewImage: ImageView? = null
    private var previewProgress: ProgressBar? = null
    private var previewLabel: TextView? = null
    protected var pendingNotice: TextView? = null
    private var previewJob = 0
    private val previewRunnable = Runnable { startPreview() }

    /** Show the "Projectivy may take a few minutes" line under the preview. */
    protected open val showsProjectivyNotice = false

    override fun onCreateGuidanceStylist(): GuidanceStylist = object : GuidanceStylist() {
        override fun onProvideLayoutId() = R.layout.settings_guidance

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, guidance: Guidance): View {
            val view = super.onCreateView(inflater, container, guidance)
            previewImage = view.findViewById(R.id.preview_image)
            previewProgress = view.findViewById(R.id.preview_progress)
            previewLabel = view.findViewById(R.id.preview_label)
            pendingNotice = view.findViewById(R.id.pending_notice)
            // A screen reopened by refreshActions isn't resumed until later: show it right
            refreshPending()
            view.findViewById<View>(R.id.preview_frame).clipToOutline = true
            if (showsProjectivyNotice) view.findViewById<View>(R.id.projectivy_notice).visibility = View.VISIBLE
            cachedPreview?.let { (key, bitmap, title) -> if (key == previewKey()) showPreview(bitmap, title) }
            return view
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPending()
        schedulePreview(0)
    }

    override fun onPause() {
        handler.removeCallbacks(previewRunnable)
        super.onPause()
    }

    protected fun refreshPending() {
        val pending = Settings.hasPendingChanges && !Settings.generating && Sources.selected().isNotEmpty()
        pendingNotice?.visibility = if (pending) View.VISIBLE else View.GONE
    }

    /** Everything that changes how the preview looks. */
    private fun previewKey() = listOf(
        Settings.token.hashCode(), Settings.language, Settings.region, Settings.signature, Settings.mdbLists.size,
        Settings.tmdbConnected, Settings.previewPin, WallpaperStore(requireContext()).current().size,
    ).joinToString("|")

    /** A part the style doesn't draw: its row stays (so rows don't jump), disabled and saying why. */
    protected fun GuidedAction.hiddenBy(drawn: Boolean): GuidedAction {
        if (!drawn) {
            description = getString(R.string.style_hidden, DetailsFragment.styleLabel(requireContext(), Settings.style))
            isEnabled = false
        }
        return this
    }

    protected fun schedulePreview(delay: Long = 700) {
        refreshPending()
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, delay)
    }

    private fun startPreview() {
        val key = previewKey()
        if (cachedPreview?.first == key && previewImage?.drawable != null) return
        if (Settings.token.isBlank()) {
            showPreviewMessage(getString(R.string.preview_no_token))
            return
        }
        if (Sources.selected().isEmpty() && Settings.previewPin.isEmpty()) {
            showPreviewMessage(getString(R.string.error_no_lists))
            return
        }
        val job = ++previewJob
        // The previous frame stays on screen while the next one renders
        previewProgress?.visibility = View.VISIBLE
        val context = requireContext().applicationContext
        background({ renderPreview(context) }) { result ->
            if (job != previewJob) return@background
            previewProgress?.visibility = View.GONE
            result.onSuccess { preview ->
                if (preview == null) {
                    showPreviewMessage(getString(R.string.preview_no_titles))
                } else {
                    cachedPreview = Triple(key, preview.first, preview.second)
                    showPreview(preview.first, preview.second)
                }
            }.onFailure {
                showPreviewMessage(getString(R.string.preview_error, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun showPreview(bitmap: Bitmap, title: String) {
        previewImage?.setImageBitmap(bitmap)
        val pinned = Settings.previewPin.isNotEmpty()
        previewLabel?.text = getString(if (pinned) R.string.preview_label_pinned else R.string.preview_label, title)
    }

    private fun showPreviewMessage(message: String) {
        previewProgress?.visibility = View.GONE
        previewImage?.setImageDrawable(null)
        previewImage?.minimumHeight = (resources.displayMetrics.density * 200).toInt()
        previewLabel?.text = message
    }

    companion object {
        private const val PREVIEW_WIDTH = 1280

        /** Last preview (key, image, title), kept while the process lives so reopening is instant. */
        private var cachedPreview: Triple<String, Bitmap, String>? = null

        /** "type:tmdbId" pins for what the user typed: "tv 1399", "tv:1399" or a TMDB URL; a bare number may be either type. */
        fun pinCandidates(text: String): List<String> {
            Regex("(?i)(?<![a-z])(tv|movie)(?![a-z])\\W*(\\d+)").find(text)?.let { return listOf("${it.groupValues[1].lowercase()}:${it.groupValues[2]}") }
            val id = Regex("\\d+").find(text)?.value ?: return emptyList()
            return MediaType.entries.map { "${it.path}:$id" }
        }

        private fun pinRef(pin: String): TitleRef? {
            val (type, id) = pin.split(':').takeIf { it.size == 2 } ?: return null
            return TitleRef(MediaType.entries.firstOrNull { it.path == type } ?: return null, id.toIntOrNull() ?: return null)
        }

        /** [pins] that can be a wallpaper (the id exists and has a backdrop), with their TMDB details. */
        fun pinInfos(context: Context, pins: List<String>): List<Pair<String, WallpaperInfo>> {
            Settings.init(context)
            val catalog = WallpaperFactory(context, PREVIEW_WIDTH).catalog
            return pins.mapNotNull { pin -> pinRef(pin)?.let(catalog::build)?.let { pin to it } }
        }

        /** Title the automatic preview picked from a list when there was no generated set, so it stays put. */
        private var autoPick: Pair<TitleRef, ListSource>? = null

        /**
         * Render the pinned title (with its list while it is in the current set; it may be any TMDB id),
         * else the first title of the current set whose list is still selected, else one of the first
         * selected list that has a usable title. Changing a setting redraws the same title.
         */
        fun renderPreview(context: Context): Pair<Bitmap, String>? {
            Settings.init(context)
            val factory = WallpaperFactory(context, PREVIEW_WIDTH)
            val stored = WallpaperStore(context).current()
            val pin = Settings.previewPin
            pinRef(pin)?.let { ref ->
                val sourceKey = stored.firstOrNull { "${it.type}:${it.tmdbId}" == pin }?.source
                val source = Sources.available().firstOrNull { it.key == sourceKey }
                // A pin that fails says so instead of silently showing another title
                val info = factory.build(ref, source = source) ?: error(context.getString(R.string.preview_pin_not_found, pin))
                return factory.render(info) to info.title
            }
            val selected = Sources.selected()
            // Sets made before the list was stored have no source: they still came from the user's lists
            val fromSet = stored.filter { it.source.isEmpty() || selected.any { source -> source.key == it.source } }
            for (wallpaper in fromSet.take(4)) {
                val ref = pinRef("${wallpaper.type}:${wallpaper.tmdbId}") ?: continue
                val info = factory.build(ref, source = selected.firstOrNull { it.key == wallpaper.source }) ?: continue
                return factory.render(info) to info.title
            }
            autoPick?.takeIf { (_, source) -> selected.any { it.key == source.key } }?.let { (ref, source) ->
                factory.build(ref, source = source)?.let { return factory.render(it) to it.title }
            }
            for (source in selected) {
                val plan = runCatching { factory.plan(source, 3) }.getOrNull() ?: continue
                for (ref in plan.usable.take(4)) {
                    val info = factory.build(ref, plan) ?: continue
                    autoPick = ref to source
                    return factory.render(info) to info.title
                }
            }
            return null
        }
    }
}
