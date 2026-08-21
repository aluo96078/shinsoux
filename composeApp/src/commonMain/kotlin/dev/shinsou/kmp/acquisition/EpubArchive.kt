package dev.shinsou.kmp.acquisition

import kotlinx.coroutines.CancellationException

/**
 * Immutable random-access EPUB input owned by the host.
 *
 * A platform document picker can implement this with an app-private file or content-provider
 * handle.  Reads must return exactly [byteCount] bytes and the content must remain unchanged for
 * the lifetime of an acquisition.  Keeping this seam in common code prevents a large archive from
 * being copied into several `ByteArray`s merely to inspect ZIP headers and publish the original
 * package blob.
 */
public interface EpubArchiveSource {
    public val byteSize: Long
    public fun read(offset: Long, byteCount: Int): ByteArray
    /** Cooperative CPU/read checkpoint used by bounded parsers and platform inflaters. */
    public fun cancellationCheckpoint() {}
}

/** Byte-array compatibility source. The caller retains ownership until acquisition returns. */
public class ByteArrayEpubArchiveSource(
    private val bytes: ByteArray,
) : EpubArchiveSource {
    override val byteSize: Long get() = bytes.size.toLong()

    override fun read(offset: Long, byteCount: Int): ByteArray {
        require(offset in 0..bytes.size.toLong() && byteCount >= 0 &&
            byteCount.toLong() <= bytes.size.toLong() - offset) { "EPUB source read is out of bounds" }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
    }

    internal fun unsafeArray(): ByteArray = bytes
}

/**
 * Exact expanded file returned by a platform ZIP adapter.
 *
 * Compatibility extractors may keep a bounded in-memory body. The production extractor instead
 * supplies a verified replay loader, so a large EPUB retains only central-directory metadata and
 * materializes at most one expanded entry while parsing or publishing.
 */
public class EpubArchiveEntry private constructor(
    public val path: String,
    public val byteSize: Long,
    public val compressedSizeBytes: Long,
    private val bodyLoader: () -> ByteArray,
) {
    public constructor(path: String, bytes: ByteArray, compressedSizeBytes: Long) : this(
        path = path,
        byteSize = bytes.size.toLong(),
        compressedSizeBytes = compressedSizeBytes,
        bodyLoader = defensiveBodyLoader(bytes),
    )

    public val bytes: ByteArray get() = unsafeBytes().copyOf()

    init {
        require(compressedSizeBytes >= 0) { "EPUB compressed entry size must be non-negative" }
    }

    internal fun unsafeBytes(): ByteArray = bodyLoader().also { body ->
        check(body.size.toLong() == byteSize) { "EPUB entry replay returned a different size: $path" }
    }

    internal companion object {
        fun fromOwnedBytes(path: String, bytes: ByteArray, compressedSizeBytes: Long): EpubArchiveEntry =
            EpubArchiveEntry(path, bytes.size.toLong(), compressedSizeBytes) { bytes }

        fun fromVerifiedReplay(
            path: String,
            byteSize: Long,
            compressedSizeBytes: Long,
            bodyLoader: () -> ByteArray,
        ): EpubArchiveEntry = EpubArchiveEntry(path, byteSize, compressedSizeBytes, bodyLoader)
    }
}

private fun defensiveBodyLoader(bytes: ByteArray): () -> ByteArray {
    val snapshot = bytes.copyOf()
    return { snapshot }
}

/**
 * Platform seam for ZIP inflation. Compatibility implementations receive the exact archive bytes
 * and may return bounded expanded bodies. The production implementation returns verified replay
 * entries over the random-access source; the common acquisition service revalidates every limit.
 */
public fun interface EpubArchiveExtractor {
    public fun extract(archiveBytes: ByteArray, limits: EpubArchiveLimits): List<EpubArchiveEntry>

    /**
     * Streaming/random-access entry point. Legacy extractors remain source compatible and receive
     * one bounded materialization; the production extractor overrides this method.
     */
    public fun extract(archive: EpubArchiveSource, limits: EpubArchiveLimits): List<EpubArchiveEntry> {
        if (archive.byteSize > limits.maximumArchiveBytes || archive.byteSize > Int.MAX_VALUE.toLong()) {
            throw invalidArchive("EPUB archive exceeds the configured size limit")
        }
        return extract(archive.readExact(0, archive.byteSize.toInt()), limits)
    }
}

