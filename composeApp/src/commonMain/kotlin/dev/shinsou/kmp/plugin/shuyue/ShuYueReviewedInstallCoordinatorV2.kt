package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.v2.ExtensionHostFacadeV2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Exact user decision consumed once by the reviewed installer. No field has a permissive default. */
public data class ShuYueReviewedInstallApprovalV2(
    val quarantineId: String,
    val identity: ShuYueArtifactIdentityV2,
    val grantedPermissions: Set<ShuYueExecutionPermissionV2>,
    val userConfirmed: Boolean,
    val replaceInstalledVersion: Boolean,
)

/** Durable intent needed to reconstruct an exact reviewed runtime after process restart. */
@Serializable
public data class ShuYueReviewedInstallationV2(
    val quarantineId: String,
    val identity: ShuYueArtifactIdentityV2,
) {
    init {
        require(quarantineId.isNotBlank() && quarantineId.length <= 1_024 &&
            quarantineId.none { it.isWhitespace() || it.isISOControl() }) {
            "Reviewed ShuYue quarantine id must be bounded and printable"
        }
    }
}

public interface ShuYueReviewedInstallationStoreV2 {
    public suspend fun listInstalled(): List<ShuYueReviewedInstallationV2>
    public suspend fun getInstalled(packageId: String): ShuYueReviewedInstallationV2?
    public suspend fun putInstalled(installation: ShuYueReviewedInstallationV2)
    public suspend fun removeInstalled(packageId: String)
}

public class InMemoryShuYueReviewedInstallationStoreV2 : ShuYueReviewedInstallationStoreV2 {
    private val mutex = Mutex()
    private val installations = linkedMapOf<String, ShuYueReviewedInstallationV2>()

    override suspend fun listInstalled(): List<ShuYueReviewedInstallationV2> = mutex.withLock {
        installations.values.sortedBy { it.identity.packageId }.toList()
    }

    override suspend fun getInstalled(packageId: String): ShuYueReviewedInstallationV2? =
        mutex.withLock { installations[packageId] }

    override suspend fun putInstalled(installation: ShuYueReviewedInstallationV2): Unit = mutex.withLock {
        installations[installation.identity.packageId] = installation
    }

    override suspend fun removeInstalled(packageId: String): Unit = mutex.withLock {
        installations.remove(packageId)
    }
}

public data class ShuYueReviewedRehydrateResultV2(
    val installedPackageIds: List<String>,
    val blockedPackageIds: List<String>,
)

/**
 * Explicit stage -> review -> approve -> execute workflow shared by repository and backup imports.
 * Stage/review never calls PluginManager or the runtime factory. Backup provenance receives no
 * shortcut: it must present the same exact-digest prompt and explicit approval as a download.
 */
