@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.manager

import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionHostFacadeV2
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.domain.model.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.v2.LegacyMangaPackageRuntimeV2
import kotlinx.coroutines.sync.withLock
import dev.shinsou.kmp.plugin.contracts.CatalogueSource
import dev.shinsou.kmp.plugin.model.PluginManifest
import dev.shinsou.kmp.plugin.model.sourceKeyFor

/**
 * Legacy identity mapping: numeric v1 source ids and live v1 packages projected to exact
 * [SourceKey]s and Extension v2 runtimes. Owned by [PluginManager]; shares its state and locks.
 */
internal class LegacyPluginCompat(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
) {
    private val packageStore = deps.packageStore

    /** Resolves a legacy UI row to its exact live manifest identity; a bare number is never enough. */
    /**
     * The exact key of source [sourceId] declared by the installed package [packageId], read from
     * its stored manifest. Unlike [extensionSourceV2] it needs no live runtime, so it also answers
     * for a package that is installed but waiting for its permission review: a title can be added
     * to the library under that key, and it opens once the package is approved.
     */
    suspend fun installedSourceKey(packageId: String, sourceId: String): SourceKey? {
        val stored = packageStore.get(packageId) ?: return null
        val declared = stored.manifest.sources.orEmpty().firstOrNull { source ->
            (source.canonicalSourceId ?: source.id.toString()) == sourceId
        } ?: return null
        return sourceKeyFor(packageId, declared.id, declared.canonicalSourceId)
    }

    suspend fun exactSourceKeyForLegacyId(sourceId: Long): SourceKey? = state.packageMutationMutex.withLock {
        val packageId = state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } ?: return@withLock null
        val stored = packageStore.get(packageId) ?: return@withLock null
        if (state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } != packageId) return@withLock null
        val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
            ?: return@withLock null
        sourceKeyFor(packageId, sourceId, declared.canonicalSourceId)
    }

    /** Exact event lookup; a recycled legacy Long can never cross artifact ownership. */
    suspend fun exactLegacySource(target: ExactPluginSourceTarget): CatalogueSource? =
        state.lifecycleMutex.withLock {
            val runtime = state.eventScopes.entries.singleOrNull { (_, scope) ->
                scope.artifactIdentity == target.artifactIdentity && scope.sourceKey == target.sourceKey
            }?.key ?: return@withLock null
            val live = state.runtimes[target.artifactIdentity.packageId].orEmpty().any { it === runtime }
            runtime.takeIf { live && it.id == target.sourceKey.legacyLongId }
        }

    /** Materializes the currently live v1 package as an exact-keyed Extension v2 runtime. */
    @OptIn(ExtensionImplementationApi::class)
    suspend fun extensionPackageRuntimeV2(pluginId: String): LegacyMangaPackageRuntimeV2? =
        state.packageMutationMutex.withLock { legacyPackageRuntimeV2WhilePackageLocked(pluginId) }

    /** Caller holds packageMutationMutex; avoids recursive acquisition in browser operations. */
    internal suspend fun legacyPackageRuntimeV2WhilePackageLocked(pluginId: String): LegacyMangaPackageRuntimeV2? {
        val stored = packageStore.get(pluginId) ?: return null
        return state.lifecycleMutex.withLock lifecycleLock@{
            val live = state.runtimes[pluginId].orEmpty()
            if (live.isEmpty()) return@lifecycleLock null
            val declaredContentKinds = stored.manifest.declaredV2ContentKindsByLegacyId()
            if (live.none { declaredContentKinds[it.id]?.isNotEmpty() != false }) {
                return@lifecycleLock null
            }
            LegacyMangaPackageRuntimeV2(
                packageId = pluginId,
                version = stored.manifest.version,
                displayName = stored.manifest.name,
                sources = live,
                sourceKeysByLegacyId = stored.manifest.sources.orEmpty().associate { source ->
                    source.id to sourceKeyFor(pluginId, source.id, source.canonicalSourceId)
                },
                declaredContentKindsByLegacyId = declaredContentKinds,
            )
        }
    }

    internal suspend fun sourceWhilePackageLocked(sourceKey: SourceKey): HostExtensionSourceV2? =
        state.extensionRegistryV2.packageFacade(sourceKey.packageId)?.source(sourceKey)
            ?: legacyPackageRuntimeV2WhilePackageLocked(sourceKey.packageId)
                ?.let { ExtensionHostFacadeV2(it).source(sourceKey) }

    @OptIn(ExtensionImplementationApi::class)
    suspend fun exactExtensionSource(target: ExactPluginSourceTarget): HostExtensionSourceV2? =
        state.packageMutationMutex.withLock {
            val packageId = target.sourceKey.packageId
            val legacy = state.lifecycleMutex.withLock {
                val exactRuntime = state.eventScopes.entries.singleOrNull { (_, scope) ->
                    scope.artifactIdentity == target.artifactIdentity && scope.sourceKey == target.sourceKey
                }?.key ?: return@withLock null
                val live = state.runtimes[packageId].orEmpty()
                if (live.none { it === exactRuntime }) return@withLock null
                val stored = packageStore.get(packageId) ?: return@withLock null
                val declaredContentKinds = stored.manifest.declaredV2ContentKindsByLegacyId()
                if (live.none { declaredContentKinds[it.id]?.isNotEmpty() != false }) {
                    return@withLock null
                }
                LegacyMangaPackageRuntimeV2(
                    packageId = packageId,
                    version = stored.manifest.version,
                    displayName = stored.manifest.name,
                    sources = live,
                    sourceKeysByLegacyId = stored.manifest.sources.orEmpty().associate { source ->
                        source.id to sourceKeyFor(packageId, source.id, source.canonicalSourceId)
                    },
                    declaredContentKindsByLegacyId = declaredContentKinds,
                )
            }
            if (legacy != null) return@withLock ExtensionHostFacadeV2(legacy).source(target.sourceKey)
            if (state.nativeArtifactIdentities[packageId] != target.artifactIdentity) return@withLock null
            state.extensionRegistryV2.packageFacade(packageId)?.source(target.sourceKey)
        }

    /**
     * Absence from this map is pre-V2 compatibility. V2 omission is represented by `false` and
     * inherits only the verified package union; `true` preserves even an explicitly empty set.
     */
    private fun PluginManifest.declaredV2ContentKindsByLegacyId(): Map<Long, Set<ContentKind>> =
        sources.orEmpty().mapNotNull { source ->
            val declared = when (source.contentKindsDeclared) {
                null -> return@mapNotNull null
                false -> contentKinds.orEmpty()
                true -> source.contentKinds
            }
            source.id to declared.mapTo(linkedSetOf()) { value ->
                ContentKind.entries.singleOrNull { it.name == value }
                    ?: throw IllegalArgumentException("Unknown V2 content kind '$value'")
            }
        }.toMap()

    /**
     * The installed numeric id for [sourceKey]. Keys stored with library entries may lack the
     * optional legacy projection (string source ids since A7b); those resolve through the live
     * manifest entry with the same package and source id. Callers still verify ownership and the
     * exact key against that entry. Call with [packageMutationMutex] held.
     */
    internal suspend fun installedLegacyIdLocked(sourceKey: SourceKey): Long? =
        sourceKey.legacyLongId ?: packageStore.get(sourceKey.packageId)
            ?.manifest?.sources.orEmpty()
            .singleOrNull { source -> sourceKeyFor(sourceKey.packageId, source.id, source.canonicalSourceId) == sourceKey }
            ?.id
}
