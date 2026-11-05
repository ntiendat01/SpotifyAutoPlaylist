package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.data.sync.SyncRunStatus
import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.library.LibraryFixtures.id
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun `text has no ids names urls or raw messages`() {
        val rows = LibraryFixtures.mixed().copy(status = SyncRunStatus(outcome = "FORBIDDEN", errorCode = "token=abc https://x", finishedAtMs = NOW))
        val text = Diagnostics.text(LibraryModel.build(rows, NOW), "1.0")
        assertTrue(text, "error=OTHER" in text && "outcome=FORBIDDEN" in text)
        (0..6).forEach { assertFalse(id(it) in text) }
        listOf("Bài thử", "Nghệ sĩ", "Replay thử", "spotify:", "http", "token").forEach { assertFalse(it, it in text) }
        assertTrue("added=1,pending=1,verifying=1,review=1" in text)
    }

    @Test fun `relink detection and code sanitizing`() {
        assertTrue(Diagnostics.relinkRequired(SyncRunStatus(outcome = "PARTIAL", errorCode = "RELINK_REQUIRED")))
        assertFalse(Diagnostics.relinkRequired(SyncRunStatus(outcome = "SUCCESS")))
        assertEquals("-", Diagnostics.code(null))
        assertEquals("OTHER", Diagnostics.code("bad code"))
    }
}
