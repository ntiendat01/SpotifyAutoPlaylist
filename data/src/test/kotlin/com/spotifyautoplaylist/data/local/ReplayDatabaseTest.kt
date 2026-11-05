package com.spotifyautoplaylist.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.LocalDataReset
import com.spotifyautoplaylist.data.settings.SettingsRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplayDatabaseTest {
    private lateinit var db: ReplayDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            db.accounts().upsert(AccountEntity(USER_A, null, 1L))
            db.accounts().upsert(AccountEntity(USER_B, null, 1L))
        }
    }

    @After fun close() = db.close()

    @Test fun `schema matches 05-DATABASE keys, indexes and foreign keys`() {
        assertEquals(setOf("account", "tracks", "play_events", "qualification", "managed_playlist",
            "playlist_outbox", "sync_state"), strings("SELECT name FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").toSet())
        assertEquals(listOf("spotify_user_id", "track_id", "played_at_ms"), primaryKey("play_events"))
        assertEquals(listOf("spotify_user_id", "track_id"), primaryKey("tracks"))
        assertEquals(listOf("spotify_user_id", "track_id"), primaryKey("qualification"))
        assertEquals(listOf("spotify_user_id", "playlist_id", "track_id"), primaryKey("playlist_outbox"))
        assertEquals(listOf("spotify_user_id"), primaryKey("managed_playlist"))
        assertEquals(listOf("spotify_user_id"), primaryKey("sync_state"))
        assertTrue(indexColumns("play_events").contains(listOf("spotify_user_id", "played_at_ms")))
        assertTrue(uniqueIndexColumns("managed_playlist").contains(listOf("playlist_id")))
        assertEquals("1", strings("PRAGMA foreign_keys").single())
        for (table in listOf("tracks", "managed_playlist", "sync_state")) assertEquals(setOf("account"), parents(table))
        for (table in listOf("play_events", "qualification", "playlist_outbox")) assertEquals(setOf("tracks"), parents(table))
    }

    @Test fun `TC-005 TC-010 re-importing the same page counts events once and keeps progress`() = runBlocking<Unit> {
        val history = db.history()
        val events = listOf(event(USER_A, "t1", 1_000), event(USER_A, "t1", 2_000), event(USER_A, "t2", 2_000))
        val progress = SyncStateEntity(USER_A, lastPageCursor = "c1", coverageStatus = CoverageStatus.PARTIAL_BOOTSTRAP)
        assertEquals(3, history.importPage(USER_A, listOf(track(USER_A, "t1"), track(USER_A, "t2")), events, progress))
        assertEquals(0, history.importPage(USER_A, listOf(track(USER_A, "t1", name = "Renamed"), track(USER_A, "t2")), events, progress))
        assertEquals(2, history.countEvents(USER_A, "t1", 0, Long.MAX_VALUE))
        assertEquals("Renamed", history.track(USER_A, "t1")!!.name)
        assertEquals(listOf(1_000L, 2_000L), history.eventTimes(USER_A, "t1"))
        assertEquals("c1", db.syncState().get(USER_A)!!.lastPageCursor)
        assertEquals(listOf("PARTIAL_BOOTSTRAP"), strings("SELECT coverage_status FROM sync_state"))
    }

    @Test fun `TX-01 failure rolls back tracks, events and progress together`() = runBlocking<Unit> {
        val history = db.history()
        val orphan = listOf(event(USER_A, "t1", 1_000), event(USER_A, "missing-track", 2_000))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { history.importPage(USER_A, listOf(track(USER_A, "t1")), orphan, SyncStateEntity(USER_A, lastPageCursor = "c1")) }
        }
        assertNull(history.track(USER_A, "t1"))
        assertTrue(history.allEvents(USER_A).isEmpty())
        assertNull(db.syncState().get(USER_A))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { history.importPage(USER_A, listOf(track(USER_B, "t1")), emptyList(), SyncStateEntity(USER_A)) }
        }
    }

    @Test fun `counts are scoped per account and use a half-open interval`() = runBlocking<Unit> {
        val history = db.history()
        history.importPage(USER_A, listOf(track(USER_A, "t1")), listOf(event(USER_A, "t1", 1_000), event(USER_A, "t1", 2_000)), SyncStateEntity(USER_A))
        history.importPage(USER_B, listOf(track(USER_B, "t1")), listOf(event(USER_B, "t1", 1_500)), SyncStateEntity(USER_B))
        assertEquals(1, history.countEvents(USER_A, "t1", 1_000, 2_000))
        assertEquals(2, history.countEvents(USER_A, "t1", 1_000, 2_001))
        assertEquals(1, history.countEvents(USER_B, "t1", 0, Long.MAX_VALUE))
        assertEquals(2, history.allEvents(USER_A).size)
    }

    @Test fun `TX-03 qualification is monotonic and creates one pending outbox row`() = runBlocking<Unit> {
        db.history().importPage(USER_A, listOf(track(USER_A, "t1"), track(USER_A, "t2")), emptyList(), SyncStateEntity(USER_A))
        val qualifications = db.qualifications()
        assertTrue(qualifications.qualify(QualificationEntity(USER_A, "t1", 10, QualifiedBy.DAILY), "pl1"))
        assertFalse(qualifications.qualify(QualificationEntity(USER_A, "t1", 99, QualifiedBy.BOTH), "pl1"))
        assertTrue(qualifications.qualify(QualificationEntity(USER_A, "t2", 11, QualifiedBy.ROLLING), null))
        assertEquals(listOf(10L, 11L), qualifications.all(USER_A).map { it.qualifiedAtMs })
        assertEquals(QualifiedBy.DAILY, qualifications.all(USER_A).first().qualifiedBy)
        val pending = db.playlists().outboxIn(USER_A, listOf(OutboxState.PENDING))
        assertEquals(listOf("t1"), pending.map { it.trackId })
        assertEquals(0, pending.single().attemptCount)
        assertTrue(db.playlists().outboxIn(USER_B, OutboxState.entries).isEmpty())
    }

    @Test fun `managed playlist is never silently replaced and outbox state is durable`() = runBlocking<Unit> {
        val playlists = db.playlists()
        playlists.insertManaged(ManagedPlaylistEntity(USER_A, "pl1", null, "My Replays", 5))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { playlists.insertManaged(ManagedPlaylistEntity(USER_A, "pl2", null, "Other", 6)) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { playlists.insertManaged(ManagedPlaylistEntity(USER_B, "pl1", null, "Same remote", 6)) }
        }
        assertFalse(playlists.managed(USER_A)!!.ownershipVerified)
        db.history().importPage(USER_A, listOf(track(USER_A, "t1")), emptyList(), SyncStateEntity(USER_A))
        db.qualifications().qualify(QualificationEntity(USER_A, "t1", 10, QualifiedBy.DAILY), "pl1")
        val item = playlists.outboxItem(USER_A, "pl1", "t1")!!
        assertEquals(1, playlists.updateOutbox(item.copy(state = OutboxState.VERIFY_REMOTE, attemptCount = 1, lastAttemptMs = 20)))
        assertEquals(OutboxState.VERIFY_REMOTE, playlists.outboxItem(USER_A, "pl1", "t1")!!.state)
        assertEquals(listOf("VERIFY_REMOTE"), strings("SELECT state FROM playlist_outbox"))
    }

    @Test fun `account reset step removes every table and settings and is repeatable`() = runBlocking<Unit> {
        db.history().importPage(USER_A, listOf(track(USER_A, "t1")), listOf(event(USER_A, "t1", 1)), SyncStateEntity(USER_A))
        db.qualifications().qualify(QualificationEntity(USER_A, "t1", 10, QualifiedBy.DAILY), "pl1")
        db.playlists().insertManaged(ManagedPlaylistEntity(USER_A, "pl1", null, "My Replays", 5))
        val dir = Files.createTempDirectory("settings").toFile()
        try {
            val settings = SettingsRepository.create(File(dir, "settings.preferences_pb"))
            val key = AccountKey(USER_A, "a".repeat(64))
            settings.update(key) { it.copy(dailyThreshold = 4) }
            val reset = LocalDataReset(db, settings)
            reset.reset()
            reset.reset()
            for (table in listOf("account", "tracks", "play_events", "qualification", "managed_playlist", "playlist_outbox", "sync_state")) {
                assertEquals("$table not empty", "0", strings("SELECT COUNT(*) FROM $table").single())
            }
            assertEquals(3, settings.current(key).dailyThreshold)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun strings(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun primaryKey(table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
            buildList { while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("pk")) > 0) add(c.getInt(c.getColumnIndexOrThrow("pk")) to c.getString(c.getColumnIndexOrThrow("name"))) }
        }.sortedBy { it.first }.map { it.second }

    private fun indexColumns(table: String, uniqueOnly: Boolean = false): List<List<String>> =
        db.openHelper.readableDatabase.query("PRAGMA index_list($table)").use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (uniqueOnly && c.getInt(c.getColumnIndexOrThrow("unique")) == 0) continue
                    if (c.getString(c.getColumnIndexOrThrow("origin")) == "pk") continue
                    add(c.getString(c.getColumnIndexOrThrow("name")))
                }
            }
        }.map { index -> strings("SELECT name FROM pragma_index_info('$index') ORDER BY seqno") }

    private fun uniqueIndexColumns(table: String) = indexColumns(table, uniqueOnly = true)

    private fun parents(table: String): Set<String> =
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_list($table)").use { c ->
            buildSet {
                while (c.moveToNext()) {
                    assertEquals("CASCADE", c.getString(c.getColumnIndexOrThrow("on_delete")))
                    add(c.getString(c.getColumnIndexOrThrow("table")))
                }
            }
        }

    private fun track(user: String, id: String, name: String = "Song") = TrackEntity(user, id, "spotify:track:$id", name, "Artist", null, null)
    private fun event(user: String, track: String, at: Long) = PlayEventEntity(user, track, at, 5)

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
    }
}