public class ShuYueReviewedInstallCoordinatorV2(
    private val admission: ShuYueReviewedPluginAdmissionV2,
    private val approvals: ShuYueExecutionApprovalStoreV2,
    private val manager: PluginManager,
    private val installations: ShuYueReviewedInstallationStoreV2 =
        InMemoryShuYueReviewedInstallationStoreV2(),
) {
    public suspend fun stage(download: ShuYueScriptDownload): ShuYueQuarantineReviewV2 {
        val staged = admission.quarantine(download)
        return admission.inspectQuarantine(staged.quarantineId)
    }

    public suspend fun stage(candidate: ShuYueScriptCandidateV2): ShuYueQuarantineReviewV2 {
        val staged = admission.quarantine(candidate)
        return admission.inspectQuarantine(staged.quarantineId)
    }

    public suspend fun review(quarantineId: String): ShuYueQuarantineReviewV2 =
        admission.inspectQuarantine(quarantineId)

    public suspend fun approveAndInstall(
        decision: ShuYueReviewedInstallApprovalV2,
    ): ExtensionHostFacadeV2 {
        require(decision.userConfirmed) { "Reviewed ShuYue installation requires explicit user confirmation" }
        val review = admission.inspectQuarantine(decision.quarantineId)
        require(review.identity == decision.identity) { "ShuYue approval identity changed after review" }
        require(review.reviewStatus == ShuYueReviewStatusV2.REVIEWED) {
            "Only an exact reviewed ShuYue artifact can be approved"
        }
        require(decision.grantedPermissions == review.requiredPermissions) {
            "ShuYue approval must match the reviewed least-privilege permission set"
        }
        val previous = installations.getInstalled(review.identity.packageId)
        require(
            previous == null || previous.identity == review.identity || decision.replaceInstalledVersion,
        ) { "A different reviewed ShuYue version is already installed" }
        val wasTrusted = approvals.isTrusted(review.identity)
        val previousPermissions = approvals.grantedPermissions(review.identity)
        return try {
            approvals.approve(review.identity, decision.grantedPermissions)
            val facade = manager.installReviewedShuYueRuntimeV2(
                admission = admission,
                quarantineId = review.quarantineId,
                replace = decision.replaceInstalledVersion,
            )
            try {
                installations.putInstalled(
                    ShuYueReviewedInstallationV2(review.quarantineId, review.identity),
                )
            } catch (error: Throwable) {
                manager.uninstallExtensionRuntimeV2(review.identity.packageId)
                throw error
            }
            facade
        } catch (error: Throwable) {
            if (wasTrusted) {
                approvals.approve(review.identity, previousPermissions)
            } else {
                approvals.revoke(review.identity)
            }
            throw error
        }
    }

    /**
     * Reconstructs only exact approved artifacts. One corrupt/revoked marker is isolated from
     * siblings and remains durable for an explicit user retry or removal.
     */
    public suspend fun rehydrateInstalled(): ShuYueReviewedRehydrateResultV2 {
        val installed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        installations.listInstalled().forEach { installation ->
            try {
                val review = admission.inspectQuarantine(installation.quarantineId)
                require(review.identity == installation.identity) {
                    "Reviewed ShuYue installation identity changed"
                }
                require(review.reviewStatus == ShuYueReviewStatusV2.REVIEWED) {
                    "Reviewed ShuYue installation is no longer reviewed"
                }
                // Compatibility-only packages remain durable for migration/uninstall, but a
                // stopped source must never be evaluated again after an app restart.
                if (ShuYueReviewedPluginCatalogV2.find(installation.identity)?.legacyCompatibilityOnly == true) {
                    manager.uninstallExtensionRuntimeV2(installation.identity.packageId)
                    blocked += installation.identity.packageId
                    return@forEach
                }
                manager.installReviewedShuYueRuntimeV2(
                    admission = admission,
                    quarantineId = installation.quarantineId,
                    replace = true,
                )
                installed += installation.identity.packageId
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                manager.uninstallExtensionRuntimeV2(installation.identity.packageId)
                blocked += installation.identity.packageId
            }
        }
        return ShuYueReviewedRehydrateResultV2(installed.sorted(), blocked.sorted())
    }

    public suspend fun installed(packageId: String): ShuYueReviewedInstallationV2? =
        installations.getInstalled(packageId)

    public suspend fun revokeAndUnload(identity: ShuYueArtifactIdentityV2): Boolean {
        // Revoke first: GuardedRuntime observes this before any potentially delayed unload.
        approvals.revoke(identity)
        val unloaded = manager.uninstallExtensionRuntimeV2(identity.packageId)
        installations.removeInstalled(identity.packageId)
        return unloaded
    }
}

