package io.github.reva.janeway.musicbrainz

import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.TagKeys
import io.github.reva.janeway.model.Tags
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TrackOnMedium(val medium: Medium, val track: Track)

data class TrackMatch(
    val file: AudioFile,
    val track: TrackOnMedium?,
    val score: Double,
)

private const val MIN_SCORE = 0.35

/**
 * Assigns each file to at most one track of the release, best pairs first.
 * Score combines title similarity, disc/track position and length; a recording
 * identified by AcoustID for that file counts as a certain match.
 */
fun matchFilesToRelease(
    files: List<AudioFile>,
    release: Release,
    recordingsByFile: Map<String, Set<String>> = emptyMap(),
): List<TrackMatch> {
    val tracks = release.media.flatMap { medium -> medium.tracks.map { TrackOnMedium(medium, it) } }
    val candidates = files.flatMap { file ->
        val identified = recordingsByFile[file.id].orEmpty()
        tracks.map { track ->
            val score = if (track.track.recording.id in identified) 1.0 else score(file, track)
            Triple(file, track, score)
        }
    }.filter { it.third >= MIN_SCORE }.sortedByDescending { it.third }

    val assigned = mutableMapOf<String, TrackMatch>()
    val usedTracks = mutableSetOf<String>()
    for ((file, track, score) in candidates) {
        if (file.id in assigned || track.track.id in usedTracks) continue
        assigned[file.id] = TrackMatch(file, track, score)
        usedTracks += track.track.id
    }
    return files.map { assigned[it.id] ?: TrackMatch(it, null, 0.0) }
}

private fun score(file: AudioFile, candidate: TrackOnMedium): Double {
    val (medium, track) = candidate
    val titleScore = file.title?.let { similarity(it, track.title) }
        ?: similarity(file.fileName.substringBeforeLast('.'), track.title)
    val positionScore = when {
        file.trackNumber == null -> 0.5
        file.trackNumber == track.position && (file.discNumber ?: 1) == medium.position -> 1.0
        else -> 0.0
    }
    val trackLength = track.length ?: track.recording.length
    val lengthScore = if (file.durationMs != null && trackLength != null) {
        1.0 - min(abs(file.durationMs - trackLength) / 30_000.0, 1.0)
    } else {
        0.5
    }
    return 0.5 * titleScore + 0.3 * positionScore + 0.2 * lengthScore
}

/** Normalized Levenshtein similarity on lowercase letters and digits. A file name containing the title counts as a match. */
internal fun similarity(a: String, b: String): Double {
    val x = a.lowercase().filter { it.isLetterOrDigit() }
    val y = b.lowercase().filter { it.isLetterOrDigit() }
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    if (y.length >= 4 && x.contains(y)) return 0.9
    val longest = max(x.length, y.length)
    return 1.0 - levenshtein(x, y).toDouble() / longest
}

private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        previous = current.also { current = previous }
    }
    return previous[b.length]
}

/** Puts releases whose total track count equals the number of files first, otherwise keeps the search order. */
fun rankReleases(releases: List<Release>, fileCount: Int): List<Release> =
    releases.sortedByDescending { it.totalTracks == fileCount }

/** Orders releases by how many files AcoustID placed on them, then by matching track count. */
fun rankByAudioVotes(releases: List<Release>, votes: Map<String, Int>, fileCount: Int): List<Release> =
    releases.sortedWith(
        compareByDescending<Release> { votes[it.id] ?: 0 }.thenByDescending { it.totalTracks == fileCount },
    )

fun countReleaseVotes(releaseIdsPerFile: Collection<Set<String>>): Map<String, Int> =
    releaseIdsPerFile.flatten().groupingBy { it }.eachCount()

/** Tags Picard would write for this track, using the same tag names. */
fun tagsFor(release: Release, match: TrackOnMedium): Tags {
    val (medium, track) = match
    val trackCredit = track.artistCredit.ifEmpty { release.artistCredit }
    val label = release.labelInfo.firstOrNull()
    return buildMap {
        put(TagKeys.TITLE, track.title)
        put(TagKeys.ARTIST, trackCredit.joinedName())
        put(TagKeys.ALBUM, release.title)
        put(TagKeys.ALBUM_ARTIST, release.artistName)
        put(TagKeys.TRACK_NUMBER, track.position.toString())
        put(TagKeys.TRACK_TOTAL, (medium.trackCount ?: medium.tracks.size).toString())
        put(TagKeys.DISC_NUMBER, medium.position.toString())
        put(TagKeys.DISC_TOTAL, release.media.size.toString())
        put(TagKeys.DATE, release.date)
        put(TagKeys.ORIGINAL_DATE, release.releaseGroup?.firstReleaseDate)
        put(TagKeys.LABEL, label?.label?.name)
        put(TagKeys.CATALOG_NUMBER, label?.catalogNumber)
        put(TagKeys.BARCODE, release.barcode)
        put(TagKeys.MEDIA, medium.format)
        put(TagKeys.RELEASE_COUNTRY, release.country)
        put(TagKeys.MB_RECORDING_ID, track.recording.id)
        put(TagKeys.MB_RELEASE_TRACK_ID, track.id)
        put(TagKeys.MB_RELEASE_ID, release.id)
        put(TagKeys.MB_RELEASE_GROUP_ID, release.releaseGroup?.id)
        putList(TagKeys.MB_ARTIST_ID, trackCredit.mapNotNull { it.artist?.id })
        putList(TagKeys.MB_ALBUM_ARTIST_ID, release.artistCredit.mapNotNull { it.artist?.id })
    }
}

private fun MutableMap<String, List<String>>.put(key: String, value: String?) {
    if (!value.isNullOrBlank()) put(key, listOf(value))
}

private fun MutableMap<String, List<String>>.putList(key: String, values: List<String>) {
    if (values.isNotEmpty()) put(key, values)
}
