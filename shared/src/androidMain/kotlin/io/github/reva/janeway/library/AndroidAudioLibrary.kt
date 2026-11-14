package io.github.reva.janeway.library

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import io.github.reva.janeway.acoustid.AudioFingerprint
import io.github.reva.janeway.chromaprint.AudioFingerprinter
import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.FolderRef
import io.github.reva.janeway.model.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Reads and writes tags through the Storage Access Framework. The folder is a tree URI
 * from ACTION_OPEN_DOCUMENT_TREE, and each file is passed to TagLib as a file descriptor.
 */
class AndroidAudioLibrary(private val context: Context) : AudioLibrary {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val cache = ScanCache(context.filesDir)
    private val readDispatcher = Dispatchers.IO.limitedParallelism(PARALLEL_READS)

    override fun lastFolder(): FolderRef? {
        val uri = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        val granted = resolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) FolderRef(uri, prefs.getString(KEY_FOLDER_NAME, null) ?: "Music") else null
    }

    override suspend fun scan(folder: FolderRef, onProgress: (done: Int, total: Int) -> Unit): List<AudioFile> =
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_FOLDER_URI, folder.id)
                .putString(KEY_FOLDER_NAME, folder.displayName)
                .apply()
            val documents = listAudioDocuments(Uri.parse(folder.id), folder.displayName)
            val cached = cache.load(folder.id)
            val done = AtomicInteger()
            val reportEvery = maxOf(1, documents.size / 100)
            onProgress(0, documents.size)

            val scanned = documents.map { document ->
                async(readDispatcher) {
                    val hit = cached[document.documentId]
                        ?.takeIf { it.lastModified == document.lastModified && it.size == document.size }
                    val result = if (hit != null) {
                        document.toAudioFile(hit) to hit
                    } else {
                        read(document.uri, document.name, document.folder)?.let { file ->
                            file to CachedFile(
                                documentId = document.documentId,
                                lastModified = document.lastModified,
                                size = document.size,
                                durationMs = file.durationMs,
                                tags = file.tags,
                                hasCover = file.hasCover,
                            )
                        }
                    }
                    val count = done.incrementAndGet()
                    if (count % reportEvery == 0 || count == documents.size) onProgress(count, documents.size)
                    result
                }
            }.awaitAll().filterNotNull()

            cache.save(folder.id, scanned.map { it.second })
            scanned.map { it.first }
        }

    private suspend fun listAudioDocuments(tree: Uri, rootName: String): List<AudioDocument> {
        val documents = mutableListOf<AudioDocument>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to rootName))
        while (pending.isNotEmpty()) {
            val (documentId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            resolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    when {
                        cursor.getString(2) == Document.MIME_TYPE_DIR -> pending += childId to "$path/$name"
                        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS -> documents += AudioDocument(
                            documentId = childId,
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId),
                            name = name,
                            folder = path,
                            lastModified = cursor.getLong(3),
                            size = cursor.getLong(4),
                        )
                    }
                }
            }
        }
        return documents
    }

    override suspend fun reload(file: AudioFile): AudioFile = withContext(Dispatchers.IO) {
        read(Uri.parse(file.id), file.fileName, file.folder) ?: file
    }

    override suspend fun writeTags(file: AudioFile, changes: Tags): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.id)
        // TagLib replaces the whole property map on save, so merge with what is in the file.
        val current = withFd(uri, "r") { TagLib.getMetadata(it, readPictures = false) }?.propertyMap
            ?: return@withContext false
        val merged = PropertyMap(current)
        for ((key, values) in changes) {
            if (values.isEmpty()) merged.remove(key) else merged[key] = values.toTypedArray()
        }
        val saved = withFd(uri, "rw") { TagLib.savePropertyMap(it, merged) } == true
        if (saved) notifyMediaScanner(uri)
        saved
    }

    override suspend fun fingerprint(file: AudioFile): AudioFingerprint? = withContext(Dispatchers.IO) {
        val durationSeconds = file.durationMs?.let { it / 1000 }?.takeIf { it > 0 } ?: return@withContext null
        AudioFingerprinter.fingerprint(context, Uri.parse(file.id))?.let { AudioFingerprint(it, durationSeconds) }
    }

    override suspend fun writeFrontCover(file: AudioFile, image: ByteArray, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(file.id)
            val cover = Picture(data = image, description = "", pictureType = "Front Cover", mimeType = mimeType)
            val saved = withFd(uri, "rw") { TagLib.savePictures(it, arrayOf(cover)) } == true
            if (saved) notifyMediaScanner(uri)
            saved
        }

    /** Opens the document once; TagLib gets duplicated descriptors because it closes what it is given. */
    private fun read(uri: Uri, name: String, folder: String): AudioFile? {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return descriptor.use {
            // Pictures are read only to know whether there is one; the image data is dropped.
            val metadata = TagLib.getMetadata(it.dup().detachFd(), readPictures = true) ?: return null
            val properties = TagLib.getAudioProperties(it.dup().detachFd(), AudioPropertiesReadStyle.Fast)
            AudioFile(
                id = uri.toString(),
                fileName = name,
                folder = folder,
                durationMs = properties?.length,
                tags = metadata.propertyMap.mapValues { (_, values) -> values.toList() },
                hasCover = metadata.pictures.isNotEmpty(),
            )
        }
    }

    private data class AudioDocument(
        val documentId: String,
        val uri: Uri,
        val name: String,
        val folder: String,
        val lastModified: Long,
        val size: Long,
    ) {
        fun toAudioFile(cached: CachedFile) =
            AudioFile(uri.toString(), name, folder, cached.durationMs, cached.tags, cached.hasCover)
    }

    /** TagLib takes ownership of the descriptor and closes it, so it is detached here. */
    private fun <T> withFd(uri: Uri, mode: String, block: (Int) -> T): T? =
        runCatching { resolver.openFileDescriptor(uri, mode)?.detachFd() }
            .getOrNull()
            ?.let(block)

    /** Lets other apps see the new tags. Only works for files on primary storage. */
    private fun notifyMediaScanner(uri: Uri) {
        val documentId = DocumentsContract.getDocumentId(uri)
        if (!documentId.startsWith("primary:")) return
        val path = File(Environment.getExternalStorageDirectory(), documentId.removePrefix("primary:")).path
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    private companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_FOLDER_NAME = "folder_name"
        const val PARALLEL_READS = 8
        val CHILD_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "ogg", "oga", "opus", "m4a", "mp4", "aac", "wma", "wav", "aif", "aiff", "ape", "wv", "mpc", "dsf",
        )
    }
}
