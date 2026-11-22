package dev.shinsou.kmp.content.table

import dev.shinsou.kmp.content.blob.BlobAttachment
import dev.shinsou.kmp.content.blob.ManifestAttachment
import dev.shinsou.kmp.content.schema.TABLE_ATTACHMENTS
import dev.shinsou.kmp.content.schema.TABLE_ATTACHMENT_REFS
import dev.shinsou.kmp.content.schema.TABLE_AUXILIARY_ATTACHMENTS
import dev.shinsou.kmp.content.schema.TABLE_AUXILIARY_ATTACHMENT_REFS
import dev.shinsou.kmp.content.transaction.AuxiliaryBlobAttachment
import dev.shinsou.kmp.content.transaction.ContentTransactionFailurePoint
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.deepImmutableSnapshot
import dev.shinsou.kmp.domain.model.PublicationKey

/** Manifest and auxiliary attachment rows with their normalized blob-ref ledgers. */
internal class ContentAttachmentTables(sql: ContentSqlOps) : ContentSqlOps by sql {
    fun findAttachment(key: String): ManifestAttachment? {
        if (queryRows(TABLE_ATTACHMENTS, "attachment_key WHERE attachment_key = ?", key).isEmpty()) {
            return null
        }
        return loadAttachment(key)
    }

    fun findAuxiliaryAttachment(key: String): AuxiliaryBlobAttachment? {
        if (queryRows(
                TABLE_AUXILIARY_ATTACHMENTS,
                "attachment_key WHERE attachment_key = ?",
                key,
            ).isEmpty()
        ) {
            return null
        }
        return loadAuxiliaryAttachment(key)
    }

    /** Loads and verifies the manifest row and its normalized blob-ref ledger as one unit. */
    fun loadAttachment(key: String): ManifestAttachment {
        val row = queryRows(
            TABLE_ATTACHMENTS,
            "owner_scope, manifest_id, content_revision, manifest_json WHERE attachment_key = ?",
            key,
        ).singleOrNull() ?: error("Missing manifest attachment row $key")
        val ownerScope = row.string("owner_scope")
        val manifestId = row.string("manifest_id")
        val revision = row.long("content_revision")
        val attachment = decodeJson(ManifestAttachment.serializer(), row.string("manifest_json"))
        check(attachment.attachmentKey == key) { "Attachment key/body mismatch" }
        check(attachment.owner.scopeKey == ownerScope) { "Attachment owner scope mismatch" }
        check(attachment.manifestId == manifestId) { "Attachment manifest id mismatch" }
        check(attachment.contentRevision == revision) { "Attachment revision mismatch" }

        val persistedRefs = queryRows(
            TABLE_ATTACHMENT_REFS,
            "blob_id, reference_json WHERE attachment_key = ? ORDER BY blob_id",
            key,
        ).map { refRow ->
            val blobId = refRow.string("blob_id")
            val reference = decodeJson(BlobRef.serializer(), refRow.string("reference_json"))
            check(reference.blobId == blobId) { "Attachment blob-ref key/body mismatch" }
            blobId to reference
        }.toMap()
        val expectedRefs = attachment.blobs.associateBy(BlobRef::blobId)
        check(persistedRefs.size == expectedRefs.size && persistedRefs == expectedRefs) {
            "Attachment blob-ref ledger mismatch"
        }
        return ManifestAttachment(attachment.owner, attachment.manifest.deepImmutableSnapshot())
    }

    /** Loads and verifies an auxiliary row and its normalized blob-ref ledger as one unit. */
    fun loadAuxiliaryAttachment(key: String): AuxiliaryBlobAttachment {
        val row = queryRows(
            TABLE_AUXILIARY_ATTACHMENTS,
            "owner_id, purpose, attachment_json WHERE attachment_key = ?",
            key,
        ).singleOrNull() ?: error("Missing auxiliary attachment row $key")
        val attachment = decodeJson(AuxiliaryBlobAttachment.serializer(), row.string("attachment_json"))
        check(attachment.attachmentKey == key) { "Auxiliary attachment key/body mismatch" }
        check(attachment.ownerId == row.string("owner_id")) { "Auxiliary attachment owner mismatch" }
        check(attachment.purpose.name == row.string("purpose")) { "Auxiliary attachment purpose mismatch" }

        val persistedRefs = queryRows(
            TABLE_AUXILIARY_ATTACHMENT_REFS,
            "blob_id, reference_json WHERE attachment_key = ? ORDER BY blob_id",
            key,
        ).map { refRow ->
            val blobId = refRow.string("blob_id")
            val reference = decodeJson(BlobRef.serializer(), refRow.string("reference_json"))
            check(reference.blobId == blobId) { "Auxiliary blob-ref key/body mismatch" }
            blobId to reference
        }.toMap()
        val expectedRefs = attachment.blobs.associateBy(BlobRef::blobId)
        check(persistedRefs.size == expectedRefs.size && persistedRefs == expectedRefs) {
            "Auxiliary blob-ref ledger mismatch"
        }
        return attachment.deepImmutableSnapshot()
    }

