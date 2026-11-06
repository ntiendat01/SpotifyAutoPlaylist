package com.spotifyautoplaylist.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.spotifyautoplaylist.spike.MainActivity
import com.spotifyautoplaylist.spike.R

/** Builds size-mapped RemoteViews for [ReplayWidgetProvider], styled after the refreshed Compose UI. */
object ReplayWidgetViews {
    fun forTier(context: Context, tier: WidgetLayoutTier, state: WidgetUiState, widgetId: Int): RemoteViews {
        val layout = when (tier) {
            WidgetLayoutTier.COMPACT -> R.layout.widget_replay_compact
            WidgetLayoutTier.MEDIUM -> R.layout.widget_replay_medium
            WidgetLayoutTier.LARGE -> R.layout.widget_replay_large
        }
        return RemoteViews(context.packageName, layout).also { bind(context, it, tier, state, widgetId) }
    }

    /** API 31+: launcher picks the largest entry that fits while the user resizes. */
    fun responsive(context: Context, state: WidgetUiState, widgetId: Int): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(WidgetLayoutTier.entries.associate { tier ->
                SizeF(tier.minWidthDp, tier.minHeightDp) to forTier(context, tier, state, widgetId)
            })
        } else {
            forTier(context, WidgetLayoutTier.MEDIUM, state, widgetId)
        }

    private fun bind(context: Context, views: RemoteViews, tier: WidgetLayoutTier, state: WidgetUiState, widgetId: Int) {
        val problem = state.statusIsProblem
        val root = android.R.id.background
        views.setContentDescription(root, state.contentDescription)
        views.setOnClickPendingIntent(root, openApp(context, widgetId))
        views.setTextViewText(R.id.widget_count, state.countValue)
        views.setTextViewText(R.id.widget_status, state.statusLine)
        views.setImageViewResource(R.id.widget_status_icon, if (problem) R.drawable.widget_ic_info else R.drawable.widget_ic_sync)
        // No linked playlist (logged out / not configured / no playlist): hide the Spotify action rather
        // than pointing it somewhere else; the card and "Mở app" still open the app.
        views.setViewVisibility(R.id.widget_open_playlist, if (state.canOpenPlaylist) View.VISIBLE else View.GONE)
        if (state.canOpenPlaylist) views.setOnClickPendingIntent(R.id.widget_open_playlist, openPlaylist(context, widgetId, state))

        if (tier == WidgetLayoutTier.COMPACT) {
            views.setTextColor(R.id.widget_status, context.getColor(if (problem) R.color.widget_warning else R.color.widget_text_secondary))
            return
        }
        // Status chip: SyncStatusChip normally, CoverageBanner-like warning tint when it needs attention.
        views.setInt(R.id.widget_status_chip, "setBackgroundResource", if (problem) R.drawable.widget_chip_warning else R.drawable.widget_chip)
        views.setTextColor(R.id.widget_status, context.getColor(if (problem) R.color.widget_warning else R.color.widget_text))
        views.setTextViewText(R.id.widget_count_caption, state.countCaption)
        views.setOnClickPendingIntent(R.id.widget_open_app, openApp(context, widgetId))
        when (tier) {
            WidgetLayoutTier.MEDIUM -> views.setTextViewText(R.id.widget_last_sync_short, state.lastSyncShort)
            WidgetLayoutTier.LARGE -> {
                views.setTextViewText(R.id.widget_last_sync, state.lastSyncLine)
                views.setTextViewText(R.id.widget_playlist_name, state.playlistLine)
            }
            WidgetLayoutTier.COMPACT -> Unit
        }
    }

    private fun openApp(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openPlaylist(context: Context, widgetId: Int, state: WidgetUiState): PendingIntent {
        // Same https App Link MainActivity opens; Spotify claims it when installed, otherwise the browser.
        val target: Uri = requireNotNull(state.playlistUrl?.toUri() ?: state.playlistUri?.toUri())
        val intent = Intent(Intent.ACTION_VIEW, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return PendingIntent.getActivity(context, widgetId + 10_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
