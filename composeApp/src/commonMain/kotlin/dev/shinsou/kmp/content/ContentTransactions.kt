package dev.shinsou.kmp.content

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.sync.v2.CategoryMembershipSet
import dev.shinsou.kmp.sync.v2.CategoryPatch
import dev.shinsou.kmp.sync.v2.ChapterStatePatch
import dev.shinsou.kmp.sync.v2.EntityKeyRemap
import dev.shinsou.kmp.sync.v2.EntityPresenceSet
import dev.shinsou.kmp.sync.v2.ExtensionRepositoryPatch
import dev.shinsou.kmp.sync.v2.ExtensionRepositoryPresenceSet
import dev.shinsou.kmp.sync.v2.LibraryEntryPatch
import dev.shinsou.kmp.sync.v2.PortableSettingPatch
import dev.shinsou.kmp.sync.v2.ReadingProgressPresenceSet
import dev.shinsou.kmp.sync.v2.ReadingProgressSet
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncMutation
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.ContentOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement

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

/** Adapter for the application's existing v1 sync outbox authority. */
public object SyncDraftContentOutboxAdapter : ContentOutboxPersistenceAdapter<SyncDraft> {
    override fun validate(draft: SyncDraft) {
        requireSafeTransactionKey(draft.draftId, "Sync draft id")
        require(draft.event.opId == draft.draftId) { "Sync draft id must equal its operation id" }
        require(draft.event.mutations.isNotEmpty()) { "Sync draft event cannot be empty" }
        require(draft.createdAtMillis >= 0 && draft.updatedAtMillis >= draft.createdAtMillis) {
            "Sync draft timestamps are invalid"
        }
    }

    override fun id(draft: SyncDraft): String = draft.draftId

    override fun fingerprint(draft: SyncDraft): ByteArray =
        canonicalJsonBytes(CANONICAL_JSON.encodeToJsonElement(SyncDraft.serializer(), draft))

    override fun encode(draft: SyncDraft): String =
        CANONICAL_JSON.encodeToString(SyncDraft.serializer(), draft)

    override fun decode(payload: String): SyncDraft =
        CANONICAL_JSON.decodeFromString(SyncDraft.serializer(), payload).also(::validate)

    override fun isRepresentableByCurrentV1(draft: SyncDraft): Boolean =
        draft.event.schemaVersion == 1 &&
            draft.event.mutations.all(::isCurrentV1Mutation)

    private fun isCurrentV1Mutation(mutation: SyncMutation): Boolean = when (mutation) {
        is LibraryEntryPatch,
        is ChapterStatePatch,
        is CategoryPatch,
        is CategoryMembershipSet,
        is ExtensionRepositoryPatch,
        is ExtensionRepositoryPresenceSet,
        is EntityPresenceSet,
        is ReadingProgressSet,
        is ReadingProgressPresenceSet,
        is PortableSettingPatch,
        is EntityKeyRemap,
        -> true
        else -> false
    }
}

/** Short alias used by storage hosts while retaining the real [SyncDraft] serialization. */
public val SyncDraftOutboxAdapter: ContentOutboxAdapter<SyncDraft> = SyncDraftContentOutboxAdapter

/** Explicitly typed durable adapter for SQL-backed content transaction stores. */
public val SyncDraftPersistenceOutboxAdapter: ContentOutboxPersistenceAdapter<SyncDraft> =
    SyncDraftContentOutboxAdapter

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

public interface SharedContentTransactionStore<D : Any> {
    public fun commit(batch: ContentCommitBatch<D>): ContentCommitResult

    /** Restart-safe pending drafts written atomically with their content metadata. */
    public fun pendingOutbox(): List<D> = emptyList()

