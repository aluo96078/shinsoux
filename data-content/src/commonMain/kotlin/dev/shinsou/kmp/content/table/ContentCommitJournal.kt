package dev.shinsou.kmp.content.table

import dev.shinsou.kmp.content.blob.BlobPublishReceipt
import dev.shinsou.kmp.content.schema.TABLE_COMMITS
import dev.shinsou.kmp.content.transaction.ContentCommitResult
import dev.shinsou.kmp.domain.content.BlobRef
import kotlinx.serialization.Serializable

/** The commit journal: one row per committed batch with its fingerprint and receipt descriptors. */
internal class ContentCommitJournal(sql: ContentSqlOps) : ContentSqlOps by sql {
    fun findCommit(commitId: String): PersistedCommit? {
        val row = queryRows(
            TABLE_COMMITS,
            "fingerprint, result_json, receipt_descriptors_json WHERE commit_id = ?",
            commitId,
        ).singleOrNull() ?: return null
        val result = PersistedCommitResult.toRuntime(
            decodeJson(PersistedCommitResult.serializer(), row.string("result_json")),
        )
        check(result.commitId == commitId) { "Commit journal result/id mismatch" }
        val descriptors = decodeJson(
            PersistedReceiptDescriptorList.serializer(),
            row.string("receipt_descriptors_json"),
        ).receipts.onEach(::validateReceiptDescriptor)
        check(descriptors.map(PersistedReceiptDescriptor::commitToken).distinct().size == descriptors.size) {
            "Duplicate receipt descriptor token in commit journal"
        }
        check(descriptors.map { it.reference.blobId }.distinct().size == descriptors.size) {
            "Duplicate receipt descriptor blob id in commit journal"
        }
        val journalFingerprint = row.string("fingerprint")
        check(SHA256_HEX.matches(journalFingerprint)) { "Commit journal fingerprint is invalid" }
        return PersistedCommit(journalFingerprint, result, descriptors)
    }

    fun insertCommit(
        commitId: String,
        fingerprint: String,
        result: ContentCommitResult,
        receipts: List<BlobPublishReceipt>,
    ) {
        val encodedResult = encodeJson(PersistedCommitResult.serializer(), PersistedCommitResult.from(result))
        val encodedReceipts = encodeJson(
            PersistedReceiptDescriptorList.serializer(),
            PersistedReceiptDescriptorList(receipts.map(PersistedReceiptDescriptor::from)),
        )
        execute(
            "INSERT INTO $TABLE_COMMITS(commit_id, fingerprint, result_json, receipt_descriptors_json) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, commitId)
            bindString(1, fingerprint)
            bindString(2, encodedResult)
            bindString(3, encodedReceipts)
        }
    }

    fun validateReceiptDescriptor(descriptor: PersistedReceiptDescriptor) {
        requireSafeSqlKey(descriptor.storeInstanceId, "Receipt store instance id")
        requireSafeSqlKey(descriptor.commitToken, "Receipt commit token")
        descriptor.reference.validate()
        require(descriptor.incarnation > 0) { "Receipt incarnation must be positive" }
        require(descriptor.generation >= 0) { "Receipt generation must be non-negative" }
        require(descriptor.publishedAtEpochMillis >= 0) {
            "Receipt publication timestamp must be non-negative"
        }
    }
}

internal data class PersistedCommit(
    val fingerprint: String,
    val result: ContentCommitResult,
    val receiptDescriptors: List<PersistedReceiptDescriptor>,
)

@Serializable
private data class PersistedCommitResult(
    val commitId: String,
    val replayed: Boolean,
    val deferred: Boolean,
    val committedGeneration: Long?,
    val attachedOwnerIds: List<String>,
    val outboxDraftIds: List<String>,
    val migrationKeys: List<String>,
    val publicationIds: List<String> = emptyList(),
    val auxiliaryAttachmentIds: List<String> = emptyList(),
    val quarantineIds: List<String> = emptyList(),
    val rightsGrantIds: List<String> = emptyList(),
    val blobSyncJobIds: List<String> = emptyList(),
    val blobRemovalIntentIds: List<String> = emptyList(),
) {
    companion object {
        fun from(result: ContentCommitResult): PersistedCommitResult = PersistedCommitResult(
            commitId = result.commitId,
            replayed = false,
            deferred = result.deferred,
            committedGeneration = result.committedGeneration,
            attachedOwnerIds = result.attachedOwnerIds,
            outboxDraftIds = result.outboxDraftIds,
            migrationKeys = result.migrationKeys,
            publicationIds = result.publicationIds,
            auxiliaryAttachmentIds = result.auxiliaryAttachmentIds,
            quarantineIds = result.quarantineIds,
            rightsGrantIds = result.rightsGrantIds,
            blobSyncJobIds = result.blobSyncJobIds,
            blobRemovalIntentIds = result.blobRemovalIntentIds,
        )

        fun toRuntime(result: PersistedCommitResult): ContentCommitResult = ContentCommitResult(
            commitId = result.commitId,
            replayed = result.replayed,
            deferred = result.deferred,
            committedGeneration = result.committedGeneration,
            attachedOwnerIds = result.attachedOwnerIds,
            outboxDraftIds = result.outboxDraftIds,
            migrationKeys = result.migrationKeys,
            publicationIds = result.publicationIds,
            auxiliaryAttachmentIds = result.auxiliaryAttachmentIds,
            quarantineIds = result.quarantineIds,
            rightsGrantIds = result.rightsGrantIds,
            blobSyncJobIds = result.blobSyncJobIds,
            blobRemovalIntentIds = result.blobRemovalIntentIds,
        )
    }
}

@Serializable
internal data class PersistedReceiptDescriptor(
    val storeInstanceId: String,
    val commitToken: String,
    val reference: BlobRef,
    val incarnation: Long,
    val generation: Long,
    val publishedAtEpochMillis: Long,
) {
    companion object {
        fun from(receipt: BlobPublishReceipt): PersistedReceiptDescriptor = PersistedReceiptDescriptor(
            storeInstanceId = receipt.storeInstanceId,
            commitToken = receipt.commitToken,
            reference = receipt.reference,
            incarnation = receipt.incarnation,
            generation = receipt.generation,
            publishedAtEpochMillis = receipt.publishedAtEpochMillis,
        )
    }
}

@Serializable
private data class PersistedReceiptDescriptorList(val receipts: List<PersistedReceiptDescriptor>)

private val SHA256_HEX = Regex("[0-9a-f]{64}")
