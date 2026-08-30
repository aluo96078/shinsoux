package dev.shinsou.kmp.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.GraphicsEnvironment
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Uses Security.framework directly so key material never appears in process arguments, shell
 * history, command output, or an application-support file beside the ciphertext.
 */
internal class MacOsKeychainMasterKeyStore(
    private val keychain: MacOsKeychainApi = JnaMacOsKeychainApi(),
    private val service: String = DEFAULT_SERVICE,
    private val account: String = DEFAULT_ACCOUNT,
    private val accessReason: String = localizedMacOsKeychainAccessReason(
        purpose = keychainPurposeFor(service),
    ),
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val executor: ExecutorService = newKeychainExecutor(),
) : DesktopMasterKeyStore {
    private val pendingReadLock = Any()
    private var pendingRead: Future<ByteArray?>? = null

    init {
        require(service.isNotBlank() && account.isNotBlank()) { "Keychain identity cannot be blank." }
        require(accessReason.isNotBlank()) { "Keychain access reason cannot be blank." }
        require(operationTimeoutMillis > 0) { "Keychain timeout must be positive." }
    }

    override fun read(): ByteArray? {
        val future = synchronized(pendingReadLock) {
            pendingRead?.takeUnless { it.isCancelled }
                ?: executor.submit<ByteArray?> {
                    keychain.readPassword(service, account, accessReason)
                }
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
    fun readPassword(service: String, account: String, accessReason: String): ByteArray?

    fun upsertPassword(service: String, account: String, value: ByteArray)
}

internal class JnaMacOsKeychainApi(
    private val accessConfirmation: (MacOsKeychainPurpose) -> Boolean = MacOsKeychainAccessExplainer::confirm,
) : MacOsKeychainApi {
    private val security: SecurityFramework
    private val coreFoundation: CoreFoundationFramework
    private val securityLibrary: NativeLibrary
    private val coreFoundationLibrary: NativeLibrary

    init {
        check(DesktopPlatform.fromOsName(System.getProperty("os.name")) == DesktopPlatform.MAC_OS) {
            "The macOS Keychain is unavailable on this operating system."
        }
        security = Native.load(SECURITY_FRAMEWORK, SecurityFramework::class.java)
        coreFoundation = Native.load(CORE_FOUNDATION_FRAMEWORK, CoreFoundationFramework::class.java)
        securityLibrary = NativeLibrary.getInstance(SECURITY_FRAMEWORK)
        coreFoundationLibrary = NativeLibrary.getInstance(CORE_FOUNDATION_FRAMEWORK)
    }

    override fun readPassword(service: String, account: String, accessReason: String): ByteArray? {
        require(accessReason.isNotBlank()) { "Keychain access reason cannot be blank." }
        if (!passwordItemExists(service, account)) return null
        return readMacOsKeychainPassword(
            silentRead = { copyPassword(service, account, accessReason, allowAuthenticationUi = false) },
            confirmInteractiveAccess = { accessConfirmation(keychainPurposeFor(service)) },
            interactiveRead = { copyPassword(service, account, accessReason, allowAuthenticationUi = true) },
        )
    }

    /**
     * Tries without UI first. A previously authorized item is returned immediately, so the app's
     * purpose explanation appears only when Security.framework says interaction is actually needed.
     */
    private fun copyPassword(
        service: String,
        account: String,
        accessReason: String,
        allowAuthenticationUi: Boolean,
    ): MacOsKeychainReadAttempt {
        val serviceValue = createString(service)
        val accountValue = createString(account)
        val reasonValue = createString(accessReason)
        val query = checkNotNull(
            coreFoundation.CFDictionaryCreateMutable(null, 0, null, null),
        ) { "macOS could not allocate a Keychain query." }
        val result = PointerByReference()
        return try {
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecClass"),
                securityConstant("kSecClassGenericPassword"),
            )
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecAttrService"),
                serviceValue,
            )
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecAttrAccount"),
                accountValue,
            )
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecReturnData"),
                coreFoundationConstant("kCFBooleanTrue"),
            )
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecMatchLimit"),
                securityConstant("kSecMatchLimitOne"),
            )
            // Modern data-protection items can display this reason directly. Legacy generic-
            // password items ignore it and show only their service identifier, so the app also
            // presents a purpose explanation immediately before this native authorization call.
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecUseOperationPrompt"),
                reasonValue,
            )
            coreFoundation.CFDictionarySetValue(
                query,
                securityConstant("kSecUseAuthenticationUI"),
                securityConstant(
                    if (allowAuthenticationUi) "kSecUseAuthenticationUIAllow"
                    else "kSecUseAuthenticationUIFail",
                ),
            )

            val status = security.SecItemCopyMatching(query, result)
            if (status == ERR_SEC_ITEM_NOT_FOUND) return MacOsKeychainReadAttempt.Missing
            if (!allowAuthenticationUi && status in INTERACTION_REQUIRED_STATUSES) {
                return MacOsKeychainReadAttempt.AuthenticationRequired
            }
            checkStatus(status, "read")

            val data = checkNotNull(result.value) { "macOS Keychain returned no password data." }
            check(coreFoundation.CFGetTypeID(data) == coreFoundation.CFDataGetTypeID()) {
                "macOS Keychain returned an unexpected password value."
            }
            val length = coreFoundation.CFDataGetLength(data)
            check(length in 1..MAX_KEYCHAIN_VALUE_BYTES) { "macOS Keychain returned an invalid value length." }
            val bytes = checkNotNull(coreFoundation.CFDataGetBytePtr(data)) {
                "macOS Keychain returned empty password storage."
            }
            MacOsKeychainReadAttempt.Value(bytes.getByteArray(0, length.toInt()))
        } finally {
            result.value?.let(coreFoundation::CFRelease)
            coreFoundation.CFRelease(query)
            coreFoundation.CFRelease(reasonValue)
            coreFoundation.CFRelease(accountValue)
            coreFoundation.CFRelease(serviceValue)
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

    private fun passwordItemExists(service: String, account: String): Boolean {
        val serviceBytes = service.encodeToByteArray()
        val accountBytes = account.encodeToByteArray()
        val itemReference = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            null,
            null,
            itemReference,
        )
        itemReference.value?.let(coreFoundation::CFRelease)
        return when (status) {
            ERR_SEC_SUCCESS -> true
            ERR_SEC_ITEM_NOT_FOUND -> false
            else -> {
                checkStatus(status, "locate")
                false
            }
        }
    }

    private fun createString(value: String): Pointer = checkNotNull(
        coreFoundation.CFStringCreateWithCString(
            null,
            Native.toByteArray(value, Charsets.UTF_8.name()),
            CF_STRING_ENCODING_UTF_8,
        ),
    ) { "macOS could not encode a Keychain query value." }

    private fun securityConstant(name: String): Pointer = checkNotNull(
        securityLibrary.getGlobalVariableAddress(name).getPointer(0),
    ) { "Security.framework did not expose $name." }

    private fun coreFoundationConstant(name: String): Pointer = checkNotNull(
        coreFoundationLibrary.getGlobalVariableAddress(name).getPointer(0),
    ) { "CoreFoundation.framework did not expose $name." }

    internal interface SecurityFramework : Library {
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int

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

    }

    internal interface CoreFoundationFramework : Library {
        fun CFDictionaryCreateMutable(
            allocator: Pointer?,
            capacity: Long,
            keyCallBacks: Pointer?,
            valueCallBacks: Pointer?,
        ): Pointer?

        fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)

        fun CFStringCreateWithCString(
            allocator: Pointer?,
            value: ByteArray,
            encoding: Int,
        ): Pointer?

        fun CFGetTypeID(value: Pointer): Long

        fun CFDataGetTypeID(): Long

        fun CFDataGetLength(data: Pointer): Long

        fun CFDataGetBytePtr(data: Pointer): Pointer?

        fun CFRelease(value: Pointer)
    }

    private companion object {
        const val SECURITY_FRAMEWORK = "/System/Library/Frameworks/Security.framework/Security"
        const val CORE_FOUNDATION_FRAMEWORK = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        const val CF_STRING_ENCODING_UTF_8 = 0x08000100
        internal const val ERR_SEC_SUCCESS = 0
        internal const val ERR_SEC_ITEM_NOT_FOUND = -25300
        internal const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25308
        internal const val ERR_SEC_INTERACTION_REQUIRED = -25315
        const val MAX_KEYCHAIN_VALUE_BYTES: Long = 4 * 1024
        val INTERACTION_REQUIRED_STATUSES = setOf(
            ERR_SEC_INTERACTION_NOT_ALLOWED,
            ERR_SEC_INTERACTION_REQUIRED,
        )
    }
}

