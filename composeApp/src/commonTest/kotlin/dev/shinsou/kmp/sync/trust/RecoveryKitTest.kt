package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecoveryKitTest {
    @Test
    fun generatedExportImportsOnlyIntoStrictSecretStore() = runTest {
        val source = InMemorySyncSecretStore()
        val generated = RecoveryKitManager(source).generateAndInstall(
            endpoint = "https://sync.example.test",
            instanceId = INSTANCE,
            userId = USER,
            createdAt = 123_456,
        )
        assertEquals("SecretMaterial(REDACTED)", generated.exportedKit.toString())
        assertIs<SyncSecretReadResult.Available>(source.read(SyncSecretKey.RecoverySigningPrivateKey))
        assertIs<SyncSecretReadResult.Available>(source.read(SyncSecretKey.RecoveryWrappingPrivateKey))

        val destination = InMemorySyncSecretStore()
        val imported = RecoveryKitManager(destination).importAndInstall(generated.exportedKit)
        assertEquals(generated.metadata, imported)
        assertEquals(
            source.read(SyncSecretKey.RecoverySigningPrivateKey),
            destination.read(SyncSecretKey.RecoverySigningPrivateKey),
        )
        assertEquals(
            source.read(SyncSecretKey.RecoveryWrappingPrivateKey),
            destination.read(SyncSecretKey.RecoveryWrappingPrivateKey),
        )
        assertFailsWith<RecoveryKitException.AlreadyInstalled> {
            RecoveryKitManager(destination).importAndInstall(generated.exportedKit)
        }
    }

    @Test
    fun malformedExportWritesNoPrivateKeys() = runTest {
        val generated = RecoveryKitManager(InMemorySyncSecretStore()).generateAndInstall(
            "https://sync.example.test",
            INSTANCE,
            USER,
            1,
        )
        var tampered: ByteArray? = null
        generated.exportedKit.useBytes { bytes ->
            tampered = bytes.copyOf().also { copy ->
                val last = copy.lastIndex
                copy[last] = if (copy[last] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
            }
        }
        val destination = InMemorySyncSecretStore()
        assertFailsWith<RecoveryKitException> {
            RecoveryKitManager(destination).importAndInstall(SecretMaterial(requireNotNull(tampered).asList()))
        }
        assertEquals(SyncSecretReadResult.Missing, destination.read(SyncSecretKey.RecoverySigningPrivateKey))
        assertEquals(SyncSecretReadResult.Missing, destination.read(SyncSecretKey.RecoveryWrappingPrivateKey))
    }

    @Test
    fun partialSecretStoreWriteRollsBackBothRecoveryKeys() = runTest {
        val store = FailingSecondWriteStore()
        assertFailsWith<RecoveryKitException.Storage> {
            RecoveryKitManager(store).generateAndInstall(
                "https://sync.example.test",
                INSTANCE,
                USER,
                1,
            )
        }
        assertEquals(SyncSecretReadResult.Missing, store.read(SyncSecretKey.RecoverySigningPrivateKey))
        assertEquals(SyncSecretReadResult.Missing, store.read(SyncSecretKey.RecoveryWrappingPrivateKey))
    }

    @Test
    fun recoveryReplacementKeepsOldSignerUntilClaimCommit() = runTest {
        SodiumSyncPrimitives.initialize()
        val store = InMemorySyncSecretStore()
        val manager = RecoveryKitManager(store)
        val current = manager.generateAndInstall("https://sync.example.test", INSTANCE, USER, 1)
        val staged = manager.stageReplacement("https://sync.example.test", INSTANCE, USER, 2)
        val manifest = "{}"
        val beforeCommit = decodeBase64Url(manager.signRecoveryClaimManifest(manifest))
        assertTrue(
            SodiumSyncPrimitives.verifyEd25519(
                "shinsou:recovery-claim:v1\u0000{}".encodeToByteArray(),
                beforeCommit,
                decodeBase64Url(current.metadata.recoverySigningPublicKey),
            ),
        )

        manager.activateStagedReplacement()
        val afterCommit = decodeBase64Url(manager.signRecoveryClaimManifest(manifest))
        assertTrue(
            SodiumSyncPrimitives.verifyEd25519(
                "shinsou:recovery-claim:v1\u0000{}".encodeToByteArray(),
                afterCommit,
                decodeBase64Url(staged.metadata.recoverySigningPublicKey),
            ),
        )
        assertEquals(SyncSecretReadResult.Missing, store.read(SyncSecretKey.PendingRecoverySigningPrivateKey))
        assertEquals(SyncSecretReadResult.Missing, store.read(SyncSecretKey.PendingRecoveryWrappingPrivateKey))
    }

    @Test
    fun recoveryWorkspaceEnvelopeIsAuthenticatedAndCommitmentChecked() = runTest {
        SodiumSyncPrimitives.initialize()
        val store = InMemorySyncSecretStore()
        val generated = RecoveryKitManager(store).generateAndInstall(
            "https://sync.example.test",
            INSTANCE,
            USER,
            1,
        )
        val workspaceKey = ByteArray(32) { index -> (index + 1).toByte() }
        val recoveryPublic = decodeBase64Url(generated.metadata.recoveryWrappingPublicKey)
        val envelope = SodiumSyncPrimitives.wrapKey(
            workspaceKey,
            recoveryPublic,
            "shinsou:workspace-key-envelope:v1".encodeToByteArray(),
        )
        val encodedEnvelope = encodeBase64Url(
            DeterministicCbor.encode(
                JsonObject(
                    mapOf(
                        "cipherSuite" to JsonPrimitive("X25519_HKDF_SHA256_CHACHA20_POLY1305"),
                        "ephemeralPublicKey" to JsonPrimitive(encodeBase64Url(envelope.ephemeralPublicKey)),
                        "nonce" to JsonPrimitive(encodeBase64Url(envelope.nonce)),
                        "ciphertext" to JsonPrimitive(encodeBase64Url(envelope.ciphertext)),
                    ),
                ),
            ),
        )
        val commitment = encodeBase64Url(
            SodiumSyncPrimitives.sha256(
                "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray() + workspaceKey,
            ),
        )
        val opened = RecoveryKitManager(store).unwrapRecoveryWorkspaceKey(encodedEnvelope, commitment)
        var openedBytes: ByteArray? = null
        opened.useBytes { openedBytes = it.copyOf() }
        assertTrue(workspaceKey.contentEquals(requireNotNull(openedBytes)))
        assertFailsWith<RecoveryKitException.KeyMismatch> {
            RecoveryKitManager(store).unwrapRecoveryWorkspaceKey(encodedEnvelope, encodeBase64Url(ByteArray(32)))
        }
    }

    private class FailingSecondWriteStore : SyncSecretStore {
        private val delegate = InMemorySyncSecretStore()
        private var writes = 0

        override suspend fun read(key: SyncSecretKey): SyncSecretReadResult = delegate.read(key)

        override suspend fun write(key: SyncSecretKey, material: SecretMaterial) {
            writes++
            if (writes == 2) error("simulated protected-store failure")
            delegate.write(key, material)
        }

        override suspend fun delete(key: SyncSecretKey) = delegate.delete(key)
    }

    private companion object {
        const val INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val USER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
