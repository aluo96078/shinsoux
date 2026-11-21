package dev.shinsou.kmp.content

import dev.shinsou.kmp.rights.ContentOperation

/** Blob sync job rows; each needs its manifest attachment and a grant that allows syncing. */
internal class ContentBlobSyncJobTable(
    sql: ContentSqlOps,
    private val attachments: ContentAttachmentTables,
    private val rights: ContentRightsGrantTable,
) : ContentSqlOps by sql {
    fun findBlobSyncJob(jobId: String): ContentBlobSyncJobMutation? {
        val row = queryRows(
            TABLE_BLOB_SYNC_JOBS,
            "attachment_key, blob_id, owner_scope, manifest_id, content_revision, grant_id, " +
                "mutation_json WHERE job_id = ?",
            jobId,
        ).singleOrNull() ?: return null
        val mutation = decodeJson(ContentBlobSyncJobMutation.serializer(), row.string("mutation_json"))
        check(mutation.jobId == jobId) { "Blob sync job key/body mismatch" }
        check(mutation.blob.blobId == row.string("blob_id")) { "Blob sync job blob mismatch" }
        check(mutation.owner.scopeKey == row.string("owner_scope")) { "Blob sync job owner mismatch" }
        check(mutation.manifestId == row.string("manifest_id")) { "Blob sync job manifest mismatch" }
        check(mutation.contentRevision == row.longOrNull("content_revision")) { "Blob sync job revision mismatch" }
        check(mutation.grantReference.value == row.string("grant_id")) { "Blob sync job grant mismatch" }
        val attachment = requireNotNull(attachments.findAttachment(row.string("attachment_key"))) {
            "Blob sync job manifest attachment is missing"
        }
        check(
            attachment.owner == mutation.owner &&
                attachment.manifestId == mutation.manifestId &&
                attachment.contentRevision == mutation.contentRevision &&
                mutation.blob in attachment.blobs,
        ) { "Blob sync job attachment mismatch" }
        val grant = requireNotNull(rights.findRightsGrant(mutation.grantReference)) {
            "Blob sync job rights grant is missing"
        }
        check(grant.scope.covers(mutation.accessScope) && ContentOperation.SYNC_BLOB in grant.allowedOperations) {
            "Blob sync job is no longer authorized by its durable grant"
        }
        return mutation.copy(blob = mutation.blob.copy())
    }

    fun writeBlobSyncJobs(mutations: List<ContentBlobSyncJobMutation>) {
        mutations.forEach { mutation ->
            if (findBlobSyncJob(mutation.jobId) != null) return@forEach
            execute(
                """
                    INSERT INTO $TABLE_BLOB_SYNC_JOBS(
                      job_id, attachment_key, blob_id, owner_scope, manifest_id,
                      content_revision, grant_id, mutation_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                8,
            ) {
                bindString(0, mutation.jobId)
                bindString(1, blobSyncAttachmentKey(mutation))
                bindString(2, mutation.blob.blobId)
                bindString(3, mutation.owner.scopeKey)
                bindString(4, mutation.manifestId)
                bindLong(5, mutation.contentRevision)
                bindString(6, mutation.grantReference.value)
                bindString(7, encodeJson(ContentBlobSyncJobMutation.serializer(), mutation))
            }
        }
    }
}

internal fun blobSyncAttachmentKey(job: ContentBlobSyncJobMutation): String =
    "${job.owner.scopeKey}/${job.manifestId}/${job.contentRevision}"
