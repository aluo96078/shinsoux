package dev.shinsou.kmp.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsDpapiMasterKeyStoreTest {
    @Test
    fun protectedBlobPersistsAndReopensWithoutPlaintextKey() {
        withTemporaryDirectory { directory ->
            val protectedKeyFile = directory.resolve(DesktopMasterKeyStoreFactory.WINDOWS_PROTECTED_KEY_FILE)
            val dpapi = FakeWindowsDpapiApi()
            val key = ByteArray(32) { (it * 7 + 3).toByte() }

            WindowsDpapiMasterKeyStore(protectedKeyFile, dpapi).write(key)

            val bytesOnDisk = Files.readAllBytes(protectedKeyFile)
            assertFalse(key.contentEquals(bytesOnDisk))
            assertFalse(bytesOnDisk.toList().windowed(key.size).any { it == key.toList() })
            assertContentEquals(key, WindowsDpapiMasterKeyStore(protectedKeyFile, dpapi).read())
            assertContentEquals(key, dpapi.lastProtectedPlaintext)
            assertContentEquals(bytesOnDisk, dpapi.lastUnprotectedBlob)
        }
    }

    @Test
    fun absentProtectedBlobReturnsNullWithoutCallingDpapi() {
        withTemporaryDirectory { directory ->
            val dpapi = FakeWindowsDpapiApi()
            val store = WindowsDpapiMasterKeyStore(directory.resolve("missing.dpapi"), dpapi)

            assertNull(store.read())
            assertNull(dpapi.lastUnprotectedBlob)
        }
    }

    @Test
    fun nonRegularProtectedBlobIsRejected() {
        withTemporaryDirectory { directory ->
            val protectedKeyPath = directory.resolve("plugin-secrets.dpapi")
            Files.createDirectory(protectedKeyPath)

            assertFailsWith<IllegalStateException> {
                WindowsDpapiMasterKeyStore(protectedKeyPath, FakeWindowsDpapiApi()).read()
            }
        }
    }

    @Test
    fun oversizedProtectedBlobIsRejectedBeforeCallingDpapi() {
        withTemporaryDirectory { directory ->
            val protectedKeyPath = directory.resolve("plugin-secrets.dpapi")
            Files.write(protectedKeyPath, ByteArray(64 * 1024 + 1))
            val dpapi = FakeWindowsDpapiApi()

            assertFailsWith<IllegalStateException> {
                WindowsDpapiMasterKeyStore(protectedKeyPath, dpapi).read()
            }
            assertNull(dpapi.lastUnprotectedBlob)
        }
    }

    @Test
    fun blobAnotherWindowsUserProtectedIsSetAsideSoAFreshKeyCanReplaceIt() {
        withTemporaryDirectory { directory ->
            val protectedKeyFile = directory.resolve(DesktopMasterKeyStoreFactory.WINDOWS_PROTECTED_KEY_FILE)
            val original = ByteArray(32) { it.toByte() }
            WindowsDpapiMasterKeyStore(protectedKeyFile, FakeWindowsDpapiApi()).write(original)
            val foreignBytes = Files.readAllBytes(protectedKeyFile)
            // NTE_BAD_KEY_STATE: what DPAPI reports after the user's master key is lost.
            val dpapi = FakeWindowsDpapiApi(unprotectError = 0x8009000B.toInt())
            val store = WindowsDpapiMasterKeyStore(protectedKeyFile, dpapi)

            assertNull(store.read())
            assertFalse(Files.exists(protectedKeyFile))
            val quarantined = Files.list(directory).use { files ->
                files.filter { it.fileName.toString().startsWith("${protectedKeyFile.fileName}.unreadable-") }.toList()
            }
            assertEquals(1, quarantined.size)
            assertContentEquals(foreignBytes, Files.readAllBytes(quarantined.single()))

            val replacement = ByteArray(32) { (it + 100).toByte() }
            WindowsDpapiMasterKeyStore(protectedKeyFile, FakeWindowsDpapiApi()).write(replacement)
            assertContentEquals(replacement, WindowsDpapiMasterKeyStore(protectedKeyFile, FakeWindowsDpapiApi()).read())
        }
    }

    @Test
    fun unexpectedDpapiFailureKeepsTheBlobAndPropagates() {
        withTemporaryDirectory { directory ->
            val protectedKeyFile = directory.resolve(DesktopMasterKeyStoreFactory.WINDOWS_PROTECTED_KEY_FILE)
            WindowsDpapiMasterKeyStore(protectedKeyFile, FakeWindowsDpapiApi()).write(ByteArray(32) { 1 })
            val before = Files.readAllBytes(protectedKeyFile)
            // ERROR_NOT_ENOUGH_MEMORY may be transient, so the key must not be discarded.
            val store = WindowsDpapiMasterKeyStore(protectedKeyFile, FakeWindowsDpapiApi(unprotectError = 8))

            val error = assertFailsWith<WindowsDpapiException> { store.read() }
            assertEquals(8, error.errorCode)
            assertContentEquals(before, Files.readAllBytes(protectedKeyFile))
        }
    }

    @Test
    fun factoryLoadsOnlyTheCurrentOperatingSystemAdapter() {
        withTemporaryDirectory { directory ->
            val store = DesktopMasterKeyStoreFactory.create(directory)

            when (DesktopPlatform.current) {
                DesktopPlatform.MAC_OS -> assertIs<MacOsKeychainMasterKeyStore>(store)
                DesktopPlatform.WINDOWS -> assertIs<WindowsDpapiMasterKeyStore>(store)
                DesktopPlatform.LINUX,
                DesktopPlatform.OTHER,
                -> assertFailsWith<IllegalStateException> { store.read() }
            }
        }
    }

    @Test
    fun unsupportedPlatformCanConstructStoreWithoutLoadingAnyNativeLibrary() {
        withTemporaryDirectory { directory ->
            val store = DesktopMasterKeyStoreFactory.create(directory, DesktopPlatform.LINUX)

            assertFailsWith<IllegalStateException> { store.read() }
        }
    }

    @Test
    fun nativeDpapiRoundTripsUnderTheCurrentWindowsUser() {
        if (DesktopPlatform.current != DesktopPlatform.WINDOWS) return

        val dpapi = JnaWindowsDpapiApi()
        val key = ByteArray(32) { (255 - it * 3).toByte() }
        val protectedValue = dpapi.protect(key)
        try {
            assertFalse(key.contentEquals(protectedValue))
            val unprotectedValue = dpapi.unprotect(protectedValue)
            try {
                assertContentEquals(key, unprotectedValue)
            } finally {
                unprotectedValue.fill(0)
            }
        } finally {
            protectedValue.fill(0)
        }
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("shinsou-windows-dpapi-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class FakeWindowsDpapiApi(private val unprotectError: Int? = null) : WindowsDpapiApi {
        var lastProtectedPlaintext: ByteArray? = null
            private set
        var lastUnprotectedBlob: ByteArray? = null
            private set

        override fun protect(value: ByteArray): ByteArray {
            lastProtectedPlaintext = value.copyOf()
            return MAGIC + value.map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()
        }

        override fun unprotect(value: ByteArray): ByteArray {
            lastUnprotectedBlob = value.copyOf()
            unprotectError?.let { throw WindowsDpapiException(it, "fake DPAPI failure $it") }
            assertTrue(value.size > MAGIC.size)
            assertContentEquals(MAGIC, value.copyOfRange(0, MAGIC.size))
            return value.copyOfRange(MAGIC.size, value.size)
                .map { byte -> (byte.toInt() xor MASK).toByte() }
                .toByteArray()
        }

        private companion object {
            val MAGIC = byteArrayOf(0x44, 0x50, 0x41, 0x50, 0x49)
            const val MASK = 0x5a
        }
    }
}
