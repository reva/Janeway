package io.github.reva.janeway.library

import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.TagKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClusteringTest {
    @Test
    fun placeholderArtistsDoNotSplitAnAlbum() {
        val clusters = clusterByAlbum(
            listOf(file("a", "Music/X", artist = "Unknown Artist", album = "Album"), file("b", "Music/X", artist = "<unknown>", album = "Album")),
        )
        assertEquals(1, clusters.size)
        assertNull(clusters.single().artist)
    }

    @Test
    fun placeholderAlbumsAreGroupedByFolder() {
        val clusters = clusterByAlbum(
            listOf(file("a", "Music/X", artist = "A", album = "Unknown Album"), file("b", "Music/Y", artist = "A", album = "Unknown Album")),
        )
        assertEquals(listOf("X", "Y"), clusters.mapNotNull { it.album }.sorted())
    }

    @Test
    fun compilationWithoutAlbumArtistStaysTogether() {
        val clusters = clusterByAlbum(
            listOf(
                file("a", "Music/Hits", artist = "Artist A", album = "Hits"),
                file("b", "Music/Hits", artist = "Artist B", album = "Hits"),
                file("c", "Music/Hits", artist = "Artist C", album = "Hits"),
            ),
        )
        assertEquals(3, clusters.single().files.size)
        assertEquals(VARIOUS_ARTISTS, clusters.single().artist)
    }

    @Test
    fun singleArtistAlbumSpansDiscFolders() {
        val clusters = clusterByAlbum(
            listOf(file("a", "Music/Blue/CD1", artist = "A", album = "Blue"), file("b", "Music/Blue/CD2", artist = "A", album = "Blue")),
        )
        assertEquals(2, clusters.single().files.size)
        assertEquals("A", clusters.single().artist)
    }

    @Test
    fun sameTitleByDifferentArtistsInDifferentFoldersIsSplit() {
        val clusters = clusterByAlbum(
            listOf(file("a", "Music/A", artist = "A", album = "Greatest Hits"), file("b", "Music/B", artist = "B", album = "Greatest Hits")),
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun albumArtistKeepsTracksTogether() {
        val clusters = clusterByAlbum(
            listOf(
                file("a", "Music/X", artist = "Artist A", album = "Hits", albumArtist = "Various Artists"),
                file("b", "Music/Y", artist = "Artist B", album = "Hits", albumArtist = "Various Artists"),
            ),
        )
        assertEquals(2, clusters.single().files.size)
    }

    private fun file(id: String, folder: String, artist: String, album: String, albumArtist: String? = null) = AudioFile(
        id = id,
        fileName = "$id.mp3",
        folder = folder,
        durationMs = null,
        tags = buildMap {
            put(TagKeys.ARTIST, listOf(artist))
            put(TagKeys.ALBUM, listOf(album))
            albumArtist?.let { put(TagKeys.ALBUM_ARTIST, listOf(it)) }
        },
        hasCover = false,
    )
}
