package com.rfmarcos.tmdbwallpapers

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen preview of the generated wallpapers, navigated with the D-pad. */
class PreviewActivity : AppCompatActivity() {

    private lateinit var image: ImageView
    private lateinit var caption: TextView
    private lateinit var store: WallpaperStore
    private var wallpapers: List<StoredWallpaper> = emptyList()
    private var position = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WallpaperStore(this)
        wallpapers = store.current()

        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        caption = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            textSize = 16f
            setPadding(32, 16, 32, 24)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(caption, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
            ))
        })
        show()
    }

    private fun show() {
        if (wallpapers.isEmpty()) {
            caption.setText(R.string.preview_empty)
            return
        }
        val wallpaper = wallpapers[position]
        val file = store.fileOf(wallpaper)
        val metrics = resources.displayMetrics
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file?.path, options)
        var sample = 1
        while (options.outWidth / (sample * 2) >= metrics.widthPixels) sample *= 2
        image.setImageBitmap(BitmapFactory.decodeFile(file?.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        caption.text = "${wallpaper.title}   ${getString(R.string.preview_counter, position + 1, wallpapers.size)}"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (wallpapers.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> position = (position + 1) % wallpapers.size
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> position = (position - 1 + wallpapers.size) % wallpapers.size
            else -> return super.onKeyDown(keyCode, event)
        }
        show()
        return true
    }
}
