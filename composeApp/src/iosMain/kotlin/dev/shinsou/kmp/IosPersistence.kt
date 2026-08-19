package dev.shinsou.kmp

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.plugin.PluginJson
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.isSensitivePluginKey
import dev.shinsou.kmp.plugin.migrateLegacySensitivePluginValues
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.errSecItemNotFound
import platform.darwin.OSStatus

/** Durable iOS snapshot storage. Foundation's atomic write replaces the file as one transaction. */
@OptIn(ExperimentalForeignApi::class)
internal object IosSnapshotPersistence {
    private const val FILE_NAME = "shinsou-state.json"

    fun load(): AppSnapshot {
        val encoded = try {
            readUtf8(IosApplicationPaths.file(FILE_NAME)) ?: return AppSnapshot()
        } catch (_: InvalidPersistedUtf8Exception) {
            quarantineCorruptSnapshot()
            return AppSnapshot()
        }
        return try {
            ShinsouRepository.decodeSnapshot(encoded)
        } catch (_: Exception) {
            // Validation and serialization failures indicate an unusable snapshot. Do not treat
            // VM/runtime failures (for example OOM) as corruption and move valid user data away.
            quarantineCorruptSnapshot()
            AppSnapshot()
        }
    }

    fun save(encoded: String) {
        writeUtf8(IosApplicationPaths.file(FILE_NAME), encoded)
    }

    private fun quarantineCorruptSnapshot() {
        quarantineCorruptFile(
            source = IosApplicationPaths.file(FILE_NAME),
            destinationPrefix = "shinsou-state",
        )
    }
}

