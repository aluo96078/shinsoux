package dev.shinsou.kmp.backup

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobStoreException
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPortableAuxiliaryState
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.withoutPortableSecrets
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

public const val SHINSOU_CONTENT_BACKUP_FORMAT: String = "dev.shinsou.kmp.content-archive"
public const val SHINSOU_CONTENT_BACKUP_FORMAT_VERSION: Int = 2

/** Body-free portable authority stored separately from optional archive blob entries. */
@Serializable
public data class BackupV2PortableState(
    val schemaVersion: Int = SHINSOU_CONTENT_BACKUP_FORMAT_VERSION,
    val legacySnapshot: AppSnapshot,
    val publications: List<Publication> = emptyList(),
    val annotations: List<ContentAnnotation> = emptyList(),
    /** Portable policy records only; provider credentials and protection keys are never grants. */
    val rightsGrants: List<RightsGrant> = emptyList(),
    /** Small SQLite authority required for migration identity and compatibility-state repair. */
    val auxiliary: ContentPortableAuxiliaryState = ContentPortableAuxiliaryState(),
) {
    init { validate() }

    public fun validate(): BackupV2PortableState {
        require(schemaVersion == SHINSOU_CONTENT_BACKUP_FORMAT_VERSION) {
            "Unsupported content-backup state version $schemaVersion"
        }
        legacySnapshot.validate()
        require(legacySnapshot == legacySnapshot.withoutPortableSecrets()) {
            "Portable backup metadata contains local secret material"
        }
        require(publications.map { it.key }.distinct().size == publications.size) {
            "Backup publications must have unique ids"
        }
        publications.forEach(Publication::validate)
        require(annotations.map(ContentAnnotation::annotationId).distinct().size == annotations.size) {
            "Backup annotations must have unique ids"
        }
        annotations.forEach(ContentAnnotation::validate)
        val publicationIds = publications.mapTo(hashSetOf()) { it.key }
        require(annotations.all { it.scope.publicationId in publicationIds }) {
            "Backup annotation refers to an absent publication"
        }
        require(rightsGrants.map { it.grantId }.distinct().size == rightsGrants.size) {
            "Backup rights grants must have unique ids"
        }
        rightsGrants.forEach { grant ->
            grant.validate()
            val publication = publications.singleOrNull { it.key == grant.scope.publicationId }
            require(publication?.acquisitions?.any { it.id == grant.scope.acquisitionId } == true) {
                "Backup rights grant refers to an absent acquisition"
            }
        }
        auxiliary.validate()
        return this
    }
}

@Serializable
public enum class BackupV2EntryKind {
    PORTABLE_STATE,
    CONTENT_BLOB,
}

@Serializable
public data class BackupV2EntryDescriptor(
    val path: String,
    val kind: BackupV2EntryKind,
    val byteSize: Long,
    val sha256: String,
    val mediaType: String,
    val blob: BlobRef? = null,
) {
    init { validate() }

    public fun validate() {
        requireArchivePath(path)
        require(byteSize >= 0) { "Backup entry size must be non-negative" }
        require(SHA256_HEX.matches(sha256)) { "Backup entry checksum must be lowercase SHA-256" }
        require(mediaType.isNotBlank() && mediaType.length <= 255 && mediaType.none(Char::isISOControl)) {
            "Backup entry media type is invalid"
        }
        when (kind) {
            BackupV2EntryKind.PORTABLE_STATE -> {
                require(blob == null) { "Portable-state entry cannot claim a content blob" }
                require(path == PORTABLE_STATE_PATH) { "Portable-state entry path is not canonical" }
            }
            BackupV2EntryKind.CONTENT_BLOB -> {
                val reference = requireNotNull(blob) { "Content entry requires a blob reference" }
                reference.validate()
                require(path == blobPath(reference)) { "Content blob entry path is not canonical" }
                require(byteSize == reference.byteSize && sha256 == reference.plaintextDigest &&
                    mediaType == reference.mediaType) {
                    "Content entry descriptor does not match its immutable blob reference"
                }
            }
        }
    }
}

@Serializable
public data class BackupV2AttachmentRecord(
    val attachment: ManifestAttachment,
    val blobEntryPaths: List<String>,
) {
    init {
        attachment.owner.validate()
        attachment.manifest.validate()
        require(blobEntryPaths.distinct().size == blobEntryPaths.size) {
            "Backup attachment entry paths must be unique"
        }
        blobEntryPaths.forEach(::requireArchivePath)
    }
}

@Serializable
public enum class BackupV2OmissionReason {
    NOT_REQUESTED,
    RIGHTS_DENIED,
    BLOB_MISSING,
    BLOB_CORRUPT,
    ARCHIVE_LIMIT,
}

@Serializable
public data class BackupV2OmittedAttachment(
    val attachmentKey: String,
    val reason: BackupV2OmissionReason,
) {
    init {
        require(attachmentKey.isNotBlank() && attachmentKey.length <= 16_384 &&
            attachmentKey.none(Char::isISOControl)) { "Omitted attachment key is invalid" }
    }
}

