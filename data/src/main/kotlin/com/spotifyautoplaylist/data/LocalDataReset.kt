package com.spotifyautoplaylist.data

import com.spotifyautoplaylist.auth.AccountResetStep
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.data.sync.SyncStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Account reset step for all local account data (one account per installation in v1). Idempotent, so an
 * interrupted reset can be repeated. Never touches the remote Spotify playlist.
 */
class LocalDataReset(
    private val database: ReplayDatabase,
    private val settings: SettingsRepository,
    private val syncStatus: SyncStatusRepository? = null,
) : AccountResetStep {
    override suspend fun reset() {
        withContext(Dispatchers.IO) { database.clearAllTables() }
        settings.clear()
        syncStatus?.clear()
    }
}