/**
 * File-backed plugin state with Keychain routing for credentials, cookies, OAuth tokens and secrets.
 * Existing sensitive values in the plain store are migrated to Keychain on first access.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosPluginKeyValueStore : PluginKeyValueStore {
    override suspend fun getString(key: String): String? = IosPluginState.mutex.withLock {
        ensureSensitiveValuesMigratedLocked()
        if (!isSensitivePluginKey(key) || IosPluginState.keychainUnavailable) {
            return@withLock plainValuesLocked()[key]
        }
        try {
            secureValueLocked(key)
        } catch (error: Throwable) {
            if (!markKeychainUnavailable(error)) throw error
            plainValuesLocked()[key]
        }
    }

    override suspend fun putString(key: String, value: String): Unit = IosPluginState.mutex.withLock {
        // A cancelled UI request must not split the Keychain mutation, cache publication and
        // legacy-file cleanup into different observable states.
        withContext(NonCancellable) {
            ensureSensitiveValuesMigratedLocked()
            val current = plainValuesLocked()
            if (isSensitivePluginKey(key)) {
                if (!IosPluginState.keychainUnavailable) {
                    try {
                        writeSecureValueLocked(key, value)
                        if (key in current) persistPlainValuesLocked(current - key)
                        return@withContext
                    } catch (error: Throwable) {
                        if (!markKeychainUnavailable(error)) throw error
                    }
                }
                // See the IosPluginState.keychainUnavailable comment below. This is intentionally
                // a graceful fallback for unsigned simulator/debug builds, not the release path.
                if (current[key] != value) persistPlainValuesLocked(current + (key to value))
            } else {
                if (current[key] != value) persistPlainValuesLocked(current + (key to value))
                if (!IosPluginState.keychainUnavailable) {
                    try {
                        deleteSecureValueLocked(key)
                    } catch (error: Throwable) {
                        if (!markKeychainUnavailable(error)) throw error
                    }
                }
            }
        }
    }

    override suspend fun remove(key: String): Unit = IosPluginState.mutex.withLock {
        withContext(NonCancellable) {
            ensureSensitiveValuesMigratedLocked()
            if (!IosPluginState.keychainUnavailable) {
                try {
                    deleteSecureValueLocked(key)
                } catch (error: Throwable) {
                    if (!markKeychainUnavailable(error)) throw error
                }
            }
            val current = plainValuesLocked()
            if (key in current) persistPlainValuesLocked(current - key)
        }
    }

    /** A present null entry is the process-level sentinel for a confirmed missing Keychain item. */
    private suspend fun secureValueLocked(key: String): String? {
        val cached = IosPluginState.secureValues
        if (key in cached) return cached[key]

        // Publish the result (including a miss) before returning to a possibly cancelled caller.
        return withContext(NonCancellable + Dispatchers.Default) {
            val value = IosKeychain.get(key)
            IosPluginState.secureValues = IosPluginState.secureValues + (key to value)
            value
        }
    }

    private suspend fun writeSecureValueLocked(key: String, value: String) {
        val cached = IosPluginState.secureValues
        if (key in cached && cached[key] == value) return
        onStorageDispatcher { IosKeychain.set(key, value) }
        IosPluginState.secureValues = IosPluginState.secureValues + (key to value)
    }

    private suspend fun deleteSecureValueLocked(key: String) {
        val cached = IosPluginState.secureValues
        if (key in cached && cached[key] == null) return
        onStorageDispatcher { IosKeychain.delete(key) }
        IosPluginState.secureValues = IosPluginState.secureValues + (key to null)
    }

    /**
     * Loads and decodes the plain store once per process. Cache snapshots are immutable and every
     * access is guarded by [IosPluginState.mutex], so dispatcher hops never expose a mutable native
     * collection across workers.
     */
    private suspend fun plainValuesLocked(): Map<String, String> {
        IosPluginState.plainValues?.let { return it }
        val loaded: Map<String, String> = onStorageDispatcher {
            val encoded = try {
                readUtf8(IosPluginState.statePath)
            } catch (_: InvalidPersistedUtf8Exception) {
                quarantineCorruptFile(IosPluginState.statePath, "plugin-state")
                null
            }
            if (encoded == null) {
                emptyMap()
            } else {
                try {
                    PluginJson.decodeFromString(IosPluginState.serializer, encoded)
                } catch (_: Exception) {
                    // Keep the original bytes recoverable. Returning an empty map without moving
                    // them would let the next ordinary preference write erase the only copy.
                    quarantineCorruptFile(IosPluginState.statePath, "plugin-state")
                    emptyMap()
                }
            }
        }.toMap()
        IosPluginState.plainValues = loaded
        return loaded
    }

    /** Writes before publishing the new cache snapshot, preserving the store's atomic semantics. */
    private suspend fun persistPlainValuesLocked(values: Map<String, String>) {
        val snapshot = values.toMap()
        // A cancelled UI operation must not leave a successfully replaced file paired with the
        // previous in-memory snapshot. Publish both sides of the commit in one non-cancellable hop.
        withContext(NonCancellable + Dispatchers.Default) {
            val encoded = PluginJson.encodeToString(IosPluginState.serializer, snapshot)
            writeUtf8(IosPluginState.statePath, encoded)
            IosPluginState.plainValues = snapshot
        }
    }

    /**
     * Sweeps the complete legacy file on the first store operation. The original file is rewritten
     * only after every sensitive Keychain value is present and has been verified by a read-back.
     */
    private suspend fun ensureSensitiveValuesMigratedLocked() {
        if (IosPluginState.sensitiveMigrationChecked) return
        // Migration may write several Keychain entries and then rewrite the plain snapshot. Once
        // it starts, finish that transaction even if the initiating screen leaves composition.
        withContext(NonCancellable) {
            val original = plainValuesLocked()
            if (IosPluginState.keychainUnavailable) {
                IosPluginState.sensitiveMigrationChecked = true
                return@withContext
            }
            val migration = try {
                onStorageDispatcher {
                    val migratedSecureValues = mutableMapOf<String, String?>()
                    val remaining = migrateLegacySensitivePluginValues(
                        plainValues = original,
                        readSecure = { key ->
                            IosKeychain.get(key).also { migratedSecureValues[key] = it }
                        },
                        writeSecure = IosKeychain::set,
                    )
                    remaining to migratedSecureValues.toMap()
                }
            } catch (error: Throwable) {
                if (!markKeychainUnavailable(error)) throw error
                // Do not rewrite the legacy file after a partial migration. Keeping the original
                // values allows the file-backed fallback above to continue serving the app.
                IosPluginState.sensitiveMigrationChecked = true
                return@withContext
            }
            val (remaining, migratedSecureValues) = migration
            if (remaining != original) persistPlainValuesLocked(remaining)
            // Every migration read is now authoritative, including confirmed missing entries.
            IosPluginState.secureValues = IosPluginState.secureValues + migratedSecureValues
            IosPluginState.sensitiveMigrationChecked = true
        }
    }

    private fun markKeychainUnavailable(error: Throwable): Boolean {
        if (error !is IosKeychainException || error.status != ERR_SEC_MISSING_ENTITLEMENT) return false
        IosPluginState.keychainUnavailable = true
        return true
    }
}

