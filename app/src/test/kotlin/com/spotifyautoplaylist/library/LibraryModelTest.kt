package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.data.local.CoverageStatus
import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.library.LibraryFixtures.H
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.library.LibraryFixtures.outbox
import com.spotifyautoplaylist.library.LibraryFixtures.plays
import com.spotifyautoplaylist.library.LibraryFixtures.qual
import com.spotifyautoplaylist.library.LibraryFixtures.rows
import com.spotifyautoplaylist.library.LibraryFixtures.track
import org.junit.Assert.*
import org.junit.Test

class LibraryModelTest {
    private fun snap(r: LibraryRows = LibraryFixtures.mixed()) = LibraryModel.build(r, NOW)
    private fun LibrarySnapshot.t(n: Int) = tracks.single { it.trackId == LibraryFixtures.id(n) }

    @Test fun `statuses follow outbox priority and Added only when PRESENT`() {
        val s = snap()
        assertEquals(TrackStatus.NEAR, s.t(0).status)          // 2 today, daily 3
        assertEquals(TrackStatus.NEAR, s.t(1).status)          // 4 in 72h, rolling 5
        assertEquals(TrackStatus.ADDED, s.t(2).status)
        assertEquals(TrackStatus.ADDING, s.t(3).status)
        assertEquals(TrackStatus.NEEDS_REVIEW, s.t(4).status)
        assertEquals(TrackStatus.TRACKING, s.t(5).status)
        assertEquals(TrackStatus.VERIFYING, s.t(6).status)
        assertNull(s.t(3).addedAtMs)
        assertEquals(1, s.confirmedCount)
    }

    @Test fun `counts use Vietnam day and inclusive-exclusive 72h window`() {
        // 11h ago = 01:00 today VN; 13h ago = 23:00 yesterday VN; exactly 72h is outside (now-72h, now].
        val s = snap(rows(tracks = listOf(track(0)), events = plays(0, 11, 13, 72, 71.9)))
        assertEquals(1, s.t(0).todayCount)
        assertEquals(3, s.t(0).rolling72hCount)
    }

    @Test fun `confirmed count is unknown without playlist or verification, zero only after success`() {
        assertNull(snap(rows(playlist = null)).confirmedCount)
        assertNull(snap(rows(status = SyncRunStatus())).confirmedCount)
        assertEquals(0, snap(rows()).confirmedCount)
        assertEquals(NOW - H, snap(rows()).lastVerifiedAtMs)
    }

    @Test fun `rows on an old playlist are ignored`() {
        val s = snap(rows(tracks = listOf(track(0)), quals = listOf(qual(0)),
            outbox = listOf(outbox(0, OutboxState.PRESENT, NOW, playlist = "OLDPLAYLIST00000000000"))))
        assertEquals(TrackStatus.QUALIFIED, s.t(0).status)
        assertEquals(0, s.confirmedCount)
    }

    @Test fun `almost there excludes qualified and sorts by remaining, max three`() {
        val near = LibraryModel.almostThere(snap())
        assertEquals(listOf(LibraryFixtures.id(0), LibraryFixtures.id(1)), near.map { it.trackId }.sorted())
        val many = snap(rows(tracks = (0..5).map { track(it) }, events = (0..5).flatMap { plays(it, *Array(it % 3 + 1) { h -> h + 1 }) }))
        val top = LibraryModel.almostThere(many)
        assertEquals(3, top.size)
        assertTrue(top.zipWithNext().all { (a, b) -> a.remaining(3, 5) <= b.remaining(3, 5) })
    }

    @Test fun `newly collected only lists remotely confirmed tracks`() {
        assertEquals(listOf(LibraryFixtures.id(2)), LibraryModel.newlyCollected(snap()).map { it.trackId })
    }

    @Test fun `seven Vietnam days end today and deduplicate events`() {
        val r = rows(tracks = listOf(track(0)), events = plays(0, 1, 1, 13, 24 * 6 + 11, 24 * 7 + 1))
        val d = snap(r).days
        assertEquals(7, d.size)
        assertEquals(LibraryModel.day(NOW), d.last().day)
        assertEquals("T5", d.last().label) // 2026-11-05 is a Thursday
        assertEquals(1, d.last().count)
        assertEquals(1, d[5].count)
        assertEquals(1, d.first().count)
        assertEquals(3, d.sumOf { it.count })
    }

    @Test fun `coverage maps partial states and unknown`() {
        assertEquals(Coverage.PARTIAL, snap(rows(coverage = CoverageStatus.PARTIAL_BOOTSTRAP)).coverage)
        assertEquals(Coverage.PARTIAL, snap(rows(coverage = CoverageStatus.HISTORY_INCOMPLETE)).coverage)
        assertEquals(Coverage.UNKNOWN, snap(rows(coverage = CoverageStatus.UNKNOWN)).coverage)
    }

    @Test fun `links only for valid ids and sync copy never shows raw codes`() {
        assertEquals("https://open.spotify.com/track/${LibraryFixtures.id(1)}", snap().t(1).spotifyUrl)
        assertNull(LibraryModel.trackUrl("spotify:track:bad"))
        assertEquals("Không có mạng · Hiển thị dữ liệu trên máy", SyncCopy.headline(SyncRunStatus(outcome = "NETWORK_ERROR"), NOW))
        assertFalse(SyncCopy.headline(SyncRunStatus(outcome = "WEIRD_INTERNAL", errorCode = "X"), NOW).contains("WEIRD"))
    }
}
