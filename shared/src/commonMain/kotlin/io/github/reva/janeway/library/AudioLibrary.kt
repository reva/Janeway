package io.github.reva.janeway.library

import io.github.reva.janeway.acoustid.AudioFingerprint
import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.FolderRef
import io.github.reva.janeway.model.Tags

/** Platform access to audio files and their tags. */
interface AudioLibrary {
    /** The folder chosen in an earlier session, if access to it is still granted. */
    fun lastFolder(): FolderRef?

    /** Reads all audio files below [folder]. [onProgress] gets total 0 while files are still being listed. */
    suspend fun scan(folder: FolderRef, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<AudioFile>

    suspend fun reload(file: AudioFile): AudioFile

    /**
     * Sets the given tags and keeps all others. An empty list removes a tag.
     * Returns false if the file could not be written.
     */
    suspend fun writeTags(file: AudioFile, changes: Tags): Boolean

    /** Chromaprint fingerprint of the first two minutes, or null if the format cannot be decoded. */
    suspend fun fingerprint(file: AudioFile): AudioFingerprint?

    /** Replaces all embedded pictures with this front cover. */
    suspend fun writeFrontCover(file: AudioFile, image: ByteArray, mimeType: String): Boolean
}
