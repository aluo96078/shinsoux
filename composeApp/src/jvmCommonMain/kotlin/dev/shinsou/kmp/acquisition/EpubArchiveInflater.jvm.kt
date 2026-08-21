package dev.shinsou.kmp.acquisition

import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual fun platformInflateRawDeflate(
    compressed: ByteArray,
    expectedSize: Int,
    cancellationCheckpoint: () -> Unit,
): ByteArray {
    require(expectedSize >= 0) { "Expected EPUB entry size must be non-negative" }
    val inflater = Inflater(true)
    return try {
        inflater.setInput(compressed)
        val output = ByteArray(expectedSize)
        var written = 0
        while (!inflater.finished() && written < output.size) {
            cancellationCheckpoint()
            val count = inflater.inflate(
                output,
                written,
                minOf(EPUB_INFLATE_OUTPUT_CHUNK_BYTES, output.size - written),
            )
            if (count == 0) {
                when {
                    inflater.needsDictionary() -> throw DataFormatException("EPUB DEFLATE stream needs a dictionary")
                    inflater.needsInput() -> throw DataFormatException("EPUB DEFLATE stream ended early")
                    else -> throw DataFormatException("EPUB DEFLATE stream made no progress")
                }
            }
            written += count
        }
        cancellationCheckpoint()
        if (output.isEmpty() && !inflater.finished()) {
            val sentinel = ByteArray(1)
            val count = inflater.inflate(sentinel)
            if (count != 0) throw DataFormatException("EPUB DEFLATE stream exceeds its declared size")
        }
        if (!inflater.finished() || written != expectedSize || inflater.remaining != 0) {
            throw DataFormatException("EPUB DEFLATE stream does not match its declared bounds")
        }
        output
    } finally {
        inflater.end()
    }
}

private const val EPUB_INFLATE_OUTPUT_CHUNK_BYTES: Int = 64 * 1024
