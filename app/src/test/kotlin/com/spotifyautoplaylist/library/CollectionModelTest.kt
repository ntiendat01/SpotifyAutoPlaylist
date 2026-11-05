package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.data.local.OutboxState
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.library.LibraryFixtures.id
import com.spotifyautoplaylist.library.LibraryModel.Segment
import org.junit.Assert.*
import org.junit.Test

class CollectionModelTest {
    private val r = LibraryFixtures.mixed().let { it.copy(
        tracks = it.tracks + LibraryFixtures.track(7) + LibraryFixtures.track(8),
        qualifications = it.qualifications + LibraryFixtures.qual(7) + LibraryFixtures.qual(8),
        outbox = it.outbox + LibraryFixtures.outbox(7, OutboxState.REMOVED_EXTERNALLY) +
            LibraryFixtures.outbox(8, OutboxState.RETRY_LATER, attempts = 2)) }
    private val s = LibraryModel.build(r, NOW)
    private fun ids(seg: Segment) = LibraryModel.collection(s, seg).map { it.trackId }.toSet()

    @Test fun `segments are disjoint and Added is PRESENT only`() {
        assertEquals(setOf(id(2)), ids(Segment.CONFIRMED))
        assertEquals(setOf(id(3), id(6), id(8)), ids(Segment.PENDING))
        assertEquals(setOf(id(4), id(7)), ids(Segment.REVIEW))
        val all = Segment.entries.flatMap { ids(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun `reasons are sanitized copy`() {
        fun t(n: Int) = s.tracks.single { it.trackId == id(n) }
        assertTrue(LibraryModel.reason(t(8), NOW).startsWith("Sẽ thử lại · 2 lần thử"))
        assertEquals("Không thể thêm bài tự động. Cần kiểm tra.", LibraryModel.reason(t(4), NOW))
        assertTrue(LibraryModel.reason(t(7), NOW).contains("không tự thêm lại"))
        assertFalse(LibraryModel.reason(t(4), NOW).contains("FORBIDDEN"))
    }
}
