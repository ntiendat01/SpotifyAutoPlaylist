package com.spotifyautoplaylist.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.OutboxState.BLOCKED
import com.spotifyautoplaylist.data.local.OutboxState.PENDING
import com.spotifyautoplaylist.data.local.OutboxState.PRESENT
import com.spotifyautoplaylist.data.local.OutboxState.REMOVED_EXTERNALLY
import com.spotifyautoplaylist.data.local.OutboxState.RETRY_LATER
import com.spotifyautoplaylist.data.local.OutboxState.SENDING
import com.spotifyautoplaylist.data.local.OutboxState.VERIFY_REMOTE
import com.spotifyautoplaylist.data.local.OutboxTransitions
import com.spotifyautoplaylist.data.local.PlaylistOutboxEntity
import com.spotifyautoplaylist.data.local.QualificationEntity
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.local.SyncStateEntity
import com.spotifyautoplaylist.data.local.TrackEntity
import com.spotifyautoplaylist.playlist.WriteRun.Status
import com.spotifyautoplaylist.spotify.OwnerDto
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.PlaylistEntryDto
import com.spotifyautoplaylist.spotify.PlaylistItemDto
import com.spotifyautoplaylist.spotify.PlaylistItemsPage
import com.spotifyautoplaylist.spotify.SnapshotResponse
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyEndpoint
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TASK-042: OutboxWriter over in-memory Room with fake Spotify reads/POSTs (TC-012..016, TC-025, TC-028). */
@RunWith(RobolectricTestRunner::class)
class OutboxWriterTest {
    private lateinit var db: ReplayDatabase
    private var active = A
    private val remote = mutableMapOf<String, MutableList<String>>()
    private var deleted = false
    private var failReads = false
    /** Scripted POST behaviour; default applies the add and returns a snapshot. */
    private var onPost: (String, List<String>) -> SnapshotResponse = { p, ids -> apply(p, ids); SnapshotResponse("snap") }
    private val posts = mutableListOf<Pair<String, List<String>>>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun apply(p: String, ids: List<String>) { remote.getOrPut(p) { mutableListOf() } += ids }
    private fun fail(kind: SpotifyErrorKind, status: Int?, retry: Long? = null) =
        SpotifyApiException(SpotifyEndpoint.ADD_ITEMS, kind, status, retry)

    private fun page(playlistId: String, offset: Int): PlaylistItemsPage {
        if (failReads) throw SpotifyApiException(SpotifyEndpoint.PLAYLIST_ITEMS, SpotifyErrorKind.SERVER, 503)
        val all = remote[playlistId].orEmpty()
        val slice = all.drop(offset).take(50)
        return PlaylistItemsPage(slice.map { PlaylistItemDto("2026-11-01T00:00:00Z", false, PlaylistEntryDto(it, "track")) },
            all.size, offset, next = if (offset + slice.size < all.size) "n" else null)
    }
    private val verifier = ManagedPlaylistVerifier({ active }, { db.playlists().managed(it) }) { id ->
        if (deleted) throw SpotifyApiException(SpotifyEndpoint.PLAYLIST, SpotifyErrorKind.NOT_FOUND, 404)
        PlaylistDto(id, "My Replays", owner = OwnerDto(active.spotifyUserId), public = false, collaborative = false, snapshotId = "snap")
    }
    private val writer by lazy {
        OutboxWriter({ active }, PlaylistReconciler({ active }, verifier, ::page, db, { NOW }), db,
            { p, ids -> posts += p to ids; onPost(p, ids) }, { NOW })
    }
    private fun run() = runBlocking { writer.run() }

    private fun setup(user: AccountKey, playlist: String, vararg rows: PlaylistOutboxEntity, qualifiedAt: (Int) -> Long = { it.toLong() }) =
        runBlocking {
            val u = user.spotifyUserId
            db.accounts().upsert(AccountEntity(u, null, 1))
            db.playlists().insertManaged(ManagedPlaylistEntity(u, playlist, null, "My Replays", null, true))
            db.history().importPage(u, rows.map { TrackEntity(u, it.trackId, "spotify:track:${it.trackId}", "Dummy", "A", null, null) },
                emptyList(), SyncStateEntity(u))
            rows.forEachIndexed { i, row ->
                db.qualifications().qualify(QualificationEntity(u, row.trackId, qualifiedAt(i), QualifiedBy.DAILY), playlist)
                db.playlists().updateOutbox(row.copy(spotifyUserId = u, playlistId = playlist))
            }
        }
    private fun row(track: String, state: OutboxState = PENDING, attempts: Int = 0) = PlaylistOutboxEntity("", "", track, state, attempts)
    private fun rows(user: AccountKey = A, playlist: String = P1) = runBlocking {
        db.qualifications().outboxInQualificationOrder(user.spotifyUserId, playlist).associateBy { it.trackId }
    }
    private fun states(user: AccountKey = A, playlist: String = P1) = rows(user, playlist).mapValues { it.value.state }

