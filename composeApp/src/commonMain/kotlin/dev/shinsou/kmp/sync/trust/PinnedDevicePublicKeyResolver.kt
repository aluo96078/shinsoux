package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.crypto.SyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.v2.BinaryData

/** Production resolver: event/checkpoint verification can only use keys from a verified full pin. */
class PinnedDevicePublicKeyResolver(
    private val pinStore: DeviceDirectoryPinStore,
    private val workspaceId: suspend () -> String?,
) : SyncDevicePublicKeyResolver {
    constructor(pinStore: DeviceDirectoryPinStore, workspaceId: String) : this(pinStore, { workspaceId })

    override suspend fun signingPublicKey(deviceId: String): BinaryData? {
        val workspace = workspaceId()?.takeIf(String::isNotBlank) ?: return null
        val pin = pinStore.load(workspace)?.device(deviceId) ?: return null
        val bytes = try {
            decodeBase64Url(pin.signingPublicKey)
        } catch (error: Throwable) {
            throw DeviceDirectoryTrustException.Malformed("Pinned device signing key is malformed")
        }
        if (bytes.size != ED25519_PUBLIC_KEY_BYTES) {
            throw DeviceDirectoryTrustException.Malformed("Pinned device signing key has an invalid size")
        }
        return BinaryData.copyOf(bytes)
    }
}

private const val ED25519_PUBLIC_KEY_BYTES = 32
