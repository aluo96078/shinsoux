package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.validate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable field names introduced by sync state schema v2. */
public object ContentSyncFields {
    public object Publication {
        public const val TITLE: String = "title"
        public const val AUTHOR: String = "author"
        public const val DESCRIPTION: String = "description"
        public const val COVER_BLOB_ID: String = "coverBlobId"
        public const val FAVORITE: String = "favorite"
        public const val CATEGORY_IDS: String = "categoryIds"
        public const val LEGACY_FLATTENED: String = "legacyFlattened"
        /** Ordered child identities make an incomplete remote graph fail closed. */
        public const val ACQUISITION_IDS: String = "acquisitionIds"
        public const val DOCUMENT_SHA256: String = "documentSha256"
        public const val DOCUMENT_CHUNK_COUNT: String = "documentChunkCount"
        public const val DOCUMENT_CHUNK_PREFIX: String = "documentChunk."
        public const val PROJECTION_SHA256: String = "projectionSha256"
        public const val PROJECTION_CHUNK_COUNT: String = "projectionChunkCount"
        public const val PROJECTION_CHUNK_PREFIX: String = "projectionChunk."
    }

    public object Acquisition {
        public const val PUBLICATION_KEY: String = "publicationKey"
        public const val SOURCE_KEY: String = "sourceKey"
        public const val REMOTE_CANONICAL_ID: String = "remoteCanonicalId"
        public const val RIGHTS_GRANT_ID: String = "rightsGrantId"
        public const val AVAILABILITY: String = "availability"
        public const val CONTENT_REVISION: String = "contentRevision"
        public const val UNIT_IDS: String = "unitIds"
        public const val DOCUMENT_SHA256: String = "documentSha256"
        public const val DOCUMENT_CHUNK_COUNT: String = "documentChunkCount"
        public const val DOCUMENT_CHUNK_PREFIX: String = "documentChunk."
        public const val RIGHTS_DOCUMENT_SHA256: String = "rightsDocumentSha256"
        public const val RIGHTS_DOCUMENT_CHUNK_COUNT: String = "rightsDocumentChunkCount"
        public const val RIGHTS_DOCUMENT_CHUNK_PREFIX: String = "rightsDocumentChunk."
        public const val PROJECTION_SHA256: String = "projectionSha256"
        public const val PROJECTION_CHUNK_COUNT: String = "projectionChunkCount"
        public const val PROJECTION_CHUNK_PREFIX: String = "projectionChunk."
    }

    public object Unit {
        public const val ACQUISITION_KEY: String = "acquisitionKey"
        public const val TITLE: String = "title"
        public const val SOURCE_ORDER: String = "sourceOrder"
        public const val ORDINAL: String = "ordinal"
        public const val REMOTE_CANONICAL_ID: String = "remoteCanonicalId"
        public const val MANIFEST_IDS: String = "manifestIds"
        public const val DOCUMENT_SHA256: String = "documentSha256"
        public const val DOCUMENT_CHUNK_COUNT: String = "documentChunkCount"
        public const val DOCUMENT_CHUNK_PREFIX: String = "documentChunk."
        public const val PROJECTION_SHA256: String = "projectionSha256"
        public const val PROJECTION_CHUNK_COUNT: String = "projectionChunkCount"
        public const val PROJECTION_CHUNK_PREFIX: String = "projectionChunk."
    }

    public object Manifest {
        public const val UNIT_KEY: String = "unitKey"
        public const val CONTENT_REVISION: String = "contentRevision"
        public const val CONTENT_KIND: String = "contentKind"
        public const val BLOB_IDS: String = "blobIds"
        public const val REPRESENTATION_ID: String = "representationId"
        /** Full, lossless ContentManifest split into bounded base64url fields. */
        public const val DOCUMENT_SHA256: String = "documentSha256"
        public const val DOCUMENT_CHUNK_COUNT: String = "documentChunkCount"
        public const val DOCUMENT_CHUNK_PREFIX: String = "documentChunk."
        public const val PROJECTION_SHA256: String = "projectionSha256"
        public const val PROJECTION_CHUNK_COUNT: String = "projectionChunkCount"
        public const val PROJECTION_CHUNK_PREFIX: String = "projectionChunk."
    }

