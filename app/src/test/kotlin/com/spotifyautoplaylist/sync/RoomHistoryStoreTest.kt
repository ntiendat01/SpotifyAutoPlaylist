package com.spotifyautoplaylist.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.data.local.CoverageStatus
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.spotify.Cursors
import com.spotifyautoplaylist.spotify.PlayHistoryItem
import com.spotifyautoplaylist.spotify.RecentlyPlayedPage
import com.spotifyautoplaylist.spotify.TrackDto
import com.spotifyautoplaylist.spotify.sync.HistorySync
import com.spotifyautoplaylist.spotify.sync.RecentlyPlayedSource
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomHistoryStoreTest {
    private lateinit var db: ReplayDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private fun item(minute: Long) = PlayHistoryItem(Instant.ofEpochMilli(at(minute)).toString(),
        TrackDto(id = TRACK, type = "track", name = "Dummy", isLocal = false))

    private fun page(vararg minutes: Long, last: Boolean = false) = RecentlyPlayedPage(
        minutes.map(::item), next = if (last) null else "x", cursors = Cursors(before = at(minutes.min()).toString()))

    private fun run(user: String, vararg pages: Pair<Long?, RecentlyPlayedPage>) = runBlocking {
        val map = pages.toMap()
        HistorySync(RecentlyPlayedSource { map.getValue(it) }, RoomHistoryStore(db) { 1L }, { NOW }).sync(user)
    }

    @Test fun `tc010 tc011 pages persist once with watermark checkpoint in sync_state`() = runBlocking<Unit> {
        run(USER_A, null to page(30, 20), at(20) to page(10, last = true))
        run(USER_A, null to page(40, 30))
        run(USER_A, null to page(40, 30))

        assertEquals(4, db.history().allEvents(USER_A).size)
        val state = db.syncState().get(USER_A)!!
        assertEquals(CoverageStatus.COMPLETE_SINCE_BASELINE, state.coverageStatus)
        assertEquals(at(40), state.highWaterPlayedAtMs)
        assertNull(state.lastPageCursor)
        assertEquals("Dummy", db.history().track(USER_A, TRACK)!!.name)
    }

    @Test fun `tc026 gap is stored and accounts stay isolated`() = runBlocking<Unit> {
        run(USER_A, null to page(10, last = true))
        run(USER_A, null to page(50, last = true))
        val a = db.syncState().get(USER_A)!!
        assertEquals(CoverageStatus.HISTORY_INCOMPLETE, a.coverageStatus)
        assertEquals(at(10), a.highWaterPlayedAtMs)
        assertEquals(at(10), a.lastGapStartMs)
        assertEquals(at(50), a.lastGapEndMs)

        assertNull(db.syncState().get(USER_B))
        assertEquals(0, db.history().allEvents(USER_B).size)
        run(USER_B, null to page(5, last = true))
        assertEquals(CoverageStatus.COMPLETE_SINCE_BASELINE, db.syncState().get(USER_B)!!.coverageStatus)
        assertEquals(2, db.history().allEvents(USER_A).size)
        assertEquals(1, db.history().allEvents(USER_B).size)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-11-04T07:00:00Z")
        const val USER_A = "dummy-user-a"
        const val USER_B = "dummy-user-b"
        const val TRACK = "0000000000000000000001"
        fun at(minute: Long) = NOW.toEpochMilli() - 1_000_000_000L + minute * 60_000L
    }
}
