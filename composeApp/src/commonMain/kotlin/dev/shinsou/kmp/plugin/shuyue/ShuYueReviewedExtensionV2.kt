@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginSystemEventNames
import dev.shinsou.kmp.plugin.v2.BrowseOptionsSchemaV2
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.CloseableExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionHostFacadeV2
import dev.shinsou.kmp.plugin.v2.ExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ExtensionPackageV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.UserInteractionScopedExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.SourceLifecycleControlledExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginResultV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.SourceDescriptorV2
import dev.shinsou.kmp.plugin.v2.TextChunkResultV2
import dev.shinsou.kmp.plugin.v2.TextChunkStreamV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Sensitive host facilities a reviewed ShuYue script must be explicitly granted. */
public enum class ShuYueExecutionPermissionV2 {
    EXECUTE_SCRIPT,
    NETWORK,
    COOKIE_STORAGE,
    CREDENTIAL_ACCESS,
    LOGIN_PROMPT,
    FAVORITE_MUTATION,
    BROWSER_CHALLENGE,
}

/** Trust and permission keys are exact-version and exact-digest; updates never inherit grants. */
@Serializable
public data class ShuYueArtifactIdentityV2(
    val packageId: String,
    val version: String,
    val versionCode: Int,
    val sha256: String,
) {
    init {
        requireAdmissionId(packageId, "ShuYue package id")
        requireAdmissionId(version, "ShuYue package version")
        require(versionCode > 0) { "ShuYue version code must be positive" }
        require(SHA256.matches(sha256)) { "ShuYue digest must be lowercase SHA-256" }
    }
}

public enum class ShuYueReviewStatusV2 {
    REVIEWED,
    UNKNOWN_PACKAGE,
    UNREVIEWED_VERSION,
    DIGEST_MISMATCH,
    SOURCE_ID_MISMATCH,
}

public enum class ShuYueScriptProvenanceV2 {
    REVIEWED_REPOSITORY,
    LEGACY_BACKUP,
}

/** Bounded inert input usable by repository download and future backup-import code. */
public class ShuYueScriptCandidateV2(
    public val packageId: String,
    public val version: String,
    public val versionCode: Int,
    sourceIds: List<String>,
    bytes: ByteArray,
    public val provenance: ShuYueScriptProvenanceV2,
    public val reportedSha256: String? = null,
) {
    public val sourceIds: List<String> = sourceIds.toList()
    private val backingBytes: ByteArray = bytes.copyOf()

    init {
        requireAdmissionId(packageId, "ShuYue package id")
        requireAdmissionId(version, "ShuYue package version")
        require(versionCode > 0) { "ShuYue version code must be positive" }
        require(this.sourceIds.isNotEmpty() && this.sourceIds.size <= MAX_SHUYUE_SOURCES) {
            "ShuYue candidate needs a bounded source list"
        }
        require(this.sourceIds.distinct().size == this.sourceIds.size) { "Duplicate ShuYue source id" }
        this.sourceIds.forEach { requireAdmissionId(it, "ShuYue source id") }
        require(backingBytes.isNotEmpty() && backingBytes.size <= MAX_SHUYUE_SCRIPT_BYTES) {
            "ShuYue script is outside the quarantine byte bound"
        }
        reportedSha256?.let { require(SHA256.matches(it)) { "Reported ShuYue digest is invalid" } }
    }

    public fun copyBytes(): ByteArray = backingBytes.copyOf()

    public companion object {
        public fun from(download: ShuYueScriptDownload): ShuYueScriptCandidateV2 =
            ShuYueScriptCandidateV2(
                packageId = download.metadata.packageId,
                version = download.metadata.version,
                versionCode = download.metadata.versionCode,
                sourceIds = download.metadata.sourceIds,
                bytes = download.copyBytes(),
                provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
                reportedSha256 = download.sha256,
            )
    }
}

