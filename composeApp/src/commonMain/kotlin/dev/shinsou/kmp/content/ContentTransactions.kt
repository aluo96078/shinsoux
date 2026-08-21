package dev.shinsou.kmp.content

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
import dev.shinsou.kmp.sync.v2.SYNC_STATE_SCHEMA_VERSION
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncMutation
import kotlinx.coroutines.sync.Mutex
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
        draft.event.schemaVersion == SYNC_STATE_SCHEMA_VERSION &&
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
    }
}

/** Short alias used by storage hosts while retaining the real [SyncDraft] serialization. */
public val SyncDraftOutboxAdapter: ContentOutboxAdapter<SyncDraft> = SyncDraftContentOutboxAdapter

/** Explicitly typed durable adapter for SQL-backed content transaction stores. */
public val SyncDraftPersistenceOutboxAdapter: ContentOutboxPersistenceAdapter<SyncDraft> =
    SyncDraftContentOutboxAdapter

public enum class ContentSyncMode { V1_ACTIVE, V2_ACTIVE, INACTIVE }
public enum class UnrepresentableDraftPolicy { REJECT, DEFER }

public data class ContentCommitResult(
    val commitId: String,
    val replayed: Boolean,
    val deferred: Boolean,
    val committedGeneration: Long?,
    val attachedOwnerIds: List<String>,
    val outboxDraftIds: List<String>,
    val migrationKeys: List<String> = emptyList(),
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
    public val unrepresentableDraftPolicy: UnrepresentableDraftPolicy = UnrepresentableDraftPolicy.REJECT,
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

    init {
        if (this.migrations.isNotEmpty()) {
            require(this.migrations.size == 1) { "A content transaction may commit one migration source" }
            require(this.commitId == this.migrations.single().commitId) {
                "Migration transactions must use the deterministic source-digest commit id"
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
        unrepresentableDraftPolicy: UnrepresentableDraftPolicy = this.unrepresentableDraftPolicy,
    ): ContentCommitBatch<D> = ContentCommitBatch(
        commitId = commitId,
        receipts = receipts,
        attachments = attachments,
        metadata = metadata,
        aliases = aliases,
        outbox = outbox,
        migrations = migrations,
        unrepresentableDraftPolicy = unrepresentableDraftPolicy,
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
)

/** Executable shared-transaction oracle with rollback at every physical write boundary. */
public class InMemorySharedContentTransactionStore<D : Any>(
    public val blobStore: InMemoryContentBlobStore,
    private val outboxAdapter: ContentOutboxAdapter<D>,
    /** Host-owned negotiated state; an individual batch cannot self-declare a future wire. */
    private val syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
) : SharedContentTransactionStore<D> {
    private val transactionMutex = Mutex()
    private val metadata = LinkedHashMap<String, String>()
    private val aliases = LinkedHashMap<String, String>()
    private val migrations = LinkedHashMap<String, ContentMigrationLedgerMutation>()
    private val outbox = LinkedHashMap<String, D>()
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
            )
        }

    override fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = withTransactionLock {
        lookupMigrationLedgerLocked(namespace, sourceDigestSha256, resultFingerprintSha256)
    }

    override fun commit(batch: ContentCommitBatch<D>): ContentCommitResult = withTransactionLock {
        batch.outbox.forEach { draft ->
            outboxAdapter.validate(draft)
            requireSafeTransactionKey(outboxAdapter.id(draft), "Outbox draft id")
        }
        val authoritativeSyncMode = syncModeProvider()
        val fingerprint = fingerprint(batch, authoritativeSyncMode)
        val migrationReplay = batch.migrations.singleOrNull()?.let { migration ->
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
                consumeReplayReceipts(batch.receipts, batch.attachments)
                return@withTransactionLock (previous?.result ?: replayResult(batch)).copy(replayed = true)
            }
        }
        commits[batch.commitId]?.let { previous ->
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
            if (batch.attachments.isNotEmpty() || batch.receipts.isNotEmpty()) {
                batch.attachments.mapTo(this) { "manifest:${it.manifestId}" }
                if (batch.attachments.isEmpty()) add("unattached-blob-receipt")
            }
            batch.migrations.mapTo(this) { "migration:${it.migrationKey}" }
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
            val commitSnapshot = LinkedHashMap(commits)
            try {
                blobStore.validateAtomicAttachmentsLocked(batch.receipts, blobAttachments)
                maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE)
                validateMetadataAndAliases(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE)
                validateMigrations(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_VALIDATE)
                validateOutbox(batch)
                maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_VALIDATE)

                blobStore.consumeAtomicAttachmentsLocked(batch.receipts, blobAttachments) {
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
                val result = ContentCommitResult(
                    batch.commitId,
                    replayed = false,
                    deferred = false,
                    committedGeneration = batch.receipts.maxOfOrNull(BlobPublishReceipt::generation),
                    attachedOwnerIds = batch.attachments.map { it.owner.scopeKey },
                    outboxDraftIds = batch.outbox.map(outboxAdapter::id),
                    migrationKeys = batch.migrations.map(ContentMigrationLedgerMutation::migrationKey),
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
    )

    private fun consumeReplayReceipts(
        receipts: List<BlobPublishReceipt>,
        attachments: List<ManifestAttachment>,
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
            // The migration ledger is the durable replay authority.  A reopened process may
            // have lost local blob bytes (or have detected corruption) even though its durable
            // metadata/attachment row is intact.  In that case replay still succeeds, but no
            // ephemeral receipt is retired; the caller can recover or republish the capability
            // later.  Only expected blob-boundary failures are swallowed here.
            try {
                blobStore.hydrateAttachmentsLocked(durableAttachments)
            } catch (_: ContentBlobStoreException) {
                return@withExclusiveTransaction
            }
            val referencedBlobIds = durableAttachments
                .flatMap(BlobAttachment::blobs)
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

    private fun fingerprint(batch: ContentCommitBatch<D>, authoritativeSyncMode: ContentSyncMode): String {
        val writer = CanonicalWriter()
        writer.string(batch.commitId)
        writer.string(authoritativeSyncMode.name)
        writer.string(batch.unrepresentableDraftPolicy.name)
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

    private inline fun <T> withTransactionLock(block: () -> T): T {
        if (!transactionMutex.tryLock()) throw IllegalStateException("Concurrent content transaction must retry")
        return try { block() } finally { transactionMutex.unlock() }
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
    AFTER_JOURNAL_WRITE,
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
private val SHA256_HEX = Regex("[0-9a-f]{64}")

private fun requireSafeTransactionKey(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 4096) { "$label must be non-blank and bounded" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
}