/**
 * Production OCF ZIP reader shared by every target.
 *
 * The central directory is parsed and fully bounded before any entry is inflated.  Only the two
 * methods required by OCF (stored and raw DEFLATE) are accepted, encrypted/ZIP64/multi-disk
 * archives fail closed, local headers must agree with their central records, and every expanded
 * body is checked against its declared size and CRC-32.  [platformInflateRawDeflate] is the only
 * platform-specific operation.
 */
public class BoundedEpubArchiveExtractor : EpubArchiveExtractor {
    override fun extract(archiveBytes: ByteArray, limits: EpubArchiveLimits): List<EpubArchiveEntry> {
        return extract(ByteArrayEpubArchiveSource(archiveBytes), limits)
    }

    override fun extract(archive: EpubArchiveSource, limits: EpubArchiveLimits): List<EpubArchiveEntry> {
        if (archive.byteSize > limits.maximumArchiveBytes) {
            throw invalidArchive("EPUB archive exceeds the configured size limit")
        }
        val records = parseCentralDirectory(archive, limits)
        return records.filterNot(ZipCentralRecord::directory).map { record ->
            val replay = { readVerifiedEntry(archive, record) }
            // Validate every CRC and declared size before acquisition publishes the archive or a
            // resource. The expanded body is immediately released; later parsing/publishing
            // replays one entry at a time instead of retaining the complete expanded package.
            replay()
            EpubArchiveEntry.fromVerifiedReplay(
                path = record.path,
                byteSize = record.expandedSize,
                compressedSizeBytes = record.compressedSize,
                bodyLoader = replay,
            )
        }.sortedBy(EpubArchiveEntry::path)
    }
}

