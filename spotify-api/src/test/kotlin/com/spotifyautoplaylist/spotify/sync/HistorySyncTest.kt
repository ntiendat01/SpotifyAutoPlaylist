package com.spotifyautoplaylist.spotify.sync

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.BearerTokenProvider
import com.spotifyautoplaylist.spotify.Cursors
import com.spotifyautoplaylist.spotify.PlayHistoryItem
import com.spotifyautoplaylist.spotify.RecentlyPlayedPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.TrackDto
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySyncTest {
    private val store = FakeStore()
    private val server = MockWebServer()

    @After fun stop() = server.shutdown()

    // ---- fakes --------------------------------------------------------------------------------------

    class FakeStore : HistoryStore {
        val events = linkedSetOf<Triple<String, String, Long>>()
        var state: SyncCheckpoint? = null
        val progress = mutableListOf<SyncCheckpoint>()
        var commits = 0
        override suspend fun checkpoint(userId: String) = state
        override suspend fun importPage(userId: String, plays: List<ValidPlay>, progress: SyncCheckpoint, ingestedAtMs: Long): Int {
            this.progress += progress
            state = progress
            return plays.count { events.add(Triple(userId, it.trackId, it.playedAtMs)) }
        }
        override suspend fun commit(userId: String, checkpoint: SyncCheckpoint) { state = checkpoint; commits++ }
    }

    /** Serves pages keyed by the `before` value requested. */
    private class Pages(vararg pages: Pair<Long?, RecentlyPlayedPage>) : RecentlyPlayedSource {
        val map = pages.toMap()
        val requests = mutableListOf<Long?>()
        var failOn: Long? = -1
        override suspend fun page(before: Long?): RecentlyPlayedPage {
            requests += before
            if (before == failOn) throw SpotifyApiException(com.spotifyautoplaylist.spotify.SpotifyEndpoint.RECENTLY_PLAYED, SpotifyErrorKind.SERVER, 500)
            return map.getValue(before)
        }
    }

    private fun item(minute: Long, track: String = T1, type: String = "track", local: Boolean? = false, id: String? = track) =
        PlayHistoryItem(Instant.ofEpochMilli(at(minute)).toString(), TrackDto(id = id, type = type, name = "n", isLocal = local))

    /** Items newest first; cursor = oldest item, next present unless [last]. */
    private fun page(vararg minutes: Long, last: Boolean = false) = RecentlyPlayedPage(
        minutes.sortedDescending().map { item(it) },
        next = if (last) null else "ignored",
        cursors = Cursors(before = minutes.minOrNull()?.let { at(it).toString() }),
    )

    private fun sync(source: RecentlyPlayedSource, cap: Int = 20) =
        runBlocking { HistorySync(source, store, { NOW }, cap).sync(USER) }

    // ---- tests --------------------------------------------------------------------------------------

    @Test
    fun firstSyncWalksBackwardsAndEstablishesCompleteBaseline() {
        val source = Pages(null to page(30, 20), at(20) to page(10, 5), at(5) to page(1, last = true))
        val result = sync(source)

        assertEquals(listOf(null, at(20), at(5)), source.requests)
        assertEquals(StopReason.END_OF_HISTORY, result.stop)
        assertEquals(5, result.inserted)
        assertEquals(Coverage.COMPLETE_SINCE_BASELINE, store.state!!.coverage)
        assertEquals(at(30), store.state!!.highWaterPlayedAtMs)
        assertEquals(NOW.toEpochMilli(), store.state!!.lastSuccessAtMs)
        assertNull(store.state!!.lastPageCursor)
    }

    @Test
    fun checkpointHighWaterOnlyMovesInFinalCommit() {
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(10), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        sync(Pages(null to page(40, 30), at(30) to page(20, 10)))
        assertTrue(store.progress.all { it.highWaterPlayedAtMs == at(10) })
        assertEquals(listOf(at(30).toString(), at(10).toString()), store.progress.map { it.lastPageCursor })
        assertEquals(at(40), store.state!!.highWaterPlayedAtMs)
        assertEquals(1, store.commits)
    }

    @Test
    fun tc010_tc011_overlappingWatermarkImportsEachEventOnce() {
        sync(Pages(null to page(30, 20), at(20) to page(10, last = true)))
        assertEquals(3, store.events.size)
        // Newest page overlaps the high-water (30) exactly: equality boundary included, nothing duplicated.
        val second = Pages(null to page(50, 40, 30))
        val result = sync(second)
        assertEquals(StopReason.BRIDGED, result.stop)
        assertEquals(listOf<Long?>(null), second.requests)
        assertEquals(2, result.inserted)
        assertEquals(5, store.events.size)
        assertEquals(at(50), store.state!!.highWaterPlayedAtMs)
        assertEquals(Coverage.COMPLETE_SINCE_BASELINE, store.state!!.coverage)
        // Same page again (TC-010): no new events, status stable.
        assertEquals(0, sync(Pages(null to page(50, 40, 30))).inserted)
        assertEquals(5, store.events.size)
        assertEquals(Coverage.COMPLETE_SINCE_BASELINE, store.state!!.coverage)
    }

    @Test
    fun multiPageBridgeFollowsBeforeCursorUntilWatermark() {
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(15), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        val source = Pages(null to page(50, 40), at(40) to page(30, 20), at(20) to page(15, 12))
        val result = sync(source)
        assertEquals(StopReason.BRIDGED, result.stop)
        assertEquals(3, result.pages)
        assertEquals(6, store.events.size)
        assertEquals(at(50), store.state!!.highWaterPlayedAtMs)
    }

    @Test
    fun bootstrapTruncatedByPageCapIsPartial() {
        val result = sync(Pages(null to page(30, 20), at(20) to page(10, 5)), cap = 2)
        assertEquals(StopReason.PAGE_CAP, result.stop)
        assertEquals(Coverage.PARTIAL_BOOTSTRAP, store.state!!.coverage)
        assertEquals(at(30), store.state!!.highWaterPlayedAtMs)
    }

    @Test
    fun tc026_unbridgedGapKeepsOldHighWaterAndIsVisible() {
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(5), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        val result = sync(Pages(null to page(50, 40), at(40) to page(30, last = true)))
        assertEquals(StopReason.END_OF_HISTORY, result.stop)
        val s = store.state!!
        assertEquals(Coverage.HISTORY_INCOMPLETE, s.coverage)
        assertEquals(at(5), s.highWaterPlayedAtMs)
        assertEquals(at(5), s.gapStartMs)
        assertEquals(at(30), s.gapEndMs)
        assertEquals(3, store.events.size) // only real events, nothing inferred

        // A later run that reaches the retained high-water closes the gap, conservatively as PARTIAL_BOOTSTRAP.
        sync(Pages(null to page(60, 50, 5)))
        assertEquals(Coverage.PARTIAL_BOOTSTRAP, store.state!!.coverage)
        assertEquals(at(60), store.state!!.highWaterPlayedAtMs)
        assertNull(store.state!!.gapStartMs)
    }

    @Test
    fun pageCapAndRepeatedCursorCannotBridge() {
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(1), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        assertEquals(StopReason.PAGE_CAP, sync(Pages(null to page(50, 40), at(40) to page(30, 20)), cap = 2).stop)
        assertEquals(Coverage.HISTORY_INCOMPLETE, store.state!!.coverage)
        assertEquals(at(1), store.state!!.highWaterPlayedAtMs)

        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(1), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        val stuck = Pages(null to page(50, 40), at(40) to page(45, 40))
        assertEquals(StopReason.NO_PROGRESS, sync(stuck).stop)
        assertEquals(listOf(null, at(40)), stuck.requests)
        assertEquals(Coverage.HISTORY_INCOMPLETE, store.state!!.coverage)
    }

    @Test
    fun emptyNewestPageWithWatermarkIsIncompleteAndEmptyBaselineIsUnknown() {
        val empty = RecentlyPlayedPage(emptyList())
        sync(Pages(null to empty))
        assertEquals(Coverage.UNKNOWN, store.state!!.coverage)
        assertNull(store.state!!.highWaterPlayedAtMs)

        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(1), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        sync(Pages(null to empty))
        assertEquals(Coverage.HISTORY_INCOMPLETE, store.state!!.coverage)
        assertEquals(NOW.toEpochMilli(), store.state!!.gapEndMs)
    }

    @Test
    fun tc027_invalidItemsAreFilteredNotImported() {
        val future = PlayHistoryItem(NOW.plusSeconds(301).toString(), TrackDto(id = T1, type = "track"))
        val page = RecentlyPlayedPage(listOf(
            item(9), item(8, local = true), item(7, type = "episode"), item(6, id = null), item(5, id = "bad"),
            PlayHistoryItem("not-a-time", TrackDto(id = T1, type = "track")), future,
            PlayHistoryItem(Instant.ofEpochMilli(at(4)).toString(), null),
        ))
        val result = sync(Pages(null to page))
        assertEquals(1, result.inserted)
        assertEquals(7, result.filtered)
        assertEquals(setOf(Triple(USER, T1, at(9))), store.events)
    }

    @Test
    fun differentTracksAtSameTimeAreSeparateEvents() {
        val page = RecentlyPlayedPage(listOf(item(9, T1), item(9, T2)))
        assertEquals(2, sync(Pages(null to page)).inserted)
    }

    @Test
    fun failureKeepsCommittedPagesAndHighWater() {
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(1), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        val source = Pages(null to page(50, 40)).apply { failOn = at(40) }
        val e = assertThrows(SpotifyApiException::class.java) { sync(source) }
        assertEquals(SpotifyErrorKind.SERVER, e.kind)
        val s = store.state!!
        assertEquals(2, store.events.size)
        assertEquals(at(1), s.highWaterPlayedAtMs)
        assertEquals(Coverage.HISTORY_INCOMPLETE, s.coverage)
        assertEquals("SERVER", s.lastErrorCode)
        assertNull(s.lastSuccessAtMs)
    }

    @Test
    fun mockWebServerPaginationUsesBeforeCursorAndStopsAtWatermark() {
        fun body(vararg minutes: Long, next: Boolean = true) = """{"items":[${minutes.joinToString(",") {
            """{"played_at":"${Instant.ofEpochMilli(at(it))}","track":{"id":"$T1","type":"track","is_local":false}}"""
        }}],"next":${if (next) "\"x\"" else "null"},"cursors":{"before":"${at(minutes.last())}"}}"""
        server.enqueue(MockResponse().setBody(body(50, 40)))
        server.enqueue(MockResponse().setBody(body(30, 20)))
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(20), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        val client = SpotifyClient.create(Tokens, {}, server.url("/v1/"), SpotifyClient.defaultHttpClient(), { NOW }, { 0L })
        val result = sync(client.asRecentlyPlayedSource())

        assertEquals("/v1/me/player/recently-played?limit=50", server.takeRequest().path)
        assertEquals("/v1/me/player/recently-played?limit=50&before=${at(40)}", server.takeRequest().path)
        assertEquals(2, server.requestCount)
        assertEquals(StopReason.BRIDGED, result.stop)
        assertEquals(at(50), store.state!!.highWaterPlayedAtMs)
        assertEquals(4, store.events.size)
    }

    @Test
    fun mockWebServerQuotaStopsRunWithoutAdvancing() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"status":429,"message":"x","reason":"QUOTA_EXCEEDED"}}"""))
        store.state = SyncCheckpoint(highWaterPlayedAtMs = at(20), coverage = Coverage.COMPLETE_SINCE_BASELINE)
        val client = SpotifyClient.create(Tokens, {}, server.url("/v1/"), SpotifyClient.defaultHttpClient(), { NOW }, { 0L })
        val e = assertThrows(SpotifyApiException::class.java) { sync(client.asRecentlyPlayedSource()) }
        assertEquals(1, server.requestCount)
        assertEquals(e.kind.name, store.state!!.lastErrorCode)
        assertEquals(at(20), store.state!!.highWaterPlayedAtMs)
        assertEquals(Coverage.HISTORY_INCOMPLETE, store.state!!.coverage)
    }

    private object Tokens : BearerTokenProvider {
        override suspend fun accessToken() = AccessToken("dummy-token")
        override suspend fun onUnauthorized(rejected: AccessToken) = AccessToken("dummy-token-2")
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-11-04T07:00:00Z")
        const val USER = "dummy-user"
        const val T1 = "0000000000000000000001"
        const val T2 = "0000000000000000000002"
        fun at(minute: Long) = NOW.toEpochMilli() - 1_000_000_000L + minute * 60_000L
    }
}
