package com.spotifyautoplaylist.data.auth

import com.spotifyautoplaylist.auth.AuthException
import com.spotifyautoplaylist.auth.AuthFailure
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SpotifyAuthConfigRepositoryTest {
    private val dir = Files.createTempDirectory("auth-config").toFile()
    private val repo = SpotifyAuthConfigRepository.create(File(dir, "spotify-auth-config.preferences_pb"))
    private val client = "0123456789abcdef0123456789abcdef"
    private val owner = "owner-user-1"

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun `empty store stays empty without build seed`() = runBlocking<Unit> {
        assertNull(repo.current())
        assertNull(repo.ensureSeeded("", ""))
        assertNull(repo.current())
    }

    @Test fun `seeds from build config only when store empty`() = runBlocking<Unit> {
        val seeded = repo.ensureSeeded(client, owner)
        assertNotNull(seeded)
        assertEquals(client, seeded!!.clientId)
        assertEquals(owner, seeded.expectedOwnerId)
        // Second seed with different build values must not overwrite a saved runtime config.
        val again = repo.ensureSeeded("f".repeat(32), "other-owner")
        assertEquals(client, again!!.clientId)
        assertEquals(owner, again.expectedOwnerId)
    }

    @Test fun `save persists runtime override and rejects partial values`() = runBlocking<Unit> {
        val saved = repo.save(client, owner)
        assertEquals(client, repo.current()!!.clientId)
        assertEquals(owner, repo.current()!!.expectedOwnerId)
        assertEquals(AuthFailure.NOT_CONFIGURED,
            assertThrows(AuthException::class.java) { runBlocking { repo.save(client, "") } }.reason)
        assertEquals(saved.clientId, repo.current()!!.clientId)
        assertEquals(AuthFailure.NOT_CONFIGURED,
            assertThrows(AuthException::class.java) { runBlocking { repo.save("short", owner) } }.reason)
    }

    @Test fun `clear removes runtime config`() = runBlocking<Unit> {
        repo.save(client, owner)
        repo.clear()
        assertNull(repo.current())
    }

    @Test fun `mask never echoes the raw id`() {
        val masked = SpotifyAuthConfigRepository.maskId(owner)
        assertFalse(masked.contains(owner))
        assertTrue(masked.startsWith("••••"))
    }
}
