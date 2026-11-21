package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.domain.model.RightsGrantRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.deepImmutableSnapshot
import dev.shinsou.kmp.domain.content.immutableListOf

@Serializable
public data class ContentMetadataMutation(val key: String, val value: String) {
    init {
        requireSafeTransactionKey(key, "Metadata key")
        require(value.length <= MAX_TRANSACTION_VALUE_LENGTH) { "Metadata value is too large" }
        require(value.none(Char::isISOControl)) { "Metadata value contains control characters" }
    }
}

@Serializable
public data class ContentAliasMutation(val alias: String, val target: String) {
    init {
        requireSafeTransactionKey(alias, "Alias")
        requireSafeTransactionKey(target, "Alias target")
    }
}

/** Typed publication-domain row admitted by the same transaction as manifests and migration data. */
@Serializable
public data class ContentPublicationMutation(val publication: Publication) {
    init { publication.validate() }

    public val publicationKey: PublicationKey get() = publication.key

    internal fun deepImmutableSnapshot(): ContentPublicationMutation = ContentPublicationMutation(
        CANONICAL_JSON.decodeFromString(
            Publication.serializer(),
            CANONICAL_JSON.encodeToString(Publication.serializer(), publication),
        ),
    )
}

/** Non-manifest immutable bytes which must remain live across recovery/GC. */
@Serializable
public data class AuxiliaryBlobAttachment(
    val ownerId: String,
    val purpose: AuxiliaryBlobPurpose,
    val blobs: List<BlobRef>,
) {
    init {
        requireSafeTransactionKey(ownerId, "Auxiliary blob owner id")
        require(blobs.isNotEmpty()) { "An auxiliary attachment needs at least one blob" }
        require(blobs.map(BlobRef::blobId).distinct().size == blobs.size) {
            "Auxiliary attachment blob ids must be unique"
        }
        blobs.forEach(BlobRef::validate)
    }

    public val attachmentKey: String get() = "${purpose.name.lowercase()}:$ownerId"

    internal fun deepImmutableSnapshot(): AuxiliaryBlobAttachment = copy(
        blobs = immutableListOf(blobs.map { it.copy() }),
    )
}

@Serializable
public enum class AuxiliaryBlobPurpose {
    PLUGIN_QUARANTINE,
}

/**
 * A quarantined package occurrence. Executable text is deliberately represented only by an
 * immutable blob reference; decode, preview and commit never carry inline JavaScript.
 */
@Serializable
public data class ContentQuarantineMutation(
    val quarantineId: String,
    val packageId: String,
    val version: String,
    /** Exact legacy package revision; zero is retained as invalid/unreviewed rather than invented. */
    val versionCode: Int = 0,
    val sourceIds: List<String>,
    val origin: String,
    val ordinal: Int,
    val scriptBlob: BlobRef,
    val enabledHint: Boolean? = null,
    val installedAtEpochMillis: Long? = null,
) {
    init {
        requireSafeTransactionKey(quarantineId, "Quarantine id")
        requireSafeTransactionKey(packageId, "Quarantined package id")
        require(version.isNotBlank() && version.length <= 512 && version.none(Char::isISOControl)) {
            "Quarantined package version is invalid"
        }
        require(versionCode >= 0) { "Quarantined package version code is invalid" }
        require(sourceIds.size <= 1_024) {
            "Quarantined package source list is invalid"
        }
        require(sourceIds.distinct().size == sourceIds.size) {
            "Quarantined package source ids must be unique"
        }
        sourceIds.forEach { requireSafeTransactionKey(it, "Quarantined source id") }
        requireSafeTransactionKey(origin, "Quarantine origin")
        require(ordinal >= 0) { "Quarantine ordinal must be non-negative" }
        scriptBlob.validate()
        require(scriptBlob.mediaType == QUARANTINED_SCRIPT_MEDIA_TYPE) {
            "Quarantined script must use $QUARANTINED_SCRIPT_MEDIA_TYPE"
        }
        require(installedAtEpochMillis == null || installedAtEpochMillis >= 0) {
            "Quarantine installation timestamp is invalid"
        }
    }

    public val auxiliaryOwnerId: String get() = "quarantine:$quarantineId"

    internal fun deepImmutableSnapshot(): ContentQuarantineMutation = copy(
        sourceIds = immutableListOf(sourceIds),
        scriptBlob = scriptBlob.copy(),
    )

    public companion object {
        public const val QUARANTINED_SCRIPT_MEDIA_TYPE: String = "application/javascript"
    }
}

/** Full host policy paired atomically with Acquisition's persisted grant reference. */
@Serializable
public data class ContentRightsGrantMutation(val grant: RightsGrant) {
    init { grant.validate() }

    public val grantReference: RightsGrantRef get() = grant.grantId

    internal fun deepImmutableSnapshot(): ContentRightsGrantMutation = ContentRightsGrantMutation(
        CANONICAL_JSON.decodeFromString(
            RightsGrant.serializer(),
            CANONICAL_JSON.encodeToString(RightsGrant.serializer(), grant),
        ),
    )
}

