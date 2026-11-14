package io.github.reva.janeway.chromaprint

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Computes Chromaprint fingerprints like fpcalc: decodes the first two minutes with the
 * platform decoders and returns the compressed fingerprint string AcoustID expects.
 */
object AudioFingerprinter {
    private const val MAX_SECONDS = 120
    private const val TIMEOUT_US = 10_000L

    /** Returns null if the file has no audio track or Android has no decoder for it. Blocking. */
    fun fingerprint(context: Context, uri: Uri): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decode(extractor, codec)
            } finally {
                codec.release()
            }
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec): String? {
        val handle = Chromaprint.nativeNew()
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var started = false
            var samplesLeft = 0L

            fun start(format: MediaFormat): Boolean {
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                if (encoding != AudioFormat.ENCODING_PCM_16BIT) return false
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                samplesLeft = sampleRate.toLong() * channels * MAX_SECONDS
                started = Chromaprint.nativeStart(handle, sampleRate, channels)
                return started
            }

            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!started && !start(codec.outputFormat)) return null
                } else if (index >= 0) {
                    if (info.size > 0) {
                        if (!started && !start(codec.outputFormat)) return null
                        val bytes = minOf(info.size.toLong(), samplesLeft * 2).toInt()
                        Chromaprint.nativeFeed(handle, codec.getOutputBuffer(index)!!, info.offset, bytes)
                        samplesLeft -= bytes / 2
                    }
                    codec.releaseOutputBuffer(index, false)
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (endOfStream || (started && samplesLeft <= 0)) break
                }
            }
            return if (started) Chromaprint.nativeFinish(handle) else null
        } finally {
            Chromaprint.nativeFree(handle)
        }
    }
}
