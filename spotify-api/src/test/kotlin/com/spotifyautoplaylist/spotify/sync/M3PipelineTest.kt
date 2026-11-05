package com.spotifyautoplaylist.spotify.sync

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.BearerTokenProvider
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import com.spotifyautoplaylist.spotify.rules.PlayRecord
import com.spotifyautoplaylist.spotify.rules.QualificationEvaluator
import com.spotifyautoplaylist.spotify.rules.QualifiedBy
import com.spotifyautoplaylist.spotify.rules.Thresholds
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

/**
 * TASK-033 end-to-end over the real HTTP stack: MockWebServer -> SpotifyClient -> HistorySync ->
 * account-scoped in-memory store -> QualificationEvaluator. Dummy IDs and tokens only.
 */
class M3PipelineTest {
    private val server = MockWebServer()
    private var nanos = 1_000_000_000L
    private val store = Store()
    private val client = SpotifyClient.create(Tokens, SpotifyHttpLogger.NONE, server.url("/v1/"), SpotifyClient.defaultHttpClient(), { Instant.ofEpochMilli(NOW) }, { nanos })

    @After fun stop() = server.shutdown()

    class Store : HistoryStore {
        val events = linkedSetOf<PlayRecord>()
        val states = mutableMapOf<String, SyncCheckpoint>()
        override suspend fun checkpoint(userId: String) = states[userId]
        override suspend fun importPage(userId: String, plays: List<ValidPlay>, progress: SyncCheckpoint, ingestedAtMs: Long): Int {
            states[userId] = progress
            return plays.count { events.add(PlayRecord(userId, it.trackId, it.playedAtMs)) }
        }
        override suspend fun commit(userId: String, checkpoint: SyncCheckpoint) { states[userId] = checkpoint }
        fun count(user: String) = events.count { it.userId == user }
    }

    private object Tokens : BearerTokenProvider {
        override suspend fun accessToken() = AccessToken("dummy-access")
        override suspend fun onUnauthorized(rejected: AccessToken) = AccessToken("dummy-access-2")
    }

    private fun item(min: Long, track: String = T1, type: String = "track", local: Boolean = false, id: Boolean = true) =
        """{"played_at":"${Instant.ofEpochMilli(at(min))}","track":{${if (id) "\"id\":\"$track\"," else ""}"type":"$type","name":"Dummy","is_local":$local,"artists":[{"name":"A"}]}}"""

