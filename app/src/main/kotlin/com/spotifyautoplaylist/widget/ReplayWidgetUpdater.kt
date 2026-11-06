package com.spotifyautoplaylist.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.spike.BuildConfig
import com.spotifyautoplaylist.spike.ReplayApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads persisted widget data (Room + sync-status DataStore only — never auth tokens) and
 * pushes RemoteViews. Called after sync completes and after cheap local changes (setup / sign-out).
 */
object ReplayWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Non-blocking refresh for UI / setup hooks. */
    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                refresh(app)
            } catch (_: Exception) {
                // Never crash the host; a later sync or launcher update retries.
            }
        }
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, ReplayWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = withContext(Dispatchers.IO) { load(app) }
        for (id in ids) {
            ReplayWidgetProvider.updateAppWidget(app, manager, id, state)
        }
    }

    suspend fun load(context: Context): WidgetUiState {
        val configured = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()
        val app = context.applicationContext as? ReplayApplication
        if (app == null) {
            return WidgetUiMapper.map(
                WidgetPersistedInput(
                    spotifyConfigured = configured,
                    hasAccount = false,
                    playlistId = null,
                    playlistName = null,
                    playlistUrl = null,
                    addedCount = null,
                    status = SyncRunStatus(),
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }
        val container = app.container
        val account = container.database.accounts().firstOrNull()
        val playlist = account?.let { container.database.playlists().managed(it.spotifyUserId) }
        val added = if (account != null && playlist != null) {
            container.database.playlists().countPresent(account.spotifyUserId, playlist.playlistId)
        } else {
            null
        }
        val status = container.syncStatus.stored()
        return WidgetUiMapper.map(
            WidgetPersistedInput(
                spotifyConfigured = configured,
                hasAccount = account != null,
                playlistId = playlist?.playlistId,
                playlistName = playlist?.name,
                playlistUrl = playlist?.playlistUrl,
                addedCount = added,
                status = status,
                nowMs = System.currentTimeMillis(),
            ),
        )
    }
}
