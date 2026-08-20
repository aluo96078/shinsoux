package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.trust.PinnedDeviceDirectory
import dev.shinsou.kmp.sync.trust.PinnedDeviceIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDirectoryPinPersistenceTest {
    @Test
    fun compareAndSetPersistsMonotonicPinsAndClearDeletesLastFile() = runTest {
        val backend = MemoryPinBackend()
        val store = PersistentDeviceDirectoryPinStore(backend)
        val first = pin(version = 1, hashSeed = 'A')
        assertTrue(store.compareAndSet(WORKSPACE, null, first))
        assertEquals(first, store.load(WORKSPACE))
        assertFalse(store.compareAndSet(WORKSPACE, null, first))

        val second = pin(version = 2, hashSeed = 'Q')
        assertTrue(store.compareAndSet(WORKSPACE, first.revision, second))
        assertEquals(second, store.load(WORKSPACE))
        assertFailsWith<dev.shinsou.kmp.sync.trust.DeviceDirectoryTrustException.Rollback> {
            store.compareAndSet(WORKSPACE, second.revision, first)
        }

        store.clear(WORKSPACE)
        assertNull(store.load(WORKSPACE))
        assertEquals(1, backend.deleteCount)
    }

    @Test
    fun malformedPinFileFailsClosedInsteadOfResettingTrust() = runTest {
        val backend = MemoryPinBackend("{not-json")
        val store = PersistentDeviceDirectoryPinStore(backend)
        assertFailsWith<SyncMetadataCorruptException> { store.load(WORKSPACE) }
        assertEquals("{not-json", backend.value)
    }

    private fun pin(version: Long, hashSeed: Char) = PinnedDeviceDirectory(
        workspaceId = WORKSPACE,
        version = version,
        hash = hashSeed.toString().repeat(43),
        allDeviceCount = 1,
        devices = listOf(
            PinnedDeviceIdentity(
                deviceId = DEVICE,
                userId = USER,
                displayName = "Phone",
                platform = "ios",
                signingPublicKey = "A".repeat(43),
                wrappingPublicKey = "Q".repeat(43),
                status = "active",
                authEpoch = version,
                createdAt = 1,
                attestationSha256 = "g".repeat(43),
            ),
        ),
    )

    private class MemoryPinBackend(initial: String? = null) : DeviceDirectoryPinBackend {
        var value: String? = initial
        var deleteCount: Int = 0

        override suspend fun readUtf8(): String? = value
        override suspend fun writeUtf8(value: String) {
            this.value = value
        }

        override suspend fun delete() {
            value = null
            deleteCount++
        }
    }

    private companion object {
        const val WORKSPACE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val USER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val DEVICE = "11111111-1111-4111-8111-111111111111"
    }
}
