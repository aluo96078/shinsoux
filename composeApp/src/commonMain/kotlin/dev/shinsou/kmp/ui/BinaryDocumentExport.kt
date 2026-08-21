package dev.shinsou.kmp.ui

import kotlinx.coroutines.CancellationException

/**
 * A synchronous chunk consumer used by platform document exporters.
 *
 * The chunk is only valid for the duration of [write]; implementations must consume it before
 * returning instead of retaining or mutating it.
 */
public fun interface BinaryDocumentExportSink {
    public fun write(chunk: ByteArray)
}

/**
 * Repeatable binary output which can be written without collecting the complete document.
 *
 * [writeTo] must emit exactly [expectedByteSize] bytes and return the emitted byte count. Platform
 * hosts validate both counts before publishing the document.
 */
public interface BinaryDocumentExportSource {
    public val expectedByteSize: Long
    public fun writeTo(sink: BinaryDocumentExportSink): Long
}

/** Defensive in-memory adapter retained for existing small binary-export callers. */
public class ByteArrayBinaryDocumentExportSource(contents: ByteArray) : BinaryDocumentExportSource {
    private val bytes: ByteArray = contents.copyOf()

    override val expectedByteSize: Long get() = bytes.size.toLong()

    override fun writeTo(sink: BinaryDocumentExportSink): Long {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(BINARY_DOCUMENT_EXPORT_CHUNK_BYTES, bytes.size - offset)
            sink.write(bytes.copyOfRange(offset, offset + count))
            offset += count
        }
        return offset.toLong()
    }
}

/**
 * Validates a streaming source while forwarding its chunks. A length mismatch is an export
 * failure, never a partially successful document.
 */
public fun BinaryDocumentExportSource.writeCheckedTo(sink: BinaryDocumentExportSink): Long {
    require(expectedByteSize >= 0L) { "Binary document size must be non-negative" }
    var forwarded = 0L
    val reported = writeTo(BinaryDocumentExportSink { chunk ->
        require(chunk.isNotEmpty()) { "Binary document source emitted an empty chunk" }
        if (forwarded > expectedByteSize - chunk.size.toLong()) {
            throw IllegalStateException("Binary document source exceeded its declared size")
        }
        sink.write(chunk)
        forwarded += chunk.size
    })
    check(reported == forwarded) { "Binary document source reported an inconsistent byte count" }
    check(forwarded == expectedByteSize) { "Binary document source did not emit its declared size" }
    return forwarded
}

/** Explicit compatibility materialization for small documents and legacy platform hosts. */
public fun BinaryDocumentExportSource.copyToByteArray(
    maximumBytes: Long = DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES,
): ByteArray {
    require(maximumBytes >= 0L) { "Binary document compatibility limit must be non-negative" }
    require(expectedByteSize >= 0L) { "Binary document size must be non-negative" }
    check(expectedByteSize <= maximumBytes && expectedByteSize <= Int.MAX_VALUE.toLong()) {
        "Binary document is too large for the ByteArray compatibility path"
    }
    val output = ByteArray(expectedByteSize.toInt())
    var offset = 0
    writeCheckedTo(BinaryDocumentExportSink { chunk ->
        chunk.copyInto(output, destinationOffset = offset)
        offset += chunk.size
    })
    return output
}

/**
 * Runs a platform write and best-effort removes its partial destination on every failure.
 * This is primarily needed by document providers that cannot offer an atomic replace operation.
 */
internal fun writeBinaryDocumentWithFailureCleanup(
    source: BinaryDocumentExportSource,
    write: (BinaryDocumentExportSource) -> Unit,
    discardPartial: () -> Unit,
): Boolean {
    var complete = false
    return try {
        write(source)
        complete = true
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    } finally {
        if (!complete) runCatching(discardPartial)
    }
}

public const val DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES: Long = 8L * 1024 * 1024

private const val BINARY_DOCUMENT_EXPORT_CHUNK_BYTES: Int = 64 * 1024
