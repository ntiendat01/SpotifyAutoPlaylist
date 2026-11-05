package com.spotifyautoplaylist.data.settings

import com.spotifyautoplaylist.auth.AccountKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {
    private val dir = Files.createTempDirectory("settings").toFile()
    private val settings = SettingsRepository.create(File(dir, "settings.preferences_pb"))
    private val accountA = AccountKey("user-a", "a".repeat(64))
    private val accountB = AccountKey("user-b", "b".repeat(64))

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun `defaults follow 05-DATABASE with fixed window and time zone`() = runBlocking<Unit> {
        val defaults = settings.current(accountA)
        assertEquals(3, defaults.dailyThreshold)
        assertEquals(5, defaults.rollingThreshold)
        assertEquals(72, defaults.rollingWindowHours)
        assertEquals("Asia/Ho_Chi_Minh", defaults.timeZone)
        assertTrue(defaults.autoSyncEnabled)
        assertEquals(AccountSettings.DEFAULT_PLAYLIST_NAME, defaults.playlistName)
    }

    @Test fun `valid update persists and boundary values are accepted`() = runBlocking<Unit> {
        settings.update(accountA) { it.copy(dailyThreshold = 2, rollingThreshold = 30, autoSyncEnabled = false) }
        val stored = settings.current(accountA)
        assertEquals(2, stored.dailyThreshold)
        assertEquals(30, stored.rollingThreshold)
        assertFalse(stored.autoSyncEnabled)
        settings.update(accountA) { it.copy(dailyThreshold = 20, rollingThreshold = 2) }
        assertEquals(20, settings.current(accountA).dailyThreshold)
    }

    @Test fun `invalid values are rejected and nothing is written`() = runBlocking<Unit> {
        settings.update(accountA) { it.copy(dailyThreshold = 4) }
        for (invalid in listOf<(AccountSettings) -> AccountSettings>(
            { it.copy(dailyThreshold = 1) }, { it.copy(dailyThreshold = 21) },
            { it.copy(rollingThreshold = 1) }, { it.copy(rollingThreshold = 31) }, { it.copy(playlistName = " ") },
        )) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.update(accountA, invalid) } }
        }
        assertEquals(4, settings.current(accountA).dailyThreshold)
    }

    @Test fun `another account never sees or inherits previous settings`() = runBlocking<Unit> {
        settings.update(accountA) { it.copy(dailyThreshold = 4, playlistName = "Mine") }
        assertEquals(AccountSettings(), settings.current(accountB))
        settings.update(accountB) { it.copy(rollingThreshold = 6) }
        val b = settings.current(accountB)
        assertEquals(3, b.dailyThreshold)
        assertEquals(AccountSettings.DEFAULT_PLAYLIST_NAME, b.playlistName)
        assertEquals(AccountSettings(), settings.current(accountA))
        settings.clear()
        assertEquals(AccountSettings(), settings.current(accountB))
    }
}
