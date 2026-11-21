@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import kotlinx.coroutines.sync.Mutex
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionHostFacadeV2
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.v2.ExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ExtensionPackageV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.ArtifactBoundExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.SourceLifecycleControlledExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginRuntimeLifecycle
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.domain.model.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.v2.LegacyMangaPackageRuntimeV2
import dev.shinsou.kmp.contract.SourceCookie
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock

/**
 * Controls the final host execution gate after repository and artifact verification.
 *
 * The compatibility value is deliberately verbose and must be selected by tests or an explicit
 * developer host. Production callers use the default exact-artifact approval policy.
 */
public enum class PluginExecutionAdmissionMode {
    REQUIRE_EXACT_ARTIFACT_APPROVAL,
    UNSAFE_DEVELOPER_COMPATIBILITY,
}

/**
 * The plugin subsystem's public facade. The work is done by collaborators that share one
 * [PluginManagerState]: [PluginInstaller], [PluginRepositoryRefresher], [PluginTrustCoordinator],
 * [PluginWebChallengeCoordinator], [PluginRuntimeLoader], [PluginSourceContentAccess] and
 * [LegacyPluginCompat]. Lock order: [PluginManagerState.packageMutationMutex], then
 * [PluginManagerState.lifecycleMutex]; [interactionMutex] is taken only while neither is held.
 */
