package com.spotifyautoplaylist.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spotifyautoplaylist.auth.AccountKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Account-scoped settings (docs/05-DATABASE.md). Thresholds are inclusive; window and time zone are fixed in v1. */
data class AccountSettings(
    val dailyThreshold: Int = 4,
    val rollingThreshold: Int = 5,
    val autoSyncEnabled: Boolean = true,
    val playlistName: String = DEFAULT_PLAYLIST_NAME,
) {
    init {
        require(dailyThreshold in DAILY_RANGE) { "dailyThreshold must be in $DAILY_RANGE" }
        require(rollingThreshold in ROLLING_RANGE) { "rollingThreshold must be in $ROLLING_RANGE" }
        require(playlistName.isNotBlank() && playlistName.length <= 100) { "invalid playlist name" }
    }

    val rollingWindowHours: Int get() = ROLLING_WINDOW_HOURS
    val timeZone: String get() = TIME_ZONE

    companion object {
        const val ROLLING_WINDOW_HOURS = 72
        const val TIME_ZONE = "Asia/Ho_Chi_Minh"
        const val DEFAULT_PLAYLIST_NAME = "My Replays — AutoPlaylist"
        val DAILY_RANGE = 2..20
        val ROLLING_RANGE = 2..30
    }
}

/**
 * One preferences file per installation, tagged with the owning account hash. Values written for another
 * account are never returned: reads fall back to defaults and the next write replaces them.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    fun settings(account: AccountKey): Flow<AccountSettings> = dataStore.data.map { it.toSettings(account) }

    suspend fun current(account: AccountKey): AccountSettings = settings(account).first()

    /** Validates via [AccountSettings]; an invalid value throws and nothing is written. */
    suspend fun update(account: AccountKey, transform: (AccountSettings) -> AccountSettings): AccountSettings {
        var result: AccountSettings? = null
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings(account))
            prefs.clear()
            prefs[OWNER] = account.accountHash
            prefs[DAILY] = updated.dailyThreshold
            prefs[ROLLING] = updated.rollingThreshold
            prefs[AUTO_SYNC] = updated.autoSyncEnabled
            prefs[PLAYLIST_NAME] = updated.playlistName
            result = updated
        }
        return checkNotNull(result)
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toSettings(account: AccountKey): AccountSettings {
        if (this[OWNER] != account.accountHash) return AccountSettings()
        val defaults = AccountSettings()
        return AccountSettings(
            dailyThreshold = this[DAILY] ?: defaults.dailyThreshold,
            rollingThreshold = this[ROLLING] ?: defaults.rollingThreshold,
            autoSyncEnabled = this[AUTO_SYNC] ?: defaults.autoSyncEnabled,
            playlistName = this[PLAYLIST_NAME] ?: defaults.playlistName,
        )
    }

    companion object {
        private val OWNER = stringPreferencesKey("owner_account_hash")
        private val DAILY = intPreferencesKey("daily_threshold")
        private val ROLLING = intPreferencesKey("rolling_threshold")
        private val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        private val PLAYLIST_NAME = stringPreferencesKey("playlist_name")

        /** Create once per process; [file] must end with `.preferences_pb`. */
        fun create(file: File) = SettingsRepository(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