    public object Annotation {
        public const val DOCUMENT_SHA256: String = "documentSha256"
        public const val DOCUMENT_CHUNK_COUNT: String = "documentChunkCount"
        public const val DOCUMENT_CHUNK_PREFIX: String = "documentChunk."
        /** Written only after every chunk; a receiver publishes no annotation without this match. */
        public const val COMMITTED_SHA256: String = "committedDocumentSha256"
    }
}

@Serializable
@SerialName("publication_patch_v2")
public data class PublicationPatchV2(
    val key: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.PUBLICATION) {
            "Publication patch requires a publication key"
        }
    }
}

@Serializable
@SerialName("acquisition_patch_v2")
public data class AcquisitionPatchV2(
    val key: SyncEntityKey,
    val publicationKey: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.ACQUISITION) {
            "Acquisition patch requires an acquisition key"
        }
        require(publicationKey.entityType == SyncEntityType.PUBLICATION) {
            "Acquisition parent requires a publication key"
        }
    }
}

@Serializable
@SerialName("publication_unit_patch_v2")
public data class PublicationUnitPatchV2(
    val key: SyncEntityKey,
    val acquisitionKey: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.PUBLICATION_UNIT) {
            "Publication-unit patch requires a unit key"
        }
        require(acquisitionKey.entityType == SyncEntityType.ACQUISITION) {
            "Publication-unit parent requires an acquisition key"
        }
    }
}

@Serializable
@SerialName("content_manifest_patch_v2")
public data class ContentManifestPatchV2(
    val key: SyncEntityKey,
    val unitKey: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.CONTENT_MANIFEST) {
            "Content-manifest patch requires a manifest key"
        }
        require(unitKey.entityType == SyncEntityType.PUBLICATION_UNIT) {
            "Content-manifest parent requires a publication-unit key"
        }
    }
}

/** LWW annotation record retained after deletion so an older replica cannot resurrect it. */
@Serializable
public data class SyncedAnnotationRecord(
    val annotationId: String,
    val annotation: LwwRegister<ContentAnnotation>? = null,
    val presence: LwwRegister<Boolean>? = null,
) {
    init {
        requireCanonicalContentUuid(annotationId, "Annotation id")
        annotation?.let { require(it.value.annotationId == annotationId) }
    }

    public val isPresent: Boolean
        get() = presence?.value == true && annotation?.value?.state?.name != "TOMBSTONE"
}

@Serializable
@SerialName("content_annotation_put_v2")
public data class ContentAnnotationPutV2(
    val annotation: ContentAnnotation,
) : SyncMutation {
    init {
        annotation.validate()
    }
}

/**
 * Bounded staging mutation for an annotation document. Chunks live in the generic entity CRDT;
 * [ContentSyncFields.Annotation.COMMITTED_SHA256] is a digest-bound commit marker applied last.
 */
@Serializable
@SerialName("content_annotation_patch_v2")
public data class ContentAnnotationPatchV2(
    val key: SyncEntityKey,
    val fields: Map<String, SyncValue>,
    val ensurePresent: Boolean = true,
) : SyncMutation {
    init {
        require(key.entityType == SyncEntityType.ANNOTATION) {
            "Content-annotation patch requires an annotation key"
        }
        require(fields.isNotEmpty()) { "Content-annotation patch is empty" }
    }
}

@Serializable
public data class PublicationCategoryMembershipKeyV2(
    val publicationKey: SyncEntityKey,
    val categoryKey: SyncEntityKey,
) : Comparable<PublicationCategoryMembershipKeyV2> {
    init {
        require(publicationKey.entityType == SyncEntityType.PUBLICATION)
        require(categoryKey.entityType == SyncEntityType.CATEGORY)
    }

    override fun compareTo(other: PublicationCategoryMembershipKeyV2): Int =
        publicationKey.compareTo(other.publicationKey).takeIf { it != 0 }
            ?: categoryKey.compareTo(other.categoryKey)
}

@Serializable
@SerialName("publication_category_membership_set_v2")
public data class PublicationCategoryMembershipSetV2(
    val publicationKey: SyncEntityKey,
    val categoryKey: SyncEntityKey,
    val present: Boolean,
) : SyncMutation {
    init {
        PublicationCategoryMembershipKeyV2(publicationKey, categoryKey)
    }
}

