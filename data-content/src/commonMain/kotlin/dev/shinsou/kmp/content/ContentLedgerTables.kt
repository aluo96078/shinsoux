package dev.shinsou.kmp.content

/** Metadata, alias and migration-ledger rows. */
internal class ContentLedgerTables(sql: ContentSqlOps) : ContentSqlOps by sql {
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
            execute(
                "INSERT INTO $TABLE_METADATA(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                2,
            ) {
                bindString(0, mutation.key)
                bindString(1, mutation.value)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_METADATA_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_METADATA_WRITE)
        }
    }

    fun writeAliases(mutations: List<ContentAliasMutation>) {
        mutations.forEach { mutation ->
            execute(
                "INSERT INTO $TABLE_ALIASES(alias, target) VALUES (?, ?) ON CONFLICT(alias) DO UPDATE SET target = excluded.target",
                2,
            ) {
                bindString(0, mutation.alias)
                bindString(1, mutation.target)
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_ALIAS_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_ALIAS_WRITE)
        }
    }

    fun writeMigrations(mutations: List<ContentMigrationLedgerMutation>) {
        mutations.forEach { mutation ->
            execute(
                """
                    INSERT INTO $TABLE_MIGRATIONS(
                      migration_key, namespace, source_digest, result_fingerprint, commit_id, mutation_json
                    ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent() + " ON CONFLICT(migration_key) DO NOTHING",
                6,
            ) {
                bindString(0, mutation.migrationKey)
                bindString(1, mutation.namespace)
                bindString(2, mutation.sourceDigestSha256)
                bindString(3, mutation.resultFingerprintSha256)
                bindString(4, mutation.commitId)
                bindString(5, encodeJson(ContentMigrationLedgerMutation.serializer(), mutation))
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_MIGRATION_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_MIGRATION_WRITE)
        }
    }
}