@Serializable
public data class BackupV2ArchiveManifest(
    val format: String = SHINSOU_CONTENT_BACKUP_FORMAT,
    val formatVersion: Int = SHINSOU_CONTENT_BACKUP_FORMAT_VERSION,
    val createdAtEpochMillis: Long,
    val appVersion: String = "",
    val entries: List<BackupV2EntryDescriptor>,
    val attachments: List<BackupV2AttachmentRecord> = emptyList(),
    val omittedAttachments: List<BackupV2OmittedAttachment> = emptyList(),
) {
    init { validate() }

    public fun validate(): BackupV2ArchiveManifest {
        require(format == SHINSOU_CONTENT_BACKUP_FORMAT) { "Not a Shinsou X content backup" }
        require(formatVersion == SHINSOU_CONTENT_BACKUP_FORMAT_VERSION) {
            "Unsupported content backup version $formatVersion"
        }
        require(createdAtEpochMillis >= 0) { "Backup creation time must be non-negative" }
        require(appVersion.length <= 1_024 && appVersion.none(Char::isISOControl)) {
            "Backup app version is invalid"
        }
        require(entries.isNotEmpty() && entries.size <= MAX_ARCHIVE_ENTRIES) {
            "Backup entry count is invalid"
        }
        entries.forEach(BackupV2EntryDescriptor::validate)
        require(entries.map(BackupV2EntryDescriptor::path).distinct().size == entries.size) {
            "Backup entry paths must be unique"
        }
        require(entries.count { it.kind == BackupV2EntryKind.PORTABLE_STATE } == 1) {
            "Backup needs exactly one portable-state entry"
        }
        require(attachments.map { it.attachment.attachmentKey }.distinct().size == attachments.size) {
            "Backup attachment keys must be unique"
        }
        val byPath = entries.associateBy(BackupV2EntryDescriptor::path)
        attachments.forEach { record ->
            val expected = record.attachment.blobs.map(::blobPath)
            require(record.blobEntryPaths == expected) {
                "Backup attachment must list its complete manifest blob graph in manifest order"
            }
            record.blobEntryPaths.forEach { path ->
                val descriptor = byPath[path]
                require(descriptor?.kind == BackupV2EntryKind.CONTENT_BLOB) {
                    "Backup attachment refers to an undeclared body entry"
                }
            }
        }
        val committedKeys = attachments.mapTo(hashSetOf()) { it.attachment.attachmentKey }
        require(omittedAttachments.map(BackupV2OmittedAttachment::attachmentKey).distinct().size ==
            omittedAttachments.size) { "Omitted backup attachment keys must be unique" }
        require(omittedAttachments.none { it.attachmentKey in committedKeys }) {
            "A backup attachment cannot be both included and omitted"
        }
        return this
    }
}

@Serializable
public data class BackupV2ManifestEnvelope(
    val manifest: BackupV2ArchiveManifest,
    val manifestSha256: String,
) {
    init { validate() }

    public fun validate(): BackupV2ManifestEnvelope {
        manifest.validate()
        require(manifestSha256 == BackupV2ManifestCodec.digest(manifest)) {
            "Backup manifest checksum mismatch"
        }
        return this
    }
}

/** Exact owner/scope binding presented to the host rights authority for optional body export. */
public data class BackupV2AttachmentCandidate(
    val attachment: ManifestAttachment,
    val access: ContentAccessRequest,
) {
    init {
        val owner = attachment.owner
        val scope = access.scope
        require(scope.publicationId == owner.publicationKey &&
            scope.acquisitionId == owner.acquisitionId &&
            scope.unitId == owner.unitKey &&
            scope.manifestId == attachment.manifestId &&
            scope.contentRevision == attachment.contentRevision) {
            "Backup attachment and rights scope do not match"
        }
    }
}

public data class BackupV2CreatePolicy(
    val includeContentBlobs: Boolean = true,
    val maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
) {
    init {
        require(maximumArchiveBytes in 1..Int.MAX_VALUE.toLong()) {
            "Backup archive byte limit is invalid"
        }
    }
}

/**
 * Immutable random-access encoded backup input. Platform code may back this with an app-private
 * file/channel; [read] must return exactly [byteCount] bytes and remain stable while the decoded
 * archive is in use.
 */
public interface BackupV2ArchiveSource {
    public val byteSize: Long
    public fun read(offset: Long, byteCount: Int): ByteArray
}

/** Defensive in-memory compatibility source for the legacy `decode(ByteArray)` API. */
public class ByteArrayBackupV2ArchiveSource(bytes: ByteArray) : BackupV2ArchiveSource {
    private val contents = bytes.copyOf()
    override val byteSize: Long get() = contents.size.toLong()

    override fun read(offset: Long, byteCount: Int): ByteArray {
        require(offset in 0..contents.size.toLong() && byteCount >= 0 &&
            byteCount.toLong() <= contents.size.toLong() - offset) { "Backup source read is out of bounds" }
        return contents.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
    }
}

/** Chunk sink used by [BackupV2BinaryCodec.encodeTo] for file/channel-backed exports. */
public fun interface BackupV2BinarySink {
    public fun write(chunk: ByteArray)
}

/** Immutable logical archive. Entries may be memory, blob-lease, or random-access slices. */
public class BackupV2Archive private constructor(entries: List<NamedBackupV2ArchiveEntry>) {
    private val contents: Map<String, BackupV2ArchiveEntry> = entries.associate { named ->
        requireArchivePath(named.path)
        named.path to named.entry
    }.also { mapped ->
        require(mapped.size == entries.size) { "Content backup contains duplicate entry paths" }
    }

