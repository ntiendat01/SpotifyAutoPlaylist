package com.spotifyautoplaylist.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spotifyautoplaylist.auth.AccountKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * TASK-051 last full-pass status (history + qualification + playlist write). History cursors and coverage stay
 * in Room `sync_state`; this only adds what that table cannot hold without a migration: the pass outcome and
 * the "do not call Spotify before" time from Retry-After / quota. Codes are enum names only, never bodies.
 */
data class SyncRunStatus(
    val outcome: String? = null,
    val finishedAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    val blockedUntilMs: Long? = null,
    val errorCode: String? = null,
    val imported: Int = 0,
    val newlyQualified: Int = 0,
    val added: Int = 0,
)

/** One preferences file per installation, tagged with the owning account hash like [com.spotifyautoplaylist.data.settings.SettingsRepository]. */
class SyncStatusRepository(private val dataStore: DataStore<Preferences>) {
    fun status(account: AccountKey): Flow<SyncRunStatus> = dataStore.data.map { it.toStatus(account) }

    suspend fun current(account: AccountKey): SyncRunStatus = status(account).first()

    /**
     * Installation-scoped read for home-screen widgets: returns whatever pass is stored for the current
     * owner tag, without needing an [AccountKey] (and without touching auth tokens). Empty when unbound.
     */
    suspend fun stored(): SyncRunStatus = dataStore.data.map { prefs ->
        if (prefs[OWNER] == null) SyncRunStatus()
        else SyncRunStatus(
            prefs[OUTCOME], prefs[FINISHED], prefs[SUCCESS], prefs[BLOCKED], prefs[ERROR],
            prefs[IMPORTED] ?: 0, prefs[QUALIFIED] ?: 0, prefs[ADDED] ?: 0,
        )
    }.first()

    suspend fun record(account: AccountKey, status: SyncRunStatus) {
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[OWNER] = account.accountHash
            status.outcome?.let { prefs[OUTCOME] = it }
            status.finishedAtMs?.let { prefs[FINISHED] = it }
            status.lastSuccessAtMs?.let { prefs[SUCCESS] = it }
            status.blockedUntilMs?.let { prefs[BLOCKED] = it }
            status.errorCode?.let { prefs[ERROR] = it }
            prefs[IMPORTED] = status.imported
            prefs[QUALIFIED] = status.newlyQualified
            prefs[ADDED] = status.added
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toStatus(account: AccountKey): SyncRunStatus {
        if (this[OWNER] != account.accountHash) return SyncRunStatus()
        return SyncRunStatus(this[OUTCOME], this[FINISHED], this[SUCCESS], this[BLOCKED], this[ERROR],
            this[IMPORTED] ?: 0, this[QUALIFIED] ?: 0, this[ADDED] ?: 0)
    }

    companion object {
        private val OWNER = stringPreferencesKey("owner_account_hash")
        private val OUTCOME = stringPreferencesKey("outcome")
        private val FINISHED = longPreferencesKey("finished_at_ms")
        private val SUCCESS = longPreferencesKey("last_success_at_ms")
        private val BLOCKED = longPreferencesKey("blocked_until_ms")
        private val ERROR = stringPreferencesKey("error_code")
        private val IMPORTED = intPreferencesKey("imported")
        private val QUALIFIED = intPreferencesKey("newly_qualified")
        private val ADDED = intPreferencesKey("added")

        /** Create once per process; [file] must end with `.preferences_pb`. */
        fun create(file: File) = SyncStatusRepository(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
