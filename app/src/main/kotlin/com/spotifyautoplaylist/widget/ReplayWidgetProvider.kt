package com.spotifyautoplaylist.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget receiver (TASK-060). The system delivers [onUpdate] when the widget is added
 * and after boot/restore; [onAppWidgetOptionsChanged] covers resize on API 29–30. API 31+ installs
 * size-mapped [android.widget.RemoteViews] so the launcher swaps layouts while resizing.
 * Reads run off the main thread via [goAsync]; there is no periodic widget work.
 */
class ReplayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        loadThen(context) { state -> appWidgetIds.forEach { updateAppWidget(context, manager, it, state) } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return // size map already installed
        loadThen(context) { state -> updateAppWidget(context, manager, appWidgetId, state, newOptions) }
    }

    private fun loadThen(context: Context, block: (WidgetUiState) -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                val state = runCatching { ReplayWidgetUpdater.load(context) }.getOrDefault(WidgetUiState.Loading)
                runCatching { block(state) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int, state: WidgetUiState, options: Bundle? = null) {
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ReplayWidgetViews.responsive(context, state, appWidgetId)
            } else {
                val opts = options ?: manager.getAppWidgetOptions(appWidgetId)
                val tier = WidgetLayoutTier.select(
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                )
                ReplayWidgetViews.forTier(context, tier, state, appWidgetId)
            }
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
