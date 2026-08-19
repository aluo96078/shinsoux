package dev.shinsou.kmp.ui

import kotlin.math.min

/**
 * Memory boundary applied before a platform picker reads a selected document.
 *
 * [maxBytesByExtension] is useful when one picker accepts both images and archives: images can
 * retain their smaller per-page limit while archives use [maxBytesPerFile].
 */
class ImportedDocumentLimits(
    val maxBytesPerFile: Long,
    val maxTotalBytes: Long = maxBytesPerFile,
    maxBytesByExtension: Map<String, Long> = emptyMap(),
) {
    val maxBytesByExtension: Map<String, Long> = maxBytesByExtension
        .mapKeys { (extension, _) -> extension.trimStart('.').lowercase() }

    init {
        require(maxBytesPerFile in 1..Int.MAX_VALUE.toLong()) {
            "The per-file import limit must fit in memory."
        }
        require(maxTotalBytes > 0) { "The total import limit must be positive." }
        require(this.maxBytesByExtension.values.all { it in 1..Int.MAX_VALUE.toLong() }) {
            "Every extension import limit must fit in memory."
        }
    }

    fun maxBytesFor(name: String): Long {
        val extension = name.substringAfterLast('.', "").lowercase()
        return maxBytesByExtension[extension] ?: maxBytesPerFile
    }
}

class ImportedDocumentReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A conservative fallback for snapshot/backup documents without a narrower format limit. */
val DEFAULT_IMPORTED_DOCUMENT_LIMITS: ImportedDocumentLimits = ImportedDocumentLimits(
    maxBytesPerFile = 64L * 1024L * 1024L,
)

/**
 * Reads exactly the metadata-declared byte count without ever growing the destination buffer.
 *
 * Platforms supply a small `InputStream`/`NSFileHandle` adapter through [read]. A one-byte probe
 * after the declared payload catches a file/provider that grew between metadata lookup and read.
 */
internal fun readBoundedImportedBytes(
    name: String,
    declaredSize: Long?,
    limits: ImportedDocumentLimits,
    previouslyAcceptedBytes: Long = 0,
    read: (destination: ByteArray, offset: Int, length: Int) -> Int,
): ByteArray {
    val displayName = name.trim().ifBlank { "document" }
    if (declaredSize == null || declaredSize < 0) {
        throw ImportedDocumentReadException(
            "Unable to determine the size of “$displayName”. Choose a file with readable size information.",
        )
    }
    if (previouslyAcceptedBytes < 0 || previouslyAcceptedBytes > limits.maxTotalBytes) {
        throw ImportedDocumentReadException("The selected files exceed the total import limit.")
    }

    val fileLimit = limits.maxBytesFor(displayName)
    if (declaredSize > fileLimit) {
        throw ImportedDocumentReadException(
            "“$displayName” exceeds the ${formatImportByteLimit(fileLimit)} per-file import limit.",
        )
    }
    if (declaredSize > limits.maxTotalBytes - previouslyAcceptedBytes) {
        throw ImportedDocumentReadException(
            "The selected files exceed the ${formatImportByteLimit(limits.maxTotalBytes)} total import limit.",
        )
    }

    val expectedSize = declaredSize.toInt()
    val result = ByteArray(expectedSize)
    var offset = 0
    while (offset < expectedSize) {
        val requested = min(IMPORT_READ_CHUNK_BYTES, expectedSize - offset)
        val count = readImportChunk(displayName) { read(result, offset, requested) }
        if (count <= 0) {
            throw ImportedDocumentReadException(
                "“$displayName” changed while it was being read. Select the file again.",
            )
        }
        if (count > requested) {
            throw ImportedDocumentReadException("The document provider returned an invalid size for “$displayName”.")
        }
        offset += count
    }

    val probe = ByteArray(1)
    val extra = readImportChunk(displayName) { read(probe, 0, 1) }
    if (extra > 1) {
        throw ImportedDocumentReadException("The document provider returned an invalid size for “$displayName”.")
    }
    if (extra > 0) {
        throw ImportedDocumentReadException(
            "“$displayName” changed while it was being read. Select the file again.",
        )
    }
    return result
}

private inline fun readImportChunk(name: String, block: () -> Int): Int = try {
    block()
} catch (error: ImportedDocumentReadException) {
    throw error
} catch (error: Throwable) {
    throw ImportedDocumentReadException("Unable to read “$name”.", error)
}

private fun formatImportByteLimit(bytes: Long): String = when {
    bytes % (1024L * 1024L) == 0L -> "${bytes / (1024L * 1024L)} MiB"
    bytes % 1024L == 0L -> "${bytes / 1024L} KiB"
    else -> "$bytes bytes"
}

private const val IMPORT_READ_CHUNK_BYTES = 64 * 1024
