package com.spotifyautoplaylist.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.onboarding.FileCreationJournal
import com.spotifyautoplaylist.onboarding.PlaylistSetupRepository
import com.spotifyautoplaylist.onboarding.RoomSetupPersistence
import com.spotifyautoplaylist.onboarding.SetupApi
import com.spotifyautoplaylist.spotify.OwnerDto
import com.spotifyautoplaylist.spotify.PlaylistDto
import com.spotifyautoplaylist.spotify.PlaylistsPage
import com.spotifyautoplaylist.spotify.SpotifyApiException
import com.spotifyautoplaylist.spotify.SpotifyEndpoint
import com.spotifyautoplaylist.spotify.SpotifyErrorKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedPlaylistVerifierTest {
    private lateinit var db: ReplayDatabase
    private lateinit var dir: File
    private var active = A
    private var response: () -> PlaylistDto = { remote() }
    private val reads = mutableListOf<String>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
        dir = Files.createTempDirectory("setup").toFile()
    }
    @After fun close() { db.close(); dir.deleteRecursively() }

    private fun remote(id: String = P1, owner: String? = A.spotifyUserId, public: Boolean? = false,
                       collaborative: Boolean? = false, snapshot: String? = "snap-1", name: String? = "My Replays") =
        PlaylistDto(id, name, owner = OwnerDto(owner), public = public, collaborative = collaborative, snapshotId = snapshot)

    private val verifier = ManagedPlaylistVerifier({ active }, { db.playlists().managed(it) }) { reads += it; response() }
    private fun verify() = runBlocking { verifier.verify() }
    private fun store(user: String, id: String, verified: Boolean = true) = runBlocking {
        db.accounts().upsert(AccountEntity(user, null, 1))
        db.playlists().insertManaged(ManagedPlaylistEntity(user, id, null, "My Replays", null, verified))
    }
    private fun fail(kind: SpotifyErrorKind, status: Int?): () -> PlaylistDto =
        { throw SpotifyApiException(SpotifyEndpoint.PLAYLIST, kind, status) }

    @Test fun `no mapping for the bound account is NotLinked without a request`() {
        store(B.spotifyUserId, P2)
        assertEquals(WriteTarget.NotLinked, verify())
        assertTrue(reads.isEmpty())
    }

    @Test fun `existing mapping is re-verified by GET on every run and exposes the current snapshot`() {
        store(A.spotifyUserId, P1)
        val first = verify() as WriteTarget.Ready
        assertEquals(P1 to "snap-1", first.playlistId to first.snapshotId)
        response = { remote(snapshot = "snap-2", name = "Renamed") }
        val second = verify() as WriteTarget.Ready
        assertEquals("snap-2" to "Renamed", second.snapshotId to second.name)
        assertEquals(listOf(P1, P1), reads)
        assertEquals(P1, runBlocking { db.playlists().managed(A.spotifyUserId) }!!.playlistId)
    }

    @Test fun `TC-016 deleted playlist requires relink and the mapping is not replaced or recreated`() {
        store(A.spotifyUserId, P1)
        response = fail(SpotifyErrorKind.NOT_FOUND, 404)
        assertEquals(WriteTarget.RelinkRequired(RelinkReason.DELETED), verify())
        val kept = runBlocking { db.playlists().managed(A.spotifyUserId) }!!
        assertEquals(P1 to true, kept.playlistId to kept.ownershipVerified)
    }

    @Test fun `foreign, public, collaborative or unknown privacy playlists are not writable`() {
        store(A.spotifyUserId, P1)
        val cases = listOf(
            remote(owner = B.spotifyUserId) to RelinkReason.OWNER,
            remote(owner = null) to RelinkReason.OWNER,
            remote(public = true) to RelinkReason.PRIVACY,
            remote(public = null) to RelinkReason.PRIVACY,
            remote(collaborative = true) to RelinkReason.PRIVACY,
            remote(collaborative = null) to RelinkReason.PRIVACY,
        )
        for ((dto, reason) in cases) {
            response = { dto }
            assertEquals(WriteTarget.RelinkRequired(reason), verify())
        }
    }

    @Test fun `malformed GET and transient errors block writes without relink`() {
        store(A.spotifyUserId, P1)
        response = { remote(id = P2) }
        assertEquals(WriteTarget.Unavailable(SpotifyErrorKind.MALFORMED_RESPONSE, true), verify())
        response = { remote(snapshot = " ") }
        assertEquals(WriteTarget.Unavailable(SpotifyErrorKind.MALFORMED_RESPONSE, true), verify())
        response = fail(SpotifyErrorKind.RATE_LIMITED, 429)
        assertEquals(WriteTarget.Unavailable(SpotifyErrorKind.RATE_LIMITED, true), verify())
        response = fail(SpotifyErrorKind.SERVER, 503)
        assertEquals(WriteTarget.Unavailable(SpotifyErrorKind.SERVER, true), verify())
        response = fail(SpotifyErrorKind.FORBIDDEN, 403)
        assertEquals(WriteTarget.Unavailable(SpotifyErrorKind.FORBIDDEN, false), verify())
    }

    @Test fun `unverified or invalid stored mapping requires relink without a request`() {
        store(A.spotifyUserId, P1, verified = false)
        assertEquals(WriteTarget.RelinkRequired(RelinkReason.UNVERIFIED), verify())
        assertTrue(reads.isEmpty())
    }

    @Test fun `TC-033 TC-034 account scoping - other account mapping never used and switch mid-GET rejects`() {
        store(A.spotifyUserId, P1); store(B.spotifyUserId, P2)
        active = B
        response = { remote(id = P2, owner = B.spotifyUserId) }
        assertEquals(P2, (verify() as WriteTarget.Ready).playlistId)
        active = A
        response = { active = B; remote() }
        assertEquals(WriteTarget.RelinkRequired(RelinkReason.OWNER), verify())
    }

    @Test fun `onboarding link persists a mapping that the verifier accepts`() = runBlocking {
        val api = object : SetupApi {
            override suspend fun create(name: String, description: String) = error("no create")
            override suspend fun get(id: String) = remote()
            override suspend fun ownPlaylists(offset: Int) = PlaylistsPage(emptyList(), 0, offset)
        }
        val setup = PlaylistSetupRepository({ A }, api, RoomSetupPersistence(db, FileCreationJournal(dir)))
        setup.link("https://open.spotify.com/playlist/$P1?si=x", confirmed = true)
        assertEquals(P1, (verify() as WriteTarget.Ready).playlistId)
    }

    companion object {
        val A = AccountKey("user-a", "a".repeat(64))
        val B = AccountKey("user-b", "b".repeat(64))
        val P1 = "1".repeat(22)
        val P2 = "2".repeat(22)
    }
}
