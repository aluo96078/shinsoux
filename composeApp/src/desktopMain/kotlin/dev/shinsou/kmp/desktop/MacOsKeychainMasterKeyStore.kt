package dev.shinsou.kmp.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Uses Security.framework directly so key material never appears in process arguments, shell
 * history, command output, or an application-support file beside the ciphertext.
 */
internal class MacOsKeychainMasterKeyStore(
    private val keychain: MacOsKeychainApi = JnaMacOsKeychainApi(),
    private val service: String = DEFAULT_SERVICE,
    private val account: String = DEFAULT_ACCOUNT,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val executor: ExecutorService = newKeychainExecutor(),
) : DesktopMasterKeyStore {
    private val pendingReadLock = Any()
    private var pendingRead: Future<ByteArray?>? = null

    init {
        require(service.isNotBlank() && account.isNotBlank()) { "Keychain identity cannot be blank." }
        require(operationTimeoutMillis > 0) { "Keychain timeout must be positive." }
    }

    override fun read(): ByteArray? {
        val future = synchronized(pendingReadLock) {
            pendingRead?.takeUnless { it.isCancelled }
                ?: executor.submit<ByteArray?> { keychain.readPassword(service, account) }
                    .also { pendingRead = it }
        }
        return try {
            awaitKeychainCall("read", future)
        } finally {
            if (future.isDone) {
                synchronized(pendingReadLock) {
                    if (pendingRead === future) pendingRead = null
                }
            }
        }
    }

    override fun write(value: ByteArray) {
        require(value.isNotEmpty()) { "Refusing to store an empty desktop master key." }
        val future = executor.submit<Unit> {
            val isolatedValue = value.copyOf()
            try {
                keychain.upsertPassword(service, account, isolatedValue)
            } finally {
                isolatedValue.fill(0)
            }
        }
        awaitKeychainCall("write", future)
    }

    private fun <T> awaitKeychainCall(operation: String, future: Future<T>): T {
        return try {
            future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            // Keychain Services may be synchronously waiting for SecurityAgent. Interrupting that
            // native call is unreliable and discards a successful authorization that arrives just
            // after the UI timeout. Keep the single read alive so a retry can consume its result.
            throw IllegalStateException(
                "macOS Keychain $operation timed out. Complete any pending Keychain access prompt, then try again.",
                timeout,
            )
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("macOS Keychain $operation was interrupted.", interrupted)
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw IllegalStateException("macOS Keychain $operation failed.", cause)
        }
    }

    companion object {
        internal const val DEFAULT_SERVICE = "dev.aluo.shinsoux.desktop.plugin-secrets"
        internal const val DEFAULT_ACCOUNT = "master-key-v1"
        internal const val DEFAULT_OPERATION_TIMEOUT_MILLIS: Long = 8_000L

        private fun newKeychainExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "shinsou-macos-keychain").apply { isDaemon = true }
        }
    }
}

internal interface MacOsKeychainApi {
    fun readPassword(service: String, account: String): ByteArray?

    fun upsertPassword(service: String, account: String, value: ByteArray)
}

private class JnaMacOsKeychainApi : MacOsKeychainApi {
    private val security: SecurityFramework
    private val coreFoundation: CoreFoundationFramework

    init {
        check(DesktopPlatform.fromOsName(System.getProperty("os.name")) == DesktopPlatform.MAC_OS) {
            "The macOS Keychain is unavailable on this operating system."
        }
        security = Native.load(SECURITY_FRAMEWORK, SecurityFramework::class.java)
        coreFoundation = Native.load(CORE_FOUNDATION_FRAMEWORK, CoreFoundationFramework::class.java)
    }

    override fun readPassword(service: String, account: String): ByteArray? {
        val serviceBytes = service.encodeToByteArray()
        val accountBytes = account.encodeToByteArray()
        val passwordLength = IntByReference()
        val passwordData = PointerByReference()
        val itemReference = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            passwordLength,
            passwordData,
            itemReference,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        checkStatus(status, "read")

        val dataPointer = passwordData.value
        val itemPointer = itemReference.value
        return try {
            check(dataPointer != null && itemPointer != null) { "macOS Keychain returned an incomplete item." }
            val length = passwordLength.value
            check(length in 1..MAX_KEYCHAIN_VALUE_BYTES) { "macOS Keychain returned an invalid value length." }
            dataPointer.getByteArray(0, length)
        } finally {
            if (dataPointer != null) security.SecKeychainItemFreeContent(null, dataPointer)
            if (itemPointer != null) coreFoundation.CFRelease(itemPointer)
        }
    }

    override fun upsertPassword(service: String, account: String, value: ByteArray) {
        require(value.size <= MAX_KEYCHAIN_VALUE_BYTES) { "Desktop master key is unexpectedly large." }
        val serviceBytes = service.encodeToByteArray()
        val accountBytes = account.encodeToByteArray()
        val itemReference = PointerByReference()
        val findStatus = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            null,
            null,
            itemReference,
        )
        val nativeValue = value.copyOf()
        try {
            when (findStatus) {
                ERR_SEC_SUCCESS -> {
                    val itemPointer = itemReference.value
                        ?: error("macOS Keychain returned no item reference for an existing key.")
                    try {
                        checkStatus(
                            security.SecKeychainItemModifyAttributesAndData(
                                itemPointer,
                                null,
                                nativeValue.size,
                                nativeValue,
                            ),
                            "update",
                        )
                    } finally {
                        coreFoundation.CFRelease(itemPointer)
                    }
                }

                ERR_SEC_ITEM_NOT_FOUND -> checkStatus(
                    security.SecKeychainAddGenericPassword(
                        null,
                        serviceBytes.size,
                        serviceBytes,
                        accountBytes.size,
                        accountBytes,
                        nativeValue.size,
                        nativeValue,
                        null,
                    ),
                    "create",
                )

                else -> checkStatus(findStatus, "locate")
            }
        } finally {
            nativeValue.fill(0)
        }
    }

    private fun checkStatus(status: Int, operation: String) {
        check(status == ERR_SEC_SUCCESS) { "macOS Keychain $operation failed with status $status." }
    }

    private interface SecurityFramework : Library {
        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference?,
            passwordData: PointerByReference?,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer,
            attrList: Pointer?,
            length: Int,
            data: ByteArray,
        ): Int

        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer): Int
    }

    private interface CoreFoundationFramework : Library {
        fun CFRelease(value: Pointer)
    }

    private companion object {
        const val SECURITY_FRAMEWORK = "/System/Library/Frameworks/Security.framework/Security"
        const val CORE_FOUNDATION_FRAMEWORK = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        const val ERR_SEC_SUCCESS = 0
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
        const val MAX_KEYCHAIN_VALUE_BYTES = 4 * 1024
    }
}