    /**
     * Deletes only drafts already durably accepted by the authoritative LocalSyncStore.
     * Implementations must acknowledge the complete id set atomically or leave it unchanged.
     */
    public fun acknowledgeOutbox(draftIds: Set<String>): Int {
        require(draftIds.isEmpty()) { "This content transaction store has no drainable outbox" }
        return 0
    }

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

/** Executable shared-transaction oracle with rollback at every physical write boundary. */
public class InMemorySharedContentTransactionStore<D : Any>(
    public val blobStore: InMemoryContentBlobStore,
    private val outboxAdapter: ContentOutboxAdapter<D>,
    /** Host-owned negotiated state; an individual batch cannot self-declare a future wire. */
    private val syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
) : SharedContentTransactionStore<D> {
    private val transactionMutex = SynchronousLock()
    private val metadata = LinkedHashMap<String, String>()
    private val aliases = LinkedHashMap<String, String>()
    private val migrations = LinkedHashMap<String, ContentMigrationLedgerMutation>()
    private val outbox = LinkedHashMap<String, D>()
    private val publications = LinkedHashMap<PublicationKey, Publication>()
    private val quarantines = LinkedHashMap<String, ContentQuarantineMutation>()
    private val rightsGrants = LinkedHashMap<RightsGrantRef, RightsGrant>()
    private val blobSyncJobs = LinkedHashMap<String, ContentBlobSyncJobMutation>()
    private val publicationReplicaCursors = LinkedHashMap<PublicationKey, ContentPublicationReplicaCursor>()
    private val blobRemovalIntents = LinkedHashMap<String, ContentBlobRemovalIntent>()
    private val commits = LinkedHashMap<String, CommittedBatch>()

    public var failureInjection: ContentTransactionFailurePoint? = null

    public val state: InMemoryContentTransactionState<D>
        get() = withTransactionLock {
            InMemoryContentTransactionState(
                metadata = metadata.toMap(),
                aliases = aliases.toMap(),
                migrations = migrations.toMap(),
                outbox = outbox.values.toList(),
                committedIds = commits.keys.toSet(),
                publications = publications.toMap(),
                quarantines = quarantines.toMap(),
                rightsGrants = rightsGrants.toMap(),
                blobSyncJobs = blobSyncJobs.toMap(),
                publicationReplicaCursors = publicationReplicaCursors.toMap(),
                blobRemovalIntents = blobRemovalIntents.toMap(),
            )
        }

    override fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = withTransactionLock {
        lookupMigrationLedgerLocked(namespace, sourceDigestSha256, resultFingerprintSha256)
    }

    override fun pendingOutbox(): List<D> = withTransactionLock { outbox.values.toList() }

    override fun acknowledgeOutbox(draftIds: Set<String>): Int = withTransactionLock {
        validateAcknowledgementIds(draftIds, "Outbox draft id")
        val existing = draftIds.count(outbox::containsKey)
        draftIds.forEach(outbox::remove)
        existing
    }

    override fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = withTransactionLock {
        blobSyncJobs.values.map { it.copy(blob = it.blob.copy()) }
    }

    override fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int = withTransactionLock {
        validateAcknowledgementIds(jobIds, "Blob sync job id")
        val existing = jobIds.count(blobSyncJobs::containsKey)
        jobIds.forEach(blobSyncJobs::remove)
        existing
    }

    override fun publicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? = withTransactionLock {
        publicationKey.validate()
        publicationReplicaCursors[publicationKey]
    }

    override fun pendingBlobRemovalIntents(): List<ContentBlobRemovalIntent> = withTransactionLock {
        blobRemovalIntents.values.sortedBy(ContentBlobRemovalIntent::intentId)
    }

    override fun acknowledgeBlobRemovalIntents(intentIds: Set<String>): Int = withTransactionLock {
        validateAcknowledgementIds(intentIds, "Blob removal intent id")
        val existing = intentIds.count(blobRemovalIntents::containsKey)
        intentIds.forEach(blobRemovalIntents::remove)
        existing
    }

    override fun detachReplicaAuthority(
        authority: ContentReplicaAuthority,
    ): ContentReplicaAuthorityDepartureResult = withTransactionLock {
        val cursorSnapshot = LinkedHashMap(publicationReplicaCursors)
        val intentSnapshot = LinkedHashMap(blobRemovalIntents)
        val commitSnapshot = LinkedHashMap(commits)
        try {
            val publicationKeys = publicationReplicaCursors
                .filterValues { cursor -> cursor.belongsTo(authority) }
                .keys
                .toSet()
            val commitIds = commits.keys.filterTo(linkedSetOf()) { commitId ->
                publicationKeys.any { publicationKey ->
                    commitId.isReplicaCommitFor(publicationKey)
                }
            }
            val removalIntentIds = blobRemovalIntents
                .filterValues { intent -> intent.sourceCursor.belongsTo(authority) }
                .keys
                .toSet()

            publicationKeys.forEach(publicationReplicaCursors::remove)
            commitIds.forEach(commits::remove)
            removalIntentIds.forEach(blobRemovalIntents::remove)
            maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_AUTHORITY_METADATA_DELETE)

            ContentReplicaAuthorityDepartureResult(
                authority = authority,
                removedCursorCount = publicationKeys.size,
                removedCommitCount = commitIds.size,
                removedBlobRemovalIntentCount = removalIntentIds.size,
            )
        } catch (failure: Throwable) {
            restore(publicationReplicaCursors, cursorSnapshot)
            restore(blobRemovalIntents, intentSnapshot)
            restore(commits, commitSnapshot)
            throw failure
        }
    }