/** Durable exact-version quarantine and approvals over the application's platform KV boundary. */
public class KeyValueShuYueReviewedStoreV2(
    private val keyValueStore: PluginKeyValueStore,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) : ShuYueScriptQuarantineStoreV2,
    ShuYueExecutionApprovalStoreV2,
    ShuYueReviewedInstallationStoreV2 {
    private val mutex = Mutex()

    override suspend fun put(record: ShuYueQuarantinedScriptV2): Unit = mutex.withLock {
        val key = quarantineKey(record.quarantineId)
        val encoded = encode(record)
        val previous = keyValueStore.getString(key)
        require(previous == null || previous == encoded) {
            "Quarantine id conflicts with different durable ShuYue bytes"
        }
        keyValueStore.putString(key, encoded)
    }

    override suspend fun get(quarantineId: String): ShuYueQuarantinedScriptV2? = mutex.withLock {
        val encoded = keyValueStore.getString(quarantineKey(quarantineId)) ?: return@withLock null
        val decoded = decode(encoded)
        if (decoded.quarantineId != quarantineId) {
            throw ShuYueAdmissionException.CorruptQuarantine()
        }
        decoded
    }

    override suspend fun approve(
        identity: ShuYueArtifactIdentityV2,
        permissions: Set<ShuYueExecutionPermissionV2>,
    ): Unit = mutex.withLock {
        val identityKey = approvalIdentityKey(identity)
        val encodedPermissions = json.encodeToString(
            ListSerializer(String.serializer()),
            permissions.map(Enum<*>::name).sorted(),
        )
        // Permissions are written first. A crash can therefore leave only an inert permission
        // proposal; trust is the final commit marker read by admission.
        keyValueStore.putString("$APPROVAL_PREFIX.$identityKey.permissions", encodedPermissions)
        keyValueStore.putString("$APPROVAL_PREFIX.$identityKey.trust", "true")
    }

    override suspend fun revoke(identity: ShuYueArtifactIdentityV2): Unit = mutex.withLock {
        val identityKey = approvalIdentityKey(identity)
        // Trust is removed first so a partial failure remains fail-closed.
        keyValueStore.remove("$APPROVAL_PREFIX.$identityKey.trust")
        keyValueStore.remove("$APPROVAL_PREFIX.$identityKey.permissions")
    }

    override suspend fun isTrusted(identity: ShuYueArtifactIdentityV2): Boolean = mutex.withLock {
        keyValueStore.getString("$APPROVAL_PREFIX.${approvalIdentityKey(identity)}.trust") == "true"
    }

    override suspend fun grantedPermissions(
        identity: ShuYueArtifactIdentityV2,
    ): Set<ShuYueExecutionPermissionV2> = mutex.withLock {
        val encoded = keyValueStore.getString(
            "$APPROVAL_PREFIX.${approvalIdentityKey(identity)}.permissions",
        ) ?: return@withLock emptySet()
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), encoded)
                .map(::decodeExecutionPermission)
                .toSet()
        }.getOrDefault(emptySet())
    }

    override suspend fun listInstalled(): List<ShuYueReviewedInstallationV2> = mutex.withLock {
        readInstallations().values.sortedBy { it.identity.packageId }
    }

    override suspend fun getInstalled(packageId: String): ShuYueReviewedInstallationV2? = mutex.withLock {
        readInstallations()[packageId]
    }

    override suspend fun putInstalled(installation: ShuYueReviewedInstallationV2): Unit = mutex.withLock {
        val values = readInstallations().toMutableMap()
        values[installation.identity.packageId] = installation
        writeInstallations(values)
    }

    override suspend fun removeInstalled(packageId: String): Unit = mutex.withLock {
        val values = readInstallations().toMutableMap()
        if (values.remove(packageId) != null) writeInstallations(values)
    }

    private suspend fun readInstallations(): Map<String, ShuYueReviewedInstallationV2> {
        val encoded = keyValueStore.getString(INSTALLATIONS_KEY) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(
                ListSerializer(ShuYueReviewedInstallationV2.serializer()),
                encoded,
            ).associateBy { installation -> installation.identity.packageId }
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeInstallations(values: Map<String, ShuYueReviewedInstallationV2>) {
        keyValueStore.putString(
            INSTALLATIONS_KEY,
            json.encodeToString(
                ListSerializer(ShuYueReviewedInstallationV2.serializer()),
                values.values.sortedBy { it.identity.packageId },
            ),
        )
    }

    private fun encode(record: ShuYueQuarantinedScriptV2): String = json.encodeToString(
        StoredQuarantineV2.serializer(),
        StoredQuarantineV2(
            quarantineId = record.quarantineId,
            packageId = record.identity.packageId,
            version = record.identity.version,
            versionCode = record.identity.versionCode,
            sha256 = record.identity.sha256,
            sourceIds = record.sourceIds,
            scriptHex = record.copyBytes().toHex(),
            provenance = record.provenance.name,
            reviewStatus = record.stagedReviewStatus.name,
        ),
    )

    private fun decode(encoded: String): ShuYueQuarantinedScriptV2 {
        return try {
            val stored = json.decodeFromString(StoredQuarantineV2.serializer(), encoded)
            val bytes = stored.scriptHex.hexToBytes()
            val identity = ShuYueArtifactIdentityV2(
                stored.packageId,
                stored.version,
                stored.versionCode,
                stored.sha256,
            )
            require(Sha256.hex(bytes) == identity.sha256) { "Durable ShuYue quarantine digest changed" }
            ShuYueQuarantinedScriptV2(
                quarantineId = stored.quarantineId,
                identity = identity,
                sourceIds = stored.sourceIds,
                bytes = bytes,
                provenance = decodeProvenance(stored.provenance),
                stagedReviewStatus = decodeReviewStatus(stored.reviewStatus),
            )
        } catch (_: Exception) {
            // Quarantined bytes and parser diagnostics are deliberately not returned to the UI.
            // A damaged record remains fail-closed, but is distinct from a genuinely absent one.
            throw ShuYueAdmissionException.CorruptQuarantine()
        }
    }

    private fun decodeExecutionPermission(encoded: String): ShuYueExecutionPermissionV2 = when (encoded) {
        "EXECUTE_SCRIPT" -> ShuYueExecutionPermissionV2.EXECUTE_SCRIPT
        "NETWORK" -> ShuYueExecutionPermissionV2.NETWORK
        "COOKIE_STORAGE" -> ShuYueExecutionPermissionV2.COOKIE_STORAGE
        "CREDENTIAL_ACCESS" -> ShuYueExecutionPermissionV2.CREDENTIAL_ACCESS
        "LOGIN_PROMPT" -> ShuYueExecutionPermissionV2.LOGIN_PROMPT
        "FAVORITE_MUTATION" -> ShuYueExecutionPermissionV2.FAVORITE_MUTATION
        "BROWSER_CHALLENGE" -> ShuYueExecutionPermissionV2.BROWSER_CHALLENGE
        else -> throw IllegalArgumentException("Unknown durable ShuYue execution permission")
    }

    private fun decodeProvenance(encoded: String): ShuYueScriptProvenanceV2 = when (encoded) {
        "REVIEWED_REPOSITORY" -> ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY
        "LEGACY_BACKUP" -> ShuYueScriptProvenanceV2.LEGACY_BACKUP
        else -> throw IllegalArgumentException("Unknown durable ShuYue provenance")
    }

    private fun decodeReviewStatus(encoded: String): ShuYueReviewStatusV2 = when (encoded) {
        "REVIEWED" -> ShuYueReviewStatusV2.REVIEWED
        "UNKNOWN_PACKAGE" -> ShuYueReviewStatusV2.UNKNOWN_PACKAGE
        "UNREVIEWED_VERSION" -> ShuYueReviewStatusV2.UNREVIEWED_VERSION
        "DIGEST_MISMATCH" -> ShuYueReviewStatusV2.DIGEST_MISMATCH
        "SOURCE_ID_MISMATCH" -> ShuYueReviewStatusV2.SOURCE_ID_MISMATCH
        else -> throw IllegalArgumentException("Unknown durable ShuYue review status")
    }

    private fun quarantineKey(quarantineId: String): String =
        "$QUARANTINE_PREFIX.${Sha256.hex(quarantineId.encodeToByteArray())}"

    private fun approvalIdentityKey(identity: ShuYueArtifactIdentityV2): String = Sha256.hex(
        listOf(
            identity.packageId,
            identity.version,
            identity.versionCode.toString(),
            identity.sha256,
        ).joinToString("|") { value -> "${value.encodeToByteArray().size}:$value" }.encodeToByteArray(),
    )

    @Serializable
    private data class StoredQuarantineV2(
        val quarantineId: String,
        val packageId: String,
        val version: String,
        val versionCode: Int,
        val sha256: String,
        val sourceIds: List<String>,
        val scriptHex: String,
        val provenance: String,
        val reviewStatus: String,
    )

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0f]
        }
        return result.concatToString()
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) {
            "Durable ShuYue quarantine body is not canonical hex"
        }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val QUARANTINE_PREFIX: String = "plugin.shuyue.v2.quarantine"
        const val APPROVAL_PREFIX: String = "plugin.shuyue.v2.approval"
        const val INSTALLATIONS_KEY: String = "plugin.shuyue.v2.installations"
    }
}
