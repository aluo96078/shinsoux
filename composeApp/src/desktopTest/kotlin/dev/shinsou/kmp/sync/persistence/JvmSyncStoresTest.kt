package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class JvmSyncStoresTest {
    @Test
    fun sessionRoundTripsClearsAndRejectsCorruption() = runTest {
        val path = Files.createTempDirectory("shinsou-sync-session").resolve("session.json")
        val store = JvmFileSyncSessionStore(path)
        val session = testSession()

        assertNull(store.load())
        store.save(session)
        assertEquals(session, JvmFileSyncSessionStore(path).load())

        store.clear()
        assertNull(store.load())

        Files.writeString(path, "{not-json")
        assertFailsWith<SyncMetadataCorruptException> { store.load() }
    }

    @Test
    fun installationIdentityIsStableAndCorruptionNeverRotatesIt() = runTest {
        val path = Files.createTempDirectory("shinsou-sync-installation").resolve("installation.json")
        val uuids = ArrayDeque(
            listOf(
                "10000000-0000-4000-8000-000000000001",
                "20000000-0000-4000-8000-000000000002",
            ),
        )
        val first = JvmFileSyncInstallationStore(path) { uuids.removeFirst() }.loadOrCreate()
        val reopened = JvmFileSyncInstallationStore(path) { error("Identity must not rotate") }.loadOrCreate()

        assertEquals(first, reopened)
        assertNotEquals(first.installationId, first.deviceId)

        Files.writeString(path, "{}")
        assertFailsWith<SyncMetadataCorruptException> {
            JvmFileSyncInstallationStore(path) { error("Corruption must not create a new UUID") }.loadOrCreate()
        }
    }

    @Test
    fun secretRoundTripNeverWritesPlaintextAndWorkspaceKeysDoNotCollide() = runTest {
        val path = Files.createTempDirectory("shinsou-sync-secrets").resolve("secrets.json")
        val store = JvmEncryptedSyncSecretStore(path, XorTestProtector())
        val plaintext = "plain-secret-never-store".encodeToByteArray()
        val material = SecretMaterial(plaintext.asList())
        val firstWorkspace = SyncSecretKey.WorkspaceEpochKey("workspace-a", 3)
        val secondWorkspace = SyncSecretKey.WorkspaceEpochKey("workspace-b", 3)

        assertNotEquals(firstWorkspace.storageIdentifier(), secondWorkspace.storageIdentifier())
        assertEquals(SyncSecretReadResult.Missing, store.read(firstWorkspace))
        store.write(firstWorkspace, material)

        val available = assertIs<SyncSecretReadResult.Available>(store.read(firstWorkspace))
        assertEquals(material, available.material)
        assertFalse(Files.readString(path).contains(plaintext.decodeToString()))

        store.delete(firstWorkspace)
        assertEquals(SyncSecretReadResult.Missing, store.read(firstWorkspace))
    }

    @Test
    fun protectionFailuresRemainUnavailableOrCorrupt() = runTest {
        val directory = Files.createTempDirectory("shinsou-sync-fail-closed")
        val path = directory.resolve("secrets.json")
        val key = SyncSecretKey.DeviceCredential
        JvmEncryptedSyncSecretStore(path, XorTestProtector()).write(
            key,
            SecretMaterial(listOf(1, 2, 3)),
        )

        val unavailable = JvmEncryptedSyncSecretStore(path, UnavailableTestProtector())
        assertIs<SyncSecretReadResult.Unavailable>(unavailable.read(key))
        assertFailsWith<SyncMetadataUnavailableException> {
            unavailable.write(SyncSecretKey.AccessToken, SecretMaterial(listOf(4, 5, 6)))
        }

        val corrupt = JvmEncryptedSyncSecretStore(path, CorruptTestProtector())
        assertIs<SyncSecretReadResult.Corrupt>(corrupt.read(key))

        Files.writeString(path, "not-json")
        assertIs<SyncSecretReadResult.Corrupt>(
            JvmEncryptedSyncSecretStore(path, XorTestProtector()).read(key),
        )
    }

    private fun testSession() = SyncSession(
        endpoint = "https://sync.example.test",
        instanceId = "instance-1",
        userId = "user-1",
        workspaceId = "workspace-1",
        deviceId = "device-1",
        deviceDisplayName = "Test Desktop",
        platform = "desktop",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 2,
        activeKeyEpoch = 3,
    )
}

private class XorTestProtector : JvmSyncSecretProtector {
    override fun encrypt(plaintext: ByteArray, protectedValuesExist: Boolean): ByteArray =
        byteArrayOf(VERSION) + plaintext.map { byte -> (byte.toInt() xor MASK).toByte() }

    override fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size < 2 || payload.first() != VERSION) {
            throw JvmSyncProtectionCorruptException("Test envelope is invalid")
        }
        return payload.drop(1).map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()
    }

    private companion object {
        const val MASK = 0x5a
        const val VERSION: Byte = 1
    }
}

private class UnavailableTestProtector : JvmSyncSecretProtector {
    override fun encrypt(plaintext: ByteArray, protectedValuesExist: Boolean): ByteArray {
        check(protectedValuesExist) { "Existing ciphertext must be reported to the protector" }
        throw JvmSyncProtectionUnavailableException("Protected key is missing")
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        throw JvmSyncProtectionUnavailableException("Protected key is missing")
    }
}

private class CorruptTestProtector : JvmSyncSecretProtector {
    override fun encrypt(plaintext: ByteArray, protectedValuesExist: Boolean): ByteArray {
        throw JvmSyncProtectionCorruptException("Protected key is corrupt")
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        throw JvmSyncProtectionCorruptException("Ciphertext authentication failed")
    }
}
