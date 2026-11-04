package com.spotifyautoplaylist.onboarding

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.SpotifyResourceId
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.PlaylistsPage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

const val DEFAULT_PLAYLIST_NAME = "My Replays — AutoPlaylist"

class SetupPlaylist(val id: String, val name: String) {
    init {
        if (!SpotifyResourceId.isId(id) || name.isBlank()) throw SetupException(SetupFailure.RESPONSE)
    }
    val url: String get() = "https://open.spotify.com/playlist/$id"
    override fun toString() = "SetupPlaylist(<redacted>)"
}

data class SetupSnapshot(val playlist: SetupPlaylist? = null, val recoveryRequired: Boolean = false)

enum class SetupFailure { CONFIRMATION, INVALID_NAME, INVALID_LINK, OWNER, PRIVACY, RESPONSE, RECOVERY, STORAGE, ALREADY_LINKED }
class SetupException(val reason: SetupFailure) : Exception(reason.name)

class CreationRecord(
    val marker: String = "[replay-setup:${UUID.randomUUID()}]",
    val attempted: Boolean = false,
    val playlistId: String? = null,
) {
    fun attempted() = CreationRecord(marker, true, playlistId)
    fun identified(id: String) = CreationRecord(marker, true, id)
    override fun toString() = "CreationRecord(attempted=$attempted, <redacted>)"
}

interface SetupPersistence {
    suspend fun playlist(account: AccountKey): SetupPlaylist?
    suspend fun savePlaylist(account: AccountKey, playlist: SetupPlaylist)
    suspend fun record(account: AccountKey): CreationRecord
    suspend fun saveRecord(account: AccountKey, record: CreationRecord)
}

interface SetupApi {
    suspend fun create(name: String, description: String): PlaylistDto
    suspend fun get(id: String): PlaylistDto
    suspend fun ownPlaylists(offset: Int): PlaylistsPage
}

interface PlaylistSetup {
    suspend fun load(): SetupSnapshot
    suspend fun create(name: String, confirmed: Boolean): SetupPlaylist
    suspend fun recover(): SetupPlaylist
    suspend fun link(input: String, confirmed: Boolean): SetupPlaylist
}

/** Only onboarding FR-002. No track read, counting, outbox or playlist-item POST belongs here. */
class PlaylistSetupRepository(
    private val account: suspend () -> AccountKey,
    private val api: SetupApi,
    private val persistence: SetupPersistence,
) : PlaylistSetup {
    private val mutex = Mutex()

    override suspend fun load(): SetupSnapshot = mutex.withLock {
        val key = account()
        SetupSnapshot(persistence.playlist(key), persistence.record(key).attempted)
    }

    override suspend fun create(name: String, confirmed: Boolean): SetupPlaylist = mutex.withLock {
        if (!confirmed) throw SetupException(SetupFailure.CONFIRMATION)
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > 100) throw SetupException(SetupFailure.INVALID_NAME)
        val key = account()
        persistence.playlist(key)?.let { return@withLock it }
        val record = persistence.record(key)
        if (record.attempted) return@withLock recoverLocked(key, record)
        // Persist the intention BEFORE POST. Even cancellation/process death cannot enable a blind retry.
        withContext(NonCancellable) {
            persistence.saveRecord(key, record.attempted())
            val created = api.create(cleanName, "Auto-collected repeat plays ${record.marker}")
            if (!SpotifyResourceId.isId(created.id)) throw SetupException(SetupFailure.RESPONSE)
            persistence.saveRecord(key, record.identified(created.id))
            verifyAndSave(key, created.id)
        }
    }

    override suspend fun recover(): SetupPlaylist = mutex.withLock {
        val key = account()
        persistence.playlist(key)?.let { return@withLock it }
        recoverLocked(key, persistence.record(key))
    }

    override suspend fun link(input: String, confirmed: Boolean): SetupPlaylist = mutex.withLock {
        if (!confirmed) throw SetupException(SetupFailure.CONFIRMATION)
        val id = SpotifyResourceId.parse(input, "playlist") ?: throw SetupException(SetupFailure.INVALID_LINK)
        val key = account()
        persistence.playlist(key)?.let {
            if (it.id != id) throw SetupException(SetupFailure.ALREADY_LINKED)
            return@withLock it
        }
        // Failed validation must not replace a known recovery ID.
        verifyAndSave(key, id)
    }

    private suspend fun recoverLocked(key: AccountKey, record: CreationRecord): SetupPlaylist {
        record.playlistId?.let { return verifyAndSave(key, it) }
        if (!record.attempted) throw SetupException(SetupFailure.RECOVERY)
        var offset = 0
        var initialTotal: Int? = null
        val matches = linkedSetOf<String>()
        // Bounded, validated offset pagination. Never follow arbitrary server-provided URLs.
        repeat(220) {
            val page = api.ownPlaylists(offset)
            if (page.offset != offset || page.total !in 0..11_000 || page.items.size > 50 ||
                (initialTotal != null && initialTotal != page.total)) throw SetupException(SetupFailure.RECOVERY)
            initialTotal = page.total
            page.items.filter { it.owner?.id == key.spotifyUserId && it.description?.contains(record.marker) == true }
                .forEach { matches.add(it.id) }
            offset += page.items.size
            if (offset > page.total) throw SetupException(SetupFailure.RECOVERY)
            if (offset == page.total && page.next == null) {
                val id = matches.singleOrNull() ?: throw SetupException(SetupFailure.RECOVERY)
                persistence.saveRecord(key, record.identified(id))
                return verifyAndSave(key, id)
            }
            if (page.items.isEmpty() || page.next == null) throw SetupException(SetupFailure.RECOVERY)
        }
        throw SetupException(SetupFailure.RECOVERY)
    }

    private suspend fun verifyAndSave(key: AccountKey, id: String): SetupPlaylist {
        if (!SpotifyResourceId.isId(id)) throw SetupException(SetupFailure.RESPONSE)
        val remote = api.get(id)
        val name = remote.name
        if (remote.id != id || remote.snapshotId.isNullOrBlank() || name.isNullOrBlank())
            throw SetupException(SetupFailure.RESPONSE)
        if (remote.owner?.id != key.spotifyUserId) throw SetupException(SetupFailure.OWNER)
        if (remote.public != false || remote.collaborative != false) throw SetupException(SetupFailure.PRIVACY)
        // Account reset/sign-out must not allow a result to be attached to a different active account.
        if (account() != key) throw SetupException(SetupFailure.OWNER)
        return SetupPlaylist(id, name).also { persistence.savePlaylist(key, it) }
    }
}