    override fun commit(batch: ContentCommitBatch<D>): ContentCommitResult = withTransactionLock {
        batch.outbox.forEach { draft ->
            outboxAdapter.validate(draft)
            requireSafeTransactionKey(outboxAdapter.id(draft), "Outbox draft id")
        }
        val authoritativeSyncMode = syncModeProvider()
        val replacingPortableGraph =
            batch.semantics == ContentCommitSemantics.REPLACE_PORTABLE_GRAPH
        val replicaReplacement = batch.replicaReplacement
        replicaReplacement?.let { replacement ->
            val current = publicationReplicaCursors[replacement.replacement.publicationKey]
            if (current == replacement.replacement) {
                validateReplicaReplay(batch)
                val previous = commits[batch.commitId]
                consumeReplayReceipts(batch.receipts, batch.attachments, batch.auxiliaryAttachments)
                return@withTransactionLock (previous?.result ?: replayResult(batch)).copy(replayed = true)
            }
            if (current != replacement.expected) {
                throw ContentTransactionException.CommitConflict(replacement.conflictId)
            }
        }
        val fingerprint = fingerprint(batch, authoritativeSyncMode)
        val migrationReplay = batch.migrations
            .takeUnless { replacingPortableGraph }
            ?.singleOrNull()
            ?.let { migration ->
            lookupMigrationLedgerLocked(
                migration.namespace,
                migration.sourceDigestSha256,
                migration.resultFingerprintSha256,
            )
        }
        migrationReplay?.let { lookup ->
            if (lookup.isConflict) throw ContentTransactionException.CommitConflict(lookup.deterministicCommitId)
            if (lookup.isReplay) {
                val previous = commits[lookup.deterministicCommitId]
                consumeReplayReceipts(batch.receipts, batch.attachments, batch.auxiliaryAttachments)
                return@withTransactionLock (previous?.result ?: replayResult(batch)).copy(replayed = true)
            }
        }
        commits[batch.commitId]?.takeUnless { replacingPortableGraph }?.let { previous ->
            if (previous.fingerprint != fingerprint) throw ContentTransactionException.CommitConflict(batch.commitId)
            // A receipt is an in-process capability, not a bearer token.  An attacker (or a
            // stale caller) may copy every scalar field and still must not replay a commit with
            // a different object.  The original batch remains replayable for idempotence.
            if (previous.receipts.size != batch.receipts.size || previous.receipts.zip(batch.receipts).any { (expected, actual) -> expected !== actual }) {
                val token = batch.receipts.firstOrNull()?.commitToken ?: batch.commitId
                throw ContentBlobStoreException.ReceiptMismatch(token)
            }
            return@withTransactionLock previous.result.copy(replayed = true)
        }

        maybeFail(ContentTransactionFailurePoint.BEFORE_VALIDATE)
        val v1Gaps = buildList {
            batch.outbox.filterNot(outboxAdapter::isRepresentableByCurrentV1).mapTo(this, outboxAdapter::id)
            // String metadata/aliases have no typed proof that sync v1 can represent them.
            batch.metadata.mapTo(this) { "metadata:${it.key}" }
            batch.aliases.mapTo(this) { "alias:${it.alias}" }
            if (batch.attachments.isNotEmpty() || batch.auxiliaryAttachments.isNotEmpty() ||
                batch.receipts.isNotEmpty()
            ) {
                batch.attachments.mapTo(this) { "manifest:${it.manifestId}" }
                if (batch.attachments.isEmpty() && batch.auxiliaryAttachments.isEmpty()) {
                    add("unattached-blob-receipt")
                }
            }
            batch.migrations.mapTo(this) { "migration:${it.migrationKey}" }
            batch.publications.mapTo(this) { "publication:${it.publicationKey.value}" }
            batch.auxiliaryAttachments.mapTo(this) { "auxiliary:${it.attachmentKey}" }
            batch.quarantines.mapTo(this) { "quarantine:${it.quarantineId}" }
            batch.rightsGrants.mapTo(this) { "rights:${it.grantReference.value}" }
            batch.blobSyncJobs.mapTo(this) { "blob-sync-job:${it.jobId}" }
            batch.replicaReplacement?.let { add("replica:${it.replacement.publicationKey.value}") }
        }.distinct()
        if (authoritativeSyncMode == ContentSyncMode.V1_ACTIVE && v1Gaps.isNotEmpty()) {
            if (batch.unrepresentableDraftPolicy == UnrepresentableDraftPolicy.DEFER) {
                return@withTransactionLock ContentCommitResult(
                    batch.commitId,
                    replayed = false,
                    deferred = true,
                    committedGeneration = null,
                    attachedOwnerIds = emptyList(),
                    outboxDraftIds = v1Gaps,
                    migrationKeys = emptyList(),
                )
            }
            throw ContentTransactionException.V1SyncCannotRepresent(v1Gaps)
        }

        validateUniqueBatchEntries(batch)
        maybeFail(ContentTransactionFailurePoint.AFTER_STRUCTURAL_VALIDATE)
        val blobAttachments = batch.attachments.map(ManifestAttachment::asBlobAttachment)

        blobStore.withExclusiveTransaction {
            val blobRollback = blobStore.snapshotForTransactionLocked()
            val metadataSnapshot = LinkedHashMap(metadata)
            val aliasSnapshot = LinkedHashMap(aliases)
            val migrationSnapshot = LinkedHashMap(migrations)
            val outboxSnapshot = LinkedHashMap(outbox)
            val publicationSnapshot = LinkedHashMap(publications)
            val quarantineSnapshot = LinkedHashMap(quarantines)
            val rightsGrantSnapshot = LinkedHashMap(rightsGrants)
            val blobSyncJobSnapshot = LinkedHashMap(blobSyncJobs)
            val publicationReplicaCursorSnapshot = LinkedHashMap(publicationReplicaCursors)
            val blobRemovalIntentSnapshot = LinkedHashMap(blobRemovalIntents)
            val commitSnapshot = LinkedHashMap(commits)
            try {
                if (replacingPortableGraph) {
                    blobStore.clearManifestAttachmentsLocked()
                    publications.clear()
                    rightsGrants.clear()
                    blobSyncJobs.clear()
                    outbox.clear()
                    publicationReplicaCursors.clear()
                    blobRemovalIntents.clear()
                    commits.keys.removeAll { it.startsWith(CONTENT_REPLICA_COMMIT_ID_PREFIX) }
                    // A restore is repeatable even after the same archive was applied before.
                    commits.remove(batch.commitId)
                }
                var detachedReplicaAttachments: List<BlobAttachment> = emptyList()
                replicaReplacement?.let { replacement ->
                    val publicationKey = replacement.replacement.publicationKey
                    if (publicationReplicaCursors[publicationKey] != replacement.expected) {
                        throw ContentTransactionException.CommitConflict(replacement.conflictId)
                    }
                    detachedReplicaAttachments = blobStore.detachManifestAttachmentsLocked(publicationKey)
                    publications.remove(publicationKey)
                    rightsGrants.entries.removeAll { (_, grant) ->
                        grant.scope.publicationId == publicationKey
                    }
                    blobSyncJobs.entries.removeAll { (_, job) ->
                        job.owner.publicationKey == publicationKey
                    }
                }
                blobStore.validateAtomicAttachmentsLocked(
                    batch.receipts,
                    blobAttachments,
                    batch.auxiliaryAttachments,
                )
                maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE)
                validateMetadataAndAliases(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE)
                validateMigrations(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_VALIDATE)
                validateOutbox(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_VALIDATE)
                validatePublications(batch)
                validateTypedPublicationAttachments(
                    publications = batch.publications,
                    suppliedAttachments = batch.attachments,
                ) { expected ->
                    blobStore.attachedLocked(
                        expected.owner,
                        expected.manifestId,
                        expected.contentRevision,
                    )?.let { durable -> ManifestAttachment(durable.owner, durable.manifest) }
                }
                validateQuarantines(batch)
                validateRightsGrants(batch)
                validateBlobSyncJobs(batch)

                blobStore.consumeAtomicAttachmentsLocked(
                    batch.receipts,
                    blobAttachments,
                    batch.auxiliaryAttachments,
                ) {
                    maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
                    // Kept for callers of the first M0 draft; the newer point above is emitted
                    // after each attachment, while this one is emitted once below.
                }
                maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_WRITE)
                batch.metadata.forEach { mutation ->
                    metadata[mutation.key] = mutation.value
                    maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_METADATA_WRITE)
                }
                batch.aliases.forEach { mutation ->
                    aliases[mutation.alias] = mutation.target
                    maybeFail(ContentTransactionFailurePoint.AFTER_ALIAS_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_ALIAS_WRITE)
                }
                batch.migrations.forEach { mutation ->
                    migrations[mutation.migrationKey] = mutation
                    maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_MIGRATION_WRITE)
                }
                batch.outbox.forEach { draft ->
                    outbox[outboxAdapter.id(draft)] = draft
                    maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_OUTBOX_WRITE)
                }
                batch.publications.forEach { mutation ->
                    publications[mutation.publicationKey] = mutation.publication
                    maybeFail(ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_PUBLICATION_WRITE)
                }
                batch.quarantines.forEach { mutation ->
                    quarantines[mutation.quarantineId] = mutation
                    maybeFail(ContentTransactionFailurePoint.AFTER_QUARANTINE_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_QUARANTINE_WRITE)
                }
                batch.rightsGrants.forEach { mutation ->
                    rightsGrants[mutation.grantReference] = mutation.grant
                    maybeFail(ContentTransactionFailurePoint.AFTER_RIGHTS_GRANT_WRITE)
                    maybeFail(ContentTransactionFailurePoint.AFTER_EACH_RIGHTS_GRANT_WRITE)
                }
                batch.blobSyncJobs.forEach { mutation ->
                    blobSyncJobs[mutation.jobId] = mutation
                }
                val removalIntent = replicaReplacement?.let { replacement ->
                    publicationReplicaCursors[replacement.replacement.publicationKey] = replacement.replacement
                    maybeFail(ContentTransactionFailurePoint.AFTER_REPLICA_CURSOR_WRITE)
                    buildBlobRemovalIntent(
                        replacement.replacement,
                        detachedReplicaAttachments.flatMap(BlobAttachment::blobs),
                        batch.attachments.flatMap(ManifestAttachment::blobs),
                    )?.also { intent ->
                        blobRemovalIntents[intent.intentId]?.let { existing ->
                            if (existing != intent) {
                                throw ContentTransactionException.CommitConflict(intent.intentId)
                            }
                        }
                        blobRemovalIntents[intent.intentId] = intent
                        maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_REMOVAL_INTENT_WRITE)
                    }
                }
                val result = ContentCommitResult(
                    batch.commitId,
                    replayed = false,
                    deferred = false,
                    committedGeneration = batch.receipts.maxOfOrNull(BlobPublishReceipt::generation),
                    attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
                    outboxDraftIds = batch.outbox.map(outboxAdapter::id),
                    migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
                    publicationIds = batch.publications.map { it.publicationKey.value },
                    auxiliaryAttachmentIds = batch.auxiliaryAttachments.map(AuxiliaryBlobAttachment::attachmentKey),
                    quarantineIds = batch.quarantines.map(ContentQuarantineMutation::quarantineId),
                    rightsGrantIds = batch.rightsGrants.map { it.grantReference.value },
                    blobSyncJobIds = batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId),
                    blobRemovalIntentIds = listOfNotNull(removalIntent?.intentId),
                )
                commits[batch.commitId] = CommittedBatch(fingerprint, result, batch.receipts.toList())
                maybeFail(ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE)
                result
            } catch (error: Throwable) {
                blobRollback.rollback()
                restore(metadata, metadataSnapshot)
                restore(aliases, aliasSnapshot)
                restore(migrations, migrationSnapshot)
                restore(outbox, outboxSnapshot)
                restore(publications, publicationSnapshot)
                restore(quarantines, quarantineSnapshot)
                restore(rightsGrants, rightsGrantSnapshot)
                restore(blobSyncJobs, blobSyncJobSnapshot)
                restore(publicationReplicaCursors, publicationReplicaCursorSnapshot)
                restore(blobRemovalIntents, blobRemovalIntentSnapshot)
                restore(commits, commitSnapshot)
                throw error
            }
        }
    }

    private fun validateUniqueBatchEntries(batch: ContentCommitBatch<D>) {
        if (batch.metadata.map(ContentMetadataMutation::key).distinct().size != batch.metadata.size) {
            throw ContentTransactionException.DuplicateEntry("Metadata keys must be unique")
        }
        if (batch.aliases.map(ContentAliasMutation::alias).distinct().size != batch.aliases.size) {
            throw ContentTransactionException.DuplicateEntry("Alias keys must be unique")
        }
        if (batch.migrations.map(ContentMigrationLedgerMutation::migrationKey).distinct().size != batch.migrations.size) {
            throw ContentTransactionException.DuplicateEntry("Migration ledger keys must be unique")
        }
        if (batch.outbox.map(outboxAdapter::id).distinct().size != batch.outbox.size) {
            throw ContentTransactionException.DuplicateEntry("Outbox draft ids must be unique")
        }
        if (batch.attachments.map(ManifestAttachment::attachmentKey).distinct().size != batch.attachments.size) {
            throw ContentTransactionException.DuplicateEntry("Manifest attachment keys must be unique")
        }
        if (batch.publications.map(ContentPublicationMutation::publicationKey).distinct().size !=
            batch.publications.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Publication keys must be unique")
        }
        if (batch.auxiliaryAttachments.map(AuxiliaryBlobAttachment::attachmentKey).distinct().size !=
            batch.auxiliaryAttachments.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Auxiliary attachment keys must be unique")
        }
        if (batch.quarantines.map(ContentQuarantineMutation::quarantineId).distinct().size !=
            batch.quarantines.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Quarantine ids must be unique")
        }
        if (batch.rightsGrants.map(ContentRightsGrantMutation::grantReference).distinct().size !=
            batch.rightsGrants.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Rights grant ids must be unique")
        }
        if (batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId).distinct().size != batch.blobSyncJobs.size) {
            throw ContentTransactionException.DuplicateEntry("Blob sync job ids must be unique")
        }
    }

    private fun lookupMigrationLedgerLocked(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup {
        val requested = ContentMigrationLedgerMutation(namespace, sourceDigestSha256, resultFingerprintSha256)
        val existing = migrations[requested.migrationKey]
        return ContentMigrationLedgerLookup(
            status = when {
                existing == null -> ContentMigrationLookupStatus.MISSING
                existing.resultFingerprintSha256 == requested.resultFingerprintSha256 ->
                    ContentMigrationLookupStatus.REPLAY
                else -> ContentMigrationLookupStatus.CONFLICT
            },
            namespace = requested.namespace,
            sourceDigestSha256 = requested.sourceDigestSha256,
            requestedResultFingerprintSha256 = requested.resultFingerprintSha256,
            deterministicCommitId = requested.commitId,
            existing = existing,
        )
    }

    private fun replayResult(batch: ContentCommitBatch<D>): ContentCommitResult = ContentCommitResult(
        commitId = batch.commitId,
        replayed = true,
        deferred = false,
        committedGeneration = null,
        attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
        outboxDraftIds = batch.outbox.map(outboxAdapter::id),
        migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
        publicationIds = batch.publications.map { it.publicationKey.value },
        auxiliaryAttachmentIds = batch.auxiliaryAttachments.map(AuxiliaryBlobAttachment::attachmentKey),
        quarantineIds = batch.quarantines.map(ContentQuarantineMutation::quarantineId),
        rightsGrantIds = batch.rightsGrants.map { it.grantReference.value },
        blobSyncJobIds = batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId),
        blobRemovalIntentIds = batch.replicaReplacement?.replacement?.let { cursor ->
            blobRemovalIntents.values
                .filter { it.sourceCursor == cursor }
                .map(ContentBlobRemovalIntent::intentId)
                .sorted()
        }.orEmpty(),
    )

    private fun validateReplicaReplay(batch: ContentCommitBatch<D>) {
        val replacement = requireNotNull(batch.replicaReplacement).replacement
        val durablePublication = publications[replacement.publicationKey]
        val durableAttachments = blobStore.withExclusiveTransaction {
            blobStore.manifestAttachmentsLocked(replacement.publicationKey)
        }
        if (replacement.present) {
            if (durablePublication != batch.publications.single().publication ||
                durableAttachments != batch.attachments
                    .map(ManifestAttachment::asBlobAttachment)
                    .sortedBy(BlobAttachment::attachmentKey)
            ) {
                throw ContentTransactionException.CommitConflict(
                    "replica-state:${replacement.publicationKey.value}",
                )
            }
            val requestedRights = batch.rightsGrants.associate { it.grantReference to it.grant }
            val durableRights = rightsGrants.filterValues {
                it.scope.publicationId == replacement.publicationKey
            }
            if (durableRights != requestedRights) {
                throw ContentTransactionException.CommitConflict(
                    "replica-rights:${replacement.publicationKey.value}",
                )
            }
        } else if (durablePublication != null || durableAttachments.isNotEmpty() ||
            rightsGrants.values.any { it.scope.publicationId == replacement.publicationKey }
        ) {
            throw ContentTransactionException.CommitConflict(
                "replica-tombstone:${replacement.publicationKey.value}",
            )
        }
    }

    private fun consumeReplayReceipts(
        receipts: List<BlobPublishReceipt>,
        attachments: List<ManifestAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
    ) {
        blobStore.withExclusiveTransaction {
            // A migration ledger proves that the source/result was already committed, but it
            // does not authorize a caller to attach a new owner/blob during replay.  Retire a
            // receipt only when the caller supplied the exact durable attachment (including its
            // complete manifest/ref graph); unrelated/new attachments remain untouched.
            val durableAttachments = attachments.mapNotNull { candidate ->
                val candidateSnapshot = candidate.deepImmutableSnapshot()
                val durable = blobStore.attachedLocked(
                    candidateSnapshot.owner,
                    candidateSnapshot.manifestId,
                    candidateSnapshot.contentRevision,
                )
                candidateSnapshot.asBlobAttachment().takeIf { it == durable }
            }
            val durableAuxiliaryAttachments = auxiliaryAttachments.mapNotNull { candidate ->
                val candidateSnapshot = candidate.deepImmutableSnapshot()
                candidateSnapshot.takeIf {
                    blobStore.auxiliaryAttachedLocked(candidateSnapshot.attachmentKey) == candidateSnapshot
                }
            }
            // The migration ledger is the durable replay authority.  A reopened process may
            // have lost local blob bytes (or have detected corruption) even though its durable
            // metadata/attachment row is intact.  In that case replay still succeeds, but no
            // ephemeral receipt is retired; the caller can recover or republish the capability
            // later.  Only expected blob-boundary failures are swallowed here.
            try {
                blobStore.hydrateAttachmentsLocked(durableAttachments, durableAuxiliaryAttachments)
            } catch (_: ContentBlobStoreException) {
                return@withExclusiveTransaction
            }
            val referencedBlobIds = (
                durableAttachments.flatMap(BlobAttachment::blobs) +
                    durableAuxiliaryAttachments.flatMap(AuxiliaryBlobAttachment::blobs)
                )
                .mapTo(hashSetOf(), BlobRef::blobId)
            if (receipts.isEmpty() || referencedBlobIds.isEmpty()) return@withExclusiveTransaction
            val eligible = receipts.filter { it.reference.blobId in referencedBlobIds }
            val valid = eligible.mapNotNull { receipt ->
                try {
                    blobStore.validateReceiptsOnlyLocked(listOf(receipt))
                    receipt
                } catch (_: ContentBlobStoreException) {
                    // Semantic replay does not depend on an ephemeral receipt.  A valid local
                    // receipt is opportunistically retired; a stale/foreign one is left alone.
                    null
                }
            }
            if (valid.isNotEmpty()) {
                try {
                    // Validate/consume the valid subset as one operation so a multi-resource
                    // replay cannot partially retire its local capabilities.
                    blobStore.consumeReceiptsLocked(valid)
                } catch (_: ContentBlobStoreException) {
                    // Keep all capabilities pending if the final grouped consume no longer
                    // matches; durable semantic replay itself remains successful.
                }
            }
        }
    }

    private fun validateMetadataAndAliases(batch: ContentCommitBatch<D>) {
        batch.metadata.forEach { draft ->
            metadata[draft.key]?.let { if (it != draft.value) throw ContentTransactionException.CommitConflict("metadata:${draft.key}") }
        }
        batch.aliases.forEach { draft ->
            aliases[draft.alias]?.let { if (it != draft.target) throw ContentTransactionException.CommitConflict("alias:${draft.alias}") }
        }
    }

    private fun validateOutbox(batch: ContentCommitBatch<D>) {
        batch.outbox.forEach { draft ->
            val id = outboxAdapter.id(draft)
            outbox[id]?.let { existing ->
                if (!outboxAdapter.fingerprint(existing).contentEquals(outboxAdapter.fingerprint(draft))) {
                    throw ContentTransactionException.CommitConflict("outbox:$id")
                }
            }
        }
    }

    private fun validateMigrations(batch: ContentCommitBatch<D>) {
        batch.migrations.forEach { mutation ->
            migrations[mutation.migrationKey]?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict("migration:${mutation.migrationKey}")
                }
            }
        }
    }

    private fun validatePublications(batch: ContentCommitBatch<D>) {
        batch.publications.forEach { mutation ->
            publications[mutation.publicationKey]?.let { existing ->
                if (existing != mutation.publication &&
                    !isImmutablePublicationGraphExtension(existing, mutation.publication)
                ) {
                    throw ContentTransactionException.CommitConflict(
                        "publication:${mutation.publicationKey.value}",
                    )
                }
            }
        }
    }

    private fun validateQuarantines(batch: ContentCommitBatch<D>) {
        val auxiliaryByOwner = batch.auxiliaryAttachments.associateBy(AuxiliaryBlobAttachment::ownerId)
        batch.quarantines.forEach { mutation ->
            val attachment = auxiliaryByOwner[mutation.auxiliaryOwnerId]
                ?: throw ContentTransactionException.DuplicateEntry(
                    "Quarantine ${mutation.quarantineId} is missing its auxiliary blob attachment",
                )
            if (attachment.purpose != AuxiliaryBlobPurpose.PLUGIN_QUARANTINE ||
                attachment.blobs != listOf(mutation.scriptBlob)
            ) {
                throw ContentTransactionException.CommitConflict(
                    "quarantine-attachment:${mutation.quarantineId}",
                )
            }
            quarantines[mutation.quarantineId]?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict(
                        "quarantine:${mutation.quarantineId}",
                    )
                }
            }
        }
    }

    private fun validateRightsGrants(batch: ContentCommitBatch<D>) {
        // Older projection rows may carry a reference whose provider policy remains local-only;
        // absence therefore stays fail-closed at RightsAuthority instead of breaking decode.
        val publicationByKey = LinkedHashMap(publications)
        batch.publications.forEach { publicationByKey[it.publicationKey] = it.publication }
        val acquisitions = publicationByKey.values
            .flatMap { publication -> publication.acquisitions.map { publication.key to it } }
        batch.rightsGrants.forEach { mutation ->
            val scopedAcquisition = acquisitions.singleOrNull { (publicationKey, acquisition) ->
                acquisition.rightsGrantRef == mutation.grantReference &&
                    publicationKey == mutation.grant.scope.publicationId &&
                    acquisition.id == mutation.grant.scope.acquisitionId
            }
            if (scopedAcquisition == null) {
                throw ContentTransactionException.DuplicateEntry(
                    "Rights grant ${mutation.grantReference.value} has no exact acquisition scope",
                )
            }
            rightsGrants[mutation.grantReference]?.let { existing ->
                if (existing != mutation.grant) {
                    throw ContentTransactionException.CommitConflict(
                        "rights:${mutation.grantReference.value}",
                    )
                }
            }
        }
    }

    private fun validateBlobSyncJobs(batch: ContentCommitBatch<D>) {
        if (batch.blobSyncJobs.isEmpty()) return
        val grants = LinkedHashMap(rightsGrants)
        batch.rightsGrants.forEach { grants[it.grantReference] = it.grant }
        batch.blobSyncJobs.forEach { job ->
            val attachment = batch.attachments.singleOrNull {
                it.owner == job.owner && it.manifestId == job.manifestId &&
                    it.contentRevision == job.contentRevision
            } ?: blobStore.attachedLocked(job.owner, job.manifestId, job.contentRevision)?.let { durable ->
                ManifestAttachment(durable.owner, durable.manifest)
            }
            if (attachment == null || job.blob !in attachment.blobs) {
                throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no exact durable manifest attachment",
                )
            }
            val grant = grants[job.grantReference]
                ?: throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no durable rights grant",
                )
            if (!grant.scope.covers(job.accessScope) || ContentOperation.SYNC_BLOB !in grant.allowedOperations) {
                throw ContentTransactionException.CommitConflict("blob-sync-rights:${job.jobId}")
            }
            blobSyncJobs[job.jobId]?.let { existing ->
                if (existing != job) throw ContentTransactionException.CommitConflict("blob-sync-job:${job.jobId}")
            }
        }
    }

    private fun fingerprint(batch: ContentCommitBatch<D>, authoritativeSyncMode: ContentSyncMode): String {
        val writer = CanonicalWriter()
        writer.string(batch.commitId)
        writer.string(authoritativeSyncMode.name)
        writer.string(batch.unrepresentableDraftPolicy.name)
        writer.string(batch.semantics.name)
        writer.list(batch.receipts.sortedBy(BlobPublishReceipt::commitToken)) { receipt ->
            string(receipt.storeInstanceId); string(receipt.commitToken); blobRef(receipt.reference)
            long(receipt.incarnation); long(receipt.generation); long(receipt.publishedAtEpochMillis)
        }
        writer.list(batch.attachments.sortedBy(ManifestAttachment::attachmentKey)) { attachment ->
            string(attachment.owner.publicationKey.value)
            string(attachment.owner.acquisitionId)
            string(attachment.owner.unitKey.value)
            bytes(canonicalJsonBytes(CANONICAL_JSON.encodeToJsonElement(ContentManifest.serializer(), attachment.manifest)))
        }
        writer.list(batch.metadata.sortedBy(ContentMetadataMutation::key)) { string(it.key); string(it.value) }
        writer.list(batch.aliases.sortedBy(ContentAliasMutation::alias)) { string(it.alias); string(it.target) }
        writer.list(batch.migrations.sortedBy(ContentMigrationLedgerMutation::migrationKey)) { mutation ->
            string(mutation.namespace); string(mutation.sourceDigestSha256); string(mutation.resultFingerprintSha256)
        }
        writer.list(batch.publications.sortedBy { it.publicationKey.value }) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(Publication.serializer(), mutation.publication),
                ),
            )
        }
        writer.list(batch.auxiliaryAttachments.sortedBy(AuxiliaryBlobAttachment::attachmentKey)) { attachment ->
            string(attachment.ownerId)
            string(attachment.purpose.name)
            list(attachment.blobs.sortedBy(BlobRef::blobId)) { blobRef(it) }
        }
        writer.list(batch.quarantines.sortedBy(ContentQuarantineMutation::quarantineId)) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(ContentQuarantineMutation.serializer(), mutation),
                ),
            )
        }
        writer.list(batch.rightsGrants.sortedBy { it.grantReference.value }) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(RightsGrant.serializer(), mutation.grant),
                ),
            )
        }
        writer.list(batch.blobSyncJobs.sortedBy(ContentBlobSyncJobMutation::jobId)) { mutation ->
            bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(ContentBlobSyncJobMutation.serializer(), mutation),
                ),
            )
        }
        batch.replicaReplacement?.let { replacement ->
            writer.bytes(
                canonicalJsonBytes(
                    CANONICAL_JSON.encodeToJsonElement(
                        ContentPublicationReplicaReplacement.serializer(),
                        replacement,
                    ),
                ),
            )
        } ?: writer.bytes(ByteArray(0))
        writer.list(batch.outbox.sortedBy(outboxAdapter::id)) { draft ->
            string(outboxAdapter.id(draft)); bytes(outboxAdapter.fingerprint(draft))
        }
        return Sha256.hex(writer.toByteArray())
    }

    private fun maybeFail(point: ContentTransactionFailurePoint) {
        if (failureInjection == point) {
            failureInjection = null
            throw IllegalStateException("Injected shared content transaction failure at $point")
        }
    }

    private fun validateAcknowledgementIds(ids: Set<String>, label: String) {
        ids.forEach { requireSafeTransactionKey(it, label) }
    }

    private inline fun <T> withTransactionLock(block: () -> T): T {
        return transactionMutex.withLock(block)
    }

    private fun <K, V> restore(target: MutableMap<K, V>, snapshot: Map<K, V>) {
        target.clear(); target.putAll(snapshot)
    }

    private data class CommittedBatch(
        val fingerprint: String,
        val result: ContentCommitResult,
        val receipts: List<BlobPublishReceipt>,
    )
}

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

