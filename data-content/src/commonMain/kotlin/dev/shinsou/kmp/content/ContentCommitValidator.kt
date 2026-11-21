package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.rights.ContentOperation

/** The checks a [ContentCommitBatch] passes before any row is written; each reads, none writes. */
internal class ContentCommitValidator<D : Any>(
    private val tables: ContentTables,
    private val outbox: ContentOutboxTable<D>,
) : ContentSqlOps by tables {
    private val outboxAdapter = outbox.outboxAdapter

    fun validateDrafts(batch: ContentCommitBatch<D>) {
        batch.outbox.forEach { draft ->
            outboxAdapter.validate(draft)
            requireSafeSqlKey(outboxAdapter.id(draft), "Outbox draft id")
            val encoded = outboxAdapter.encode(draft)
            require(encoded.length <= MAX_SQL_PAYLOAD_LENGTH) { "Outbox payload is too large" }
        }
    }

    fun unrepresentableV1Gaps(
        batch: ContentCommitBatch<D>,
        authoritativeSyncMode: ContentSyncMode,
    ): List<String> {
        if (authoritativeSyncMode != ContentSyncMode.V1_ACTIVE) return emptyList()
        return buildList {
        batch.outbox.filterNot(outboxAdapter::isRepresentableByCurrentV1).mapTo(this, outboxAdapter::id)
        // There is no typed v1 proof for the new metadata/alias authority.  Fail closed instead
        // of treating a string key/value as if the legacy wire could represent it losslessly.
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
    }

    fun validateUniqueBatchEntries(batch: ContentCommitBatch<D>) {
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
        if (batch.receipts.map(BlobPublishReceipt::commitToken).distinct().size != batch.receipts.size) {
            throw ContentTransactionException.DuplicateEntry("Blob receipt tokens must be unique")
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
        if (batch.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId).distinct().size !=
            batch.blobSyncJobs.size
        ) {
            throw ContentTransactionException.DuplicateEntry("Blob sync job ids must be unique")
        }
    }

    fun validateSqlRows(batch: ContentCommitBatch<D>) {
        batch.metadata.forEach { mutation ->
            findString(TABLE_METADATA, "key", mutation.key, "value")?.let { existing ->
                if (existing != mutation.value) throw ContentTransactionException.CommitConflict("metadata:${mutation.key}")
            }
        }
        batch.aliases.forEach { mutation ->
            findString(TABLE_ALIASES, "alias", mutation.alias, "target")?.let { existing ->
                if (existing != mutation.target) throw ContentTransactionException.CommitConflict("alias:${mutation.alias}")
            }
        }
        batch.migrations.forEach { mutation ->
            tables.ledger.findMigration(mutation.migrationKey)?.let { existing ->
                if (existing != mutation) throw ContentTransactionException.CommitConflict("migration:${mutation.migrationKey}")
            }
        }
        batch.outbox.forEach { draft ->
            val id = outboxAdapter.id(draft)
            findString(TABLE_OUTBOX, "draft_id", id, "payload")?.let { encoded ->
                val existing = outbox.decodeOutbox(encoded)
                outboxAdapter.validate(existing)
                if (!outboxAdapter.fingerprint(existing).contentEquals(outboxAdapter.fingerprint(draft))) {
                    throw ContentTransactionException.CommitConflict("outbox:$id")
                }
            }
        }
        batch.publications.forEach { mutation ->
            tables.publications.findPublication(mutation.publicationKey)?.let { existing ->
                if (existing != mutation.publication &&
                    !isImmutablePublicationGraphExtension(existing, mutation.publication)
                ) {
                    throw ContentTransactionException.CommitConflict(
                        "publication:${mutation.publicationKey.value}",
                    )
                }
            }
        }
        batch.quarantines.forEach { mutation ->
            tables.quarantines.findQuarantine(mutation.quarantineId)?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict(
                        "quarantine:${mutation.quarantineId}",
                    )
                }
            }
        }
        batch.rightsGrants.forEach { mutation ->
            tables.rights.findRightsGrant(mutation.grantReference)?.let { existing ->
                if (existing != mutation.grant) {
                    throw ContentTransactionException.CommitConflict(
                        "rights:${mutation.grantReference.value}",
                    )
                }
            }
        }
        batch.blobSyncJobs.forEach { mutation ->
            tables.blobSyncJobs.findBlobSyncJob(mutation.jobId)?.let { existing ->
                if (existing != mutation) {
                    throw ContentTransactionException.CommitConflict(
                        "blob-sync-job:${mutation.jobId}",
                    )
                }
            }
        }
    }

    fun validateReplicaReplay(batch: ContentCommitBatch<D>) {
        val replacement = requireNotNull(batch.replicaReplacement).replacement
        val durablePublication = tables.publications.findPublication(replacement.publicationKey)
        val durableAttachments = tables.attachments.loadPublicationAttachments(replacement.publicationKey)
        val durableRights = queryRows(
            TABLE_RIGHTS_GRANTS,
            "grant_id WHERE publication_id = ? " +
                "ORDER BY grant_id",
            replacement.publicationKey.value,
        ).associate { row ->
            val grant = requireNotNull(tables.rights.findRightsGrant(RightsGrantRef(row.string("grant_id"))))
            grant.grantId to grant
        }
        if (replacement.present) {
            if (durablePublication != batch.publications.single().publication ||
                durableAttachments != batch.attachments.sortedBy(ManifestAttachment::attachmentKey) ||
                durableRights != batch.rightsGrants.associate { it.grantReference to it.grant }
            ) {
                throw ContentTransactionException.CommitConflict(
                    "replica-state:${replacement.publicationKey.value}",
                )
            }
        } else if (durablePublication != null || durableAttachments.isNotEmpty() || durableRights.isNotEmpty()) {
            throw ContentTransactionException.CommitConflict(
                "replica-tombstone:${replacement.publicationKey.value}",
            )
        }
    }

    fun validatePublicationAttachments(batch: ContentCommitBatch<D>) {
        validateTypedPublicationAttachments(
            publications = batch.publications,
            suppliedAttachments = batch.attachments,
        ) { expected -> tables.attachments.findAttachment(expected.attachmentKey) }
    }

    fun validateQuarantines(batch: ContentCommitBatch<D>) {
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
        }
    }

    fun validateRightsGrants(batch: ContentCommitBatch<D>) {
        if (batch.rightsGrants.isEmpty()) return
        val publicationByKey = queryRows(TABLE_PUBLICATIONS, "publication_id ORDER BY publication_id")
            .associateTo(linkedMapOf()) { row ->
                val key = PublicationKey(row.string("publication_id"))
                key to requireNotNull(tables.publications.findPublication(key))
            }
        batch.publications.forEach { publicationByKey[it.publicationKey] = it.publication }
        val acquisitions = publicationByKey.values.flatMap { publication ->
            publication.acquisitions.map { acquisition -> publication.key to acquisition }
        }
        batch.rightsGrants.forEach { mutation ->
            val exactScope = acquisitions.singleOrNull { (publicationKey, acquisition) ->
                acquisition.rightsGrantRef == mutation.grantReference &&
                    publicationKey == mutation.grant.scope.publicationId &&
                    acquisition.id == mutation.grant.scope.acquisitionId
            }
            if (exactScope == null) {
                throw ContentTransactionException.DuplicateEntry(
                    "Rights grant ${mutation.grantReference.value} has no exact acquisition scope",
                )
            }
        }
    }

    fun validateBlobSyncJobs(batch: ContentCommitBatch<D>) {
        batch.blobSyncJobs.forEach { job ->
            val attachment = batch.attachments.singleOrNull {
                it.owner == job.owner &&
                    it.manifestId == job.manifestId &&
                    it.contentRevision == job.contentRevision
            } ?: tables.attachments.findAttachment(blobSyncAttachmentKey(job))
            if (attachment == null || job.blob !in attachment.blobs) {
                throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no exact durable manifest attachment",
                )
            }
            val grant = batch.rightsGrants
                .singleOrNull { it.grantReference == job.grantReference }
                ?.grant
                ?: tables.rights.findRightsGrant(job.grantReference)
                ?: throw ContentTransactionException.DuplicateEntry(
                    "Blob sync job ${job.jobId} has no durable rights grant",
                )
            if (!grant.scope.covers(job.accessScope) || ContentOperation.SYNC_BLOB !in grant.allowedOperations) {
                throw ContentTransactionException.CommitConflict("blob-sync-rights:${job.jobId}")
            }
        }
    }
}
