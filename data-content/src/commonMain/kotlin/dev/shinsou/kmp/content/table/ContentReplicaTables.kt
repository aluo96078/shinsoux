package dev.shinsou.kmp.content.table

import dev.shinsou.kmp.content.schema.TABLE_BLOB_REMOVAL_INTENTS
import dev.shinsou.kmp.content.schema.TABLE_PUBLICATION_REPLICA_CURSORS
import dev.shinsou.kmp.content.sql.SqliteUpsert
import dev.shinsou.kmp.content.transaction.ContentBlobRemovalIntent
import dev.shinsou.kmp.content.transaction.ContentPublicationReplicaCursor
import dev.shinsou.kmp.content.transaction.ContentTransactionException
import dev.shinsou.kmp.domain.model.PublicationKey

/** Publication replica cursors and the blob removal intents they leave behind. */
internal class ContentReplicaTables(sql: ContentSqlOps) : ContentSqlOps by sql {
    private val replicaCursorUpsert = SqliteUpsert(
        TABLE_PUBLICATION_REPLICA_CURSORS,
        listOf(
            "publication_id", "instance_id", "workspace_id", "through_workspace_seq",
            "present", "graph_fingerprint", "cursor_json",
        ),
        keyCount = 1,
    )

    fun findPublicationReplicaCursor(
        publicationKey: PublicationKey,
    ): ContentPublicationReplicaCursor? {
        val row = queryRows(
            TABLE_PUBLICATION_REPLICA_CURSORS,
            "instance_id, workspace_id, through_workspace_seq, present, graph_fingerprint, " +
                "cursor_json WHERE publication_id = ?",
            publicationKey.value,
        ).singleOrNull() ?: return null
        val cursor = decodeJson(
            ContentPublicationReplicaCursor.serializer(),
            row.string("cursor_json"),
        )
        check(cursor.publicationKey == publicationKey) { "Replica cursor publication key/body mismatch" }
        check(cursor.instanceId == row.string("instance_id")) { "Replica cursor instance mismatch" }
        check(cursor.workspaceId == row.string("workspace_id")) { "Replica cursor workspace mismatch" }
        check(cursor.throughWorkspaceSeq == row.long("through_workspace_seq")) {
            "Replica cursor sequence mismatch"
        }
        check(cursor.present == (row.long("present") == 1L)) {
            "Replica cursor presence mismatch"
        }
        check(cursor.graphFingerprintSha256 == row.string("graph_fingerprint")) {
            "Replica cursor fingerprint mismatch"
        }
        return cursor
    }

    fun findBlobRemovalIntent(intentId: String): ContentBlobRemovalIntent? {
        val row = queryRows(
            TABLE_BLOB_REMOVAL_INTENTS,
            "publication_id, through_workspace_seq, reason, intent_json " +
                "WHERE intent_id = ?",
            intentId,
        ).singleOrNull() ?: return null
        val intent = decodeJson(ContentBlobRemovalIntent.serializer(), row.string("intent_json"))
        check(intent.intentId == intentId) { "Blob removal intent key/body mismatch" }
        check(intent.publicationKey.value == row.string("publication_id")) {
            "Blob removal intent publication mismatch"
        }
        check(intent.sourceCursor.throughWorkspaceSeq == row.long("through_workspace_seq")) {
            "Blob removal intent sequence mismatch"
        }
        check(intent.reason.name == row.string("reason")) { "Blob removal intent reason mismatch" }
        return intent
    }

    fun writePublicationReplicaCursor(cursor: ContentPublicationReplicaCursor) {
        // SqliteUpsert: Android 10's SQLite has no `ON CONFLICT ... DO UPDATE`.
        replicaCursorUpsert.run(
            listOf(
                cursor.publicationKey.value,
                cursor.instanceId,
                cursor.workspaceId,
                cursor.throughWorkspaceSeq,
                if (cursor.present) 1L else 0L,
                cursor.graphFingerprintSha256,
                encodeJson(ContentPublicationReplicaCursor.serializer(), cursor),
            ),
            ::execute,
        )
    }

    fun writeBlobRemovalIntent(intent: ContentBlobRemovalIntent) {
        findBlobRemovalIntent(intent.intentId)?.let { existing ->
            if (existing != intent) throw ContentTransactionException.CommitConflict(intent.intentId)
            return
        }
        execute(
            """
                INSERT INTO $TABLE_BLOB_REMOVAL_INTENTS(
                  intent_id, publication_id, through_workspace_seq, reason, intent_json
                ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            5,
        ) {
            bindString(0, intent.intentId)
            bindString(1, intent.publicationKey.value)
            bindLong(2, intent.sourceCursor.throughWorkspaceSeq)
            bindString(3, intent.reason.name)
            bindString(4, encodeJson(ContentBlobRemovalIntent.serializer(), intent))
        }
    }
}
