package dev.shinsou.kmp.content

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.domain.model.LegacyAliasKey
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.PortableAliasBinding
import dev.shinsou.kmp.domain.model.PortableAliasException
import dev.shinsou.kmp.domain.model.PortableAliasRequest
import dev.shinsou.kmp.domain.model.PortableAliasResolver

/**
 * Persistent legacy identity seam backed by the unified content alias table.
 *
 * The encoded alias key is also accepted by [ContentAliasMutation], allowing an importer to bind
 * the exact same identity inside its publication/manifest/migration commit. This resolver exists
 * for read-only compatibility projection and restart lookup; it never touches AppSnapshot.
 */
public class SqlDriverPortableAliasResolver(
    private val driver: SqlDriver,
) : PortableAliasResolver {
    private val transactions = object : TransacterImpl(driver) {}
    private val mutex = SynchronousLock()

    init {
        ContentTransactionSchema.create(driver).value
    }

    override fun resolveOrBindAll(requests: List<PortableAliasRequest>): List<PortableAliasBinding> = locked {
        val uniqueAliases = HashSet<String>()
        requests.forEach { request ->
            if (!uniqueAliases.add(aliasStorageKey(request.namespace, request.alias))) {
                throw PortableAliasException.DuplicateRequest(request.alias)
            }
        }
        transactions.transactionWithResult(false) {
            val resolved = requests.map { request ->
                val storageKey = aliasStorageKey(request.namespace, request.alias)
                val existingUuid = selectTarget(storageKey)
                if (existingUuid != null && existingUuid != request.derivedUuid) {
                    throw PortableAliasException.ChangedBinding(request.alias)
                }
                val existingAlias = selectLegacyAliasForUuid(request.derivedUuid)
                if (existingAlias != null && existingAlias != storageKey) {
                    throw PortableAliasException.UuidCollision(request.derivedUuid)
                }
                PortableAliasBinding(
                    namespace = request.namespace,
                    alias = request.alias,
                    portableUuid = existingUuid ?: request.derivedUuid,
                )
            }
            resolved.forEach { binding ->
                driver.execute(
                    identifier = null,
                    sql = "INSERT OR IGNORE INTO content_transaction_aliases(alias, target) VALUES (?, ?)",
                    parameters = 2,
                ) {
                    bindString(0, aliasStorageKey(binding.namespace, binding.alias))
                    bindString(1, binding.portableUuid)
                }.value
            }
            resolved
        }
    }

    override fun resolve(
        namespace: MigrationNamespaceId,
        alias: LegacyAliasKey,
    ): PortableAliasBinding? = locked {
        selectTarget(aliasStorageKey(namespace, alias))?.let { PortableAliasBinding(namespace, alias, it) }
    }

    private fun selectTarget(storageKey: String): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT target FROM content_transaction_aliases WHERE alias = ?",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 1,
        binders = { bindString(0, storageKey) },
    ).value

    private fun selectLegacyAliasForUuid(portableUuid: String): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT alias FROM content_transaction_aliases WHERE target = ? AND alias LIKE 'legacy-v1:%'",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 1,
        binders = { bindString(0, portableUuid) },
    ).value

    private inline fun <T> locked(block: () -> T): T {
        return mutex.withLock(block)
    }
}

/** Stable bridge for importers that persist mapper-produced bindings in [ContentCommitBatch]. */
public fun PortableAliasBinding.asContentAliasMutation(): ContentAliasMutation =
    ContentAliasMutation(aliasStorageKey(namespace, alias), portableUuid)

private fun aliasStorageKey(namespace: MigrationNamespaceId, alias: LegacyAliasKey): String {
    namespace.validate()
    alias.validate()
    return "legacy-v1:${namespace.value}:${alias.kind.name.lowercase()}:${alias.canonicalId}"
}