private fun readVerifiedEntry(
    archive: EpubArchiveSource,
    record: ZipCentralRecord,
): ByteArray {
    val compressed = readCompressedBody(archive, record)
    val expanded = when (record.compressionMethod) {
        ZIP_METHOD_STORED -> {
            if (record.compressedSize != record.expandedSize) {
                throw invalidArchive("Stored EPUB entry has mismatched sizes: ${record.path}")
            }
            compressed
        }
        ZIP_METHOD_DEFLATE -> try {
            platformInflateRawDeflate(
                compressed,
                record.expandedSize.toInt(),
                archive::cancellationCheckpoint,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: EpubAcquisitionException.InvalidArchive) {
            throw error
        } catch (error: Throwable) {
            throw invalidArchive("EPUB entry could not be inflated: ${record.path}", error)
        }
        else -> throw invalidArchive(
            "EPUB entry uses unsupported ZIP compression method ${record.compressionMethod}: ${record.path}",
        )
    }
    if (expanded.size.toLong() != record.expandedSize) {
        throw invalidArchive("EPUB entry expanded size does not match its central record: ${record.path}")
    }
    if (crc32(expanded) != record.crc32) {
        throw invalidArchive("EPUB entry failed CRC-32 verification: ${record.path}")
    }
    return expanded
}

public data class EpubArchiveLimits(
    val maximumArchiveBytes: Long = 512L * 1024 * 1024,
    val maximumEntries: Int = 10_000,
    val maximumEntryBytes: Long = 64L * 1024 * 1024,
    val maximumExpandedBytes: Long = 512L * 1024 * 1024,
    val maximumCompressionRatio: Double = 200.0,
    val maximumXmlBytes: Long = 4L * 1024 * 1024,
    /** Hard bound for the largest compressed body materialized for the inflater. */
    val maximumCompressedEntryBytes: Long = 65L * 1024 * 1024,
) {
    init {
        require(maximumArchiveBytes > 0 && maximumArchiveBytes <= Int.MAX_VALUE) {
            "Maximum EPUB archive size must be positive and addressable"
        }
        require(maximumEntries in 1..100_000) { "Maximum EPUB entry count must fit the content manifest" }
        require(maximumEntryBytes > 0 && maximumEntryBytes <= Int.MAX_VALUE) {
            "Maximum EPUB entry size must be positive and addressable"
        }
        require(maximumExpandedBytes > 0 && maximumExpandedBytes <= Int.MAX_VALUE) {
            "Maximum EPUB expanded size must be positive and addressable"
        }
        require(maximumCompressionRatio.isFinite() && maximumCompressionRatio >= 1.0) {
            "Maximum EPUB compression ratio must be finite and at least one"
        }
        require(maximumXmlBytes > 0 && maximumXmlBytes <= maximumEntryBytes) {
            "Maximum EPUB XML size must be positive and no larger than an entry"
        }
        require(maximumCompressedEntryBytes > 0 && maximumCompressedEntryBytes <= Int.MAX_VALUE) {
            "Maximum compressed EPUB entry size must be positive and addressable"
        }
    }
}

public class EpubAcquisitionPolicy(
    public val archiveLimits: EpubArchiveLimits = EpubArchiveLimits(),
    /** Empty by default: encrypted resources fail closed until a lawful provider is installed. */
    supportedEncryptionAlgorithms: Set<String> = emptySet(),
) {
    public val supportedEncryptionAlgorithms: Set<String> = supportedEncryptionAlgorithms.toSet()

    init {
        require(supportedEncryptionAlgorithms.all { algorithm ->
            algorithm.isNotBlank() && algorithm.length <= 2048 && algorithm.none(Char::isISOControl)
        }) { "Supported EPUB encryption algorithms must be bounded and printable" }
    }
}

public sealed class EpubAcquisitionException(message: String) : IllegalArgumentException(message) {
    public class InvalidArchive(message: String) : EpubAcquisitionException(message)
    public class InvalidPackage(message: String) : EpubAcquisitionException(message)
    public class ResourceMissing(public val path: String) :
        EpubAcquisitionException("EPUB resource is missing: $path")
    public class UnsupportedEncryption(public val algorithms: Set<String>) :
        EpubAcquisitionException("EPUB uses unsupported encryption: ${algorithms.sorted().joinToString()}")
}

internal fun validateArchiveEntries(
    archiveByteSize: Long,
    entries: List<EpubArchiveEntry>,
    limits: EpubArchiveLimits,
): List<EpubArchiveEntry> {
    if (archiveByteSize !in 0..limits.maximumArchiveBytes) {
        throw EpubAcquisitionException.InvalidArchive("EPUB archive exceeds the configured size limit")
    }
    if (entries.isEmpty() || entries.size > limits.maximumEntries) {
        throw EpubAcquisitionException.InvalidArchive("EPUB must contain a bounded, non-empty entry set")
    }
    var expanded = 0L
    val paths = HashSet<String>()
    entries.forEach { entry ->
        val canonical = runCatching { canonicalArchivePath(entry.path) }.getOrElse { error ->
            throw EpubAcquisitionException.InvalidArchive(error.message ?: "EPUB contains an unsafe entry path")
        }
        if (canonical != entry.path) {
            throw EpubAcquisitionException.InvalidArchive("EPUB entry paths must already be canonical: ${entry.path}")
        }
        if (!paths.add(canonical)) {
            throw EpubAcquisitionException.InvalidArchive("EPUB contains a duplicate entry path: $canonical")
        }
        if (entry.byteSize > limits.maximumEntryBytes) {
            throw EpubAcquisitionException.InvalidArchive("EPUB entry exceeds the configured size limit: $canonical")
        }
        if (entry.compressedSizeBytes > limits.maximumCompressedEntryBytes) {
            throw EpubAcquisitionException.InvalidArchive(
                "EPUB compressed entry exceeds the configured size limit: $canonical",
            )
        }
        if (expanded > limits.maximumExpandedBytes - entry.byteSize) {
            throw EpubAcquisitionException.InvalidArchive("EPUB expanded size exceeds the configured limit")
        }
        expanded += entry.byteSize
        if (entry.byteSize > 0 && entry.compressedSizeBytes == 0L) {
            throw EpubAcquisitionException.InvalidArchive("EPUB entry has an invalid zero compressed size: $canonical")
        }
        if (entry.byteSize > 0) {
            val ratio = entry.byteSize.toDouble() / entry.compressedSizeBytes.toDouble()
            if (!ratio.isFinite() || ratio > limits.maximumCompressionRatio) {
                throw EpubAcquisitionException.InvalidArchive("EPUB entry exceeds the compression-ratio limit: $canonical")
            }
        }
    }
    return entries.sortedBy(EpubArchiveEntry::path)
}

private data class ZipCentralRecord(
    val path: String,
    val rawPath: ByteArray,
    val flags: Int,
    val compressionMethod: Int,
    val crc32: Long,
    val compressedSize: Long,
    val expandedSize: Long,
    val localHeaderOffset: Long,
    val directory: Boolean,
)

private fun parseCentralDirectory(
    archive: EpubArchiveSource,
    limits: EpubArchiveLimits,
): List<ZipCentralRecord> {
    val eocdOffset = findEndOfCentralDirectory(archive)
    val endRecord = archive.readExact(eocdOffset, ZIP_EOCD_FIXED_SIZE)
    val diskNumber = endRecord.u16(4)
    val centralDisk = endRecord.u16(6)
    val entriesOnDisk = endRecord.u16(8)
    val entryCount = endRecord.u16(10)
    val centralSize = endRecord.u32(12)
    val centralOffset = endRecord.u32(16)
    if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
        throw invalidArchive("Multi-disk EPUB ZIP archives are unsupported")
    }
    if (entryCount == ZIP64_U16_SENTINEL || centralSize == ZIP64_U32_SENTINEL ||
        centralOffset == ZIP64_U32_SENTINEL
    ) {
        throw invalidArchive("ZIP64 EPUB archives are unsupported")
    }
    if (entryCount == 0 || entryCount > limits.maximumEntries) {
        throw invalidArchive("EPUB must contain a bounded, non-empty entry set")
    }
    val centralEnd = checkedEnd(centralOffset, centralSize, archive.byteSize, "EPUB central directory")
    if (centralEnd != eocdOffset) {
        throw invalidArchive("EPUB central directory is not contiguous with its end record")
    }

    var cursor = centralOffset
    var expandedTotal = 0L
    val paths = HashSet<String>(entryCount)
    val records = ArrayList<ZipCentralRecord>(entryCount)
    repeat(entryCount) {
        val header = archive.readExact(cursor, ZIP_CENTRAL_FIXED_SIZE)
        requireSignature(header, 0, ZIP_CENTRAL_SIGNATURE, "EPUB central directory entry")
        val flags = header.u16(8)
        val method = header.u16(10)
        val expectedCrc = header.u32(16)
        val compressedSize = header.u32(20)
        val expandedSize = header.u32(24)
        val pathLength = header.u16(28)
        val extraLength = header.u16(30)
        val commentLength = header.u16(32)
        val startingDisk = header.u16(34)
        val localOffset = header.u32(42)
        if (compressedSize == ZIP64_U32_SENTINEL || expandedSize == ZIP64_U32_SENTINEL ||
            localOffset == ZIP64_U32_SENTINEL || startingDisk == ZIP64_U16_SENTINEL
        ) {
            throw invalidArchive("ZIP64 EPUB entries are unsupported")
        }
        if (startingDisk != 0) throw invalidArchive("Multi-disk EPUB entries are unsupported")
        rejectUnsafeZipFlags(flags)
        if (method != ZIP_METHOD_STORED && method != ZIP_METHOD_DEFLATE) {
            throw invalidArchive("EPUB ZIP compression method $method is unsupported")
        }
        val variableSize = pathLength.toLong() + extraLength.toLong() + commentLength.toLong()
        val recordEnd = checkedEnd(
            cursor + ZIP_CENTRAL_FIXED_SIZE,
            variableSize,
            centralEnd,
            "EPUB central directory entry",
        )
        if (pathLength == 0) throw invalidArchive("EPUB ZIP entry has an empty path")
        val rawPath = archive.readExact(cursor + ZIP_CENTRAL_FIXED_SIZE, pathLength)
        val decodedPath = decodeZipPath(rawPath)
        val directory = decodedPath.endsWith('/')
        val canonical = try {
            canonicalArchivePath(if (directory) decodedPath.dropLast(1) else decodedPath)
        } catch (error: IllegalArgumentException) {
            throw invalidArchive(error.message ?: "EPUB contains an unsafe entry path", error)
        }
        val path = if (directory) "$canonical/" else canonical
        if (!paths.add(path)) throw invalidArchive("EPUB contains a duplicate entry path: $path")
        if (directory && (compressedSize != 0L || expandedSize != 0L)) {
            throw invalidArchive("EPUB directory entry contains a body: $path")
        }
        if (!directory) {
            validateDeclaredZipLimits(path, compressedSize, expandedSize, expandedTotal, limits)
            expandedTotal += expandedSize
        }
        records += ZipCentralRecord(
            path = path,
            rawPath = rawPath,
            flags = flags,
            compressionMethod = method,
            crc32 = expectedCrc,
            compressedSize = compressedSize,
            expandedSize = expandedSize,
            localHeaderOffset = localOffset,
            directory = directory,
        )
        cursor = recordEnd
    }
    if (cursor != centralEnd) {
        throw invalidArchive("EPUB central directory size does not match its entries")
    }
    if (records.none { !it.directory }) throw invalidArchive("EPUB archive contains no resource entries")
    return records
}

