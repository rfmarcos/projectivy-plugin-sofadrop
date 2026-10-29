package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sofascreen: the current wallpaper set as a screensaver, offline, with a transition between wallpapers,
 * an optional slow zoom and an optional clock with the date and the weather (see the screensaver settings
 * in [Settings]). A set published while it runs is picked up at the end of a round.
 */
class SofascreenView(context: Context) : FrameLayout(context) {

    private val store = WallpaperStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private var front = image()
    private var back = image()
    private val dim = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val clock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var date: TextView? = null
    /** The clock's digits and their patterns ("HH", "mm"), set by [tick]. */
    private val times = mutableListOf<Pair<TextView, String>>()
    private var weather: TextView? = null
    private val empty = TextView(context).apply {
        setText(R.string.screensaver_empty)
        setTextColor(Color.argb(200, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        visibility = GONE
    }

    private var playlist: List<StoredWallpaper> = emptyList()
    private var position = -1
    private var running = false
    private val next = Runnable { showNext() }
    private val drift = object : Runnable {
        override fun run() {
            // A few pixels every minute, so the clock doesn't burn into the panel
            val range = resources.displayMetrics.heightPixels * 0.012f
            clock.animate().translationX(Random.nextFloat() * 2 * range - range).translationY(Random.nextFloat() * 2 * range - range)
                .setDuration(2000).start()
            handler.postDelayed(this, 60_000)
        }
    }
    /** The time and date, at the start of every minute. */
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            for ((view, pattern) in times) view.text = DateFormat.format(pattern, now)
            date?.text = dateText()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    private val weatherUpdate = object : Runnable {
        override fun run() {
            updateWeather()
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun image() = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    fun start() {
        if (running) return
        running = true
        dim.alpha = 1f - Settings.screensaverBrightness / 100f
        buildClock()
        position = -1
        playlist = emptyList()
        showNext(animate = false)
        if (Settings.screensaverClock != Settings.CLOCK_OFF) {
            tick.run()
            handler.postDelayed(drift, 60_000)
        }
        if (weather != null) handler.post(weatherUpdate)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        for (view in listOf(front, back, clock)) view.animate().cancel()
        front.setImageDrawable(null)
        back.setImageDrawable(null)
    }

    override fun onDetachedFromWindow() {
        stop()
        decoder.shutdownNow()
        network.shutdownNow()
        super.onDetachedFromWindow()
    }

    // --- Wallpapers ---

    /** The next wallpaper; a new round re-reads the set, so a newly published one is used. */
    private fun showNext(animate: Boolean = true) {
        if (!running) return
        if (position + 1 >= playlist.size) {
            val current = store.current()
            playlist = if (Settings.screensaverShuffle) current.shuffled() else current
            position = -1
        }
        if (playlist.isEmpty()) {
            empty.visibility = VISIBLE
            handler.postDelayed(next, 30_000) // nothing generated yet: look again later
            return
        }
        empty.visibility = GONE
        position++
        val file = store.screensaverFileOf(playlist[position])
        val width = resources.displayMetrics.widthPixels
        decoder.execute {
            val bitmap = file?.takeIf { it.isFile }?.let { decode(it.path, width) }
            handler.post {
                if (!running) return@post
                if (bitmap == null) {
                    // Removed by a newer set: start the round again from that one
                    playlist = emptyList()
                    handler.postDelayed(next, 500)
                } else {
                    show(bitmap, animate)
                }
            }
        }
    }

    private fun show(bitmap: Bitmap, animate: Boolean) {
        val incoming = back
        val outgoing = front
        reset(incoming)
        incoming.setImageBitmap(bitmap)
        // The incoming image goes on top of the outgoing one, under the dimming and the clock
        removeView(incoming)
        addView(incoming, indexOfChild(outgoing) + 1)
        back = outgoing
        front = incoming

        val transition = Settings.screensaverTransition.let {
            if (it == Settings.TRANSITION_RANDOM) Settings.TRANSITIONS.filter { t -> t != Settings.TRANSITION_RANDOM }.random() else it
        }
        val duration = if (animate) TRANSITION_MILLIS else 0L
        val done = Runnable {
            outgoing.animate().cancel()
            reset(outgoing)
            outgoing.setImageDrawable(null)
            slowZoom(incoming)
        }
        val ease = AccelerateDecelerateInterpolator()
        when {
            duration == 0L -> done.run()
            transition == Settings.TRANSITION_BLACK -> {
                incoming.alpha = 0f
                outgoing.animate().alpha(0f).setDuration(duration / 2).setInterpolator(ease).withEndAction {
                    incoming.animate().alpha(1f).setDuration(duration / 2).setInterpolator(ease).withEndAction(done).start()
                }.start()
            }
            transition == Settings.TRANSITION_SLIDE -> {
                val distance = width.toFloat()
                incoming.translationX = distance
                outgoing.animate().translationX(-distance).setDuration(duration).setInterpolator(ease).start()
                incoming.animate().translationX(0f).setDuration(duration).setInterpolator(ease).withEndAction(done).start()
            }
            transition == Settings.TRANSITION_ZOOM -> {
                incoming.alpha = 0f
                incoming.scaleX = 1.15f
                incoming.scaleY = 1.15f
                incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).setInterpolator(ease).withEndAction(done).start()
            }
            else -> {
                incoming.alpha = 0f
                incoming.animate().alpha(1f).setDuration(duration).setInterpolator(ease).withEndAction(done).start()
            }
        }
        handler.removeCallbacks(next)
        handler.postDelayed(next, Settings.screensaverSeconds * 1000L + duration)
    }

    /**
     * Zoom in slowly while the wallpaper is shown, anchored left (bottom left for the copies with the
     * text at the bottom) so its logo and text stay in view.
     */
    private fun slowZoom(view: ImageView) {
        if (!Settings.screensaverZoom) return
        val screen = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        view.pivotX = 0f
        view.pivotY = if (Settings.screensaverInfoBottom) screen else screen / 2f
        view.animate().scaleX(SLOW_ZOOM).scaleY(SLOW_ZOOM).setDuration(Settings.screensaverSeconds * 1000L)
            .setInterpolator(LinearInterpolator()).start()
    }

    private fun reset(view: ImageView) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.pivotX = width / 2f
        view.pivotY = height / 2f
    }

    // --- Clock ---

    private fun buildClock() {
        clock.removeAllViews()
        times.clear()
        date = null
        weather = null
        val style = Settings.screensaverClock
        clock.visibility = if (style == Settings.CLOCK_OFF) GONE else VISIBLE
        if (style == Settings.CLOCK_OFF) return
        val height = resources.displayMetrics.heightPixels.toFloat()
        val h24 = when (Settings.screensaverClockFormat) {
            Settings.CLOCK_FORMAT_24 -> true
            Settings.CLOCK_FORMAT_12 -> false
            else -> DateFormat.is24HourFormat(context)
        }
        val digits = ResourcesCompat.getFont(context, R.font.roboto_bold)
        val light = ResourcesCompat.getFont(context, R.font.roboto_light)
        val small = height * 0.045f

        if (style == Settings.CLOCK_STACKED) {
            // Hours, the date, minutes: one block that reads at a glance
            clock.gravity = Gravity.CENTER_HORIZONTAL
            clock.addView(clockText(if (h24) "HH" else "h", height * 0.17f, digits))
            if (Settings.screensaverDate) date = smallText(small, light).also { clock.addView(it) }
            else clock.addView(divider(height), LinearLayout.LayoutParams((height * 0.13f).toInt(), (height * 0.004f).toInt().coerceAtLeast(2)).apply {
                // Without the date: a thin line between hours and minutes
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (height * 0.022f).toInt()
                bottomMargin = (height * 0.026f).toInt()
            })
            clock.addView(clockText("mm", height * 0.17f, digits))
        } else {
            clock.gravity = Gravity.END
            clock.addView(clockText(if (h24) "HH:mm" else "h:mm", height * 0.11f, digits))
            if (Settings.screensaverDate) date = smallText(small, light).also { clock.addView(it) }
        }
        if (Settings.screensaverWeather && Settings.weatherCoordinates != null) {
            weather = smallText(small * 0.85f, light).also {
                it.visibility = GONE // until there is a reading
                it.setPadding(0, (small * 0.4f).toInt(), 0, 0)
                clock.addView(it)
            }
        }

        val params = clock.layoutParams as LayoutParams
        params.gravity = Gravity.END or (if (Settings.screensaverClockBottom) Gravity.BOTTOM else Gravity.TOP)
        params.marginEnd = (resources.displayMetrics.widthPixels * 0.055f).toInt()
        params.topMargin = (height * 0.07f).toInt()
        params.bottomMargin = (height * 0.07f).toInt()
        clock.layoutParams = params
    }

    private fun clockText(pattern: String, sizePx: Float, font: android.graphics.Typeface?) = TextView(context).apply {
        style(this, sizePx, font)
        maxLines = 1
        times += this to pattern
    }

    /** Rounded, slightly see-through white, like the light date it replaces. */
    private fun divider(height: Float) = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(190, 255, 255, 255))
            cornerRadius = height * 0.002f
        }
    }

    /** The date and weather lines: thin capitals, spaced a little. */
    private fun smallText(sizePx: Float, font: android.graphics.Typeface?) = TextView(context).apply {
        style(this, sizePx, font)
        setAllCaps(true)
        letterSpacing = 0.04f
    }

    private fun style(view: TextView, sizePx: Float, font: android.graphics.Typeface?) = view.apply {
        // Its own width: by default a vertical LinearLayout makes it as wide as the block, which then
        // keeps the width it had before the text was set, cutting "12" down to "1"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        setShadowLayer(sizePx * 0.08f, 0f, sizePx * 0.02f, Color.argb(150, 0, 0, 0))
    }

    /** "MA·26·OKT", "MON·OCT·26": weekday, day and month in the order of the language, without their dots. */
    private fun dateText(): String {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEdMMM").replace(Regex("'[^']*'"), "")
        val now = Date()
        return Regex("E+|c+|d+|M+|L+").findAll(pattern)
            .joinToString("·") { SimpleDateFormat(it.value, locale).format(now).replace(".", "").trim() }
    }

    // --- Weather ---

    /** "12° · Partly cloudy" under the clock, off the main thread; the line stays hidden while there is none. */
    private fun updateWeather() {
        val coordinates = Settings.weatherCoordinates ?: return
        network.execute {
            val now = Weather.current(coordinates)
            handler.post {
                val line = weather ?: return@post
                if (!running) return@post
                line.visibility = if (now == null) GONE else VISIBLE
                if (now != null) line.text = "${now.temperature}°  ·  ${context.getString(Weather.description(now.code))}"
            }
        }
    }

    companion object {
        private const val TRANSITION_MILLIS = 1500L
        private const val SLOW_ZOOM = 1.06f

        /** Decode an image subsampled close to [width], like the wallpaper viewer. */
        private fun decode(path: String, width: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/** The system screensaver ("Sofascreen" in Android's screensaver settings). */
class SofascreenService : DreamService() {

    private var view: SofascreenView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Settings.init(this)
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        view = SofascreenView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
        askForMissingCopies()
    }

    /**
     * Running as the screensaver while the set has no copies of its own (Sofascreen was chosen after the
     * last update, or Android didn't say it was): make the set again with them, at most every few hours.
     */
    private fun askForMissingCopies() {
        if (!Settings.screensaverDiffers || Settings.generating) return
        val set = WallpaperStore(this).current()
        if (set.isEmpty() || set.all { it.screensaverFile.isNotEmpty() }) return
        val now = System.currentTimeMillis()
        if (now - Settings.screensaverCopiesAskedAt < COPIES_RETRY_MILLIS) return
        Settings.screensaverCopiesAskedAt = now
        GenerateWallpapersWorker.runNow(this, screensaver = true)
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    companion object {
        private const val COPIES_RETRY_MILLIS = 6 * 3_600_000L

        /**
         * False only when Android says another screensaver (or none) is chosen; true when it doesn't
         * say (a TV that hides the setting): then the screensaver's copies are drawn anyway.
         */
        fun isChosen(context: Context): Boolean = try {
            val resolver = context.contentResolver
            val enabled = android.provider.Settings.Secure.getInt(resolver, "screensaver_enabled", 1) != 0
            val components = android.provider.Settings.Secure.getString(resolver, "screensaver_components")
            enabled && (components == null || ScreensaverFragment.component(context) in components ||
                "${context.packageName}/.${SofascreenService::class.java.simpleName}" in components)
        } catch (_: Exception) {
            true // not readable for apps on this Android version
        }
    }
}

/** Sofascreen started from the settings, to try it; any key closes it, as with the real screensaver. */
class SofascreenActivity : AppCompatActivity() {

    private lateinit var view: SofascreenView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = SofascreenView(this)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        view.start()
    }

    override fun onStop() {
        view.stop()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }
}
