package io.github.reva.janeway.library

import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.TagKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagIssuesTest {
    @Test
    fun flagsTrackNumbersInTitles() {
        listOf("01 - Intro", "1. Intro", "03_Intro", "2) Intro", "1-03 Intro", "2.07 - Intro", "01 Intro", "  12 - Song")
            .forEach { assertTrue(hasTrackNumberPrefix(it, trackNumber = null), it) }
        assertTrue(hasTrackNumberPrefix("3 Intro", trackNumber = 3))
    }

    @Test
    fun acceptsTitlesThatStartWithNumbers() {
        listOf("99 Problems", "7 Rings", "1979", "2 + 2 = 5", "1-800-273-8255", "4'33\"", "22", "2.0", "Song 2")
            .forEach { assertFalse(hasTrackNumberPrefix(it, trackNumber = 1), it) }
    }

    @Test
    fun detectsMissingAndPlaceholderTags() {
        val file = file(artist = "<Unknown>", title = "Track 05", album = null, hasCover = false)
        assertEquals(
            setOf(TagIssue.MissingArtist, TagIssue.MissingTitle, TagIssue.MissingAlbum, TagIssue.NoCover),
            file.issues(),
        )
    }

    @Test
    fun cleanFileHasNoIssues() {
        assertEquals(emptySet(), file(artist = "Daft Punk", title = "One More Time", album = "Discovery", hasCover = true).issues())
    }

    @Test
    fun filtersAlbumsByIssue() {
        val counts = mapOf(TagIssue.NoCover to 2)
        assertTrue(LibraryFilter.All.matches(emptyMap()))
        assertFalse(LibraryFilter.NeedsAttention.matches(emptyMap()))
        assertTrue(LibraryFilter.NeedsAttention.matches(counts))
        assertTrue(LibraryFilter.Has(TagIssue.NoCover).matches(counts))
        assertFalse(LibraryFilter.Has(TagIssue.MissingTitle).matches(counts))
    }

    private fun file(artist: String?, title: String?, album: String?, hasCover: Boolean) = AudioFile(
        id = "f",
        fileName = "f.mp3",
        folder = "Music",
        durationMs = 1000,
        tags = buildMap {
            artist?.let { put(TagKeys.ARTIST, listOf(it)) }
            title?.let { put(TagKeys.TITLE, listOf(it)) }
            album?.let { put(TagKeys.ALBUM, listOf(it)) }
        },
        hasCover = hasCover,
    )
}