private fun validateDeclaredZipLimits(
    path: String,
    compressedSize: Long,
    expandedSize: Long,
    expandedTotal: Long,
    limits: EpubArchiveLimits,
) {
    if (expandedSize > limits.maximumEntryBytes) {
        throw invalidArchive("EPUB entry exceeds the configured size limit: $path")
    }
    if (compressedSize > limits.maximumCompressedEntryBytes) {
        throw invalidArchive("EPUB compressed entry exceeds the configured size limit: $path")
    }
    if (expandedTotal > limits.maximumExpandedBytes - expandedSize) {
        throw invalidArchive("EPUB expanded size exceeds the configured limit")
    }
    if (expandedSize > 0 && compressedSize == 0L) {
        throw invalidArchive("EPUB entry has an invalid zero compressed size: $path")
    }
    if (expandedSize > 0) {
        val ratio = expandedSize.toDouble() / compressedSize.toDouble()
        if (!ratio.isFinite() || ratio > limits.maximumCompressionRatio) {
            throw invalidArchive("EPUB entry exceeds the compression-ratio limit: $path")
        }
    }
}

private fun readCompressedBody(archive: EpubArchiveSource, record: ZipCentralRecord): ByteArray {
    val offset = record.localHeaderOffset
    val header = archive.readExact(offset, ZIP_LOCAL_FIXED_SIZE)
    requireSignature(header, 0, ZIP_LOCAL_SIGNATURE, "EPUB local file header")
    val localFlags = header.u16(6)
    val localMethod = header.u16(8)
    val pathLength = header.u16(26)
    val extraLength = header.u16(28)
    rejectUnsafeZipFlags(localFlags)
    if (localFlags != record.flags || localMethod != record.compressionMethod) {
        throw invalidArchive("EPUB local and central ZIP headers disagree: ${record.path}")
    }
    val pathStart = offset + ZIP_LOCAL_FIXED_SIZE
    val dataStart = checkedEnd(
        pathStart,
        pathLength.toLong() + extraLength.toLong(),
        archive.byteSize,
        "EPUB local file header",
    )
    val localPath = archive.readExact(pathStart, pathLength)
    if (!localPath.contentEquals(record.rawPath)) {
        throw invalidArchive("EPUB local and central entry paths disagree: ${record.path}")
    }
    checkedEnd(dataStart, record.compressedSize, archive.byteSize, "EPUB compressed entry")
    if (record.compressedSize > Int.MAX_VALUE) throw invalidArchive("EPUB compressed entry is too large")
    return archive.readExact(dataStart, record.compressedSize.toInt())
}

