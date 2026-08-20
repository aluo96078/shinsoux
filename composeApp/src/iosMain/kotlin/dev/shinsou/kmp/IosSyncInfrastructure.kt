package dev.shinsou.kmp

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.shinsou.kmp.sync.persistence.SqlDriverSyncStatePersistence
import dev.shinsou.kmp.sync.persistence.createIosFileDeviceDirectoryPinStore
import dev.shinsou.kmp.sync.persistence.SyncInstallationIdentity
import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.persistence.SyncLocalSchema
import dev.shinsou.kmp.sync.persistence.SyncMetadataCorruptException
import dev.shinsou.kmp.sync.persistence.SyncMetadataJson
import dev.shinsou.kmp.sync.persistence.SyncMetadataUnavailableException
import dev.shinsou.kmp.sync.persistence.newSyncInstallationIdentity
import dev.shinsou.kmp.sync.persistence.storageIdentifier
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncPlatformInfrastructure
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
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
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.numberWithBool
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIDevice
import platform.darwin.OSStatus

/** iOS persistence bundle. Sync secrets never pass through the permissive plugin store. */
@OptIn(ExperimentalForeignApi::class)
internal class IosSyncInfrastructure(
    private val directory: String = IosSyncPaths.directory,
    keychain: IosSyncKeychainApi = SecurityFrameworkIosSyncKeychainApi(),
) : SyncPlatformInfrastructure {
    override val installationStore: SyncInstallationStore = IosFileSyncInstallationStore(
        path = "$directory/installation.json",
    )
    override val sessionStore: SyncSessionStore = IosFileSyncSessionStore(
        path = "$directory/session.json",
    )
    override val secretStore: SyncSecretStore = IosKeychainSyncSecretStore(keychain)
    override val deviceDirectoryPinStore: DeviceDirectoryPinStore =
        createIosFileDeviceDirectoryPinStore("$directory/device-directory-pins.json")
    override val platform: String = "ios"
    override val deviceDisplayName: String = UIDevice.currentDevice.name.trim().ifBlank { "iOS device" }

    private val driverLock = NSLock()
    private var openPersistence: SqlDriverSyncStatePersistence? = null

    override fun statePersistence(): SqlDriverSyncStatePersistence = withDriverLock {
        openPersistence ?: createStatePersistence().also { openPersistence = it }
    }

    override fun close() = withDriverLock {
        openPersistence?.close()
        openPersistence = null
    }

    private fun createStatePersistence() = SqlDriverSyncStatePersistence(
        NativeSqliteDriver(
            schema = SyncLocalSchema,
            name = DATABASE_NAME,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig = configuration.extendedConfig.copy(basePath = directory),
                )
            },
        ),
    )

    private inline fun <T> withDriverLock(block: () -> T): T {
        driverLock.lock()
        return try {
            block()
        } finally {
            driverLock.unlock()
        }
    }

    private companion object {
        const val DATABASE_NAME = "shinsou-sync-v2.db"
    }
}

