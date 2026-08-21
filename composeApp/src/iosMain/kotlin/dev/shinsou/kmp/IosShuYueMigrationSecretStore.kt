package dev.shinsou.kmp

import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.migration.shuyue.ShuYueSecretWriteBatch
import dev.shinsou.kmp.migration.shuyue.encodeShuYueSecretBatchBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Strict iOS implementation. It never uses the plugin store's unsigned-simulator plaintext
 * fallback: a missing entitlement or any other Keychain failure rejects the entire secret import.
 */
internal class IosShuYueMigrationSecretStore(
    private val keychain: IosSyncKeychainApi = SecurityFrameworkIosSyncKeychainApi(
        service = SHUYUE_MIGRATION_KEYCHAIN_SERVICE,
    ),
) : ShuYueMigrationSecretStore {
    override val protectedAtRest: Boolean = true
    private val mutex = Mutex()

    override suspend fun replaceAtomically(batch: ShuYueSecretWriteBatch): Unit = mutex.withLock {
        withContext(NonCancellable + Dispatchers.Default) {
            val encoded = encodeShuYueSecretBatchBytes(batch)
            try {
                when (val result = keychain.write(SHUYUE_MIGRATION_KEYCHAIN_ACCOUNT, encoded)) {
                    IosSyncKeychainMutationResult.Success -> Unit
                    is IosSyncKeychainMutationResult.Unavailable -> error(
                        "iOS Keychain ShuYue migration write failed with status ${result.status}",
                    )
                    is IosSyncKeychainMutationResult.Corrupt -> error(
                        "iOS Keychain ShuYue migration write failed with status ${result.status}",
                    )
                }
            } finally {
                encoded.fill(0)
            }
        }
    }
}

private const val SHUYUE_MIGRATION_KEYCHAIN_SERVICE =
    "dev.aluo.shinsoux.shuyue-migration-secrets.v1"
private const val SHUYUE_MIGRATION_KEYCHAIN_ACCOUNT = "selected-secret-batch"
