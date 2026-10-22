package com.rfmarcos.sofadrop

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import kotlinx.serialization.Serializable

/** A rectangle as [left, top, right, bottom] in screen pixels. */
typealias Box = List<Int>

/**
 * Where Projectivy's home screen puts its rows, app cards and status cluster, read by
 * [LayoutReaderService] (or the generic default). Coordinates are in [width]x[height] screen pixels.
 */
@Serializable
data class LauncherLayout(
    val width: Int,
    val height: Int,
    val capturedAt: Long,
    val rows: List<Box>,
    val cards: List<Card>,
    val status: Box? = null,
    val clock: Box? = null,
) {
    @Serializable
    data class Card(val label: String, val box: Box, val focused: Boolean = false)

    companion object {
        private const val ROW_ID = "/row_content"
        private const val STATUS_ID = "/statusbar_icons_container"
        private const val CLOCK_ID = "/title_text"

        /**
         * Projectivy 4.72's default home on a 1080p TV (measured on a real one): status cluster top
         * right, a row of app banners at about 65% of the height with the first card focused, and the
         * start of a second row at the bottom.
         */
        val GENERIC = LauncherLayout(
            1920, 1080, 0,
            rows = listOf(listOf(96, 694, 1824, 870), listOf(96, 909, 1824, 1080)),
            cards = listOf(Card("", listOf(96, 699, 387, 865), focused = true)) +
                // Rows scroll sideways: the last cards are cut at the row's right edge, as Projectivy reports them
                (0 until 6).map { Card("", listOf(384 + 272 * it, 710, minOf(640 + 272 * it, 1824), 854)) } +
                (0 until 6).map { Card("", listOf(112 + 336 * it, 925, minOf(432 + 336 * it, 1824), 1080)) },
            status = listOf(1439, 56, 1599, 136),
            clock = listOf(1599, 54, 1808, 138),
        )

        /** Read Projectivy's home from its window; null when it isn't showing rows of apps (e.g. its settings). */
        fun capture(root: AccessibilityNodeInfo, now: Long): LauncherLayout? {
            val screen = Rect().also(root::getBoundsInScreen)
            if (screen.width() <= 0 || screen.height() <= 0) return null
            val rows = mutableListOf<Box>()
            val cards = mutableListOf<Card>()
            var status: Box? = null
            var clock: Box? = null
            fun boxOf(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).let { listOf(it.left, it.top, it.right, it.bottom) }
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30) return
                val id = node.viewIdResourceName.orEmpty()
                when {
                    id.endsWith(ROW_ID) -> {
                        rows += boxOf(node)
                        for (i in 0 until node.childCount) {
                            val card = node.getChild(i) ?: continue
                            val label = card.contentDescription?.toString().orEmpty()
                            if (label.isNotEmpty()) cards += Card(label, boxOf(card), card.isFocused || card.isAccessibilityFocused)
                        }
                        return
                    }
                    id.endsWith(STATUS_ID) -> status = boxOf(node)
                    id.endsWith(CLOCK_ID) -> clock = boxOf(node)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
            val layout = LauncherLayout(screen.width(), screen.height(), now, rows, cards, status, clock)
            return layout.takeIf { it.isPlausible() }
        }
    }

    /** Rows with cards, every box inside the screen and not degenerate. */
    fun isPlausible(): Boolean {
        if (rows.isEmpty() || cards.isEmpty()) return false
        val boxes = rows + cards.map { it.box } + listOfNotNull(status, clock)
        return boxes.all { (l, t, r, b) -> r > l && b > t && l >= 0 && t >= 0 && r <= width + 1 && b <= height + 1 }
    }
}
