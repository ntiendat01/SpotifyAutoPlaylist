package com.spotifyautoplaylist.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.CoverageStatus
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.rules.QualificationRecorder
import com.spotifyautoplaylist.spotify.Cursors
import com.spotifyautoplaylist.spotify.PlayHistoryItem
import com.spotifyautoplaylist.spotify.RecentlyPlayedPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyEndpoint
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.TrackDto
import com.spotifyautoplaylist.spotify.sync.HistorySync
import com.spotifyautoplaylist.spotify.sync.RecentlyPlayedSource
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TASK-033 integration across HistorySync -> RoomHistoryStore (in-memory Room) -> QualificationRecorder.
 * The HTTP layer is covered by spotify-api M3PipelineTest (MockWebServer is not on :app's test classpath).
 */
@RunWith(RobolectricTestRunner::class)
class M3RoomIntegrationTest {
    private lateinit var db: ReplayDatabase
    private lateinit var dir: File
    private lateinit var settings: SettingsRepository
    private lateinit var recorder: QualificationRecorder

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
        dir = Files.createTempDirectory("settings").toFile()
        settings = SettingsRepository.create(File(dir, "s.preferences_pb"))
        recorder = QualificationRecorder(db, settings)
    }

    @After fun close() { db.close(); dir.deleteRecursively() }

    private fun item(t: Long, track: String) = PlayHistoryItem(Instant.ofEpochMilli(t).toString(), TrackDto(id = track, type = "track", name = "Dummy", isLocal = false))

    /** Items newest first; `next` null ends history. */
    private fun page(vararg plays: Pair<Long, String>, last: Boolean = false) = RecentlyPlayedPage(
        plays.sortedByDescending { it.first }.map { item(it.first, it.second) }, next = if (last) null else "x",
        cursors = Cursors(before = plays.minOf { it.first }.toString()))

    private class Source(vararg pages: Pair<Long?, Any>) : RecentlyPlayedSource {
        private val map = pages.toMap()
        override suspend fun page(before: Long?): RecentlyPlayedPage = when (val v = map.getValue(before)) {
            is RecentlyPlayedPage -> v
            else -> throw v as SpotifyApiException
        }
    }

    private fun sync(user: String, vararg pages: Pair<Long?, Any>) = runBlocking {
        HistorySync(Source(*pages), RoomHistoryStore(db) { 1L }, { Instant.ofEpochMilli(NOW) }).sync(user)
    }

    private fun state(user: String) = runBlocking { db.syncState().get(user)!! }
    private fun events(user: String) = runBlocking { db.history().allEvents(user).size }
    private fun quals(user: String) = runBlocking { db.qualifications().all(user) }
    private fun outbox(user: String) = runBlocking { db.playlists().outboxIn(user, OutboxState.entries) }
    private fun link(user: String) = runBlocking { db.playlists().insertManaged(ManagedPlaylistEntity(user, "pl-$user", null, "My Replays", 1)) }
    private fun evaluate(user: String) = runBlocking { recorder.evaluate(AccountKey(user, HASH)) }

    private val d = vn("2026-11-04T09:00")
    private val h = 3_600_000L

    @Test fun `exit criterion - repeated sync and evaluation keep counts, rows and coverage stable`() {
        val newest = page(d + 3 * h to T1, d + 2 * h to T1, d + h to T1)
        sync(A, null to newest, (d + h) to page(d to T2, d - h to T2, last = true))
        link(A)
        assertEquals(listOf(T1), evaluate(A).newlyQualified)
        assertEquals(CoverageStatus.COMPLETE_SINCE_BASELINE, state(A).coverageStatus)
        val snapshot = Triple(events(A), quals(A), outbox(A))
        repeat(3) {
            sync(A, null to newest)
            assertTrue(evaluate(A).newlyQualified.isEmpty())
            assertEquals(snapshot, Triple(events(A), quals(A), outbox(A)))
            assertEquals(CoverageStatus.COMPLETE_SINCE_BASELINE, state(A).coverageStatus)
            assertEquals(d + 3 * h, state(A).highWaterPlayedAtMs)
        }
        assertEquals(5, events(A))
        assertEquals(OutboxState.PENDING, outbox(A).single().state)
    }

    @Test fun `page cap baseline is PARTIAL_BOOTSTRAP and empty account is UNKNOWN`() {
        sync(B, null to RecentlyPlayedPage(emptyList()))
        assertEquals(CoverageStatus.UNKNOWN, state(B).coverageStatus)
        val r = runBlocking {
            HistorySync(Source(null to page(d to T1)), RoomHistoryStore(db) { 1L }, { Instant.ofEpochMilli(NOW) }, pageCap = 1).sync(A)
        }
        assertEquals(CoverageStatus.PARTIAL_BOOTSTRAP, state(A).coverageStatus)
        assertEquals(1, r.inserted)
    }

    @Test fun `tc026 gap then recovery is honest in sync_state and qualifications use only real events`() {
        sync(A, null to page(d to T1, d - h to T1, last = true))
        sync(A, null to page(d + 50 * h to T2, last = true)) // cannot reach the old high-water
        val gap = state(A)
        assertEquals(CoverageStatus.HISTORY_INCOMPLETE, gap.coverageStatus)
        assertEquals(d, gap.highWaterPlayedAtMs)
        assertEquals(d to d + 50 * h, gap.lastGapStartMs to gap.lastGapEndMs)
        assertTrue(evaluate(A).newlyQualified.isEmpty()) // 2 T1 plays: no invented third
        sync(A, null to page(d + 51 * h to T1, d + 50 * h to T2), (d + 50 * h) to page(d + 2 * h to T1, d to T1))
        val ok = state(A)
        assertEquals(CoverageStatus.PARTIAL_BOOTSTRAP, ok.coverageStatus)
        assertEquals(d + 51 * h, ok.highWaterPlayedAtMs)
        assertNull(ok.lastGapStartMs)
        assertEquals(5, events(A))
        // Recovered event d+2h makes three T1 plays on the same VN day.
        assertEquals(listOf(T1), evaluate(A).newlyQualified)
        assertEquals(d + 2 * h, quals(A).single().qualifiedAtMs)
    }

    @Test fun `rate limit mid-run persists first page, keeps high-water, then retry bridges`() {
        sync(A, null to page(d to T1, last = true))
        val limited = SpotifyApiException(SpotifyEndpoint.RECENTLY_PLAYED, SpotifyErrorKind.RATE_LIMITED, 429, 7)
        assertThrows(SpotifyApiException::class.java) {
            sync(A, null to page(d + 3 * h to T1, d + 2 * h to T1), (d + 2 * h) to limited)
        }
        assertEquals(3, events(A))
        assertEquals(d, state(A).highWaterPlayedAtMs)
        assertEquals(CoverageStatus.HISTORY_INCOMPLETE, state(A).coverageStatus)
        assertEquals("RATE_LIMITED", state(A).lastErrorCode)
        sync(A, null to page(d + 3 * h to T1, d + 2 * h to T1), (d + 2 * h) to page(d + h to T1, d to T1))
        assertEquals(4, events(A))
        assertEquals(d + 3 * h, state(A).highWaterPlayedAtMs)
        assertNull(state(A).lastErrorCode)
        assertEquals(CoverageStatus.PARTIAL_BOOTSTRAP, state(A).coverageStatus)
    }

    @Test fun `accounts are isolated across sync, qualification and outbox`() {
        sync(A, null to page(d + 2 * h to T1, d + h to T1, d to T1, last = true))
        sync(B, null to page(d + h to T1, d to T1, last = true))
        link(A); link(B)
        evaluate(A); evaluate(B)
        assertEquals(3 to 2, events(A) to events(B))
        assertEquals(listOf(T1), quals(A).map { it.trackId })
        assertTrue(quals(B).isEmpty() && outbox(B).isEmpty())
        assertEquals(listOf("pl-$A"), outbox(A).map { it.playlistId })
        assertEquals(d + 2 * h, state(A).highWaterPlayedAtMs)
        assertEquals(d + h, state(B).highWaterPlayedAtMs)
    }

    @Test fun `tc004 tc009 threshold changes over synced history - daily 3 to 2, rolling stays 5 per 72h`() {
        // T1: 5 plays over ~70h, max 2 per VN day -> rolling. T2: 2 plays one day -> only with daily 2.
        sync(A, null to page(
            d + 70 * h to T1, d + 60 * h to T1, d + 50 * h to T1, d + 30 * h to T1, d to T1,
            d + 2 * h to T2, d + h to T2, last = true))
        link(A)
        val key = AccountKey(A, HASH)
        assertEquals(listOf(T1), evaluate(A).newlyQualified)
        assertEquals(QualifiedBy.ROLLING, quals(A).single().qualifiedBy)
        assertEquals(d + 70 * h, quals(A).single().qualifiedAtMs)
        val lowered = runBlocking { recorder.changeThresholds(key, daily = 2, rolling = 5) }
        assertEquals(listOf(T2), lowered.newlyQualified)
        val raised = runBlocking { recorder.changeThresholds(key, daily = 20, rolling = 30) }
        assertTrue(raised.newlyQualified.isEmpty())
        assertEquals(setOf(T1, T2), quals(A).map { it.trackId }.toSet())
        assertEquals(setOf(T1, T2), outbox(A).map { it.trackId }.toSet())
        assertEquals(QualifiedBy.ROLLING, quals(A).first { it.trackId == T1 }.qualifiedBy)
    }

    companion object {
        const val A = "user-a"
        const val B = "user-b"
        const val T1 = "0000000000000000000001"
        const val T2 = "0000000000000000000002"
        val HASH = "a".repeat(64)
        fun vn(t: String) = LocalDateTime.parse(t).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli()
        val NOW = vn("2026-11-08T00:00")
    }
}