public class PluginManager(
    repositoryClient: ExtensionRepositoryClient,
    private val packageStore: PluginPackageStore,
    verifier: PluginVerifier,
    runtimeFactory: ScriptPluginRuntimeFactory,
    private val environment: ScriptPluginEnvironment,
    eventGrantAdmission: KeyValuePluginEventGrantAdmission? = null,
    executionAdmissionMode: PluginExecutionAdmissionMode =
        PluginExecutionAdmissionMode.REQUIRE_EXACT_ARTIFACT_APPROVAL,
    reviewedImageTransport: PluginHttpTransport? = null,
    /** ADR-0002 §10.1: publisher trust, withdrawals and the user's block list decide each runtime's tier. */
    executionTierGate: PluginExecutionTierGate?,
    /** ADR-0002 §8.1: true for a canonical repository URL developer mode currently admits. */
    developerRepository: (String) -> Boolean = { false },
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val state = PluginManagerState()
    private val deps = PluginManagerDependencies(
        repositoryClient = repositoryClient,
        packageStore = packageStore,
        verifier = verifier,
        runtimeFactory = runtimeFactory,
        environment = environment,
        eventGrantAdmission = eventGrantAdmission,
        executionAdmissionMode = executionAdmissionMode,
        reviewedImageTransport = reviewedImageTransport,
        executionTierGate = executionTierGate,
        developerRepository = developerRepository,
        defaultDispatcher = defaultDispatcher,
    )
    private val executionAdmission = PluginExecutionAdmission(state, deps)
    private val legacyCompat = LegacyPluginCompat(state, deps)
    private val runtimeLoader = PluginRuntimeLoader(state, deps, executionAdmission)
    private val trustCoordinator = PluginTrustCoordinator(state, deps, executionAdmission, runtimeLoader)
    private val repositoryRefresher = PluginRepositoryRefresher(state, deps)
    private val installer = PluginInstaller(state, deps, executionAdmission, runtimeLoader, trustCoordinator, repositoryRefresher)
    private val webChallenges = PluginWebChallengeCoordinator(state, deps, executionAdmission, legacyCompat)
    private val contentAccess = PluginSourceContentAccess(state, deps, executionAdmission, legacyCompat)

    private val interactionMutex = Mutex()
    private val interactionCounts = mutableMapOf<String, Int>()
    private var pluginUiAvailable: Boolean = false

    /** Host-internal snapshot used to build an isolated browser without exposing raw storage. */
    internal class WebChallengeAuthorization internal constructor(
        internal val capability: SourceWebChallengeCapability,
        internal val sourceKey: SourceKey,
        internal val storageId: Long,
        internal val canonicalOrigin: String,
        internal val source: HostExtensionSourceV2,
        internal val localStorageKeys: Set<String>,
        internal val requiredLocalStorageKeys: Set<String>,
        internal val allowedSubresourceOrigins: Set<String>,
        internal val allowedNavigationOrigins: Set<String>,
        internal val storage: PluginStorage,
    )

    internal data class RepositoryRefreshResult(
        val descriptors: List<ExtensionDescriptor>,
        val snapshots: Map<String, RepositoryIndexSnapshot>,
        /** Added repositories that now publish signed documents from a publisher not yet trusted here. */
        val repositoriesNeedingTrust: Set<String> = emptySet(),
    )

    public suspend fun refresh(repositories: List<ExtensionRepository>): List<ExtensionDescriptor> =
        refreshWithAdmissions(repositories).descriptors

    internal suspend fun refreshWithAdmissions(
        repositories: List<ExtensionRepository>,
    ): RepositoryRefreshResult = repositoryRefresher.refreshWithAdmissions(repositories)

    /**
     * ADR-0002 §6.3: what an update of a running, granted package would ask for, when D4 would not
     * carry the grant over — shown before anything is downloaded, so the installed version keeps
     * running until the user accepts (and keeps running if they do not). Null for a first install,
     * an update D4 continues, or a package that is not granted now (the regular flow applies).
     * Built from the index entry only (which must name the artifact's digest); the downloaded
     * artifact must be exactly that one before the grant is applied.
     */
    public suspend fun updateReviewPreview(repository: ExtensionRepository, entry: PluginIndexEntry): UpdateReviewPreview? =
        installer.updateReviewPreview(repository, entry)

    /** See [updateReviewPreview]; [stored] has no script and is never installed. */
    public class UpdateReviewPreview internal constructor(
        public val stored: StoredPlugin,
        public val review: PluginEventGrantReview,
    )

    /** The install key [entry] of [repository] has, or would have, on this device. */
    public suspend fun installKeyFor(repository: ExtensionRepository, entry: PluginIndexEntry): String? =
        repositoryRefresher.installKeyFor(repository, entry)

    public suspend fun install(
        repository: ExtensionRepository,
        entry: PluginIndexEntry,
        repositoryAdmission: RepositoryIndexAdmission? = null,
    ): ScriptPluginRuntime = installer.install(repository, entry, repositoryAdmission)

    public suspend fun update(
        repository: ExtensionRepository,
        entry: PluginIndexEntry,
        repositoryAdmission: RepositoryIndexAdmission? = null,
    ): ScriptPluginRuntime = install(repository, entry, repositoryAdmission)

    /** Installs Mihon metadata as non-executable stub sources, matching the Swift fallback. */
    public suspend fun installLegacy(
        repository: ExtensionRepository,
        entry: LegacyExtensionIndexEntry,
    ): List<CatalogueSource> = installer.installLegacy(repository, entry)

    public suspend fun uninstall(pluginId: String): Unit = installer.uninstall(pluginId)

    /**
     * Changes the package's execution grant and applies it to the live runtime immediately.
     * Revocation keeps the package metadata and bytes installed for UI management/uninstall.
     */
    public suspend fun setPluginTrusted(pluginId: String, trusted: Boolean): Unit =
        trustCoordinator.setPluginTrusted(pluginId, trusted)

    /**
     * ADR-0002 §8.1: takes every developer-mode (`~dev`) runtime down now. Called before the mode
     * is switched off, so no development build is ever live while the mode is off.
     */
    public suspend fun unloadDeveloperRuntimes(): Unit = runtimeLoader.unloadDeveloperRuntimes()

    /**
     * Reconstructs trusted packages independently. An untrusted, corrupt, or unloadable package
     * remains installed but cannot prevent other packages from becoming available.
     */
    public suspend fun loadInstalled(): List<CatalogueSource> = runtimeLoader.loadInstalled()

    public suspend fun installedPlugins(): List<StoredPlugin> = packageStore.list()

    /** Returns the verified exact-digest permission review, if this artifact requested host access. */
    public suspend fun pendingEventGrantReview(pluginId: String): PluginEventGrantReview? =
        trustCoordinator.pendingEventGrantReview(pluginId)

    /** True only when this exact installed digest may currently create executable runtimes. */
    public suspend fun hasExecutionApproval(pluginId: String): Boolean = trustCoordinator.hasExecutionApproval(pluginId)

    /** Explicitly approves the complete verified set, then rebuilds the runtime negotiation. */
    public suspend fun approveEventGrantReview(
        pluginId: String,
        expectedReview: PluginEventGrantReview,
        permissions: Set<PluginHostPermission>,
        selection: PluginGrantSelection? = null,
    ): Unit = trustCoordinator.approveEventGrantReview(pluginId, expectedReview, permissions, selection)

    /** Updates that kept the user's previous grant; the UI tells the user about each one. */
    public val grantContinuationNotices: kotlinx.coroutines.flow.SharedFlow<PluginGrantContinuationNotice>
        get() = state.continuationNotices

    /** The verified review of the installed artifact of [pluginId] (granted or not). */
    public suspend fun currentEventGrantReview(pluginId: String): PluginEventGrantReview? =
        trustCoordinator.currentEventGrantReview(pluginId)

    /** What the user granted the installed artifact of [pluginId], or null when it is not granted. */
    public suspend fun grantedSelection(pluginId: String): PluginGrantSelection? = trustCoordinator.grantedSelection(pluginId)

    /**
     * Applies source settings → permissions (ADR-0002 §6.5) to an installed plugin: sensitive items
     * can be withdrawn, and declared content host families switched on or off. The plugin reloads
     * under a new generation, so the old runtime's authority ends now.
     */
    public suspend fun narrowGrant(pluginId: String, selection: PluginGrantSelection): Unit =
        trustCoordinator.narrowGrant(pluginId, selection)

    public suspend fun revokeEventGrants(pluginId: String): Unit = trustCoordinator.revokeEventGrants(pluginId)

    public suspend fun catalogueSources(): List<CatalogueSource> = state.lifecycleMutex.withLock {
        state.sources.values.toList()
    }

    public suspend fun source(sourceId: Long): CatalogueSource? = state.lifecycleMutex.withLock { state.sources[sourceId] }

    /**
     * The exact key of source [sourceId] declared by the installed package [packageId], read from
     * its stored manifest. Unlike [extensionSourceV2] it needs no live runtime, so it also answers
     * for a package that is installed but waiting for its permission review: a title can be added
     * to the library under that key, and it opens once the package is approved.
     */
    public suspend fun installedSourceKey(packageId: String, sourceId: String): SourceKey? =
        legacyCompat.installedSourceKey(packageId, sourceId)

    /** Resolves a legacy UI row to its exact live manifest identity; a bare number is never enough. */
    public suspend fun exactSourceKeyForLegacyId(sourceId: Long): SourceKey? = legacyCompat.exactSourceKeyForLegacyId(sourceId)

    /**
     * Host-only exact storage lookup for source settings/import UI. Package identity is resolved
     * from the manager's live ownership map and cannot be supplied by extension code.
     */
    public suspend fun storageForSource(sourceId: Long): PluginStorage? = contentAccess.storageForSource(sourceId)

    /** Host-only content plane; its allowlist comes from the exact live source manifest. */
    public suspend fun contentNetworkForSource(sourceId: Long): PluginContentNetworkClient? =
        contentAccess.contentNetworkForSource(sourceId)

    /**
     * Exact v2 content scope for UI thumbnails and inline reader assets. Generic native runtimes
     * have no repository-defined network declaration and therefore remain denied.
     */
    public suspend fun contentNetworkScopeForSource(sourceKey: SourceKey): TypedReaderRemoteAssetScope? =
        contentAccess.contentNetworkScopeForSource(sourceKey)

    public suspend fun contentNetworkForSource(sourceKey: SourceKey): PluginContentNetworkClient? =
        contentAccess.contentNetworkForSource(sourceKey)

    /** Exact event lookup; a recycled legacy Long can never cross artifact ownership. */
    public suspend fun exactLegacySource(target: ExactPluginSourceTarget): CatalogueSource? =
        legacyCompat.exactLegacySource(target)

    /** Materializes the currently live v1 package as an exact-keyed Extension v2 runtime. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun extensionPackageRuntimeV2(pluginId: String): LegacyMangaPackageRuntimeV2? =
        legacyCompat.extensionPackageRuntimeV2(pluginId)

    /** Installs an already-admitted native v2 runtime; replacement and lifecycle are explicit. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun installExtensionRuntimeV2(
        runtime: ExtensionPackageRuntimeV2,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 = state.packageMutationMutex.withLock {
        state.invalidateWebChallengeGrants()
        val facade = state.extensionRegistryV2.install(runtime, replace)
        // A replacement must never inherit an earlier runtime's identity.
        state.nativeArtifactIdentities.remove(runtime.descriptor.packageId)
        (runtime as? ArtifactBoundExtensionPackageRuntimeV2)?.let {
            state.nativeArtifactIdentities[runtime.descriptor.packageId] = it.artifactIdentity
        }
        (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let {
            state.nativeLifecycleRuntimes[runtime.descriptor.packageId] = it
        }
        state.disabledNativeSources.removeAll { it.packageId == runtime.descriptor.packageId }
        facade
    }

    public suspend fun uninstallExtensionRuntimeV2(packageId: String): Boolean =
        state.packageMutationMutex.withLock {
            state.invalidateWebChallengeGrants()
            state.nativeArtifactIdentities.remove(packageId)
            state.nativeLifecycleRuntimes.remove(packageId)
            state.disabledNativeSources.removeAll { it.packageId == packageId }
            state.extensionRegistryV2.uninstall(packageId)
        }

    /** Host-gated facade for an admitted native runtime or a currently live legacy package. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun extensionFacadeV2(pluginId: String): ExtensionHostFacadeV2? =
        state.extensionRegistryV2.packageFacade(pluginId)
            ?: legacyCompat.extensionPackageRuntimeV2(pluginId)?.let { ExtensionHostFacadeV2(it) }

    /** Exact opaque source lookup used by the production browse/detail/content gateway. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? =
        extensionFacadeV2(sourceKey.packageId)?.source(sourceKey)

    /**
     * Issues one exact-runtime browser capability. Generic packages are deliberately denied an
     * embedded browser: browser origins in repository/manifest data are not host review.
     */
    internal suspend fun issueWebChallenge(requestedKey: SourceKey): WebChallengeAuthorization? =
        webChallenges.issueWebChallenge(requestedKey)

    /** Compatibility predicate; it does not issue import authority. */
    public suspend fun authorizeWebChallenge(sourceKey: SourceKey): Boolean = webChallenges.authorizeWebChallenge(sourceKey)

    /** Generic/legacy manifests are not equivalent to a host-reviewed embedded-browser scope. */
    public suspend fun authorizeWebChallenge(sourceId: Long): Boolean = false

    /** Dismissal consumes the exact token so stale platform callbacks cannot import later. */
    public suspend fun cancelWebChallenge(capability: SourceWebChallengeCapability): Unit =
        webChallenges.cancelWebChallenge(capability)

    /**
     * Consumes and revalidates an import under the package mutation lock, then completes every
     * storage write before replacement/unload/revocation can enter the same critical section.
     */
    public suspend fun importWebChallengeSession(
        sourceKey: SourceKey,
        capability: SourceWebChallengeCapability,
        cookies: List<SourceCookie>,
        userAgent: String,
        localStorage: Map<String, String>,
    ): Unit = webChallenges.importWebChallengeSession(sourceKey, capability, cookies, userAgent, localStorage)

    @OptIn(ExtensionImplementationApi::class)
    public suspend fun exactExtensionSource(target: ExactPluginSourceTarget): HostExtensionSourceV2? =
        legacyCompat.exactExtensionSource(target)

    /** Enables modal event permissions only for one foreground UI-triggered source invocation. */
    public suspend fun <T> withUserInteractionContext(sourceKey: SourceKey, block: suspend () -> T): T {
        val scopes = state.lifecycleMutex.withLock {
            state.eventScopes.values.filter { it.sourceKey == sourceKey }
        }
        // Legacy-backed V2 sources execute the same engine as the v1 UI path, but their opaque
        // SourceKey otherwise bypasses the old source-id wrapper.  Issue the host context handle
        // for the exact live scope across the complete detail/units/content call.
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        val interactionGranted = interactionMutex.withLock {
            if (!pluginUiAvailable) {
                false
            } else {
                scopes.forEach { scope ->
                    val next = (interactionCounts[scope.runtimeKey] ?: 0) + 1
                    interactionCounts[scope.runtimeKey] = next
                    if (next == 1) gateway?.setUserInteractionContext(scope, true)
                }
                true
            }
        }
        return try {
            // UI availability gates only modal host-event authority. The source operation itself
            // must still run after an already-started user action if a desktop foreground event is
            // delayed or the app moves to the background while network authentication is active.
            if (interactionGranted) {
                extensionSourceV2(sourceKey)?.withUserInteractionContext(block) ?: block()
            } else {
                block()
            }
        } finally {
            if (interactionGranted) {
                interactionMutex.withLock {
                    scopes.forEach { scope ->
                        val next = (interactionCounts[scope.runtimeKey] ?: 1) - 1
                        if (next <= 0) {
                            interactionCounts.remove(scope.runtimeKey)
                            gateway?.setUserInteractionContext(scope, false)
                        } else interactionCounts[scope.runtimeKey] = next
                    }
                }
            }
        }
    }

    /** Legacy UI row counterpart; resolves only the currently bound exact runtime scope. */
    public suspend fun <T> withUserInteractionContext(sourceId: Long, block: suspend () -> T): T {
        val scope = state.lifecycleMutex.withLock {
            state.eventScopes.values.singleOrNull { it.sourceKey.legacyLongId == sourceId }
        } ?: return block()
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        val interactionGranted = interactionMutex.withLock {
            if (!pluginUiAvailable) {
                false
            } else {
                val next = (interactionCounts[scope.runtimeKey] ?: 0) + 1
                interactionCounts[scope.runtimeKey] = next
                if (next == 1) gateway?.setUserInteractionContext(scope, true)
                true
            }
        }
        return try {
            block()
        } finally {
            if (interactionGranted) {
                interactionMutex.withLock {
                    val next = (interactionCounts[scope.runtimeKey] ?: 1) - 1
                    if (next <= 0) {
                        interactionCounts.remove(scope.runtimeKey)
                        gateway?.setUserInteractionContext(scope, false)
                    } else interactionCounts[scope.runtimeKey] = next
                }
            }
        }
    }

    public suspend fun <T> withVisibleEventContext(
        sourceKey: SourceKey,
        publicationId: String,
        unitId: String? = null,
        block: suspend () -> T,
    ): T {
        val scope = state.lifecycleMutex.withLock {
            state.eventScopes.values.singleOrNull { it.sourceKey == sourceKey }
        }
        val registry = environment.systemEventContextRegistry
            ?: (environment.systemEventSink as? PluginSystemEventGateway)?.contextRegistry
        if (registry == null || scope == null) return block()
        return registry.withInvocation(
            scope,
            dev.shinsou.kmp.plugin.events.PluginEventContextRegistry.VisibleContext(
                publicationId,
                unitId,
            ),
        ) { block() }
    }

    public suspend fun setPluginUiAvailable(available: Boolean) {
        val scopes = state.lifecycleMutex.withLock { state.eventScopes.values.toList() }
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        interactionMutex.withLock {
            pluginUiAvailable = available
            scopes.forEach { scope ->
                gateway?.setRuntimeLifecycle(
                    scope,
                    if (available) PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED
                    else PluginRuntimeLifecycle.OPEN_BACKGROUND,
                )
                if (!available) interactionCounts.remove(scope.runtimeKey)
            }
        }
        state.extensionRegistryV2.descriptors().forEach { descriptor ->
            val facade = state.extensionRegistryV2.packageFacade(descriptor.packageId) ?: return@forEach
            descriptor.sources.forEach { sourceDescriptor ->
                facade.source(sourceDescriptor.sourceKey)?.setHostUiAvailable(available)
            }
        }
    }

    public suspend fun setEventSourceEnabled(sourceKey: SourceKey, enabled: Boolean) {
        state.packageMutationMutex.withLock {
            state.invalidateWebChallengeGrants()
            if (!enabled) {
                val scopes = state.lifecycleMutex.withLock {
                    state.eventScopes.values.filter { it.sourceKey == sourceKey }
                }
                val gateway = environment.systemEventSink as? PluginSystemEventGateway
                scopes.forEach { gateway?.closeRuntime(it) }
                state.nativeLifecycleRuntimes[sourceKey.packageId]?.setSourceEnabled(sourceKey, false)
                state.disabledNativeSources += sourceKey
                return@withLock
            }
            val stored = packageStore.get(sourceKey.packageId) ?: return@withLock
            try {
                state.lifecycleMutex.withLock { runtimeLoader.loadStored(stored) }
                if (legacyCompat.sourceWhilePackageLocked(sourceKey) != null) state.disabledNativeSources.remove(sourceKey)
            } catch (_: PluginExecutionInertException) {
                // Reversible tier decision: keep the grant, build nothing.
            } catch (error: ScriptRuntimeUnavailableException) {
                // Approval is still a valid user decision, but this host cannot safely execute
                // the generic in-process engine. Keep the verified artifact installed and
                // explicitly inert rather than surfacing a misleading install failure or
                // leaving a durable grant that could resurrect on restart.
                Log.warn("Plugin", error) {
                    "Script runtime unavailable; disabling the plugin event source package"
                }
                state.locallyRevokedArtifacts += stored.artifactIdentity()
                executionAdmission.quarantineUnavailableRuntime(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
            }
        }
    }

    public suspend fun setEventSourceEnabled(sourceId: Long, enabled: Boolean) {
        val sourceKey = state.lifecycleMutex.withLock {
            state.eventScopes.values.singleOrNull { it.sourceKey.legacyLongId == sourceId }?.sourceKey
        } ?: return
        setEventSourceEnabled(sourceKey, enabled)
    }

    /**
     * The V2 sources the browse UI shows: native V2 packages, plus every script source that
     * produces text chapters (A5) — those read through the V2 publication/reader path, while
     * image-only script sources stay on the manga path.
     */
    public suspend fun extensionDescriptorsV2(): List<ExtensionPackageV2> {
        val native = state.extensionRegistryV2.descriptors()
        val nativeIds = native.mapTo(hashSetOf(), ExtensionPackageV2::packageId)
        val scripted = state.packageMutationMutex.withLock {
            packageStore.list().mapNotNull { stored ->
                if (stored.manifest.id in nativeIds) return@mapNotNull null
                // Manga-only packages (the common case) cost nothing here.
                if (stored.manifest.sources.orEmpty().none { "PLAIN_TEXT" in it.contentKinds } &&
                    stored.manifest.contentKinds.orEmpty().none { it == "PLAIN_TEXT" }
                ) return@mapNotNull null
                val runtime = runCatchingCancellable { legacyCompat.legacyPackageRuntimeV2WhilePackageLocked(stored.manifest.id) }.getOrNull()
                    ?: return@mapNotNull null
                val textSources = runtime.descriptor.sources.filter { ContentKind.PLAIN_TEXT in it.supportedContentKinds }
                if (textSources.isEmpty()) return@mapNotNull null
                runtime.descriptor.copy(
                    sources = textSources,
                    supportedContentKinds = textSources.flatMapTo(linkedSetOf()) { it.supportedContentKinds },
                )
            }
        }
        return native + scripted
    }

    /**
     * What the added repositories last said they publish, installed here or not. Empty until a
     * repository has been refreshed in this run; starts nothing.
     */
    public fun knownRepositoryPackages(): Collection<ExtensionDescriptor> = repositoryRefresher.knownRepositoryPackages()

    /** Forgets what repositories other than [baseUrls] published: they are no longer added. */
    public fun retainRepositoryPackages(baseUrls: Collection<String>): Unit =
        repositoryRefresher.retainRepositoryPackages(baseUrls)

    /** The V2 packages loaded right now (native ones and adapted script packages); starts nothing. */
    public suspend fun loadedExtensionDescriptorsV2(): List<ExtensionPackageV2> = state.extensionRegistryV2.descriptors()

    public suspend fun close(): Unit = runtimeLoader.close()
}
