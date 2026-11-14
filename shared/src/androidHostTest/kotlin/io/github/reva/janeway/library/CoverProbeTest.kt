package io.github.reva.janeway.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverProbeTest {
    @Test
    fun detectsCoversInRealFiles() {
        for (extension in listOf("mp3", "flac", "m4a")) {
            assertEquals(true, probe("with_cover.$extension"), "with_cover.$extension")
            assertEquals(false, probe("no_cover.$extension"), "no_cover.$extension")
        }
    }

    @Test
    fun findsPictureFrameAfterOtherId3Frames() {
        val tag = id3v23(frame("TIT2", 5), frame("TPE1", 3), frame("APIC", 10))
        assertEquals(true, CoverProbe.hasCover(BytesSource(tag), "mp3"))
        assertEquals(false, CoverProbe.hasCover(BytesSource(id3v23(frame("TIT2", 5))), "mp3"))
    }

    @Test
    fun leavesUnusualTagsToTagLib() {
        val unsynchronised = id3v23(frame("APIC", 4)).also { it[5] = 0x80.toByte() }
        assertNull(CoverProbe.hasCover(BytesSource(unsynchronised), "mp3"))
        val garbledFrameId = id3v23(byteArrayOf(0x7F, 0x01, 0x02, 0x03, 0, 0, 0, 1, 0, 0, 0))
        assertNull(CoverProbe.hasCover(BytesSource(garbledFrameId), "mp3"))
        assertNull(CoverProbe.hasCover(BytesSource(ByteArray(64)), "ogg"))
    }

    @Test
    fun handlesTruncatedFiles() {
        val flacWithoutBlocks = "fLaC".toByteArray()
        assertNull(CoverProbe.hasCover(BytesSource(flacWithoutBlocks), "flac"))
        assertEquals(false, CoverProbe.hasCover(BytesSource(ByteArray(3)), "mp3"))
    }

    private fun probe(resource: String): Boolean? {
        val bytes = javaClass.classLoader!!.getResourceAsStream("covers/$resource")!!.use { it.readBytes() }
        return CoverProbe.hasCover(BytesSource(bytes), resource.substringAfterLast('.'))
    }

    private fun frame(id: String, size: Int) =
        id.toByteArray() + byteArrayOf(0, 0, (size shr 8).toByte(), size.toByte(), 0, 0) + ByteArray(size)

    private fun id3v23(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { all, frame -> all + frame } + ByteArray(16)
        val size = body.size
        val syncSafe = byteArrayOf(
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncSafe + body
    }

    private class BytesSource(private val bytes: ByteArray) : ByteSource {
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, length: Int): ByteArray {
            if (position < 0 || position >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(position.toInt(), minOf(bytes.size, position.toInt() + length))
        }
    }
}
