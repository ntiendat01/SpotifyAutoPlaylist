package com.spotifyautoplaylist.spotify

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.auth.BearerTokenProvider
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyClientTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private val events = mutableListOf<SpotifyHttpEvent>()
    private var nanos = 5_000_000_000L

    @After
    fun stop() = server.shutdown()

    private fun client(http: OkHttpClient = SpotifyClient.defaultHttpClient()) =
        SpotifyClient.create(tokens, { events += it }, server.url("/v1/"), http, { NOW }, { nanos })

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun apiError(block: suspend () -> Unit): SpotifyApiException =
        assertThrows(SpotifyApiException::class.java) { runBlocking { block() } }

    @Test
    fun recentlyPlayedSendsBoundedRequestWithBearer() = runBlocking<Unit> {
        server.enqueue(json(200, RECENT))
        val page = client().recentlyPlayed(before = 1_793_775_600_000)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/player/recently-played?limit=50&before=1793775600000", request.path)
        assertEquals("Bearer $TOKEN_1", request.getHeader("Authorization"))
        assertEquals(TRACK, page.items.single().track?.id)
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeNetwork() {
        val client = client()
        val invalid = listOf<suspend () -> Unit>(
            { client.recentlyPlayed(before = 1, after = 2) },
            { client.recentlyPlayed(limit = 51) },
            { client.playlist("../me") },
            { client.playlistItems(PLAYLIST, offset = -1) },
            { client.addTracks(PLAYLIST, emptyList()) },
            { client.addTracks(PLAYLIST, listOf(TRACK, TRACK)) },
            { client.addTracks(PLAYLIST, List(101) { "%022d".format(it) }) },
            { client.addTracks(PLAYLIST, listOf("spotify:track:$TRACK")) },
            { client.createPlaylist(" ", "d") },
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { runBlocking { it() } } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unauthorizedRefreshesOnceAndRetries() = runBlocking<Unit> {
        server.enqueue(json(401, """{"error":{"status":401,"message":"expired"}}"""))
        server.enqueue(json(200, RECENT))
        client().recentlyPlayed()

        assertEquals("Bearer $TOKEN_1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer $TOKEN_2", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf(SpotifyErrorKind.UNAUTHORIZED, null), events.map { it.error })
    }

    @Test
    fun repeatedUnauthorizedRequiresReauth() {
        server.enqueue(json(401, "{}"))
        server.enqueue(json(401, "{}"))
        val failure = assertThrows(AuthException::class.java) { runBlocking { client().playlist(PLAYLIST) } }
        assertEquals(AuthFailure.REAUTH_REQUIRED, failure.reason)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun repeatedUnauthorizedFailsEvenIfProviderReturnsAToken() {
        tokens.reauthOnSecondRefresh = false
        server.enqueue(json(401, "{}"))
        server.enqueue(json(401, "{}"))
        val failure = apiError { client().playlist(PLAYLIST) }
        assertEquals(SpotifyErrorKind.UNAUTHORIZED, failure.kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun statusMatrixForReads() {
        val cases = listOf(
            400 to SpotifyErrorKind.BAD_REQUEST, 403 to SpotifyErrorKind.FORBIDDEN, 404 to SpotifyErrorKind.NOT_FOUND,
            500 to SpotifyErrorKind.SERVER, 503 to SpotifyErrorKind.SERVER, 302 to SpotifyErrorKind.UNEXPECTED_STATUS,
        )
        cases.forEach { (code, kind) ->
            server.enqueue(json(code, "{}").setHeader("Location", server.url("/elsewhere")))
            val failure = apiError { client().playlist(PLAYLIST) }
            assertEquals(kind, failure.kind)
            assertEquals(code, failure.httpStatus)
            assertEquals(kind == SpotifyErrorKind.SERVER || kind == SpotifyErrorKind.UNEXPECTED_STATUS, failure.retryable)
            assertFalse(failure.outcomeUnknown)
        }
        // No retries and no redirect following.
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun rateLimitFailsFastUntilRetryAfter() = runBlocking<Unit> {
        val client = client()
        server.enqueue(json(429, "{}").setHeader("Retry-After", "7"))
        val limited = apiError { client.recentlyPlayed() }
        assertEquals(SpotifyErrorKind.RATE_LIMITED, limited.kind)
        assertEquals(7L, limited.retryAfterSeconds)
        assertTrue(limited.retryable)

        nanos += TimeUnit.SECONDS.toNanos(3)
        val blocked = apiError { client.playlist(PLAYLIST) }
        assertEquals(SpotifyErrorKind.RATE_LIMITED, blocked.kind)
        assertEquals(4L, blocked.retryAfterSeconds)
        assertEquals(1, server.requestCount)

        nanos += TimeUnit.SECONDS.toNanos(4)
        server.enqueue(json(200, RECENT))
        client.recentlyPlayed()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun quotaExceededIsDistinguished() {
        server.enqueue(json(429, """{"error":{"status":429,"message":"quota","reason":"QUOTA_EXCEEDED"}}"""))
        val failure = apiError { client().recentlyPlayed() }
        assertEquals(SpotifyErrorKind.QUOTA_EXCEEDED, failure.kind)
        assertEquals(60L, failure.retryAfterSeconds)
    }

    @Test
    fun retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(12L, retryAfterSeconds(" 12 ", NOW))
        assertEquals(30L, retryAfterSeconds("Wed, 04 Nov 2026 07:00:30 GMT", NOW))
        assertEquals(0L, retryAfterSeconds("Wed, 04 Nov 2026 06:00:00 GMT", NOW))
        assertNull(retryAfterSeconds("soon", NOW))
        assertNull(retryAfterSeconds(null, NOW))
    }

    @Test
    fun malformedAndOversizedResponsesAreRejected() {
        val bodies = listOf("""{"next":null}""", "not json", """{"items":[],"pad":"${"x".repeat(1_100_000)}"}""")
        bodies.forEach { body ->
            server.enqueue(json(200, body))
            val failure = apiError { client().recentlyPlayed() }
            assertEquals(SpotifyErrorKind.MALFORMED_RESPONSE, failure.kind)
            assertTrue(failure.retryable)
        }
        server.enqueue(json(200, """{"items":[$ITEM,$ITEM]}"""))
        assertEquals(SpotifyErrorKind.MALFORMED_RESPONSE, apiError { client().recentlyPlayed(limit = 1) }.kind)
    }

    @Test
    fun addTracksPostsUrisAndRequiresSnapshot() = runBlocking<Unit> {
        server.enqueue(json(201, """{"snapshot_id":"snap-2"}"""))
        assertEquals("snap-2", client().addTracks(PLAYLIST, listOf(TRACK)).snapshotId)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/playlists/$PLAYLIST/items", request.path)
        assertEquals("""{"uris":["spotify:track:$TRACK"]}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun createPlaylistIsPrivate() = runBlocking<Unit> {
        server.enqueue(json(201, """{"id":"$PLAYLIST","owner":{"id":"owner"},"public":false,"collaborative":false}"""))
        assertEquals(PLAYLIST, client().createPlaylist("My Replays — AutoPlaylist", "Auto-collected repeat plays").id)

        val request = server.takeRequest()
        assertEquals("/v1/me/playlists", request.path)
        assertEquals(
            """{"name":"My Replays — AutoPlaylist","description":"Auto-collected repeat plays","public":false,"collaborative":false}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun ambiguousWritesAreOutcomeUnknownAndNeverResent() {
        val fastTimeout = SpotifyClient.defaultHttpClient().newBuilder()
            .readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build()
        val responses = listOf(
            json(500, "{}") to SpotifyErrorKind.SERVER,
            json(201, """{"snapshot_id":""}""") to SpotifyErrorKind.MALFORMED_RESPONSE,
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) to SpotifyErrorKind.NETWORK,
        )
        responses.forEachIndexed { index, (response, kind) ->
            server.enqueue(response)
            val failure = apiError { client(fastTimeout).addTracks(PLAYLIST, listOf(TRACK)) }
            assertEquals(kind, failure.kind)
            assertTrue(failure.outcomeUnknown)
            assertFalse(failure.retryable)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test
    fun rejectedWritesAreNotOutcomeUnknown() {
        listOf(400, 403, 404).forEach { code ->
            server.enqueue(json(code, "{}"))
            val failure = apiError { client().addTracks(PLAYLIST, listOf(TRACK)) }
            assertFalse(failure.outcomeUnknown)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun playlistItemsRequestsOnePageWithFieldFilter() = runBlocking<Unit> {
        server.enqueue(json(200, """{"items":[{"is_local":false,"item":{"id":"$TRACK","type":"track"}}],"total":51,"offset":50,"next":null}"""))
        val page = client().playlistItems(PLAYLIST, offset = 50)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/playlists/$PLAYLIST/items", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("50", url.queryParameter("offset"))
        assertTrue(url.queryParameter("fields")!!.contains("item(id,type,uri,is_local,linked_from)"))
        assertEquals(TRACK, page.items.single().item?.id)

        server.enqueue(json(200, """{"items":[],"total":0,"offset":0}"""))
        assertEquals(SpotifyErrorKind.MALFORMED_RESPONSE, apiError { client().playlistItems(PLAYLIST, offset = 50) }.kind)
    }

    @Test
    fun playlistAndOwnedPlaylistsParse() = runBlocking<Unit> {
        server.enqueue(json(200, """{"id":"$PLAYLIST","owner":{"id":"owner"},"public":false,"collaborative":false,"snapshot_id":"s"}"""))
        assertEquals("owner", client().playlist(PLAYLIST).owner?.id)
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("fields")!!.contains("owner(id)"))

        server.enqueue(json(200, """{"items":[{"id":"$PLAYLIST","name":"n","owner":{"id":"owner"}}],"total":1,"offset":0,"next":null}"""))
        assertEquals(listOf(PLAYLIST), client().myPlaylists().items.map { it.id })
        assertEquals("/v1/me/playlists?limit=50&offset=0", server.takeRequest().path)

        server.enqueue(json(200, """{"id":"2222222222222222222222"}"""))
        assertEquals(SpotifyErrorKind.MALFORMED_RESPONSE, apiError { client().playlist(PLAYLIST) }.kind)
    }

    @Test
    fun logsAndErrorsContainNoTokensIdsOrQueries() {
        server.enqueue(json(401, "{}"))
        server.enqueue(json(200, RECENT))
        server.enqueue(json(500, """{"error":{"message":"$TRACK"}}"""))
        server.enqueue(json(429, "{}").setHeader("Retry-After", "1"))
        val client = client()
        val errors = mutableListOf<String>()
        runBlocking { client.recentlyPlayed(before = 1_793_775_600_000) }
        errors += apiError { client.addTracks(PLAYLIST, listOf(TRACK)) }.message!!
        errors += apiError { client.playlistItems(PLAYLIST) }.message!!
        errors += apiError { client.playlist(PLAYLIST) }.message!!

        val lines = events.map(SpotifyHttpEvent::toString) + errors
        assertEquals(
            listOf(
                "API-01 GET /me/player/recently-played attempt=1 status=401 result=UNAUTHORIZED ms=0",
                "API-01 GET /me/player/recently-played attempt=2 status=200 result=OK ms=0",
                "API-04 POST /playlists/{id}/items attempt=1 status=500 result=SERVER ms=0",
                "API-03 GET /playlists/{id}/items attempt=1 status=429 result=RATE_LIMITED ms=0",
            ),
            events.map(SpotifyHttpEvent::toString),
        )
        val secrets = listOf(TOKEN_1, TOKEN_2, "Bearer", PLAYLIST, TRACK, "1793775600000", "spotify:track", "?")
        lines.forEach { line -> secrets.forEach { assertFalse("$line leaks $it", line.contains(it)) } }
    }

    private class FakeTokens : BearerTokenProvider {
        var refreshes = 0
        var reauthOnSecondRefresh = true
        private var current = AccessToken(TOKEN_1)

        override suspend fun accessToken() = current

        override suspend fun onUnauthorized(rejected: AccessToken): AccessToken {
            refreshes++
            if (refreshes > 1 && reauthOnSecondRefresh) throw AuthException(AuthFailure.REAUTH_REQUIRED, 401)
            current = AccessToken(if (refreshes == 1) TOKEN_2 else "dummy-access-$refreshes")
            return current
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-04T07:00:00Z")
        const val TOKEN_1 = "dummy-access-one"
        const val TOKEN_2 = "dummy-access-two"
        const val TRACK = "0000000000000000000001"
        const val PLAYLIST = "1111111111111111111111"
        const val ITEM = """{"played_at":"2026-11-04T07:00:00Z","track":{"id":"$TRACK","type":"track"}}"""
        const val RECENT = """{"items":[$ITEM],"next":null}"""
    }
}