internal sealed interface MacOsKeychainReadAttempt {
    data class Value(val bytes: ByteArray) : MacOsKeychainReadAttempt

    data object Missing : MacOsKeychainReadAttempt

    data object AuthenticationRequired : MacOsKeychainReadAttempt
}

internal fun readMacOsKeychainPassword(
    silentRead: () -> MacOsKeychainReadAttempt,
    confirmInteractiveAccess: () -> Boolean,
    interactiveRead: () -> MacOsKeychainReadAttempt,
): ByteArray? {
    return when (val silent = silentRead()) {
        is MacOsKeychainReadAttempt.Value -> silent.bytes
        MacOsKeychainReadAttempt.Missing -> null
        MacOsKeychainReadAttempt.AuthenticationRequired -> {
            if (!confirmInteractiveAccess()) throw MacOsKeychainAccessCanceledException()
            when (val interactive = interactiveRead()) {
                is MacOsKeychainReadAttempt.Value -> interactive.bytes
                MacOsKeychainReadAttempt.Missing -> null
                MacOsKeychainReadAttempt.AuthenticationRequired -> error(
                    "macOS Keychain still requires authentication after interactive access was allowed.",
                )
            }
        }
    }
}

internal enum class MacOsKeychainPurpose {
    EXTENSION_SIGN_IN,
    SYNC,
}

