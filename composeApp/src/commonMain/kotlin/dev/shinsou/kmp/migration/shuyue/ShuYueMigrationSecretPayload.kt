package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.plugin.PluginKeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One protected value is deliberately used for the complete migration result. Platform stores can
 * therefore replace the selected credential and cookie set without exposing an intermediate state.
 */
internal class KeyValueShuYueMigrationSecretStore(
    private val keyValueStore: PluginKeyValueStore,
) : ShuYueMigrationSecretStore {
    override val protectedAtRest: Boolean = true

    override suspend fun replaceAtomically(batch: ShuYueSecretWriteBatch) {
        keyValueStore.putString(SHUYUE_MIGRATION_SECRET_BATCH_KEY, encodeShuYueSecretBatch(batch))
    }
}

@Serializable
private data class StoredShuYueSecretBatchV1(
    val schemaVersion: Int = SHUYUE_MIGRATION_SECRET_SCHEMA_VERSION,
    val credentials: List<StoredShuYueCredentialV1>,
    val cookies: List<StoredShuYueCookieV1>,
)

@Serializable
private data class StoredShuYueCredentialV1(
    val sourceId: String,
    val username: String,
    val password: String,
    val updatedAtEpochMillis: Long,
)

@Serializable
private data class StoredShuYueCookieV1(
    val sourceId: String,
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtEpochMillis: Long?,
)

internal fun encodeShuYueSecretBatch(batch: ShuYueSecretWriteBatch): String =
    ShuYueSecretPayloadJson.encodeToString(
        StoredShuYueSecretBatchV1(
            credentials = batch.credentials
                .sortedBy(ShuYueSecretCredential::sourceId)
                .map {
                    StoredShuYueCredentialV1(
                        sourceId = it.sourceId,
                        username = it.username,
                        password = it.password,
                        updatedAtEpochMillis = it.updatedAtEpochMillis,
                    )
                },
            cookies = batch.cookies
                .sortedWith(
                    compareBy<ShuYueSecretCookie>(ShuYueSecretCookie::sourceId)
                        .thenBy { it.domain.lowercase() }
                        .thenBy(ShuYueSecretCookie::path)
                        .thenBy(ShuYueSecretCookie::name),
                )
                .map {
                    StoredShuYueCookieV1(
                        sourceId = it.sourceId,
                        name = it.name,
                        value = it.value,
                        domain = it.domain,
                        path = it.path,
                        expiresAtEpochMillis = it.expiresAtEpochMillis,
                    )
                },
        ),
    )

internal fun encodeShuYueSecretBatchBytes(batch: ShuYueSecretWriteBatch): ByteArray =
    encodeShuYueSecretBatch(batch).encodeToByteArray()

private val ShuYueSecretPayloadJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

internal const val SHUYUE_MIGRATION_SECRET_BATCH_KEY: String =
    "migration.shuyue.secret.batch.v1"
private const val SHUYUE_MIGRATION_SECRET_SCHEMA_VERSION: Int = 1