private val CANONICAL_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    allowStructuredMapKeys = true
}

private class CanonicalWriter {
    private val output = ArrayList<Byte>()

    fun string(value: String) = bytes(value.encodeToByteArray())
    fun bytes(value: ByteArray) {
        long(value.size.toLong())
        value.forEach(output::add)
    }
    fun long(value: Long) {
        for (shift in 56 downTo 0 step 8) output += ((value ushr shift) and 0xff).toByte()
    }
    fun blobRef(reference: BlobRef) {
        string(reference.blobId); long(reference.schemaVersion.toLong()); string(reference.digestAlgorithm)
        string(reference.plaintextDigest); long(reference.byteSize); string(reference.mediaType)
    }
    fun <T> list(values: List<T>, write: CanonicalWriter.(T) -> Unit) {
        long(values.size.toLong())
        values.forEach { write(it) }
    }
    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private fun canonicalJsonBytes(element: JsonElement): ByteArray = CanonicalWriter().also { writer ->
    fun write(value: JsonElement) {
        when (value) {
            JsonNull -> writer.string("null")
            is JsonPrimitive -> {
                writer.string(if (value.isString) "string" else "primitive")
                writer.string(value.content)
            }
            is JsonArray -> {
                writer.string("array"); writer.long(value.size.toLong()); value.forEach(::write)
            }
            is JsonObject -> {
                writer.string("object"); writer.long(value.size.toLong())
                value.keys.sorted().forEach { key -> writer.string(key); write(requireNotNull(value[key])) }
            }
        }
    }
    write(element)
}.toByteArray()

private const val MAX_TRANSACTION_VALUE_LENGTH: Int = 1_000_000
internal const val CONTENT_REPLICA_COMMIT_ID_PREFIX: String = "replica:"
private val SHA256_HEX = Regex("[0-9a-f]{64}")

private fun ContentPublicationReplicaCursor.belongsTo(authority: ContentReplicaAuthority): Boolean =
    instanceId == authority.instanceId && workspaceId == authority.workspaceId

private fun String.isReplicaCommitFor(publicationKey: PublicationKey): Boolean =
    startsWith("$CONTENT_REPLICA_COMMIT_ID_PREFIX${publicationKey.value}:")

private fun requireSafeTransactionKey(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 4096) { "$label must be non-blank and bounded" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
}
