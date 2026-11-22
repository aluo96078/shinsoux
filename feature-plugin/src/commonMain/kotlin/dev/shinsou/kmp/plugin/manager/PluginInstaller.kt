@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.manager

import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.plugin.contracts.CatalogueSource
import dev.shinsou.kmp.plugin.contracts.FilterList
import dev.shinsou.kmp.plugin.contracts.MangasPage
import dev.shinsou.kmp.plugin.contracts.Page
import dev.shinsou.kmp.plugin.contracts.SChapter
import dev.shinsou.kmp.plugin.contracts.SManga
import dev.shinsou.kmp.plugin.model.ExtensionRepository
import dev.shinsou.kmp.plugin.model.GENERIC_V2_CONTRACT
import dev.shinsou.kmp.plugin.model.GENERIC_V2_RUNTIME
import dev.shinsou.kmp.plugin.model.InstalledPluginMetadata
import dev.shinsou.kmp.plugin.model.LegacyExtensionIndexEntry
import dev.shinsou.kmp.plugin.model.PluginIndexEntry
import dev.shinsou.kmp.plugin.model.PluginInstallKeys
import dev.shinsou.kmp.plugin.model.PluginManifest
import dev.shinsou.kmp.plugin.model.PluginNamePolicy
import dev.shinsou.kmp.plugin.model.SourceIndexEntry
import dev.shinsou.kmp.plugin.model.StoredPlugin
import dev.shinsou.kmp.plugin.model.artifactIdentity
import dev.shinsou.kmp.plugin.model.eventGrantReview
import dev.shinsou.kmp.plugin.model.requireSupportedGenericRuntime
import dev.shinsou.kmp.plugin.repository.RepositoryIndexAdmission
import dev.shinsou.kmp.plugin.runtime.SandboxTierCeiling
import dev.shinsou.kmp.plugin.runtime.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.runtime.ScriptRuntimeUnavailableException
import dev.shinsou.kmp.plugin.runtime.stableSourceId
import dev.shinsou.kmp.plugin.storage.requireValidPluginPackageId
import dev.shinsou.kmp.plugin.storage.requireValidStoredPluginPackage
import dev.shinsou.kmp.plugin.trust.PluginGrantContinuationNotice
import dev.shinsou.kmp.plugin.trust.PluginVerifier

