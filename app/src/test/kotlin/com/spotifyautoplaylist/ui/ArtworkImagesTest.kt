package com.spotifyautoplaylist.ui

import android.graphics.Bitmap
import com.spotifyautoplaylist.library.LibraryFixtures
import com.spotifyautoplaylist.library.LibraryModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkImagesTest {
    @Test fun `only anonymous HTTPS Spotify CDN artwork is permitted`() {
        assertTrue(ArtworkImages.permitted("https://i.scdn.co/image/synthetic"))
        for (url in listOf(null, "http://i.scdn.co/image/x", "https://i.scdn.co.evil.test/image/x",
            "https://evil.test/image/x", "file:///tmp/image", "https://user@i.scdn.co/image/x",
            "https://i.scdn.co:8765/image/x", "https://i.scdn.co/image/x#fragment", "invalid")) {
            assertFalse("must reject $url", ArtworkImages.permitted(url))
        }
    }

    @Test fun `stream cap rejects oversized responses including unknown content lengths`() {
        val limit = ArtworkImages.MAX_BYTES
        assertEquals(limit, ArtworkImages.readBounded(ByteArrayInputStream(ByteArray(limit)))!!.size)
        assertNull(ArtworkImages.readBounded(ByteArrayInputStream(ByteArray(limit + 1))))
        assertNull(ArtworkImages.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun `decoded images fit the memory dimensions budget`() {
        val image = Bitmap.createBitmap(1025, 769, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, output)
        val decoded = ArtworkImages.decode(output.toByteArray())!!
        assertTrue("decoded width ${decoded.width}", decoded.width <= 512)
        assertTrue("decoded height ${decoded.height}", decoded.height <= 512)
    }

    @Test fun `read model exposes only stored artwork without changing counts or status`() {
        val rows = LibraryFixtures.mixed()
        val url = "https://i.scdn.co/image/synthetic"
        val before = LibraryModel.build(rows, LibraryFixtures.NOW)
        val after = LibraryModel.build(rows.copy(tracks = rows.tracks.map { it.copy(imageUrl = url) }), LibraryFixtures.NOW)
        assertTrue(after.tracks.all { it.artworkUrl == url })
        assertEquals(before.tracks.map { it.copy(artworkUrl = url) }, after.tracks)
        assertEquals(before.confirmedCount, after.confirmedCount)
    }
}
