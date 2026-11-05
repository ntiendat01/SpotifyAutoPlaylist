package com.spotifyautoplaylist.spotify.playlist

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.auth.BearerTokenProvider
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** TASK-042: MockWebServer -> real SpotifyClient.addTracks (API-04) -> addItemsOutcome classification. */
class AddItemsOutcomeTest {
    private val server = MockWebServer()
    private var refreshes = 0
    private var reauth = false
    private val tokens = object : BearerTokenProvider {
        override suspend fun accessToken() = AccessToken("dummy-access-$refreshes")
        override suspend fun onUnauthorized(rejected: AccessToken): AccessToken {
            refreshes++
            if (reauth && refreshes > 1) throw AuthException(AuthFailure.REAUTH_REQUIRED, 401)
            return AccessToken("dummy-access-$refreshes")
        }
    }
    private val http = SpotifyClient.defaultHttpClient().newBuilder()
        .readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build()
    private val client = SpotifyClient.create(tokens, SpotifyHttpLogger.NONE, server.url("/v1/"), http,
        { Instant.parse("2026-11-05T00:00:00Z") }, { 1_000_000_000L })

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String = "{}") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun add(ids: List<String> = listOf(TRACK)) = runBlocking { addItemsOutcome { client.addTracks(PLAYLIST, ids) } }

    @Test fun `201 with snapshot is Added and sends one POST of track URIs`() {
        server.enqueue(json(201, """{"snapshot_id":"snap-9"}"""))
        assertEquals(AddItemsOutcome.Added("snap-9"), add())
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("""{"uris":["spotify:track:$TRACK"]}""", r.body.readUtf8())
        assertEquals(1, server.requestCount)
    }

    @Test fun `429 is Throttled with Retry-After and is not resent`() {
        server.enqueue(json(429).setHeader("Retry-After", "17"))
        assertEquals(AddItemsOutcome.Throttled(SpotifyErrorKind.RATE_LIMITED, 17), add())
        server.enqueue(json(429, """{"error":{"status":429,"reason":"QUOTA_EXCEEDED"}}"""))
        // The client blocks further calls until Retry-After has passed: no second request reaches the server.
        assertEquals(AddItemsOutcome.Throttled(SpotifyErrorKind.RATE_LIMITED, 17), add())
        assertEquals(1, server.requestCount)
    }

    @Test fun `quota 429 is Throttled QUOTA_EXCEEDED`() {
        server.enqueue(json(429, """{"error":{"status":429,"reason":"QUOTA_EXCEEDED"}}""").setHeader("Retry-After", "3"))
        assertEquals(AddItemsOutcome.Throttled(SpotifyErrorKind.QUOTA_EXCEEDED, 3), add())
    }

    @Test fun `5xx, timeout and unreadable success are Ambiguous, each sent exactly once`() {
        server.enqueue(json(503))
        assertEquals(AddItemsOutcome.Ambiguous(SpotifyErrorKind.SERVER), add())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(AddItemsOutcome.Ambiguous(SpotifyErrorKind.NETWORK), add())
        server.enqueue(json(201, "not json"))
        assertEquals(AddItemsOutcome.Ambiguous(SpotifyErrorKind.MALFORMED_RESPONSE), add())
        assertEquals(3, server.requestCount)
    }

    @Test fun `401 refreshes once and retries once`() {
        server.enqueue(json(401)); server.enqueue(json(201, """{"snapshot_id":"s"}"""))
        assertEquals(AddItemsOutcome.Added("s"), add())
        assertEquals(2, server.requestCount)
        assertEquals("Bearer dummy-access-0", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer dummy-access-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `401 twice is Rejected UNAUTHORIZED without a third POST`() {
        reauth = true
        server.enqueue(json(401)); server.enqueue(json(401))
        assertEquals(AddItemsOutcome.Rejected(SpotifyErrorKind.UNAUTHORIZED), add())
        assertEquals(2, server.requestCount)
    }

    @Test fun `403, 404 and 400 are definite rejections`() {
        listOf(403 to SpotifyErrorKind.FORBIDDEN, 404 to SpotifyErrorKind.NOT_FOUND, 400 to SpotifyErrorKind.BAD_REQUEST).forEach { (c, k) ->
            server.enqueue(json(c))
            assertEquals(AddItemsOutcome.Rejected(k), add())
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun `batch limit is 100 tracks per request and nothing is sent above it`() {
        server.enqueue(json(201, """{"snapshot_id":"s"}"""))
        assertEquals(AddItemsOutcome.Added("s"), add(List(100) { "%022d".format(it) }))
        assertThrows(IllegalArgumentException::class.java) { add(List(101) { "%022d".format(it) }) }
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val PLAYLIST = "37i9dQZF1DXcBWIGoYBM5M"
        const val TRACK = "0000000000000000000001"
    }
}
