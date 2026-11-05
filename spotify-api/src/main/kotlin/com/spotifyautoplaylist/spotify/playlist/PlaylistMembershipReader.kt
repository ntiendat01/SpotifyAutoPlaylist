package com.spotifyautoplaylist.spotify.playlist

import com.spotifyautoplaylist.auth.SpotifyResourceId
import com.spotifyautoplaylist.spotify.PlaylistItemsPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import java.time.Instant

/** Result of reading every item of one playlist (API-03). Only [Complete] may be used to decide membership. */
sealed interface RemoteMembership {
    /**
     * All pages were read consistently. [addedAtMs] maps each Spotify track ID present (incl. `linked_from`
     * originals) to its earliest parsable `added_at` (null if none). Local files, episodes and entries without
     * a valid ID are counted in [ignoredItems] and never match a track.
     */
    class Complete(val addedAtMs: Map<String, Long?>, val totalItems: Int, val ignoredItems: Int) : RemoteMembership {
        fun contains(trackId: String) = trackId in addedAtMs
        override fun toString() = "Complete(tracks=${addedAtMs.size}, total=$totalItems, ignored=$ignoredItems)"
    }

    /** Reading stopped early; nothing may be concluded about presence or absence. */
    data class Incomplete(val reason: Reason, val error: SpotifyErrorKind? = null, val retryAfterSeconds: Long? = null) : RemoteMembership

    enum class Reason { API_ERROR, INCONSISTENT_PAGINATION, PAGE_CAP }
}

/**
 * TASK-041: paginates `GET /playlists/{id}/items` by offset (never follows server URLs; `next` is only a
 * continuation signal). Bounded by [pageCap]; fails closed on repeated/out-of-order offsets, a changing
 * `total`, empty non-final pages or `next` disagreeing with `total`.
 */
class PlaylistMembershipReader(
    private val fetch: suspend (offset: Int) -> PlaylistItemsPage,
    private val pageCap: Int = DEFAULT_PAGE_CAP,
) {
    suspend fun read(): RemoteMembership {
        val found = LinkedHashMap<String, Long?>()
        var ignored = 0
        var offset = 0
        var total: Int? = null
        repeat(pageCap) {
            val page = try {
                fetch(offset)
            } catch (e: SpotifyApiException) {
                return RemoteMembership.Incomplete(RemoteMembership.Reason.API_ERROR, e.kind, e.retryAfterSeconds)
            }
            if (page.offset != offset || page.total !in 0..MAX_TOTAL || (total != null && total != page.total))
                return inconsistent()
            total = page.total
            for (row in page.items) {
                val entry = row.item
                val ids = listOfNotNull(entry?.id, entry?.linkedFrom?.id)
                val usable = entry != null && row.isLocal != true && entry.isLocal != true && entry.type == "track" &&
                    ids.isNotEmpty() && ids.all(SpotifyResourceId::isId)
                if (!usable) { ignored++; continue }
                val at = row.addedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                for (id in ids) found[id] = listOfNotNull(found[id], at).minOrNull()
            }
            offset += page.items.size
            val last = offset >= page.total
            if (offset > page.total || (last != (page.next == null)) || (!last && page.items.isEmpty()))
                return inconsistent()
            if (last) return RemoteMembership.Complete(found, page.total, ignored)
        }
        return RemoteMembership.Incomplete(RemoteMembership.Reason.PAGE_CAP)
    }

    private fun inconsistent() = RemoteMembership.Incomplete(RemoteMembership.Reason.INCONSISTENT_PAGINATION)

    companion object {
        /** 50 items/page; Spotify playlists hold at most 10,000 items. */
        const val DEFAULT_PAGE_CAP = 201
        const val MAX_TOTAL = 10_000
    }
}
