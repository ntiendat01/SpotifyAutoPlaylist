package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.library.LibraryFixtures.id
import com.spotifyautoplaylist.library.LibraryFixtures.plays
import com.spotifyautoplaylist.library.LibraryFixtures.rows
import com.spotifyautoplaylist.library.LibraryFixtures.track
import com.spotifyautoplaylist.library.LibraryModel.StatusFilter
import com.spotifyautoplaylist.library.LibraryModel.TimeFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFilterTest {
    private val s = LibraryModel.build(LibraryFixtures.mixed(), NOW)
    private fun ids(q: String = "", t: TimeFilter = TimeFilter.ALL, st: StatusFilter = StatusFilter.ALL) =
        LibraryModel.history(s, q, t, st).map { it.trackId }.toSet()

    @Test fun `time filters use today and rolling counts`() {
        assertEquals((0..6).map(::id).toSet(), ids())
        assertEquals(setOf(0, 2, 3, 4, 6).map(::id).toSet(), ids(t = TimeFilter.TODAY))
        assertEquals(setOf(0, 1, 2, 3, 4, 6).map(::id).toSet(), ids(t = TimeFilter.H72))
    }

    @Test fun `status filters keep Added strictly remote`() {
        assertEquals(setOf(id(0), id(1)), ids(st = StatusFilter.NEAR))
        assertEquals(setOf(id(2)), ids(st = StatusFilter.ADDED))
    }

    @Test fun `search is case and accent insensitive on title and artist`() {
        assertEquals(setOf(id(1)), ids("bai thu b"))
        assertEquals(setOf(id(3)), ids("NGHỆ SĨ THỬ 3"))
        assertEquals(emptySet<String>(), ids("không có"))
        val d = LibraryModel.build(rows(tracks = listOf(track(0, "Đường xa")), events = plays(0, 1)), NOW)
        assertEquals(1, LibraryModel.history(d, "duong", TimeFilter.ALL, StatusFilter.ALL).size)
    }

    @Test fun `one row per track, latest play first, summary counts window plays`() {
        val r = LibraryModel.history(s, "", TimeFilter.H72, StatusFilter.ALL)
        assertEquals(r.size, r.map { it.trackId }.distinct().size)
        assertEquals(r.sortedByDescending { it.lastPlayedAtMs }, r)
        assertEquals(2 + 4 + 3 + 4 + 3 + 3, LibraryModel.plays(r, TimeFilter.H72))
    }
}
