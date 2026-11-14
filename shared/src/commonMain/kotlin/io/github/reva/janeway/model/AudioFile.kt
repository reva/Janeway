package io.github.reva.janeway.model

/**
 * Tags use TagLib property names (TITLE, ALBUMARTIST, MUSICBRAINZ_ALBUMID, ...),
 * which map to the native frame or atom of each format.
 */
typealias Tags = Map<String, List<String>>

fun Tags.first(key: String): String? = get(key)?.firstOrNull()?.takeIf { it.isNotBlank() }

data class FolderRef(val id: String, val displayName: String)

data class AudioFile(
    val id: String,
    val fileName: String,
    val folder: String,
    val durationMs: Int?,
    val tags: Tags,
    val hasCover: Boolean,
) {
    val title: String? get() = tags.first(TagKeys.TITLE)
    val artist: String? get() = tags.first(TagKeys.ARTIST)
    val album: String? get() = tags.first(TagKeys.ALBUM)
    val albumArtist: String? get() = tags.first(TagKeys.ALBUM_ARTIST)
    val trackNumber: Int? get() = tags.first(TagKeys.TRACK_NUMBER)?.leadingInt()
    val discNumber: Int? get() = tags.first(TagKeys.DISC_NUMBER)?.leadingInt()
}

private fun String.leadingInt(): Int? = trim().takeWhile { it.isDigit() }.toIntOrNull()

object TagKeys {
    const val TITLE = "TITLE"
    const val ARTIST = "ARTIST"
    const val ALBUM = "ALBUM"
    const val ALBUM_ARTIST = "ALBUMARTIST"
    const val TRACK_NUMBER = "TRACKNUMBER"
    const val TRACK_TOTAL = "TRACKTOTAL"
    const val DISC_NUMBER = "DISCNUMBER"
    const val DISC_TOTAL = "DISCTOTAL"
    const val DATE = "DATE"
    const val ORIGINAL_DATE = "ORIGINALDATE"
    const val LABEL = "LABEL"
    const val CATALOG_NUMBER = "CATALOGNUMBER"
    const val BARCODE = "BARCODE"
    const val MEDIA = "MEDIA"
    const val RELEASE_COUNTRY = "RELEASECOUNTRY"
    const val MB_RECORDING_ID = "MUSICBRAINZ_TRACKID"
    const val MB_RELEASE_TRACK_ID = "MUSICBRAINZ_RELEASETRACKID"
    const val MB_RELEASE_ID = "MUSICBRAINZ_ALBUMID"
    const val MB_RELEASE_GROUP_ID = "MUSICBRAINZ_RELEASEGROUPID"
    const val MB_ARTIST_ID = "MUSICBRAINZ_ARTISTID"
    const val MB_ALBUM_ARTIST_ID = "MUSICBRAINZ_ALBUMARTISTID"
}
