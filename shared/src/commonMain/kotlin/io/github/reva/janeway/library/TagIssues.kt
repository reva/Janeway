package io.github.reva.janeway.library

import io.github.reva.janeway.model.AudioFile

enum class TagIssue(val label: String) {
    MissingArtist("no artist"),
    MissingTitle("no title"),
    MissingAlbum("no album"),
    TrackNumberInTitle("track no. in title"),
    NoCover("no cover"),
}

fun AudioFile.issues(): Set<TagIssue> = buildSet {
    if (isPlaceholder(artist, ARTIST_PLACEHOLDERS)) add(TagIssue.MissingArtist)
    if (isPlaceholder(title, TITLE_PLACEHOLDERS) || title?.matches(numberedTrack) == true) add(TagIssue.MissingTitle)
    if (isPlaceholder(album, ALBUM_PLACEHOLDERS)) add(TagIssue.MissingAlbum)
    if (title?.let { hasTrackNumberPrefix(it, trackNumber) } == true) add(TagIssue.TrackNumberInTitle)
    if (!hasCover) add(TagIssue.NoCover)
}

/** How many files of the album have each issue. */
fun AlbumCluster.issueCounts(): Map<TagIssue, Int> =
    files.flatMap { it.issues() }.groupingBy { it }.eachCount()

sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object NeedsAttention : LibraryFilter
    data class Has(val issue: TagIssue) : LibraryFilter
}

fun LibraryFilter.matches(counts: Map<TagIssue, Int>): Boolean = when (this) {
    LibraryFilter.All -> true
    LibraryFilter.NeedsAttention -> counts.isNotEmpty()
    is LibraryFilter.Has -> issue in counts
}

internal val ARTIST_PLACEHOLDERS = setOf("unknown", "unknown artist", "no artist", "artist", "?")
private val TITLE_PLACEHOLDERS = setOf("unknown", "untitled", "no title", "title", "?")
internal val ALBUM_PLACEHOLDERS = setOf("unknown", "unknown album", "no album", "untitled", "album", "?")

/** Titles like "Track 5", "Track05" or "AudioTrack 01" that ripping tools write when they know nothing. */
private val numberedTrack = Regex("""(?i)\s*(audio\s*)?(track|titel|piste|pista)\s*\d+\s*""")

internal fun isPlaceholder(value: String?, placeholders: Set<String>): Boolean {
    if (value.isNullOrBlank()) return true
    val normalized = value.trim().lowercase().removeSurrounding("<", ">").removeSurrounding("[", "]")
    return normalized in placeholders
}

/** "01 - Intro", "1. Intro", "03_Intro", "2) Intro" */
private val numberWithSeparator = Regex("""^\s*\d{1,3}\s*(-|\.|_|\)|])\s*(?=[^\d\s])""")

/** "1-03 Intro", "2.07 - Intro": disc and track number */
private val discAndTrack = Regex("""^\s*\d{1,2}[-.]\d{2,3}(\s|[-._])+(?=\D)""")

/** "01 Intro", or "3 Intro" when it is track 3 */
private val numberWithSpace = Regex("""^\s*(\d{1,3})\s+(?=[^\d\s])""")

/**
 * Detects a track number left in front of the title. A number followed only by a space
 * counts when it is zero-padded or equals the track number, so "99 Problems" or "7 Rings" pass.
 */
internal fun hasTrackNumberPrefix(title: String, trackNumber: Int?): Boolean {
    if (numberWithSeparator.containsMatchIn(title) || discAndTrack.containsMatchIn(title)) return true
    val number = numberWithSpace.find(title)?.groupValues?.get(1) ?: return false
    return (number.length > 1 && number.startsWith('0')) || number.toInt() == trackNumber
}