    public val paths: Set<String> get() = contents.keys.toSet()

    /** Compatibility materialization for small callers. Core inspect/publish/encode paths stream. */
    public fun read(path: String): ByteArray? = contents[path]?.readFully()

    internal fun entry(path: String): BackupV2ArchiveEntry? = contents[path]

    internal fun orderedEntries(): List<Pair<String, BackupV2ArchiveEntry>> =
        contents.entries.map { it.key to it.value }

    internal companion object {
        fun fromOwnedEntries(entries: Map<String, BackupV2ArchiveEntry>): BackupV2Archive =
            BackupV2Archive(entries.map { (path, entry) -> NamedBackupV2ArchiveEntry(path, entry) })
    }
}

private data class NamedBackupV2ArchiveEntry(
    val path: String,
    val entry: BackupV2ArchiveEntry,
)

internal interface BackupV2ArchiveEntry {
    val byteSize: Long
    fun open(): BackupV2ArchiveEntryReader
}

internal interface BackupV2ArchiveEntryReader {
    fun readChunk(maxBytes: Int = BACKUP_STREAM_CHUNK_BYTES): ByteArray?
    fun close()
}

private class MemoryBackupV2ArchiveEntry(
    private val bytes: ByteArray,
) : BackupV2ArchiveEntry {
    override val byteSize: Long get() = bytes.size.toLong()
    override fun open(): BackupV2ArchiveEntryReader = object : BackupV2ArchiveEntryReader {
        private var offset = 0
        override fun readChunk(maxBytes: Int): ByteArray? {
            require(maxBytes > 0) { "Backup read chunk size must be positive" }
            if (offset == bytes.size) return null
            val remaining = bytes.size - offset
            val end = if (maxBytes >= remaining) bytes.size else offset + maxBytes
            return bytes.copyOfRange(offset, end).also { offset = end }
        }
        override fun close() = Unit
    }
}

private class BlobBackupV2ArchiveEntry(
    private val blobStore: ContentBlobStore,
    private val reference: BlobRef,
) : BackupV2ArchiveEntry {
    override val byteSize: Long get() = reference.byteSize
    override fun open(): BackupV2ArchiveEntryReader {
        val lease = blobStore.openRead(reference)
            ?: throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        return object : BackupV2ArchiveEntryReader {
            override fun readChunk(maxBytes: Int): ByteArray? = lease.readChunk(maxBytes)
            override fun close() = lease.close()
        }
    }
}

private class SlicedBackupV2ArchiveEntry(
    private val source: BackupV2ArchiveSource,
    private val offset: Long,
    override val byteSize: Long,
) : BackupV2ArchiveEntry {
    override fun open(): BackupV2ArchiveEntryReader = object : BackupV2ArchiveEntryReader {
        private var consumed = 0L
        override fun readChunk(maxBytes: Int): ByteArray? {
            require(maxBytes > 0) { "Backup read chunk size must be positive" }
            if (consumed == byteSize) return null
            val count = minOf(maxBytes.toLong(), byteSize - consumed).toInt()
            return source.readExact(offset + consumed, count).also { consumed += it.size }
        }
        override fun close() = Unit
    }
}

private fun BackupV2ArchiveEntry.readFully(): ByteArray {
    if (byteSize > Int.MAX_VALUE) throw BackupFormatException("Backup entry is too large")
    val output = ByteArray(byteSize.toInt())
    var offset = 0
    val reader = open()
    return try {
        while (true) {
            val chunk = reader.readChunk() ?: break
            if (chunk.isEmpty() || chunk.size > output.size - offset) {
                throw BackupFormatException("Content backup entry size mismatch")
            }
            chunk.copyInto(output, offset)
            offset += chunk.size
        }
        if (offset != output.size) throw BackupFormatException("Content backup entry is truncated")
        output
    } finally {
        reader.close()
    }
}

public data class BackupV2CreateResult(
    val archive: BackupV2Archive,
    val envelope: BackupV2ManifestEnvelope,
) {
    public val omittedAttachments: List<BackupV2OmittedAttachment>
        get() = envelope.manifest.omittedAttachments
}

public data class BackupV2Inspection(
    val envelope: BackupV2ManifestEnvelope,
    val portableState: BackupV2PortableState,
)

/** A decoded archive paired with the exact checksum inspection performed during decode. */
public data class InspectedBackupV2Archive(
    val archive: BackupV2Archive,
    val inspection: BackupV2Inspection,
)

public object BackupV2ManifestCodec {
    public fun encode(envelope: BackupV2ManifestEnvelope): ByteArray {
        val bytes = BackupV2Json.encodeToString(envelope.validate()).encodeToByteArray()
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
        return bytes
    }

    public fun decode(bytes: ByteArray): BackupV2ManifestEnvelope {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
        return try {
            BackupV2Json.decodeFromString<BackupV2ManifestEnvelope>(bytes.decodeToString()).validate()
        } catch (error: IllegalArgumentException) {
            throw BackupFormatException(error.message ?: "Invalid content backup manifest")
        }
    }

    internal fun digest(manifest: BackupV2ArchiveManifest): String =
        Sha256.hex(BackupV2Json.encodeToString(manifest).encodeToByteArray())
}

