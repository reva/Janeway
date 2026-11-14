package io.github.reva.janeway.chromaprint

import java.nio.ByteBuffer

internal object Chromaprint {
    init {
        System.loadLibrary("janeway_chromaprint")
    }

    @JvmStatic external fun nativeNew(): Long

    @JvmStatic external fun nativeStart(handle: Long, sampleRate: Int, channels: Int): Boolean

    @JvmStatic external fun nativeFeed(handle: Long, buffer: ByteBuffer, offsetBytes: Int, sizeBytes: Int): Boolean

    @JvmStatic external fun nativeFinish(handle: Long): String?

    @JvmStatic external fun nativeFree(handle: Long)
}
