package com.spotifyautoplaylist.spotify.playlist

import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.spotify.SnapshotResponse
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyErrorKind

/**
 * TASK-042 classification of one API-04 add-items POST (04 §6/§8, BR-008). The client has already done its single
 * 401 refresh-retry; nothing here retries.
 */
sealed interface AddItemsOutcome {
    /** 2xx with a snapshot_id: confirmed success. */
    data class Added(val snapshotId: String) : AddItemsOutcome
    /** Timeout, I/O, 5xx, unreadable success or unexpected status: may have been applied; verify remotely, never re-POST. */
    data class Ambiguous(val kind: SpotifyErrorKind) : AddItemsOutcome
    /** 429 (rate limit or quota): request rejected before applying; honour [retryAfterSeconds]. */
    data class Throttled(val kind: SpotifyErrorKind, val retryAfterSeconds: Long?) : AddItemsOutcome
    /** Definite rejection (400/401 after refresh/403/404): nothing applied. */
    data class Rejected(val kind: SpotifyErrorKind) : AddItemsOutcome
}

suspend fun addItemsOutcome(post: suspend () -> SnapshotResponse): AddItemsOutcome = try {
    AddItemsOutcome.Added(post().snapshotId)
} catch (e: SpotifyApiException) {
    when {
        e.outcomeUnknown -> AddItemsOutcome.Ambiguous(e.kind)
        e.kind == SpotifyErrorKind.RATE_LIMITED || e.kind == SpotifyErrorKind.QUOTA_EXCEEDED ->
            AddItemsOutcome.Throttled(e.kind, e.retryAfterSeconds)
        else -> AddItemsOutcome.Rejected(e.kind)
    }
} catch (_: AuthException) {
    // Raised by the token provider when the refreshed token is rejected too: the POST got 401 twice.
    AddItemsOutcome.Rejected(SpotifyErrorKind.UNAUTHORIZED)
}