public object PortableContentBackupV2Service {
    public fun create(
        state: BackupV2PortableState,
        candidates: List<BackupV2AttachmentCandidate>,
        blobStore: ContentBlobStore,
        operationGate: ContentOperationGate,
        createdAtEpochMillis: Long,
        appVersion: String = "",
        policy: BackupV2CreatePolicy = BackupV2CreatePolicy(),
    ): BackupV2CreateResult {
        require(createdAtEpochMillis >= 0) { "Backup creation time must be non-negative" }
        require(candidates.map { it.attachment.attachmentKey }.distinct().size == candidates.size) {
            "Backup candidates must have unique attachment keys"
        }
        val sortedState = state.copy(
            legacySnapshot = state.legacySnapshot.withoutPortableSecrets(),
            publications = state.publications.sortedBy { it.key.value },
            annotations = state.annotations.sortedBy(ContentAnnotation::annotationId),
            rightsGrants = state.rightsGrants.sortedBy { it.grantId.value },
            auxiliary = state.auxiliary.copy(
                metadata = state.auxiliary.metadata.sortedBy { it.key },
                aliases = state.auxiliary.aliases.sortedBy { it.alias },
                migrations = state.auxiliary.migrations.sortedBy { it.migrationKey },
            ),
        ).validate()
        val sanitizedState = sortedState.copy(
            annotations = sortedState.annotations.filter { annotation ->
                annotationExportRequests(annotation, sortedState, candidates).any { access ->
                    operationGate.decide(access, ContentOperation.EXPORT) == RightsDecision.ALLOW
                }
            },
        ).validate()
        val stateBytes = BackupV2Json.encodeToString(sanitizedState).encodeToByteArray()
        require(stateBytes.size <= MAX_PORTABLE_STATE_BYTES &&
            stateBytes.size.toLong() <= policy.maximumArchiveBytes) {
            "Portable backup metadata exceeds the archive limit"
        }
        val stateDescriptor = BackupV2EntryDescriptor(
            path = PORTABLE_STATE_PATH,
            kind = BackupV2EntryKind.PORTABLE_STATE,
            byteSize = stateBytes.size.toLong(),
            sha256 = Sha256.hex(stateBytes),
            mediaType = "application/json",
        )
        val archiveEntries = linkedMapOf<String, BackupV2ArchiveEntry>(
            PORTABLE_STATE_PATH to MemoryBackupV2ArchiveEntry(stateBytes),
        )
        val descriptors = linkedMapOf(PORTABLE_STATE_PATH to stateDescriptor)
        val included = ArrayList<BackupV2AttachmentRecord>()
        val omitted = ArrayList<BackupV2OmittedAttachment>()
        var archiveBytes = stateBytes.size.toLong()
        val stateAttachments = sanitizedState.publications.flatMap { publication ->
            publication.acquisitions.flatMap { acquisition ->
                acquisition.units.flatMap { unit ->
                    unit.manifestRevisions.map { manifest ->
                        ManifestAttachment(
                            owner = ContentManifestOwner(publication.key, acquisition.id, unit.key),
                            manifest = manifest,
                        )
                    }
                }
            }
        }.associateBy(ManifestAttachment::attachmentKey)
        candidates.forEach { candidate ->
            require(stateAttachments[candidate.attachment.attachmentKey] == candidate.attachment) {
                "Backup candidate is not the exact manifest graph in portable state"
            }
        }

        candidates.sortedBy { it.attachment.attachmentKey }.forEach { candidate ->
            val attachment = candidate.attachment
            if (!policy.includeContentBlobs) {
                omitted += BackupV2OmittedAttachment(
                    attachment.attachmentKey,
                    BackupV2OmissionReason.NOT_REQUESTED,
                )
                return@forEach
            }
            if (operationGate.decide(candidate.access, ContentOperation.EXPORT) != RightsDecision.ALLOW) {
                omitted += BackupV2OmittedAttachment(
                    attachment.attachmentKey,
                    BackupV2OmissionReason.RIGHTS_DENIED,
                )
                return@forEach
            }
            val staged = ArrayList<BlobRef>()
            var stagedBytes = 0L
            var failure: BackupV2OmissionReason? = null
            attachment.blobs.forEach blobLoop@{ reference ->
                if (failure != null || descriptors.containsKey(blobPath(reference))) return@blobLoop
                if (reference.byteSize > policy.maximumArchiveBytes - archiveBytes - stagedBytes) {
                    failure = BackupV2OmissionReason.ARCHIVE_LIMIT
                    return@blobLoop
                }
                val verified = try {
                    verifyStoredBlob(blobStore, reference)
                } catch (_: ContentBlobStoreException.CorruptBlob) {
                    failure = BackupV2OmissionReason.BLOB_CORRUPT
                    false
                } catch (_: ContentBlobStoreException) {
                    failure = BackupV2OmissionReason.BLOB_MISSING
                    false
                }
                if (!verified) {
                    if (failure == null) failure = BackupV2OmissionReason.BLOB_MISSING
                } else {
                    staged += reference
                    stagedBytes += reference.byteSize
                }
            }
            if (failure != null) {
                omitted += BackupV2OmittedAttachment(attachment.attachmentKey, requireNotNull(failure))
                return@forEach
            }
            staged.forEach { reference ->
                val descriptor = BackupV2EntryDescriptor(
                    path = blobPath(reference),
                    kind = BackupV2EntryKind.CONTENT_BLOB,
                    byteSize = reference.byteSize,
                    sha256 = reference.plaintextDigest,
                    mediaType = reference.mediaType,
                    blob = reference,
                )
                descriptors[descriptor.path] = descriptor
                archiveEntries[descriptor.path] = BlobBackupV2ArchiveEntry(blobStore, reference)
                archiveBytes += reference.byteSize
            }
            included += BackupV2AttachmentRecord(
                attachment = attachment,
                blobEntryPaths = attachment.blobs.map(::blobPath),
            )
        }

        val manifest = BackupV2ArchiveManifest(
            createdAtEpochMillis = createdAtEpochMillis,
            appVersion = appVersion,
            entries = descriptors.values.sortedBy(BackupV2EntryDescriptor::path),
            attachments = included,
            omittedAttachments = omitted,
        )
        val envelope = BackupV2ManifestEnvelope(manifest, BackupV2ManifestCodec.digest(manifest))
        val manifestBytes = BackupV2ManifestCodec.encode(envelope)
        require(archiveBytes + manifestBytes.size <= policy.maximumArchiveBytes) {
            "Backup manifest exceeds the archive limit"
        }
        archiveEntries[MANIFEST_PATH] = MemoryBackupV2ArchiveEntry(manifestBytes)
        return BackupV2CreateResult(BackupV2Archive.fromOwnedEntries(archiveEntries), envelope)
    }