/** Stored quarantine record; bytes remain defensive and are re-hashed at every admission. */
public class ShuYueQuarantinedScriptV2(
    public val quarantineId: String,
    public val identity: ShuYueArtifactIdentityV2,
    sourceIds: List<String>,
    bytes: ByteArray,
    public val provenance: ShuYueScriptProvenanceV2,
    public val stagedReviewStatus: ShuYueReviewStatusV2,
) {
    public val sourceIds: List<String> = sourceIds.toList()
    private val backingBytes: ByteArray = bytes.copyOf()

    init {
        requireAdmissionId(quarantineId, "ShuYue quarantine id")
        require(this.sourceIds.isNotEmpty() && this.sourceIds.distinct().size == this.sourceIds.size)
        require(backingBytes.isNotEmpty() && backingBytes.size <= MAX_SHUYUE_SCRIPT_BYTES)
    }

    public fun copyBytes(): ByteArray = backingBytes.copyOf()

    internal fun defensiveCopy(): ShuYueQuarantinedScriptV2 = ShuYueQuarantinedScriptV2(
        quarantineId,
        identity,
        sourceIds,
        backingBytes,
        provenance,
        stagedReviewStatus,
    )
}

public data class ShuYueQuarantineResultV2(
    val quarantineId: String,
    val identity: ShuYueArtifactIdentityV2,
    val reviewStatus: ShuYueReviewStatusV2,
)

/** Body-free review prompt. Merely inspecting this value can never evaluate quarantined bytes. */
public data class ShuYueQuarantineReviewV2(
    val quarantineId: String,
    val identity: ShuYueArtifactIdentityV2,
    val sourceIds: List<String>,
    val provenance: ShuYueScriptProvenanceV2,
    val reviewStatus: ShuYueReviewStatusV2,
    val requiredPermissions: Set<ShuYueExecutionPermissionV2>,
)

public interface ShuYueScriptQuarantineStoreV2 {
    public suspend fun put(record: ShuYueQuarantinedScriptV2)
    public suspend fun get(quarantineId: String): ShuYueQuarantinedScriptV2?
}

public class InMemoryShuYueScriptQuarantineStoreV2 : ShuYueScriptQuarantineStoreV2 {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, ShuYueQuarantinedScriptV2>()

    override suspend fun put(record: ShuYueQuarantinedScriptV2) {
        mutex.withLock {
            val previous = records[record.quarantineId]
            require(previous == null || previous.identity == record.identity &&
                previous.sourceIds == record.sourceIds &&
                previous.stagedReviewStatus == record.stagedReviewStatus &&
                previous.copyBytes().contentEquals(record.copyBytes())) {
                "Quarantine id conflicts with different ShuYue bytes"
            }
            records[record.quarantineId] = record.defensiveCopy()
        }
    }

    override suspend fun get(quarantineId: String): ShuYueQuarantinedScriptV2? =
        mutex.withLock { records[quarantineId]?.defensiveCopy() }
}

public fun interface ShuYueScriptTrustStoreV2 {
    public suspend fun isTrusted(identity: ShuYueArtifactIdentityV2): Boolean
}

public fun interface ShuYueScriptPermissionStoreV2 {
    public suspend fun grantedPermissions(identity: ShuYueArtifactIdentityV2): Set<ShuYueExecutionPermissionV2>
}

/** Explicit mutable approval boundary; admission itself receives only the read-only interfaces. */
public interface ShuYueExecutionApprovalStoreV2 :
    ShuYueScriptTrustStoreV2,
    ShuYueScriptPermissionStoreV2 {
    public suspend fun approve(
        identity: ShuYueArtifactIdentityV2,
        permissions: Set<ShuYueExecutionPermissionV2>,
    )

    public suspend fun revoke(identity: ShuYueArtifactIdentityV2)
}

/** Mutable in-memory approval stores are test/local seams; production can use a strict secret DB. */
public class InMemoryShuYueExecutionApprovalsV2 : ShuYueExecutionApprovalStoreV2 {
    private val mutex = Mutex()
    private val trusted = mutableSetOf<ShuYueArtifactIdentityV2>()
    private val permissions = mutableMapOf<ShuYueArtifactIdentityV2, Set<ShuYueExecutionPermissionV2>>()

    public suspend fun trust(identity: ShuYueArtifactIdentityV2) {
        mutex.withLock { trusted += identity }
    }

    public suspend fun revokeTrust(identity: ShuYueArtifactIdentityV2) {
        mutex.withLock { trusted -= identity }
    }

    public suspend fun grant(
        identity: ShuYueArtifactIdentityV2,
        granted: Set<ShuYueExecutionPermissionV2>,
    ) {
        mutex.withLock { permissions[identity] = granted.toSet() }
    }

    public suspend fun revokePermissions(identity: ShuYueArtifactIdentityV2) {
        mutex.withLock { permissions.remove(identity) }
    }