    private fun page(items: List<String>, next: Boolean, before: Long?) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"items":[${items.joinToString(",")}],"next":${if (next) "\"x\"" else "null"},"cursors":${before?.let { "{\"before\":\"$it\"}" } ?: "null"},"limit":50}""")

    private fun page(vararg mins: Long, next: Boolean = true, track: String = T1) =
        page(mins.map { item(it, track) }, next, at(mins.min()))

    private fun sync(user: String = A, cap: Int = 20) = runBlocking { HistorySync(client.asRecentlyPlayedSource(), store, { Instant.ofEpochMilli(NOW) }, cap).sync(user) }
    private fun qualified(user: String = A, t: Thresholds = Thresholds()) = QualificationEvaluator.evaluate(user, store.events, t)
    private fun requestPaths() = List(server.requestCount) { server.takeRequest().path }

    @Test fun `tc010 tc011 multi-page baseline then repeated syncs keep counts and status stable`() {
        server.enqueue(page(50, 40, 30))
        server.enqueue(page(20, 10, next = false))
        val first = sync()
        assertEquals(5, first.inserted)
        assertEquals(Coverage.COMPLETE_SINCE_BASELINE, first.checkpoint.coverage)
        assertEquals(at(50), first.checkpoint.highWaterPlayedAtMs)
        val q1 = qualified()
        assertEquals(QualifiedBy.DAILY, q1.getValue(T1).qualifiedBy)
        assertEquals(at(30), q1.getValue(T1).qualifiedAtMs)
        repeat(3) {
            server.enqueue(page(50, 40, 30)) // same newest page again, overlaps high-water
            val again = sync()
            assertEquals(0, again.inserted)
            assertEquals(StopReason.BRIDGED, again.stop)
            assertEquals(Coverage.COMPLETE_SINCE_BASELINE, again.checkpoint.coverage)
        }
        assertEquals(5, store.count(A))
        assertEquals(q1, qualified())
        val paths = requestPaths()
        assertEquals("/v1/me/player/recently-played?limit=50", paths[0])
        assertEquals("/v1/me/player/recently-played?limit=50&before=${at(30)}", paths[1])
    }

    @Test fun `tc011 overlap inclusive on high-water imports new events once`() {
        server.enqueue(page(30, 20, next = false)); sync()
        server.enqueue(page(45, 40, 30)) // includes the old high-water event
        val r = sync()
        assertEquals(2, r.inserted)
        assertEquals(at(45), r.checkpoint.highWaterPlayedAtMs)
        assertEquals(4, store.count(A))
    }

    @Test fun `empty history is UNKNOWN and page cap baseline is PARTIAL_BOOTSTRAP`() {
        server.enqueue(page(emptyList(), next = false, before = null))
        assertEquals(Coverage.UNKNOWN, sync().checkpoint.coverage)
        server.enqueue(page(50, 40))
        val r = sync(cap = 1)
        assertEquals(StopReason.PAGE_CAP, r.stop)
        assertEquals(Coverage.PARTIAL_BOOTSTRAP, r.checkpoint.coverage)
    }

    @Test fun `tc026 unbridged gap is HISTORY_INCOMPLETE then recovery bridges without inventing plays`() {
        server.enqueue(page(10, 5, next = false)); sync()
        // Outage: newest history no longer reaches the high-water (Spotify keeps only recent plays).
        server.enqueue(page(300, 290, next = false))
        val gap = sync()
        assertEquals(Coverage.HISTORY_INCOMPLETE, gap.checkpoint.coverage)
        assertEquals(at(10), gap.checkpoint.highWaterPlayedAtMs)
        assertEquals(at(10) to at(290), gap.checkpoint.gapStartMs to gap.checkpoint.gapEndMs)
        assertEquals(4, store.count(A))
        // Next run reaches the retained high-water: contiguous again, conservatively PARTIAL_BOOTSTRAP.
        server.enqueue(page(310, 300, 290))
        server.enqueue(page(20, 10))
        val ok = sync()
        assertEquals(StopReason.BRIDGED, ok.stop)
        assertEquals(Coverage.PARTIAL_BOOTSTRAP, ok.checkpoint.coverage)
        assertEquals(at(310), ok.checkpoint.highWaterPlayedAtMs)
        assertNull(ok.checkpoint.gapStartMs)
        assertEquals(6, store.count(A))
    }

    @Test fun `429 mid-run keeps imported pages, holds high-water and marks gap until a retry bridges`() {
        server.enqueue(page(10, next = false)); sync()
        server.enqueue(page(100, 90, 80))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("{}"))
        val e = assertThrows(SpotifyApiException::class.java) { sync() }
        assertEquals(SpotifyErrorKind.RATE_LIMITED, e.kind)
        assertEquals(7L, e.retryAfterSeconds)
        val s = store.states.getValue(A)
        assertEquals(at(10), s.highWaterPlayedAtMs)
        assertEquals(Coverage.HISTORY_INCOMPLETE, s.coverage)
        assertEquals("RATE_LIMITED", s.lastErrorCode)
        assertEquals(4, store.count(A))
        // Before Retry-After elapses the client fails fast without a request.
        val before = server.requestCount
        assertEquals(SpotifyErrorKind.RATE_LIMITED, assertThrows(SpotifyApiException::class.java) { sync() }.kind)
        assertEquals(before, server.requestCount)
        nanos += 8_000_000_000L
        server.enqueue(page(100, 90, 80))
        server.enqueue(page(70, 10))
        val ok = sync()
        assertEquals(1, ok.inserted)
        assertEquals(at(100), ok.checkpoint.highWaterPlayedAtMs)
        assertNull(ok.checkpoint.lastErrorCode)
        assertEquals(5, store.count(A))
    }

    @Test fun `quota exceeded and server error on first page change nothing`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"status":429,"message":"quota","reason":"QUOTA_EXCEEDED"}}"""))
        assertEquals(SpotifyErrorKind.QUOTA_EXCEEDED, assertThrows(SpotifyApiException::class.java) { sync() }.kind)
        nanos += 3_600_000_000_000L
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(SpotifyErrorKind.SERVER, assertThrows(SpotifyApiException::class.java) { sync() }.kind)
        assertEquals(0, store.count(A))
        assertNull(store.states.getValue(A).highWaterPlayedAtMs)
        assertEquals(Coverage.UNKNOWN, store.states.getValue(A).coverage)
    }

    @Test fun `tc027 local episode and null-id items are filtered over HTTP`() {
        server.enqueue(page(listOf(item(5), item(4, type = "episode"), item(3, local = true), item(2, id = false), item(1, track = "bad")), false, at(1)))
        val r = sync()
        assertEquals(1, r.inserted)
        assertEquals(4, r.filtered)
    }

    @Test fun `accounts are isolated end to end`() {
        server.enqueue(page(3, 2, 1, next = false)); sync(A)
        server.enqueue(page(3, 2, next = false)); sync(B)
        assertEquals(setOf(T1), qualified(A).keys)
        assertTrue(qualified(B).isEmpty())
        assertEquals(at(3), store.states.getValue(B).highWaterPlayedAtMs)
    }

    @Test fun `tc001 tc002 tc009 thresholds over synced history`() {
        // Two plays one day: not qualified at 3, qualified at 2; three plays: qualified at third.
        server.enqueue(page(listOf(item(3, T1), item(2, T2), item(2, T1), item(1, T2), item(1, T1)), false, at(1)))
        sync()
        assertEquals(setOf(T1), qualified().keys)
        assertEquals(at(3), qualified().getValue(T1).qualifiedAtMs)
        assertEquals(setOf(T1, T2), qualified(t = Thresholds(daily = 2)).keys)
    }

    companion object {
        const val A = "user-a"
        const val B = "user-b"
        const val T1 = "0000000000000000000001"
        const val T2 = "0000000000000000000002"
        /** Base 2026-11-04T00:00Z = 07:00 Vietnam; NOW = 08:00Z (480 min), all events same VN day. */
        val NOW: Long = Instant.parse("2026-11-04T08:00:00Z").toEpochMilli()
        fun at(minutesAfterBase: Long): Long = Instant.parse("2026-11-04T00:00:00Z").toEpochMilli() + minutesAfterBase * 60_000
    }
}