    public fun inspect(
        archive: BackupV2Archive,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): BackupV2Inspection {
        require(maximumArchiveBytes in 1..Int.MAX_VALUE.toLong()) { "Backup archive limit is invalid" }
        val manifestEntry = archive.entry(MANIFEST_PATH)
            ?: throw BackupFormatException("Content backup manifest is missing")
        if (manifestEntry.byteSize > MAX_MANIFEST_BYTES) throw BackupFormatException("Backup manifest is too large")
        val manifestBytes = manifestEntry.readFully()
        val envelope = BackupV2ManifestCodec.decode(manifestBytes)
        val expectedPaths = envelope.manifest.entries.mapTo(linkedSetOf(MANIFEST_PATH)) { it.path }
        if (archive.paths != expectedPaths) {
            throw BackupFormatException("Content backup contains missing or undeclared entries")
        }
        var total = manifestBytes.size.toLong()
        envelope.manifest.entries.forEach { descriptor ->
            val entry = archive.entry(descriptor.path)
                ?: throw BackupFormatException("Content backup entry is missing")
            total = checkedAdd(total, entry.byteSize)
            if (total > maximumArchiveBytes) throw BackupFormatException("Content backup is too large")
            verifyArchiveEntry(entry, descriptor.byteSize, descriptor.sha256)
        }
        val stateEntry = requireNotNull(archive.entry(PORTABLE_STATE_PATH))
        if (stateEntry.byteSize > MAX_PORTABLE_STATE_BYTES) {
            throw BackupFormatException("Portable backup metadata is too large")
        }
        val stateBytes = stateEntry.readFully()
        val state = try {
            BackupV2Json.decodeFromString<BackupV2PortableState>(stateBytes.decodeToString()).validate()
        } catch (error: IllegalArgumentException) {
            throw BackupFormatException(error.message ?: "Invalid portable backup state")
        }
        return BackupV2Inspection(envelope, state)
    }

    /** Publish verified body entries; callers retain receipts for one shared SQL transaction. */
    public fun publishBodies(
        archive: BackupV2Archive,
        blobStore: ContentBlobStore,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): BackupV2PublishedBodies {
        val inspection = inspect(archive, maximumArchiveBytes)
        val descriptors = inspection.envelope.manifest.entries
            .filter { it.kind == BackupV2EntryKind.CONTENT_BLOB }
            .associateBy(BackupV2EntryDescriptor::path)
        val receipts = descriptors.values.sortedBy(BackupV2EntryDescriptor::path).map { descriptor ->
            val reference = requireNotNull(descriptor.blob)
            val entry = archive.entry(descriptor.path)
                ?: throw BackupFormatException("Content backup entry is missing")
            publishArchiveEntry(entry, reference, blobStore)
        }
        return BackupV2PublishedBodies(
            inspection = inspection,
            receipts = receipts,
            attachments = inspection.envelope.manifest.attachments.map(BackupV2AttachmentRecord::attachment),
        )
    }

    private fun verifyStoredBlob(blobStore: ContentBlobStore, reference: BlobRef): Boolean {
        val lease = blobStore.openRead(reference) ?: return false
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        return try {
            var total = 0L
            while (true) {
                val chunk = lease.readChunk() ?: break
                require(chunk.isNotEmpty()) { "Blob reader returned an empty non-EOF chunk" }
                total = checkedAdd(total, chunk.size.toLong())
                if (total > reference.byteSize) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
                buffer.write(chunk)
                hashing.write(buffer, chunk.size.toLong())
            }
            if (total != reference.byteSize || hashing.hash.hex() != reference.plaintextDigest) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            true
        } finally {
            try {
                hashing.close()
            } finally {
                lease.close()
            }
        }
    }

