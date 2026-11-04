package com.spotifyautoplaylist.auth

import java.io.Closeable
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

private const val PLAYLIST = "PPPPPPPPPPPPPPPPPPPPPP"
private const val TRACK = "TTTTTTTTTTTTTTTTTTTTTT"
private const val OTHER_TRACK = "OOOOOOOOOOOOOOOOOOOOOO"
private const val MARKER = "[replay-m0:00000000-0000-0000-0000-000000000000]"

class ApiSmokeSessionTest {
    @Test fun `wrong account or missing immutable account id cannot open API session`() = runBlocking {
        for ((body, reason) in listOf(
            """{"id":"other","account_id":"stable-dummy"}""" to ProbeFailure.ACCOUNT_MISMATCH,
            """{"id":"dummy-owner"}""" to ProbeFailure.PROFILE_RESPONSE,
        )) Fixture().use { f ->
            f.server.enqueue(json(body))
            val grant = f.grant()
            assertEquals(reason, failure { f.factory.open(grant, "dummy-owner", DUMMY_CLIENT_ID) }.reason)
            assertTrue(grant.isClosed)
            assertEquals(1, f.server.requestCount)
            assertTrue(f.journal.scopes.isEmpty())
        }
    }

    @Test fun `journal namespaces are isolated by immutable account id and client id`() = runBlocking {
        Fixture().use { f ->
            f.open(accountId = "stable-one").close()
            f.open(accountId = "stable-two").close()
            f.open(accountId = "stable-one", clientId = "f".repeat(32)).close()
            assertEquals(3, f.journal.scopes.size)
            assertTrue(f.journal.scopes.all { it.matches(Regex("[0-9a-f]{64}")) })
        }
    }

    @Test fun `recent validates one page and returns aggregate counts without history`() = runBlocking {
        Fixture().use { f ->
            val session = f.open()
            f.server.enqueue(json("""{"items":[
              {"played_at":"2026-11-04T00:00:00Z","track":{"id":"$TRACK","type":"track","is_local":false}},
              {"played_at":"2026-11-04T00:00:00Z","track":{"id":"$TRACK","type":"episode"}},
              {"played_at":"2026-11-04T00:00:00Z","track":{"id":"$TRACK","type":"track","is_local":true}},
              {"played_at":"invalid","track":{"id":"$TRACK","type":"track"}},
              {"played_at":"2026-11-04T01:00:00Z","track":{"id":"$TRACK","type":"track"}}
            ]}"""))
            val result = session.recent()
            assertEquals(RecentSmokeResult(5, 1, 4), result)
            assertFalse(result.toString().contains(TRACK))
            f.server.takeRequest()
            val request = f.server.takeRequest()
            assertEquals("/v1/me/player/recently-played?limit=50", request.path)
            assertEquals("Bearer dummy-access-token", request.getHeader("Authorization"))
        }
    }

