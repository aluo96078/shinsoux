package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretAccessException
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.requireSecret

/** Builds out-of-band anchors only from strict, platform-protected local private keys. */
class StoredDeviceTrustContextProvider(
    private val secretStore: SyncSecretStore,
) {
    suspend operator fun invoke(session: SyncSession): DeviceDirectoryTrustContext {
        SodiumSyncPrimitives.initialize()
        val signingPrivate = secretStore.requireSecret(SyncSecretKey.DeviceSigningPrivateKey).copyBytes()
        val wrappingPrivate = secretStore.requireSecret(SyncSecretKey.DeviceWrappingPrivateKey).copyBytes()
        val signingPublic: ByteArray
        val wrappingPublic: ByteArray
        try {
            signingPublic = SodiumSyncPrimitives.ed25519PublicKey(signingPrivate)
            wrappingPublic = SodiumSyncPrimitives.x25519PublicKey(wrappingPrivate)
        } finally {
            SodiumSyncPrimitives.destroy(signingPrivate)
            SodiumSyncPrimitives.destroy(wrappingPrivate)
        }
        val recoveryAnchors = recoverySigningPublicKey()?.let(::setOf).orEmpty()
        return try {
            DeviceDirectoryTrustContext(
                instanceId = session.instanceId,
                workspaceId = session.workspaceId,
                trustedDevices = listOf(
                    TrustedDeviceAnchor(
                        deviceId = session.deviceId,
                        signingPublicKey = encodeBase64Url(signingPublic),
                        wrappingPublicKey = encodeBase64Url(wrappingPublic),
                    ),
                ),
                trustedRecoverySigningPublicKeys = recoveryAnchors,
            )
        } finally {
            SodiumSyncPrimitives.destroy(signingPublic)
            SodiumSyncPrimitives.destroy(wrappingPublic)
        }
    }

    private suspend fun recoverySigningPublicKey(): String? = when (
        val result = secretStore.read(SyncSecretKey.RecoverySigningPrivateKey)
    ) {
        SyncSecretReadResult.Missing -> null
        is SyncSecretReadResult.Available -> {
            val privateKey = result.material.copyBytes()
            try {
                encodeBase64Url(SodiumSyncPrimitives.ed25519PublicKey(privateKey))
            } finally {
                SodiumSyncPrimitives.destroy(privateKey)
            }
        }

        is SyncSecretReadResult.Unavailable -> throw SyncSecretAccessException.Unavailable(
            SyncSecretKey.RecoverySigningPrivateKey,
            result.diagnostic,
        )

        is SyncSecretReadResult.Corrupt -> throw SyncSecretAccessException.Corrupt(
            SyncSecretKey.RecoverySigningPrivateKey,
            result.diagnostic,
        )
    }
}

private fun SecretMaterial.copyBytes(): ByteArray {
    var copy: ByteArray? = null
    useBytes { copy = it.copyOf() }
    return requireNotNull(copy)
}