    private fun verifyArchiveEntry(
        entry: BackupV2ArchiveEntry,
        expectedSize: Long,
        expectedSha256: String,
    ) {
        if (entry.byteSize != expectedSize) {
            throw BackupFormatException("Content backup entry size mismatch")
        }
        val reader = try {
            entry.open()
        } catch (error: Throwable) {
            throw BackupFormatException(error.message ?: "Content backup entry is unavailable")
        }
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        try {
            var total = 0L
            while (true) {
                val chunk = reader.readChunk() ?: break
                if (chunk.isEmpty() || total > expectedSize - chunk.size) {
                    throw BackupFormatException("Content backup entry size mismatch")
                }
                total += chunk.size
                buffer.write(chunk)
                hashing.write(buffer, chunk.size.toLong())
            }
            if (total != expectedSize || hashing.hash.hex() != expectedSha256) {
                throw BackupFormatException("Content backup entry checksum mismatch")
            }
        } finally {
            try {
                hashing.close()
            } finally {
                reader.close()
            }
        }
    }

    private fun publishArchiveEntry(
        entry: BackupV2ArchiveEntry,
        reference: BlobRef,
        blobStore: ContentBlobStore,
    ): dev.shinsou.kmp.content.BlobPublishReceipt {
        if (entry.byteSize != reference.byteSize) {
            throw BackupFormatException("Content backup entry size mismatch")
        }
        val stage = blobStore.beginStage(reference.byteSize, reference.mediaType)
        val reader = try {
            entry.open()
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
        return try {
            var total = 0L
            while (true) {
                val chunk = reader.readChunk() ?: break
                if (chunk.isEmpty() || total > reference.byteSize - chunk.size) {
                    throw BackupFormatException("Content backup entry size mismatch")
                }
                stage.append(chunk)
                total += chunk.size
            }
            if (total != reference.byteSize) throw BackupFormatException("Content backup entry is truncated")
            blobStore.publish(stage.seal(reference))
        } catch (error: Throwable) {
            stage.abort()
            throw error
        } finally {
            reader.close()
        }
    }

    /**
     * Annotation bodies and quote selectors are exportable content, even in a metadata-only
     * archive. Prefer the exact manifest-scoped requests supplied by the host. A tombstone for an
     * old revision may no longer have a manifest candidate, so it falls back to the durable
     * acquisition grant and still fails closed when that acquisition is absent or more narrowly
     * scoped than the annotation.
     */
    private fun annotationExportRequests(
        annotation: ContentAnnotation,
        state: BackupV2PortableState,
        candidates: List<BackupV2AttachmentCandidate>,
    ): List<ContentAccessRequest> {
        val scope = annotation.scope
        val textCharacters = annotation.exportTextCharacters()
        val exact = candidates.asSequence()
            .filter { candidate ->
                val owner = candidate.attachment.owner
                owner.publicationKey == scope.publicationId &&
                    owner.acquisitionId == scope.acquisitionId &&
                    owner.unitKey == scope.unitId &&
                    candidate.attachment.contentRevision == scope.contentRevision
            }
            .map { candidate -> candidate.access.withTextCharacters(textCharacters) }
            .toList()
        if (exact.isNotEmpty()) return exact

        val acquisition = state.publications
            .firstOrNull { it.key == scope.publicationId }
            ?.acquisitions
            ?.firstOrNull { it.id == scope.acquisitionId }
            ?: return emptyList()
        return listOf(
            ContentAccessRequest(
                grantReference = acquisition.rightsGrantRef,
                scope = RightsScope(
                    publicationId = scope.publicationId,
                    acquisitionId = scope.acquisitionId,
                    unitId = scope.unitId,
                    contentRevision = scope.contentRevision,
                ),
                context = RightsOperationContext(textCharacters = textCharacters),
            ),
        )
    }
}

public data class BackupV2PublishedBodies(
    val inspection: BackupV2Inspection,
    val receipts: List<dev.shinsou.kmp.content.BlobPublishReceipt>,
    val attachments: List<ManifestAttachment>,
)

/** Bounded deterministic file encoding with no path traversal or undeclared trailing entries. */
public object BackupV2BinaryCodec {
    public fun encode(
        archive: BackupV2Archive,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): ByteArray {
        PortableContentBackupV2Service.inspect(archive, maximumArchiveBytes)
        val ordered = ordered(archive)
        val encodedSize = encodedSizeOf(ordered, maximumArchiveBytes)
        if (encodedSize > Int.MAX_VALUE) throw BackupFormatException("Content backup is too large")
        val output = ByteArray(encodedSize.toInt())
        var offset = 0
        writeEncoded(ordered, BackupV2BinarySink { chunk ->
            if (chunk.size > output.size - offset) throw BackupFormatException("Content backup size changed")
            chunk.copyInto(output, offset)
            offset += chunk.size
        }, maximumArchiveBytes)
        if (offset != output.size) throw BackupFormatException("Content backup size changed")
        return output
    }

    /** Writes a verified archive incrementally without collecting boxed bytes or body maps. */
    public fun encodeTo(
        archive: BackupV2Archive,
        sink: BackupV2BinarySink,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): Long {
        PortableContentBackupV2Service.inspect(archive, maximumArchiveBytes)
        val ordered = ordered(archive)
        return writeEncoded(ordered, sink, maximumArchiveBytes)
    }