/** Durable local body-upload work queued atomically with a manifest and its sync metadata draft. */
@Serializable
public data class ContentBlobReincarnationEvidence(
    /** Remote manifest that was irreversibly deleted or declared terminal by the Worker. */
    val previousManifestId: String,
    val tombstoneId: String,
    /** Exactly one generation step is admitted; arbitrary manifest swaps remain invalid. */
    val previousGeneration: Long,
    val terminalKind: ContentBlobReincarnationTerminalKind,
    /** Present only when the terminal evidence is an exact completed-GC receipt. */
    val gcReceiptId: String? = null,
) {
    init {
        require(PublicationKey.isPortableUuid(previousManifestId)) {
            "Previous remote manifest id must be a UUID"
        }
        require(PublicationKey.isPortableUuid(tombstoneId)) {
            "Blob reincarnation tombstone id must be a UUID"
        }
        require(previousGeneration > 0) { "Previous blob generation must be positive" }
        when (terminalKind) {
            ContentBlobReincarnationTerminalKind.REUPLOAD_REQUIRED ->
                require(gcReceiptId == null) { "Reupload-required evidence cannot claim a GC receipt" }
            ContentBlobReincarnationTerminalKind.GC_COMPLETED -> {
                val receiptId = requireNotNull(gcReceiptId) { "Completed GC evidence requires its receipt id" }
                require(PublicationKey.isPortableUuid(receiptId)) { "Blob GC receipt id must be a UUID" }
            }
        }
    }
}

@Serializable
public enum class ContentBlobReincarnationTerminalKind {
    REUPLOAD_REQUIRED,
    GC_COMPLETED,
}

@Serializable
public data class ContentBlobSyncJobMutation(
    val jobId: String,
    val blob: BlobRef,
    val owner: ContentManifestOwner,
    val manifestId: String,
    val contentRevision: Long,
    val grantReference: RightsGrantRef,
    /** Generation one is the legacy/current v2 upload path. */
    val generation: Long = 1,
    /** Non-null only when a Worker terminal permits generation + 1 for the same blob id. */
    val reincarnationEvidence: ContentBlobReincarnationEvidence? = null,
) {
    init {
        requireSafeTransactionKey(jobId, "Blob sync job id")
        blob.validate()
        owner.validate()
        require(PublicationKey.isPortableUuid(manifestId)) { "Blob sync job manifest id must be a UUID" }
        require(contentRevision >= 0) { "Blob sync job revision cannot be negative" }
        grantReference.validate()
        require(generation > 0) { "Blob sync job generation must be positive" }
        reincarnationEvidence?.let { evidence ->
            require(generation == evidence.previousGeneration + 1) {
                "Blob reincarnation job must advance exactly one generation"
            }
        }
        require(generation == 1L || reincarnationEvidence != null) {
            "A later blob generation requires terminal reincarnation evidence"
        }
    }

    public val accessScope: dev.shinsou.kmp.rights.RightsScope
        get() = dev.shinsou.kmp.rights.RightsScope(
            publicationId = owner.publicationKey,
            acquisitionId = owner.acquisitionId,
            unitId = owner.unitKey,
            manifestId = manifestId,
            contentRevision = contentRevision,
        )
}

/**
 * Durable per-publication boundary for an authenticated schema-v2 replica projection.
 *
 * [throughWorkspaceSeq] is the monotonic server ordering authority. The fingerprint binds that
 * sequence to either the complete verified publication graph or its presence tombstone, so the
 * same sequence can never be reused for different local content.
 */
@Serializable
public data class ContentPublicationReplicaCursor(
    val publicationKey: PublicationKey,
    val instanceId: String,
    val workspaceId: String,
    val throughWorkspaceSeq: Long,
    val present: Boolean,
    val graphFingerprintSha256: String,
) {
    init {
        publicationKey.validate()
        requireSafeTransactionKey(instanceId, "Replica instance id")
        requireSafeTransactionKey(workspaceId, "Replica workspace id")
        require(throughWorkspaceSeq >= 0) { "Replica workspace sequence cannot be negative" }
        require(SHA256_HEX.matches(graphFingerprintSha256)) {
            "Replica graph fingerprint must be lowercase SHA-256"
        }
    }

    public val sourceIdentity: String get() = "$instanceId/$workspaceId"
}

/** Exact remote authority whose local CAS metadata must not survive workspace departure. */
public data class ContentReplicaAuthority(
    val instanceId: String,
    val workspaceId: String,
) {
    init {
        requireSafeTransactionKey(instanceId, "Replica authority instance id")
        requireSafeTransactionKey(workspaceId, "Replica authority workspace id")
    }
}

/**
 * Result of detaching one sync authority while retaining the complete local publication graph.
 * Removal intents are authority-bound GC hints, so they are retired with the cursors and journal.
 */
public data class ContentReplicaAuthorityDepartureResult(
    val authority: ContentReplicaAuthority,
    val removedCursorCount: Int,
    val removedCommitCount: Int,
    val removedBlobRemovalIntentCount: Int,
) {
    init {
        require(removedCursorCount >= 0)
        require(removedCommitCount >= 0)
        require(removedBlobRemovalIntentCount >= 0)
    }
}

