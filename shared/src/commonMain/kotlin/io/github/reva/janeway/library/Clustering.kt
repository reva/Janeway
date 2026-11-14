package io.github.reva.janeway.library

import io.github.reva.janeway.model.AudioFile

data class AlbumCluster(
    val key: String,
    val album: String?,
    val artist: String?,
    val files: List<AudioFile>,
)

const val VARIOUS_ARTISTS = "Various Artists"

/**
 * Groups files into probable albums by album tag and album artist. Without an album artist,
 * files of one album stay together when they share a track artist; with several track artists
 * (a compilation) they are grouped per folder instead. Files without an album tag are grouped
 * by folder. Placeholders such as "Unknown Artist" count as missing.
 */
fun clusterByAlbum(files: List<AudioFile>): List<AlbumCluster> =
    files.groupBy { it.knownAlbum?.normalized() }.flatMap { (album, sameAlbum) ->
        if (album == null) {
            sameAlbum.groupBy { it.folder }.map { (folder, group) -> "folder:$folder" to group }
        } else {
            sameAlbum.groupBy { it.knownAlbumArtist?.normalized() }.flatMap { (albumArtist, group) ->
                val trackArtists = group.trackArtists()
                when {
                    albumArtist != null -> listOf("tag:$albumArtist|$album" to group)
                    trackArtists.size <= 1 -> listOf("tag:${trackArtists.firstOrNull().orEmpty()}|$album" to group)
                    else -> group.groupBy { it.folder }.map { (folder, inFolder) -> "tag:folder=$folder|$album" to inFolder }
                }
            }
        }
    }.map { (key, group) -> toCluster(key, group) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { "${it.artist.orEmpty()} ${it.album.orEmpty()}" })

private fun toCluster(key: String, group: List<AudioFile>): AlbumCluster {
    val sorted = group.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.fileName }))
    val artist = sorted.mostCommon { it.knownAlbumArtist }
        ?: if (sorted.trackArtists().size > 1) VARIOUS_ARTISTS else sorted.mostCommon { it.knownTrackArtist }
    return AlbumCluster(
        key = key,
        album = sorted.mostCommon { it.knownAlbum } ?: sorted.first().folder.substringAfterLast('/'),
        artist = artist,
        files = sorted,
    )
}

private val AudioFile.knownAlbum: String? get() = album?.takeUnless { isPlaceholder(it, ALBUM_PLACEHOLDERS) }

private val AudioFile.knownAlbumArtist: String? get() = albumArtist?.takeUnless { isPlaceholder(it, ARTIST_PLACEHOLDERS) }

private val AudioFile.knownTrackArtist: String? get() = artist?.takeUnless { isPlaceholder(it, ARTIST_PLACEHOLDERS) }

private fun List<AudioFile>.trackArtists(): Set<String> = mapNotNullTo(mutableSetOf()) { it.knownTrackArtist?.normalized() }

private fun String.normalized(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

private fun <T> List<T>.mostCommon(selector: (T) -> String?): String? =
    mapNotNull(selector).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