/** Atomic JSON persistence for non-secret Cloudflare session metadata. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileSyncSessionStore(
    private val path: String,
) : SyncSessionStore {
    private val mutex = Mutex()

    override suspend fun load(): SyncSession? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val encoded = readSyncUtf8IfPresent(path) ?: return@withContext null
            try {
                SyncMetadataJson.decodeFromString(SyncSession.serializer(), encoded)
            } catch (error: SerializationException) {
                throw SyncMetadataCorruptException("Sync session metadata is malformed", error)
            } catch (error: IllegalArgumentException) {
                throw SyncMetadataCorruptException("Sync session metadata failed validation", error)
            }
        }
    }

    override suspend fun save(session: SyncSession): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            writeSyncUtf8Atomically(
                path,
                SyncMetadataJson.encodeToString(SyncSession.serializer(), session),
            )
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            ensureSyncRegularOrMissing(path)
            val manager = NSFileManager.defaultManager
            if (manager.fileExistsAtPath(path) && !manager.removeItemAtPath(path, error = null)) {
                throw SyncMetadataUnavailableException("Unable to clear sync session metadata")
            }
        }
    }
}

/** A missing file is first install; unreadable or malformed identity files never rotate IDs. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileSyncInstallationStore(
    private val path: String,
    private val randomUuid: () -> String = { NSUUID().UUIDString.lowercase() },
) : SyncInstallationStore {
    private val mutex = Mutex()

    override suspend fun loadOrCreate(): SyncInstallationIdentity = mutex.withLock {
        withContext(Dispatchers.Default) {
            readIdentity()?.let { return@withContext it }
            val created = newSyncInstallationIdentity(randomUuid)
            writeSyncUtf8Atomically(
                path,
                SyncMetadataJson.encodeToString(SyncInstallationIdentity.serializer(), created),
            )
            readIdentity() ?: throw SyncMetadataUnavailableException(
                "Sync installation identity disappeared immediately after creation",
            )
        }
    }

    private fun readIdentity(): SyncInstallationIdentity? {
        val encoded = readSyncUtf8IfPresent(path) ?: return null
        return try {
            SyncMetadataJson.decodeFromString(SyncInstallationIdentity.serializer(), encoded)
        } catch (error: SerializationException) {
            throw SyncMetadataCorruptException("Sync installation identity is malformed", error)
        } catch (error: IllegalArgumentException) {
            throw SyncMetadataCorruptException("Sync installation identity failed validation", error)
        }
    }
}

/** Typed Keychain read result. The value owns [bytes], which the caller wipes after copying. */
internal sealed interface IosSyncKeychainReadResult {
    data class Value(val bytes: ByteArray) : IosSyncKeychainReadResult
    data object Missing : IosSyncKeychainReadResult
    data class Unavailable(val status: OSStatus) : IosSyncKeychainReadResult
    data class Corrupt(val status: OSStatus) : IosSyncKeychainReadResult
}

internal sealed interface IosSyncKeychainMutationResult {
    data object Success : IosSyncKeychainMutationResult
    data class Unavailable(val status: OSStatus) : IosSyncKeychainMutationResult
    data class Corrupt(val status: OSStatus) : IosSyncKeychainMutationResult
}

/** Narrow injectable boundary keeps Security.framework status handling deterministic in tests. */
internal interface IosSyncKeychainApi {
    fun read(account: String): IosSyncKeychainReadResult
    fun write(account: String, bytes: ByteArray): IosSyncKeychainMutationResult
    fun delete(account: String): IosSyncKeychainMutationResult
}

/** Strict secret store: missing, unavailable and corrupt remain distinct and there is no fallback. */
internal class IosKeychainSyncSecretStore(
    private val keychain: IosSyncKeychainApi,
) : SyncSecretStore {
    private val mutex = Mutex()

    override suspend fun read(key: SyncSecretKey): SyncSecretReadResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            when (val result = keychain.read(key.storageIdentifier())) {
                is IosSyncKeychainReadResult.Value -> try {
                    if (result.bytes.isEmpty()) {
                        SyncSecretReadResult.Corrupt("Keychain secret payload is empty")
                    } else {
                        SyncSecretReadResult.Available(SecretMaterial(result.bytes.asList()))
                    }
                } finally {
                    result.bytes.fill(0)
                }

                IosSyncKeychainReadResult.Missing -> SyncSecretReadResult.Missing
                is IosSyncKeychainReadResult.Unavailable -> SyncSecretReadResult.Unavailable(
                    "iOS Keychain read failed with status ${result.status}",
                )

                is IosSyncKeychainReadResult.Corrupt -> SyncSecretReadResult.Corrupt(
                    "iOS Keychain data failed with status ${result.status}",
                )
            }
        }
    }

    override suspend fun write(key: SyncSecretKey, material: SecretMaterial): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            var result: IosSyncKeychainMutationResult? = null
            material.useBytes { bytes -> result = keychain.write(key.storageIdentifier(), bytes) }
            requireMutationSucceeded(requireNotNull(result), "write")
        }
    }

    override suspend fun delete(key: SyncSecretKey): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            requireMutationSucceeded(keychain.delete(key.storageIdentifier()), "delete")
        }
    }

    private fun requireMutationSucceeded(result: IosSyncKeychainMutationResult, operation: String) {
        when (result) {
            IosSyncKeychainMutationResult.Success -> Unit
            is IosSyncKeychainMutationResult.Unavailable -> throw SyncMetadataUnavailableException(
                "iOS Keychain $operation failed with status ${result.status}",
            )

            is IosSyncKeychainMutationResult.Corrupt -> throw SyncMetadataCorruptException(
                "iOS Keychain $operation failed with status ${result.status}",
            )
        }
    }
}