    override suspend fun approve(
        identity: ShuYueArtifactIdentityV2,
        permissions: Set<ShuYueExecutionPermissionV2>,
    ) {
        mutex.withLock {
            this.permissions[identity] = permissions.toSet()
            trusted += identity
        }
    }

    override suspend fun revoke(identity: ShuYueArtifactIdentityV2) {
        mutex.withLock {
            trusted -= identity
            permissions.remove(identity)
        }
    }

    override suspend fun isTrusted(identity: ShuYueArtifactIdentityV2): Boolean =
        mutex.withLock { identity in trusted }

    override suspend fun grantedPermissions(identity: ShuYueArtifactIdentityV2): Set<ShuYueExecutionPermissionV2> =
        mutex.withLock { permissions[identity].orEmpty().toSet() }
}

/** Exact reviewed metadata and digest pinned in the repository provenance fixture. */
public class ShuYueReviewedPluginProfileV2(
    public val identity: ShuYueArtifactIdentityV2,
    public val displayName: String,
    public val sourceId: String,
    public val sourceName: String,
    public val languageTag: String,
    public val baseUrl: String,
    capabilities: Set<ExtensionCapability>,
    requiredPermissions: Set<ShuYueExecutionPermissionV2>,
    /**
     * Compatibility-only packages stay in the exact reviewed catalogue so an existing install
     * can be rehydrated and migrated, but must never be offered as a new source. ShuYue's old
     * Wenku8 HTML script is the first such package; the maintained relay is a separate identity.
    */
    public val legacyCompatibilityOnly: Boolean = false,
    /** V2-index-only pin; it must never be selected for a legacy index without a digest. */
    public val v2IndexOnly: Boolean = false,
    public val systemEvents: PluginSystemEventDeclaration? = null,
) {
    public val capabilities: Set<ExtensionCapability> = capabilities.toSet()
    public val requiredPermissions: Set<ShuYueExecutionPermissionV2> = requiredPermissions.toSet()

    init {
        requireAdmissionText(displayName, "Reviewed ShuYue display name")
        requireAdmissionId(sourceId, "Reviewed ShuYue source id")
        requireAdmissionText(sourceName, "Reviewed ShuYue source name")
        requireAdmissionId(languageTag, "Reviewed ShuYue language")
        require(ShuYueExecutionPermissionV2.EXECUTE_SCRIPT in this.requiredPermissions) {
            "Reviewed ShuYue profile must require explicit script execution"
        }
        require(ShuYueExecutionPermissionV2.NETWORK in this.requiredPermissions) {
            "Reviewed ShuYue remote source must require network permission"
        }
        if (ExtensionCapability.LOGIN in this.capabilities) {
            require(
                setOf(
                    ShuYueExecutionPermissionV2.CREDENTIAL_ACCESS,
                    ShuYueExecutionPermissionV2.COOKIE_STORAGE,
                    ShuYueExecutionPermissionV2.LOGIN_PROMPT,
                ).all { it in this.requiredPermissions },
            ) { "Reviewed ShuYue login source is missing sensitive permissions" }
        }
        if (ExtensionCapability.FAVORITE in this.capabilities) {
            require(ShuYueExecutionPermissionV2.FAVORITE_MUTATION in this.requiredPermissions) {
                "Reviewed ShuYue favorite source is missing mutation permission"
            }
        }
    }

    public val descriptor: ExtensionPackageV2
        get() {
            val source = SourceDescriptorV2(
                sourceKey = SourceKey(2, identity.packageId, sourceId),
                displayName = sourceName,
                languageTag = languageTag,
                supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
                capabilities = capabilities + ExtensionCapability.CONTENT,
                baseUrl = baseUrl,
            )
            return ExtensionPackageV2(
                contractVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                packageId = identity.packageId,
                version = identity.version,
                displayName = displayName,
                sources = listOf(source),
                supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
            )
        }
}

