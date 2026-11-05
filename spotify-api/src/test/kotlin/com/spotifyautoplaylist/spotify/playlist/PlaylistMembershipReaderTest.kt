package com.spotifyautoplaylist.spotify.playlist

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.BearerTokenProvider
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import com.spotifyautoplaylist.spotify.playlist.RemoteMembership.Reason
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-041 / TC-028: MockWebServer -> real SpotifyClient.playlistItems -> PlaylistMembershipReader. */
class PlaylistMembershipReaderTest {
    private val server = MockWebServer()
    private var nanos = 1_000_000_000L
    private val client = SpotifyClient.create(Tokens, SpotifyHttpLogger.NONE, server.url("/v1/"),
        SpotifyClient.defaultHttpClient(), { Instant.parse("2026-11-05T00:00:00Z") }, { nanos })

    @After fun stop() = server.shutdown()

    private object Tokens : BearerTokenProvider {
        override suspend fun accessToken() = AccessToken("dummy-access")
        override suspend fun onUnauthorized(rejected: AccessToken) = AccessToken("dummy-access-2")
    }

    private fun reader(cap: Int = PlaylistMembershipReader.DEFAULT_PAGE_CAP) =
        PlaylistMembershipReader({ client.playlistItems(PLAYLIST, it) }, cap)

    private fun track(id: String, at: String? = "2026-11-01T00:00:00Z", linked: String? = null) =
        """{${at?.let { "\"added_at\":\"$it\"," }.orEmpty()}"is_local":false,"item":{"id":"$id","type":"track","uri":"spotify:track:$id","is_local":false${linked?.let { ",\"linked_from\":{\"id\":\"$it\"}" }.orEmpty()}}}"""

    private fun page(offset: Int, total: Int, items: List<String>, next: Boolean = offset + items.size < total) =
        MockResponse().setResponseCode(200).setBody(
            """{"items":[${items.joinToString(",")}],"total":$total,"offset":$offset,"limit":50,"next":${if (next) "\"https://evil.example/next\"" else "null"}}""")

    private fun run(cap: Int = PlaylistMembershipReader.DEFAULT_PAGE_CAP) = runBlocking { reader(cap).read() }

    @Test fun `TC-028 all pages are read by offset and never via server next URL`() {
        val first = (0 until 50).map { track(id(it)) }
        server.enqueue(page(0, 52, first))
        server.enqueue(page(50, 52, listOf(track(id(50)), track(id(51)))))
        val m = run() as RemoteMembership.Complete
        assertEquals(52, m.addedAtMs.size)
        assertTrue(m.contains(id(51)))
        val paths = (1..2).map { server.takeRequest().requestUrl!! }
        assertEquals(listOf("0", "50"), paths.map { it.queryParameter("offset") })
        assertTrue(paths.all { it.host == server.hostName && it.encodedPath == "/v1/playlists/$PLAYLIST/items" })
    }

    @Test fun `empty playlist is complete with no tracks`() {
        server.enqueue(page(0, 0, emptyList()))
        val m = run() as RemoteMembership.Complete
        assertTrue(m.addedAtMs.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `local files, episodes, null and invalid entries are ignored, duplicates and linked_from collapse`() {
        server.enqueue(page(0, 7, listOf(
            track(id(1), at = "2026-11-02T00:00:00Z"), track(id(1), at = "2026-11-01T00:00:00Z"),
            """{"is_local":true,"item":{"id":null,"type":"track","uri":"spotify:local:x","is_local":true}}""",
            """{"is_local":false,"item":{"id":"${id(9)}","type":"episode","uri":"spotify:episode:${id(9)}"}}""",
            """{"is_local":false,"item":null}""",
            """{"is_local":false,"item":{"id":"bad id","type":"track"}}""",
            track(id(2), at = null, linked = id(3)),
        )))
        val m = run() as RemoteMembership.Complete
        assertEquals(setOf(id(1), id(2), id(3)), m.addedAtMs.keys)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), m.addedAtMs[id(1)])
        assertEquals(null, m.addedAtMs[id(2)])
        assertFalse(m.contains(id(9)))
        assertEquals(4, m.ignoredItems)
    }

    @Test fun `429 mid-pagination is incomplete with retry-after`() {
        server.enqueue(page(0, 60, (0 until 50).map { track(id(it)) }))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9"))
        assertEquals(RemoteMembership.Incomplete(Reason.API_ERROR, SpotifyErrorKind.RATE_LIMITED, 9), run())
    }

    @Test fun `5xx mid-pagination is incomplete`() {
        server.enqueue(page(0, 60, (0 until 50).map { track(id(it)) }))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val m = run() as RemoteMembership.Incomplete
        assertEquals(Reason.API_ERROR to SpotifyErrorKind.SERVER, m.reason to m.error)
    }

    @Test fun `malformed page body is incomplete`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":"nope"}"""))
        assertEquals(SpotifyErrorKind.MALFORMED_RESPONSE, (run() as RemoteMembership.Incomplete).error)
    }

    @Test fun `repeated or wrong offset is rejected by the client and ends incomplete`() {
        server.enqueue(page(0, 100, (0 until 50).map { track(id(it)) }))
        server.enqueue(page(0, 100, (0 until 50).map { track(id(it)) })) // server repeats first page
        val m = run() as RemoteMembership.Incomplete
        assertEquals(SpotifyErrorKind.MALFORMED_RESPONSE, m.error)
    }

    @Test fun `changing total, empty non-final page and next disagreeing with total are inconsistent`() {
        server.enqueue(page(0, 100, (0 until 50).map { track(id(it)) }))
        server.enqueue(page(50, 101, (50 until 100).map { track(id(it)) }))
        assertEquals(Reason.INCONSISTENT_PAGINATION, (run() as RemoteMembership.Incomplete).reason)
        server.enqueue(page(0, 10, emptyList(), next = true))
        assertEquals(Reason.INCONSISTENT_PAGINATION, (run() as RemoteMembership.Incomplete).reason)
        server.enqueue(page(0, 10, listOf(track(id(1))), next = false))
        assertEquals(Reason.INCONSISTENT_PAGINATION, (run() as RemoteMembership.Incomplete).reason)
    }

    @Test fun `page cap stops with incomplete instead of claiming membership`() {
        server.enqueue(page(0, 150, (0 until 50).map { track(id(it)) }))
        server.enqueue(page(50, 150, (50 until 100).map { track(id(it)) }))
        assertEquals(RemoteMembership.Incomplete(Reason.PAGE_CAP), run(cap = 2))
        assertEquals(2, server.requestCount)
    }

    companion object {
        val PLAYLIST = "P".repeat(22)
        fun id(n: Int) = n.toString().padStart(22, '0')
    }
}
