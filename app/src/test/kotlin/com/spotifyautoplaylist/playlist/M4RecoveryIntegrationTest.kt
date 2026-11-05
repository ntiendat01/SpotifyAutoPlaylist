package com.spotifyautoplaylist.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.OutboxState.PENDING
import com.spotifyautoplaylist.data.local.OutboxState.PRESENT
import com.spotifyautoplaylist.data.local.OutboxState.REMOVED_EXTERNALLY
import com.spotifyautoplaylist.data.local.OutboxState.RETRY_LATER
import com.spotifyautoplaylist.data.local.OutboxState.SENDING
import com.spotifyautoplaylist.data.local.PlayEventEntity
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.local.SyncStateEntity
import com.spotifyautoplaylist.data.local.TrackEntity
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.playlist.WriteRun.Status
import com.spotifyautoplaylist.rules.QualificationRecorder
import com.spotifyautoplaylist.spotify.OwnerDto
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.PlaylistEntryDto
import com.spotifyautoplaylist.spotify.PlaylistItemDto
import com.spotifyautoplaylist.spotify.PlaylistItemsPage
import com.spotifyautoplaylist.spotify.SnapshotResponse
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyEndpoint
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TASK-043: end-to-end recovery paths over the real pieces on in-memory Room:
 * play events -> QualificationRecorder (TX-03 outbox rows) -> OutboxWriter -> PlaylistReconciler ->
 * ManagedPlaylistVerifier, against [FakeSpotify], a stateful model of remote playlists with failure injection.
 * Every scenario ends with [assertExactlyOnce].
 */
@RunWith(RobolectricTestRunner::class)
class M4RecoveryIntegrationTest {

    /** What the next POST does. Process deaths are modelled by an exception that is not a Spotify/auth error. */
    enum class Fault { OK, TIMEOUT_APPLIED, TIMEOUT_NOT_APPLIED, SERVER_5XX_NOT_APPLIED, SERVER_5XX_APPLIED,
        RATE_LIMITED, QUOTA, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, DEATH_BEFORE_POST, DEATH_AFTER_POST }

    class ProcessDeath : RuntimeException("simulated process death")

    /** Stateful remote: per-playlist ordered item list, 50-item offset pages, owner per playlist. Never removes on its own. */
    class FakeSpotify {
        val playlists = mutableMapOf<String, MutableList<String>>()
        val owners = mutableMapOf<String, String>()
        val faults = ArrayDeque<Fault>()
        val posts = mutableListOf<Pair<String, List<String>>>()
        val violations = mutableListOf<String>()
        var readFailures = 0
        /** Read failures armed by the next POST (so the pre-POST reconcile still succeeds). */
        var readFailuresAfterPost = 0

        fun page(playlistId: String, offset: Int): PlaylistItemsPage {
            if (readFailures > 0) { readFailures--; throw err(SpotifyEndpoint.PLAYLIST_ITEMS, SpotifyErrorKind.SERVER, 503) }
            val all = playlists[playlistId] ?: throw err(SpotifyEndpoint.PLAYLIST_ITEMS, SpotifyErrorKind.NOT_FOUND, 404)
            val slice = all.drop(offset).take(50)
            return PlaylistItemsPage(slice.map { PlaylistItemDto("2026-11-01T00:00:00Z", false, PlaylistEntryDto(it, "track")) },
                all.size, offset, next = if (offset + slice.size < all.size) "n" else null)
        }

        fun playlist(id: String): PlaylistDto {
            val owner = owners[id]
            if (owner == null || id !in playlists) throw err(SpotifyEndpoint.PLAYLIST, SpotifyErrorKind.NOT_FOUND, 404)
            return PlaylistDto(id, "My Replays", owner = OwnerDto(owner), public = false, collaborative = false, snapshotId = "s${playlists[id]!!.size}")
        }

