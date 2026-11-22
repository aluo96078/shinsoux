@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.model

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.runtime.stableSourceId
import dev.shinsou.kmp.plugin.trust.PluginVerifier

// Package rules shared by PluginManager and its collaborators. They read only their arguments.

internal fun StoredPlugin.artifactIdentity(): PluginArtifactIdentity = PluginArtifactIdentity(
    packageId = manifest.id,
    version = manifest.version,
    versionCode = manifest.versionCode ?: PluginVerifier.versionInt(manifest.version),
    sha256 = metadata.installedSha256,
)

internal fun StoredPlugin.eventGrantReview(includeEmpty: Boolean = false): PluginEventGrantReview? {
    val effectiveRuntimePermissions = effectiveRuntimePermissions(this)
    if (!includeEmpty && manifest.requestedHostPermissions.isEmpty() && effectiveRuntimePermissions.isEmpty()) return null
    val artifact = artifactIdentity()
    val declaredSources = manifest.sources.orEmpty()
    val sourceKeys = if (declaredSources.isEmpty()) {
        val id = stableSourceId(manifest.id)
        listOf(SourceKey(packageId = manifest.id, sourceId = id.toString(), legacyLongId = id))
    } else {
        declaredSources.map { source ->
            sourceKeyFor(manifest.id, source.id, source.canonicalSourceId)
        }
    }
    return PluginEventGrantReview(
        artifact,
        sourceKeys,
        manifest.requestedHostPermissions,
        effectiveRuntimePermissions,
        declaredSources.flatMapTo(linkedSetOf()) { it.contentHostSuffixes },
    )
}

internal fun StoredPlugin.isMetadataOnly(): Boolean =
    scriptBytes.isEmpty() &&
        metadata.installedSha256.isBlank() &&
        manifest.signature.isBlank()

internal fun sourceKeyFor(packageId: String, legacyId: Long, canonicalSourceId: String? = null): SourceKey =
    SourceKey(packageId = packageId, sourceId = canonicalSourceId ?: legacyId.toString(), legacyLongId = legacyId)

/** Missing permissions are retained for decoding only and never grant executable services. */
internal fun effectiveRuntimePermissions(stored: StoredPlugin): Set<PluginRuntimePermission> =
    stored.manifest.runtimePermissions.orEmpty()

internal fun StoredPlugin.hasExecutableRuntimeDeclaration(): Boolean =
    manifest.runtimePermissions?.contains(PluginRuntimePermission.EXECUTE_SCRIPT) == true &&
        runCatching { requireSupportedGenericRuntime(manifest) }.isSuccess

internal fun requireSupportedGenericRuntime(manifest: PluginManifest) {
    val isV2 = manifest.sidecarUrl != null || manifest.contract != null || manifest.runtime != null
    if (!isV2) return
    require(manifest.contract == GENERIC_V2_CONTRACT && manifest.runtime == GENERIC_V2_RUNTIME) {
        "Unsupported generic V2 contract/runtime '${manifest.contract}'/'${manifest.runtime}'"
    }
}

internal const val GENERIC_V2_CONTRACT: String = "shinsou"
internal const val GENERIC_V2_RUNTIME: String = "legacy-shinsou-adapter-v2"