@Serializable
public data class ContentProgressKeyV2(
    val publicationId: String,
    val acquisitionId: String,
    val unitId: String,
) : Comparable<ContentProgressKeyV2> {
    init {
        requireCanonicalContentUuid(publicationId, "Content progress publication id")
        requireCanonicalContentUuid(acquisitionId, "Content progress acquisition id")
        requireCanonicalContentUuid(unitId, "Content progress unit id")
    }

    override fun compareTo(other: ContentProgressKeyV2): Int = compareValuesBy(
        this,
        other,
        ContentProgressKeyV2::publicationId,
        ContentProgressKeyV2::acquisitionId,
        ContentProgressKeyV2::unitId,
    )

    public fun stableString(): String = "$publicationId|$acquisitionId|$unitId"

    public companion object {
        public fun from(locator: ReadingLocator): ContentProgressKeyV2 = ContentProgressKeyV2(
            publicationId = locator.scope.publicationId.value,
            acquisitionId = locator.scope.acquisitionId,
            unitId = locator.scope.unitId.value,
        )
    }
}

@Serializable
public data class ContentReadingProgressRecordV2(
    val key: ContentProgressKeyV2,
    val locator: LwwRegister<ReadingLocator>? = null,
    val readState: LwwRegister<Boolean>? = null,
    val historyTouchedAtEpochMillis: LwwRegister<Long>? = null,
    val presence: LwwRegister<Boolean>? = null,
) {
    init {
        locator?.let { require(ContentProgressKeyV2.from(it.value) == key) }
        historyTouchedAtEpochMillis?.let { require(it.value >= 0) }
    }

    public val isPresent: Boolean get() = presence?.value == true && locator != null
}

@Serializable
@SerialName("content_reading_progress_set_v2")
public data class ContentReadingProgressSetV2(
    val locator: ReadingLocator,
    val readState: Boolean? = null,
    val historyTouchedAtEpochMillis: Long? = null,
) : SyncMutation {
    init {
        locator.validate()
        require(readState != null || historyTouchedAtEpochMillis != null) {
            "Content reading-progress mutation is empty"
        }
        historyTouchedAtEpochMillis?.let { require(it >= 0) }
    }
}

@Serializable
@SerialName("content_reading_progress_presence_set_v2")
public data class ContentReadingProgressPresenceSetV2(
    val key: ContentProgressKeyV2,
    val present: Boolean,
) : SyncMutation

/**
 * Opaque per-blob DEK envelope. The workspace epoch key encrypts the DEK; no plaintext key or
 * plaintext digest is visible to the Worker.
 */