/** Installs, updates and uninstalls packages; every commit and rollback runs under [PluginManager]'s locks. */
internal class PluginInstaller(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
    private val executionAdmission: PluginExecutionAdmission,
    private val runtimeLoader: PluginRuntimeLoader,
    private val trustCoordinator: PluginTrustCoordinator,
    private val repositoryRefresher: PluginRepositoryRefresher,
) {
    private val repositoryClient = deps.repositoryClient
    private val packageStore = deps.packageStore
    private val verifier = deps.verifier
    private val eventGrantAdmission = deps.eventGrantAdmission
    private val executionTierGate = deps.executionTierGate
    private val defaultDispatcher = deps.defaultDispatcher

    private fun manifestFor(installKey: String, entry: PluginIndexEntry, signature: String): PluginManifest = PluginManifest(
        id = installKey,
        name = entry.name,
        version = entry.version,
        versionCode = entry.versionCode,
        lang = entry.lang,
        nsfw = entry.nsfw == 1,
        script = "$installKey.js",
        signature = signature,
        minRuntimeVersion = entry.minRuntimeVersion,
        sources = entry.sources?.let { sources -> localSources(installKey, sources) },
        contentKinds = entry.contentKinds.takeIf {
            entry.sidecarUrl != null ||
                entry.sources.orEmpty().any { source -> source.contentKindsDeclared != null }
        },
        systemEvents = entry.systemEvents,
        requestedHostPermissions = entry.requestedHostPermissions,
        runtimePermissions = entry.runtimePermissions,
        contract = entry.contract,
        runtime = entry.runtime,
        sidecarUrl = entry.sidecarUrl,
    )

    /**
     * ADR-0002 §6.3: what an update of a running, granted package would ask for, when D4 would not
     * carry the grant over — shown before anything is downloaded, so the installed version keeps
     * running until the user accepts (and keeps running if they do not). Null for a first install,
     * an update D4 continues, or a package that is not granted now (the regular flow applies).
     * Built from the index entry only (which must name the artifact's digest); the downloaded
     * artifact must be exactly that one before the grant is applied.
     */
    suspend fun updateReviewPreview(repository: ExtensionRepository, entry: PluginIndexEntry): PluginManager.UpdateReviewPreview? {
        val installKey = repositoryRefresher.installKeyResolver(repository)(entry.id) ?: return null
        val previousStored = packageStore.get(installKey) ?: return null
        val previousReview = previousStored.eventGrantReview(includeEmpty = true) ?: return null
        if (!executionAdmission.executionAdmissionGranted(previousReview)) return null
        val publisherId = executionTierGate?.publisherForRepository(repositoryClient.canonicalBaseUrl(repository.baseUrl))
        // The approval must name the exact artifact: an index without digests (legacy, unsigned)
        // learns it only at download, so its update takes the regular review after installing.
        val digest = entry.sha256?.trim()?.lowercase() ?: return null
        val preview = StoredPlugin(
            metadata = InstalledPluginMetadata(
                manifest = manifestFor(installKey, entry, signature = digest),
                repositoryBaseUrl = repository.baseUrl,
                installedSha256 = digest,
                legacyStorageMigrationAllowed = false,
                publisherId = publisherId,
            ),
            scriptBytes = ByteArray(0),
        )
        val review = preview.eventGrantReview(includeEmpty = true) ?: return null
        if (trustCoordinator.continuedGrantFor(previousStored, previousReview, preview, review) != null) return null
        return PluginManager.UpdateReviewPreview(preview, review)
    }

    suspend fun install(
        repository: ExtensionRepository,
        entry: PluginIndexEntry,
        repositoryAdmission: RepositoryIndexAdmission? = null,
    ): ScriptPluginRuntime =
        withContext(defaultDispatcher) {
            PluginVerifier.validateSafeFileComponent(entry.id)
            require(entry.installable && !entry.referenceOnly && !entry.legacyCompatibilityOnly) {
                "Plugin '${entry.id}' is migration/reference metadata and cannot be installed"
            }
            requireSupportedGenericRuntime(entry)
            SandboxTierCeiling.requireWithinCeiling(entry)
            PluginNamePolicy.requireNotMisleading(entry.name, *entry.sources.orEmpty().map { it.name }.toTypedArray())
            require(entry.runtimePermissions != null) {
                "New plugin '${entry.id}' must declare runtimePermissions"
            }
            // The artifact is fetched before taking packageMutationMutex: that lock is also taken
            // by every reader image request, by uninstall and by repository refresh, and a slow
            // repository must not hold all of them for the length of a download. Nothing fetched
            // here is trusted yet: every check against installed and repository state runs again
            // under the lock, on these bytes.
            if (entry.sha256 != null) {
                repositoryClient.requireArtifactNotDowngraded(repository.baseUrl, entry, repositoryAdmission)
            }
            repositoryClient.verifyPluginV2Sidecar(repository.baseUrl, entry)
            val scriptBytes = repositoryClient.downloadPluginScript(repository.baseUrl, entry.scriptUrl)
            require(entry.byteSize == null || scriptBytes.size == entry.byteSize) {
                "Plugin '${entry.id}' artifact size does not match its V2 declaration"
            }
            state.packageMutationMutex.withLock {
            state.invalidateWebChallengeGrants()
            // The publisher is fixed by the repository's pinned key, never by what the index says.
            // Keyed exactly as the client keys trust: an equivalent spelling must not miss the publisher.
            val canonicalRepository = repositoryClient.canonicalBaseUrl(repository.baseUrl)
            val publisherId = executionTierGate?.publisherForRepository(canonicalRepository)
            // D6: the local identity, so another publisher's package with the same id installs beside it.
            val installKey = requireNotNull(repositoryRefresher.installKeyResolver(repository)(entry.id)) {
                "Plugin '${entry.id}' cannot be installed under a local identity"
            }
            PluginVerifier.validateSafeFileComponent(installKey)
            val previousStored = packageStore.get(installKey)
            val previousVersionCode = previousStored?.manifest?.versionCode
                ?: previousStored?.manifest?.version?.let(PluginVerifier::versionInt)
            require(previousVersionCode == null || entry.versionCode >= previousVersionCode) {
                "Plugin '${entry.id}' downgrade is not allowed"
            }
            if (entry.sha256 != null) {
                repositoryClient.requireArtifactNotDowngraded(repository.baseUrl, entry, repositoryAdmission)
            }
            val actualHash = Sha256.hex(scriptBytes)
            val expectedHash = entry.sha256?.trim()?.lowercase()
            if (publisherId != null) executionTierGate.requireInstallable(entry.id, publisherId, actualHash)
            // Current repositories may have no digest field. Hashing the received bytes and
            // recording the resulting trust token preserves Shinsou's legacy TOFU behavior.
            val manifest = manifestFor(installKey, entry, signature = expectedHash ?: actualHash)
            // Installation validates integrity but permission review remains the sole execution
            // admission. The older trust token is retained for explicit user revocation and
            // persisted-byte tamper checks; it must never cause runtime construction here.
            val verified = verifier.verify(scriptBytes, manifest)
            val stored = StoredPlugin(
                metadata = InstalledPluginMetadata(
                    manifest = manifest,
                    repositoryBaseUrl = repository.baseUrl,
                    installedSha256 = verified.sha256,
                    legacyTrustOnInstall = expectedHash == null,
                    legacyStorageMigrationAllowed = false,
                    publisherId = publisherId,
                ),
                scriptBytes = scriptBytes,
            )
            requireValidStoredPluginPackage(stored)
            val grantReview = requireNotNull(stored.eventGrantReview(includeEmpty = true))
            val previousReview = previousStored?.eventGrantReview(includeEmpty = true)
            // D4: an update that asks for nothing new keeps the user's decision (the user is told).
            val continuedGrant = trustCoordinator.continuedGrantFor(previousStored, previousReview, stored, grantReview)
            if (continuedGrant != null) {
                val admission = requireNotNull(eventGrantAdmission)
                admission.stage(grantReview)
                admission.approve(grantReview.artifact, continuedGrant.hostPermissions, continuedGrant)
            }
            val alreadyGranted = executionAdmission.executionAdmissionGranted(grantReview)
            val reservation = state.lifecycleMutex.withLock { runtimeLoader.reserveManifestSources(manifest) }
            var candidates: List<ScriptPluginRuntime> = emptyList()
            var committed = false
            var liveStateReplaced = false
            try {
                require(state.extensionRegistryV2.packageFacade(installKey) == null) {
                    "Extension package '${entry.id}' is already loaded by the V2 runtime"
                }
                if (alreadyGranted) {
                    candidates = runtimeLoader.createRuntimeCandidates(stored)
                }
                val compatibilityResult = candidates.takeIf(List<*>::isNotEmpty)
                    ?.let { runtimeCompatibilityResult(manifest, candidates) }
                    ?: InertScriptPluginRuntimeHandle(manifest)
                // Downloading, verification and runtime construction remain cancellable. Once the
                // durable/live commit starts, finish both halves so cancellation cannot leave a new
                // package paired with the previous runtime (or no runtime on first installation).
                currentCoroutineContext().ensureActive()
                // Permission approval is mutable state. Recheck it immediately before crossing
                // the durable/live commit boundary so a concurrent or external revocation cannot
                // race a runtime into existence from a stale decision.
                if (candidates.isNotEmpty() && !executionAdmission.executionAdmissionGranted(grantReview)) {
                    runtimeLoader.closeRuntimes(candidates)
                    candidates = emptyList()
                }
                if (candidates.isNotEmpty()) {
                    state.lifecycleMutex.withLock { runtimeLoader.validateRuntimeCandidates(installKey, candidates) }
                }
                withContext(NonCancellable) {
                    packageStore.put(stored)
                    requirePersistedPackageMatches(stored)
                    state.lifecycleMutex.withLock {
                        if (candidates.isEmpty()) runtimeLoader.unloadPlugin(installKey)
                        else runtimeLoader.replaceRuntimes(installKey, candidates)
                        // The live maps have changed as soon as unload/replace returns. Mark that
                        // boundary before committing the inert reservation: if the latter throws,
                        // rollback must rebuild the previous runtime instead of merely closing the
                        // candidate and leaving the live map pointing at that closed object.
                        liveStateReplaced = true
                        runtimeLoader.commitReservation(reservation)
                    }
                    if (entry.sha256 != null) {
                        repositoryClient.recordCommittedArtifact(
                            repository.baseUrl,
                            entry,
                            verified.sha256,
                            repositoryAdmission,
                        )
                    }
                    committed = true
                }
                // An obsolete exact-artifact grant cannot authorize the replacement runtime. It
                // is removed only after the new durable/live state is committed, so a failed
                // update never destroys the still-installed version's approval. Cleanup failure
                // is safe (the key is artifact-bound) and must not roll back a valid new commit.
                if (previousReview != null && previousReview.artifact != grantReview.artifact) {
                    // The new version is committed: a cancel arriving here must not unload it.
                    withContext(NonCancellable) {
                        runCatchingCancellable { eventGrantAdmission?.revoke(previousReview.artifact) }
                    }
                }
                // Restore a matching in-memory authorizer or stage a new review only after the
                // replacement is durable and the old runtime has closed. A failed update cannot
                // grant the candidate or revoke the still-installed artifact's authorization.
                eventGrantAdmission?.hydrate(grantReview)
                if (continuedGrant != null && previousStored != null) {
                    state.continuationNotices.tryEmit(
                        PluginGrantContinuationNotice(installKey, entry.name, previousStored.manifest.version, entry.version),
                    )
                }
                return@withLock compatibilityResult
            } catch (error: Throwable) {
                if (!committed) {
                    withContext(NonCancellable) {
                        if (!liveStateReplaced) {
                            runCatchingCancellable { runtimeLoader.closeRuntimes(candidates) }
                                .exceptionOrNull()?.let(error::addSuppressed)
                        }
                        runCatchingCancellable {
                            if (previousStored == null) packageStore.remove(installKey)
                            else packageStore.put(previousStored)
                        }.exceptionOrNull()?.let(error::addSuppressed)
                        if (liveStateReplaced) {
                            runCatchingCancellable { runtimeLoader.restorePreviousRuntime(installKey, previousStored) }
                                .onFailure { restoreFailure ->
                                    error.addSuppressed(restoreFailure)
                                    // If restoration itself fails, do not leave the replacement
                                    // artifact live beside an uncertain durable package.
                                    runCatchingCancellable {
                                        state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(installKey) }
                                    }.exceptionOrNull()?.let(error::addSuppressed)
                                }
                        }
                        if ((!alreadyGranted || continuedGrant != null) && previousReview?.artifact != grantReview.artifact) {
                            runCatchingCancellable { eventGrantAdmission?.revoke(grantReview.artifact) }
                                .exceptionOrNull()?.let(error::addSuppressed)
                        }
                    }
                } else {
                    // The package and anti-rollback ledger are already committed, but admission
                    // hydration/staging failed. Keep the installed artifact manageable while
                    // making its executable state explicitly inert; returning a live runtime here
                    // would make a storage outage an execution-admission bypass.
                    withContext(NonCancellable) {
                        runCatchingCancellable {
                            state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(installKey) }
                        }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
                withContext(NonCancellable) {
                    runCatchingCancellable { state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) } }
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
        }

    /** Installs Mihon metadata as non-executable stub sources, matching the Swift fallback. */
    suspend fun installLegacy(
        repository: ExtensionRepository,
        entry: LegacyExtensionIndexEntry,
    ): List<CatalogueSource> = withContext(defaultDispatcher) {
        state.packageMutationMutex.withLock {
        state.invalidateWebChallengeGrants()
        PluginVerifier.validateSafeFileComponent(entry.pkg)
        val manifest = PluginManifest(
            id = entry.pkg,
            name = entry.name,
            version = entry.version,
            versionCode = entry.code,
            lang = entry.lang,
            nsfw = entry.nsfw == 1,
            script = "${entry.pkg}.js",
            signature = "",
            sources = entry.sources,
        )
        val stored = StoredPlugin(
            InstalledPluginMetadata(
                manifest,
                repository.baseUrl,
                "",
                legacyTrustOnInstall = true,
                legacyStorageMigrationAllowed = false,
            ),
            ByteArray(0),
        )
        val previous = packageStore.get(entry.pkg)
        val reservation = state.lifecycleMutex.withLock { runtimeLoader.reserveManifestSources(manifest) }
        currentCoroutineContext().ensureActive()
        try {
            withContext(NonCancellable) {
                packageStore.put(stored)
                requirePersistedPackageMatches(stored)
                verifier.trustMetadataOnly(manifest, stored.metadata.installedSha256)
                state.lifecycleMutex.withLock {
                    runtimeLoader.unloadPlugin(entry.pkg)
                    runtimeLoader.addMetadataSources(stored).also {
                        runtimeLoader.commitReservation(reservation)
                    }
                }
            }
        } catch (error: Throwable) {
            // Trust-store failure happens after the package commit point. Restore the previous
            // durable package so the current live sources still describe what will load next time.
            withContext(NonCancellable) {
                runCatchingCancellable {
                    if (previous == null) packageStore.remove(entry.pkg) else packageStore.put(previous)
                }
            }
            state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) }
            throw error
        }
        }
    }

    @Suppress("SuspendFunSwallowedCancellation") // Teardown runs under NonCancellable; the captured removal failure is rethrown as the primary error.
    suspend fun uninstall(pluginId: String) {
        val failures = withContext(defaultDispatcher) {
            state.packageMutationMutex.withLock {
            requireValidPluginPackageId(pluginId)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                // User intent is the security commit point. Install the process-local denial
                // before calling any fallible persistence boundary, then attempt every teardown
                // independently. A package-store outage must never leave script/native facades,
                // browser capabilities, or host-event authority live.
                state.locallyRevokedPluginIds += pluginId
                state.invalidateWebChallengeGrants()
                val cleanupFailures = mutableListOf<Throwable>()
                var removalFailure: Throwable? = null
                fun captureCleanup(error: Throwable) {
                    cleanupFailures += error
                }

                val artifacts = linkedSetOf<PluginArtifactIdentity>().apply {
                    state.nativeArtifactIdentities[pluginId]?.let(::add)
                    state.lifecycleMutex.withLock {
                        state.eventScopes.values
                            .filter { it.artifactIdentity.packageId == pluginId }
                            .mapTo(this@apply, BoundPluginScope::artifactIdentity)
                    }
                }
                state.locallyRevokedArtifacts += artifacts

                // Make all already-published entry points unreachable before consulting any
                // potentially slow durable store. Legacy unload closes its event generations;
                // native registry removal detaches the facade before awaiting runtime close.
                runCatchingCancellable { state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(pluginId) } }
                    .exceptionOrNull()?.let(::captureCleanup)
                state.nativeArtifactIdentities.remove(pluginId)
                state.nativeLifecycleRuntimes.remove(pluginId)
                state.disabledNativeSources.removeAll { it.packageId == pluginId }
                runCatchingCancellable { state.extensionRegistryV2.uninstall(pluginId) }
                    .exceptionOrNull()?.let(::captureCleanup)
                artifacts.forEach { artifact ->
                    runCatchingCancellable { eventGrantAdmission?.revoke(artifact) }
                        .exceptionOrNull()?.let(::captureCleanup)
                }

                val stored = runCatchingCancellable { packageStore.get(pluginId) }
                    .onFailure(::captureCleanup)
                    .getOrNull()
                stored?.artifactIdentity()?.let {
                    if (artifacts.add(it)) {
                        state.locallyRevokedArtifacts += it
                        runCatchingCancellable { eventGrantAdmission?.revoke(it) }
                            .exceptionOrNull()?.let(::captureCleanup)
                    }
                }
                // Generic execution trust is a second durable authority. Revoke it even if the
                // package bytes remain available for a later uninstall retry.
                runCatchingCancellable { verifier.revokeAll(pluginId) }
                    .exceptionOrNull()?.let(::captureCleanup)
                // Legacy trust-on-install is deliberately readable without the secure trust
                // store. Clear that durable compatibility bit before deletion so a retained
                // package cannot execute after restart merely because remove() failed.
                stored?.takeIf { it.metadata.legacyTrustOnInstall }?.let { installed ->
                    runCatchingCancellable {
                        packageStore.put(
                            installed.copy(
                                metadata = installed.metadata.copy(legacyTrustOnInstall = false),
                            ),
                        )
                    }.exceptionOrNull()?.let(::captureCleanup)
                }

                try {
                    packageStore.remove(pluginId)
                } catch (error: Throwable) {
                    removalFailure = error
                }

                // Only a confirmed durable deletion can retire the denial floor. If deletion
                // fails, retained bytes remain inert for this manager and on restart because the
                // durable trust/event grants above were revoked.
                if (removalFailure == null && cleanupFailures.isEmpty()) {
                    state.locallyRevokedPluginIds.remove(pluginId)
                    state.locallyRevokedArtifacts.removeAll { it.packageId == pluginId }
                }
                removalFailure to cleanupFailures.toList()
            }
        }
        }

        // Assemble and throw after crossing the coroutine context boundary. Coroutine stack-trace
        // recovery may copy a thrown exception at that boundary without its suppressed failures.
        // Returning the failures as data preserves the package-store error as the observable
        // primary exception while retaining every independent teardown error for diagnostics.
        failures.first?.let { primary ->
            failures.second.filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
        failures.second.firstOrNull()?.let { primary ->
            failures.second.drop(1).filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }

    /**
     * The sources as this device runs them (D6): unchanged for a bare install key; for a
     * publisher-scoped one each numeric id is projected from its exact identity, and the published
     * id is kept as the canonical source id the script selects.
     */
    private fun localSources(installKey: String, sources: List<SourceIndexEntry>): List<SourceIndexEntry> {
        if (!PluginInstallKeys.isPublisherScoped(installKey)) return sources
        return sources.map { source ->
            val sourceId = source.canonicalSourceId ?: source.id.toString()
            source.copy(id = PluginInstallKeys.projectedSourceId(installKey, sourceId), canonicalSourceId = sourceId)
        }
    }

    /** Confirms the package commit before any prebuilt runtime becomes live. */
    private suspend fun requirePersistedPackageMatches(expected: StoredPlugin) {
        val persisted = requireNotNull(packageStore.get(expected.manifest.id)) {
            "Plugin package commit could not be reconstructed"
        }
        requireValidStoredPluginPackage(persisted)
        require(persisted.metadata == expected.metadata &&
            persisted.scriptBytes.contentEquals(expected.scriptBytes)
        ) { "Persisted plugin package differs from its verified installation bytes" }
    }

    /** Existing install callers receive the single v1 source, or an explicit non-source handle. */
    private fun runtimeCompatibilityResult(
        manifest: PluginManifest,
        candidates: List<ScriptPluginRuntime>,
    ): ScriptPluginRuntime = candidates.singleOrNull()
        ?: MultiSourceScriptPluginRuntimeHandle(manifest, candidates)
}