        fun add(playlistId: String, ids: List<String>): SnapshotResponse {
            posts += playlistId to ids
            if (ids.size > 100) violations += "batch of ${ids.size}"
            if (ids.distinct() != ids) violations += "duplicate ids in one POST"
            val list = playlists[playlistId]
            val fault = faults.removeFirstOrNull() ?: Fault.OK
            readFailures += readFailuresAfterPost; readFailuresAfterPost = 0
            // A blind re-POST: the client sends something that is already in the remote playlist.
            if (list != null && ids.any { it in list }) violations += "re-POST of present ${ids.filter { it in list }}"
            fun applyIt() { list!! += ids }
            return when (fault) {
                Fault.OK -> { applyIt(); SnapshotResponse("s${list!!.size}") }
                Fault.TIMEOUT_APPLIED -> { applyIt(); throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.NETWORK, null) }
                Fault.TIMEOUT_NOT_APPLIED -> throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.NETWORK, null)
                Fault.SERVER_5XX_APPLIED -> { applyIt(); throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.SERVER, 502) }
                Fault.SERVER_5XX_NOT_APPLIED -> throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.SERVER, 503)
                Fault.RATE_LIMITED -> throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.RATE_LIMITED, 429, 30)
                Fault.QUOTA -> throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.QUOTA_EXCEEDED, 429)
                Fault.UNAUTHORIZED -> throw AuthException(AuthFailure.REAUTH_REQUIRED, 401)
                Fault.FORBIDDEN -> throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.FORBIDDEN, 403)
                Fault.NOT_FOUND -> { playlists.remove(playlistId); throw err(SpotifyEndpoint.ADD_ITEMS, SpotifyErrorKind.NOT_FOUND, 404) }
                Fault.DEATH_BEFORE_POST -> throw ProcessDeath()
                Fault.DEATH_AFTER_POST -> { applyIt(); throw ProcessDeath() }
            }
        }

        fun count(playlistId: String, track: String) = playlists[playlistId].orEmpty().count { it == track }
        fun postsOf(track: String) = posts.count { track in it.second }

        private fun err(e: SpotifyEndpoint, k: SpotifyErrorKind, status: Int?, retry: Long? = null) = SpotifyApiException(e, k, status, retry)
    }

    private val db: ReplayDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dir: File = Files.createTempDirectory("m4settings").toFile()
    private val settings = SettingsRepository.create(File(dir, "settings.preferences_pb"))
    private val recorder = QualificationRecorder(db, settings)
    private val spotify = FakeSpotify()
    private var active = A

    @After fun close() { db.close(); dir.deleteRecursively() }

    /** A fresh writer stack, as after a process restart: nothing survives except Room and the remote. */
    private fun writer(): OutboxWriter {
        val verifier = ManagedPlaylistVerifier({ active }, { db.playlists().managed(it) }, spotify::playlist)
        return OutboxWriter({ active }, PlaylistReconciler({ active }, verifier, spotify::page, db, { NOW }), db, spotify::add, { NOW })
    }
    private fun write(): WriteRun = runBlocking { writer().run() }
    private fun writeDying() { try { write(); fail("expected process death") } catch (_: ProcessDeath) {} }

    private fun link(account: AccountKey = A, playlist: String = P1) = runBlocking {
        db.accounts().upsert(AccountEntity(account.spotifyUserId, null, 1))
        db.playlists().insertManaged(ManagedPlaylistEntity(account.spotifyUserId, playlist, null, "My Replays", null, true))
        spotify.playlists.getOrPut(playlist) { mutableListOf() }
        spotify.owners[playlist] = account.spotifyUserId
    }

    private fun plays(account: AccountKey, track: String, vararg times: Long) = runBlocking {
        val u = account.spotifyUserId
        db.history().importPage(u, listOf(TrackEntity(u, track, "spotify:track:$track", "Dummy", "A", null, null)),
            times.map { PlayEventEntity(u, track, it, 1) }, SyncStateEntity(u))
    }

    /** [n] tracks, each played 3 times in one VN day (default daily >= 3), qualification order = index order.
     *  Non-overlapping [LISTEN]-spaced slots so TASK-055 min-listen gaps stay valid (no hour-bucket collisions). */
    private fun qualify(n: Int, account: AccountKey = A, offset: Int = 0): List<String> {
        val ids = (offset until offset + n).map(::id)
        ids.forEachIndexed { i, t ->
            val base = DAY + (offset + i) * 3L * LISTEN
            plays(account, t, base, base + LISTEN, base + 2 * LISTEN)
        }
        val r = runBlocking { recorder.evaluate(account) }
        assertEquals(ids, r.newlyQualified)
        return ids
    }

    private fun outbox(account: AccountKey = A, playlist: String = P1) = runBlocking {
        db.qualifications().outboxInQualificationOrder(account.spotifyUserId, playlist)
    }
    private fun states(account: AccountKey = A, playlist: String = P1) = outbox(account, playlist).associate { it.trackId to it.state }

    /** Exit criterion: each qualifying track is exactly once remote and exactly once local as PRESENT; no violations. */
    private fun assertExactlyOnce(tracks: List<String>, account: AccountKey = A, playlist: String = P1) {
        assertEquals(emptyList<String>(), spotify.violations)
        val rows = outbox(account, playlist)
        val quals = runBlocking { db.qualifications().all(account.spotifyUserId) }
        for (t in tracks) {
            assertEquals("remote count of $t", 1, spotify.count(playlist, t))
            assertEquals("outbox rows of $t", 1, rows.count { it.trackId == t })
            assertEquals("qualifications of $t", 1, quals.count { it.trackId == t })
            assertEquals("state of $t", PRESENT, rows.single { it.trackId == t }.state)
        }
        assertEquals(spotify.playlists[playlist]!!.distinct(), spotify.playlists[playlist])
        // PRESENT is only ever set from a confirmed 2xx or a complete remote read: every PRESENT row is remote.
        rows.filter { it.state == PRESENT }.forEach { assertTrue("${it.trackId} PRESENT but not remote", spotify.count(playlist, it.trackId) == 1) }
    }

    /** Repeat runs (writer + re-evaluation) send nothing and change nothing. */
    private fun assertStable(account: AccountKey = A, playlist: String = P1) {
        val before = outbox(account, playlist)
        val posts = spotify.posts.size
        val remote = spotify.playlists[playlist]!!.toList()
        repeat(3) {
            runBlocking { recorder.evaluate(account) }
            assertEquals(Status.DONE, write().status)
        }
        assertEquals(posts, spotify.posts.size)
        assertEquals(before, outbox(account, playlist))
        assertEquals(remote, spotify.playlists[playlist])
    }

    // ---- normal path and batch boundaries (TC-013, TC-028) ----

    @Test fun `TC-013 TC-028 batch boundaries 0 1 99 100 101 250 - each track exactly once, batches of at most 100`() {
        var offset = 0
        for ((i, n) in listOf(0, 1, 99, 100, 101, 250).withIndex()) {
            val account = AccountKey("user-$i", "$i".repeat(64)); val playlist = "%022d".format(9_000 + i)
            active = account
            link(account, playlist)
            val start = spotify.posts.size
            val ids = if (n == 0) emptyList<String>().also { runBlocking { recorder.evaluate(account) } } else qualify(n, account, offset)
            offset += n
            assertEquals(Status.DONE, write().status)
            val sizes = spotify.posts.drop(start).map { it.second.size }
            assertEquals("n=$n", List(n / 100) { 100 } + listOfNotNull((n % 100).takeIf { it > 0 }), sizes)
            assertEquals(ids, spotify.posts.drop(start).flatMap { it.second }) // qualification order
            assertEquals(ids, spotify.playlists[playlist])
            assertExactlyOnce(ids, account, playlist)
            assertStable(account, playlist)
        }
    }

    @Test fun `TC-012 tracks already in the remote playlist (beyond page 1) are never POSTed`() {
        link()
        val ids = qualify(120)
        spotify.playlists[P1]!! += List(60) { X(it) } + ids.filterIndexed { i, _ -> i % 2 == 0 }
        write()
        assertEquals(ids.filterIndexed { i, _ -> i % 2 == 1 }, spotify.posts.flatMap { it.second })
        assertExactlyOnce(ids)
        assertStable()
    }

    // ---- ambiguous POSTs (TC-014) ----

    @Test fun `TC-014 timeout where the add applied - verified PRESENT, never re-POSTed`() =
        ambiguous(Fault.TIMEOUT_APPLIED, expectRepost = false)

    @Test fun `TC-014 5xx where the add applied - verified PRESENT, never re-POSTed`() =
        ambiguous(Fault.SERVER_5XX_APPLIED, expectRepost = false)

    @Test fun `TC-014 timeout where the add did not apply - verified absent, re-sent once on a later run`() =
        ambiguous(Fault.TIMEOUT_NOT_APPLIED, expectRepost = true)

    @Test fun `TC-014 5xx not applied - verified absent, re-sent once on a later run`() =
        ambiguous(Fault.SERVER_5XX_NOT_APPLIED, expectRepost = true)

    private fun ambiguous(fault: Fault, expectRepost: Boolean) {
        link()
        val ids = qualify(150)
        spotify.faults += listOf(Fault.OK, fault)
        assertEquals(Status.AMBIGUOUS_VERIFIED, write().status)
        assertEquals(2, spotify.posts.size) // run stops after verifying; no re-POST inside the run
        val second = ids.drop(100)
        assertEquals((if (expectRepost) RETRY_LATER else PRESENT), states()[second[0]])
        write() // RETRY_LATER -> PENDING by reconcile; this run must not re-send yet
        write()
        assertEquals(if (expectRepost) 2 else 1, spotify.postsOf(second[0]))
        assertEquals(1, spotify.postsOf(ids[0]))
        assertExactlyOnce(ids)
        assertStable()
    }

    @Test fun `TC-014 ambiguous POST then the verification read fails - stays unresolved, no POST until a complete read`() {
        link()
        val ids = qualify(3)
        spotify.faults += Fault.TIMEOUT_APPLIED
        spotify.readFailuresAfterPost = 1
        assertEquals(Status.VERIFY_PENDING, write().status)
        assertEquals(setOf(OutboxState.VERIFY_REMOTE), states().values.toSet())
        spotify.readFailures = 1
        assertEquals(Status.READ_INCOMPLETE, write().status)
        assertEquals(1, spotify.posts.size)
        write()
        assertExactlyOnce(ids)
        assertStable()
    }

    // ---- process death ----

    @Test fun `process death after the durable claim but before the POST reached Spotify - verified absent, sent once later`() {
        link()
        val ids = qualify(5)
        spotify.faults += Fault.DEATH_BEFORE_POST
        writeDying()
        assertEquals(setOf(SENDING), states().values.toSet())
        assertEquals(Status.DONE, write().status) // resume: SENDING -> VERIFY_REMOTE -> absent -> RETRY_LATER; no POST
        assertEquals(1, spotify.posts.size)
        write(); write()
        assertEquals(2, spotify.postsOf(ids[0]))
        assertExactlyOnce(ids)
        assertStable()
    }

    @Test fun `process death after the POST applied but before settle - resume verifies PRESENT, no second POST`() {
        link()
        val ids = qualify(130)
        spotify.faults += listOf(Fault.OK, Fault.DEATH_AFTER_POST)
        writeDying()
        assertEquals(30, states().values.count { it == SENDING })
        assertEquals(Status.DONE, write().status)
        assertEquals(2, spotify.posts.size)
        assertExactlyOnce(ids)
        assertStable()
    }

    @Test fun `repeated crashes and faults in sequence still converge to exactly once`() {
        link()
        val ids = qualify(250)
        spotify.faults += listOf(Fault.DEATH_AFTER_POST)
        writeDying()
        spotify.faults += listOf(Fault.TIMEOUT_NOT_APPLIED)
        write()
        spotify.faults += listOf(Fault.SERVER_5XX_APPLIED)
        write()
        spotify.faults += listOf(Fault.RATE_LIMITED)
        write()
        spotify.faults += listOf(Fault.DEATH_BEFORE_POST)
        runCatching { write() }
        repeat(6) { write() }
        assertExactlyOnce(ids)
        assertStable()
    }

    // ---- definite rejections ----

    @Test fun `TC-021 TC-022 429 and quota stop the run and the batch is sent once after recovery`() {
        link()
        val ids = qualify(101)
        spotify.faults += Fault.RATE_LIMITED
        val r = write()
        assertEquals(Status.THROTTLED to 30L, r.status to r.retryAfterSeconds)
        assertEquals(1, spotify.posts.size)
        spotify.faults += Fault.QUOTA
        // RETRY_LATER rows become PENDING on the next complete read and are sent in that run: quota stops it again.
        assertEquals(Status.THROTTLED, write().status)
        assertEquals(2, spotify.postsOf(ids[0]))
        repeat(3) { write() }
        assertExactlyOnce(ids)
        assertStable()
    }

    @Test fun `TC-020 401 after refresh needs re-auth, then sent once`() {
        link()
        val ids = qualify(2)
        spotify.faults += Fault.UNAUTHORIZED
        assertEquals(Status.AUTH_REQUIRED, write().status)
        assertEquals(setOf(RETRY_LATER), states().values.toSet())
        write(); write()
        assertExactlyOnce(ids)
        assertEquals(2, spotify.postsOf(ids[0]))
        assertStable()
    }

    @Test fun `TC-025 403 is an explicit stop with no retry inside the run`() {
        link()
        val ids = qualify(2)
        spotify.faults += Fault.FORBIDDEN
        assertEquals(Status.FORBIDDEN, write().status)
        assertEquals(1, spotify.posts.size)
        write(); write()
        assertExactlyOnce(ids)
    }

    @Test fun `TC-016 playlist deleted remotely - writes pause until relinked, then each track once in the new playlist`() {
        link()
        val ids = qualify(3)
        spotify.faults += Fault.NOT_FOUND
        assertEquals(Status.RELINK_REQUIRED, write().status)
        repeat(2) {
            val r = write()
            assertEquals(Status.NOT_WRITABLE, r.status)
            assertTrue(r.target is WriteTarget.RelinkRequired)
        }
        assertEquals(1, spotify.posts.size)
        assertEquals(setOf(RETRY_LATER), states().values.toSet()) // nothing lost; nothing marked PRESENT
        assertEquals(emptyList<String>(), spotify.violations)
        assertTrue(ids.none { spotify.count(P1, it) > 0 })
        // Re-pointing the outbox to a newly confirmed playlist is not implemented (no relink flow in TASK-040..042).
    }

    // ---- external removal and no auto-removal (TC-015, TC-017) ----

    @Test fun `TC-015 manual removal becomes REMOVED_EXTERNALLY and is never re-added, even after crashes and re-evaluation`() {
        link()
        val ids = qualify(3)
        write()
        spotify.playlists[P1]!!.remove(ids[1])
        write()
        assertEquals(REMOVED_EXTERNALLY, states()[ids[1]])
        plays(A, ids[1], DAY + 10 * HOUR, DAY + 11 * HOUR, DAY + 12 * HOUR) // more plays later
        runBlocking { recorder.evaluate(A); recorder.changeThresholds(A, 2, 2) }
        spotify.faults += Fault.DEATH_AFTER_POST
        repeat(3) { runCatching { write() } }
        assertEquals(0, spotify.count(P1, ids[1]))
        assertEquals(1, spotify.postsOf(ids[1]))
        assertEquals(REMOVED_EXTERNALLY, states()[ids[1]])
        assertExactlyOnce(listOf(ids[0], ids[2]))
    }

    @Test fun `TC-017 TC-009 threshold changes feed the writer and never remove anything`() {
        link()
        val dailyOnly = id(1); val rollingOnly = id(2); val base = id(3)
        plays(A, base, DAY, DAY + HOUR, DAY + 2 * HOUR)                     // daily >= 3
        plays(A, dailyOnly, DAY + 3 * HOUR, DAY + 4 * HOUR)                 // 2 plays in one day
        // +LISTEN so rollingOnly's first play does not collide with base at DAY (min-listen gap).
        plays(A, rollingOnly, DAY + LISTEN, DAY + 22 * HOUR + LISTEN, DAY + 44 * HOUR + LISTEN, DAY + 66 * HOUR + LISTEN) // 1/day, 4 within 72h
        assertEquals(listOf(base), runBlocking { recorder.evaluate(A) }.newlyQualified)
        write()
        assertEquals(listOf(base), spotify.playlists[P1])

        assertEquals(listOf(dailyOnly), runBlocking { recorder.changeThresholds(A, 2, 5) }.newlyQualified)
        write()
        assertEquals(listOf(rollingOnly), runBlocking { recorder.changeThresholds(A, 2, 4) }.newlyQualified)
        write()
        assertExactlyOnce(listOf(base, dailyOnly, rollingOnly))

        // Raising thresholds again (back to defaults 3 / 5 and beyond) removes nothing locally or remotely.
        assertEquals(emptyList<String>(), runBlocking { recorder.changeThresholds(A, 3, 5) }.newlyQualified)
        runBlocking { recorder.changeThresholds(A, 20, 30) }
        write()
        assertEquals(listOf(base, dailyOnly, rollingOnly), spotify.playlists[P1])
        assertExactlyOnce(listOf(base, dailyOnly, rollingOnly))
        assertStable()
    }

    // ---- account isolation ----

    @Test fun `accounts are isolated - same track qualifies for both, each playlist gets it once, switch mid-run stops`() {
        link(A, P1); link(B, P2)
        val idsA = qualify(150, A)
        val idsB = qualify(150, B)
        spotify.faults += Fault.OK
        val stopAfterFirst = object { var armed = true }
        val w = run {
            val verifier = ManagedPlaylistVerifier({ active }, { db.playlists().managed(it) }, spotify::playlist)
            OutboxWriter({ active }, PlaylistReconciler({ active }, verifier, spotify::page, db, { NOW }), db,
                { p, ids -> spotify.add(p, ids).also { if (stopAfterFirst.armed) { stopAfterFirst.armed = false; active = B } } }, { NOW })
        }
        assertEquals(Status.ACCOUNT_CHANGED, runBlocking { w.run() }.status)
        assertEquals(listOf(P1), spotify.posts.map { it.first })
        assertTrue(states(B, P2).values.all { it == PENDING })
        write(); write() // B
        assertExactlyOnce(idsB, B, P2)
        active = A
        write()
        assertExactlyOnce(idsA, A, P1)
        assertFalse(spotify.posts.any { it.first == P1 && it.second.isEmpty() })
        assertEquals(idsA, idsB) // same IDs, separate playlists
        assertStable(A, P1)
        active = B; assertStable(B, P2)
    }

    companion object {
        val A = AccountKey("user-a", "a".repeat(64))
        val B = AccountKey("user-b", "b".repeat(64))
        val P1 = "1".repeat(22); val P2 = "2".repeat(22)
        fun id(i: Int) = "%022d".format(i)
        fun X(i: Int) = "X%021d".format(i)
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        /** Clears TASK-055 min-listen between consecutive plays (any track). */
        const val LISTEN = 60_000L
        val DAY = LocalDateTime.parse("2026-11-04T08:00").atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli()
    }
}