public object ShuYueReviewedPluginCatalogV2 {
    public val profiles: List<ShuYueReviewedPluginProfileV2> = listOf(
        profile(
            packageId = "zh.wenku8",
            version = "1.6.14",
            versionCode = 32,
            sha256 = "5536b392476d59000770a15e2f759c3fb5f5d51b551c03ae42182c7eb5610b9e",
            displayName = "輕小說文庫（停止維護）",
            sourceName = "輕小說文庫（停止維護）",
            baseUrl = "https://www.wenku8.net",
            login = true,
            favorite = true,
            browserChallenge = true,
            legacyCompatibilityOnly = true,
        ),
        profile(
            packageId = "zh.wenku8.api",
            version = "1.0.4",
            versionCode = 5,
            sha256 = "aaa7875360a52dd3393288bbb4f1e85d38ddd6a42041a0e489d7585db8bb5996",
            displayName = "輕小說文庫",
            sourceName = "輕小說文庫",
            baseUrl = "https://wenku8-relay.mewx.org/",
            login = true,
            favorite = true,
            browserChallenge = false,
        ),
        profile(
            packageId = "zh.wenku8.api",
            version = "1.0.4",
            versionCode = 5,
            sha256 = "5a9d1ac0d8263629e82332a88b2a7ed4eb6efb857804a8ae6ae946b2eb23b627",
            displayName = "輕小說文庫",
            sourceName = "輕小說文庫",
            baseUrl = "https://wenku8-relay.mewx.org/",
            login = true,
            favorite = true,
            browserChallenge = false,
            v2IndexOnly = true,
        ),
        profile(
            packageId = "zh.biquge.tw",
            version = "1.0.3",
            versionCode = 4,
            sha256 = "74a961995aae9bef40444a819011e3b7702fcce6ce179fbd8e1ff6c733468303",
            displayName = "筆趣閣",
            sourceName = "筆趣閣",
            baseUrl = "https://www.biquge.tw",
            login = false,
            favorite = false,
            browserChallenge = true,
        ),
        profile(
            packageId = "zh.biquge.tw",
            version = "1.0.3",
            versionCode = 4,
            sha256 = "9320a3204fc2dbf88a20c74a8a8fff9723536f0cb32a0dc56763db67be96f2d6",
            displayName = "筆趣閣",
            sourceName = "筆趣閣",
            baseUrl = "https://www.biquge.tw",
            login = false,
            favorite = false,
            browserChallenge = true,
            v2IndexOnly = true,
        ),
        profile(
            packageId = "zh.wenku8",
            version = "1.6.14",
            versionCode = 32,
            sha256 = "a6c1f21f94337249403b8d165d1ebe31e2c7534d62ca2b92fc5bd3f3c5bccfd1",
            displayName = "輕小說文庫（停止維護）",
            sourceName = "輕小說文庫（停止維護）",
            baseUrl = "https://www.wenku8.net",
            login = true,
            favorite = true,
            browserChallenge = true,
            legacyCompatibilityOnly = true,
            v2IndexOnly = true,
        ),
    )

    public fun find(identity: ShuYueArtifactIdentityV2): ShuYueReviewedPluginProfileV2? =
        profiles.singleOrNull { it.identity == identity }

    /** Packages safe to display/install from a repository for a new user. */
    public val installableProfiles: List<ShuYueReviewedPluginProfileV2>
        get() = profiles.filterNot(ShuYueReviewedPluginProfileV2::legacyCompatibilityOnly)

    /**
     * Resolves the opaque source identity stored by ShuYue v1 to the exact reviewed v2 key.
     *
     * Package identity was not stored beside a ShuYue book, so only a source id that maps to one
     * unambiguous reviewed package can be upgraded. Unknown or ambiguous ids deliberately return
     * null and remain on the inert compatibility package during migration.
     */
    public fun sourceKeyForLegacySourceId(sourceId: String): SourceKey? {
        requireAdmissionId(sourceId, "Legacy ShuYue source id")
        return profiles.asSequence()
            .filter { it.sourceId == sourceId }
            .map { it.descriptor.sources.single().sourceKey }
            .distinct()
            .toList()
            .singleOrNull()
    }

    /** Resolves repository metadata to the exact reviewed digest when V2 supplies one. */
    public fun findRepositoryProfile(
        packageId: String,
        version: String,
        versionCode: Int,
        sha256: String?,
    ): ShuYueReviewedPluginProfileV2? {
        val candidates = installableProfiles.filter { profile ->
            profile.identity.packageId == packageId &&
                profile.identity.version == version &&
                profile.identity.versionCode == versionCode
        }
        return if (sha256 != null) {
            candidates.singleOrNull { it.identity.sha256 == sha256 }
        } else {
            // Legacy indexes did not carry a digest. Only an explicitly legacy-compatible
            // record may satisfy one; V2-only pins must never inherit its grants.
            candidates.singleOrNull { !it.v2IndexOnly }
        }
    }