private fun requireSupportedGenericRuntime(entry: PluginIndexEntry) {
    val isV2 = entry.sidecarUrl != null || entry.contract != null || entry.runtime != null
    if (!isV2) return
    require(entry.contract == GENERIC_V2_CONTRACT && entry.runtime == GENERIC_V2_RUNTIME) {
        "Unsupported generic V2 contract/runtime '${entry.contract}'/'${entry.runtime}'"
    }
}

/**
 * Compatibility-only return value for the historical `install(): ScriptPluginRuntime` surface.
 * It deliberately cannot execute catalogue calls because doing so would reintroduce an implicit
 * "first source" decision. Callers select one of [PluginManager.catalogueSources] instead.
 */
private class MultiSourceScriptPluginRuntimeHandle(
    private val manifest: PluginManifest,
    private val delegates: List<ScriptPluginRuntime>,
) : ScriptPluginRuntime {
    override val pluginId: String = manifest.id
    override val id: Long = stableSourceId("${manifest.id}:multi-source-handle")
    override val name: String = manifest.name
    override val lang: String = manifest.lang
    override val baseUrl: String = ""
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String>
        get() = delegates.flatMap(ScriptPluginRuntime::recentLogs)

    override suspend fun getPopularManga(page: Int): MangasPage = ambiguous()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = ambiguous()
    override suspend fun getLatestUpdates(page: Int): MangasPage = ambiguous()
    override suspend fun getFilterList(): FilterList = ambiguous()
    override suspend fun getMangaDetails(manga: SManga): SManga = ambiguous()
    override suspend fun getChapterList(manga: SManga): List<SChapter> = ambiguous()
    override suspend fun getPageList(chapter: SChapter): List<Page> = ambiguous()
    override suspend fun login(username: String, password: String): Boolean = ambiguous()
    override suspend fun logout(): Unit = ambiguous()

    // The package manager owns the real source runtimes; closing a compatibility view must not
    // tear down live sources behind other consumers.
    override suspend fun close(): Unit = Unit

    private fun ambiguous(): Nothing = throw ScriptRuntimeUnavailableException(
        "Plugin '${manifest.id}' contains multiple sources; select an exact source id",
    )
}

/** Non-executable receipt returned when bytes were installed but exact-artifact review is pending. */
private class InertScriptPluginRuntimeHandle(
    private val manifest: PluginManifest,
) : ScriptPluginRuntime {
    override val pluginId: String = manifest.id
    override val id: Long = stableSourceId("${manifest.id}:pending-permission-review")
    override val name: String = manifest.name
    override val lang: String = manifest.lang
    override val baseUrl: String = ""
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = denied()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = denied()
    override suspend fun getLatestUpdates(page: Int): MangasPage = denied()
    override suspend fun getFilterList(): FilterList = denied()
    override suspend fun getMangaDetails(manga: SManga): SManga = denied()
    override suspend fun getChapterList(manga: SManga): List<SChapter> = denied()
    override suspend fun getPageList(chapter: SChapter): List<Page> = denied()
    override suspend fun login(username: String, password: String): Boolean = denied()
    override suspend fun logout(): Unit = denied()
    override suspend fun close(): Unit = Unit

    private fun denied(): Nothing = throw ScriptRuntimeUnavailableException(
        "Plugin '${manifest.id}' is installed but awaits exact-artifact permission approval",
    )
}
