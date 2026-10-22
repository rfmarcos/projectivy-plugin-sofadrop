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

    override fun onCreateGuidanceStylist(): GuidanceStylist = object : GuidanceStylist() {
        override fun onProvideLayoutId() = R.layout.settings_guidance

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, guidance: Guidance): View {
            val view = super.onCreateView(inflater, container, guidance)
            previewImage = view.findViewById(R.id.preview_image)
            previewProgress = view.findViewById(R.id.preview_progress)
            previewLabel = view.findViewById(R.id.preview_label)
            pendingNotice = view.findViewById(R.id.pending_notice)
            view.findViewById<View>(R.id.preview_frame).clipToOutline = true
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
        if (Sources.selected().isEmpty()) {
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

        /** The pinned title while it is still in the current set; a pin left behind by a new set is dropped. */
        private fun pinnedRef(context: Context): Pair<TitleRef, String>? {
            val pin = Settings.previewPin.ifEmpty { return null }
            val stored = WallpaperStore(context).current().firstOrNull { "${it.type}:${it.tmdbId}" == pin }
            val type = MediaType.entries.firstOrNull { it.path == stored?.type }
            if (stored == null || type == null) {
                Settings.previewPin = ""
                return null
            }
            return TitleRef(type, stored.tmdbId) to stored.source
        }

        /** Render the pinned wallpaper, else one of the first selected list that has a usable title. */
        fun renderPreview(context: Context): Pair<Bitmap, String>? {
            Settings.init(context)
            val factory = WallpaperFactory(context, PREVIEW_WIDTH)
            pinnedRef(context)?.let { (ref, sourceKey) ->
                val source = Sources.available().firstOrNull { it.key == sourceKey }
                factory.build(ref, source = source)?.let { info -> return factory.render(info) to info.title }
            }
            for (source in Sources.selected()) {
                val plan = runCatching { factory.plan(source, 3) }.getOrNull() ?: continue
                for (ref in plan.usable.take(4)) {
                    val info = factory.build(ref, plan) ?: continue
                    return factory.render(info) to info.title
                }
            }
            return null
        }
    }
}
