package dev.shinsou.kmp.content

/** Plugin quarantine rows; each is backed by an auxiliary attachment. */
internal class ContentQuarantineTable(
    sql: ContentSqlOps,
    private val attachments: ContentAttachmentTables,
) : ContentSqlOps by sql {
    fun findQuarantine(quarantineId: String): ContentQuarantineMutation? {
        val row = queryRows(
            TABLE_QUARANTINES,
            "auxiliary_attachment_key, package_id, version, origin, ordinal, script_blob_id, " +
                "mutation_json WHERE quarantine_id = ?",
            quarantineId,
        ).singleOrNull() ?: return null
        val mutation = decodeJson(ContentQuarantineMutation.serializer(), row.string("mutation_json"))
        check(mutation.quarantineId == quarantineId) { "Quarantine key/body mismatch" }
        check(mutation.packageId == row.string("package_id")) { "Quarantine package id mismatch" }
        check(mutation.version == row.string("version")) { "Quarantine version mismatch" }
        check(mutation.origin == row.string("origin")) { "Quarantine origin mismatch" }
        check(mutation.ordinal.toLong() == row.long("ordinal")) { "Quarantine ordinal mismatch" }
        check(mutation.scriptBlob.blobId == row.string("script_blob_id")) { "Quarantine script blob mismatch" }
        val attachmentKey = row.string("auxiliary_attachment_key")
        val attachment = requireNotNull(attachments.findAuxiliaryAttachment(attachmentKey)) {
            "Quarantine auxiliary attachment is missing"
        }
        check(attachment.ownerId == mutation.auxiliaryOwnerId &&
            attachment.purpose == AuxiliaryBlobPurpose.PLUGIN_QUARANTINE &&
            attachment.blobs == listOf(mutation.scriptBlob)
        ) {
            "Quarantine auxiliary attachment mismatch"
        }
        return mutation.deepImmutableSnapshot()
    }

    fun writeQuarantines(mutations: List<ContentQuarantineMutation>) {
        mutations.forEach { mutation ->
            if (findQuarantine(mutation.quarantineId) != null) return@forEach
            val auxiliaryAttachmentKey = AuxiliaryBlobAttachment(
                ownerId = mutation.auxiliaryOwnerId,
                purpose = AuxiliaryBlobPurpose.PLUGIN_QUARANTINE,
                blobs = listOf(mutation.scriptBlob),
            ).attachmentKey
            execute(
                """
                    INSERT INTO $TABLE_QUARANTINES(
                      quarantine_id, auxiliary_attachment_key, package_id, version, origin,
                      ordinal, script_blob_id, mutation_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                8,
            ) {
                bindString(0, mutation.quarantineId)
                bindString(1, auxiliaryAttachmentKey)
                bindString(2, mutation.packageId)
                bindString(3, mutation.version)
                bindString(4, mutation.origin)
                bindLong(5, mutation.ordinal.toLong())
                bindString(6, mutation.scriptBlob.blobId)
                bindString(7, encodeJson(ContentQuarantineMutation.serializer(), mutation))
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_QUARANTINE_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_QUARANTINE_WRITE)
        }
    }
}
