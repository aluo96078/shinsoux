package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.rights.RightsGrant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The journal fingerprint of [batch]: equal batches (and only those) replay one commit. */
internal fun <D : Any> contentCommitFingerprint(
    batch: ContentCommitBatch<D>,
    authoritativeSyncMode: ContentSyncMode,
    codec: Json,
    outboxAdapter: ContentOutboxPersistenceAdapter<D>,
): String {
    val writer = SqlCanonicalWriter()
    writer.string(batch.commitId)
    writer.string(authoritativeSyncMode.name)
    writer.string(batch.unrepresentableDraftPolicy.name)
    writer.string(batch.semantics.name)
    writer.list(batch.receipts.sortedBy(BlobPublishReceipt::commitToken)) { receipt ->
        string(receipt.storeInstanceId)
        string(receipt.commitToken)
        blobRef(receipt.reference)
        long(receipt.incarnation)
        long(receipt.generation)
        long(receipt.publishedAtEpochMillis)
    }
    writer.list(batch.attachments.sortedBy(ManifestAttachment::attachmentKey)) { attachment ->
        string(attachment.owner.publicationKey.value)
        string(attachment.owner.acquisitionId)
        string(attachment.owner.unitKey.value)
        bytes(canonicalJsonBytes(codec.encodeToJsonElement(ContentManifest.serializer(), attachment.manifest)))
    }
    writer.list(batch.metadata.sortedBy(ContentMetadataMutation::key)) { string(it.key); string(it.value) }
    writer.list(batch.aliases.sortedBy(ContentAliasMutation::alias)) { string(it.alias); string(it.target) }
    writer.list(batch.migrations.sortedBy(ContentMigrationLedgerMutation::migrationKey)) { mutation ->
        string(mutation.namespace)
        string(mutation.sourceDigestSha256)
        string(mutation.resultFingerprintSha256)
    }
    writer.list(batch.publications.sortedBy { it.publicationKey.value }) { mutation ->
        bytes(canonicalJsonBytes(codec.encodeToJsonElement(Publication.serializer(), mutation.publication)))
    }
    writer.list(batch.auxiliaryAttachments.sortedBy(AuxiliaryBlobAttachment::attachmentKey)) { attachment ->
        string(attachment.ownerId)
        string(attachment.purpose.name)
        list(attachment.blobs.sortedBy(BlobRef::blobId)) { blobRef(it) }
    }
    writer.list(batch.quarantines.sortedBy(ContentQuarantineMutation::quarantineId)) { mutation ->
        bytes(
            canonicalJsonBytes(
                codec.encodeToJsonElement(ContentQuarantineMutation.serializer(), mutation),
            ),
        )
    }
    writer.list(batch.rightsGrants.sortedBy { it.grantReference.value }) { mutation ->
        bytes(canonicalJsonBytes(codec.encodeToJsonElement(RightsGrant.serializer(), mutation.grant)))
    }
    writer.list(batch.blobSyncJobs.sortedBy(ContentBlobSyncJobMutation::jobId)) { mutation ->
        bytes(
            canonicalJsonBytes(
                codec.encodeToJsonElement(ContentBlobSyncJobMutation.serializer(), mutation),
            ),
        )
    }
    batch.replicaReplacement?.let { replacement ->
        writer.bytes(
            canonicalJsonBytes(
                codec.encodeToJsonElement(
                    ContentPublicationReplicaReplacement.serializer(),
                    replacement,
                ),
            ),
        )
    } ?: writer.bytes(ByteArray(0))
    writer.list(batch.outbox.sortedBy(outboxAdapter::id)) { draft ->
        string(outboxAdapter.id(draft))
        bytes(outboxAdapter.fingerprint(draft))
    }
    return dev.shinsou.kmp.crypto.Sha256.hex(writer.toByteArray())
}

private class SqlCanonicalWriter {
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
        string(reference.blobId)
        long(reference.schemaVersion.toLong())
        string(reference.digestAlgorithm)
        string(reference.plaintextDigest)
        long(reference.byteSize)
        string(reference.mediaType)
    }

    fun <T> list(values: List<T>, write: SqlCanonicalWriter.(T) -> Unit) {
        long(values.size.toLong())
        values.forEach { write(it) }
    }

    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private fun canonicalJsonBytes(element: JsonElement): ByteArray = SqlCanonicalWriter().also { writer ->
    fun write(value: JsonElement) {
        when (value) {
            JsonNull -> writer.string("null")
            is JsonPrimitive -> {
                writer.string(if (value.isString) "string" else "primitive")
                writer.string(value.content)
            }
            is JsonArray -> {
                writer.string("array")
                writer.long(value.size.toLong())
                value.forEach(::write)
            }
            is JsonObject -> {
                writer.string("object")
                writer.long(value.size.toLong())
                value.keys.sorted().forEach { key ->
                    writer.string(key)
                    write(requireNotNull(value[key]))
                }
            }
        }
    }
    write(element)
}.toByteArray()