internal class MacOsKeychainAccessCanceledException : IllegalStateException(
    "The user canceled the macOS Keychain access explanation.",
)

internal data class MacOsKeychainAccessExplanation(
    val title: String,
    val message: String,
    val continueLabel: String,
    val cancelLabel: String,
)

/**
 * Legacy macOS generic-password authorization sheets discard kSecUseOperationPrompt. Show one
 * explicit, app-owned explanation per secret purpose and process before SecurityAgent asks for
 * the Mac login password. Canceling here prevents the native password sheet from opening.
 */
internal object MacOsKeychainAccessExplainer {
    private val lock = Any()
    private val confirmedPurposes = mutableSetOf<MacOsKeychainPurpose>()

    fun confirm(purpose: MacOsKeychainPurpose): Boolean = synchronized(lock) {
        if (purpose in confirmedPurposes) return@synchronized true
        if (GraphicsEnvironment.isHeadless()) return@synchronized false

        val explanation = localizedMacOsKeychainAccessExplanation(purpose)
        var confirmed = false
        val showDialog = {
            confirmed = JOptionPane.showOptionDialog(
                null,
                explanation.message,
                explanation.title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                arrayOf(explanation.continueLabel, explanation.cancelLabel),
                explanation.continueLabel,
            ) == JOptionPane.YES_OPTION
        }
        if (SwingUtilities.isEventDispatchThread()) {
            showDialog()
        } else {
            SwingUtilities.invokeAndWait(showDialog)
        }
        if (confirmed) confirmedPurposes += purpose
        confirmed
    }
}

internal fun keychainPurposeFor(service: String): MacOsKeychainPurpose =
    if (service == MacOsKeychainMasterKeyStore.DEFAULT_SERVICE) {
        MacOsKeychainPurpose.EXTENSION_SIGN_IN
    } else {
        MacOsKeychainPurpose.SYNC
    }

internal fun localizedMacOsKeychainAccessReason(
    purpose: MacOsKeychainPurpose,
    locale: Locale = Locale.getDefault(),
): String {
    val traditionalChinese = locale.language.equals("zh", ignoreCase = true) &&
        !locale.country.equals("CN", ignoreCase = true) &&
        !locale.country.equals("SG", ignoreCase = true) &&
        !locale.script.equals("Hans", ignoreCase = true)
    return when (purpose) {
        MacOsKeychainPurpose.EXTENSION_SIGN_IN -> if (traditionalChinese) {
            "用於解密只儲存在此 Mac 的擴充套件登入資料與 Cookie。此視窗中的鑰匙圈密碼只由 macOS 驗證，Shinsou X 不會取得。"
        } else {
            "Decrypts extension sign-in details and cookies stored only on this Mac. The Keychain password is verified only by macOS; Shinsou X cannot read it."
        }

        MacOsKeychainPurpose.SYNC -> if (traditionalChinese) {
            "用於解密只儲存在此 Mac 的同步憑證。此視窗中的鑰匙圈密碼只由 macOS 驗證，Shinsou X 不會取得。"
        } else {
            "Decrypts sync credentials stored only on this Mac. The Keychain password is verified only by macOS; Shinsou X cannot read it."
        }
    }
}

