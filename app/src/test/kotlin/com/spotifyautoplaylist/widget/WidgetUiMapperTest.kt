package com.spotifyautoplaylist.widget

import com.spotifyautoplaylist.data.sync.SyncRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUiMapperTest {
    private val now = 1_700_000_000_000L
    private val playlistId = "abcdefghijklmnopqrstuv" // 22 chars

    private fun input(
        configured: Boolean = true,
        hasAccount: Boolean = true,
        playlistId: String? = this.playlistId,
        playlistName: String? = "My Replays",
        playlistUrl: String? = null,
        added: Int? = 3,
        status: SyncRunStatus = SyncRunStatus(outcome = "SUCCESS", finishedAtMs = now, lastSuccessAtMs = now),
    ) = WidgetPersistedInput(configured, hasAccount, playlistId, playlistName, playlistUrl, added, status, now)

    @Test fun `not configured`() {
        val s = WidgetUiMapper.map(input(configured = false, hasAccount = false, playlistId = null, added = null))
        assertEquals(WidgetPhase.NOT_CONFIGURED, s.phase)
        assertEquals("—", s.countValue)
        assertFalse(s.canOpenPlaylist)
        assertTrue(s.statusIsProblem)
    }

    @Test fun `logged out points to the app`() {
        val s = WidgetUiMapper.map(input(hasAccount = false, playlistId = null, added = null, status = SyncRunStatus()))
        assertEquals(WidgetPhase.LOGGED_OUT, s.phase)
        assertEquals("Chưa kết nối Spotify", s.statusLine)
        assertEquals("Mở app để kết nối Spotify", s.countCaption)
    }

    @Test fun `no playlist yet`() {
        val s = WidgetUiMapper.map(input(playlistId = null, added = null))
        assertEquals(WidgetPhase.NO_PLAYLIST, s.phase)
        assertEquals("Chưa liên kết playlist", s.playlistLine)
        assertFalse(s.canOpenPlaylist)
    }

    @Test fun `empty verified playlist shows zero with app hero caption`() {
        val s = WidgetUiMapper.map(input(added = 0))
        assertEquals(WidgetPhase.EMPTY_PLAYLIST, s.phase)
        assertEquals("0", s.countValue)
        assertEquals("bài đã xác minh trên Spotify", s.countCaption)
        assertEquals("spotify:playlist:$playlistId", s.playlistUri)
        assertEquals("https://open.spotify.com/playlist/$playlistId", s.playlistUrl)
    }

    @Test fun `never synced playlist is unverified not zero, like PlaylistHero`() {
        val s = WidgetUiMapper.map(input(added = 0, status = SyncRunStatus()))
        assertEquals(WidgetPhase.EMPTY_PLAYLIST, s.phase)
        assertEquals("—", s.countValue)
        assertEquals("Chưa xác minh số bài trên Spotify", s.countCaption)
        assertEquals("Chưa đồng bộ lần nào", s.statusLine)
        assertEquals("Chưa có lần đồng bộ thành công", s.lastSyncLine)
        assertEquals("", s.lastSyncShort)
        assertTrue(s.canOpenPlaylist)
    }

    @Test fun `ready with count, playlist name and last success`() {
        val s = WidgetUiMapper.map(input(added = 12))
        assertEquals(WidgetPhase.READY, s.phase)
        assertEquals("12", s.countValue)
        assertEquals("My Replays", s.playlistLine)
        assertTrue(s.lastSyncLine.startsWith("Đồng bộ thành công gần nhất: "))
        assertTrue(s.lastSyncShort.startsWith("Thành công "))
        assertFalse(s.statusIsProblem)
        assertTrue(s.contentDescription.contains("12 bài đã xác minh trên Spotify"))
    }

    @Test fun `blank playlist name falls back to app wording`() {
        assertEquals("Playlist của bạn", WidgetUiMapper.map(input(playlistName = " ")).playlistLine)
    }

    @Test fun `stored https url is preferred, foreign urls are ignored`() {
        val own = "https://open.spotify.com/playlist/$playlistId?si=x"
        assertEquals(own, WidgetUiMapper.map(input(playlistUrl = own)).playlistUrl)
        assertEquals("https://open.spotify.com/playlist/$playlistId",
            WidgetUiMapper.map(input(playlistUrl = "https://evil.example/x")).playlistUrl)
    }

    @Test fun `offline keeps cached count`() {
        val s = WidgetUiMapper.map(input(added = 5, status = SyncRunStatus(outcome = "NETWORK_ERROR", lastSuccessAtMs = now - 60_000)))
        assertEquals(WidgetPhase.OFFLINE, s.phase)
        assertEquals("5", s.countValue)
        assertEquals("Không có mạng · Hiển thị dữ liệu trên máy", s.statusLine)
        assertTrue(s.statusIsProblem)
    }

    @Test fun `error relink`() {
        val s = WidgetUiMapper.map(input(status = SyncRunStatus(outcome = "RELINK_REQUIRED", errorCode = "RELINK_DELETED")))
        assertEquals(WidgetPhase.ERROR, s.phase)
        assertEquals("Playlist cần liên kết lại", s.statusLine)
        assertTrue(s.statusIsProblem)
    }

    @Test fun `deferred and disabled are not problems`() {
        assertFalse(WidgetUiMapper.map(input(status = SyncRunStatus(outcome = "DEFERRED", lastSuccessAtMs = now))).statusIsProblem)
        assertFalse(WidgetUiMapper.map(input(status = SyncRunStatus(outcome = "DISABLED", lastSuccessAtMs = now))).statusIsProblem)
    }

    @Test fun `invalid playlist id is treated as no playlist`() {
        val s = WidgetUiMapper.map(input(playlistId = "short", added = 1))
        assertEquals(WidgetPhase.NO_PLAYLIST, s.phase)
        assertNull(s.playlistUri)
    }

    @Test fun `loading placeholder`() {
        assertEquals(WidgetPhase.LOADING, WidgetUiState.Loading.phase)
        assertFalse(WidgetUiState.Loading.canOpenPlaylist)
    }

    @Test fun `never embeds raw outcome codes or ids the user should not see`() {
        val s = WidgetUiMapper.map(input(status = SyncRunStatus(outcome = "WEIRD_INTERNAL", errorCode = "SECRET")))
        val all = listOf(s.statusLine, s.countCaption, s.lastSyncLine, s.playlistLine, s.contentDescription).joinToString()
        assertFalse(all.contains("WEIRD"))
        assertFalse(all.contains("SECRET"))
        assertFalse(all.contains(playlistId))
    }
}
