package com.spotifyautoplaylist.widget

/**
 * Size → layout selection for the home-screen widget (TASK-060).
 * Breakpoints are in dp (AppWidgetManager OPTION_APPWIDGET_*_WIDTH/HEIGHT) and match the
 * API 31+ SizeF map in [ReplayWidgetViews.responsive].
 *
 * - COMPACT (~2×1, 4×1, 3×1): count + status + round "open in Spotify" button
 * - MEDIUM (default 4×2): brand, last success, count, status chip, two pill actions
 * - LARGE (~4×3 .. 5×4+): hero card with playlist name, larger type, 48dp actions
 */
enum class WidgetLayoutTier(val minWidthDp: Float, val minHeightDp: Float) {
    COMPACT(110f, 40f),
    MEDIUM(220f, 130f),
    LARGE(300f, 240f),
    ;

    companion object {
        fun select(widthDp: Int, heightDp: Int): WidgetLayoutTier = when {
            widthDp >= LARGE.minWidthDp && heightDp >= LARGE.minHeightDp -> LARGE
            widthDp >= MEDIUM.minWidthDp && heightDp >= MEDIUM.minHeightDp -> MEDIUM
            else -> COMPACT
        }
    }
}
