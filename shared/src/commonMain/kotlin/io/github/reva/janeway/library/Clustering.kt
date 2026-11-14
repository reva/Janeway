package io.github.reva.janeway.library

import io.github.reva.janeway.model.AudioFile

data class AlbumCluster(
    val key: String,
    val album: String?,
    val artist: String?,
    val files: List<AudioFile>,
)

/**
 * Groups files into probable albums: by album artist and album tag when present,
 * otherwise by folder. Placeholders such as "Unknown Artist" count as missing.
 */
fun clusterByAlbum(files: List<AudioFile>): List<AlbumCluster> =
    files.groupBy { file ->
        val album = file.knownAlbum?.normalized()
        if (album != null) "tag:${file.knownArtist?.normalized().orEmpty()}|$album"
        else "folder:${file.folder}"
    }.map { (key, group) ->
        val sorted = group.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.fileName }))
        AlbumCluster(
            key = key,
            album = sorted.mostCommon { it.knownAlbum } ?: sorted.first().folder.substringAfterLast('/'),
            artist = sorted.mostCommon { it.knownArtist },
            files = sorted,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { "${it.artist.orEmpty()} ${it.album.orEmpty()}" })

private val AudioFile.knownAlbum: String? get() = album?.takeUnless { isPlaceholder(it, ALBUM_PLACEHOLDERS) }

private val AudioFile.knownArtist: String?
    get() = listOf(albumArtist, artist).firstOrNull { !isPlaceholder(it, ARTIST_PLACEHOLDERS) }

private fun String.normalized(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

private fun <T> List<T>.mostCommon(selector: (T) -> String?): String? =
    mapNotNull(selector).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
