package dev.shinsou.kmp.content.table

import dev.shinsou.kmp.content.schema.TABLE_ALIASES
import dev.shinsou.kmp.content.schema.TABLE_METADATA
import dev.shinsou.kmp.content.schema.TABLE_MIGRATIONS
import dev.shinsou.kmp.content.sql.SqliteUpsert
import dev.shinsou.kmp.content.transaction.ContentAliasMutation
import dev.shinsou.kmp.content.transaction.ContentMetadataMutation
import dev.shinsou.kmp.content.transaction.ContentMigrationLedgerLookup
import dev.shinsou.kmp.content.transaction.ContentMigrationLedgerMutation
import dev.shinsou.kmp.content.transaction.ContentMigrationLookupStatus
import dev.shinsou.kmp.content.transaction.ContentTransactionFailurePoint

/** Metadata, alias and migration-ledger rows. */
internal class ContentLedgerTables(sql: ContentSqlOps) : ContentSqlOps by sql {
    // SqliteUpsert: Android 10's SQLite has no `ON CONFLICT ... DO UPDATE`.
    private val metadataUpsert = SqliteUpsert(TABLE_METADATA, listOf("key", "value"), keyCount = 1)
    private val aliasUpsert = SqliteUpsert(TABLE_ALIASES, listOf("alias", "target"), keyCount = 1)
    private val migrationInsert = SqliteUpsert(
        TABLE_MIGRATIONS,
        listOf("migration_key", "namespace", "source_digest", "result_fingerprint", "commit_id", "mutation_json"),
        keyCount = 1,
        updated = emptyList(),
    )

    /** Reads and verifies one migration row, including every denormalized SQL column. */
    fun findMigration(migrationKey: String): ContentMigrationLedgerMutation? {
        val row = queryRows(
            TABLE_MIGRATIONS,
            "namespace, source_digest, result_fingerprint, commit_id, mutation_json WHERE migration_key = ?",
            migrationKey,
        ).singleOrNull() ?: return null
        val existing = decodeJson(
            ContentMigrationLedgerMutation.serializer(),
            row.string("mutation_json"),
        )
        check(existing.migrationKey == migrationKey) { "Migration key/body mismatch" }
        check(existing.namespace == row.string("namespace")) { "Migration namespace mismatch" }
        check(existing.sourceDigestSha256 == row.string("source_digest")) {
            "Migration source digest mismatch"
        }
        check(existing.resultFingerprintSha256 == row.string("result_fingerprint")) {
            "Migration result fingerprint mismatch"
        }
        check(existing.commitId == row.string("commit_id")) { "Migration commit id mismatch" }
        return existing
    }

    fun loadAllMigrations(): List<ContentMigrationLedgerMutation> {
        val migrations = ArrayList<ContentMigrationLedgerMutation>()
        val keys = hashSetOf<String>()
        queryRows(
            TABLE_MIGRATIONS,
            "migration_key, namespace, source_digest, result_fingerprint, commit_id, mutation_json ORDER BY migration_key",
        ).forEach { row ->
            val key = row.string("migration_key")
            val mutation = decodeJson(
                ContentMigrationLedgerMutation.serializer(),
                row.string("mutation_json"),
            )
            check(mutation.migrationKey == key) { "Migration key/body mismatch" }
            check(mutation.namespace == row.string("namespace")) { "Migration namespace mismatch" }
            check(mutation.sourceDigestSha256 == row.string("source_digest")) {
                "Migration source digest mismatch"
            }
            check(mutation.resultFingerprintSha256 == row.string("result_fingerprint")) {
                "Migration result fingerprint mismatch"
            }
            check(mutation.commitId == row.string("commit_id")) { "Migration commit id mismatch" }
            check(keys.add(key)) { "Duplicate migration key" }
            migrations += mutation
        }
        return migrations
    }

    fun lookupMigrationLedgerLocked(
        namespace: String,
        sourceDigestSha256: String,
        resultFingerprintSha256: String,
    ): ContentMigrationLedgerLookup {
        val requested = ContentMigrationLedgerMutation(
            namespace = namespace,
            sourceDigestSha256 = sourceDigestSha256,
            resultFingerprintSha256 = resultFingerprintSha256,
        )
        val existing = findMigration(requested.migrationKey)
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

    fun writeMetadata(mutations: List<ContentMetadataMutation>) {
        mutations.forEach { mutation ->
            metadataUpsert.run(listOf(mutation.key, mutation.value), ::execute)
            maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_METADATA_WRITE)
        }
    }

    fun writeAliases(mutations: List<ContentAliasMutation>) {
        mutations.forEach { mutation ->
            aliasUpsert.run(listOf(mutation.alias, mutation.target), ::execute)
            maybeFail(ContentTransactionFailurePoint.AFTER_ALIAS_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_ALIAS_WRITE)
        }
    }

    fun writeMigrations(mutations: List<ContentMigrationLedgerMutation>) {
        mutations.forEach { mutation ->
            migrationInsert.run(
                listOf(
                    mutation.migrationKey,
                    mutation.namespace,
                    mutation.sourceDigestSha256,
                    mutation.resultFingerprintSha256,
                    mutation.commitId,
                    encodeJson(ContentMigrationLedgerMutation.serializer(), mutation),
                ),
                ::execute,
            )
            maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_MIGRATION_WRITE)
        }
    }
}
