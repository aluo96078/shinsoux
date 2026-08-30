package dev.shinsou.kmp.desktop

import com.sun.jna.NativeLibrary
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class DesktopPluginKeyValueStoreTest {
    @Test
    fun legacyKeyMigratesToKeychainOnlyAfterExistingCiphertextDecrypts() = runTest {
        withTemporaryDirectory { directory ->
            val legacyKey = ByteArray(32) { (it + 1).toByte() }
            writeLegacyState(directory, legacyKey, "session-cookie")
            val keychain = FakeMasterKeyStore()

            val store = DesktopPluginKeyValueStore(directory, keychain)

            assertEquals("session-cookie", store.getString(SENSITIVE_KEY))
            assertContentEquals(legacyKey, assertNotNull(keychain.value))
            assertEquals(1, keychain.writeCount)
            assertFalse(directory.resolve(LEGACY_KEY_FILE).exists())
            assertFalse(directory.resolve("$LEGACY_KEY_FILE.tmp").exists())

            val reopened = DesktopPluginKeyValueStore(directory, keychain)
            assertEquals("session-cookie", reopened.getString(SENSITIVE_KEY))
        }
    }

    @Test
    fun failedKeychainWritePreservesLegacyKeyAndCiphertext() = runTest {
        withTemporaryDirectory { directory ->
            val legacyKey = ByteArray(32) { (it + 3).toByte() }
            writeLegacyState(directory, legacyKey, "keep-me")
            val stateBefore = Files.readString(directory.resolve(STATE_FILE))
            val keychain = FakeMasterKeyStore(failWrites = true)

            assertFailsWith<IllegalStateException> {
                DesktopPluginKeyValueStore(directory, keychain).getString(SENSITIVE_KEY)
            }

            assertTrue(directory.resolve(LEGACY_KEY_FILE).exists())
            assertEquals(stateBefore, Files.readString(directory.resolve(STATE_FILE)))
        }
    }

    @Test
    fun unverifiedKeychainWritePreservesLegacyKey() = runTest {
        withTemporaryDirectory { directory ->
            val legacyKey = ByteArray(32) { (it + 5).toByte() }
            writeLegacyState(directory, legacyKey, "keep-until-verified")
            val keychain = FakeMasterKeyStore(discardWrites = true)

            assertFailsWith<IllegalStateException> {
                DesktopPluginKeyValueStore(directory, keychain).getString(SENSITIVE_KEY)
            }

            assertTrue(directory.resolve(LEGACY_KEY_FILE).exists())
            assertEquals(1, keychain.writeCount)
        }
    }

    @Test
    fun corruptedCiphertextNeverDeletesLegacyKey() = runTest {
        withTemporaryDirectory { directory ->
            val legacyKey = ByteArray(32) { (it + 7).toByte() }
            Files.writeString(
                directory.resolve(LEGACY_KEY_FILE),
                Base64.getEncoder().encodeToString(legacyKey),
                StandardCharsets.US_ASCII,
            )
            Files.writeString(
                directory.resolve(STATE_FILE),
                "{\"$SENSITIVE_KEY\":\"enc:v1:not-valid-base64!\"}",
                StandardCharsets.UTF_8,
            )
            val keychain = FakeMasterKeyStore()

            assertFailsWith<IllegalStateException> {
                DesktopPluginKeyValueStore(directory, keychain).getString(SENSITIVE_KEY)
            }

            assertTrue(directory.resolve(LEGACY_KEY_FILE).exists())
            assertEquals(0, keychain.writeCount)
        }
    }

    @Test
    fun newlyGeneratedKeyExistsOnlyInProtectedStore() = runTest {
        withTemporaryDirectory { directory ->
            val keychain = FakeMasterKeyStore()
            val store = DesktopPluginKeyValueStore(directory, keychain)

            store.putString(SENSITIVE_KEY, "new-secret")

            assertEquals("new-secret", store.getString(SENSITIVE_KEY))
            assertEquals(32, assertNotNull(keychain.value).size)
            assertFalse(directory.resolve(LEGACY_KEY_FILE).exists())
            assertFalse(directory.resolve("$LEGACY_KEY_FILE.tmp").exists())
            assertFalse(Files.readString(directory.resolve(STATE_FILE)).contains("new-secret"))
        }
    }

    @Test
    fun keychainAdapterUsesStableIdentityAndRawKeyBytes() {
        val api = FakeKeychainApi()
        val store = MacOsKeychainMasterKeyStore(
            keychain = api,
            accessReason = "Explain the protected extension sign-in lookup.",
        )
        val key = ByteArray(32) { (255 - it).toByte() }

        store.write(key)

        assertEquals("dev.aluo.shinsoux.desktop.plugin-secrets", api.service)
        assertEquals("master-key-v1", api.account)
        assertContentEquals(key, api.value)
        assertContentEquals(key, store.read())
        assertEquals("Explain the protected extension sign-in lookup.", api.accessReason)
    }

    @Test
    fun keychainAccessReasonExplainsPurposeWithoutLeakingInternalIdentity() {
        val traditionalChinese = localizedMacOsKeychainAccessReason(
            purpose = MacOsKeychainPurpose.EXTENSION_SIGN_IN,
            locale = Locale.forLanguageTag("zh-TW"),
        )
        val english = localizedMacOsKeychainAccessReason(
            purpose = MacOsKeychainPurpose.EXTENSION_SIGN_IN,
            locale = Locale.US,
        )

        assertTrue(traditionalChinese.contains("登入資料"))
        assertTrue(traditionalChinese.contains("macOS"))
        assertTrue(english.contains("extension sign-in details"))
        assertTrue(english.contains("macOS"))
        listOf(traditionalChinese, english).forEach { reason ->
            assertFalse(reason.contains(MacOsKeychainMasterKeyStore.DEFAULT_SERVICE))
            assertFalse(reason.contains(MacOsKeychainMasterKeyStore.DEFAULT_ACCOUNT))
        }
    }

    @Test
    fun keychainPreflightExplainsTheUpcomingSystemPasswordWindow() {
        val traditionalChinese = localizedMacOsKeychainAccessExplanation(
            purpose = MacOsKeychainPurpose.EXTENSION_SIGN_IN,
            locale = Locale.forLanguageTag("zh-TW"),
        )
        val simplifiedChinese = localizedMacOsKeychainAccessExplanation(
            purpose = MacOsKeychainPurpose.EXTENSION_SIGN_IN,
            locale = Locale.forLanguageTag("zh-CN"),
        )
        val english = localizedMacOsKeychainAccessExplanation(
            purpose = MacOsKeychainPurpose.EXTENSION_SIGN_IN,
            locale = Locale.US,
        )

        assertTrue(traditionalChinese.title.contains("為何"))
        assertTrue(traditionalChinese.message.contains("下一個系統視窗"))
        assertTrue(traditionalChinese.message.contains("Shinsou X 不會取得或保存"))
        assertTrue(simplifiedChinese.message.contains("下一个系统窗口"))
        assertTrue(english.message.contains("next system window"))
        listOf(traditionalChinese, simplifiedChinese, english).forEach { explanation ->
            assertTrue(explanation.continueLabel.isNotBlank())
            assertTrue(explanation.cancelLabel.isNotBlank())
            assertFalse(explanation.message.contains(MacOsKeychainMasterKeyStore.DEFAULT_SERVICE))
            assertFalse(explanation.message.contains(MacOsKeychainMasterKeyStore.DEFAULT_ACCOUNT))
        }
    }

    @Test
    fun keychainAdapterBoundsAStuckNativeReadAndRecoversAfterAuthorizationCompletes() {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "test-keychain-timeout").apply { isDaemon = true }
        }
        val release = java.util.concurrent.CountDownLatch(1)
        val readCalls = java.util.concurrent.atomic.AtomicInteger()
        val expected = ByteArray(32) { (it + 11).toByte() }
        val api = object : MacOsKeychainApi {
            override fun readPassword(
                service: String,
                account: String,
                accessReason: String,
            ): ByteArray? {
                readCalls.incrementAndGet()
                release.await()
                return expected.copyOf()
            }

            override fun upsertPassword(service: String, account: String, value: ByteArray) = Unit
        }
        val store = MacOsKeychainMasterKeyStore(
            keychain = api,
            operationTimeoutMillis = 50,
            executor = executor,
        )

        try {
            repeat(2) {
                val elapsed = measureTime {
                    val failure = assertFailsWith<IllegalStateException> { store.read() }
                    assertTrue(failure.message.orEmpty().contains("timed out"))
                }
                assertTrue(elapsed.inWholeMilliseconds < 500)
            }

            release.countDown()
            val elapsed = measureTime {
                assertContentEquals(expected, store.read())
            }
            assertTrue(elapsed.inWholeMilliseconds < 500)
            assertEquals(1, readCalls.get())

            val subsequent = measureTime {
                assertContentEquals(expected, store.read())
            }
            assertTrue(subsequent.inWholeMilliseconds < 500)
            assertEquals(2, readCalls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun requiredSecurityFrameworkSymbolsAreAvailable() {
        if (DesktopPlatform.current != DesktopPlatform.MAC_OS) return

        val security = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security")
        listOf(
            "SecItemCopyMatching",
            "SecKeychainFindGenericPassword",
            "SecKeychainAddGenericPassword",
            "SecKeychainItemModifyAttributesAndData",
        ).forEach { symbol -> assertNotNull(security.getFunction(symbol)) }
    }

    @Test
    fun modernKeychainQueryHandlesMissingItemWithoutShowingAuthorizationUi() {
        if (DesktopPlatform.current != DesktopPlatform.MAC_OS) return

        val result = JnaMacOsKeychainApi().readPassword(
            service = "dev.aluo.shinsoux.desktop.test-missing-${UUID.randomUUID()}",
            account = "missing",
            accessReason = "Automated test lookup for a deliberately missing item.",
        )

        assertEquals(null, result)
    }

    @Test
    fun desktopDoesNotAdvertiseUnimplementedSecurityControls() = runTest {
        val services = DesktopAppServices(closeApplication = {})

        assertFalse(services.securityCapabilities.appLock.available)
        assertFalse(services.securityCapabilities.secureScreen.available)
        assertFalse(services.authenticate("test"))
    }

    private fun writeLegacyState(directory: Path, key: ByteArray, secret: String) {
        Files.writeString(
            directory.resolve(LEGACY_KEY_FILE),
            Base64.getEncoder().encodeToString(key),
            StandardCharsets.US_ASCII,
        )
        val encrypted = encryptLegacyValue(secret, key)
        Files.writeString(
            directory.resolve(STATE_FILE),
            "{\"$SENSITIVE_KEY\":\"enc:v1:$encrypted\"}",
            StandardCharsets.UTF_8,
        )
    }

    private fun encryptLegacyValue(value: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        val payload = ByteArray(1 + cipher.iv.size + ciphertext.size)
        payload[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(payload, destinationOffset = 1)
        ciphertext.copyInto(payload, destinationOffset = 1 + cipher.iv.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    private suspend fun withTemporaryDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("shinsou-desktop-secrets-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class FakeMasterKeyStore(
        initialValue: ByteArray? = null,
        private val failWrites: Boolean = false,
        private val discardWrites: Boolean = false,
    ) : DesktopMasterKeyStore {
        var value: ByteArray? = initialValue?.copyOf()
            private set
        var writeCount: Int = 0
            private set

        override fun read(): ByteArray? = value?.copyOf()

        override fun write(value: ByteArray) {
            writeCount += 1
            if (failWrites) error("Simulated Keychain failure")
            if (!discardWrites) this.value = value.copyOf()
        }
    }

    private class FakeKeychainApi : MacOsKeychainApi {
        var service: String? = null
        var account: String? = null
        var accessReason: String? = null
        var value: ByteArray? = null

        override fun readPassword(
            service: String,
            account: String,
            accessReason: String,
        ): ByteArray? {
            this.service = service
            this.account = account
            this.accessReason = accessReason
            return value?.copyOf()
        }

        override fun upsertPassword(service: String, account: String, value: ByteArray) {
            this.service = service
            this.account = account
            this.value = value.copyOf()
        }
    }

    private companion object {
        const val STATE_FILE = "plugin-state.json"
        const val LEGACY_KEY_FILE = "plugin-secrets.key"
        const val SENSITIVE_KEY = "source.1.cookies"
    }
}
