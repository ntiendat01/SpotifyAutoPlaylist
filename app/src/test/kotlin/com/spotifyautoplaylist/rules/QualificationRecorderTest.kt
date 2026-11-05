package com.spotifyautoplaylist.rules

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifyautoplaylist.auth.AccountKey
import com.spotifyautoplaylist.data.local.AccountEntity
import com.spotifyautoplaylist.data.local.ManagedPlaylistEntity
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.local.PlayEventEntity
import com.spotifyautoplaylist.data.local.QualificationEntity
import com.spotifyautoplaylist.data.local.QualifiedBy
import com.spotifyautoplaylist.data.local.ReplayDatabase
import com.spotifyautoplaylist.data.local.SyncStateEntity
import com.spotifyautoplaylist.data.local.TrackEntity
import com.spotifyautoplaylist.data.settings.SettingsRepository
import com.spotifyautoplaylist.spotify.rules.Thresholds
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QualificationRecorderTest {
    private lateinit var db: ReplayDatabase
    private lateinit var dir: File
    private lateinit var settings: SettingsRepository
    private lateinit var recorder: QualificationRecorder

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReplayDatabase::class.java)
            .allowMainThreadQueries().build()
        dir = Files.createTempDirectory("settings").toFile()
        settings = SettingsRepository.create(File(dir, "settings.preferences_pb"))
        recorder = QualificationRecorder(db, settings)
        runBlocking { db.accounts().upsert(AccountEntity(A, null, 1)); db.accounts().upsert(AccountEntity(B, null, 1)) }
    }

    @After fun close() { db.close(); dir.deleteRecursively() }

    private fun vn(t: String) = LocalDateTime.parse(t).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli()
    private fun track(user: String, id: String) = TrackEntity(user, id, "spotify:track:$id", "Song", "Artist", null, null)

    private suspend fun plays(user: String, trackId: String, vararg times: Long) {
        db.history().importPage(user, listOf(track(user, trackId)), times.map { PlayEventEntity(user, trackId, it, 1) }, SyncStateEntity(user))
    }

    private suspend fun link(user: String, pl: String) = db.playlists().insertManaged(ManagedPlaylistEntity(user, pl, null, "My Replays", 1))
    private suspend fun quals(user: String) = db.qualifications().all(user)
    private suspend fun outbox(user: String) = db.playlists().outboxIn(user, OutboxState.entries)

    private val day = vn("2026-11-04T08:00")
    private val hour = 3_600_000L
    /** Clears TASK-055 min-listen between consecutive plays (any track). */
    private val listen = 60_000L

    @Test fun `tx03 new qualification and pending outbox row are written together`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T1, day, day + hour, day + 2 * hour)
        plays(A, T2, day + listen, day + hour + listen)
        val r = recorder.evaluate(A, Thresholds())
        assertEquals(listOf(T1), r.newlyQualified)
        assertEquals(listOf(QualificationEntity(A, T1, day + 2 * hour, QualifiedBy.DAILY)), quals(A))
        val row = outbox(A).single()
        assertEquals(T1 to OutboxState.PENDING, row.trackId to row.state)
        assertEquals("plA", row.playlistId)
        assertEquals(0, row.attemptCount)
    }

    @Test fun `repeated evaluation is idempotent and keeps outbox progress`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T1, day, day + hour, day + 2 * hour)
        recorder.evaluate(A, Thresholds())
        val item = db.playlists().outboxItem(A, "plA", T1)!!
        db.playlists().updateOutbox(item.copy(state = OutboxState.PRESENT, attemptCount = 1, addedAtMs = 9))
        plays(A, T1, day, day + hour, day + 2 * hour) // re-imported page (TC-005/TC-010)
        repeat(3) { assertTrue(recorder.evaluate(A, Thresholds()).newlyQualified.isEmpty()) }
        assertEquals(1, quals(A).size)
        assertEquals(OutboxState.PRESENT, outbox(A).single().state)
        assertEquals(9L, outbox(A).single().addedAtMs)
    }

    @Test fun `removed externally is never requeued`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T1, day, day + hour, day + 2 * hour)
        recorder.evaluate(A, Thresholds())
        db.playlists().updateOutbox(db.playlists().outboxItem(A, "plA", T1)!!.copy(state = OutboxState.REMOVED_EXTERNALLY))
        plays(A, T1, day + 3 * hour)
        recorder.evaluate(A, Thresholds(daily = 2))
        assertEquals(OutboxState.REMOVED_EXTERNALLY, outbox(A).single().state)
        assertEquals(day + 2 * hour, quals(A).single().qualifiedAtMs)
    }

    @Test fun `batch is atomic - a failing row rolls back all qualifications and outbox rows`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T1, day)
        val good = QualificationEntity(A, T1, day, QualifiedBy.DAILY)
        val noTrack = QualificationEntity(A, "missing", day, QualifiedBy.DAILY) // FK violation
        try {
            db.qualifications().qualifyBatch(A, listOf(good, noTrack), "plA"); fail("expected FK failure")
        } catch (expected: Exception) { }
        assertTrue(quals(A).isEmpty())
        assertTrue(outbox(A).isEmpty())
    }

    @Test fun `cross-account rows are rejected and accounts stay isolated`() = runBlocking<Unit> {
        link(A, "plA"); link(B, "plB")
        plays(A, T1, day, day + hour, day + 2 * hour)
        plays(B, T1, day, day + hour)
        recorder.evaluate(A, Thresholds()); recorder.evaluate(B, Thresholds())
        assertEquals(listOf(T1), quals(A).map { it.trackId })
        assertTrue(quals(B).isEmpty())
        assertTrue(outbox(B).isEmpty())
        assertEquals(setOf("plA"), outbox(A).map { it.playlistId }.toSet())
        try {
            db.qualifications().qualifyBatch(B, listOf(QualificationEntity(A, T1, 1, QualifiedBy.DAILY)), "plB"); fail()
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun `qualification before playlist link is backfilled into outbox once linked`() = runBlocking<Unit> {
        plays(A, T1, day, day + hour, day + 2 * hour)
        val r = recorder.evaluate(A, Thresholds())
        assertEquals(false, r.playlistLinked)
        assertEquals(1, quals(A).size)
        assertTrue(outbox(A).isEmpty())
        link(A, "plA")
        assertTrue(recorder.evaluate(A, Thresholds()).newlyQualified.isEmpty())
        assertEquals(listOf(T1 to OutboxState.PENDING), outbox(A).map { it.trackId to it.state })
    }

    @Test fun `every qualification has an outbox row when linked`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T1, day, day + hour, day + 2 * hour)
        plays(A, T2, day + listen, day + 30 * hour, day + 50 * hour, day + 60 * hour, day + 70 * hour)
        recorder.evaluate(A, Thresholds())
        assertEquals(quals(A).map { it.trackId }.toSet(), outbox(A).map { it.trackId }.toSet())
    }

    @Test fun `tc009 lowering daily threshold adds new qualifications without removing`() = runBlocking<Unit> {
        link(A, "plA")
        val key = AccountKey(A, "a".repeat(64))
        plays(A, T1, day, day + hour, day + 2 * hour)
        plays(A, T2, day + listen, day + hour + listen)
        recorder.evaluate(key)
        assertEquals(listOf(T1), quals(A).map { it.trackId })
        val r = recorder.changeThresholds(key, daily = 2, rolling = 5)
        assertEquals(listOf(T2), r.newlyQualified)
        assertEquals(2, settings.current(key).dailyThreshold)
        assertEquals(day + 2 * hour, quals(A).first { it.trackId == T1 }.qualifiedAtMs) // T1 unchanged
        assertEquals(setOf(T1, T2), outbox(A).map { it.trackId }.toSet())
    }

    @Test fun `raising thresholds never deletes qualifications or outbox rows`() = runBlocking<Unit> {
        link(A, "plA")
        val key = AccountKey(A, "a".repeat(64))
        plays(A, T1, day, day + hour, day + 2 * hour)
        recorder.evaluate(key)
        val r = recorder.changeThresholds(key, daily = 20, rolling = 30)
        assertTrue(r.newlyQualified.isEmpty())
        assertEquals(listOf(T1), quals(A).map { it.trackId })
        assertEquals(QualifiedBy.DAILY, quals(A).single().qualifiedBy)
        assertEquals(listOf(T1), outbox(A).map { it.trackId })
        // A new track below the raised threshold does not qualify.
        plays(A, T2, day + listen, day + hour + listen, day + 2 * hour + listen)
        assertTrue(recorder.evaluate(key).newlyQualified.isEmpty())
    }

    @Test fun `invalid threshold change writes nothing`() = runBlocking<Unit> {
        val key = AccountKey(A, "a".repeat(64))
        try { recorder.changeThresholds(key, daily = 1, rolling = 5); fail() } catch (expected: IllegalArgumentException) { }
        assertEquals(3, settings.current(key).dailyThreshold)
    }

    @Test fun `tc008 late history qualifies at original time and outbox is in qualification order`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T2, day, day + hour, day + 2 * hour)          // qualifies at 10:00 Nov 4
        recorder.evaluate(A, Thresholds())
        val old = vn("2026-10-01T08:00")
        plays(A, T1, old, old + hour, old + 2 * hour)           // late-imported older history
        // Same VN-day slots as T2 but +listen so min-listen gaps stay valid; track-ID tiebreak unchanged at equal qualifiedAt.
        plays(A, T3, day + listen, day + hour + listen, day + 2 * hour + listen)
        val r = recorder.evaluate(A, Thresholds())
        assertEquals(listOf(T1, T3), r.newlyQualified)
        assertEquals(old + 2 * hour, quals(A).first().qualifiedAtMs)
        assertEquals(listOf(T1, T2, T3), db.qualifications().outboxInQualificationOrder(A, "plA").map { it.trackId })
    }

    @Test fun `rolling and both are persisted`() = runBlocking<Unit> {
        link(A, "plA")
        plays(A, T1, day, day + 30 * hour, day + 50 * hour, day + 60 * hour, day + 70 * hour) // max 2/day
        val q = quals(A).ifEmpty { recorder.evaluate(A, Thresholds()); quals(A) }.single()
        assertEquals(QualifiedBy.ROLLING, q.qualifiedBy)
        assertEquals(day + 70 * hour, q.qualifiedAtMs)
        // Offset from T1's first play at [day] so all five T2 plays clear the min-listen gate.
        plays(A, T2, day + listen, day + 2 * listen, day + 3 * listen, day + 4 * listen, day + 5 * listen)
        recorder.evaluate(A, Thresholds(daily = 5, rolling = 5))
        assertEquals(QualifiedBy.BOTH, quals(A).first { it.trackId == T2 }.qualifiedBy)
    }


    @Test fun `min-listen short gap does not qualify and re-eval stays idempotent`() = runBlocking<Unit> {
        link(A, "plA")
        // Three same-day plays for T1 but the 2nd/3rd are <30s after the previous global play → not countable.
        plays(A, T1, day, day + 10_000, day + 20_000)
        assertTrue(recorder.evaluate(A, Thresholds()).newlyQualified.isEmpty())
        assertTrue(quals(A).isEmpty())
        // Re-import same page + re-eval: still empty (idempotent); adding a real 3rd countable play qualifies once.
        plays(A, T1, day, day + 10_000, day + 20_000)
        assertTrue(recorder.evaluate(A, Thresholds()).newlyQualified.isEmpty())
        plays(A, T1, day + listen, day + hour, day + 2 * hour)
        val r = recorder.evaluate(A, Thresholds())
        assertEquals(listOf(T1), r.newlyQualified)
        repeat(2) { assertTrue(recorder.evaluate(A, Thresholds()).newlyQualified.isEmpty()) }
        assertEquals(1, quals(A).size)
    }

    @Test fun `no events or empty batch writes nothing`() = runBlocking<Unit> {
        link(A, "plA")
        assertTrue(recorder.evaluate(A, Thresholds()).newlyQualified.isEmpty())
        assertTrue(db.qualifications().qualifyBatch(A, emptyList(), "plA").isEmpty())
        assertTrue(quals(A).isEmpty() && outbox(A).isEmpty())
    }

    companion object {
        const val A = "user-a"
        const val B = "user-b"
        const val T1 = "0000000000000000000001"
        const val T2 = "0000000000000000000002"
        const val T3 = "0000000000000000000003"
    }
}