/** Exact compare-and-swap request for one publication-scoped replica graph. */
@Serializable
public data class ContentPublicationReplicaReplacement(
    val expected: ContentPublicationReplicaCursor?,
    val replacement: ContentPublicationReplicaCursor,
) {
    init {
        expected?.let { previous ->
            require(previous.publicationKey == replacement.publicationKey) {
                "Replica CAS publication identity changed"
            }
            require(previous.instanceId == replacement.instanceId &&
                previous.workspaceId == replacement.workspaceId) {
                "Replica CAS cannot cross sync authorities"
            }
            require(replacement.throughWorkspaceSeq > previous.throughWorkspaceSeq) {
                "Replica replacement must advance the authenticated workspace sequence"
            }
        }
    }

    public val commitId: String
        get() = "$CONTENT_REPLICA_COMMIT_ID_PREFIX${replacement.publicationKey.value}:" +
            "${replacement.throughWorkspaceSeq}:${replacement.graphFingerprintSha256}"

    public val conflictId: String get() = "replica-cas:${replacement.publicationKey.value}"
}

public enum class ContentBlobRemovalReason {
    REPLICA_REPLACED,
    REPLICA_TOMBSTONED,
}

/**
 * Durable local lifecycle work emitted when a replica replacement detaches immutable bodies.
 * A consumer must still recheck all live manifest references before local or remote GC.
 */
@Serializable
public data class ContentBlobRemovalIntent(
    val intentId: String,
    val publicationKey: PublicationKey,
    val sourceCursor: ContentPublicationReplicaCursor,
    val reason: ContentBlobRemovalReason,
    val removedBlobs: List<BlobRef>,
) {
    init {
        requireSafeTransactionKey(intentId, "Blob removal intent id")
        publicationKey.validate()
        require(sourceCursor.publicationKey == publicationKey) {
            "Blob removal intent cursor has the wrong publication"
        }
        require(removedBlobs.isNotEmpty()) { "Blob removal intent cannot be empty" }
        require(removedBlobs.map(BlobRef::blobId) == removedBlobs.map(BlobRef::blobId).distinct().sorted()) {
            "Removed blob references must use unique sorted identities"
        }
        removedBlobs.forEach(BlobRef::validate)
        require((reason == ContentBlobRemovalReason.REPLICA_TOMBSTONED) == !sourceCursor.present) {
            "Blob removal reason does not match replica presence"
        }
    }
}

/**
 * Durable idempotency record for one inspected import. The source digest identifies the exact
 * backup/archive bytes; resultFingerprint binds that digest to the portable identities and
 * metadata produced by staging, so rerunning altered migration logic fails instead of duplicating
 * publications, categories, blobs, or quarantine entries.
 */
@Serializable
public data class ContentMigrationLedgerMutation(
    val namespace: String,
    val sourceDigestSha256: String,
    val resultFingerprintSha256: String,
) {
    init {
        requireSafeTransactionKey(namespace, "Migration namespace")
        require(SHA256_HEX.matches(sourceDigestSha256)) { "Migration source digest must be lowercase SHA-256" }
        require(SHA256_HEX.matches(resultFingerprintSha256)) { "Migration result fingerprint must be lowercase SHA-256" }
    }

    public val migrationKey: String get() = "$namespace:$sourceDigestSha256"
    /** Deterministic transaction id makes a retry of the same backup hit commit replay. */
    public val commitId: String get() = "migration:$migrationKey"
}

/** Result of checking the durable migration identity before staging ephemeral blob receipts. */
public enum class ContentMigrationLookupStatus { MISSING, REPLAY, CONFLICT }

public data class ContentMigrationLedgerLookup(
    val status: ContentMigrationLookupStatus,
    val namespace: String,
    val sourceDigestSha256: String,
    val requestedResultFingerprintSha256: String,
    val deterministicCommitId: String,
    val existing: ContentMigrationLedgerMutation? = null,
) {
    init {
        requireSafeTransactionKey(namespace, "Migration namespace")
        require(SHA256_HEX.matches(sourceDigestSha256)) { "Migration source digest must be lowercase SHA-256" }
        require(SHA256_HEX.matches(requestedResultFingerprintSha256)) {
            "Migration result fingerprint must be lowercase SHA-256"
        }
        require(deterministicCommitId == "migration:$namespace:$sourceDigestSha256") {
            "Migration lookup commit id is not deterministic"
        }
        when (status) {
            ContentMigrationLookupStatus.MISSING -> require(existing == null) {
                "Missing migration lookup cannot carry an existing ledger"
            }
            ContentMigrationLookupStatus.REPLAY,
            ContentMigrationLookupStatus.CONFLICT,
            -> require(existing != null) { "Resolved migration lookup must carry an existing ledger" }
        }
    }

    public val isReplay: Boolean get() = status == ContentMigrationLookupStatus.REPLAY
    public val isConflict: Boolean get() = status == ContentMigrationLookupStatus.CONFLICT
}

public typealias ContentMetadataDraft = ContentMetadataMutation

public typealias ContentAliasDraft = ContentAliasMutation

/** Host-specific bridge to a real outbox draft type. */
public interface ContentOutboxAdapter<D : Any> {
    public fun validate(draft: D)
    public fun id(draft: D): String
    /** Canonical, deep fingerprint bytes; callers length-prefix these bytes in the batch digest. */
    public fun fingerprint(draft: D): ByteArray
    public fun isRepresentableByCurrentV1(draft: D): Boolean
}