    internal fun review(
        identity: ShuYueArtifactIdentityV2,
        sourceIds: List<String>,
        reportedDigestMatches: Boolean,
    ): ShuYueReviewStatusV2 {
        if (!reportedDigestMatches) return ShuYueReviewStatusV2.DIGEST_MISMATCH
        val packageProfiles = profiles.filter { it.identity.packageId == identity.packageId }
        if (packageProfiles.isEmpty()) return ShuYueReviewStatusV2.UNKNOWN_PACKAGE
        val versions = packageProfiles.filter {
            it.identity.version == identity.version && it.identity.versionCode == identity.versionCode
        }
        if (versions.isEmpty()) return ShuYueReviewStatusV2.UNREVIEWED_VERSION
        val version = versions.singleOrNull { it.identity.sha256 == identity.sha256 }
            ?: return ShuYueReviewStatusV2.DIGEST_MISMATCH
        if (sourceIds != listOf(version.sourceId)) return ShuYueReviewStatusV2.SOURCE_ID_MISMATCH
        return ShuYueReviewStatusV2.REVIEWED
    }

    private fun profile(
        packageId: String,
        version: String,
        versionCode: Int,
        sha256: String,
        displayName: String,
        sourceName: String,
        baseUrl: String,
        login: Boolean,
        favorite: Boolean,
        browserChallenge: Boolean,
        legacyCompatibilityOnly: Boolean = false,
        v2IndexOnly: Boolean = false,
        systemEvents: PluginSystemEventDeclaration? = if (login) {
            PluginSystemEventDeclaration(
                minVersion = 1,
                maxVersion = 1,
                required = setOf(PluginSystemEventNames.LOGIN_CAPABILITY),
            )
        } else {
            null
        },
    ): ShuYueReviewedPluginProfileV2 {
        val capabilities = buildSet {
            add(ExtensionCapability.BROWSE)
            add(ExtensionCapability.SEARCH)
            add(ExtensionCapability.LATEST)
            add(ExtensionCapability.METADATA)
            add(ExtensionCapability.UNITS)
            add(ExtensionCapability.CONTENT)
            if (login) add(ExtensionCapability.LOGIN)
            if (favorite) add(ExtensionCapability.FAVORITE)
        }
        val permissions = buildSet {
            add(ShuYueExecutionPermissionV2.EXECUTE_SCRIPT)
            add(ShuYueExecutionPermissionV2.NETWORK)
            if (login) {
                add(ShuYueExecutionPermissionV2.COOKIE_STORAGE)
                add(ShuYueExecutionPermissionV2.CREDENTIAL_ACCESS)
                add(ShuYueExecutionPermissionV2.LOGIN_PROMPT)
            }
            if (favorite) add(ShuYueExecutionPermissionV2.FAVORITE_MUTATION)
            if (browserChallenge) add(ShuYueExecutionPermissionV2.BROWSER_CHALLENGE)
        }
        return ShuYueReviewedPluginProfileV2(
            identity = ShuYueArtifactIdentityV2(packageId, version, versionCode, sha256),
            displayName = displayName,
            sourceId = packageId,
            sourceName = sourceName,
            languageTag = "zh",
            baseUrl = baseUrl,
            capabilities = capabilities,
            requiredPermissions = permissions,
            legacyCompatibilityOnly = legacyCompatibilityOnly,
            v2IndexOnly = v2IndexOnly,
            systemEvents = systemEvents,
        )
    }
}

/** Only an admitted artifact exposes script bytes to the trusted platform runtime factory. */
public sealed interface ShuYueAdmittedScriptV2 {
    public val identity: ShuYueArtifactIdentityV2
    public val descriptor: ExtensionPackageV2
    public val grantedPermissions: Set<ShuYueExecutionPermissionV2>
    public val systemEvents: PluginSystemEventDeclaration?
    public fun copyBytes(): ByteArray
}

public fun interface ShuYueReviewedRuntimeFactoryV2 {
    public suspend fun create(artifact: ShuYueAdmittedScriptV2): ExtensionPackageRuntimeV2
}

public sealed class ShuYueAdmissionException(message: String) : IllegalStateException(message) {
    public class MissingQuarantine : ShuYueAdmissionException("ShuYue script is not in quarantine")
    public class NotReviewed(public val status: ShuYueReviewStatusV2) :
        ShuYueAdmissionException("ShuYue script is not reviewed: $status")
    public class DigestChanged : ShuYueAdmissionException("ShuYue quarantine digest changed")
    public class NotTrusted : ShuYueAdmissionException("ShuYue script has no current user trust grant")
    public class MissingPermissions(public val missing: Set<ShuYueExecutionPermissionV2>) :
        ShuYueAdmissionException("ShuYue script is missing execution permissions: $missing")
    public class RuntimeMismatch : ShuYueAdmissionException("ShuYue runtime descriptor does not match reviewed metadata")
}

