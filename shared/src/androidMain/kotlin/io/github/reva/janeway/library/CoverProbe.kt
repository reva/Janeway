package io.github.reva.janeway.library

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random access to a file's bytes. */
internal interface ByteSource {
    val size: Long

    /** Up to [length] bytes from [position]; fewer at the end of the file. */
    fun read(position: Long, length: Int): ByteArray
}

internal class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= size) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) <= 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/**
 * Tells whether a file embeds a picture by reading only tag and container headers, so
 * scanning a library does not load every cover image. Covers MP3 (ID3v2), FLAC and MP4/M4A;
 * returns null for other formats and for anything unusual, so the caller can ask TagLib.
 */
internal object CoverProbe {
    fun hasCover(source: ByteSource, extension: String): Boolean? = try {
        when (extension.lowercase()) {
            "mp3" -> id3HasPicture(source)
            "flac" -> flacHasPicture(source)
            "m4a", "mp4" -> mp4HasCover(source)
            else -> null
        }
    } catch (e: IOException) {
        null
    }

    private fun id3HasPicture(source: ByteSource): Boolean? {
        val header = source.read(0, 10)
        if (header.size < 10 || !header.startsWith("ID3")) return false
        val major = header[3].toInt()
        val flags = header.u8(5)
        // Unsynchronised tags can hide frame headers; leave those to TagLib.
        if (major !in 2..4 || flags and 0x80 != 0) return null
        val end = 10L + syncSafe(header, 6)
        var position = 10L
        if (major >= 3 && flags and 0x40 != 0) {
            val extended = source.read(position, 4)
            if (extended.size < 4) return null
            position += if (major == 4) syncSafe(extended, 0) else extended.u32(0) + 4
        }
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        while (position + headerLength <= end) {
            val frame = source.read(position, headerLength)
            if (frame.size < headerLength || frame[0].toInt() == 0) return false
            val id = String(frame, 0, idLength, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) return null
            if (id == "APIC" || id == "PIC") return true
            val size = when (major) {
                2 -> frame.u24(3).toLong()
                3 -> frame.u32(4)
                else -> syncSafe(frame, 4)
            }
            position += headerLength + size
        }
        return false
    }

    private fun flacHasPicture(source: ByteSource): Boolean? {
        var position = 0L
        val start = source.read(0, 10)
        if (start.size >= 10 && start.startsWith("ID3")) {
            position = 10L + syncSafe(start, 6) + if (start.u8(5) and 0x10 != 0) 10 else 0
        }
        if (!source.read(position, 4).startsWith("fLaC")) return null
        position += 4
        while (true) {
            val block = source.read(position, 4)
            if (block.size < 4) return null
            val type = block.u8(0) and 0x7F
            if (type == PICTURE_BLOCK) return true
            if (type == INVALID_BLOCK) return null
            if (block.u8(0) and 0x80 != 0) return false
            position += 4 + block.u24(1)
        }
    }

    private fun mp4HasCover(source: ByteSource): Boolean? {
        val moov = findAtom(source, 0, source.size, "moov") ?: return null
        val meta = findAtom(source, moov.dataStart, moov.end, "udta")
            ?.let { findAtom(source, it.dataStart, it.end, "meta") }
            ?: findAtom(source, moov.dataStart, moov.end, "meta")
            ?: return false
        // iTunes-style meta is a full box with 4 bytes of version and flags before its children.
        val ilst = findAtom(source, meta.dataStart + 4, meta.end, "ilst")
            ?: findAtom(source, meta.dataStart, meta.end, "ilst")
            ?: return false
        return findAtom(source, ilst.dataStart, ilst.end, "covr") != null
    }

    private class Atom(val dataStart: Long, val end: Long)

    private fun findAtom(source: ByteSource, start: Long, end: Long, type: String): Atom? {
        var position = start
        while (position + 8 <= end) {
            val header = source.read(position, 16)
            if (header.size < 8) return null
            var size = header.u32(0)
            var headerLength = 8
            when (size) {
                0L -> size = end - position
                1L -> {
                    if (header.size < 16) return null
                    size = header.u64(8)
                    headerLength = 16
                }
            }
            if (size < headerLength) return null
            if (String(header, 4, 4, Charsets.ISO_8859_1) == type) return Atom(position + headerLength, position + size)
            position += size
        }
        return null
    }

    private const val PICTURE_BLOCK = 6
    private const val INVALID_BLOCK = 127

    private fun ByteArray.startsWith(text: String) =
        size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
    private fun ByteArray.u24(index: Int) = (u8(index) shl 16) or (u8(index + 1) shl 8) or u8(index + 2)
    private fun ByteArray.u32(index: Int) =
        (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()
    private fun ByteArray.u64(index: Int) = (u32(index) shl 32) or u32(index + 4)

    private fun syncSafe(bytes: ByteArray, index: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 7) or (bytes.u8(index + i).toLong() and 0x7F) }
}