    @Test fun `TC-013 happy path - one POST, PRESENT, and an idempotent re-run sends nothing`() {
        setup(A, P1, row(T1), row(T2))
        val first = run()
        assertEquals(WriteRun(Status.DONE, listOf(listOf(T1, T2))), first)
        assertEquals(mapOf(T1 to PRESENT, T2 to PRESENT), states())
        assertEquals(1, rows()[T1]!!.attemptCount)
        assertEquals(NOW, rows()[T1]!!.addedAtMs)
        val snapshot = rows()
        assertEquals(WriteRun(Status.DONE), run())
        assertEquals(1, posts.size)
        assertEquals(snapshot, rows())
    }

    @Test fun `TC-012 already present remotely is not POSTed`() {
        setup(A, P1, row(T1), row(T2))
        remote[P1] = mutableListOf(T1)
        run()
        assertEquals(listOf(P1 to listOf(T2)), posts)
        assertEquals(mapOf(T1 to PRESENT, T2 to PRESENT), states())
    }

    @Test fun `TC-014 ambiguous POST that was applied - VERIFY_REMOTE then PRESENT without a second POST`() {
        setup(A, P1, row(T1))
        onPost = { p, ids -> apply(p, ids); throw fail(SpotifyErrorKind.NETWORK, null) }
        assertEquals(WriteRun(Status.AMBIGUOUS_VERIFIED, listOf(listOf(T1)), SpotifyErrorKind.NETWORK), run())
        assertEquals(PRESENT, states()[T1])
        onPost = { _, _ -> error("must not re-POST") }
        assertEquals(WriteRun(Status.DONE), run())
        assertEquals(1, posts.size)
        assertEquals(listOf(T1), remote[P1])
    }

    @Test fun `ambiguous POST that failed - absent after verify goes RETRY_LATER, re-sent only on a later run`() {
        setup(A, P1, row(T1), row(T2))
        onPost = { _, _ -> throw fail(SpotifyErrorKind.SERVER, 503) }
        assertEquals(Status.AMBIGUOUS_VERIFIED, run().status)
        assertEquals(RETRY_LATER, states()[T1])
        assertEquals(OutboxTransitions.VERIFY_ABSENT, rows()[T1]!!.lastErrorCode)
        assertEquals(1, posts.size)
        onPost = { p, ids -> apply(p, ids); SnapshotResponse("s") }
        assertEquals(WriteRun(Status.DONE, listOf(listOf(T1, T2))), run())
        assertEquals(mapOf(T1 to PRESENT, T2 to PRESENT), states())
        assertEquals(2, rows()[T1]!!.attemptCount)
    }

    @Test fun `ambiguous POST with an incomplete verification read stays VERIFY_REMOTE`() {
        setup(A, P1, row(T1))
        onPost = { _, _ -> failReads = true; throw fail(SpotifyErrorKind.SERVER, 500) }
        assertEquals(Status.VERIFY_PENDING, run().status)
        assertEquals(VERIFY_REMOTE, states()[T1])
        failReads = false
        onPost = { _, _ -> error("unverified row must not be POSTed") }
        // Next run: complete read shows absence -> RETRY_LATER, still no POST in that run.
        assertEquals(WriteRun(Status.DONE), run())
        assertEquals(RETRY_LATER, states()[T1])
        assertEquals(1, posts.size)
    }

    @Test fun `crash with SENDING rows - resume verifies remotely and never blind-POSTs`() {
        setup(A, P1, row(T1, SENDING, 1), row(T2, SENDING, 1), row(T3))
        remote[P1] = mutableListOf(T1)
        assertEquals(WriteRun(Status.DONE, listOf(listOf(T3))), run())
        assertEquals(mapOf(T1 to PRESENT, T2 to RETRY_LATER, T3 to PRESENT), states())
        assertEquals(listOf(P1 to listOf(T3)), posts)
    }