/**
 * Quarantine/admission boundary shared by repository install and future transactional migration.
 * Decode, stage, report and trust operations never call [runtimeFactory].
 */
public class ShuYueReviewedPluginAdmissionV2(
    private val quarantineStore: ShuYueScriptQuarantineStoreV2,
    private val trustStore: ShuYueScriptTrustStoreV2,
    private val permissionStore: ShuYueScriptPermissionStoreV2,
    private val runtimeFactory: ShuYueReviewedRuntimeFactoryV2,
    reviewedProfiles: List<ShuYueReviewedPluginProfileV2> = ShuYueReviewedPluginCatalogV2.profiles,
) {
    private val reviewedProfiles: List<ShuYueReviewedPluginProfileV2> = reviewedProfiles.toList()

    init {
        require(this.reviewedProfiles.isNotEmpty()) { "Reviewed ShuYue profile catalogue is empty" }
        require(this.reviewedProfiles.map { it.identity }.distinct().size == this.reviewedProfiles.size) {
            "Reviewed ShuYue profile catalogue contains duplicate identities"
        }
        this.reviewedProfiles.forEach { it.descriptor.validate() }
    }

    public suspend fun quarantine(download: ShuYueScriptDownload): ShuYueQuarantineResultV2 =
        quarantine(ShuYueScriptCandidateV2.from(download))

    public suspend fun quarantine(candidate: ShuYueScriptCandidateV2): ShuYueQuarantineResultV2 {
        val bytes = candidate.copyBytes()
        val actualDigest = Sha256.hex(bytes)
        val identity = ShuYueArtifactIdentityV2(
            candidate.packageId,
            candidate.version,
            candidate.versionCode,
            actualDigest,
        )
        val reportedMatches = candidate.reportedSha256 == null || candidate.reportedSha256 == actualDigest
        val status = review(identity, candidate.sourceIds, reportedMatches)
        val quarantineId = "${candidate.packageId}-${candidate.versionCode}-$actualDigest"
        quarantineStore.put(
            ShuYueQuarantinedScriptV2(
                quarantineId = quarantineId,
                identity = identity,
                sourceIds = candidate.sourceIds,
                bytes = bytes,
                provenance = candidate.provenance,
                stagedReviewStatus = status,
            ),
        )
        return ShuYueQuarantineResultV2(quarantineId, identity, status)
    }

    /** Re-hashes and re-reviews inert bytes without consulting approvals or creating a runtime. */
    public suspend fun inspectQuarantine(quarantineId: String): ShuYueQuarantineReviewV2 {
        val record = quarantineStore.get(quarantineId) ?: throw ShuYueAdmissionException.MissingQuarantine()
        val actualDigest = Sha256.hex(record.copyBytes())
        if (actualDigest != record.identity.sha256) throw ShuYueAdmissionException.DigestChanged()
        val currentStatus = review(record.identity, record.sourceIds, reportedDigestMatches = true)
        val status = if (record.stagedReviewStatus == ShuYueReviewStatusV2.REVIEWED) {
            currentStatus
        } else {
            record.stagedReviewStatus
        }
        val profile = reviewedProfiles.singleOrNull { it.identity == record.identity }
        return ShuYueQuarantineReviewV2(
            quarantineId = record.quarantineId,
            identity = record.identity,
            sourceIds = record.sourceIds.toList(),
            provenance = record.provenance,
            reviewStatus = status,
            requiredPermissions = if (status == ShuYueReviewStatusV2.REVIEWED) {
                requireNotNull(profile).requiredPermissions.toSet()
            } else {
                emptySet()
            },
        )
    }

    /** Re-checks every gate before evaluating bytes, then returns a per-call guarded runtime. */
    public suspend fun createRuntime(quarantineId: String): ExtensionPackageRuntimeV2 {
        val admission = requireAdmitted(quarantineId)
        val artifact = AdmittedScript(admission.record, admission.profile, admission.permissions)
        val runtime = runtimeFactory.create(artifact)
        if (runtime.descriptor != admission.profile.descriptor) {
            closeRuntime(runtime)
            throw ShuYueAdmissionException.RuntimeMismatch()
        }
        val facade = ExtensionHostFacadeV2(runtime)
        if (runtime.descriptor.sources.any { facade.source(it.sourceKey) == null }) {
            closeRuntime(runtime)
            throw ShuYueAdmissionException.RuntimeMismatch()
        }
        return GuardedRuntime(runtime) {
            requireCurrentApproval(admission.record.identity, admission.profile)
        }
    }

    private suspend fun requireAdmitted(quarantineId: String): Admission {
        val record = quarantineStore.get(quarantineId) ?: throw ShuYueAdmissionException.MissingQuarantine()
        val actualDigest = Sha256.hex(record.copyBytes())
        if (actualDigest != record.identity.sha256) throw ShuYueAdmissionException.DigestChanged()
        if (record.stagedReviewStatus != ShuYueReviewStatusV2.REVIEWED) {
            throw ShuYueAdmissionException.NotReviewed(record.stagedReviewStatus)
        }
        val status = review(record.identity, record.sourceIds, true)
        if (status != ShuYueReviewStatusV2.REVIEWED) throw ShuYueAdmissionException.NotReviewed(status)
        val profile = requireNotNull(reviewedProfiles.singleOrNull { it.identity == record.identity })
        val permissions = requireCurrentApproval(record.identity, profile)
        return Admission(record, profile, permissions.toSet())
    }

    private suspend fun requireCurrentApproval(
        identity: ShuYueArtifactIdentityV2,
        profile: ShuYueReviewedPluginProfileV2,
    ): Set<ShuYueExecutionPermissionV2> {
        if (!trustStore.isTrusted(identity)) throw ShuYueAdmissionException.NotTrusted()
        val permissions = permissionStore.grantedPermissions(identity)
        val missing = profile.requiredPermissions - permissions
        if (missing.isNotEmpty()) throw ShuYueAdmissionException.MissingPermissions(missing)
        return permissions.toSet()
    }

    private fun review(
        identity: ShuYueArtifactIdentityV2,
        sourceIds: List<String>,
        reportedDigestMatches: Boolean,
    ): ShuYueReviewStatusV2 {
        if (!reportedDigestMatches) return ShuYueReviewStatusV2.DIGEST_MISMATCH
        val packageProfiles = reviewedProfiles.filter { it.identity.packageId == identity.packageId }
        if (packageProfiles.isEmpty()) return ShuYueReviewStatusV2.UNKNOWN_PACKAGE
        // A V2 repository may publish multiple reviewed artifacts for the same package/version
        // (for example a legacy index-compatible body and a V2 body).  The digest is the exact
        // admission key, so do not reject that legitimate pair with package/version
        // singleOrNull; select the matching digest after establishing that the version exists.
        val versions = packageProfiles.filter {
            it.identity.version == identity.version && it.identity.versionCode == identity.versionCode
        }
        if (versions.isEmpty()) return ShuYueReviewStatusV2.UNREVIEWED_VERSION
        val version = versions.singleOrNull { it.identity.sha256 == identity.sha256 }
            ?: return ShuYueReviewStatusV2.DIGEST_MISMATCH
        if (sourceIds != listOf(version.sourceId)) return ShuYueReviewStatusV2.SOURCE_ID_MISMATCH
        return ShuYueReviewStatusV2.REVIEWED
    }

    private suspend fun closeRuntime(runtime: ExtensionPackageRuntimeV2) {
        if (runtime is CloseableExtensionPackageRuntimeV2) runCatching { runtime.close() }
    }

    private data class Admission(
        val record: ShuYueQuarantinedScriptV2,
        val profile: ShuYueReviewedPluginProfileV2,
        val permissions: Set<ShuYueExecutionPermissionV2>,
    )

    private class AdmittedScript(
        private val record: ShuYueQuarantinedScriptV2,
        profile: ShuYueReviewedPluginProfileV2,
        permissions: Set<ShuYueExecutionPermissionV2>,
    ) : ShuYueAdmittedScriptV2 {
        override val identity: ShuYueArtifactIdentityV2 = record.identity
        override val descriptor: ExtensionPackageV2 = profile.descriptor
        override val grantedPermissions: Set<ShuYueExecutionPermissionV2> = permissions.toSet()
        override val systemEvents: PluginSystemEventDeclaration? = profile.systemEvents
        override fun copyBytes(): ByteArray = record.copyBytes()
    }
}