private fun findEndOfCentralDirectory(archive: EpubArchiveSource): Long {
    if (archive.byteSize < ZIP_EOCD_FIXED_SIZE) throw invalidArchive("EPUB ZIP end record is missing")
    val tailSize = minOf(
        archive.byteSize,
        (ZIP_EOCD_FIXED_SIZE + ZIP_MAX_COMMENT_LENGTH).toLong(),
    ).toInt()
    val tailOffset = archive.byteSize - tailSize
    val tail = archive.readExact(tailOffset, tailSize)
    var cursor = tail.size - ZIP_EOCD_FIXED_SIZE
    while (cursor >= 0) {
        if (tail.hasSignature(cursor, ZIP_EOCD_SIGNATURE)) {
            val commentLength = tail.u16(cursor + 20)
            if (cursor + ZIP_EOCD_FIXED_SIZE + commentLength == tail.size) return tailOffset + cursor
        }
        cursor--
    }
    throw invalidArchive("EPUB ZIP end record is missing or malformed")
}

private fun rejectUnsafeZipFlags(flags: Int) {
    if ((flags and ZIP_FLAG_ENCRYPTED) != 0 || (flags and ZIP_FLAG_STRONG_ENCRYPTION) != 0 ||
        (flags and ZIP_FLAG_MASKED_HEADERS) != 0
    ) {
        throw invalidArchive("Encrypted ZIP containers are not supported for EPUB")
    }
}