/**
 * Durable companion to [ContentOutboxAdapter].  A content transaction stores an outbox draft
 * as an opaque payload, while the adapter remains the authority for its id, validation and
 * v1 representability.  Keeping this as a separate interface preserves source compatibility
 * for existing in-memory adapters which do not need persistence.
 */
public interface ContentOutboxPersistenceAdapter<D : Any> : ContentOutboxAdapter<D> {
    /** Returns a bounded, deterministic UTF-8 payload suitable for a SQLite TEXT column. */
    public fun encode(draft: D): String

    /** Decodes a payload previously returned by [encode]; implementations must validate it. */
    public fun decode(payload: String): D
}

/**
 * An outbox row left behind by a version of the app that queued sync drafts next to its content
 * commits. Nothing produces one any more and nothing reads the payload: it is kept only so the
 * rows can be listed and removed ([ContentOutboxDiscarder]).
 */
public data class LegacyOutboxDraft(val draftId: String, val payload: String)

/** Stores a [LegacyOutboxDraft] as the payload it was read from. */
public object LegacyOutboxDraftAdapter : ContentOutboxPersistenceAdapter<LegacyOutboxDraft> {
    override fun validate(draft: LegacyOutboxDraft) {
        requireSafeTransactionKey(draft.draftId, "Outbox draft id")
    }

    override fun id(draft: LegacyOutboxDraft): String = draft.draftId

    override fun fingerprint(draft: LegacyOutboxDraft): ByteArray = draft.payload.encodeToByteArray()

    override fun encode(draft: LegacyOutboxDraft): String = draft.payload

