package com.spotifyautoplaylist.spotify

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SpotifyDtoTest {
    private val json = SpotifyClient.JSON

    @Test
    fun recentlyPlayedDocumentExampleParses() {
        val page = json.decodeFromString<RecentlyPlayedPage>(
            """
            {"items":[{"played_at":"2026-11-04T07:00:00.000Z",
              "track":{"id":"$TRACK","uri":"spotify:track:$TRACK","type":"track","name":"Example",
                "artists":[{"name":"Artist"}],"is_local":false}}],
             "next":null,"cursors":{"after":"1793775600000","before":"1793775600000"}}
            """,
        )
        val item = page.items.single()
        assertEquals("2026-11-04T07:00:00.000Z", item.playedAt)
        assertEquals(TRACK, item.track?.id)
        assertEquals("spotify:track:$TRACK", item.track?.uri)
        assertEquals(listOf("Artist"), item.track?.artists?.map { it.name })
        assertEquals(false, item.track?.isLocal)
        assertNull(page.next)
        assertEquals("1793775600000", page.cursors?.before)
    }

    @Test
    fun unknownRemovedAndNullFieldsAreTolerated() {
        val page = json.decodeFromString<RecentlyPlayedPage>(
            """
            {"items":[
              {"played_at":"2026-11-04T07:00:00Z","track":{"id":"$TRACK","popularity":7,"available_markets":[]},
               "context":{"type":"playlist"}},
              {"played_at":"2026-11-04T06:00:00Z","track":null},
              {"played_at":"2026-11-04T05:00:00Z","track":{"id":null,"uri":"spotify:local:a:b:c:1","is_local":true}}
            ],"limit":50,"href":"ignored"}
            """,
        )
        assertEquals(3, page.items.size)
        assertEquals(emptyList<ArtistDto>(), page.items[0].track?.artists)
        assertNull(page.items[0].track?.album)
        assertNull(page.items[1].track)
        assertEquals(true, page.items[2].track?.isLocal)
        assertNull(page.cursors)
    }

    @Test
    fun missingRequiredFieldsFailDecoding() {
        assertThrows(SerializationException::class.java) { json.decodeFromString<RecentlyPlayedPage>("""{"next":null}""") }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<RecentlyPlayedPage>("""{"items":[{"track":{"id":"$TRACK"}}]}""")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<PlaylistItemsPage>("""{"items":[],"offset":0}""")
        }
        assertThrows(SerializationException::class.java) { json.decodeFromString<SnapshotResponse>("{}") }
    }

    @Test
    fun playlistItemsReadItemFieldNotLegacyTrack() {
        val page = json.decodeFromString<PlaylistItemsPage>(
            """
            {"items":[
              {"added_at":"2026-11-04T07:00:00Z","is_local":false,
               "item":{"id":"$TRACK","type":"track","uri":"spotify:track:$TRACK","is_local":false,"linked_from":null}},
              {"is_local":false,"item":{"id":"$OTHER","type":"episode"}},
              {"is_local":false,"track":{"id":"$OTHER","type":"track"}}
            ],"total":3,"offset":0,"limit":50,"next":null}
            """,
        )
        assertEquals(listOf(TRACK, OTHER, null), page.items.map { it.item?.id })
        assertEquals("episode", page.items[1].item?.type)
        assertNull(page.items[0].item?.linkedFrom)
        assertEquals(3, page.total)
    }

    @Test
    fun playlistMetadataParses() {
        val playlist = json.decodeFromString<PlaylistDto>(
            """
            {"id":"$PLAYLIST","name":"My Replays — AutoPlaylist","owner":{"id":"owner","display_name":"x"},
             "public":false,"collaborative":false,"snapshot_id":"snap",
             "external_urls":{"spotify":"https://open.spotify.com/playlist/$PLAYLIST"}}
            """,
        )
        assertEquals("owner", playlist.owner?.id)
        assertEquals(false, playlist.public)
        assertEquals(false, playlist.collaborative)
        assertEquals("snap", playlist.snapshotId)
        assertEquals("https://open.spotify.com/playlist/$PLAYLIST", playlist.externalUrls?.spotify)
    }

    @Test
    fun requestBodiesMatchContract() {
        assertEquals(
            """{"name":"My Replays — AutoPlaylist","description":"Auto-collected repeat plays","public":false,"collaborative":false}""",
            json.encodeToString(CreatePlaylistRequest.serializer(), CreatePlaylistRequest("My Replays — AutoPlaylist", "Auto-collected repeat plays")),
        )
        assertEquals(
            """{"uris":["spotify:track:$TRACK"]}""",
            json.encodeToString(AddItemsRequest.serializer(), AddItemsRequest(listOf("spotify:track:$TRACK"))),
        )
    }

    private companion object {
        const val TRACK = "0000000000000000000001"
        const val OTHER = "0000000000000000000002"
        const val PLAYLIST = "1111111111111111111111"
    }
}