/** Shared by every adapter instance so reopening a composition cannot reparse the package store. */
private object IosPluginState {
    val mutex = Mutex()
    val serializer = MapSerializer(String.serializer(), String.serializer())
    val statePath: String by lazy { IosApplicationPaths.file("plugin-state.json") }
    var plainValues: Map<String, String>? = null
    // Null values are deliberate cached misses. containsKey() distinguishes them from not loaded.
    var secureValues: Map<String, String?> = emptyMap()
    var sensitiveMigrationChecked: Boolean = false

    // Simulator/debug builds may not have an application-identifier entitlement. In that
    // environment Security.framework returns errSecMissingEntitlement (-34018) for every
    // Keychain operation. Keep the app usable by retaining sensitive values in the cached
    // file-backed store until a real Keychain is available (release builds still use Keychain).
    var keychainUnavailable: Boolean = false
}

private suspend inline fun <T> onStorageDispatcher(crossinline block: () -> T): T =
    withContext(Dispatchers.Default) { block() }

@OptIn(ExperimentalForeignApi::class)
private object IosApplicationPaths {
    private val directory: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("Application Support directory is unavailable")
        val path = "$support/Shinsou"
        check(
            NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        ) { "Unable to create the Shinsou X application support directory" }
        path
    }

    fun file(name: String): String = "$directory/$name"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readUtf8(path: String): String? {
    val data = NSData.dataWithContentsOfFile(path) ?: run {
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            error("Unable to read persisted data: $path")
        }
        return null
    }
    @Suppress("CAST_NEVER_SUCCEEDS")
    return NSString.create(data, NSUTF8StringEncoding) as? String
        ?: throw InvalidPersistedUtf8Exception(path)
}

private class InvalidPersistedUtf8Exception(path: String) :
    IllegalStateException("Persisted data is not valid UTF-8: $path")

