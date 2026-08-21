package dev.shinsou.kmp.content

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.SuspendingTransacterImpl
import dev.shinsou.kmp.annotation.SqlDriverContentAnnotationStore
import dev.shinsou.kmp.app.LegacyPublicationProjection
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.rights.InMemoryRightsAuthority
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.search.DerivedLocalFullTextIndex
import dev.shinsou.kmp.sync.v2.SyncDraft

/** Typed publication read side for acquisition/import consumers. */
public interface PublicationStore {
    public fun find(key: PublicationKey): Publication?
    public fun all(): List<Publication>
}

/** Durable full-grant read side paired with acquisition grant references. */
public interface RightsGrantStore {
    public fun find(reference: RightsGrantRef): RightsGrant?
    public fun all(): List<RightsGrant>
}

/** One explicit, two-phase orphan scan. Newly discovered bodies are never deleted in this pass. */
public data class ContentBlobRecoveryResult(
    val discoveredCount: Int,
    val eligibleCount: Int,
    val removedCount: Int,
)

/**
 * A short-lived host admission used while a remote immutable body is being verified. Releasing
 * the lease revokes every grant that did not become the exact durable policy in the enclosing
 * content transaction. Implementations must make [release] idempotent for cancellation paths.
 */
public fun interface ContentRightsAdmissionLease {
    public fun release()
}

/**
 * Production M1 graph over one platform-owned SQLite driver.
 *
 * Importers atomically commit publications, aliases, manifests, blob receipts, body jobs and
 * portable drafts here. Blob construction restores only bounded metadata; immutable payloads stay
 * outside the cold-start path until [ContentBlobStore.openRead].
 * [dev.shinsou.kmp.sync.v2.ContentSyncOutboxDrainBridge] then re-clocks drafts inside LocalSyncStore
 * and acknowledges them only after that journal is durable.
 */
