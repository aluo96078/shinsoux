@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.manager

import kotlinx.coroutines.sync.Semaphore
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import io.ktor.http.Url
import kotlinx.coroutines.sync.withLock
import dev.shinsou.kmp.plugin.browser.REVIEWED_BROWSER_IMAGE_MAX_BYTES
import dev.shinsou.kmp.plugin.browser.browserImageOriginOf
import dev.shinsou.kmp.plugin.browser.validateReviewedBrowserImageRequest
import dev.shinsou.kmp.plugin.model.PluginRuntimePermission
import dev.shinsou.kmp.plugin.model.SourceIndexEntry
import dev.shinsou.kmp.plugin.model.StoredPlugin
import dev.shinsou.kmp.plugin.model.artifactIdentity
import dev.shinsou.kmp.plugin.model.effectiveRuntimePermissions
import dev.shinsou.kmp.plugin.model.eventGrantReview
import dev.shinsou.kmp.plugin.model.sourceKeyFor
import dev.shinsou.kmp.plugin.network.PluginContentNetworkClient
import dev.shinsou.kmp.plugin.network.PluginNetworkLimits
import dev.shinsou.kmp.plugin.network.TypedReaderRemoteAssetScope
import dev.shinsou.kmp.plugin.network.imageBodyForDecoderOrNull
import dev.shinsou.kmp.plugin.network.normalizePluginOrigins
import dev.shinsou.kmp.plugin.network.resolvePluginTransportHost
import dev.shinsou.kmp.plugin.storage.BoundPluginStorage
import dev.shinsou.kmp.plugin.storage.PluginStorage