/** Re-authorizes every script invocation so trust/permission revocation is immediately effective. */
private class GuardedRuntime(
    private val delegate: ExtensionPackageRuntimeV2,
    private val authorize: suspend () -> Unit,
) : CloseableExtensionPackageRuntimeV2, SourceLifecycleControlledExtensionPackageRuntimeV2 {
    private val guardedSources = delegate.descriptor.sources.associate { descriptor ->
        descriptor.sourceKey to GuardedSource(requireNotNull(delegate.source(descriptor.sourceKey)), authorize)
    }

    override val descriptor: ExtensionPackageV2 = delegate.descriptor
    override fun source(sourceKey: SourceKey): ExtensionSourceV2? = guardedSources[sourceKey]

    override suspend fun close() {
        if (delegate is CloseableExtensionPackageRuntimeV2) delegate.close()
    }

    override suspend fun setSourceEnabled(sourceKey: SourceKey, enabled: Boolean): Boolean {
        return (delegate as? SourceLifecycleControlledExtensionPackageRuntimeV2)
            ?.setSourceEnabled(sourceKey, enabled) == true
    }
}

private class GuardedSource(
    private val delegate: ExtensionSourceV2,
    private val authorize: suspend () -> Unit,
) : ExtensionSourceV2, UserInteractionScopedExtensionSourceV2 {
    override val descriptor: SourceDescriptorV2 = delegate.descriptor

    /**
     * Preserve the host-only interaction scope across the admission/authorization wrapper.
     *
     * Reviewed ShuYue sources use this scope to authorize modal system events such as the v2
     * login prompt.  Dropping it here leaves the event runtime foreground but without an active
     * user interaction, so a collection request silently falls back to an unauthenticated
     * network call instead of presenting the login dialog.
     */
    override suspend fun <T> withUserInteractionContext(block: suspend () -> T): T {
        authorize()
        val scoped = delegate as? UserInteractionScopedExtensionSourceV2
        return scoped?.withUserInteractionContext(block) ?: block()
    }

    override fun setHostUiAvailable(available: Boolean) {
        (delegate as? UserInteractionScopedExtensionSourceV2)?.setHostUiAvailable(available)
    }

    override suspend fun browseOptions(): BrowseOptionsSchemaV2 = guarded { delegate.browseOptions() }
    override suspend fun getFilterList(): List<dev.shinsou.kmp.plugin.v2.BrowseFilterV2> =
        guarded { delegate.getFilterList() }
    override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
        guarded { delegate.search(query, page) }
    override suspend fun search(
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> = guarded { delegate.search(query, page, options) }
    override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = guarded { delegate.latest(page) }
    override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
        guarded { delegate.browse(options, page) }
    override suspend fun details(remotePublicationId: String): RemotePublicationV2 =
        guarded { delegate.details(remotePublicationId) }
    override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
        guarded { delegate.units(remotePublicationId, page) }
    override suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 =
        guarded { delegate.content(remotePublicationId, remoteUnitId) }
    override suspend fun openTextStream(streamId: String): TextChunkStreamV2 {
        val stream = guarded { delegate.openTextStream(streamId) }
        return GuardedTextStream(stream, authorize)
    }
    override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 =
        guarded { delegate.login(credentials) }
    override suspend fun logout(): Unit = guarded { delegate.logout() }
    override suspend fun preferences(): List<PreferenceV2> = guarded { delegate.preferences() }
    override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit =
        guarded { delegate.favorite(remotePublicationId, favorite) }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        authorize()
        return block()
    }
}

private class GuardedTextStream(
    private val delegate: TextChunkStreamV2,
    private val authorize: suspend () -> Unit,
) : TextChunkStreamV2 {
    override val maxChunkBytes: Int = delegate.maxChunkBytes
    override suspend fun next(cursor: String?): TextChunkResultV2 {
        authorize()
        return delegate.next(cursor)
    }
    override fun cancel(): Unit = delegate.cancel()
}

private fun requireAdmissionId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 512 && value.none { it.isWhitespace() || it.isISOControl() }) {
        "$label must be bounded and printable"
    }
}

private fun requireAdmissionText(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 4_096 && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
}

private val SHA256: Regex = Regex("[0-9a-f]{64}")
private const val MAX_SHUYUE_SOURCES: Int = 256
private const val MAX_SHUYUE_SCRIPT_BYTES: Int = 8 * 1024 * 1024