    override fun decode(payload: String): LegacyOutboxDraft {
        val draftId = ((Json.parseToJsonElement(payload) as? JsonObject)?.get("draftId") as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
        return LegacyOutboxDraft(requireNotNull(draftId) { "Outbox payload has no draft id" }, payload)
            .also(::validate)
    }

    override fun isRepresentableByCurrentV1(draft: LegacyOutboxDraft): Boolean = true
}

public enum class ContentSyncMode { V1_ACTIVE, V2_ACTIVE, INACTIVE }

public enum class UnrepresentableDraftPolicy { REJECT, DEFER }

/**
 * Controls whether a batch appends immutable content rows or owns the complete portable graph.
 *
 * [REPLACE_PORTABLE_GRAPH] replaces publications, manifest attachments, rights grants, pending
 * content outbox drafts, and body-sync jobs. Metadata, aliases, and migration ledgers supplied by
 * the archive are merged into the device-local authority; existing rows are never deleted.
 * Auxiliary attachments and quarantine bookkeeping are device-local and cannot be imported by
 * this operation.
 */
public enum class ContentCommitSemantics {
    MERGE_IMMUTABLE,
    REPLACE_PORTABLE_GRAPH,
    /** Replaces exactly one verified publication graph behind a durable replica CAS cursor. */
    REPLACE_PUBLICATION_REPLICA,
}

public data class ContentCommitResult(
    val commitId: String,
    val replayed: Boolean,
    val deferred: Boolean,
    val committedGeneration: Long?,
    val attachedOwnerIds: List<String>,
    val outboxDraftIds: List<String>,
    val migrationKeys: List<String> = emptyList(),
    val publicationIds: List<String> = emptyList(),
    val auxiliaryAttachmentIds: List<String> = emptyList(),
    val quarantineIds: List<String> = emptyList(),
    val rightsGrantIds: List<String> = emptyList(),
    val blobSyncJobIds: List<String> = emptyList(),
    val blobRemovalIntentIds: List<String> = emptyList(),
)

public typealias ContentCommitReceipt = ContentCommitResult

/** Local, non-serializable batch because it contains one-use blob receipt capabilities. */
public class ContentCommitBatch<D : Any>(
    commitId: String,
    receipts: List<BlobPublishReceipt> = emptyList(),
    attachments: List<ManifestAttachment> = emptyList(),
    metadata: List<ContentMetadataMutation> = emptyList(),
    aliases: List<ContentAliasMutation> = emptyList(),
    outbox: List<D> = emptyList(),
    migrations: List<ContentMigrationLedgerMutation> = emptyList(),
    publications: List<ContentPublicationMutation> = emptyList(),
    auxiliaryAttachments: List<AuxiliaryBlobAttachment> = emptyList(),
    quarantines: List<ContentQuarantineMutation> = emptyList(),
    rightsGrants: List<ContentRightsGrantMutation> = emptyList(),
    blobSyncJobs: List<ContentBlobSyncJobMutation> = emptyList(),
    replicaReplacement: ContentPublicationReplicaReplacement? = null,
    public val unrepresentableDraftPolicy: UnrepresentableDraftPolicy = UnrepresentableDraftPolicy.REJECT,
    public val semantics: ContentCommitSemantics = ContentCommitSemantics.MERGE_IMMUTABLE,
) {
    public val commitId: String = commitId.also { requireSafeTransactionKey(it, "Content commit id") }
    public val receipts: List<BlobPublishReceipt> = immutableListOf(receipts)
    /** Attachments are deep-snapshotted before the batch becomes observable to a store. */
    public val attachments: List<ManifestAttachment> = immutableListOf(
        attachments.map(ManifestAttachment::deepImmutableSnapshot),
    )
    public val metadata: List<ContentMetadataMutation> = immutableListOf(metadata)
    public val aliases: List<ContentAliasMutation> = immutableListOf(aliases)
    public val outbox: List<D> = immutableListOf(outbox)
    public val migrations: List<ContentMigrationLedgerMutation> = immutableListOf(migrations)
    public val publications: List<ContentPublicationMutation> = immutableListOf(
        publications.map(ContentPublicationMutation::deepImmutableSnapshot),
    )
    public val auxiliaryAttachments: List<AuxiliaryBlobAttachment> = immutableListOf(
        auxiliaryAttachments.map(AuxiliaryBlobAttachment::deepImmutableSnapshot),
    )
    public val quarantines: List<ContentQuarantineMutation> = immutableListOf(
        quarantines.map(ContentQuarantineMutation::deepImmutableSnapshot),
    )
    public val rightsGrants: List<ContentRightsGrantMutation> = immutableListOf(
        rightsGrants.map(ContentRightsGrantMutation::deepImmutableSnapshot),
    )
    public val blobSyncJobs: List<ContentBlobSyncJobMutation> = immutableListOf(blobSyncJobs.map { it.copy() })
    public val replicaReplacement: ContentPublicationReplicaReplacement? = replicaReplacement?.copy()

    init {
        if (semantics != ContentCommitSemantics.REPLACE_PORTABLE_GRAPH && this.migrations.isNotEmpty()) {
            require(this.migrations.size == 1) { "A content transaction may commit one migration source" }
            require(this.commitId == this.migrations.single().commitId) {
                "Migration transactions must use the deterministic source-digest commit id"
            }
        }
        if (semantics == ContentCommitSemantics.REPLACE_PORTABLE_GRAPH) {
            require(auxiliaryAttachments.isEmpty() && quarantines.isEmpty()) {
                "Portable graph replacement cannot import device-local auxiliary or quarantine state"
            }
        }
        if (semantics == ContentCommitSemantics.REPLACE_PUBLICATION_REPLICA) {
            val replacement = requireNotNull(this.replicaReplacement) {
                "Publication replica replacement requires an exact CAS cursor"
            }
            require(this.commitId == replacement.commitId) {
                "Publication replica commit id must bind its cursor and graph fingerprint"
            }
            require(metadata.isEmpty() && aliases.isEmpty() && outbox.isEmpty() && migrations.isEmpty() &&
                auxiliaryAttachments.isEmpty() && quarantines.isEmpty() && blobSyncJobs.isEmpty()) {
                "Publication replica replacement may only replace its typed graph and rights"
            }
            val publicationKey = replacement.replacement.publicationKey
            if (replacement.replacement.present) {
                require(this.publications.size == 1 &&
                    this.publications.single().publicationKey == publicationKey) {
                    "Present replica replacement requires exactly its complete publication"
                }
                require(this.attachments.all { it.owner.publicationKey == publicationKey }) {
                    "Replica replacement contains a foreign manifest attachment"
                }
                require(this.rightsGrants.all { it.grant.scope.publicationId == publicationKey }) {
                    "Replica replacement contains a foreign rights grant"
                }
            } else {
                require(this.publications.isEmpty() && this.receipts.isEmpty() &&
                    this.attachments.isEmpty() && this.rightsGrants.isEmpty()) {
                    "Replica tombstone cannot install publication content"
                }
            }
        } else {
            require(this.replicaReplacement == null) {
                "Replica CAS cursor requires publication-scoped replacement semantics"
            }
        }
    }

    public val manifestAttachments: List<ManifestAttachment> get() = attachments
    public val outboxDrafts: List<D> get() = outbox

    public fun copy(
        commitId: String = this.commitId,
        receipts: List<BlobPublishReceipt> = this.receipts,
        attachments: List<ManifestAttachment> = this.attachments,
        metadata: List<ContentMetadataMutation> = this.metadata,
        aliases: List<ContentAliasMutation> = this.aliases,
        outbox: List<D> = this.outbox,
        migrations: List<ContentMigrationLedgerMutation> = this.migrations,
        publications: List<ContentPublicationMutation> = this.publications,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment> = this.auxiliaryAttachments,
        quarantines: List<ContentQuarantineMutation> = this.quarantines,
        rightsGrants: List<ContentRightsGrantMutation> = this.rightsGrants,
        blobSyncJobs: List<ContentBlobSyncJobMutation> = this.blobSyncJobs,
        replicaReplacement: ContentPublicationReplicaReplacement? = this.replicaReplacement,
        unrepresentableDraftPolicy: UnrepresentableDraftPolicy = this.unrepresentableDraftPolicy,
        semantics: ContentCommitSemantics = this.semantics,
    ): ContentCommitBatch<D> = ContentCommitBatch(
        commitId = commitId,
        receipts = receipts,
        attachments = attachments,
        metadata = metadata,
        aliases = aliases,
        outbox = outbox,
        migrations = migrations,
        publications = publications,
        auxiliaryAttachments = auxiliaryAttachments,
        quarantines = quarantines,
        rightsGrants = rightsGrants,
        blobSyncJobs = blobSyncJobs,
        replicaReplacement = replicaReplacement,
        unrepresentableDraftPolicy = unrepresentableDraftPolicy,
        semantics = semantics,
    )
}

public sealed class ContentTransactionException(message: String) : IllegalArgumentException(message) {
    public class CommitConflict(public val conflictingId: String) :
        ContentTransactionException("Commit/id $conflictingId was already bound to different content")
    public class DuplicateEntry(message: String) : ContentTransactionException(message)
    public class V1SyncCannotRepresent(public val draftIds: List<String>) :
        ContentTransactionException("Active v1 sync cannot represent content drafts: ${draftIds.joinToString()}")
}

/**
 * Outcome of a local-only body purge. [removedBlobs] are the blob references that were detached
 * from the purged publication; they may still be shared by another attachment, so the caller must
 * only delete them through a guarded path such as [ContentBlobStore.deleteUnreferencedNow].
 */
public data class ContentLocalBodyPurgeResult(
    val removedBlobs: List<BlobRef> = emptyList(),
    val purgedUnitCount: Int = 0,
)

/** Same publication graph with every stored manifest revision dropped; identity/bindings stay. */
internal fun Publication.withoutManifestRevisions(): Publication = copy(
    acquisitions = acquisitions.map { acquisition ->
        acquisition.copy(units = acquisition.units.map { unit -> unit.copy(manifestRevisions = emptyList()) })
    },
)

public interface SharedContentTransactionStore<D : Any> {
    public fun commit(batch: ContentCommitBatch<D>): ContentCommitResult

