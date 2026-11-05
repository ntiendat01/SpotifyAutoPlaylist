package com.spotifyautoplaylist.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.PlaylistOutboxEntity
import com.spotifyautoplaylist.data.local.QualificationEntity
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.local.SyncStateEntity
import com.spotifyautoplaylist.data.local.TrackEntity
import com.spotifyautoplaylist.spotify.OwnerDto
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.PlaylistEntryDto
import com.spotifyautoplaylist.spotify.PlaylistItemDto
import com.spotifyautoplaylist.spotify.PlaylistItemsPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyEndpoint
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import com.spotifyautoplaylist.spotify.playlist.RemoteMembership
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistReconcilerTest {
    private lateinit var db: ReplayDatabase
    private var active = A
    /** Remote playlist contents per playlist ID; a thrown exception at a given offset simulates a failure. */
    private val remote = mutableMapOf<String, List<String>>()
    private var failAtOffset: Int? = null
    private var deleted = false
    private val itemReads = mutableListOf<String>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun page(playlistId: String, offset: Int): PlaylistItemsPage {
        itemReads += playlistId
        if (offset == failAtOffset) throw SpotifyApiException(SpotifyEndpoint.PLAYLIST_ITEMS, SpotifyErrorKind.RATE_LIMITED, 429, 5)
        val all = remote[playlistId].orEmpty()
        val slice = all.drop(offset).take(2)
        return PlaylistItemsPage(slice.map { PlaylistItemDto("2026-11-01T00:00:00Z", false, PlaylistEntryDto(it, "track")) },
            all.size, offset, next = if (offset + slice.size < all.size) "n" else null)
    }

    private val verifier = ManagedPlaylistVerifier({ active }, { db.playlists().managed(it) }) { id ->
        if (deleted) throw SpotifyApiException(SpotifyEndpoint.PLAYLIST, SpotifyErrorKind.NOT_FOUND, 404)
        PlaylistDto(id, "My Replays", owner = OwnerDto(active.spotifyUserId), public = false, collaborative = false, snapshotId = "snap")
    }
    private val reconciler by lazy { PlaylistReconciler({ active }, verifier, ::page, db, { NOW }) }
    private fun reconcile() = runBlocking { reconciler.reconcile() }

    private fun setup(user: AccountKey, playlist: String, vararg rows: Pair<String, OutboxState>) = runBlocking {
        val u = user.spotifyUserId
        db.accounts().upsert(AccountEntity(u, null, 1))
        db.playlists().insertManaged(ManagedPlaylistEntity(u, playlist, null, "My Replays", null, true))
        rows.forEachIndexed { i, (track, state) ->
            db.history().importPage(u, listOf(TrackEntity(u, track, "spotify:track:$track", "Dummy", "A", null, null)),
                emptyList(), SyncStateEntity(u))
            db.qualifications().qualify(QualificationEntity(u, track, i.toLong(), QualifiedBy.DAILY), playlist)
            db.playlists().updateOutbox(PlaylistOutboxEntity(u, playlist, track, state))
        }
    }
    private fun states(user: AccountKey, playlist: String) = runBlocking {
        db.qualifications().outboxInQualificationOrder(user.spotifyUserId, playlist).associate { it.trackId to it.state }
    }
    private fun rows(user: AccountKey, playlist: String) = runBlocking {
        db.qualifications().outboxInQualificationOrder(user.spotifyUserId, playlist)
    }

    @Test fun `TC-012 present remotely becomes PRESENT and missing stays PENDING without any write`() {
        setup(A, P1, T1 to OutboxState.PENDING, T2 to OutboxState.PENDING, T3 to OutboxState.RETRY_LATER)
        remote[P1] = listOf(T1, X1, X2)
        val r = reconcile() as ReconcileResult.Reconciled
        assertEquals(listOf(T1), r.present)
        assertEquals(listOf(T2, T3), r.missing)
        assertEquals(mapOf(T1 to OutboxState.PRESENT, T2 to OutboxState.PENDING, T3 to OutboxState.PENDING), states(A, P1))
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), rows(A, P1).first().addedAtMs)
    }

    @Test fun `TC-015 previously PRESENT track removed remotely is REMOVED_EXTERNALLY and is never re-queued`() {
        setup(A, P1, T1 to OutboxState.PRESENT, T2 to OutboxState.REMOVED_EXTERNALLY)
        remote[P1] = listOf(X1)
        val r = reconcile() as ReconcileResult.Reconciled
        assertEquals(listOf(T1, T2), r.removedExternally)
        assertTrue(r.missing.isEmpty())
        assertEquals(NOW, rows(A, P1).first().lastAttemptMs)
        remote[P1] = listOf(X1, T2) // user re-adds manually: state stays (no documented transition, no auto action)
        assertEquals(OutboxState.REMOVED_EXTERNALLY, states(A, P1)[T2].also { reconcile() })
        assertEquals(OutboxState.REMOVED_EXTERNALLY, states(A, P1)[T2])
    }

    @Test fun `repeat reconciliation is idempotent`() {
        setup(A, P1, T1 to OutboxState.PENDING, T2 to OutboxState.PENDING)
        remote[P1] = listOf(T1)
        val first = reconcile(); val snapshot = rows(A, P1)
        repeat(2) { assertEquals(first, reconcile()); assertEquals(snapshot, rows(A, P1)) }
    }

    @Test fun `incomplete read never marks anything present or removed`() {
        setup(A, P1, T1 to OutboxState.PENDING, T2 to OutboxState.PRESENT, T3 to OutboxState.PENDING)
        remote[P1] = listOf(X1, X2, T1)
        failAtOffset = 2
        val before = rows(A, P1)
        val r = reconcile() as ReconcileResult.Incomplete
        assertEquals(RemoteMembership.Incomplete(RemoteMembership.Reason.API_ERROR, SpotifyErrorKind.RATE_LIMITED, 5), r.membership)
        assertEquals(before, rows(A, P1))
    }

    @Test fun `in-flight states - present resolves to PRESENT, absent stays unresolved for TASK-042`() {
        setup(A, P1, T1 to OutboxState.VERIFY_REMOTE, T2 to OutboxState.VERIFY_REMOTE, T3 to OutboxState.SENDING, X1 to OutboxState.BLOCKED)
        remote[P1] = listOf(T1)
        val r = reconcile() as ReconcileResult.Reconciled
        assertEquals(listOf(T1), r.present)
        assertEquals(listOf(T2, T3, X1), r.unresolved)
        assertTrue(r.missing.isEmpty())
    }

    @Test fun `TC-016 gate - deleted or unverified playlist reads nothing and changes nothing`() {
        setup(A, P1, T1 to OutboxState.PENDING)
        deleted = true
        assertEquals(ReconcileResult.NotWritable(WriteTarget.RelinkRequired(RelinkReason.DELETED)), reconcile())
        assertTrue(itemReads.isEmpty())
        assertEquals(OutboxState.PENDING, states(A, P1)[T1])
    }

    @Test fun `account isolation - other account rows and playlist untouched, switch mid-read applies nothing`() {
        setup(A, P1, T1 to OutboxState.PENDING)
        setup(B, P2, T1 to OutboxState.PRESENT)
        remote[P1] = listOf(T1); remote[P2] = emptyList()
        reconcile()
        assertEquals(OutboxState.PRESENT, states(A, P1)[T1])
        assertEquals(OutboxState.PRESENT, states(B, P2)[T1])
        assertEquals(listOf(P1), itemReads.distinct())
        // Switch between read and persist: nothing applied to either account.
        val switching = PlaylistReconciler({ active }, verifier, { id, o -> page(id, o).also { active = B } }, db, { NOW })
        active = A; remote[P1] = emptyList()
        assertEquals(ReconcileResult.NotWritable(WriteTarget.RelinkRequired(RelinkReason.OWNER)), runBlocking { switching.reconcile() })
        assertEquals(OutboxState.PRESENT, states(A, P1)[T1])
        assertEquals(OutboxState.PRESENT, states(B, P2)[T1])
    }

    companion object {
        val A = AccountKey("user-a", "a".repeat(64))
        val B = AccountKey("user-b", "b".repeat(64))
        val P1 = "1".repeat(22); val P2 = "2".repeat(22)
        val T1 = "T".repeat(21) + "1"; val T2 = "T".repeat(21) + "2"; val T3 = "T".repeat(21) + "3"
        val X1 = "X".repeat(21) + "1"; val X2 = "X".repeat(21) + "2"
        const val NOW = 1_800_000_000_000L
    }
}
