@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shinsou.kmp

import dev.shinsou.kmp.sync.persistence.SyncMetadataCorruptException
import dev.shinsou.kmp.sync.persistence.SyncMetadataUnavailableException
import dev.shinsou.kmp.sync.v2.LocalSyncStoreState
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncState
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.darwin.OSStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class IosSyncInfrastructureTest {
    @Test
    fun keychainStorePreservesRawBytesAndSeparatesFailureClasses() = runTest {
        val keychain = FakeIosSyncKeychainApi()
        val store = IosKeychainSyncSecretStore(keychain)
        val key = SyncSecretKey.DeviceSigningPrivateKey
        val raw = byteArrayOf(0, 1, 2, 0x7f, 0x80.toByte(), 0xff.toByte())
        val material = SecretMaterial(raw.asList())

        store.write(key, material)
        assertContentEquals(raw, keychain.values.values.single())
        assertEquals(material, assertIs<SyncSecretReadResult.Available>(store.read(key)).material)

        keychain.forcedRead = IosSyncKeychainReadResult.Missing
        assertEquals(SyncSecretReadResult.Missing, store.read(key))
        keychain.forcedRead = IosSyncKeychainReadResult.Unavailable(ERR_SEC_MISSING_ENTITLEMENT)
        assertIs<SyncSecretReadResult.Unavailable>(store.read(key))
        keychain.forcedRead = IosSyncKeychainReadResult.Corrupt(ERR_SEC_DECODE)
        assertIs<SyncSecretReadResult.Corrupt>(store.read(key))

        keychain.forcedMutation = IosSyncKeychainMutationResult.Unavailable(ERR_SEC_NOT_AVAILABLE)
        assertFailsWith<SyncMetadataUnavailableException> {
            store.write(key, material)
        }
        keychain.forcedMutation = IosSyncKeychainMutationResult.Corrupt(ERR_SEC_DECODE)
        assertFailsWith<SyncMetadataCorruptException> { store.delete(key) }
    }

    @Test
    fun installationSessionAndSqliteStateSurviveReopen() = runTest {
        withTemporarySyncDirectory { directory ->
            val uuids = ArrayDeque(
                listOf(
                    "10000000-0000-4000-8000-000000000001",
                    "20000000-0000-4000-8000-000000000002",
                ),
            )
            val installationPath = "$directory/installation.json"
            val installationStore = IosFileSyncInstallationStore(installationPath) { uuids.removeFirst() }
            val identity = installationStore.loadOrCreate()
            assertEquals(
                identity,
                IosFileSyncInstallationStore(installationPath) { error("Identity must not rotate") }.loadOrCreate(),
            )
            assertNotEquals(identity.installationId, identity.deviceId)

            val sessionPath = "$directory/session.json"
            val sessionStore = IosFileSyncSessionStore(sessionPath)
            val session = testSession(identity.deviceId)
            assertNull(sessionStore.load())
            sessionStore.save(session)
            assertEquals(session, IosFileSyncSessionStore(sessionPath).load())
            sessionStore.clear()
            assertNull(sessionStore.load())

            val state = LocalSyncStoreState(
                replica = SyncState(throughWorkspaceSeq = 9),
                nextDeviceSeq = 3,
                committedDeviceSeq = 2,
            )
            IosSyncInfrastructure(directory, FakeIosSyncKeychainApi()).also { infrastructure ->
                infrastructure.statePersistence().saveAtomically(state)
                infrastructure.close()
            }
            IosSyncInfrastructure(directory, FakeIosSyncKeychainApi()).also { infrastructure ->
                assertEquals(state, infrastructure.statePersistence().load())
                infrastructure.close()
            }
        }
    }

    private fun testSession(deviceId: String) = SyncSession(
        endpoint = "https://sync.example.test",
        instanceId = "instance-1",
        userId = "user-1",
        workspaceId = "workspace-1",
        deviceId = deviceId,
        deviceDisplayName = "iOS Simulator",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 2,
        activeKeyEpoch = 3,
    )
}

private class FakeIosSyncKeychainApi : IosSyncKeychainApi {
    val values = mutableMapOf<String, ByteArray>()
    var forcedRead: IosSyncKeychainReadResult? = null
    var forcedMutation: IosSyncKeychainMutationResult? = null

    override fun read(account: String): IosSyncKeychainReadResult = forcedRead
        ?: values[account]?.copyOf()?.let(IosSyncKeychainReadResult::Value)
        ?: IosSyncKeychainReadResult.Missing

    override fun write(account: String, bytes: ByteArray): IosSyncKeychainMutationResult {
        forcedMutation?.let { return it }
        values[account] = bytes.copyOf()
        return IosSyncKeychainMutationResult.Success
    }

    override fun delete(account: String): IosSyncKeychainMutationResult {
        forcedMutation?.let { return it }
        values.remove(account)?.fill(0)
        return IosSyncKeychainMutationResult.Success
    }
}

private suspend fun withTemporarySyncDirectory(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory() + "shinsou-sync-${NSUUID().UUIDString}"
    check(
        NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        ),
    )
    try {
        block(directory)
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }
}

private const val ERR_SEC_MISSING_ENTITLEMENT: OSStatus = -34018
private const val ERR_SEC_NOT_AVAILABLE: OSStatus = -25291
private const val ERR_SEC_DECODE: OSStatus = -26275