    @Test fun `TC-015 external removal is REMOVED_EXTERNALLY and never re-added, nothing removed`() {
        setup(A, P1, row(T1, PRESENT, 1), row(T2))
        remote[P1] = mutableListOf(X1)
        run(); run()
        assertEquals(mapOf(T1 to REMOVED_EXTERNALLY, T2 to PRESENT), states())
        assertEquals(listOf(P1 to listOf(T2)), posts)
        assertEquals(listOf(X1, T2), remote[P1])
    }

    @Test fun `account isolation - only the active account's rows and playlist are written`() {
        setup(A, P1, row(T1))
        setup(B, P2, row(T1), row(T2))
        run()
        assertEquals(listOf(P1 to listOf(T1)), posts)
        assertEquals(mapOf(T1 to PENDING, T2 to PENDING), states(B, P2))
    }

    @Test fun `account switch before a batch stops further POSTs`() {
        setup(A, P1, *Array(150) { row(id(it)) })
        onPost = { p, ids -> apply(p, ids); active = B; SnapshotResponse("s") }
        assertEquals(Status.ACCOUNT_CHANGED, run().status)
        assertEquals(1, posts.size)
        assertEquals(50, states().values.count { it == PENDING })
    }

    @Test fun `TC-016 404 on POST pauses writes - next run gate stops before any read or POST`() {
        setup(A, P1, row(T1), row(T2))
        onPost = { _, _ -> throw fail(SpotifyErrorKind.NOT_FOUND, 404) }
        val r = run()
        assertEquals(Status.RELINK_REQUIRED, r.status)
        assertEquals(WriteTarget.RelinkRequired(RelinkReason.DELETED), r.target)
        assertEquals(RETRY_LATER, states()[T1])
        deleted = true
        assertEquals(WriteRun(Status.NOT_WRITABLE, target = WriteTarget.RelinkRequired(RelinkReason.DELETED)), run())
        assertEquals(1, posts.size)
    }

    @Test fun `429 honours Retry-After and stops, later batches stay PENDING`() {
        setup(A, P1, *Array(120) { row(id(it)) })
        onPost = { _, _ -> throw fail(SpotifyErrorKind.RATE_LIMITED, 429, 30) }
        assertEquals(WriteRun(Status.THROTTLED, listOf((0 until 100).map(::id)), SpotifyErrorKind.RATE_LIMITED, 30), run())
        assertEquals(100, states().values.count { it == RETRY_LATER })
        assertEquals(20, states().values.count { it == PENDING })
    }

    @Test fun `TC-025 403 is an explicit FORBIDDEN stop, no retry inside the run`() {
        setup(A, P1, *Array(120) { row(id(it)) })
        onPost = { _, _ -> throw fail(SpotifyErrorKind.FORBIDDEN, 403) }
        assertEquals(Status.FORBIDDEN, run().status)
        assertEquals(1, posts.size)
    }

    @Test fun `TC-028 batches of at most 100 in qualification order then track id`() {
        // Qualification time descends with the index, so order is the reverse of insertion; two rows share a time.
        setup(A, P1, *Array(205) { row(id(it)) }, qualifiedAt = { i -> if (i == 0) 204L else (205 - i).toLong() })
        remote[P1] = mutableListOf(id(7))
        val expected = rows().values.map { it.trackId }.filter { it != id(7) }
        run()
        assertEquals(listOf(100, 100, 4), posts.map { it.second.size })
        assertEquals(expected, posts.flatMap { it.second })
        assertEquals(listOf(id(204), id(203)), expected.take(2)) // earliest qualification first
        assertEquals(listOf(id(0), id(1)), expected.takeLast(2)) // tie at t=204 broken by track id
    }

    @Test fun `attempt cap - a row that used all attempts becomes BLOCKED and is not POSTed`() {
        setup(A, P1, row(T1, PENDING, OutboxTransitions.MAX_SEND_ATTEMPTS), row(T2))
        run()
        assertEquals(mapOf(T1 to BLOCKED, T2 to PRESENT), states())
        assertEquals(listOf(P1 to listOf(T2)), posts)
        assertTrue(rows()[T1]!!.lastErrorCode == OutboxTransitions.ATTEMPTS_EXHAUSTED)
    }

    companion object {
        val A = AccountKey("user-a", "a".repeat(64))
        val B = AccountKey("user-b", "b".repeat(64))
        val P1 = "1".repeat(22); val P2 = "2".repeat(22)
        val T1 = "T".repeat(21) + "1"; val T2 = "T".repeat(21) + "2"; val T3 = "T".repeat(21) + "3"
        val X1 = "X".repeat(21) + "1"
        fun id(i: Int) = "%022d".format(i)
        const val NOW = 1_800_000_000_000L
    }
}