/** Host-only storage and content-plane clients of live sources, bound to their exact manifests. */
internal class PluginSourceContentAccess(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
    private val executionAdmission: PluginExecutionAdmission,
    private val legacyCompat: LegacyPluginCompat,
) {
    private val packageStore = deps.packageStore
    private val environment = deps.environment
    private val eventGrantAdmission = deps.eventGrantAdmission
    private val reviewedImageTransport = deps.reviewedImageTransport

    private val reviewedImagePermits = Semaphore(2)

    /**
     * Host-only exact storage lookup for source settings/import UI. Package identity is resolved
     * from the manager's live ownership map and cannot be supplied by extension code.
     */
    suspend fun storageForSource(sourceId: Long): PluginStorage? = state.packageMutationMutex.withLock {
        val packageId = state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } ?: return@withLock null
        val stored = packageStore.get(packageId) ?: return@withLock null
        // Recheck after storage I/O so an unload can never hand UI a stale attacker-selected scope.
        if (state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } != packageId) return@withLock null
        val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
        BoundPluginStorage(
            delegate = environment.storage,
            sourceKey = sourceKeyFor(packageId, sourceId, declared?.canonicalSourceId),
            allowLegacyMigration = stored.metadata.legacyStorageMigrationAllowed,
        )
    }

    /** Host-only content plane; its allowlist comes from the exact live source manifest. */
    suspend fun contentNetworkForSource(sourceId: Long): PluginContentNetworkClient? =
        state.packageMutationMutex.withLock {
            val packageId = state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } ?: return@withLock null
            val stored = packageStore.get(packageId) ?: return@withLock null
            if (state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } != packageId) return@withLock null
            val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
                ?: return@withLock null
            reviewedImageContentClient(
                sourceKeyFor(packageId, sourceId, declared.canonicalSourceId), stored.artifactIdentity(),
                browserImageOriginsFor(stored, declared),
                environment.network.scopedToContentPolicy(executionAdmission.grantedNetworkPolicy(declared, executionAdmission.grantFor(stored))),
            )
        }

    /**
     * Exact v2 content scope for UI thumbnails and inline reader assets. Generic native runtimes
     * have no repository-defined network declaration and therefore remain denied.
     */
    suspend fun contentNetworkScopeForSource(sourceKey: SourceKey): TypedReaderRemoteAssetScope? =
        state.packageMutationMutex.withLock {
            legacyCompat.installedLegacyIdLocked(sourceKey)?.let { legacyId ->
                val packageId = state.lifecycleMutex.withLock { state.sourceOwners[legacyId] }
                    ?: return@withLock null
                if (packageId != sourceKey.packageId) return@withLock null
                val stored = packageStore.get(packageId) ?: return@withLock null
                val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == legacyId }
                    ?: return@withLock null
                // The numeric id is only a compatibility projection. Require the complete
                // opaque key to match the live manifest too, otherwise a caller could attach a
                // valid legacy id to an unrelated source id and borrow its content authority.
                if (sourceKey != sourceKeyFor(packageId, legacyId, declared.canonicalSourceId)) {
                    return@withLock null
                }
                return@withLock TypedReaderRemoteAssetScope(
                    sourceKey = sourceKey,
                    sourceId = legacyId,
                    network = reviewedImageContentClient(
                        sourceKey, stored.artifactIdentity(),
                        browserImageOriginsFor(stored, declared),
                        environment.network.scopedToContentPolicy(executionAdmission.grantedNetworkPolicy(declared, executionAdmission.grantFor(stored))),
                    ),
                )
            }
            null
        }

    /**
     * The origins whose images this source may load in the isolated browser: those it declared
     * as `browserImageOrigins` (always content origins), only while the user grants BROWSER_IMAGES.
     */
    private suspend fun browserImageOriginsFor(stored: StoredPlugin, declared: SourceIndexEntry): Set<String> {
        if (declared.browserImageOrigins.isEmpty()) return emptySet()
        if (PluginRuntimePermission.BROWSER_IMAGES !in effectiveRuntimePermissions(stored)) return emptySet()
        val grant = executionAdmission.grantFor(stored)
        if (eventGrantAdmission != null &&
            (grant == null || PluginRuntimePermission.BROWSER_IMAGES !in grant.runtimePermissions)
        ) return emptySet()
        return normalizePluginOrigins(declared.browserImageOrigins) intersect normalizePluginOrigins(declared.contentOrigins)
    }

    /** No script-selected origin or identity can grant the browser image transport. */
    private fun reviewedImageContentClient(
        sourceKey: SourceKey,
        identity: PluginArtifactIdentity,
        origins: Set<String>,
        native: PluginContentNetworkClient,
    ): PluginContentNetworkClient {
        val transport = reviewedImageTransport ?: return native
        if (origins.isEmpty()) return native
        return native.withReviewedImageTransport { request ->
            val origin = browserImageOriginOf(request.url)
            if (request.method != "GET" || origin == null || origin !in origins) return@withReviewedImageTransport null
            // The reader's Referer/UA hints are not browser credentials. Ignore them; the
            // isolated same-origin browser uses its real UA and no referrer/cookies.
            val imageRequest = request.copy(
                headers = emptyMap(),
                maxResponseBytes = minOf(request.maxResponseBytes, REVIEWED_BROWSER_IMAGE_MAX_BYTES),
            )
            reviewedImagePermits.acquire()
            try {
                require(isLiveBrowserImageSource(sourceKey, identity, origin)) { "Browser image source is no longer authorized" }
                val resolution = resolvePluginTransportHost(Url(imageRequest.url), environment.hostResolver, false)
                validateReviewedBrowserImageRequest(imageRequest, resolution)
                val result = kotlinx.coroutines.withTimeout(PluginNetworkLimits.UI_WAIT_MILLIS) {
                    environment.network.withContentRequestGate(resolution.host) {
                        transport.executeResolved(imageRequest, resolution)
                    }
                }
                require(isLiveBrowserImageSource(sourceKey, identity, origin)) { "Browser image source changed during loading" }
                require(result.body.size <= imageRequest.maxResponseBytes && result.imageBodyForDecoderOrNull() != null) {
                    "Browser image returned invalid content"
                }
                result
            } finally { reviewedImagePermits.release() }
        }
    }

    /** Rechecked before and after every browser image: same artifact, still admitted, still granted. */
    private suspend fun isLiveBrowserImageSource(
        sourceKey: SourceKey,
        identity: PluginArtifactIdentity,
        origin: String,
    ): Boolean = state.packageMutationMutex.withLock {
        if (state.isLocallyRevoked(identity)) return@withLock false
        val sourceId = legacyCompat.installedLegacyIdLocked(sourceKey) ?: return@withLock false
        if (state.lifecycleMutex.withLock { state.sourceOwners[sourceId] } != sourceKey.packageId) return@withLock false
        val stored = packageStore.get(sourceKey.packageId) ?: return@withLock false
        if (stored.artifactIdentity() != identity) return@withLock false
        val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId } ?: return@withLock false
        if (sourceKeyFor(sourceKey.packageId, sourceId, declared.canonicalSourceId) != sourceKey) return@withLock false
        val review = stored.eventGrantReview(includeEmpty = true) ?: return@withLock false
        executionAdmission.executionAdmissionGranted(review) && origin in browserImageOriginsFor(stored, declared)
    }

    suspend fun contentNetworkForSource(sourceKey: SourceKey): PluginContentNetworkClient? =
        contentNetworkScopeForSource(sourceKey)?.network
}