    /** Restart-safe pending drafts written atomically with their content metadata. */
    public fun pendingOutbox(): List<D> = emptyList()

    /**
     * Deletes drafts that were delivered (or, with no sync service, deliberately discarded).
     * Implementations must acknowledge the complete id set atomically or leave it unchanged.
     */
    public fun acknowledgeOutbox(draftIds: Set<String>): Int {
        require(draftIds.isEmpty()) { "This content transaction store has no drainable outbox" }
        return 0
    }

    /** Number of pending drafts, without decoding them. */
    public fun pendingOutboxCount(): Int = pendingOutbox().size

    /**
     * One page of pending drafts ordered by draft id, starting after [afterDraftId]. Lets a
     * caller walk a large outbox without holding every payload in memory at once.
     */
    public fun pendingOutboxPage(afterDraftId: String?, limit: Int): List<D> =
        throw UnsupportedOperationException("This content transaction store cannot page its outbox")

    /** One pending draft by id, or null once it was acknowledged or replaced. */
    public fun pendingOutboxDraft(draftId: String): D? =
        throw UnsupportedOperationException("This content transaction store cannot read one outbox draft")

    /**
     * Atomically removes [remove] (ids already gone are ignored) and adds [add]. Used to replace
     * superseded drafts by an equivalent compacted set; returns the number of drafts removed.
     */
    public fun replaceOutboxDrafts(remove: Set<String>, add: List<D>): Int =
        throw UnsupportedOperationException("This content transaction store cannot replace outbox drafts")

    /** Low-priority encrypted-body jobs queued by the same content transaction. */
    public fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = emptyList()

    /** Removes jobs only after their BlobReferenceCommit draft is durable. */
    public fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int {
        require(jobIds.isEmpty()) { "This content transaction store has no drainable blob jobs" }
        return 0
    }

    /** Exact durable cursor used to reject stale or cross-workspace replica materialization. */
    public fun publicationReplicaCursor(publicationKey: PublicationKey): ContentPublicationReplicaCursor? = null

    /** Durable removed-reference work awaiting a lifecycle/GC consumer. */
    public fun pendingBlobRemovalIntents(): List<ContentBlobRemovalIntent> = emptyList()

    public fun acknowledgeBlobRemovalIntents(intentIds: Set<String>): Int {
        require(intentIds.isEmpty()) { "This content transaction store has no blob removal intents" }
        return 0
    }

    /**
     * Local-only removal of a publication's stored bodies: manifest attachments are detached and
     * every unit's manifest revisions are dropped, while the publication, acquisitions, unit
     * bindings and rights stay. Emits no outbox/sync draft and never touches replica cursors.
     * [forgetCommitIdsOf] names the journal entries that would otherwise turn a re-download into a
     * replay; outbox drafts whose id starts with [draftIdPrefixOf] of one of them and that were not
     * drained yet are dropped too (they describe the state being discarded, and would sync it out
     * or collide with a re-download's drafts). Both are evaluated on the publication as read inside
     * the purge transaction, so a download committed just before cannot slip past.
     */
    public fun purgeLocalPublicationBodies(
        publicationKey: PublicationKey,
        forgetCommitIdsOf: (Publication) -> Set<String>,
        draftIdPrefixOf: (commitId: String) -> String? = { null },
    ): ContentLocalBodyPurgeResult = ContentLocalBodyPurgeResult()

    /**
     * Atomically retires authority-bound replica cursors, replay journal entries and GC hints.
     * Publications, manifests, rights, body bytes and ordinary local transaction rows survive.
     */
    public fun detachReplicaAuthority(
        authority: ContentReplicaAuthority,
    ): ContentReplicaAuthorityDepartureResult

    /**
     * Looks up the durable source-digest ledger before any ephemeral blob receipt is considered.
     * An exact result fingerprint is a semantic replay; a different result is a conflict.
     */
    public fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup

