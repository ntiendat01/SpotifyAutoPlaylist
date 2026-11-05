package com.spotifyautoplaylist.playlist

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.SpotifyResourceId
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyErrorKind

/** Why a stored mapping may not be written to. Every case requires the user to relink/confirm (BR-014, 04 §8 404). */
enum class RelinkReason { UNVERIFIED, DELETED, OWNER, PRIVACY, INVALID }

sealed interface WriteTarget {
    /** No mapping for the bound account: onboarding setup has not completed. */
    data object NotLinked : WriteTarget
    /** Verified this run by API-07. [snapshotId] is the remote snapshot observed now (not persisted). */
    class Ready(val playlistId: String, val snapshotId: String, val name: String) : WriteTarget {
        override fun toString() = "Ready(<redacted>)"
    }
    data class RelinkRequired(val reason: RelinkReason) : WriteTarget
    /** Transient or unsupported API failure; no write may happen this run. */
    data class Unavailable(val kind: SpotifyErrorKind, val retryable: Boolean) : WriteTarget
}

/**
 * TASK-040 pre-write gate: re-verifies the persisted `managed_playlist` mapping of the bound account with
 * `GET /playlists/{id}` (API-07) on every run before any M4 write. Read-only: never creates, recreates or
 * relinks a playlist and never mutates the mapping (a deleted playlist is reported, not silently replaced).
 */
class ManagedPlaylistVerifier(
    private val account: suspend () -> AccountKey,
    private val mapping: suspend (spotifyUserId: String) -> ManagedPlaylistEntity?,
    private val get: suspend (playlistId: String) -> PlaylistDto,
) {
    suspend fun verify(): WriteTarget {
        val key = account()
        val stored = mapping(key.spotifyUserId) ?: return WriteTarget.NotLinked
        if (stored.spotifyUserId != key.spotifyUserId || !SpotifyResourceId.isId(stored.playlistId))
            return WriteTarget.RelinkRequired(RelinkReason.INVALID)
        if (!stored.ownershipVerified) return WriteTarget.RelinkRequired(RelinkReason.UNVERIFIED)
        val remote = try {
            get(stored.playlistId)
        } catch (e: SpotifyApiException) {
            return if (e.kind == SpotifyErrorKind.NOT_FOUND) WriteTarget.RelinkRequired(RelinkReason.DELETED)
            else WriteTarget.Unavailable(e.kind, e.retryable)
        }
        val snapshot = remote.snapshotId
        val name = remote.name
        if (remote.id != stored.playlistId || snapshot.isNullOrBlank() || name.isNullOrBlank())
            return WriteTarget.Unavailable(SpotifyErrorKind.MALFORMED_RESPONSE, retryable = true)
        if (remote.owner?.id != key.spotifyUserId) return WriteTarget.RelinkRequired(RelinkReason.OWNER)
        if (remote.public != false || remote.collaborative != false) return WriteTarget.RelinkRequired(RelinkReason.PRIVACY)
        // Account switched during the GET (AUTH-006): never hand one account's target to another.
        if (account() != key) return WriteTarget.RelinkRequired(RelinkReason.OWNER)
        return WriteTarget.Ready(stored.playlistId, snapshot, name)
    }
}
