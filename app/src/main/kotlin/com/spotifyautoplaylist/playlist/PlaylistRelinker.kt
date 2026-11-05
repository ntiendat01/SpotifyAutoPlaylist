package com.spotifyautoplaylist.playlist

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.SpotifyResourceId
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.SpotifyApiException

/** Result of one relink attempt. Only enum names / counts; never IDs. */
sealed interface RelinkResult {
    /** Mapping re-pointed; [moved] open outbox rows now target the new playlist as PENDING. */
    data class Relinked(val moved: Int) : RelinkResult
    data class Rejected(val reason: Reason) : RelinkResult
    enum class Reason {
        CONFIRMATION, INVALID_LINK, NOT_LINKED,
        /** Current playlist verified fine this run: never replaced (no silent replacement). */
        NOT_REQUIRED,
        /** Current state could not be verified (network, 429, 403...): try again later. */
        UNAVAILABLE,
        SAME_PLAYLIST, OWNER, PRIVACY, RESPONSE, NOT_FOUND, ACCOUNT_CHANGED,
    }
}

/**
 * TASK-052 relink (TC-016 second half): the user confirms a link to an existing private playlist they own after
 * the current mapping became RelinkRequired (deleted, owner changed, public/collaborative, unverified, invalid).
 * Re-verifies the current mapping first (only RelinkRequired may be replaced), then the candidate with API-07,
 * then commits [com.spotifyautoplaylist.data.local.QualificationDao.relink] atomically. Never creates a
 * playlist and never POSTs; the next sync pass reconciles the new playlist before any write (exactly once).
 * Callers must serialize this with sync passes for the account ([com.spotifyautoplaylist.sync.SyncOrchestrator.exclusive]).
 */
class PlaylistRelinker(
    private val account: suspend () -> AccountKey,
    private val database: ReplayDatabase,
    private val verifier: ManagedPlaylistVerifier,
    private val get: suspend (playlistId: String) -> PlaylistDto,
) {
    suspend fun relink(input: String, confirmed: Boolean): RelinkResult {
        if (!confirmed) return reject(RelinkResult.Reason.CONFIRMATION)
        val id = SpotifyResourceId.parse(input, "playlist") ?: return reject(RelinkResult.Reason.INVALID_LINK)
        val key = account()
        val current = database.playlists().managed(key.spotifyUserId) ?: return reject(RelinkResult.Reason.NOT_LINKED)
        when (verifier.verify()) {
            is WriteTarget.RelinkRequired -> Unit
            is WriteTarget.Ready -> return reject(RelinkResult.Reason.NOT_REQUIRED)
            WriteTarget.NotLinked -> return reject(RelinkResult.Reason.NOT_LINKED)
            is WriteTarget.Unavailable -> return reject(RelinkResult.Reason.UNAVAILABLE)
        }
        if (id == current.playlistId) return reject(RelinkResult.Reason.SAME_PLAYLIST)
        val remote = try {
            get(id)
        } catch (e: SpotifyApiException) {
            return reject(if (e.kind == com.spotifyautoplaylist.spotify.SpotifyErrorKind.NOT_FOUND) RelinkResult.Reason.NOT_FOUND
                else RelinkResult.Reason.UNAVAILABLE)
        }
        val name = remote.name
        if (remote.id != id || remote.snapshotId.isNullOrBlank() || name.isNullOrBlank()) return reject(RelinkResult.Reason.RESPONSE)
        if (remote.owner?.id != key.spotifyUserId) return reject(RelinkResult.Reason.OWNER)
        if (remote.public != false || remote.collaborative != false) return reject(RelinkResult.Reason.PRIVACY)
        if (account() != key) return reject(RelinkResult.Reason.ACCOUNT_CHANGED)
        val moved = database.qualifications().relink(
            key.spotifyUserId, current.playlistId,
            current.copy(playlistId = id, playlistUrl = null, name = name, createdAtMs = null, ownershipVerified = true),
        )
        return RelinkResult.Relinked(moved)
    }

    private fun reject(reason: RelinkResult.Reason) = RelinkResult.Rejected(reason)
}