@OptIn(ExperimentalForeignApi::class)
private fun quarantineCorruptFile(source: String, destinationPrefix: String) {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(source)) return
    val timestamp = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
    check(
        manager.moveItemAtPath(
            source,
            toPath = IosApplicationPaths.file("$destinationPrefix.corrupt-$timestamp.json"),
            error = null,
        ),
    ) { "Unable to quarantine corrupt persisted data: $source" }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeUtf8(path: String, value: String) {
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
        ?: error("Unable to encode persisted Shinsou X data")
    check(data.writeToFile(path, atomically = true)) { "Unable to persist Shinsou X data" }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object IosKeychain {
    private const val SERVICE = "dev.aluo.shinsoux.plugin-state"
    private const val ERR_SEC_SUCCESS: OSStatus = 0

    fun set(account: String, value: String) {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Unable to encode Keychain value")
        val serviceRef = CFBridgingRetain(NSString.create(string = SERVICE))
        val accountRef = CFBridgingRetain(NSString.create(string = account))
        val dataRef = CFBridgingRetain(data)
        try {
            memScoped {
                val identity = dictionary(
                    keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount),
                    values = arrayOf(kSecClassGenericPassword, serviceRef, accountRef),
                )
                val update = dictionary(
                    keys = arrayOf(kSecValueData, kSecAttrAccessible),
                    values = arrayOf(dataRef, kSecAttrAccessibleAfterFirstUnlock),
                )
                val updateStatus = try {
                    SecItemUpdate(identity, update)
                } finally {
                    CFBridgingRelease(update)
                    CFBridgingRelease(identity)
                }
                if (updateStatus == ERR_SEC_SUCCESS) {
                    return@memScoped
                }
                requireStatus(updateStatus, "update", allowNotFound = true)

                val insert = dictionary(
                    keys = arrayOf(
                        kSecClass,
                        kSecAttrService,
                        kSecAttrAccount,
                        kSecValueData,
                        kSecAttrAccessible,
                    ),
                    values = arrayOf(
                        kSecClassGenericPassword,
                        serviceRef,
                        accountRef,
                        dataRef,
                        kSecAttrAccessibleAfterFirstUnlock,
                    ),
                )
                val status = try {
                    SecItemAdd(insert, null)
                } finally {
                    CFBridgingRelease(insert)
                }
                requireStatus(status, "write")
            }
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
            CFBridgingRelease(dataRef)
        }
    }

    fun get(account: String): String? {
        val serviceRef = CFBridgingRetain(NSString.create(string = SERVICE))
        val accountRef = CFBridgingRetain(NSString.create(string = account))
        try {
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val query = dictionary(
                    keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount, kSecReturnData, kSecMatchLimit),
                    values = arrayOf(kSecClassGenericPassword, serviceRef, accountRef, kCFBooleanTrue, kSecMatchLimitOne),
                )
                val status = try {
                    SecItemCopyMatching(query, result.ptr)
                } finally {
                    CFBridgingRelease(query)
                }
                if (status == errSecItemNotFound) return@memScoped null
                requireStatus(status, "read")
                val resultValue = result.value ?: error("Keychain read returned no value")
                val data = CFBridgingRelease(resultValue) as? NSData
                    ?: error("Keychain read returned an invalid value")
                @Suppress("CAST_NEVER_SUCCEEDS")
                NSString.create(data, NSUTF8StringEncoding) as? String
                    ?: error("Keychain value is not valid UTF-8")
            }
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
        }
    }

    fun delete(account: String) {
        val serviceRef = CFBridgingRetain(NSString.create(string = SERVICE))
        val accountRef = CFBridgingRetain(NSString.create(string = account))
        try {
            memScoped {
                val query = dictionary(
                    keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount),
                    values = arrayOf(kSecClassGenericPassword, serviceRef, accountRef),
                )
                val status = try {
                    SecItemDelete(query)
                } finally {
                    CFBridgingRelease(query)
                }
                requireStatus(status, "delete", allowNotFound = true)
            }
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
        }
    }

    private fun kotlinx.cinterop.MemScope.dictionary(
        keys: Array<*>,
        values: Array<*>,
    ): platform.CoreFoundation.CFDictionaryRef? {
        val keyPointers = allocArray<COpaquePointerVar>(keys.size)
        val valuePointers = allocArray<COpaquePointerVar>(values.size)
        for (index in keys.indices) {
            keyPointers[index] = keys[index] as kotlinx.cinterop.COpaquePointer?
            valuePointers[index] = values[index] as kotlinx.cinterop.COpaquePointer?
        }
        return CFDictionaryCreate(
            kCFAllocatorDefault,
            keyPointers,
            valuePointers,
            keys.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
    }

    private fun requireStatus(status: OSStatus, operation: String, allowNotFound: Boolean = false) {
        if (status == ERR_SEC_SUCCESS || (allowNotFound && status == errSecItemNotFound)) return
        throw IosKeychainException(status, operation)
    }
}

private class IosKeychainException(
    val status: OSStatus,
    operation: String,
) : IllegalStateException("Keychain $operation failed with status $status")

private const val ERR_SEC_MISSING_ENTITLEMENT: OSStatus = -34018
