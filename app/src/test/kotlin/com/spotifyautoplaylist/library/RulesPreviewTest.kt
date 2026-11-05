package com.spotifyautoplaylist.library

import com.spotifyautoplaylist.library.LibraryFixtures.NOW
import com.spotifyautoplaylist.library.LibraryFixtures.id
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesPreviewTest {
    private val s = LibraryModel.build(LibraryFixtures.mixed(), NOW)
    private fun ids(d: Int, r: Int) = LibraryModel.rulesPreview(s, d, r).newlyQualified.map { it.trackId }.toSet()

    @Test fun `saved rules add nothing new`() {
        assertEquals(emptySet<String>(), ids(3, 5))
        assertEquals(4, LibraryModel.rulesPreview(s, 3, 5).alreadyQualified)
    }

    @Test fun `lower daily uses any past Vietnam day`() = assertEquals(setOf(id(0), id(1)), ids(2, 5))

    @Test fun `lower rolling uses 72h window only`() = assertEquals(setOf(id(1)), ids(3, 4))

    @Test fun `raising thresholds lists no removals and never already qualified tracks`() {
        assertEquals(emptySet<String>(), ids(20, 30))
        val q = s.tracks.filter { it.qualifiedBy != null }.map { it.trackId }
        assert(ids(2, 2).none { it in q })
    }
}