    /** Short name retained for migration hosts that treat the ledger as a lookup service. */
    public fun lookupMigration(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = lookupMigrationLedger(
        namespace,
        sourceDigestSha256,
        resultFingerprintSha256,
    )
}

public data class InMemoryContentTransactionState<D : Any>(
    val metadata: Map<String, String>,
    val aliases: Map<String, String>,
    val migrations: Map<String, ContentMigrationLedgerMutation>,
    val outbox: List<D>,
    val committedIds: Set<String>,
    val publications: Map<PublicationKey, Publication> = emptyMap(),
    val quarantines: Map<String, ContentQuarantineMutation> = emptyMap(),
    val rightsGrants: Map<RightsGrantRef, RightsGrant> = emptyMap(),
    val blobSyncJobs: Map<String, ContentBlobSyncJobMutation> = emptyMap(),
    val publicationReplicaCursors: Map<PublicationKey, ContentPublicationReplicaCursor> = emptyMap(),
    val blobRemovalIntents: Map<String, ContentBlobRemovalIntent> = emptyMap(),
)

/**
 * Verifies the only update admitted by [ContentCommitSemantics.MERGE_IMMUTABLE]: an append-only
 * extension of a durable typed graph. Existing acquisitions, units and manifest revisions remain
 * byte-for-byte equal; a stale writer that omits any concurrently committed node is rejected.
 */
internal fun isImmutablePublicationGraphExtension(
    durable: Publication,
    candidate: Publication,
): Boolean {
    if (durable.key != candidate.key ||
        durable.title != candidate.title ||
        durable.workLinks != candidate.workLinks ||
        durable.description != candidate.description ||
        durable.authors != candidate.authors
    ) {
        return false
    }
    val candidateAcquisitions = candidate.acquisitions.associateBy(Acquisition::id)
    if (!candidateAcquisitions.keys.containsAll(durable.acquisitions.map(Acquisition::id))) return false
    return durable.acquisitions.all { previous ->
        val replacement = candidateAcquisitions[previous.id] ?: return@all false
        isImmutableAcquisitionGraphExtension(previous, replacement)
    }
}

private fun isImmutableAcquisitionGraphExtension(
    durable: Acquisition,
    candidate: Acquisition,
): Boolean {
    if (durable.id != candidate.id ||
        durable.origin != candidate.origin ||
        availabilityRank(candidate.availability) < availabilityRank(durable.availability) ||
        candidate.contentRevision < durable.contentRevision ||
        durable.acquiredAtEpochMillis != candidate.acquiredAtEpochMillis ||
        durable.legacyCompatibilityFacet != candidate.legacyCompatibilityFacet ||
        (durable.rightsGrantRef != null && durable.rightsGrantRef != candidate.rightsGrantRef)
    ) {
        return false
    }
    val candidateUnits = candidate.units.associateBy(PublicationUnit::key)
    if (!candidateUnits.keys.containsAll(durable.units.map(PublicationUnit::key))) return false
    val unitsAreExtensions = durable.units.all { previous ->
        val replacement = candidateUnits[previous.key] ?: return@all false
        isImmutableUnitGraphExtension(previous, replacement)
    }
    if (!unitsAreExtensions) return false

    val graphAdvanced = candidate.units.size > durable.units.size || durable.units.any { previous ->
        candidateUnits.getValue(previous.key).manifestRevisions.size > previous.manifestRevisions.size
    }
    val mutableFacetAdvanced = candidate.availability != durable.availability ||
        candidate.rightsGrantRef != durable.rightsGrantRef
    return when {
        graphAdvanced -> candidate.contentRevision > durable.contentRevision
        mutableFacetAdvanced -> candidate.contentRevision >= durable.contentRevision
        else -> candidate.contentRevision == durable.contentRevision
    }
}

private fun isImmutableUnitGraphExtension(
    durable: PublicationUnit,
    candidate: PublicationUnit,
): Boolean {
    if (durable.key != candidate.key ||
        durable.title != candidate.title ||
        durable.sourceBinding != candidate.sourceBinding ||
        durable.ordinal != candidate.ordinal ||
        durable.publishedAtEpochMillis != candidate.publishedAtEpochMillis ||
        durable.legacyCompatibilityFacet != candidate.legacyCompatibilityFacet
    ) {
        return false
    }
    val candidateManifests = candidate.manifestRevisions.associateBy { it.manifestId }
    if (durable.manifestRevisions.any { candidateManifests[it.manifestId] != it }) return false
    val previousMaximumRevision = durable.manifestRevisions.maxOfOrNull { it.contentRevision } ?: -1L
    return candidate.manifestRevisions
        .filterNot { it.manifestId in durable.manifestRevisions.mapTo(hashSetOf()) { old -> old.manifestId } }
        .all { it.contentRevision > previousMaximumRevision }
}

private fun availabilityRank(value: AcquisitionAvailability): Int = when (value) {
    AcquisitionAvailability.UNAVAILABLE -> 0
    AcquisitionAvailability.PARTIAL -> 1
    AcquisitionAvailability.AVAILABLE -> 2
}

/**
 * Keeps portable publication metadata distinct from local body durability.
 *
 * A PARTIAL/UNAVAILABLE acquisition may retain its complete manifest/ref graph before this
 * device downloads the bodies. AVAILABLE is the local-readiness assertion and therefore
 * requires every body-bearing manifest to have an exact attachment. Any attachment already
 * durable or supplied by the batch must still match the typed publication exactly.
 */
internal fun validateTypedPublicationAttachments(
    publications: List<ContentPublicationMutation>,
    suppliedAttachments: List<ManifestAttachment>,
    findDurableAttachment: (ManifestAttachment) -> ManifestAttachment?,
) {
    if (publications.isEmpty()) return
    val supplied = suppliedAttachments.associateBy(ManifestAttachment::attachmentKey)
    publications.forEach { mutation ->
        val publication = mutation.publication
        publication.acquisitions.forEach { acquisition ->
            acquisition.units.forEach { unit ->
                unit.manifestRevisions.forEach { manifest ->
                    val expected = ManifestAttachment(
                        owner = ContentManifestOwner(publication.key, acquisition.id, unit.key),
                        manifest = manifest,
                    )
                    val suppliedAttachment = supplied[expected.attachmentKey]
                    val durableAttachment = findDurableAttachment(expected)
                    if ((suppliedAttachment != null && suppliedAttachment != expected) ||
                        (durableAttachment != null && durableAttachment != expected)
                    ) {
                        throw ContentBlobStoreException.InvalidStage(
                            "Typed publication manifest ${expected.attachmentKey} conflicts with its local attachment",
                        )
                    }
                    val requiresLocalBody = acquisition.availability == AcquisitionAvailability.AVAILABLE &&
                        expected.blobs.isNotEmpty()
                    if (requiresLocalBody && suppliedAttachment == null && durableAttachment == null) {
                        throw ContentBlobStoreException.InvalidStage(
                            "Typed publication manifest ${expected.attachmentKey} is not durably attached",
                        )
                    }
                }
            }
        }
    }
}

public enum class ContentTransactionFailurePoint {
    BEFORE_VALIDATE,
    AFTER_STRUCTURAL_VALIDATE,
    AFTER_BLOB_VALIDATE,
    AFTER_METADATA_VALIDATE,
    AFTER_MIGRATION_VALIDATE,
    AFTER_OUTBOX_VALIDATE,
    /** Inject immediately after each immutable manifest attachment is installed. */
    AFTER_BLOB_ATTACHMENT_WRITE,
    AFTER_EACH_BLOB_ATTACHMENT_WRITE,
    AFTER_BLOB_WRITE,
    AFTER_METADATA_WRITE,
    AFTER_EACH_METADATA_WRITE,
    AFTER_ALIAS_WRITE,
    AFTER_EACH_ALIAS_WRITE,
    AFTER_MIGRATION_WRITE,
    AFTER_EACH_MIGRATION_WRITE,
    AFTER_OUTBOX_WRITE,
    AFTER_EACH_OUTBOX_WRITE,
    AFTER_PUBLICATION_WRITE,
    AFTER_EACH_PUBLICATION_WRITE,
    AFTER_QUARANTINE_WRITE,
    AFTER_EACH_QUARANTINE_WRITE,
    AFTER_RIGHTS_GRANT_WRITE,
    AFTER_EACH_RIGHTS_GRANT_WRITE,
    AFTER_REPLICA_CURSOR_WRITE,
    AFTER_BLOB_REMOVAL_INTENT_WRITE,
    AFTER_REPLICA_AUTHORITY_METADATA_DELETE,
    AFTER_JOURNAL_WRITE,
}

internal fun buildBlobRemovalIntent(
    cursor: ContentPublicationReplicaCursor,
    previousReferences: List<BlobRef>,
    replacementReferences: List<BlobRef>,
): ContentBlobRemovalIntent? {
    fun exactById(references: List<BlobRef>): Map<String, BlobRef> {
        val result = linkedMapOf<String, BlobRef>()
        references.forEach { reference ->
            reference.validate()
            result[reference.blobId]?.let { existing ->
                require(existing == reference) { "One blob id resolved to conflicting immutable references" }
            }
            result[reference.blobId] = reference
        }
        return result
    }

    val previous = exactById(previousReferences)
    val replacement = exactById(replacementReferences)
    previous.keys.intersect(replacement.keys).forEach { blobId ->
        require(previous.getValue(blobId) == replacement.getValue(blobId)) {
            "Replica replacement reused a blob id for different immutable content"
        }
    }
    val removed = (previous.keys - replacement.keys).sorted().map(previous::getValue)
    if (removed.isEmpty()) return null
    return ContentBlobRemovalIntent(
        intentId = "blob-removal:${cursor.publicationKey.value}:" +
            "${cursor.throughWorkspaceSeq}:${cursor.graphFingerprintSha256}",
        publicationKey = cursor.publicationKey,
        sourceCursor = cursor,
        reason = if (cursor.present) {
            ContentBlobRemovalReason.REPLICA_REPLACED
        } else {
            ContentBlobRemovalReason.REPLICA_TOMBSTONED
        },
        removedBlobs = removed,
    )
}

internal val CANONICAL_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    allowStructuredMapKeys = true
}

private const val MAX_TRANSACTION_VALUE_LENGTH: Int = 1_000_000

internal const val CONTENT_REPLICA_COMMIT_ID_PREFIX: String = "replica:"

private val SHA256_HEX = Regex("[0-9a-f]{64}")

internal fun requireSafeTransactionKey(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 4096) { "$label must be non-blank and bounded" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
}
