package com.spotifyautoplaylist.spotify.playlist

import com.spotifyautoplaylist.auth.AccessToken
import com.spotifyautoplaylist.auth.BearerTokenProvider
import com.spotifyautoplaylist.spotify.SpotifyClient
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.SpotifyHttpLogger
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-043 HTTP-level variant: a stateful MockWebServer dispatcher models one remote playlist (offset-paged
 * GET items, POST add with fault injection) behind the real SpotifyClient, PlaylistMembershipReader and
 * addItemsOutcome. A small test driver mirrors the OutboxWriter loop (read all -> POST missing in batches of
 * <= 100 -> on ambiguity re-read and stop). Room/state machine is covered in app's M4RecoveryIntegrationTest.
 */
class AddItemsReadBackHttpTest {
    enum class Fault { OK, TIMEOUT_APPLIED, TIMEOUT_NOT_APPLIED, S503, S429, S403, S404 }

    private val remote = mutableListOf<String>()
    private val faults = ArrayDeque<Fault>()
    private val posts = mutableListOf<List<String>>()
    private val violations = mutableListOf<String>()
    private var nanos = 1_000_000_000L

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(remote) {
                val url = request.requestUrl!!
                if (url.encodedPath != "/v1/playlists/$PLAYLIST/items") return MockResponse().setResponseCode(404)
                when (request.method) {
                    "GET" -> {
                        val offset = url.queryParameter("offset")!!.toInt(); val limit = url.queryParameter("limit")!!.toInt()
                        val slice = remote.drop(offset).take(limit)
                        json(200, """{"items":[${slice.joinToString(",") { item(it) }}],"total":${remote.size},"offset":$offset,"limit":$limit,"next":${if (offset + slice.size < remote.size) "\"https://api.spotify.com/next\"" else "null"}}""")
                    }
                    "POST" -> {
                        val ids = Regex("spotify:track:([A-Za-z0-9]{22})").findAll(request.body.readUtf8()).map { it.groupValues[1] }.toList()
                        posts += ids
                        if (ids.size > 100) violations += "batch ${ids.size}"
                        if (ids.any { it in remote }) violations += "re-POST of a present track"
                        when (faults.removeFirstOrNull() ?: Fault.OK) {
                            Fault.OK -> { remote += ids; json(201, """{"snapshot_id":"s${remote.size}"}""") }
                            Fault.TIMEOUT_APPLIED -> { remote += ids; MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                            Fault.TIMEOUT_NOT_APPLIED -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            Fault.S503 -> json(503)
                            Fault.S429 -> json(429).setHeader("Retry-After", "5")
                            Fault.S403 -> json(403, """{"error":{"status":403,"message":"Forbidden"}}""")
                            Fault.S404 -> json(404, """{"error":{"status":404,"message":"Not found"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
    }
    private val http = SpotifyClient.defaultHttpClient().newBuilder()
        .readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build()
    private val client = SpotifyClient.create(Tokens, SpotifyHttpLogger.NONE, server.url("/v1/"), http,
        { Instant.parse("2026-11-05T00:00:00Z") }, { nanos })

    @After fun stop() = server.shutdown()

    private object Tokens : BearerTokenProvider {
        override suspend fun accessToken() = AccessToken("dummy-access")
        override suspend fun onUnauthorized(rejected: AccessToken) = AccessToken("dummy-access-2")
    }

    private fun json(code: Int, body: String = "{}") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun item(id: String) =
        """{"added_at":"2026-11-01T00:00:00Z","is_local":false,"item":{"id":"$id","type":"track","uri":"spotify:track:$id","is_local":false}}"""

    private suspend fun read() = PlaylistMembershipReader({ client.playlistItems(PLAYLIST, it) }).read()
        .let { it as? RemoteMembership.Complete ?: error("incomplete read: $it") }

    /** One writer-like run over [wanted] (qualification order). Returns the stopping outcome, or null when done. */
    private fun sync(wanted: List<String>): AddItemsOutcome? = runBlocking {
        val present = read()
        for (batch in wanted.filterNot(present::contains).chunked(SpotifyClient.MAX_ADD)) {
            when (val o = addItemsOutcome { client.addTracks(PLAYLIST, batch) }) {
                is AddItemsOutcome.Added -> Unit
                is AddItemsOutcome.Ambiguous -> { read(); return@runBlocking o } // verify, never re-POST in this run
                else -> return@runBlocking o
            }
        }
        null
    }

    private fun assertExactlyOnce(wanted: List<String>) {
        assertEquals(emptyList<String>(), violations)
        wanted.forEach { assertEquals(1, remote.count { r -> r == it }) }
        assertEquals(remote.distinct(), remote)
        val n = posts.size
        assertEquals(null, sync(wanted)) // idempotent: complete read, nothing to send
        assertEquals(n, posts.size)
    }

    @Test fun `batch boundaries 0 1 99 100 101 250 over HTTP - each track once, all pages read back`() {
        var start = 0
        for (n in listOf(0, 1, 99, 100, 101, 250)) {
            val wanted = (start until start + n).map(::id); start += n
            val before = posts.size
            assertEquals(null, sync(wanted))
            assertEquals(List(n / 100) { 100 } + listOfNotNull((n % 100).takeIf { it > 0 }), posts.drop(before).map { it.size })
            assertExactlyOnce(wanted)
        }
        assertEquals(551, remote.size) // read-back spans 12 pages of 50
    }

    @Test fun `TC-014 timeout where the add applied - read-back finds it, no duplicate POST`() {
        val wanted = (0 until 150).map(::id)
        faults += listOf(Fault.OK, Fault.TIMEOUT_APPLIED)
        assertTrue(sync(wanted) is AddItemsOutcome.Ambiguous)
        assertEquals(null, sync(wanted))
        assertEquals(2, posts.size)
        assertExactlyOnce(wanted)
    }

    @Test fun `TC-014 timeout or 503 where the add did not apply - re-sent once after a complete read`() {
        val wanted = (0 until 3).map(::id)
        faults += listOf(Fault.TIMEOUT_NOT_APPLIED, Fault.S503)
        assertEquals(AddItemsOutcome.Ambiguous(SpotifyErrorKind.NETWORK), sync(wanted))
        assertEquals(AddItemsOutcome.Ambiguous(SpotifyErrorKind.SERVER), sync(wanted))
        assertEquals(null, sync(wanted))
        assertEquals(3, posts.size)
        assertExactlyOnce(wanted)
    }

    @Test fun `TC-021 429 is not resent before Retry-After, then sent once`() {
        val wanted = listOf(id(1))
        faults += Fault.S429
        assertEquals(AddItemsOutcome.Throttled(SpotifyErrorKind.RATE_LIMITED, 5), sync(wanted))
        nanos += TimeUnit.SECONDS.toNanos(6)
        assertEquals(null, sync(wanted))
        assertEquals(2, posts.size)
        assertExactlyOnce(wanted)
    }

    @Test fun `TC-025 TC-016 403 and 404 are definite rejections, nothing applied`() {
        val wanted = listOf(id(1))
        faults += listOf(Fault.S403, Fault.S404)
        assertEquals(AddItemsOutcome.Rejected(SpotifyErrorKind.FORBIDDEN), sync(wanted))
        assertEquals(AddItemsOutcome.Rejected(SpotifyErrorKind.NOT_FOUND), sync(wanted))
        assertTrue(remote.isEmpty())
        assertEquals(null, sync(wanted))
        assertExactlyOnce(wanted)
    }

    private companion object {
        const val PLAYLIST = "0123456789abcdefABCDEF"
        fun id(i: Int) = "%022d".format(i)
    }
}
