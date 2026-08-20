package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.network.decodeBase64Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class DeviceEnrollmentAttestationWire(
    val type: String,
    val workspaceId: String,
    val attestorDeviceId: String? = null,
    val attestorPublicKey: String,
    val signatureDomain: String,
    val manifestJson: String,
    val signature: String,
    val createdAt: Long,
)

@Serializable
data class DeviceDirectoryEntryWire(
    val deviceId: String,
    val userId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
    val status: String,
    val authEpoch: Long,
    val createdAt: Long,
    val revokedAt: Long? = null,
    val attestation: DeviceEnrollmentAttestationWire,
)

/** Full bootstrap directory, or the attested sender subset attached to a catch-up page. */
@Serializable
data class DeviceDirectoryWire(
    val version: Long,
    val hash: String,
    val allDeviceCount: Int,
    val devices: List<DeviceDirectoryEntryWire>,
)

@Serializable
data class PinnedDeviceIdentity(
    val deviceId: String,
    val userId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
    val status: String,
    val authEpoch: Long,
    val createdAt: Long,
    val revokedAt: Long? = null,
    val attestationSha256: String,
)

@Serializable
data class PinnedDeviceDirectory(
    val workspaceId: String,
    val version: Long,
    val hash: String,
    val allDeviceCount: Int,
    val devices: List<PinnedDeviceIdentity>,
) {
    init {
        require(workspaceId.isNotBlank()) { "Pinned directory workspace is missing" }
        require(version > 0) { "Pinned directory version is invalid" }
        require(hash.isCanonicalSha256()) { "Pinned directory hash is invalid" }
        require(allDeviceCount == devices.size && devices.isNotEmpty()) {
            "Pinned directory device count is inconsistent"
        }
        require(devices.map(PinnedDeviceIdentity::deviceId).distinct().size == devices.size) {
            "Pinned directory contains duplicate devices"
        }
        require(devices.map(PinnedDeviceIdentity::deviceId) == devices.map(PinnedDeviceIdentity::deviceId).sorted()) {
            "Pinned directory devices are not sorted"
        }
        require(devices.all { device ->
            device.signingPublicKey.isCanonicalBytes(32) &&
                device.wrappingPublicKey.isCanonicalBytes(32) &&
                device.attestationSha256.isCanonicalSha256() &&
                device.displayName.isNotBlank() && device.displayName.length <= 120 &&
                device.platform in setOf("android", "ios", "macos", "windows", "other") &&
                device.authEpoch > 0 && device.createdAt >= 0 &&
                device.status in setOf("active", "revoked") &&
                ((device.status == "active") == (device.revokedAt == null))
        }) { "Pinned directory contains invalid device trust metadata" }
    }

    val revision: DeviceDirectoryRevision
        get() = DeviceDirectoryRevision(version, hash)

    fun device(deviceId: String): PinnedDeviceIdentity? = devices.firstOrNull { it.deviceId == deviceId }
}

data class DeviceDirectoryRevision(val version: Long, val hash: String)

/** A key fingerprint authenticated out-of-band by local setup, QR, or six-digit comparison. */
data class TrustedDeviceAnchor(
    val deviceId: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
)

data class DeviceDirectoryTrustContext(
    val instanceId: String,
    val workspaceId: String,
    val trustedDevices: List<TrustedDeviceAnchor> = emptyList(),
    val trustedRecoverySigningPublicKeys: Set<String> = emptySet(),
) {
    init {
        require(instanceId.isNotBlank() && workspaceId.isNotBlank()) { "Directory trust context is incomplete" }
        require(trustedDevices.map(TrustedDeviceAnchor::deviceId).distinct().size == trustedDevices.size) {
            "Directory trust context contains duplicate device anchors"
        }
    }
}

interface DeviceDirectoryPinStore {
    suspend fun load(workspaceId: String): PinnedDeviceDirectory?

    /** Atomic within the store instance; returns false when another verifier advanced the pin. */
    suspend fun compareAndSet(
        workspaceId: String,
        expected: DeviceDirectoryRevision?,
        updated: PinnedDeviceDirectory,
    ): Boolean

    suspend fun clear(workspaceId: String)
}

class InMemoryDeviceDirectoryPinStore(
    initial: Collection<PinnedDeviceDirectory> = emptyList(),
) : DeviceDirectoryPinStore {
    private val mutex = Mutex()
    private val directories = initial.associateBy(PinnedDeviceDirectory::workspaceId).toMutableMap()

    init {
        require(directories.size == initial.size) { "Duplicate initial directory pins" }
    }

    override suspend fun load(workspaceId: String): PinnedDeviceDirectory? = mutex.withLock {
        directories[workspaceId]
    }

    override suspend fun compareAndSet(
        workspaceId: String,
        expected: DeviceDirectoryRevision?,
        updated: PinnedDeviceDirectory,
    ): Boolean = mutex.withLock {
        require(updated.workspaceId == workspaceId) { "Directory pin workspace mismatch" }
        val current = directories[workspaceId]
        if (current?.revision != expected) return@withLock false
        requireMonotonicPin(current, updated)
        directories[workspaceId] = updated
        true
    }

    override suspend fun clear(workspaceId: String) {
        mutex.withLock { directories.remove(workspaceId) }
    }
}

internal fun requireMonotonicPin(current: PinnedDeviceDirectory?, updated: PinnedDeviceDirectory) {
    if (current == null) return
    if (updated.version < current.version) {
        throw DeviceDirectoryTrustException.Rollback("Device directory version rolled back")
    }
    if (updated.version == current.version && updated.hash != current.hash) {
        throw DeviceDirectoryTrustException.Equivocation("Device directory hash changed without a version change")
    }
    if (updated.version == current.version && updated != current) {
        throw DeviceDirectoryTrustException.Equivocation("Pinned device directory changed at the same revision")
    }
}

internal fun String.isCanonicalSha256(): Boolean = isCanonicalBytes(32)

private fun String.isCanonicalBytes(expectedSize: Int): Boolean = runCatching {
    decodeBase64Url(this).size == expectedSize
}.getOrDefault(false)
