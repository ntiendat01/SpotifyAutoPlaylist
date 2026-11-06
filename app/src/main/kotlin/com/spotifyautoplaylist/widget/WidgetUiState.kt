package com.spotifyautoplaylist.widget

import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.library.LibraryModel
import com.spotifyautoplaylist.library.SyncCopy

/**
 * Pure mapping from persisted Room/DataStore rows → widget copy (TASK-060).
 * Copy follows the refreshed in-app wording (PlaylistHero "bài đã xác minh trên Spotify",
 * "Mở trong Spotify", [SyncCopy] status lines). Never includes tokens, client IDs or raw user IDs.
 */
enum class WidgetPhase {
    /** First paint / read in progress (initialLayout) or a failed read. */
    LOADING,
    /** Build has no Spotify Client ID (CI / sideload without spotify.local.properties). */
    NOT_CONFIGURED,
    /** No local account row — signed out or never connected. */
    LOGGED_OUT,
    /** Signed in locally but no managed playlist yet. */
    NO_PLAYLIST,
    /** Playlist linked; zero PRESENT rows (may never have synced). */
    EMPTY_PLAYLIST,
    /** Normal ready state with a count > 0. */
    READY,
    /** Last pass needs the user (auth / relink / forbidden / mismatch / quota…). */
    ERROR,
    /** Last pass was NETWORK_ERROR — cached numbers stay visible. */
    OFFLINE,
}

data class WidgetUiState(
    val phase: WidgetPhase,
    /** Hero metric: "12", "0", "—" or "…" while loading. */
    val countValue: String,
    /** Text next to the metric, e.g. "bài đã xác minh trên Spotify" or a next-step hint. */
    val countCaption: String,
    /** Large tier: "Đồng bộ thành công gần nhất: 14:30". */
    val lastSyncLine: String,
    /** Medium header: "Thành công 14:30" or empty. */
    val lastSyncShort: String,
    /** Current status headline (reuses [SyncCopy] wording). */
    val statusLine: String,
    /** Playlist name row (large tier). */
    val playlistLine: String,
    /** HTTPS open.spotify.com/playlist/<id> or null when none / invalid. */
    val playlistUrl: String?,
    /** spotify:playlist:<id> when id is a valid 22-char resource; null otherwise. */
    val playlistUri: String?,
    val statusIsProblem: Boolean,
) {
    val canOpenPlaylist: Boolean get() = playlistUrl != null || playlistUri != null

    /** Accessible summary for the whole card. */
    val contentDescription: String
        get() = listOf("replay.", "$countValue $countCaption", statusLine, lastSyncLine)
            .filter { it.isNotBlank() }.joinToString(". ")

    companion object {
        const val VERIFIED_CAPTION = "bài đã xác minh trên Spotify"

        val Loading = WidgetUiState(
            phase = WidgetPhase.LOADING, countValue = "…", countCaption = "Đang đọc dữ liệu trên máy",
            lastSyncLine = "", lastSyncShort = "", statusLine = "Đang tải…", playlistLine = "Playlist của bạn",
            playlistUrl = null, playlistUri = null, statusIsProblem = false,
        )
    }
}

data class WidgetPersistedInput(
    val spotifyConfigured: Boolean,
    val hasAccount: Boolean,
    val playlistId: String?,
    val playlistName: String?,
    val playlistUrl: String?,
    /** Remotely confirmed PRESENT count; null = unknown (no playlist). */
    val addedCount: Int?,
    val status: SyncRunStatus,
    val nowMs: Long,
)

object WidgetUiMapper {
    private val PLAYLIST_ID = Regex("[A-Za-z0-9]{22}")

    fun map(input: WidgetPersistedInput): WidgetUiState {
        if (!input.spotifyConfigured) return blocked(
            WidgetPhase.NOT_CONFIGURED, "Bản cài này chưa cấu hình đăng nhập Spotify", "Chưa cấu hình Spotify")
        if (!input.hasAccount) return blocked(
            WidgetPhase.LOGGED_OUT, "Mở app để kết nối Spotify", "Chưa kết nối Spotify")

        val lastSuccess = input.status.lastSuccessAtMs?.let { LibraryModel.time(it, input.nowMs) }
        val lastSyncLine = lastSuccess?.let { "Đồng bộ thành công gần nhất: $it" } ?: "Chưa có lần đồng bộ thành công"
        val lastSyncShort = lastSuccess?.let { "Thành công $it" }.orEmpty()

        val id = input.playlistId?.takeIf { PLAYLIST_ID.matches(it) }
        if (id == null) return WidgetUiState(
            phase = WidgetPhase.NO_PLAYLIST, countValue = "—", countCaption = "Mở app để thiết lập playlist",
            lastSyncLine = lastSyncLine, lastSyncShort = lastSyncShort, statusLine = "Chưa liên kết playlist",
            playlistLine = "Chưa liên kết playlist", playlistUrl = null, playlistUri = null, statusIsProblem = true,
        )

        val https = input.playlistUrl?.takeIf { it.startsWith("https://open.spotify.com/playlist/") }
            ?: "https://open.spotify.com/playlist/$id"
        val count = input.addedCount ?: 0
        val verified = count > 0 || input.status.lastSuccessAtMs != null
        val problem = SyncCopy.isProblem(input.status)
        val phase = when {
            input.status.outcome == "NETWORK_ERROR" -> WidgetPhase.OFFLINE
            problem -> WidgetPhase.ERROR
            count == 0 -> WidgetPhase.EMPTY_PLAYLIST
            else -> WidgetPhase.READY
        }
        return WidgetUiState(
            phase = phase,
            // Same rule as LibraryModel.confirmedCount: 0 with no successful pass is "unverified", not zero.
            countValue = if (verified) count.toString() else "—",
            countCaption = if (verified) WidgetUiState.VERIFIED_CAPTION else "Chưa xác minh số bài trên Spotify",
            lastSyncLine = lastSyncLine,
            lastSyncShort = lastSyncShort,
            statusLine = SyncCopy.headline(input.status, input.nowMs),
            playlistLine = input.playlistName?.takeIf { it.isNotBlank() } ?: "Playlist của bạn",
            playlistUrl = https,
            playlistUri = "spotify:playlist:$id",
            statusIsProblem = problem || phase == WidgetPhase.OFFLINE,
        )
    }

    private fun blocked(phase: WidgetPhase, caption: String, status: String) = WidgetUiState(
        phase = phase, countValue = "—", countCaption = caption, lastSyncLine = "", lastSyncShort = "",
        statusLine = status, playlistLine = "Chưa có playlist", playlistUrl = null, playlistUri = null,
        statusIsProblem = true,
    )
}
