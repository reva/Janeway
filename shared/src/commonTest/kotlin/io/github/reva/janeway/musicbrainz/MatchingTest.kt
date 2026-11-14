package io.github.reva.janeway.musicbrainz

import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.TagKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchingTest {
    private val release = Release(
        id = "rel",
        title = "Album",
        artistCredit = listOf(ArtistCredit("Band", artist = Artist("a1", "Band"))),
        media = listOf(
            Medium(
                position = 1,
                format = "CD",
                trackCount = 3,
                tracks = listOf(
                    track("t1", 1, "Intro", 60_000),
                    track("t2", 2, "The Long Song", 300_000),
                    track("t3", 3, "Outro", 90_000),
                ),
            ),
        ),
    )

    @Test
    fun matchesByTitleEvenWhenTrackNumbersAreWrong() {
        val files = listOf(
            file("a", "01.mp3", title = "Outro", track = 1, durationMs = 90_500),
            file("b", "02.mp3", title = "the long song", track = 2, durationMs = 299_000),
        )
        val result = matchFilesToRelease(files, release).associate { it.file.id to it.track?.track?.id }
        assertEquals("t3", result["a"])
        assertEquals("t2", result["b"])
    }

    @Test
    fun usesFileNameWhenTitleTagIsMissing() {
        val files = listOf(file("a", "03 - Intro.flac", title = null, track = null, durationMs = null))
        assertEquals("t1", matchFilesToRelease(files, release).single().track?.track?.id)
    }

    @Test
    fun leavesUnrelatedFilesUnmatched() {
        val files = listOf(file("a", "x.mp3", title = "Completely Different", track = 9, durationMs = 1_000))
        assertNull(matchFilesToRelease(files, release).single().track)
    }

    @Test
    fun acoustIdRecordingOverridesWrongTags() {
        val files = listOf(file("a", "track01.mp3", title = "Untitled", track = 1, durationMs = null))
        val result = matchFilesToRelease(files, release, recordingsByFile = mapOf("a" to setOf("rec-t3")))
        assertEquals("t3", result.single().track?.track?.id)
        assertEquals(1.0, result.single().score)
    }

    @Test
    fun ranksReleasesByAudioVotes() {
        val votes = countReleaseVotes(listOf(setOf("x", "y"), setOf("y"), setOf("y", "z")))
        val releases = listOf(Release("x", "X", trackCount = 3), Release("y", "Y", trackCount = 5), Release("z", "Z", trackCount = 3))
        assertEquals(listOf("y", "x", "z"), rankByAudioVotes(releases, votes, fileCount = 3).map { it.id })
    }

    @Test
    fun buildsPicardStyleTags() {
        val medium = release.media.single()
        val tags = tagsFor(release, TrackOnMedium(medium, medium.tracks[1]))
        assertEquals(listOf("The Long Song"), tags[TagKeys.TITLE])
        assertEquals(listOf("Band"), tags[TagKeys.ALBUM_ARTIST])
        assertEquals(listOf("2"), tags[TagKeys.TRACK_NUMBER])
        assertEquals(listOf("rec-t2"), tags[TagKeys.MB_RECORDING_ID])
        assertEquals(listOf("a1"), tags[TagKeys.MB_ALBUM_ARTIST_ID])
    }

    private fun track(id: String, position: Int, title: String, length: Int) =
        Track(id = id, position = position, title = title, length = length, recording = Recording("rec-$id", title, length))

    private fun file(id: String, name: String, title: String?, track: Int?, durationMs: Int?) = AudioFile(
        id = id,
        fileName = name,
        folder = "Music",
        durationMs = durationMs,
        tags = buildMap {
            title?.let { put(TagKeys.TITLE, listOf(it)) }
            track?.let { put(TagKeys.TRACK_NUMBER, listOf(it.toString())) }
        },
        hasCover = false,
    )
}
