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

    private fun file(id: String, folder: String, artist: String, album: String) = AudioFile(
        id = id,
        fileName = "$id.mp3",
        folder = folder,
        durationMs = null,
        tags = mapOf(TagKeys.ARTIST to listOf(artist), TagKeys.ALBUM to listOf(album)),
        hasCover = false,
    )
}