@Serializable
public data class BlobDekEnvelopeV2(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val blobId: String,
    val keyEpoch: Int,
    val cipherSuite: String = "CHACHA20_POLY1305",
    val nonceBase64Url: String,
    val wrappedDekBase64Url: String,
    val envelopeSha256Base64Url: String,
    val previousEnvelopeSha256Base64Url: String? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported blob DEK envelope schema" }
        requireCanonicalContentUuid(blobId, "Blob id")
        require(keyEpoch > 0) { "Blob DEK envelope epoch must be positive" }
        require(cipherSuite == "CHACHA20_POLY1305") { "Unsupported blob DEK envelope cipher suite" }
        requireCanonicalBase64Url(nonceBase64Url, "Blob DEK envelope nonce")
        requireCanonicalBase64Url(wrappedDekBase64Url, "Wrapped blob DEK")
        requireCanonicalSha256Base64Url(envelopeSha256Base64Url, "Blob DEK envelope hash")
        previousEnvelopeSha256Base64Url?.let {
            requireCanonicalSha256Base64Url(it, "Previous blob DEK envelope hash")
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** Clear-but-opaque identity of a committed R2 manifest; body/resource metadata stays encrypted. */
@Serializable
public data class RemoteBlobBodyManifestRefV2(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val manifestId: String,
    val blobId: String,
    val manifestCiphertextSha256Base64Url: String,
    val manifestCiphertextByteSize: Long,
    val bodyCiphertextByteSize: Long,
    val chunkCount: Int,
    val chunkSizeBytes: Int,
    val committedAtEpochMillis: Long,
    val commitReceiptId: String,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported remote blob manifest schema" }
        requireCanonicalContentUuid(manifestId, "Remote blob manifest id")
        requireCanonicalContentUuid(blobId, "Remote blob id")
        requireCanonicalSha256Base64Url(manifestCiphertextSha256Base64Url, "Remote blob manifest hash")
        require(manifestCiphertextByteSize > 0) { "Remote blob manifest must have ciphertext" }
        require(bodyCiphertextByteSize >= 0) { "Remote blob body size cannot be negative" }
        require(chunkCount >= 0 && chunkSizeBytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) {
            "Remote blob chunk geometry is invalid"
        }
        require((chunkCount == 0) == (bodyCiphertextByteSize == 0L)) {
            "Remote empty-blob geometry is inconsistent"
        }
        require(committedAtEpochMillis >= 0) { "Remote blob commit time cannot be negative" }
        requireCanonicalContentUuid(commitReceiptId, "Remote blob commit receipt id")
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public const val MIN_CHUNK_BYTES: Int = 64 * 1024
        public const val MAX_CHUNK_BYTES: Int = 8 * 1024 * 1024
    }
}

@Serializable
public data class BlobRewrapCheckpointEvidenceV2(
    val checkpointId: String,
    val checkpointCiphertextSha256Base64Url: String,
    val throughWorkspaceSeq: Long,
) {
    init {
        requireCanonicalContentUuid(checkpointId, "Re-wrap checkpoint id")
        requireCanonicalSha256Base64Url(
            checkpointCiphertextSha256Base64Url,
            "Re-wrap checkpoint ciphertext hash",
        )
        require(throughWorkspaceSeq >= 0) { "Re-wrap checkpoint sequence cannot be negative" }
    }
}

/** Blob refs are metadata; encrypted body bytes remain exclusively in the R2 body plane. */
@Serializable
public data class SyncedBlobReferenceRecord(
    val blobId: String,
    /** Monotonic remote-body lifecycle incarnation; missing legacy v2 state decodes as one. */
    val generation: Long = 1,
    val blob: LwwRegister<BlobRef>? = null,
    val remoteManifest: LwwRegister<RemoteBlobBodyManifestRefV2>? = null,
    val dekEnvelopes: Map<Int, LwwRegister<BlobDekEnvelopeV2>> = emptyMap(),
    val presence: LwwRegister<Boolean>? = null,
) {
    init {
        requireCanonicalContentUuid(blobId, "Synced blob id")
        require(generation > 0) { "Synced blob generation must be positive" }
        blob?.let { require(it.value.blobId == blobId) }
        remoteManifest?.let { require(it.value.blobId == blobId) }
        dekEnvelopes.forEach { (epoch, envelope) ->
            require(epoch > 0 && envelope.value.keyEpoch == epoch && envelope.value.blobId == blobId) {
                "Synced blob DEK envelope identity mismatch"
            }
        }
    }

    public val isRemotelyAvailable: Boolean
        get() = presence?.value == true && blob != null && remoteManifest != null &&
            dekEnvelopes.isNotEmpty()
}

/** Published only after the Worker returns an exact committed-manifest receipt. */
@Serializable
@SerialName("blob_reference_commit_v2")
public data class BlobReferenceCommitV2(
    val blob: BlobRef,
    val remoteManifest: RemoteBlobBodyManifestRefV2,
    val dekEnvelope: BlobDekEnvelopeV2,
    val generation: Long = 1,
) : SyncMutation {
    init {
        blob.validate()
        require(blob.blobId == remoteManifest.blobId && blob.blobId == dekEnvelope.blobId) {
            "Committed blob reference identities do not match"
        }
        require(generation > 0) { "Committed blob generation must be positive" }
        require(dekEnvelope.previousEnvelopeSha256Base64Url == null) {
            "An initial blob commit requires an initial DEK envelope"
        }
    }
}

@Serializable
public enum class BlobReincarnationTerminalKindV2 {
    @SerialName("reupload_required")
    REUPLOAD_REQUIRED,

    @SerialName("gc_completed")
    GC_COMPLETED,
}

/**
 * Durable proof carried from the local Worker lifecycle journal into the metadata event.
 *
 * The reducer cannot trust a bare `generation + 1`: it also requires the exact previous manifest
 * and either the Worker's terminal reupload result or an exact completed-GC receipt identity.
 */
@Serializable
public data class BlobReincarnationEvidenceV2(
    val previousManifestId: String,
    val tombstoneId: String,
    val terminalKind: BlobReincarnationTerminalKindV2,
    val gcReceiptId: String? = null,
) {
    init {
        requireCanonicalContentUuid(previousManifestId, "Previous blob manifest id")
        requireCanonicalContentUuid(tombstoneId, "Blob reincarnation tombstone id")
        when (terminalKind) {
            BlobReincarnationTerminalKindV2.REUPLOAD_REQUIRED -> require(gcReceiptId == null) {
                "Reupload-required evidence cannot claim a GC receipt"
            }
            BlobReincarnationTerminalKindV2.GC_COMPLETED ->
                requireCanonicalContentUuid(
                    requireNotNull(gcReceiptId) { "Completed GC evidence requires its receipt id" },
                    "Blob GC receipt id",
                )
        }
    }
}

/** Atomic, evidence-bound replacement of one irreversibly retired remote blob generation. */
@Serializable
@SerialName("blob_reference_reincarnation_commit_v2")
public data class BlobReferenceReincarnationCommitV2(
    val blob: BlobRef,
    val remoteManifest: RemoteBlobBodyManifestRefV2,
    val initialDekEnvelope: BlobDekEnvelopeV2,
    val generation: Long,
    val evidence: BlobReincarnationEvidenceV2,
) : SyncMutation {
    init {
        blob.validate()
        require(
            blob.blobId == remoteManifest.blobId &&
                blob.blobId == initialDekEnvelope.blobId,
        ) { "Reincarnated blob reference identities do not match" }
        require(generation > 1) { "Blob reincarnation must advance beyond generation one" }
        require(initialDekEnvelope.previousEnvelopeSha256Base64Url == null) {
            "A reincarnated body must install a fresh initial DEK envelope"
        }
        require(remoteManifest.manifestId != evidence.previousManifestId) {
            "Blob reincarnation must install a different remote manifest"
        }
    }
}

@Serializable
@SerialName("blob_dek_envelope_rewrapped_v2")
public data class BlobDekEnvelopeRewrappedV2(
    val blobId: String,
    val manifestId: String,
    val envelope: BlobDekEnvelopeV2,
    val checkpointEvidence: BlobRewrapCheckpointEvidenceV2,
    val generation: Long = 1,
) : SyncMutation {
    init {
        requireCanonicalContentUuid(blobId, "Re-wrapped blob id")
        requireCanonicalContentUuid(manifestId, "Re-wrapped manifest id")
        require(generation > 0) { "Re-wrapped blob generation must be positive" }
        require(envelope.blobId == blobId && envelope.previousEnvelopeSha256Base64Url != null) {
            "Re-wrapped blob envelope is not chained to its previous epoch"
        }
    }
}

@Serializable
@SerialName("blob_reference_presence_set_v2")
public data class BlobReferencePresenceSetV2(
    val blobId: String,
    val present: Boolean,
) : SyncMutation {
    init {
        requireCanonicalContentUuid(blobId, "Blob presence id")
    }
}

private val CONTENT_UUID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val CONTENT_BASE64URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")

internal fun requireCanonicalContentUuid(value: String, label: String) {
    require(value == value.lowercase() && CONTENT_UUID_PATTERN.matches(value)) {
        "$label must be a canonical UUID"
    }
}

internal fun requireCanonicalBase64Url(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_SYNC_BASE64URL_CHARS &&
        CONTENT_BASE64URL_PATTERN.matches(value) && '=' !in value) {
        "$label must be canonical unpadded base64url"
    }
}

internal fun requireCanonicalSha256Base64Url(value: String, label: String) {
    requireCanonicalBase64Url(value, label)
    require(value.length == SHA256_BASE64URL_CHARS) { "$label must encode exactly 32 bytes" }
}

private const val SHA256_BASE64URL_CHARS: Int = 43
private const val MAX_SYNC_BASE64URL_CHARS: Int = 16 * 1024 * 1024