public class ContentFoundationRuntime(
    private val driver: SqlDriver,
    contentBlobDirectoryPath: String? = null,
    syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V2_ACTIVE },
    private val ownsDriver: Boolean = false,
) {
    private val sharedTransactions = object : SuspendingTransacterImpl(driver) {}
    public val blobStore: SqlDriverContentBlobStore = SqlDriverContentBlobStore(
        driver = driver,
        blobDirectoryPath = contentBlobDirectoryPath,
    )
    public val aliases: SqlDriverPortableAliasResolver = SqlDriverPortableAliasResolver(driver)
    /** Annotation rows share this platform-owned driver and SQLite transaction authority. */
    public val annotations: SqlDriverContentAnnotationStore = SqlDriverContentAnnotationStore(driver)
    public val legacyProjection: LegacyPublicationProjection = LegacyPublicationProjection(aliases)

    private val sqlTransactions = SqlDriverContentTransactionStore<SyncDraft>(
        driver,
        blobStore,
        SyncDraftContentOutboxAdapter,
        syncModeProvider = syncModeProvider,
        ownsDriver = false,
    )

    private val initialRightsGrants: Map<RightsGrantRef, RightsGrant> =
        sqlTransactions.allRightsGrantsDirect().associateBy(RightsGrant::grantId)
    private val knownDurableRightsGrants = initialRightsGrants.toMutableMap()
    private val hydratedRightsAuthority = InMemoryRightsAuthority().also { authority ->
        initialRightsGrants.values.forEach(authority::admit)
    }

    /** Host-owned authority; revoke/admit changes are observed by the very next gate resolution. */
    public val rightsAuthority: InMemoryRightsAuthority = hydratedRightsAuthority

    public val transactions: SharedContentTransactionStore<SyncDraft> =
        SyncAuthorityCoordinatedContentTransactions(sqlTransactions) { batch, result ->
            // Resolve from the durable read side rather than trusting the caller's batch. A
            // migration semantic replay may legitimately ignore newly supplied batch fields.
            val replacedPublication = batch.replicaReplacement?.replacement?.publicationKey
                ?.takeIf { batch.semantics == ContentCommitSemantics.REPLACE_PUBLICATION_REPLICA }
            if (replacedPublication != null) {
                // A publication-scoped replica transaction owns the complete grant set for that
                // publication. Revoke grants which disappeared (including tombstones), replace
                // changed grants, and leave every unrelated publication's runtime policy intact.
                val knownForPublication = knownDurableRightsGrants.filterValues {
                    it.scope.publicationId == replacedPublication
                }
                val durableForPublication = sqlTransactions
                    .rightsGrantsForPublicationDirect(replacedPublication)
                    .associateBy(RightsGrant::grantId)
                // An exact semantic replay must not silently undo an explicit runtime revocation.
                // If another runtime actually changed SQLite, the remembered durable map differs
                // and this runtime still converges to the new complete publication policy.
                if (!result.replayed || knownForPublication != durableForPublication) {
                    knownForPublication.keys.forEach { reference ->
                        hydratedRightsAuthority.revoke(reference)
                        knownDurableRightsGrants.remove(reference)
                    }
                    durableForPublication.values.forEach { grant ->
                        hydratedRightsAuthority.admit(grant)
                        knownDurableRightsGrants[grant.grantId] = grant
                    }
                }
            } else {
                result.rightsGrantIds.forEach { id ->
                    val reference = RightsGrantRef(id)
                    val newlyDurable = reference !in knownDurableRightsGrants
                    sqlTransactions.findRightsGrantDirect(reference)?.let { grant ->
                        if (newlyDurable) {
                            hydratedRightsAuthority.admit(grant)
                            knownDurableRightsGrants[reference] = grant
                        }
                    }
                }
            }
        }

    public val publications: PublicationStore = object : PublicationStore {
        override fun find(key: PublicationKey): Publication? = sqlTransactions.findPublicationDirect(key)
        override fun all(): List<Publication> = sqlTransactions.allPublicationsDirect()
    }

    public val rightsGrants: RightsGrantStore = object : RightsGrantStore {
        override fun find(reference: RightsGrantRef): RightsGrant? =
            sqlTransactions.findRightsGrantDirect(reference)

        override fun all(): List<RightsGrant> = sqlTransactions.allRightsGrantsDirect()
    }

    /** Body-free metadata/alias/migration authority used by portable Backup v2 and repair jobs. */
    public fun portableAuxiliaryState(): ContentPortableAuxiliaryState =
        sqlTransactions.portableAuxiliaryStateDirect()

    /** Creates the M4 derived index on this runtime's platform-owned shared SQLite driver. */
    public fun createDerivedLocalFullTextIndex(
        operationGate: ContentOperationGate,
    ): DerivedLocalFullTextIndex = DerivedLocalFullTextIndex(driver, operationGate)

    /**
     * Temporarily admits authenticated replica grants so the normal SYNC_BLOB/OFFLINE_STORE gate
     * can authorize a destination download before the graph transaction exists locally.
     *
     * A durable grant is never re-admitted here: doing so would silently undo an explicit runtime
     * revocation. A conflicting durable grant fails before any provisional policy is left active.
     * New grants remain provisional until the caller releases this lease; only an exact grant
     * committed by [transactions] survives release.
     */
    public fun acquireProvisionalRightsAdmission(
        grants: List<RightsGrant>,
    ): ContentRightsAdmissionLease {
        val unique = LinkedHashMap<RightsGrantRef, RightsGrant>()
        grants.forEach { grant ->
            grant.validate()
            val previous = unique.put(grant.grantId, grant)
            require(previous == null || previous == grant) {
                "Replica rights admission contains conflicting grants for one identity"
            }
        }

        val provisional = ArrayList<Pair<RightsGrantRef, RightsGrant>>(unique.size)
        try {
            unique.values.forEach { grant ->
                val durable = rightsGrants.find(grant.grantId)
                require(durable == null || durable == grant) {
                    "Replica rights grant conflicts with durable host policy"
                }
                if (durable == null) {
                    hydratedRightsAuthority.admit(grant)
                    provisional += grant.grantId to grant
                }
            }
        } catch (failure: Throwable) {
            provisional.asReversed().forEach { (reference, _) ->
                hydratedRightsAuthority.revoke(reference)
            }
            throw failure
        }

        return object : ContentRightsAdmissionLease {
            private var released = false

            override fun release() {
                if (released) return
                released = true
                provisional.forEach { (reference, expected) ->
                    // A missing or conflicting row must fail closed. The exact durable row is
                    // admitted by the transaction callback before commit returns.
                    if (rightsGrants.find(reference) != expected) {
                        hydratedRightsAuthority.revoke(reference)
                    }
                }
            }
        }
    }

    /**
     * Applies the exact durable policy set after a verified portable replacement has committed.
     * This explicit operation may re-admit a restored grant; routine hydration deliberately does
     * not, because an ordinary runtime revocation must otherwise remain fail-closed.
     */
    public fun replaceRuntimeRightsWithDurableState() {
        knownDurableRightsGrants.keys.forEach(hydratedRightsAuthority::revoke)
        knownDurableRightsGrants.clear()
        sqlTransactions.allRightsGrantsDirect().forEach { grant ->
            hydratedRightsAuthority.admit(grant)
            knownDurableRightsGrants[grant.grantId] = grant
        }
    }

    /** Focused transaction-contract seam; production callers never set a failure point. */
    internal fun injectTransactionFailureForTesting(point: ContentTransactionFailurePoint?) {
        sqlTransactions.failureInjection = point
    }

    /**
     * Runs the M1 recovery boundary after every SQL attachment has been hydrated.
     *
     * The generation cutoff is captured before discovery. [ContentBlobStore.sweepRecovery]
     * re-checks attachments, receipts and active readers, while [minimumAgeMillis] ensures a body
     * first observed as orphaned in this pass is retained until a later background pass.
     */
    public fun recoverOrphanedBlobs(
        nowEpochMillis: Long,
        minimumAgeMillis: Long,
    ): ContentBlobRecoveryResult {
        val boundary = RecoveryBoundary(
            safetyCutoffGeneration = blobStore.currentGeneration,
            nowEpochMillis = nowEpochMillis,
            minimumAgeMillis = minimumAgeMillis,
        )
        val plan = blobStore.planRecovery(boundary)
        return ContentBlobRecoveryResult(
            discoveredCount = plan.discoveredOrphans.size,
            eligibleCount = plan.candidates.size,
            removedCount = blobStore.sweepRecovery(plan),
        )
    }

    /**
     * Enclosing transaction for host-coordinated backup restore. Every nested content, annotation,
     * and sync-journal store uses the same platform driver with `noEnclosing = false`.
     * Callers must keep this block local-only and must not perform network I/O while it is open.
     */
    public suspend fun <T> transaction(block: suspend () -> T): T =
        sharedTransactions.transactionWithResult(noEnclosing = false) { block() }

    public fun close() {
        if (ownsDriver) driver.close()
    }
}

