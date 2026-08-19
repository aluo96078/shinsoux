package dev.shinsou.kmp.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Storage boundary for the AES key used by desktop plugin state. */
internal interface DesktopMasterKeyStore {
    fun read(): ByteArray?

    fun write(value: ByteArray)
}

/**
 * Uses Security.framework directly so key material never appears in process arguments, shell
 * history, command output, or an application-support file beside the ciphertext.
 */
internal class MacOsKeychainMasterKeyStore(
    private val keychain: MacOsKeychainApi = JnaMacOsKeychainApi(),
) : DesktopMasterKeyStore {
    override fun read(): ByteArray? = keychain.readPassword(SERVICE, ACCOUNT)

    override fun write(value: ByteArray) {
        require(value.isNotEmpty()) { "Refusing to store an empty desktop master key." }
        keychain.upsertPassword(SERVICE, ACCOUNT, value)
    }

    private companion object {
        const val SERVICE = "dev.aluo.shinsoux.desktop.plugin-secrets"
        const val ACCOUNT = "master-key-v1"
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
        check(System.getProperty("os.name").contains("mac", ignoreCase = true)) {
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
