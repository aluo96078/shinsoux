package dev.shinsou.kmp

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.content.PlatformContentDatabaseSchema
import dev.shinsou.kmp.content.PlatformContentInfrastructure
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
import platform.darwin.OSStatus

/**
 * iOS content storage: one SQLite database plus a blob directory, both excluded from device
 * backup. The directory and database name predate the removal of sync and are kept so existing
 * libraries open unchanged.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosContentInfrastructure(
    private val directory: String = IosContentPaths.directory,
) : PlatformContentInfrastructure {
    private val driverLock = NSLock()
    private var openDriver: SqlDriver? = null

    override fun contentDriver(): SqlDriver = withDriverLock { databaseDriver() }

    override fun contentBlobDirectory(): String = "$directory/content-blobs"

    override fun close() = withDriverLock {
        openDriver?.close()
        openDriver = null
    }

    private fun databaseDriver(): SqlDriver = openDriver ?: NativeSqliteDriver(
            schema = PlatformContentDatabaseSchema,
            name = DATABASE_NAME,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig = configuration.extendedConfig.copy(basePath = directory),
                )
            },
        ).also { openDriver = it }

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
private object IosContentPaths {
    val directory: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: throw IllegalStateException(
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
            throw IllegalStateException("Unable to create the iOS content directory")
        }
        val excluded = NSURL.fileURLWithPath(path, isDirectory = true).setResourceValue(
            NSNumber.numberWithBool(true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        )
        if (!excluded) {
            throw IllegalStateException("Unable to exclude iOS content storage from backup")
        }
        path
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
private const val ERR_SEC_SUCCESS: OSStatus = 0
private const val ERR_SEC_DECODE: OSStatus = -26275
