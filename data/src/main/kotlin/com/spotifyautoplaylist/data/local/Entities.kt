package com.spotifyautoplaylist.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema contract: docs/05-DATABASE.md. Every row is scoped by spotify_user_id and cascades from `account`,
// so one account's rows can be removed atomically. Enums are stored as their TEXT names.

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "connected_at_ms") val connectedAtMs: Long,
)

@Entity(
    tableName = "tracks",
    primaryKeys = ["spotify_user_id", "track_id"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["spotify_user_id"], ["spotify_user_id"], onDelete = ForeignKey.CASCADE)],
)
data class TrackEntity(
    @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val uri: String,
    val name: String,
    @ColumnInfo(name = "artist_names") val artistNames: String,
    @ColumnInfo(name = "album_name") val albumName: String?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
)

/** Key is an approximation: Spotify exposes no unique play-event ID (same track + same ms collapses). */
@Entity(
    tableName = "play_events",
    primaryKeys = ["spotify_user_id", "track_id", "played_at_ms"],
    foreignKeys = [ForeignKey(TrackEntity::class, ["spotify_user_id", "track_id"], ["spotify_user_id", "track_id"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index("spotify_user_id", "played_at_ms")],
)
data class PlayEventEntity(
    @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "played_at_ms") val playedAtMs: Long,
    @ColumnInfo(name = "ingested_at_ms") val ingestedAtMs: Long,
)

enum class QualifiedBy { DAILY, ROLLING, BOTH }

/** Monotonic: never deleted when thresholds change. */
@Entity(
    tableName = "qualification",
    primaryKeys = ["spotify_user_id", "track_id"],
    foreignKeys = [ForeignKey(TrackEntity::class, ["spotify_user_id", "track_id"], ["spotify_user_id", "track_id"],
        onDelete = ForeignKey.CASCADE)],
)
data class QualificationEntity(
    @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "qualified_at_ms") val qualifiedAtMs: Long,
    @ColumnInfo(name = "qualified_by") val qualifiedBy: QualifiedBy,
)

@Entity(
    tableName = "managed_playlist",
    foreignKeys = [ForeignKey(AccountEntity::class, ["spotify_user_id"], ["spotify_user_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlist_id", unique = true)],
)
data class ManagedPlaylistEntity(
    @PrimaryKey @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "playlist_url") val playlistUrl: String?,
    val name: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long?,
    @ColumnInfo(name = "ownership_verified", defaultValue = "0") val ownershipVerified: Boolean = false,
)

enum class OutboxState { PENDING, CHECKING_REMOTE, SENDING, VERIFY_REMOTE, RETRY_LATER, PRESENT, REMOVED_EXTERNALLY, BLOCKED }

@Entity(
    tableName = "playlist_outbox",
    primaryKeys = ["spotify_user_id", "playlist_id", "track_id"],
    foreignKeys = [ForeignKey(TrackEntity::class, ["spotify_user_id", "track_id"], ["spotify_user_id", "track_id"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index("spotify_user_id", "track_id"), Index("spotify_user_id", "state")],
)
data class PlaylistOutboxEntity(
    @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val state: OutboxState,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_attempt_ms") val lastAttemptMs: Long? = null,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long? = null,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
)

enum class CoverageStatus { UNKNOWN, COMPLETE_SINCE_BASELINE, PARTIAL_BOOTSTRAP, HISTORY_INCOMPLETE }

/** `last_page_cursor` is resumable page progress, not proof of complete history. */
@Entity(
    tableName = "sync_state",
    foreignKeys = [ForeignKey(AccountEntity::class, ["spotify_user_id"], ["spotify_user_id"], onDelete = ForeignKey.CASCADE)],
)
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "spotify_user_id") val spotifyUserId: String,
    @ColumnInfo(name = "last_success_at_ms") val lastSuccessAtMs: Long? = null,
    @ColumnInfo(name = "high_water_played_at_ms") val highWaterPlayedAtMs: Long? = null,
    @ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long? = null,
    @ColumnInfo(name = "coverage_status") val coverageStatus: CoverageStatus = CoverageStatus.UNKNOWN,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "last_page_cursor") val lastPageCursor: String? = null,
    @ColumnInfo(name = "last_gap_start_ms") val lastGapStartMs: Long? = null,
    @ColumnInfo(name = "last_gap_end_ms") val lastGapEndMs: Long? = null,
)
