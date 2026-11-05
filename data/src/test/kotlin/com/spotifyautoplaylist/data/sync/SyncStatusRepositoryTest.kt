package com.spotifyautoplaylist.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.LocalDataReset
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.settings.SettingsRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncStatusRepositoryTest {
    private val dir: File = Files.createTempDirectory("status").toFile()
    private val a = AccountKey("user-a", "a".repeat(64))
    private val b = AccountKey("user-b", "b".repeat(64))

    @After fun clean() { dir.deleteRecursively() }

    @Test fun `round trip, account-tagged, cleared by local reset`() = runBlocking {
        val repo = SyncStatusRepository.create(File(dir, "s.preferences_pb"))
        val status = SyncRunStatus("RATE_LIMITED", 10, 5, 40, "THROTTLED", 1, 2, 3)
        repo.record(a, status)
        assertEquals(status, repo.current(a))
        assertEquals(SyncRunStatus(), repo.current(b))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java).build()
        LocalDataReset(db, SettingsRepository.create(File(dir, "x.preferences_pb")), repo).reset()
        db.close()
        assertEquals(SyncRunStatus(), repo.current(a))
    }
}