    fun loadAllAttachments(): List<ManifestAttachment> =
        queryRows(TABLE_ATTACHMENTS, "attachment_key ORDER BY attachment_key")
            .map { row -> loadAttachment(row.string("attachment_key")) }

    fun loadAllAuxiliaryAttachments(): List<AuxiliaryBlobAttachment> =
        queryRows(TABLE_AUXILIARY_ATTACHMENTS, "attachment_key ORDER BY attachment_key")
            .map { row -> loadAuxiliaryAttachment(row.string("attachment_key")) }

    fun loadPublicationAttachments(publicationKey: PublicationKey): List<ManifestAttachment> {
        publicationKey.validate()
        // `_` and `%` in the key are literal: `a_b` must not match the attachments of `axb`.
        val ownerPrefix = escapeLike(publicationKey.value) + "/%"
        return queryRows(
            TABLE_ATTACHMENTS,
            "attachment_key WHERE owner_scope LIKE ? ESCAPE '\\' ORDER BY attachment_key",
            ownerPrefix,
        ).map { row -> loadAttachment(row.string("attachment_key")) }
    }

    fun writeAttachments(attachments: List<BlobAttachment>) {
        attachments.forEach { attachment ->
            val encodedManifest = encodeJson(
                ManifestAttachment.serializer(),
                ManifestAttachment(attachment.owner, attachment.manifest.deepImmutableSnapshot()),
            )
            require(encodedManifest.length <= MAX_SQL_PAYLOAD_LENGTH) { "Manifest payload is too large" }
            execute(
                """
                    INSERT INTO $TABLE_ATTACHMENTS(
                      attachment_key, owner_scope, manifest_id, content_revision, manifest_json
                    ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                5,
            ) {
                bindString(0, attachment.attachmentKey)
                bindString(1, attachment.owner.scopeKey)
                bindString(2, attachment.manifestId)
                bindLong(3, attachment.contentRevision)
                bindString(4, encodedManifest)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
            attachment.blobs.forEach { reference ->
                execute(
                    "INSERT INTO $TABLE_ATTACHMENT_REFS(attachment_key, blob_id, reference_json) VALUES (?, ?, ?)",
                    3,
                ) {
                    bindString(0, attachment.attachmentKey)
                    bindString(1, reference.blobId)
                    bindString(2, encodeJson(BlobRef.serializer(), reference))
                }
                maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
            }
        }
    }

    fun writeAuxiliaryAttachments(attachments: List<AuxiliaryBlobAttachment>) {
        attachments.forEach { attachment ->
            val encoded = encodeJson(AuxiliaryBlobAttachment.serializer(), attachment)
            execute(
                """
                    INSERT INTO $TABLE_AUXILIARY_ATTACHMENTS(
                      attachment_key, owner_id, purpose, attachment_json
                    ) VALUES (?, ?, ?, ?)
                """.trimIndent(),
                4,
            ) {
                bindString(0, attachment.attachmentKey)
                bindString(1, attachment.ownerId)
                bindString(2, attachment.purpose.name)
                bindString(3, encoded)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE)
            attachment.blobs.forEach { reference ->
                execute(
                    "INSERT INTO $TABLE_AUXILIARY_ATTACHMENT_REFS(" +
                        "attachment_key, blob_id, reference_json) VALUES (?, ?, ?)",
                    3,
                ) {
                    bindString(0, attachment.attachmentKey)
                    bindString(1, reference.blobId)
                    bindString(2, encodeJson(BlobRef.serializer(), reference))
                }
                maybeFail(ContentTransactionFailurePoint.AFTER_EACH_BLOB_ATTACHMENT_WRITE)
            }
        }
    }
}