internal fun localizedMacOsKeychainAccessExplanation(
    purpose: MacOsKeychainPurpose,
    locale: Locale = Locale.getDefault(),
): MacOsKeychainAccessExplanation {
    val simplifiedChinese = locale.language.equals("zh", ignoreCase = true) &&
        (locale.country.equals("CN", ignoreCase = true) ||
            locale.country.equals("SG", ignoreCase = true) ||
            locale.script.equals("Hans", ignoreCase = true))
    val traditionalChinese = locale.language.equals("zh", ignoreCase = true) && !simplifiedChinese
    return when {
        traditionalChinese -> MacOsKeychainAccessExplanation(
            title = "Shinsou X 為何需要鑰匙圈密碼？",
            message = when (purpose) {
                MacOsKeychainPurpose.EXTENSION_SIGN_IN ->
                    "Shinsou X 即將請求 macOS 解鎖「鑰匙圈」，\n" +
                        "以讀取你只儲存在此 Mac 的擴充套件登入資料與 Cookie。\n\n" +
                        "下一個系統視窗需要輸入你的 Mac 登入密碼。\n" +
                        "密碼只由 macOS 驗證，Shinsou X 不會取得或保存。\n\n" +
                        "若信任此版本，可在下一個視窗選擇「永遠允許」，避免重複詢問。"

                MacOsKeychainPurpose.SYNC ->
                    "Shinsou X 即將請求 macOS 解鎖「鑰匙圈」，\n" +
                        "以讀取你只儲存在此 Mac 的同步憑證。\n\n" +
                        "下一個系統視窗需要輸入你的 Mac 登入密碼。\n" +
                        "密碼只由 macOS 驗證，Shinsou X 不會取得或保存。\n\n" +
                        "若信任此版本，可在下一個視窗選擇「永遠允許」，避免重複詢問。"
            },
            continueLabel = "繼續",
            cancelLabel = "取消",
        )

        simplifiedChinese -> MacOsKeychainAccessExplanation(
            title = "Shinsou X 为什么需要钥匙串密码？",
            message = when (purpose) {
                MacOsKeychainPurpose.EXTENSION_SIGN_IN ->
                    "Shinsou X 即将请求 macOS 解锁“钥匙串”，\n" +
                        "以读取仅存储在此 Mac 上的扩展登录信息与 Cookie。\n\n" +
                        "下一个系统窗口需要输入你的 Mac 登录密码。\n" +
                        "密码仅由 macOS 验证，Shinsou X 不会读取或保存。\n\n" +
                        "若信任此版本，可在下一窗口选择“始终允许”，避免重复询问。"

                MacOsKeychainPurpose.SYNC ->
                    "Shinsou X 即将请求 macOS 解锁“钥匙串”，\n" +
                        "以读取仅存储在此 Mac 上的同步凭据。\n\n" +
                        "下一个系统窗口需要输入你的 Mac 登录密码。\n" +
                        "密码仅由 macOS 验证，Shinsou X 不会读取或保存。\n\n" +
                        "若信任此版本，可在下一窗口选择“始终允许”，避免重复询问。"
            },
            continueLabel = "继续",
            cancelLabel = "取消",
        )

        else -> MacOsKeychainAccessExplanation(
            title = "Why does Shinsou X need your Keychain password?",
            message = when (purpose) {
                MacOsKeychainPurpose.EXTENSION_SIGN_IN ->
                    "Shinsou X is about to ask macOS to unlock Keychain so it can read\n" +
                        "extension sign-in details and cookies stored only on this Mac.\n\n" +
                        "The next system window asks for your Mac login password.\n" +
                        "Only macOS verifies it; Shinsou X cannot read or save the password.\n\n" +
                        "If you trust this build, choose Always Allow there to avoid repeated prompts."

                MacOsKeychainPurpose.SYNC ->
                    "Shinsou X is about to ask macOS to unlock Keychain so it can read\n" +
                        "sync credentials stored only on this Mac.\n\n" +
                        "The next system window asks for your Mac login password.\n" +
                        "Only macOS verifies it; Shinsou X cannot read or save the password.\n\n" +
                        "If you trust this build, choose Always Allow there to avoid repeated prompts."
            },
            continueLabel = "Continue",
            cancelLabel = "Cancel",
        )
    }
}
