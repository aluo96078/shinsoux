package dev.shinsou.kmp.acquisition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformInflateRawDeflate(
    compressed: ByteArray,
    expectedSize: Int,
    cancellationCheckpoint: () -> Unit,
): ByteArray {
    require(expectedSize >= 0) { "Expected EPUB entry size must be non-negative" }
    if (compressed.isEmpty()) throw IllegalArgumentException("EPUB DEFLATE stream is empty")
    val output = ByteArray(expectedSize)
    val destination = if (output.isEmpty()) ByteArray(1) else output
    val overflowProbe = ByteArray(1)
    memScoped {
        val stream = alloc<z_stream>()
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        compressed.usePinned { inputPinned ->
            destination.usePinned { outputPinned ->
                overflowProbe.usePinned { probePinned ->
                    stream.next_in = inputPinned.addressOf(0).reinterpret()
                    stream.avail_in = compressed.size.convert()
                    val initialized = inflateInit2(stream.ptr, -MAX_WBITS)
                    if (initialized != Z_OK) {
                        throw IllegalArgumentException("Could not initialize EPUB DEFLATE stream")
                    }
                    try {
                        var written = 0
                        var result = Z_OK
                        while (result != Z_STREAM_END) {
                            cancellationCheckpoint()
                            val remaining = expectedSize - written
                            val capacity = if (remaining > 0) {
                                minOf(EPUB_INFLATE_OUTPUT_CHUNK_BYTES, remaining)
                            } else {
                                1
                            }
                            stream.next_out = if (remaining > 0) {
                                outputPinned.addressOf(written).reinterpret()
                            } else {
                                probePinned.addressOf(0).reinterpret()
                            }
                            stream.avail_out = capacity.convert()
                            val inputBefore = stream.avail_in.toLong()
                            result = inflate(stream.ptr, Z_NO_FLUSH)
                            val produced = capacity - stream.avail_out.toInt()
                            if (remaining <= 0 && produced > 0) {
                                throw IllegalArgumentException("EPUB DEFLATE stream exceeds its declared size")
                            }
                            written += produced
                            cancellationCheckpoint()
                            if (result != Z_OK && result != Z_STREAM_END) {
                                throw IllegalArgumentException("EPUB DEFLATE stream is malformed")
                            }
                            if (result == Z_OK && produced == 0 && stream.avail_in.toLong() == inputBefore) {
                                throw IllegalArgumentException("EPUB DEFLATE stream made no progress")
                            }
                        }
                        if (written != expectedSize || stream.total_out.toLong() != expectedSize.toLong() ||
                            stream.avail_in.toLong() != 0L
                        ) {
                            throw IllegalArgumentException("EPUB DEFLATE stream does not match its declared bounds")
                        }
                    } finally {
                        inflateEnd(stream.ptr)
                    }
                }
            }
        }
    }
    return output
}

private const val EPUB_INFLATE_OUTPUT_CHUNK_BYTES: Int = 64 * 1024