private fun decodeZipPath(rawPath: ByteArray): String = try {
    rawPath.decodeToString(throwOnInvalidSequence = true)
} catch (error: Throwable) {
    throw invalidArchive("EPUB ZIP entry path is not valid UTF-8", error)
}

private fun checkedEnd(start: Long, size: Long, bound: Long, label: String): Long {
    if (start < 0 || size < 0 || start > bound || size > bound - start) {
        throw invalidArchive("$label exceeds the archive bounds")
    }
    return start + size
}

private fun EpubArchiveSource.readExact(offset: Long, byteCount: Int): ByteArray {
    if (offset < 0 || byteCount < 0 || offset > byteSize || byteCount.toLong() > byteSize - offset) {
        throw invalidArchive("EPUB source read exceeds the archive bounds")
    }
    val bytes = try {
        read(offset, byteCount)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: EpubAcquisitionException.InvalidArchive) {
        throw error
    } catch (error: Throwable) {
        throw invalidArchive("EPUB source read failed", error)
    }
    if (bytes.size != byteCount) throw invalidArchive("EPUB source returned a truncated read")
    return bytes
}

private fun requireAvailable(archive: ByteArray, offset: Int, size: Int, label: String) {
    if (offset < 0 || size < 0 || offset > archive.size || size > archive.size - offset) {
        throw invalidArchive("$label is truncated")
    }
}

private fun requireSignature(archive: ByteArray, offset: Int, signature: Long, label: String) {
    requireAvailable(archive, offset, 4, label)
    if (!archive.hasSignature(offset, signature)) throw invalidArchive("$label has an invalid signature")
}

private fun ByteArray.hasSignature(offset: Int, signature: Long): Boolean =
    offset >= 0 && offset <= size - 4 && u32(offset) == signature

