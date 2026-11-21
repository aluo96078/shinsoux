@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemCapabilityNegotiator
import dev.shinsou.kmp.plugin.events.PluginEventRuntimeStatus
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventNegotiation
import dev.shinsou.kmp.plugin.events.stablePluginRuntimeInstanceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Builds, publishes and tears down script runtimes and their source reservations. Callers hold
 * [PluginManagerState.lifecycleMutex] where a member says so; [loadInstalled], [unloadDeveloperRuntimes]
 * and [close] take the locks themselves in [PluginManager]'s order.
 */
internal class PluginRuntimeLoader(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
    private val executionAdmission: PluginExecutionAdmission,
) {
    private val packageStore = deps.packageStore
    private val verifier = deps.verifier
    private val runtimeFactory = deps.runtimeFactory
    private val environment = deps.environment
    private val eventGrantAdmission = deps.eventGrantAdmission
    private val executionTierGate = deps.executionTierGate
    private val defaultDispatcher = deps.defaultDispatcher

    private val eventScopeFactory = BoundPluginScopeFactory()
    private val legacyLoginCompatibilityScopes = mutableSetOf<String>()
    private var runtimeGeneration: Long = 0

    /**
     * Reconstructs trusted packages independently. An untrusted, corrupt, or unloadable package
     * remains installed but cannot prevent other packages from becoming available.
     */
    /**
     * ADR-0002 §8.1: takes every developer-mode (`~dev`) runtime down now. Called before the mode
     * is switched off, so no development build is ever live while the mode is off.
     */
    suspend fun unloadDeveloperRuntimes(): Unit = withContext(defaultDispatcher + NonCancellable) {
        state.packageMutationMutex.withLock {
            state.invalidateWebChallengeGrants()
            val keys = packageStore.list().map { it.manifest.id }.filter(PluginInstallKeys::isDeveloperKey)
            state.lifecycleMutex.withLock { keys.forEach { unloadPlugin(it) } }
        }
    }

    suspend fun loadInstalled(): List<CatalogueSource> = withContext(defaultDispatcher) {
        state.packageMutationMutex.withLock {
            state.invalidateWebChallengeGrants()
            state.lifecycleMutex.withLock {
                closeAllRuntimes()
                state.sources.clear()
                state.sourceOwners.clear()
                state.sourceReservations.clear()
                val storedPlugins = preflightInstalledManifestSources(packageStore.list())
                storedPlugins.forEach { stored ->
                    try {
                        loadStored(stored)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: PluginExecutionInertException) {
                        // Inert by publisher trust or the block list: reversible, so the grant stays
                        // and the package runs again once the reason is gone.
                    } catch (error: ScriptRuntimeUnavailableException) {
                        // A durable exact-artifact grant may have been created on a host which
                        // could safely isolate this runtime. If the current host cannot, convert
                        // that old grant back to a pending review just like an explicit approval
                        // or re-enable attempt does. This prevents every restart from presenting
                        // an inert package as approved while repeatedly retrying construction.
                        Log.warn("Plugin", error) {
                            "Script runtime unavailable; quarantining the installed plugin package"
                        }
                        state.locallyRevokedArtifacts += stored.artifactIdentity()
                        executionAdmission.quarantineUnavailableRuntime(
                            requireNotNull(stored.eventGrantReview(includeEmpty = true)),
                        )
                    } catch (logged: Exception) {
                        // Per-package isolation is intentional. The package stays installed so the UI can
                        // show it as blocked/corrupt and still let the user re-authorize or uninstall it.
                        Log.warn("Plugin", logged) {
                            "Failed to load an installed plugin package; leaving it installed as blocked"
                        }
                    }
                }
                state.sources.values.toList()
            }
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable + defaultDispatcher) {
        state.packageMutationMutex.withLock {
            state.invalidateWebChallengeGrants()
            state.lifecycleMutex.withLock {
                closeAllRuntimes()
                state.sources.clear()
                state.sourceOwners.clear()
                state.sourceReservations.clear()
            }
            state.nativeLifecycleRuntimes.clear()
            state.disabledNativeSources.clear()
            state.nativeArtifactIdentities.clear()
            state.extensionRegistryV2.close()
        }
    }

    internal suspend fun replaceRuntimes(
        pluginId: String,
        candidates: List<ScriptPluginRuntime>,
    ) {
        validateRuntimeCandidates(pluginId, candidates)
        unloadPlugin(pluginId)
        state.runtimes[pluginId] = candidates.toList()
        candidates.forEach { candidate ->
            state.sources[candidate.id] = candidate
            state.sourceOwners[candidate.id] = pluginId
        }
    }

    /** Rebuilds the last durable package after a late commit failure (for example ledger I/O). */
    internal suspend fun restorePreviousRuntime(pluginId: String, previous: StoredPlugin?) {
        state.lifecycleMutex.withLock {
            unloadPlugin(pluginId)
            if (previous == null) return@withLock
            val reservation = reserveManifestSources(previous.manifest)
            try {
                if (previous.isMetadataOnly()) {
                    addMetadataSources(previous)
                    commitReservation(reservation)
                    return@withLock
                }
                if (!previous.hasExecutableRuntimeDeclaration()) {
                    commitReservation(reservation)
                    return@withLock
                }
                val review = requireNotNull(previous.eventGrantReview(includeEmpty = true))
                val executable = executionAdmission.executionAdmissionGranted(review)
                if (executable) {
                    val restored = createRuntimeCandidates(previous)
                    replaceRuntimes(pluginId, restored)
                }
                commitReservation(reservation)
            } catch (restoreError: Throwable) {
                rollbackReservation(reservation)
                throw restoreError
            }
        }
    }

    internal fun validateRuntimeCandidates(
        pluginId: String,
        candidates: List<ScriptPluginRuntime>,
    ) {
        require(candidates.isNotEmpty()) { "Plugin '$pluginId' produced no executable state.sources" }
        val candidateIds = candidates.map(ScriptPluginRuntime::id)
        require(candidateIds.distinct().size == candidateIds.size) {
            "Plugin '$pluginId' produced duplicate executable source ids"
        }
        candidateIds.forEach { sourceId ->
            val owner = state.sourceOwners[sourceId]
            require(owner == null || owner == pluginId) {
                "Source id $sourceId is already owned by plugin '$owner'"
            }
            require(state.sourceReservations[sourceId] == pluginId) {
                "Source id $sourceId was not reserved for plugin '$pluginId'"
            }
        }
    }

    internal suspend fun loadStored(stored: StoredPlugin) {
        // Package stores are replaceable host dependencies. Re-establish the complete durable
        // invariant here so even a weak/custom implementation cannot split event-grant identity,
        // manifest signature, and the exact bytes handed to a runtime.
        requireValidStoredPluginPackage(stored)
        if (stored.isMetadataOnly()) {
            val trusted = PluginVerifier.isLegacyTrustValid(stored) ||
                verifier.isTrusted(stored.manifest, stored.metadata.installedSha256)
            if (!trusted) {
                val versionCode = stored.manifest.versionCode
                    ?: PluginVerifier.versionInt(stored.manifest.version)
                throw PluginVerificationException.Untrusted(
                    stored.manifest.id,
                    versionCode,
                    stored.metadata.installedSha256,
                )
            }
            val reservation = reserveManifestSources(stored.manifest)
            try {
                addMetadataSources(stored)
                commitReservation(reservation)
            } catch (error: Throwable) {
                rollbackReservation(reservation)
                throw error
            }
            return
        }

        if (!stored.hasExecutableRuntimeDeclaration()) {
            unloadPlugin(stored.manifest.id)
            return
        }

        // Every package needs the durable verifier token: a repository entry becomes executable
        // code only through the exact-artifact trust record.
        if (!PluginVerifier.isLegacyTrustValid(stored)) {
            verifier.verify(
                stored.scriptBytes,
                stored.manifest,
                trustOnValidatedDigest = false,
            )
        }
        val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        @Suppress("SuspendFunSwallowedCancellation") // Rethrows unchanged after tearing down the existing runtime.
        val admitted = try {
            executionAdmission.prepareExecutionAdmission(review)
        } catch (error: Throwable) {
            // loadStored is also used to re-enable an already materialized source. Admission I/O
            // failure must tear down that existing runtime instead of leaving it callable.
            unloadPlugin(stored.manifest.id)
            throw error
        }
        if (!admitted) {
            unloadPlugin(stored.manifest.id)
            return
        }
        require(state.extensionRegistryV2.packageFacade(stored.manifest.id) == null) {
            "Extension package '${stored.manifest.id}' is already loaded by the V2 runtime"
        }
        val reservation = reserveManifestSources(stored.manifest)
        var candidates: List<ScriptPluginRuntime> = emptyList()
        var liveStatePublished = false
        // Rethrows unchanged after tearing down the half-loaded runtime.
        @Suppress("SuspendFunSwallowedCancellation") try {
            candidates = createRuntimeCandidates(stored)
            replaceRuntimes(stored.manifest.id, candidates)
            liveStatePublished = true
            commitReservation(reservation)
        } catch (error: Throwable) {
            if (liveStatePublished) unloadPlugin(stored.manifest.id)
            else closeRuntimes(candidates)
            rollbackReservation(reservation)
            throw error
        }
    }

    /** Builds every declared source independently; a package list position is never executed. */
    @Suppress("SuspendFunSwallowedCancellation") // Rethrows unchanged after closing the partially built runtimes.
    internal suspend fun createRuntimeCandidates(stored: StoredPlugin): List<ScriptPluginRuntime> {
        val manifest = stored.manifest
        requireSupportedGenericRuntime(manifest)
        val declaredRuntimePermissions = requireNotNull(manifest.runtimePermissions) {
            "Plugin '${manifest.id}' has no executable runtime permission declaration"
        }
        require(PluginRuntimePermission.EXECUTE_SCRIPT in declaredRuntimePermissions) {
            "Plugin '${manifest.id}' lacks EXECUTE_SCRIPT permission"
        }
        val declared = manifest.sources.orEmpty()
        require(declared.map(SourceIndexEntry::id).distinct().size == declared.size) {
            "Plugin '${manifest.id}' declares duplicate source ids"
        }
        val candidates = mutableListOf<ScriptPluginRuntime>()
        try {
            if (declared.isEmpty()) {
                candidates += createScopedRuntime(stored, null)
            } else {
                declared.forEach { source ->
                    candidates += createScopedRuntime(stored, source)
                }
            }
            require(candidates.all { it.pluginId == manifest.id }) {
                "Plugin runtime package identity does not match '${manifest.id}'"
            }
            if (declared.isNotEmpty()) {
                require(candidates.map(ScriptPluginRuntime::id).toSet() == declared.map(SourceIndexEntry::id).toSet()) {
                    "Plugin runtime source identities do not match '${manifest.id}' declarations"
                }
            }
            return candidates.toList()
        } catch (error: Throwable) {
            closeRuntimes(candidates)
            throw error
        }
    }

    private suspend fun createScopedRuntime(
        stored: StoredPlugin,
        source: SourceIndexEntry?,
    ): ScriptPluginRuntime {
        val manifest = stored.manifest
        check(runtimeGeneration < Long.MAX_VALUE) { "Plugin runtime generation space is exhausted" }
        val generation = ++runtimeGeneration
        val legacyId = source?.id ?: stableSourceId(manifest.id)
        val sourceKey = sourceKeyFor(manifest.id, legacyId, source?.canonicalSourceId)
        val artifactIdentity = PluginArtifactIdentity(
            packageId = manifest.id,
            version = manifest.version,
            versionCode = manifest.versionCode ?: PluginVerifier.versionInt(manifest.version),
            sha256 = stored.metadata.installedSha256,
        )
        val scope = eventScopeFactory.bind(
            artifactIdentity = artifactIdentity,
            sourceKey = sourceKey,
            runtimeInstanceId = stablePluginRuntimeInstanceId(artifactIdentity, sourceKey),
            runtimeGeneration = generation,
        )
        val negotiated = manifest.systemEvents?.let { declaration ->
            (environment.systemEventSink as? PluginSystemEventGateway)?.negotiate(scope, declaration)
                ?: PluginSystemCapabilityNegotiator(supportedCapabilities = emptySet()).negotiate(declaration)
        }
        val legacyLoginCompatibility = manifest.systemEvents == null
        val visibleNegotiation = negotiated ?: PluginSystemEventNegotiation(enabled = false)
        // Only what the user granted (ADR-0002 §6.3); without an admission store (developer
        // compatibility) the declaration stands, as before item-level grants existed.
        val grant = eventGrantAdmission?.grantedSelection(artifactIdentity)
        val runtimePermissions = effectiveRuntimePermissions(stored)
            .let { declared -> if (grant == null) declared else declared intersect grant.runtimePermissions }
        requireExecutionTier(stored)
        val boundStorage = PermissionFilteredPluginStorage(
            BoundPluginStorage(
                delegate = environment.storage,
                sourceKey = sourceKey,
                allowLegacyMigration = stored.metadata.legacyStorageMigrationAllowed,
            ),
            runtimePermissions,
        )
        val scopedEnvironment = environment.copy(
            network = environment.network
                .scopedToStorage(boundStorage)
                .scopedToPolicy(
                    source?.let { executionAdmission.grantedNetworkPolicy(it, grant) }
                        ?: PluginNetworkPolicy.Default,
                ),
            storage = boundStorage,
            runtimePermissions = runtimePermissions,
            // Legacy code receives only the native ingress needed by bridge.requestLogin. Its
            // capability response remains disabled, and the exact authorizer below grants login
            // only after the trusted runtime proves it implements LoginSource.
            systemEventSink = environment.systemEventSink.takeIf {
                negotiated?.enabled == true || legacyLoginCompatibility
            },
            boundPluginScope = scope.takeIf {
                negotiated?.enabled == true || legacyLoginCompatibility
            },
            systemEventNegotiation = visibleNegotiation,
            systemEventDeclaration = manifest.systemEvents,
        )
        val runtime = if (source == null) {
            runtimeFactory.create(stored.script, manifest, scopedEnvironment)
        } else {
            runtimeFactory.createForSource(stored.script, manifest, source, scopedEnvironment)
        }
        state.eventScopes[runtime] = scope
        (environment.systemEventSink as? PluginSystemEventGateway)?.let { gateway ->
            if (legacyLoginCompatibility && runtime.supportsLogin) {
                gateway.grantRuntimePermissions(scope, setOf(PluginHostPermission.REQUEST_LOGIN_UI))
                legacyLoginCompatibilityScopes += scope.runtimeKey
            }
            gateway.openRuntime(scope, PluginEventRuntimeStatus(
                sourceCapabilities = buildSet {
                    add("CATALOGUE")
                    if (runtime.supportsLogin) add("LOGIN")
                    if (runtime.supportsLatest) add("LATEST")
                },
            ))
        }
        return runtime
    }

    /**
     * ADR-0002 §10.1, checked before every runtime is built (install, approval, startup, re-enable).
     * Only reached after the exact-artifact grant, so the user's authorization holds here. A
     * package must come out as sandboxed (the block list and its publisher's revocations apply), which
     * the tiered factory then runs out of process.
     */
    private suspend fun requireExecutionTier(stored: StoredPlugin) {
        val gate = executionTierGate ?: return
        val tier = gate.resolve(
            ExecutionTierCandidate(
                // Blocks and revocations name the package id the publisher published (D6).
                packageId = PluginInstallKeys.publishedPackageId(stored.manifest.id),
                artifactSha256 = stored.metadata.installedSha256,
                publisherId = stored.metadata.publisherId,
                userAuthorized = true,
                developerLocalSource = stored.metadata.publisherId == null &&
                    PluginInstallKeys.isDeveloperKey(stored.manifest.id),
            ),
        )
        if (tier !is ExecutionTier.Sandboxed) {
            throw PluginExecutionInertException(stored.manifest.id, (tier as? ExecutionTier.Inert)?.reason ?: InertReason.NOT_AUTHORIZED)
        }
    }

    internal fun addMetadataSources(stored: StoredPlugin): List<CatalogueSource> =
        stored.manifest.sources.orEmpty().map {
            MetadataStubCatalogueSource(stored.manifest.id, it)
        }.also { stubs ->
            require(stubs.map(CatalogueSource::id).distinct().size == stubs.size) {
                "Plugin '${stored.manifest.id}' declares duplicate source ids"
            }
            stubs.forEach { source ->
                val owner = state.sourceOwners[source.id]
                require(owner == null || owner == stored.manifest.id) {
                    "Source id ${source.id} is already owned by plugin '$owner'"
                }
            }
            stubs.forEach { source ->
                state.sources[source.id] = source
                state.sourceOwners[source.id] = stored.manifest.id
            }
        }

    internal suspend fun unloadPlugin(pluginId: String) {
        removeSourcesFor(pluginId)
        state.runtimes.remove(pluginId)?.let { runtime -> closeRuntimes(runtime) }
    }

    private suspend fun closeAllRuntimes() {
        state.runtimes.values.flatten().toList().forEach { runtime -> closeRuntime(runtime) }
        state.runtimes.clear()
    }

    /** Runtime teardown must finish even when the operation that made it unreachable is cancelled. */
    internal suspend fun closeRuntime(runtime: ScriptPluginRuntime) {
        state.eventScopes.remove(runtime)?.let { scope ->
            (environment.systemEventSink as? PluginSystemEventGateway)?.let { gateway ->
                gateway.closeRuntime(scope)
                if (legacyLoginCompatibilityScopes.remove(scope.runtimeKey)) {
                    gateway.revokeRuntimePermissions(scope)
                }
            }
        }
        withContext(NonCancellable) { runCatchingCancellable { runtime.close() } }
    }

    internal suspend fun closeRuntimes(values: Iterable<ScriptPluginRuntime>) {
        values.forEach { runtime -> closeRuntime(runtime) }
    }

    private fun removeSourcesFor(pluginId: String) {
        val ownedRuntimeObjects = state.runtimes[pluginId].orEmpty()
        // Remove by object identity so a malfunctioning runtime getter cannot block revocation.
        // Also remove persisted IDs for legacy multi-source entries.
        val known = state.sources.filterValues { source ->
            ownedRuntimeObjects.any { it === source } ||
                source is MetadataStubCatalogueSource && source.pluginId == pluginId
        }.keys
        known.forEach { sourceId ->
            state.sources.remove(sourceId)
            if (state.sourceOwners[sourceId] == pluginId) state.sourceOwners.remove(sourceId)
        }
    }

    internal data class SourceReservation(val packageId: String, val sourceIds: Set<Long>)

    /** Must run under [lifecycleMutex] before creating/evaluating any package runtime. */
    internal fun reserveManifestSources(manifest: PluginManifest): SourceReservation {
        val sourceIds = declaredSourceIds(manifest)
        require(sourceIds.size == manifest.sources.orEmpty().size.coerceAtLeast(1)) {
            "Plugin '${manifest.id}' declares duplicate source ids"
        }
        sourceIds.forEach { sourceId ->
            val liveOwner = state.sourceOwners[sourceId]
            require(liveOwner == null || liveOwner == manifest.id) {
                "Source id $sourceId is already owned by plugin '$liveOwner'"
            }
            val reservedOwner = state.sourceReservations[sourceId]
            require(reservedOwner == null || reservedOwner == manifest.id) {
                "Source id $sourceId is reserved by plugin '$reservedOwner'"
            }
        }
        sourceIds.forEach { state.sourceReservations[it] = manifest.id }
        return SourceReservation(manifest.id, sourceIds)
    }

    /** One inert pass ensures a corrupt stored collision cannot run whichever package lists first. */
    /**
     * The installed packages that can load together. Packages whose stored source ids collide
     * (which one owns an id is then ambiguous), or that repeat an id, stay installed but none of
     * them runs; one collision no longer keeps every other package from loading.
     */
    private fun preflightInstalledManifestSources(storedPlugins: List<StoredPlugin>): List<StoredPlugin> {
        val owners = mutableMapOf<Long, MutableSet<String>>()
        val invalid = mutableSetOf<String>()
        storedPlugins.forEach { stored ->
            val manifest = stored.manifest
            val sourceIds = declaredSourceIds(manifest)
            if (sourceIds.size != manifest.sources.orEmpty().size.coerceAtLeast(1)) invalid += manifest.id
            sourceIds.forEach { owners.getOrPut(it, ::linkedSetOf) += manifest.id }
        }
        owners.values.filter { it.size > 1 }.forEach(invalid::addAll)
        return storedPlugins.filter { it.manifest.id !in invalid }
    }

    internal fun commitReservation(reservation: SourceReservation) {
        reservation.sourceIds.forEach { sourceId ->
            check(state.sourceReservations[sourceId] == reservation.packageId)
            state.sourceReservations.remove(sourceId)
        }
    }

    internal fun rollbackReservation(reservation: SourceReservation) {
        reservation.sourceIds.forEach { sourceId ->
            if (state.sourceReservations[sourceId] == reservation.packageId) state.sourceReservations.remove(sourceId)
        }
    }

    private fun declaredSourceIds(manifest: PluginManifest): Set<Long> =
        manifest.sources.orEmpty().mapTo(linkedSetOf(), SourceIndexEntry::id)
            .ifEmpty { setOf(stableSourceId(manifest.id)) }
}

private class MetadataStubCatalogueSource(
    val pluginId: String,
    private val entry: SourceIndexEntry,
) : CatalogueSource {
    override val id: Long = entry.id
    override val canonicalSourceId: String = entry.canonicalSourceId ?: entry.id.toString()
    override val name: String = entry.name
    override val lang: String = entry.lang
    override val baseUrl: String = entry.baseUrl.orEmpty()
    override val supportsLatest: Boolean = false
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getFilterList(): FilterList = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
}
