package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.model.PublicationKey

/**
 * Every table class of [SqlDriverContentTransactionStore], built once over one [ContentSqlOps], plus
 * the deletes that span several tables. Callers hold the store's transaction lock.
 */
internal class ContentTables(sql: ContentSqlOps) : ContentSqlOps by sql {
    val ledger = ContentLedgerTables(sql)
    val attachments = ContentAttachmentTables(sql)
    val publications = ContentPublicationTables(sql)
    val rights = ContentRightsGrantTable(sql)
    val quarantines = ContentQuarantineTable(sql, attachments)
    val blobSyncJobs = ContentBlobSyncJobTable(sql, attachments, rights)
    val replicas = ContentReplicaTables(sql)
    val journal = ContentCommitJournal(sql)

    /**
     * Deletes only the authority represented by Backup v2. Migration aliases, ledgers,
     * quarantines and their auxiliary blobs are intentionally device-local and survive.
     * The caller owns the enclosing SQLite and blob rollback boundaries.
     */
    fun deletePortableGraphRows(replacementCommitId: String) {
        execute("DELETE FROM $TABLE_BLOB_SYNC_JOBS", 0)
        execute("DELETE FROM $TABLE_RIGHTS_GRANTS", 0)
        execute("DELETE FROM $TABLE_ACQUISITION_RIGHTS", 0)
        execute("DELETE FROM $TABLE_UNITS", 0)
        execute("DELETE FROM $TABLE_WORK_LINKS", 0)
        execute("DELETE FROM $TABLE_ACQUISITIONS", 0)
        execute("DELETE FROM $TABLE_PUBLICATIONS", 0)
        execute("DELETE FROM $TABLE_ATTACHMENT_REFS", 0)
        execute("DELETE FROM $TABLE_ATTACHMENTS", 0)
        execute("DELETE FROM $TABLE_OUTBOX", 0)
        execute("DELETE FROM $TABLE_PUBLICATION_REPLICA_CURSORS", 0)
        execute("DELETE FROM $TABLE_BLOB_REMOVAL_INTENTS", 0)
        execute("DELETE FROM $TABLE_COMMITS WHERE commit_id LIKE ?", 1) {
            bindString(0, "$CONTENT_REPLICA_COMMIT_ID_PREFIX%")
        }
        execute("DELETE FROM $TABLE_COMMITS WHERE commit_id = ?", 1) {
            bindString(0, replacementCommitId)
        }
    }

    /** Deletes exactly one publication's local projection; replica cursor/intent rows survive. */
    fun deletePublicationReplicaRows(publicationKey: PublicationKey) {
        // escaped as where they are read (loadPublicationAttachments): what is deleted is what is listed
        val ownerPrefix = escapeLike(publicationKey.value) + "/%"
        execute(
            "DELETE FROM $TABLE_BLOB_SYNC_JOBS WHERE attachment_key IN (" +
                "SELECT attachment_key FROM $TABLE_ATTACHMENTS WHERE owner_scope LIKE ? ESCAPE '\\')",
            1,
        ) { bindString(0, ownerPrefix) }
        execute(
            "DELETE FROM $TABLE_RIGHTS_GRANTS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_ACQUISITION_RIGHTS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_UNITS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_WORK_LINKS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_ACQUISITIONS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_PUBLICATIONS WHERE publication_id = ?",
            1,
        ) { bindString(0, publicationKey.value) }
        execute(
            "DELETE FROM $TABLE_ATTACHMENT_REFS WHERE attachment_key IN (" +
                "SELECT attachment_key FROM $TABLE_ATTACHMENTS WHERE owner_scope LIKE ? ESCAPE '\\')",
            1,
        ) { bindString(0, ownerPrefix) }
        execute(
            "DELETE FROM $TABLE_ATTACHMENTS WHERE owner_scope LIKE ? ESCAPE '\\'",
            1,
        ) { bindString(0, ownerPrefix) }
    }
}
