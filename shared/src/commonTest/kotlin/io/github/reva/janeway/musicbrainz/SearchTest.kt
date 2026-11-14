package io.github.reva.janeway.musicbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchTest {
    private val id = "0b2ab1dc-4c05-4ab1-9c62-5a1c1e2f6a3b"

    @Test
    fun parsesReleaseLinksAndBareIds() {
        assertEquals(id, parseReleaseId("https://musicbrainz.org/release/$id"))
        assertEquals(id, parseReleaseId("https://musicbrainz.org/release/$id/discids "))
        assertEquals(id, parseReleaseId(id.uppercase()))
    }

    @Test
    fun ignoresAlbumTitlesAndOtherLinks() {
        assertNull(parseReleaseId("Get Up With It"))
        assertNull(parseReleaseId("https://musicbrainz.org/artist/$id"))
    }

    @Test
    fun ranksReleasesWithMatchingTotalTrackCountFirst() {
        val oneDisc = Release(id = "a", title = "Workin'", trackCount = 6)
        val twoDiscs = Release(id = "b", title = "Get Up With It", trackCount = 8)
        val other = Release(id = "c", title = "Get Up With It", trackCount = 10)
        assertEquals(listOf("b", "a", "c"), rankReleases(listOf(oneDisc, twoDiscs, other), fileCount = 8).map { it.id })
    }
}