/** Dedicated service, raw bytes and a device-only accessibility class. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class SecurityFrameworkIosSyncKeychainApi(
    private val service: String = SYNC_KEYCHAIN_SERVICE,
) : IosSyncKeychainApi {
    override fun read(account: String): IosSyncKeychainReadResult = withIdentity(account) { serviceRef, accountRef ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val query = dictionary(
                keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount, kSecReturnData, kSecMatchLimit),
                values = arrayOf(
                    kSecClassGenericPassword,
                    serviceRef,
                    accountRef,
                    kCFBooleanTrue,
                    kSecMatchLimitOne,
                ),
            )
            val status = try {
                SecItemCopyMatching(query, result.ptr)
            } finally {
                CFBridgingRelease(query)
            }
            if (status != ERR_SEC_SUCCESS) return@memScoped classifyReadFailure(status)
            val resultValue = result.value
                ?: return@memScoped IosSyncKeychainReadResult.Corrupt(ERR_SEC_DECODE)
            val data = CFBridgingRelease(resultValue) as? NSData
                ?: return@memScoped IosSyncKeychainReadResult.Corrupt(ERR_SEC_DECODE)
            IosSyncKeychainReadResult.Value(data.toSyncByteArray())
        }
    }

    override fun write(account: String, bytes: ByteArray): IosSyncKeychainMutationResult =
        withIdentity(account) { serviceRef, accountRef ->
            val dataRef = CFBridgingRetain(bytes.toSyncNSData())
            try {
                val updateStatus = memScoped {
                    val identity = dictionary(
                        keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount),
                        values = arrayOf(kSecClassGenericPassword, serviceRef, accountRef),
                    )
                    val update = dictionary(
                        keys = arrayOf(kSecValueData, kSecAttrAccessible),
                        values = arrayOf(dataRef, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
                    )
                    try {
                        SecItemUpdate(identity, update)
                    } finally {
                        CFBridgingRelease(update)
                        CFBridgingRelease(identity)
                    }
                }
                if (updateStatus == ERR_SEC_SUCCESS) return@withIdentity IosSyncKeychainMutationResult.Success
                if (updateStatus != errSecItemNotFound) return@withIdentity classifyMutationFailure(updateStatus)

                val insertStatus = memScoped {
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
                            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                        ),
                    )
                    try {
                        SecItemAdd(insert, null)
                    } finally {
                        CFBridgingRelease(insert)
                    }
                }
                classifyMutationFailure(insertStatus)
            } finally {
                CFBridgingRelease(dataRef)
            }
        }

    override fun delete(account: String): IosSyncKeychainMutationResult = withIdentity(account) { serviceRef, accountRef ->
        val status = memScoped {
            val query = dictionary(
                keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount),
                values = arrayOf(kSecClassGenericPassword, serviceRef, accountRef),
            )
            try {
                SecItemDelete(query)
            } finally {
                CFBridgingRelease(query)
            }
        }
        if (status == errSecItemNotFound) IosSyncKeychainMutationResult.Success
        else classifyMutationFailure(status)
    }

    private inline fun <T> withIdentity(
        account: String,
        block: (serviceRef: Any?, accountRef: Any?) -> T,
    ): T {
        val serviceRef = CFBridgingRetain(NSString.create(string = service))
        val accountRef = CFBridgingRetain(NSString.create(string = account))
        return try {
            block(serviceRef, accountRef)
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
        }
    }

    private fun classifyReadFailure(status: OSStatus): IosSyncKeychainReadResult = when (status) {
        errSecItemNotFound -> IosSyncKeychainReadResult.Missing
        ERR_SEC_DECODE -> IosSyncKeychainReadResult.Corrupt(status)
        else -> IosSyncKeychainReadResult.Unavailable(status)
    }

    private fun classifyMutationFailure(status: OSStatus): IosSyncKeychainMutationResult = when (status) {
        ERR_SEC_SUCCESS -> IosSyncKeychainMutationResult.Success
        ERR_SEC_DECODE -> IosSyncKeychainMutationResult.Corrupt(status)
        else -> IosSyncKeychainMutationResult.Unavailable(status)
    }
}

@OptIn(ExperimentalForeignApi::class)
private object IosSyncPaths {
    val directory: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: throw SyncMetadataUnavailableException(
            "Application Support directory is unavailable",
        )
        val path = "$support/Shinsou/SyncV2"
        val manager = NSFileManager.defaultManager
        if (!manager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        ) {
            throw SyncMetadataUnavailableException("Unable to create the iOS sync directory")
        }
        val excluded = NSURL.fileURLWithPath(path, isDirectory = true).setResourceValue(
            NSNumber.numberWithBool(true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        )
        if (!excluded) {
            throw SyncMetadataUnavailableException("Unable to exclude iOS sync state from backup")
        }
        path
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readSyncUtf8IfPresent(path: String): String? {
    ensureSyncRegularOrMissing(path)
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) return null
    val attributes = manager.attributesOfItemAtPath(path, error = null)
        ?: throw SyncMetadataUnavailableException("Unable to inspect persisted sync metadata")
    val size = (attributes[NSFileSize] as? NSNumber)?.unsignedLongLongValue
        ?: throw SyncMetadataCorruptException("Persisted sync metadata has no valid size")
    if (size > MAX_SYNC_METADATA_BYTES.toULong()) {
        throw SyncMetadataCorruptException("Persisted sync metadata exceeds its size limit")
    }
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw SyncMetadataUnavailableException("Unable to read persisted sync metadata")
    @Suppress("CAST_NEVER_SUCCEEDS")
    return NSString.create(data, NSUTF8StringEncoding) as? String
        ?: throw SyncMetadataCorruptException("Persisted sync metadata is not valid UTF-8")
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeSyncUtf8Atomically(path: String, value: String) {
    ensureSyncRegularOrMissing(path)
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isBlank()) throw SyncMetadataUnavailableException("Sync metadata path has no parent")
    val manager = NSFileManager.defaultManager
    if (!manager.createDirectoryAtPath(parent, true, null, null)) {
        throw SyncMetadataUnavailableException("Unable to create the sync metadata directory")
    }
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
        ?: throw SyncMetadataUnavailableException("Unable to encode persisted sync metadata")
    if (data.length > MAX_SYNC_METADATA_BYTES.toULong()) {
        throw SyncMetadataCorruptException("Persisted sync metadata exceeds its size limit")
    }
    if (!data.writeToFile(path, atomically = true)) {
        throw SyncMetadataUnavailableException("Unable to atomically persist sync metadata")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ensureSyncRegularOrMissing(path: String) {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) return
    val attributes = manager.attributesOfItemAtPath(path, error = null)
        ?: throw SyncMetadataUnavailableException("Unable to inspect persisted sync metadata")
    if (attributes[NSFileType] != NSFileTypeRegular) {
        throw SyncMetadataCorruptException("Refusing to use a non-regular sync metadata file")
    }
}

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toSyncNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toSyncByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    return bytes?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
}

private const val SYNC_KEYCHAIN_SERVICE = "dev.aluo.shinsoux.sync-secrets.v1"
private const val MAX_SYNC_METADATA_BYTES = 256L * 1024L
private const val ERR_SEC_SUCCESS: OSStatus = 0
private const val ERR_SEC_DECODE: OSStatus = -26275
