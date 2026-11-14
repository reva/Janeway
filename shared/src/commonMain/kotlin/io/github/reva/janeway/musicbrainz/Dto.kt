package io.github.reva.janeway.musicbrainz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseSearchResponse(
    val releases: List<Release> = emptyList(),
)

@Serializable
data class Release(
    val id: String,
    val title: String,
    val score: Int? = null,
    val date: String? = null,
    val country: String? = null,
    val barcode: String? = null,
    val status: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<ArtistCredit> = emptyList(),
    @SerialName("release-group") val releaseGroup: ReleaseGroup? = null,
    @SerialName("label-info") val labelInfo: List<LabelInfo> = emptyList(),
    val media: List<Medium> = emptyList(),
    /** Only present on release lookups, not in search results. */
    @SerialName("cover-art-archive") val coverArt: CoverArtInfo? = null,
) {
    val artistName: String get() = artistCredit.joinedName()
    val totalTracks: Int get() = trackCount ?: media.sumOf { it.trackCount ?: it.tracks.size }
    val formatSummary: String
        get() = media.groupingBy { it.format ?: "Unknown" }.eachCount()
            .entries.joinToString(" + ") { (format, count) -> if (count > 1) "$count×$format" else format }
}

@Serializable
data class CoverArtInfo(val front: Boolean = false, val count: Int = 0)

@Serializable
data class ReleaseGroup(
    val id: String,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
)

@Serializable
data class LabelInfo(
    @SerialName("catalog-number") val catalogNumber: String? = null,
    val label: Label? = null,
)

@Serializable
data class Label(val id: String? = null, val name: String? = null)

@Serializable
data class Medium(
    val position: Int = 1,
    val format: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class Track(
    val id: String,
    val position: Int,
    val number: String? = null,
    val title: String,
    val length: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<ArtistCredit> = emptyList(),
    val recording: Recording,
)

@Serializable
data class Recording(
    val id: String,
    val title: String,
    val length: Int? = null,
)

@Serializable
data class ArtistCredit(
    val name: String,
    val joinphrase: String = "",
    val artist: Artist? = null,
)

@Serializable
data class Artist(val id: String, val name: String)

fun List<ArtistCredit>.joinedName(): String = joinToString("") { it.name + it.joinphrase }