    /**
     * Exact container size for an archive already verified with
     * [PortableContentBackupV2Service.inspect]. This avoids a throwaway encoding pass while a UI
     * prepares a streaming platform export.
     */
    internal fun encodedSizeOfInspectedArchive(
        archive: BackupV2Archive,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): Long = encodedSizeOf(ordered(archive), maximumArchiveBytes)

    private fun writeEncoded(
        ordered: List<Pair<String, BackupV2ArchiveEntry>>,
        sink: BackupV2BinarySink,
        maximumArchiveBytes: Long,
    ): Long {
        val expectedSize = encodedSizeOf(ordered, maximumArchiveBytes)
        val writer = BackupSinkWriter(sink, maximumArchiveBytes)
        writer.bytes(BINARY_MAGIC)
        writer.int(BINARY_CONTAINER_VERSION)
        writer.int(ordered.size)
        ordered.forEach { (path, entry) ->
            val pathBytes = path.encodeToByteArray()
            writer.int(pathBytes.size)
            writer.long(entry.byteSize)
            writer.bytes(pathBytes)
            val reader = entry.open()
            try {
                var payloadBytes = 0L
                while (true) {
                    val chunk = reader.readChunk() ?: break
                    if (chunk.isEmpty() || payloadBytes > entry.byteSize - chunk.size) {
                        throw BackupFormatException("Content backup entry size changed")
                    }
                    writer.bytes(chunk)
                    payloadBytes += chunk.size
                }
                if (payloadBytes != entry.byteSize) {
                    throw BackupFormatException("Content backup entry size changed")
                }
            } finally {
                reader.close()
            }
        }
        if (writer.bytesWritten != expectedSize) throw BackupFormatException("Content backup size changed")
        return writer.bytesWritten
    }

    public fun decode(
        encoded: ByteArray,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): BackupV2Archive = decodeInspected(encoded, maximumArchiveBytes).archive

    /**
     * Decodes and verifies the archive once, retaining the inspection used for the verification.
     * Callers that need both values must use this API instead of re-reading every body through
     * [PortableContentBackupV2Service.inspect].
     */
    public fun decodeInspected(
        encoded: ByteArray,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): InspectedBackupV2Archive = decodeInspected(
        ByteArrayBackupV2ArchiveSource(encoded),
        maximumArchiveBytes,
    )

    /** Decodes entry metadata as immutable source slices; bodies remain file/channel-backed. */
    public fun decode(
        source: BackupV2ArchiveSource,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): BackupV2Archive = decodeInspected(source, maximumArchiveBytes).archive

    /** Decodes entry metadata as immutable slices and verifies every checksum exactly once. */
    public fun decodeInspected(
        source: BackupV2ArchiveSource,
        maximumArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): InspectedBackupV2Archive {
        if (maximumArchiveBytes !in 1..Int.MAX_VALUE.toLong() || source.byteSize > maximumArchiveBytes) {
            throw BackupFormatException("Content backup is too large")
        }
        val reader = BackupSourceReader(source)
        if (!reader.bytes(BINARY_MAGIC.size).contentEquals(BINARY_MAGIC)) {
            throw BackupFormatException("Content backup magic is invalid")
        }
        if (reader.int() != BINARY_CONTAINER_VERSION) {
            throw BackupFormatException("Content backup container version is unsupported")
        }
        val count = reader.int()
        if (count !in 1..MAX_ARCHIVE_ENTRIES) throw BackupFormatException("Content backup entry count is invalid")
        val entries = LinkedHashMap<String, BackupV2ArchiveEntry>()
        repeat(count) {
            val pathSize = reader.int()
            if (pathSize !in 1..MAX_ARCHIVE_PATH_BYTES) throw BackupFormatException("Backup path is too large")
            val payloadSize = reader.long()
            if (payloadSize !in 0..maximumArchiveBytes) throw BackupFormatException("Backup entry is too large")
            val path = reader.bytes(pathSize).decodeToString()
            try {
                requireArchivePath(path)
            } catch (error: IllegalArgumentException) {
                throw BackupFormatException(error.message ?: "Backup path is invalid")
            }
            val payloadOffset = reader.skip(payloadSize)
            if (entries.put(path, SlicedBackupV2ArchiveEntry(source, payloadOffset, payloadSize)) != null) {
                throw BackupFormatException("Content backup contains duplicate entry paths")
            }
        }
        if (!reader.exhausted) throw BackupFormatException("Content backup contains trailing bytes")
        val archive = BackupV2Archive.fromOwnedEntries(entries)
        val inspection = PortableContentBackupV2Service.inspect(archive, maximumArchiveBytes)
        return InspectedBackupV2Archive(archive, inspection)
    }

    private fun ordered(archive: BackupV2Archive): List<Pair<String, BackupV2ArchiveEntry>> =
        archive.orderedEntries().sortedWith(
            compareBy<Pair<String, BackupV2ArchiveEntry>> { if (it.first == MANIFEST_PATH) 0 else 1 }
                .thenBy { it.first },
        )

