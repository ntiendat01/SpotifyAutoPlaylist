package com.spotifyautoplaylist.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes only. Fields Spotify may omit or has removed (February 2026 changes) are nullable with
// defaults; fields whose absence means the response is unusable have no default, so decoding fails.
// Callers still validate IDs, types and pagination before persisting anything.

/** API-01 `GET /me/player/recently-played`. */
@Serializable
data class RecentlyPlayedPage(
    val items: List<PlayHistoryItem>,
    val next: String? = null,
    val cursors: Cursors? = null,
    val limit: Int? = null,
)

@Serializable
data class PlayHistoryItem(
    @SerialName("played_at") val playedAt: String,
    val track: TrackDto? = null,
)

@Serializable
data class Cursors(val after: String? = null, val before: String? = null)

@Serializable
data class TrackDto(
    val id: String? = null,
    val uri: String? = null,
    val type: String? = null,
    val name: String? = null,
    val artists: List<ArtistDto> = emptyList(),
    val album: AlbumDto? = null,
    @SerialName("is_local") val isLocal: Boolean? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
data class ArtistDto(val id: String? = null, val name: String? = null)

@Serializable
data class AlbumDto(val name: String? = null, val images: List<ImageDto> = emptyList())

@Serializable
data class ImageDto(val url: String, val width: Int? = null, val height: Int? = null)

/** API-03 `GET /playlists/{id}/items`; the entry is in `item` (the old `track` field is not read). */
@Serializable
data class PlaylistItemsPage(
    val items: List<PlaylistItemDto>,
    val total: Int,
    val offset: Int,
    val next: String? = null,
    val limit: Int? = null,
)

@Serializable
data class PlaylistItemDto(
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("is_local") val isLocal: Boolean? = null,
    val item: PlaylistEntryDto? = null,
)

@Serializable
data class PlaylistEntryDto(
    val id: String? = null,
    val type: String? = null,
    val uri: String? = null,
    @SerialName("is_local") val isLocal: Boolean? = null,
    @SerialName("linked_from") val linkedFrom: LinkedFromDto? = null,
)

@Serializable
data class LinkedFromDto(val id: String? = null, val uri: String? = null)

/** API-02 response, API-05 rows and API-07 `GET /playlists/{id}`. */
@Serializable
data class PlaylistDto(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val owner: OwnerDto? = null,
    val public: Boolean? = null,
    val collaborative: Boolean? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    @SerialName("external_urls") val externalUrls: ExternalUrlsDto? = null,
)

@Serializable
data class OwnerDto(val id: String? = null)

@Serializable
data class ExternalUrlsDto(val spotify: String? = null)

/** API-05 `GET /me/playlists`. */
@Serializable
data class PlaylistsPage(
    val items: List<PlaylistDto>,
    val total: Int,
    val offset: Int,
    val next: String? = null,
    val limit: Int? = null,
)

/** API-02 request body; v1 playlists are always private and not collaborative. */
@Serializable
data class CreatePlaylistRequest(
    val name: String,
    val description: String,
    val public: Boolean = false,
    val collaborative: Boolean = false,
)

/** API-04 request body. */
@Serializable
data class AddItemsRequest(val uris: List<String>)

/** API-04 response. */
@Serializable
data class SnapshotResponse(@SerialName("snapshot_id") val snapshotId: String)
