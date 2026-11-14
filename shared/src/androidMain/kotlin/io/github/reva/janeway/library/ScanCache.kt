package io.github.reva.janeway.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class CachedFile(
    val documentId: String,
    val lastModified: Long,
    val size: Long,
    val durationMs: Int?,
    val tags: Map<String, List<String>>,
    val hasCover: Boolean,
)

@Serializable
private data class CacheContent(val version: Int, val folderId: String, val files: List<CachedFile>)

/** Tags of the last scanned folder, so unchanged files are not read again on the next scan. */
internal class ScanCache(directory: File) {
    private val file = File(directory, "scan-cache.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(folderId: String): Map<String, CachedFile> =
        runCatching { json.decodeFromString<CacheContent>(file.readText()) }.getOrNull()
            ?.takeIf { it.version == VERSION && it.folderId == folderId }
            ?.files
            ?.associateBy { it.documentId }
            .orEmpty()

    fun save(folderId: String, files: List<CachedFile>) {
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(CacheContent(VERSION, folderId, files)))
        temp.renameTo(file)
    }

    private companion object {
        /** Raise when CachedFile changes, so older caches are read again instead of misread. */
        const val VERSION = 2
    }
}