private class SyncAuthorityCoordinatedContentTransactions(
    private val delegate: SqlDriverContentTransactionStore<SyncDraft>,
    private val afterCommit: (ContentCommitBatch<SyncDraft>, ContentCommitResult) -> Unit,
) : SharedContentTransactionStore<SyncDraft> {
    override fun commit(batch: ContentCommitBatch<SyncDraft>): ContentCommitResult {
        return delegate.commit(batch).also { result -> afterCommit(batch, result) }
    }

    override fun pendingOutbox(): List<SyncDraft> = delegate.pendingOutbox()

    override fun acknowledgeOutbox(draftIds: Set<String>): Int = delegate.acknowledgeOutbox(draftIds)

    override fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = delegate.pendingBlobSyncJobs()

    override fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int = delegate.acknowledgeBlobSyncJobs(jobIds)

    override fun publicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? = delegate.publicationReplicaCursor(publicationKey)

    override fun pendingBlobRemovalIntents(): List<ContentBlobRemovalIntent> =
        delegate.pendingBlobRemovalIntents()

    override fun acknowledgeBlobRemovalIntents(intentIds: Set<String>): Int =
        delegate.acknowledgeBlobRemovalIntents(intentIds)

    override fun detachReplicaAuthority(
        authority: ContentReplicaAuthority,
    ): ContentReplicaAuthorityDepartureResult = delegate.detachReplicaAuthority(authority)

    override fun lookupMigrationLedger(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup = delegate.lookupMigrationLedger(
        namespace,
        sourceDigestSha256,
        resultFingerprintSha256,
    )
}
