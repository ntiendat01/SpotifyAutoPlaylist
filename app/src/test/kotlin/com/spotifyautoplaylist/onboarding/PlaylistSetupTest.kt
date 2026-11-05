package com.spotifyautoplaylist.onboarding

import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.spotify.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaylistSetupTest {
    private val key = AccountKey("owner", "a".repeat(64))
    private val id = "P".repeat(22)
    private val other = "Q".repeat(22)

    private fun remote(id: String = this.id, owner: String = "owner", isPublic: Boolean? = false,
                       collaborative: Boolean? = false, snapshot: String? = "snapshot") = PlaylistDto(
        id, "Real playlist", owner = OwnerDto(owner), public = isPublic, collaborative = collaborative, snapshotId = snapshot,
    )

    private class Memory : SetupPersistence {
        var saved: SetupPlaylist? = null
        var journal = CreationRecord()
        var saves = 0
        override suspend fun playlist(account: AccountKey) = saved
        override suspend fun savePlaylist(account: AccountKey, playlist: SetupPlaylist) { saved = playlist; saves++ }
        override suspend fun record(account: AccountKey) = journal
        override suspend fun saveRecord(account: AccountKey, record: CreationRecord) { journal = record }
    }

    private inner class Api(private val store: Memory) : SetupApi {
        var posts = 0
        var reads = 0
        var response = remote()
        var createFailure: Exception? = null
        var readFailure: Exception? = null
        var onCreate: suspend () -> Unit = {}
        var pages: (Int) -> PlaylistsPage = { offset -> PlaylistsPage(emptyList(), 0, offset) }
        override suspend fun create(name: String, description: String): PlaylistDto {
            assertTrue("journal precedes POST", store.journal.attempted)
            assertTrue(description.contains(store.journal.marker))
            posts++
            onCreate()
            createFailure?.let { throw it }
            return response
        }
        override suspend fun get(id: String): PlaylistDto { reads++; readFailure?.let { throw it }; return response }
        override suspend fun ownPlaylists(offset: Int) = pages(offset)
    }

    @Test fun `confirmation and name validation happen before a remote request`() = runTest {
        val store = Memory(); val api = Api(store); val repo = PlaylistSetupRepository({ key }, api, store)
        expect(SetupFailure.CONFIRMATION) { repo.create("Name", false) }
        expect(SetupFailure.INVALID_NAME) { repo.create("  ", true) }
        expect(SetupFailure.CONFIRMATION) { repo.link(id, false) }
        expect(SetupFailure.INVALID_LINK) { repo.link("https://evil.example/playlist/$id", true) }
        assertEquals(0, api.posts + api.reads)
    }

    @Test fun `create verifies readback before mapping and repeated create uses stored playlist`() = runTest {
        val store = Memory(); val api = Api(store); val repo = PlaylistSetupRepository({ key }, api, store)
        assertEquals(id, repo.create("Name", true).id)
        assertEquals(id, store.journal.playlistId)
        assertEquals(1, api.reads)
        assertEquals(id, repo.create("Name", true).id)
        assertEquals(1, api.posts)
        assertEquals(1, store.saves)
        assertEquals(id, repo.load().playlist?.id)
    }

    @Test fun `ambiguous creation survives new repository and discovers marker across pages without POST`() = runTest {
        val store = Memory(); val api = Api(store)
        api.createFailure = SpotifyApiException(SpotifyEndpoint.CREATE_PLAYLIST, SpotifyErrorKind.NETWORK)
        try { PlaylistSetupRepository({ key }, api, store).create("Name", true); fail() } catch (_: SpotifyApiException) { }
        assertTrue(store.journal.attempted)
        api.pages = { offset -> if (offset == 0) PlaylistsPage(listOf(remote(other)), 2, 0, "next")
            else PlaylistsPage(listOf(remote().copy(description = store.journal.marker)), 2, 1) }
        assertEquals(id, PlaylistSetupRepository({ key }, api, store).create("Name", true).id)
        assertEquals(1, api.posts)
        assertEquals(1, api.reads)
    }

    @Test fun `created ID survives failed privacy readback and recovers by ID`() = runTest {
        val store = Memory(); val api = Api(store); val repo = PlaylistSetupRepository({ key }, api, store)
        api.response = remote(isPublic = true)
        expect(SetupFailure.PRIVACY) { repo.create("Name", true) }
        assertEquals(id, store.journal.playlistId)
        assertNull(store.saved)
        api.response = remote()
        assertEquals(id, PlaylistSetupRepository({ key }, api, store).recover().id)
        assertEquals(1, api.posts)
    }

    @Test fun `missing or duplicate discovery matches never enable another creation`() = runTest {
        val store = Memory(); val api = Api(store)
        store.journal = store.journal.attempted()
        val repo = PlaylistSetupRepository({ key }, api, store)
        expect(SetupFailure.RECOVERY) { repo.create("Name", true) }
        api.pages = { PlaylistsPage(listOf(remote().copy(description = store.journal.marker), remote(other).copy(description = store.journal.marker)), 2, 0) }
        expect(SetupFailure.RECOVERY) { repo.recover() }
        assertEquals(0, api.posts)
        assertNull(store.saved)
    }

    @Test fun `invalid or changing pagination fails closed`() = runTest {
        val store = Memory(); val api = Api(store); store.journal = store.journal.attempted()
        api.pages = { offset -> if (offset == 0) PlaylistsPage(listOf(remote()), 2, 0, "next")
            else PlaylistsPage(listOf(remote()), 3, 1) }
        expect(SetupFailure.RECOVERY) { PlaylistSetupRepository({ key }, api, store).recover() }
        assertEquals(0, api.posts)
    }

    @Test fun `link rejects other owner unknown privacy collaboration and missing snapshot`() = runTest {
        val store = Memory(); val api = Api(store); val repo = PlaylistSetupRepository({ key }, api, store)
        api.response = remote(owner = "someoneelse"); expect(SetupFailure.OWNER) { repo.link(id, true) }
        api.response = remote(isPublic = null); expect(SetupFailure.PRIVACY) { repo.link(id, true) }
        api.response = remote(collaborative = true); expect(SetupFailure.PRIVACY) { repo.link(id, true) }
        api.response = remote(snapshot = null); expect(SetupFailure.RESPONSE) { repo.link(id, true) }
        assertNull(store.saved)
        assertEquals(0, api.posts)
    }

    @Test fun `explicit existing private link persists and never replaces another playlist`() = runTest {
        val store = Memory(); val api = Api(store); val repo = PlaylistSetupRepository({ key }, api, store)
        assertEquals(id, repo.link("https://open.spotify.com/playlist/$id?si=example", true).id)
        assertEquals("https://open.spotify.com/playlist/$id", store.saved?.url)
        expect(SetupFailure.ALREADY_LINKED) { repo.link(other, true) }
        assertEquals(0, api.posts)
    }

    @Test fun `account change during request cannot save playlist to another account`() = runTest {
        var active = key; val store = Memory(); val api = Api(store)
        val repo = PlaylistSetupRepository({ active }, api, store)
        api.onCreate = { active = AccountKey("other", "b".repeat(64)) }
        expect(SetupFailure.OWNER) { repo.create("Name", true) }
        assertNull(store.saved)
    }

    @Test fun `concurrent create operations send one POST`() = runTest {
        val store = Memory(); val api = Api(store); val repo = PlaylistSetupRepository({ key }, api, store)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.onCreate = { started.complete(Unit); release.await() }
        val first = async { repo.create("Name", true) }; started.await()
        val second = async { repo.create("Name", true) }; release.complete(Unit)
        assertEquals(id, first.await().id); assertEquals(id, second.await().id)
        assertEquals(1, api.posts)
    }

    @Test fun `journal persists attempt and ID with separate account files and rejects corruption`() {
        val dir = Files.createTempDirectory("setup-journal").toFile()
        try {
            val journal = FileCreationJournal(dir)
            val initial = journal.load(key)
            journal.save(key, initial.identified(id))
            val restored = FileCreationJournal(dir).load(key)
            assertEquals(initial.marker, restored.marker); assertEquals(id, restored.playlistId); assertTrue(restored.attempted)
            assertFalse(journal.load(AccountKey("other", "b".repeat(64))).attempted)
            File(dir, "${key.accountHash}.properties").writeText("corrupt")
            try { journal.load(key); fail() } catch (e: SetupException) { assertEquals(SetupFailure.STORAGE, e.reason) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `interrupted first journal save cannot mint a fresh creation marker`() {
        val dir = Files.createTempDirectory("setup-journal").toFile()
        try {
            File(dir, "${key.accountHash}.tmp").writeText("partial")
            try { FileCreationJournal(dir).load(key); fail() } catch (e: SetupException) { assertEquals(SetupFailure.STORAGE, e.reason) }
        } finally { dir.deleteRecursively() }
    }

    private suspend fun expect(reason: SetupFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: SetupException) { assertEquals(reason, e.reason) }
    }
}