    private fun encodedSizeOf(
        ordered: List<Pair<String, BackupV2ArchiveEntry>>,
        maximumArchiveBytes: Long,
    ): Long {
        var total = BINARY_MAGIC.size.toLong() + 4L + 4L
        ordered.forEach { (path, entry) ->
            val pathSize = path.encodeToByteArray().size.toLong()
            total = checkedAdd(total, 4L + 8L)
            total = checkedAdd(total, pathSize)
            total = checkedAdd(total, entry.byteSize)
            if (total > maximumArchiveBytes) {
                throw BackupFormatException("Content backup exceeds the configured limit")
            }
        }
        return total
    }
}

private class BackupSinkWriter(
    private val sink: BackupV2BinarySink,
    private val maximumBytes: Long,
) {
    var bytesWritten: Long = 0L
        private set
    fun int(value: Int) = longPart(value.toLong(), 4)
    fun long(value: Long) = longPart(value, 8)
    fun bytes(value: ByteArray) {
        if (bytesWritten > maximumBytes - value.size) {
            throw BackupFormatException("Content backup exceeds the configured limit")
        }
        sink.write(value)
        bytesWritten += value.size
    }
    private fun longPart(value: Long, bytes: Int) {
        require(value >= 0) { "Negative archive scalar" }
        ByteArray(bytes) { index ->
            (value ushr ((bytes - index - 1) * 8)).toByte()
        }.also(::bytes)
    }
}

private class BackupSourceReader(private val input: BackupV2ArchiveSource) {
    private var position = 0L
    val exhausted: Boolean get() = position == input.byteSize
    fun int(): Int {
        val value = scalar(4)
        if (value > Int.MAX_VALUE) throw BackupFormatException("Archive scalar overflows Int")
        return value.toInt()
    }
    fun long(): Long = scalar(8)
    fun bytes(count: Int): ByteArray = input.readExact(position, count).also { position += count }
    fun skip(count: Long): Long {
        if (count < 0 || position > input.byteSize || count > input.byteSize - position) {
            throw BackupFormatException("Content backup is truncated")
        }
        return position.also { position += count }
    }
    private fun scalar(count: Int): Long {
        var value = 0L
        bytes(count).forEach { byte ->
            if (value > (Long.MAX_VALUE ushr 8)) throw BackupFormatException("Archive scalar overflows Long")
            value = (value shl 8) or (byte.toLong() and 0xff)
        }
        return value
    }
}

private fun BackupV2ArchiveSource.readExact(offset: Long, byteCount: Int): ByteArray {
    if (offset < 0 || byteCount < 0 || offset > byteSize || byteCount.toLong() > byteSize - offset) {
        throw BackupFormatException("Content backup is truncated")
    }
    val bytes = try {
        read(offset, byteCount)
    } catch (error: BackupFormatException) {
        throw error
    } catch (error: Throwable) {
        throw BackupFormatException(error.message ?: "Content backup source read failed")
    }
    if (bytes.size != byteCount) throw BackupFormatException("Content backup source returned a truncated read")
    return bytes
}

private fun blobPath(reference: BlobRef): String = "blobs/${reference.blobId}.bin"

private fun requireArchivePath(value: String) {
    require(value.isNotBlank() && value.length <= MAX_ARCHIVE_PATH_BYTES) { "Backup path is invalid" }
    require(value == value.replace('\\', '/') && !value.startsWith('/') &&
        value.split('/').none { it.isBlank() || it == "." || it == ".." } &&
        value.none { it.isISOControl() }) { "Backup path is unsafe" }
}

private fun checkedAdd(left: Long, right: Long): Long {
    if (right < 0 || left > Long.MAX_VALUE - right) throw BackupFormatException("Backup size overflow")
    return left + right
}

private fun ContentAccessRequest.withTextCharacters(value: Long): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = context.offlineBytes,
        textCharacters = value,
        watermarkApplied = context.watermarkApplied,
    ),
)

private fun ContentAnnotation.exportTextCharacters(): Long {
    val values = buildList {
        body?.let(::add)
        range.quote?.let { quote ->
            add(quote.exact)
            add(quote.prefix)
            add(quote.suffix)
        }
        listOf(range.start, range.end).forEach { locator ->
            when (locator) {
                is dev.shinsou.kmp.reader.ReadingLocator.Image -> Unit
                is dev.shinsou.kmp.reader.ReadingLocator.Text -> locator.quote?.let { quote ->
                    add(quote.exact)
                    add(quote.prefix)
                    add(quote.suffix)
                }
                is dev.shinsou.kmp.reader.ReadingLocator.Epub -> locator.quote?.let { quote ->
                    add(quote.exact)
                    add(quote.prefix)
                    add(quote.suffix)
                }
            }
        }
    }
    return values.sumOf { it.length.toLong() }
}

private val BackupV2Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}

private const val MANIFEST_PATH = "manifest.json"
private const val PORTABLE_STATE_PATH = "metadata/portable-state-v2.json"
private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
private const val MAX_PORTABLE_STATE_BYTES = 64 * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 100_000
private const val MAX_ARCHIVE_PATH_BYTES = 4_096
private const val BACKUP_STREAM_CHUNK_BYTES = 64 * 1024
private const val BINARY_CONTAINER_VERSION = 1
private val BINARY_MAGIC = "SHINSOU2".encodeToByteArray()
private val SHA256_HEX = Regex("[0-9a-f]{64}")
public const val DEFAULT_MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024