    @Test fun `empty recent history is successful with zero real observations`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); f.server.enqueue(json("""{"items":[]}"""))
            assertEquals(RecentSmokeResult(0, 0, 0), s.recent())
        }
    }

    @Test fun `create requires confirmation and persists intent and id before verified private result`() = runBlocking {
        Fixture(bound = false).use { f ->
            val s = f.open()
            assertEquals(ProbeFailure.CONFIRMATION_REQUIRED, failure { s.preparePlaylist(false) }.reason)
            assertEquals(1, f.server.requestCount)
            f.page("me/playlists", emptyList())
            f.server.enqueue(json("""{"id":"$PLAYLIST"}""", 201))
            f.meta()
            assertEquals(201, s.preparePlaylist(true).postStatus)
            assertTrue(f.journal.saves.any { it.creationAttempted && it.playlistId == null })
            assertEquals(PLAYLIST, f.journal.last.playlistId)
            assertTrue(s.snapshot().privateVerified)
            val requests = f.requests()
            val post = requests.single { it.method == "POST" }
            assertEquals("/v1/me/playlists", post.path)
            val body = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
            assertEquals(false, body.getValue("public").jsonPrimitive.boolean)
            assertEquals(false, body.getValue("collaborative").jsonPrimitive.boolean)
            assertEquals(MARKER, body.getValue("description").jsonPrimitive.content)
            assertEquals("My Replays — AutoPlaylist", body.getValue("name").jsonPrimitive.content)
            f.meta(); assertNull(s.preparePlaylist(true).postStatus)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `lost create response recovers same marker without a second creation`() = runBlocking {
        Fixture(bound = false).use { f ->
            val first = f.open()
            f.page("me/playlists", emptyList())
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            failure { first.preparePlaylist(true) }
            assertTrue(f.journal.last.creationAttempted)
            first.close()
            val reopened = f.open()
            f.page("me/playlists", listOf("""{"id":"$PLAYLIST","name":"test","owner":{"id":"dummy-owner"},"description":"$MARKER"}"""))
            f.meta()
            assertEquals(SmokeOutcome.PLAYLIST_READY, reopened.preparePlaylist(true).outcome)
            assertEquals(1, f.requests().count { it.method == "POST" })
        }
    }

    @Test fun `unknown prior create or another replay playlist never triggers create POST`() = runBlocking {
        for (attempted in listOf(false, true)) Fixture(bound = false, attempted = attempted).use { f ->
            val s = f.open()
            val items = if (attempted) emptyList() else listOf("""{"id":"$PLAYLIST","owner":{"id":"dummy-owner"},"name":"My Replays — AutoPlaylist"}""")
            f.page("me/playlists", items)
            assertEquals(if (attempted) ProbeFailure.CREATION_UNCERTAIN else ProbeFailure.LINK_REQUIRED,
                failure { s.preparePlaylist(true) }.reason)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `manual link verifies owner and private flags and requires confirmation`() = runBlocking {
        Fixture(bound = false).use { f ->
            val s = f.open()
            assertEquals(ProbeFailure.CONFIRMATION_REQUIRED, failure { s.linkPlaylist(PLAYLIST, false) }.reason)
            f.meta()
            s.linkPlaylist("https://open.spotify.com/playlist/$PLAYLIST?si=dummy", true)
            assertEquals(PLAYLIST, f.journal.last.playlistId)
            assertTrue(s.snapshot().privateVerified)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `wrong owner public collaborative or unknown privacy blocks all writes`() = runBlocking {
        for ((owner, public, collaborative, reason) in listOf(
            listOf("other", "false", "false", ProbeFailure.PLAYLIST_OWNER),
            listOf("dummy-owner", "true", "false", ProbeFailure.PLAYLIST_PRIVACY),
            listOf("dummy-owner", "false", "true", ProbeFailure.PLAYLIST_PRIVACY),
            listOf("dummy-owner", "null", "false", ProbeFailure.PLAYLIST_PRIVACY),
        )) Fixture().use { f ->
            val s = f.open()
            f.server.enqueue(json("""{"id":"$PLAYLIST","owner":{"id":"$owner"},"public":$public,"collaborative":$collaborative,"snapshot_id":"s"}"""))
            assertEquals(reason, failure { s.addTrack(TRACK, true) }.reason)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `all pages are checked even when track already found and no POST follows`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); f.meta()
            f.page("playlists/$PLAYLIST/items", listOf(track(TRACK)), total = 2, nextOffset = 1)
            f.page("playlists/$PLAYLIST/items", listOf(track(OTHER_TRACK)), offset = 1, total = 2)
            f.meta()
            assertEquals(SmokeReport(SmokeOutcome.ALREADY_PRESENT, 2), s.addTrack(TRACK, true))
            assertEquals(SmokeWriteState.PRESENT, f.journal.last.writeState)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `normal add persists verification intent then reads membership before Added`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); f.membership(emptyList())
            f.server.enqueue(json("""{"snapshot_id":"new"}""", 201))
            f.membership(listOf(TRACK), "new")
            assertEquals(SmokeReport(SmokeOutcome.PRESENT, 1, 201), s.addTrack("spotify:track:$TRACK", true))
            assertTrue(f.journal.saves.any { it.writeState == SmokeWriteState.VERIFY_REMOTE && it.trackId == TRACK })
            assertEquals(SmokeWriteState.PRESENT, f.journal.last.writeState)
            assertNotNull(s.snapshot().verificationAtMillis)
            val requests = f.requests()
            val post = requests.single { it.method == "POST" }
            assertEquals("/v1/playlists/$PLAYLIST/items", post.path)
            assertEquals(listOf("spotify:track:$TRACK"), Json.parseToJsonElement(post.body.readUtf8())
                .jsonObject.getValue("uris").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("GET", requests.last().method)
        }
    }

    @Test fun `201 without remote membership never reports Added and restart reconciles before retry`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); f.membership(emptyList())
            f.server.enqueue(json("""{"snapshot_id":"new"}""", 201)); f.membership(emptyList(), "new")
            assertEquals(ProbeFailure.VERIFY_REMOTE, failure { s.addTrack(TRACK, true) }.reason)
            assertEquals(SmokeWriteState.VERIFY_REMOTE, f.journal.last.writeState)
            s.close()
            val reopened = f.open(); f.membership(listOf(TRACK), "new")
            assertEquals(SmokeOutcome.ALREADY_PRESENT, reopened.addTrack(TRACK, true).outcome)
            assertEquals(1, f.requests().count { it.method == "POST" })
        }
    }

    @Test fun `lost add response with remote presence is confirmed without blind POST retry`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); f.membership(emptyList())
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            f.membership(listOf(TRACK), "new")
            val report = s.addTrack(TRACK, true)
            assertEquals(SmokeOutcome.PRESENT, report.outcome)
            assertNull(report.postStatus)
            assertEquals(1, f.requests().count { it.method == "POST" })
        }
    }

    @Test fun `confirmed track removed externally is never re-added`() = runBlocking {
        Fixture(writeState = SmokeWriteState.PRESENT).use { f ->
            val s = f.open()
            assertEquals(SmokeWriteState.VERIFY_REMOTE, s.snapshot().writeState) // no fresh remote proof
            f.membership(emptyList())
            assertEquals(SmokeOutcome.REMOVED_EXTERNALLY, s.addTrack(TRACK, true).outcome)
            f.membership(emptyList())
            assertEquals(SmokeOutcome.REMOVED_EXTERNALLY, s.addTrack(TRACK, true).outcome)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `hostile repeated truncated unknown item or changing snapshot prevents POST`() = runBlocking {
        for (kind in listOf("hostile", "repeated", "truncated", "null-item", "old-field", "snapshot")) Fixture().use { f ->
            val s = f.open(); f.meta()
            when (kind) {
                "hostile" -> f.page("playlists/$PLAYLIST/items", listOf(track(OTHER_TRACK)), total = 2,
                    next = "https://evil.example/v1/playlists/$PLAYLIST/items?offset=1")
                "repeated" -> f.page("playlists/$PLAYLIST/items", listOf(track(OTHER_TRACK)), total = 2, nextOffset = 0)
                "truncated" -> f.page("playlists/$PLAYLIST/items", emptyList(), total = 1)
                "null-item" -> f.page("playlists/$PLAYLIST/items", listOf("""{"item":null}"""))
                "old-field" -> f.page("playlists/$PLAYLIST/items", listOf("""{"track":{"id":"$TRACK"}}"""))
                else -> { f.page("playlists/$PLAYLIST/items", emptyList()); f.meta("changed") }
            }
            assertEquals(ProbeFailure.READ_INCOMPLETE, failure { s.addTrack(TRACK, true) }.reason)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `page cap cannot be mistaken for confirmed absence`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); f.meta()
            repeat(20) { offset -> f.page("playlists/$PLAYLIST/items", listOf(track(OTHER_TRACK)), offset, 21, offset + 1) }
            assertEquals(ProbeFailure.READ_INCOMPLETE, failure { s.addTrack(TRACK, true) }.reason)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `journal failure prevents remote create or append`() = runBlocking {
        for (create in listOf(false, true)) Fixture(bound = !create).use { f ->
            val s = f.open()
            f.journal.failSaves = true
            if (create) f.page("me/playlists", emptyList()) else f.membership(emptyList())
            assertEquals(ProbeFailure.JOURNAL, failure {
                if (create) s.preparePlaylist(true) else s.addTrack(TRACK, true)
            }.reason)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    @Test fun `invalid or different locked test track cannot issue requests`() = runBlocking {
        Fixture(writeState = SmokeWriteState.VERIFY_REMOTE).use { f ->
            val s = f.open()
            assertEquals(ProbeFailure.INVALID_TRACK, failure { s.addTrack("https://evil.example/track/$TRACK", true) }.reason)
            assertEquals(ProbeFailure.TRACK_LOCKED, failure { s.addTrack(OTHER_TRACK, true) }.reason)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun `401 403 404 and 429 stop chain and quota is detected without automatic retry`() = runBlocking {
        for (status in listOf(401, 403, 404, 429)) Fixture().use { f ->
            val s = f.open()
            f.server.enqueue(json("""{"error":{"reason":"QUOTA_EXCEEDED"}}""", status).addHeader("Retry-After", "60"))
            val error = failure { s.reconcile() }
            assertEquals(status, error.httpStatus)
            assertEquals(if (status == 429) ProbeFailure.QUOTA_EXCEEDED else ProbeFailure.API_HTTP, error.reason)
            assertEquals(2, f.server.requestCount)
            if (status == 429) {
                assertEquals(ProbeFailure.QUOTA_EXCEEDED, failure { s.reconcile() }.reason)
                assertEquals(2, f.server.requestCount)
            }
        }
    }

    @Test fun `resource parsing accepts only Spotify track or playlist ids and links`() {
        assertEquals(TRACK, SpotifyResourceId.parse("https://open.spotify.com/track/$TRACK?si=dummy", "track"))
        assertEquals(TRACK, SpotifyResourceId.parse("spotify:track:$TRACK", "track"))
        for (value in listOf("http://open.spotify.com/track/$TRACK", "https://open.spotify.com/playlist/$TRACK",
            "https://open.spotify.com.evil/track/$TRACK", "https://user@open.spotify.com/track/$TRACK", "../$TRACK")) {
            assertNull(SpotifyResourceId.parse(value, "track"))
        }
    }

    @Test fun `session close and token expiry prevent all further API calls`() = runBlocking {
        Fixture().use { f ->
            val s = f.open(); s.close()
            assertEquals(ProbeFailure.SESSION_EXPIRED, failure { s.recent() }.reason)
            assertEquals(1, f.server.requestCount)
        }
        var now = 0L
        val grant = TokenGrant(TokenProbeResult(200, SpotifyAuth.scopes, 1), "dummy", nanoTime = { now })
        now = 1_000_000_000
        assertEquals(ProbeFailure.SESSION_EXPIRED, assertThrows(ProbeException::class.java) { grant.requireAccessToken() }.reason)
        assertTrue(grant.isClosed)
    }

    @Test fun `same account sessions share writer lock refresh journal and persist before HTTP POST`() = runBlocking {
        for (create in listOf(false, true)) Fixture(bound = !create).use { f ->
            val first = f.open()
            val second = f.open()
            val posts = AtomicInteger()
            val durableBeforePost = AtomicBoolean(true)
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (request.method == "POST") {
                        val record = f.journal.last
                        durableBeforePost.set(durableBeforePost.get() && if (create) record.creationAttempted
                            else record.writeState == SmokeWriteState.VERIFY_REMOTE && record.trackId == TRACK)
                        posts.incrementAndGet()
                        return json(if (create) """{"id":"$PLAYLIST"}""" else """{"snapshot_id":"new"}""", 201)
                    }
                    if (path.endsWith("/items")) {
                        val items = if (posts.get() == 0) "" else track(TRACK)
                        val total = if (posts.get() == 0) 0 else 1
                        return json("""{"items":[$items],"offset":0,"total":$total,"next":null}""")
                    }
                    if (path.endsWith("/me/playlists")) return json("""{"items":[],"offset":0,"total":0,"next":null}""")
                    return json("""{"id":"$PLAYLIST","owner":{"id":"dummy-owner"},"public":false,"collaborative":false,"snapshot_id":"s${posts.get()}"}""")
                }
            }
            listOf(async { if (create) first.preparePlaylist(true) else first.addTrack(TRACK, true) },
                async { if (create) second.preparePlaylist(true) else second.addTrack(TRACK, true) }).awaitAll()
            assertTrue(durableBeforePost.get())
            assertEquals(1, posts.get())
        }
    }

    @Test fun `cancel during add leaves durable intent and reopened session only reconciles`() = runBlocking {
        Fixture().use { f ->
            val first = f.open(); f.membership(emptyList())
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async { first.addTrack(TRACK, true) }
            withContext(Dispatchers.IO) {
                repeat(5) { index ->
                    val request = f.server.takeRequest(3, TimeUnit.SECONDS) ?: throw AssertionError("Missing request")
                    if (index == 4) assertEquals("POST", request.method)
                }
            }
            assertEquals(SmokeWriteState.VERIFY_REMOTE, f.journal.last.writeState)
            pending.cancelAndJoin()
            first.close()
            val reopened = f.open(); f.membership(emptyList())
            assertEquals(SmokeOutcome.ABSENT_RETRY_READY, reopened.reconcile().outcome)
            assertTrue(f.requests().all { it.method == "GET" })
        }
    }

    private class Fixture(bound: Boolean = true, attempted: Boolean = false,
        writeState: SmokeWriteState = SmokeWriteState.NONE,
    ) : Closeable {
        val server = MockWebServer()
        val journal = MemoryJournal(SmokeRecord(MARKER, if (bound) PLAYLIST else null, attempted,
            if (writeState == SmokeWriteState.NONE) null else TRACK, writeState))
        private val sessions = mutableListOf<SmokeSession>()
        val factory = ApiSmokeFactory(journal, server.url("/v1/"),
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS).build(),
            now = { Instant.parse("2026-11-04T00:00:00Z") })
        fun grant() = TokenGrant(TokenProbeResult(200, SpotifyAuth.scopes, 3600), "dummy-access-token")
        suspend fun open(accountId: String = "stable-dummy", clientId: String = DUMMY_CLIENT_ID): SmokeSession {
            server.enqueue(json("""{"id":"dummy-owner","account_id":"$accountId"}"""))
            return factory.open(grant(), "dummy-owner", clientId).also(sessions::add)
        }
        fun meta(snapshot: String = "s") { server.enqueue(json("""{"id":"$PLAYLIST","owner":{"id":"dummy-owner"},"public":false,"collaborative":false,"snapshot_id":"$snapshot"}""")) }
        fun page(path: String, items: List<String>, offset: Int = 0, total: Int = offset + items.size,
            nextOffset: Int? = null, next: String? = nextOffset?.let { server.url("/v1/$path?offset=$it").toString() },
        ) { server.enqueue(json(buildJsonObject {
            put("offset", offset); put("total", total); put("next", next?.let(::JsonPrimitive) ?: JsonNull)
            put("items", JsonArray(items.map(Json::parseToJsonElement)))
        }.toString())) }
        fun membership(ids: List<String>, snapshot: String = "s") { meta(snapshot); page("playlists/$PLAYLIST/items", ids.map(::track)); meta(snapshot) }
        fun requests() = (0 until server.requestCount).mapNotNull { server.takeRequest(200, TimeUnit.MILLISECONDS) }
        override fun close() { sessions.forEach(SmokeSession::close); server.close() }
    }

    private class MemoryJournal(private val initial: SmokeRecord) : SmokeJournal {
        private val records = mutableMapOf<String, SmokeRecord>()
        val scopes get() = records.keys.toSet()
        val saves = mutableListOf<SmokeRecord>()
        val last get() = records.values.last()
        var failSaves = false
        override fun load(scope: String) = records.getOrPut(scope) { initial }
        override fun save(scope: String, record: SmokeRecord) {
            if (failSaves) throw ProbeException(ProbeFailure.JOURNAL)
            records[scope] = record; saves += record
        }
    }

    companion object {
        private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)
        private fun track(id: String) = """{"item":{"id":"$id","type":"track","is_local":false}}"""
        private suspend fun failure(block: suspend () -> Any?): ProbeException {
            try { block() } catch (e: ProbeException) { return e }
            throw AssertionError("Expected safe smoke failure")
        }
    }
}
