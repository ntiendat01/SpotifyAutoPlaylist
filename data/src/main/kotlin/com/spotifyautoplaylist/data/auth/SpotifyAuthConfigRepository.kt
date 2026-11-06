package com.spotifyautoplaylist.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spotifyautoplaylist.auth.AuthConfig
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.auth.OwnerIdentity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Installation-scoped Spotify Developer App identifiers (public Client ID + owner user ID).
 * Survives account [com.spotifyautoplaylist.data.LocalDataReset] so a normal sign-out / reset does not
 * wipe the Developer App binding; only an explicit [clear] or overwrite via [save] changes them.
 */
class SpotifyAuthConfigRepository(private val dataStore: DataStore<Preferences>) {
    fun observe(): Flow<AuthConfig?> = dataStore.data.map { it.toConfig() }

    suspend fun current(): AuthConfig? = observe().first()

    /**
     * Validates and persists both values. Partial or invalid input fails with [AuthFailure.NOT_CONFIGURED]
     * and writes nothing.
     */
    suspend fun save(clientId: String, ownerUserId: String): AuthConfig {
        val config = AuthConfig.fromBuild(clientId.trim(), ownerUserId.trim())
            ?: throw AuthException(AuthFailure.NOT_CONFIGURED)
        dataStore.edit { prefs ->
            prefs[CLIENT_ID] = config.clientId
            prefs[OWNER_USER_ID] = config.expectedOwnerId
        }
        return config
    }

    /**
     * When the store is empty and both build values are present, copies them once.
     * Empty build config leaves the store empty so the UI can collect IDs at runtime.
     */
    suspend fun ensureSeeded(buildClientId: String, buildOwnerUserId: String): AuthConfig? {
        current()?.let { return it }
        val seed = AuthConfig.fromBuild(buildClientId.trim(), buildOwnerUserId.trim()) ?: return null
        return save(seed.clientId, seed.expectedOwnerId)
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toConfig(): AuthConfig? {
        val clientId = this[CLIENT_ID] ?: return null
        val ownerId = this[OWNER_USER_ID] ?: return null
        return try {
            AuthConfig.fromBuild(clientId, ownerId)
        } catch (_: AuthException) {
            null
        }
    }

    companion object {
        private val CLIENT_ID = stringPreferencesKey("spotify_client_id")
        private val OWNER_USER_ID = stringPreferencesKey("spotify_owner_user_id")

        /** Mask for Settings copy; never log the raw values. */
        fun maskId(value: String): String = OwnerIdentity.mask(value)

        /** Create once per process; [file] must end with `.preferences_pb`. */
        fun create(file: File) = SpotifyAuthConfigRepository(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