private fun ByteArray.u16(offset: Int): Int {
    requireAvailable(this, offset, 2, "EPUB ZIP integer")
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.u32(offset: Int): Long {
    requireAvailable(this, offset, 4, "EPUB ZIP integer")
    return (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun crc32(bytes: ByteArray): Long {
    var crc = 0xffffffffu
    bytes.forEach { byte ->
        crc = CRC32_TABLE[((crc xor byte.toUByte().toUInt()) and 0xffu).toInt()] xor (crc shr 8)
    }
    return (crc xor 0xffffffffu).toLong()
}

@OptIn(ExperimentalUnsignedTypes::class)
private val CRC32_TABLE: UIntArray = UIntArray(256) { index ->
    var value = index.toUInt()
    repeat(8) {
        value = if ((value and 1u) != 0u) (value shr 1) xor 0xedb88320u else value shr 1
    }
    value
}

@Suppress("UNUSED_PARAMETER")
private fun invalidArchive(message: String, cause: Throwable? = null): EpubAcquisitionException.InvalidArchive =
    EpubAcquisitionException.InvalidArchive(message)

internal expect fun platformInflateRawDeflate(
    compressed: ByteArray,
    expectedSize: Int,
    cancellationCheckpoint: () -> Unit,
): ByteArray

private const val ZIP_LOCAL_SIGNATURE: Long = 0x04034b50L
private const val ZIP_CENTRAL_SIGNATURE: Long = 0x02014b50L
private const val ZIP_EOCD_SIGNATURE: Long = 0x06054b50L
private const val ZIP_LOCAL_FIXED_SIZE: Int = 30
private const val ZIP_CENTRAL_FIXED_SIZE: Int = 46
private const val ZIP_EOCD_FIXED_SIZE: Int = 22
private const val ZIP_MAX_COMMENT_LENGTH: Int = 65_535
private const val ZIP64_U16_SENTINEL: Int = 0xffff
private const val ZIP64_U32_SENTINEL: Long = 0xffffffffL
private const val ZIP_METHOD_STORED: Int = 0
private const val ZIP_METHOD_DEFLATE: Int = 8
private const val ZIP_FLAG_ENCRYPTED: Int = 1 shl 0
private const val ZIP_FLAG_STRONG_ENCRYPTION: Int = 1 shl 6
private const val ZIP_FLAG_MASKED_HEADERS: Int = 1 shl 13

internal fun canonicalArchivePath(value: String): String {
    require(value.isNotBlank() && value.length <= MAX_EPUB_PATH_LENGTH) { "EPUB entry path must be bounded" }
    require(value.none(Char::isISOControl) && '\\' !in value && '%' !in value) {
        "EPUB entry path contains unsafe characters"
    }
    require(value.isWellFormedUtf16Path()) { "EPUB entry path contains malformed Unicode" }
    require(!value.startsWith('/') && !WINDOWS_DRIVE.containsMatchIn(value) &&
        !URI_SCHEME.containsMatchIn(value)
    ) { "EPUB entry path must be relative" }
    require(!value.endsWith('/')) { "EPUB directory entries are not resources" }
    val segments = value.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "EPUB entry path contains traversal" }
    return segments.joinToString("/")
}

/** Resolves an OPF/CSS/XHTML relative reference to an exact archive path. */
internal fun resolveArchivePath(basePath: String, reference: String): String {
    require(reference.isNotBlank() && reference.length <= MAX_EPUB_REFERENCE_LENGTH) {
        "EPUB resource reference must be bounded"
    }
    require(reference.none(Char::isISOControl) && '\\' !in reference) { "EPUB resource reference is unsafe" }
    require(!reference.startsWith('/') && !reference.startsWith("//") && !URI_SCHEME.containsMatchIn(reference)) {
        "EPUB resource reference must be relative"
    }
    val pathReference = reference.substringBefore('#').substringBefore('?')
    require(pathReference.isNotEmpty()) { "EPUB resource reference has no path" }
    val decoded = percentDecodeUtf8(pathReference)
    require(decoded.none(Char::isISOControl) && '\\' !in decoded && !decoded.startsWith('/')) {
        "EPUB resource reference decodes to an unsafe path"
    }
    val output = ArrayList<String>()
    val baseDirectory = basePath.substringBeforeLast('/', "")
    if (baseDirectory.isNotEmpty()) output += baseDirectory.split('/')
    decoded.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> {
                require(output.isNotEmpty()) { "EPUB resource reference escapes the archive root" }
                output.removeAt(output.lastIndex)
            }
            else -> output += segment
        }
    }
    return canonicalArchivePath(output.joinToString("/"))
}

internal fun archiveHref(path: String): String {
    val canonical = canonicalArchivePath(path)
    return buildString {
        var index = 0
        while (index < canonical.length) {
            val character = canonical[index]
            when {
                character == '/' || character in 'a'..'z' || character in 'A'..'Z' ||
                    character in '0'..'9' || character in "-._~" -> {
                    append(character)
                    index++
                }
                else -> {
                    val end = if (character.isHighSurrogate()) index + 2 else index + 1
                    canonical.substring(index, end).encodeToByteArray().forEach { byte ->
                        append('%')
                        append(HEX[(byte.toInt() ushr 4) and 0x0f])
                        append(HEX[byte.toInt() and 0x0f])
                    }
                    index = end
                }
            }
        }
    }
}

internal fun resolveOptionalArchivePath(basePath: String, reference: String): String? =
    runCatching { resolveArchivePath(basePath, reference) }.getOrNull()

private fun percentDecodeUtf8(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            output.append(value[index++])
            continue
        }
        val runStart = index
        while (index < value.length && value[index] == '%') {
            require(index + 2 < value.length) { "EPUB resource reference has an incomplete percent escape" }
            index += 3
        }
        val bytes = ByteArray((index - runStart) / 3) { byteIndex ->
            val encodedAt = runStart + byteIndex * 3
            value.substring(encodedAt + 1, encodedAt + 3).toIntOrNull(16)?.toByte()
                ?: throw IllegalArgumentException("EPUB resource reference has an invalid percent escape")
        }
        val decoded = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (error: Throwable) {
            throw IllegalArgumentException("EPUB resource reference has malformed UTF-8 escapes", error)
        }
        output.append(decoded)
    }
    return output.toString()
}

private fun String.isWellFormedUtf16Path(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            this[index].isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private const val MAX_EPUB_PATH_LENGTH: Int = 4096
private const val MAX_EPUB_REFERENCE_LENGTH: Int = 4096
private const val HEX: String = "0123456789ABCDEF"
