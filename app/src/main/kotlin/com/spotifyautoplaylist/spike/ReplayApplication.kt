package com.spotifyautoplaylist.spike

import android.app.Application
import com.spotifyautoplaylist.sync.SyncOrchestrator
import com.spotifyautoplaylist.sync.SyncOrchestratorProvider

/** Exactly one auth mutex, API client, Room and DataStore graph across activity recreation. */
class ReplayApplication : Application(), SyncOrchestratorProvider {
    val container: AppContainer by lazy { AppContainer(this) }

    /** WorkManager's default initializer stays; [com.spotifyautoplaylist.sync.SyncWorker] reaches the graph here. */
    override val syncOrchestrator: SyncOrchestrator? get() = container.syncOrchestrator
}
