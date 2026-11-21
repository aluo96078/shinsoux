@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stands in for a publisher in D4 between two builds of one developer-mode package; never a real key id. */
private const val DEVELOPER_CONTINUATION_PUBLISHER: String = "developer-mode:local"

/** Execution grants of installed packages: approval, narrowing, revocation and D4 continuation. */
internal class PluginTrustCoordinator(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
    private val executionAdmission: PluginExecutionAdmission,
    private val runtimeLoader: PluginRuntimeLoader,
) {
    private val repositoryClient = deps.repositoryClient
    private val packageStore = deps.packageStore
    private val verifier = deps.verifier
    private val eventGrantAdmission = deps.eventGrantAdmission
    private val executionAdmissionMode = deps.executionAdmissionMode
    private val executionTierGate = deps.executionTierGate
    private val defaultDispatcher = deps.defaultDispatcher

    /**
     * Changes the package's execution grant and applies it to the live runtime immediately.
     * Revocation keeps the package metadata and bytes installed for UI management/uninstall.
     */
    suspend fun setPluginTrusted(pluginId: String, trusted: Boolean): Unit =
        withContext(defaultDispatcher) {
        state.packageMutationMutex.withLock packageMutation@{
            state.invalidateWebChallengeGrants()
            val stored = packageStore.get(pluginId)
                ?: throw IllegalArgumentException("Extension '$pluginId' is not installed")
            if (!trusted) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    // Install the denial before touching fallible stores. Even a total storage
                    // outage cannot make a later in-process reload revive this package.
                    state.locallyRevokedPluginIds += pluginId
                    state.locallyRevokedArtifacts += stored.artifactIdentity()
                    var failure: Throwable? = null
                    fun capture(error: Throwable) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }

                    // Attempt every durable revocation even if an earlier store is unavailable;
                    // independent stores may still successfully remove their authority.
                    if (stored.metadata.legacyTrustOnInstall) {
                        runCatchingCancellable {
                            packageStore.put(
                                stored.copy(
                                    metadata = stored.metadata.copy(legacyTrustOnInstall = false),
                                ),
                            )
                        }.exceptionOrNull()?.let(::capture)
                    }
                    runCatchingCancellable { verifier.revokeAll(pluginId) }
                        .exceptionOrNull()?.let(::capture)
                    runCatchingCancellable { eventGrantAdmission?.revoke(stored.artifactIdentity()) }
                        .exceptionOrNull()?.let(::capture)

                    // Revocation is fail-closed in memory. A durable-store failure must never
                    // leave attacker-controlled code running with the permissions it had before
                    // the user requested revocation.
                    runCatchingCancellable { state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(pluginId) } }
                        .exceptionOrNull()?.let(::capture)
                    failure?.let { throw it }
                }
                return@packageMutation
            }

            if (stored.isMetadataOnly()) {
                val reservation = state.lifecycleMutex.withLock { runtimeLoader.reserveManifestSources(stored.manifest) }
                currentCoroutineContext().ensureActive()
                try {
                    withContext(NonCancellable) {
                        verifier.trustMetadataOnly(stored.manifest, stored.metadata.installedSha256)
                        state.locallyRevokedPluginIds.remove(pluginId)
                        state.locallyRevokedArtifacts.remove(stored.artifactIdentity())
                        state.lifecycleMutex.withLock {
                            runtimeLoader.unloadPlugin(pluginId)
                            runtimeLoader.addMetadataSources(stored)
                            runtimeLoader.commitReservation(reservation)
                        }
                    }
                } catch (error: Throwable) {
                    state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) }
                    throw error
                }
                return@packageMutation
            }

            if (!stored.hasExecutableRuntimeDeclaration()) {
                state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(pluginId) }
                return@packageMutation
            }

            val wasTrusted = verifier.isTrusted(stored.manifest, stored.metadata.installedSha256)
            verifier.trustInstalled(
                stored.scriptBytes,
                stored.manifest,
                stored.metadata.installedSha256,
            )
            val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
            // The legacy developer mode treats this toggle as its explicit execution action.
            // Production exact admission may clear a revocation tombstone only through approve().
            if (executionAdmissionMode == PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY) {
                state.locallyRevokedPluginIds.remove(pluginId)
                state.locallyRevokedArtifacts.remove(review.artifact)
            }
            if (!executionAdmission.executionAdmissionGranted(review)) {
                eventGrantAdmission?.hydrate(review)
                return@packageMutation
            }
            val reservation = state.lifecycleMutex.withLock { runtimeLoader.reserveManifestSources(stored.manifest) }
            val candidates = try {
                require(state.extensionRegistryV2.packageFacade(pluginId) == null) {
                    "Extension package '$pluginId' is already loaded by the V2 runtime"
                }
                runtimeLoader.createRuntimeCandidates(stored)
            } catch (inert: PluginExecutionInertException) {
                // Publisher trust or the block list keeps it inert for now; that is reversible,
                // so the user's grant stays.
                state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) }
                throw inert
            } catch (error: ScriptRuntimeUnavailableException) {
                // A user trust toggle cannot override the host's in-process isolation boundary.
                // Keep the verified package installed, but convert the old grant to a pending
                // review so this same artifact cannot be resurrected on the next restart.
                Log.warn("Plugin", error) {
                    "Script runtime unavailable; keeping the plugin package installed but inert"
                }
                state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) }
                state.locallyRevokedArtifacts += review.artifact
                executionAdmission.quarantineUnavailableRuntime(review)
                return@packageMutation
            } catch (error: Throwable) {
                state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) }
                if (!wasTrusted) {
                    withContext(NonCancellable) { verifier.revokeAll(pluginId) }
                }
                throw error
            }
            var committed = false
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    state.lifecycleMutex.withLock {
                        runtimeLoader.replaceRuntimes(pluginId, candidates)
                        runtimeLoader.commitReservation(reservation)
                    }
                    committed = true
                }
            } catch (error: Throwable) {
                if (!committed) runtimeLoader.closeRuntimes(candidates)
                state.lifecycleMutex.withLock { runtimeLoader.rollbackReservation(reservation) }
                if (!committed && !wasTrusted) {
                    withContext(NonCancellable) { verifier.revokeAll(pluginId) }
                }
                throw error
            }
        }
        }

    /** Returns the verified exact-digest permission review, if this artifact requested host access. */
    suspend fun pendingEventGrantReview(pluginId: String): PluginEventGrantReview? =
        state.packageMutationMutex.withLock {
            val stored = packageStore.get(pluginId) ?: return@withLock null
            val artifact = stored.artifactIdentity()
            if (artifact in state.runtimeUnavailableArtifacts) return@withLock null
            eventGrantAdmission?.pending(artifact)
        }

    /** True only when this exact installed digest may currently create executable runtimes. */
    suspend fun hasExecutionApproval(pluginId: String): Boolean = state.packageMutationMutex.withLock {
        val stored = packageStore.get(pluginId) ?: return@withLock false
        if (stored.isMetadataOnly()) return@withLock true
        if (!stored.hasExecutableRuntimeDeclaration()) return@withLock false
        executionAdmission.executionAdmissionGranted(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
    }

    /** Explicitly approves the complete verified set, then rebuilds the runtime negotiation. */
    suspend fun approveEventGrantReview(
        pluginId: String,
        expectedReview: PluginEventGrantReview,
        permissions: Set<PluginHostPermission>,
        selection: PluginGrantSelection? = null,
    ): Unit = state.packageMutationMutex.withLock {
        state.invalidateWebChallengeGrants()
        val stored = requireNotNull(packageStore.get(pluginId)) { "Extension '$pluginId' is not installed" }
        if (!stored.hasExecutableRuntimeDeclaration()) {
            withContext(NonCancellable) { state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(pluginId) } }
            throw IllegalArgumentException(
                "Extension '$pluginId' has no admissible executable runtime declaration",
            )
        }
        val admission = requireNotNull(eventGrantAdmission) { "Plugin event grant admission is unavailable" }
        val currentReview = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        require(expectedReview.artifact.packageId == pluginId) {
            "Reviewed plugin identity does not match the requested extension"
        }
        require(expectedReview == currentReview) {
            "Installed plugin artifact or requested permissions changed after review"
        }
        require(admission.pending(currentReview.artifact) == currentReview) {
            "The exact displayed plugin review is no longer pending"
        }
        if (selection != null) {
            // Running the script and reaching its declared origins come with the installation.
            require(selection.runtimePermissions.containsAll(
                currentReview.requestedRuntimePermissions intersect SandboxTierCeiling.ORDINARY_RUNTIME_PERMISSIONS,
            )) { "Ordinary runtime permissions cannot be declined" }
        }
        admission.approve(stored.artifactIdentity(), permissions, selection)
        state.locallyRevokedPluginIds.remove(pluginId)
        state.locallyRevokedArtifacts.remove(stored.artifactIdentity())
        try {
            state.lifecycleMutex.withLock { runtimeLoader.loadStored(stored) }
        } catch (inert: PluginExecutionInertException) {
            if (inert.reason == InertReason.UNSIGNED) {
                // Nothing will ever vouch for an unsigned artifact: same as an unreviewed one.
                state.locallyRevokedArtifacts += stored.artifactIdentity()
                executionAdmission.quarantineUnavailableRuntime(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
                throw ExtensionArtifactNotReviewedException(pluginId)
            }
            // The approval stands; publisher trust or the block list holds the runtime back for now.
            throw inert
        } catch (error: ScriptRuntimeUnavailableException) {
            // Approval is still a valid user decision, but this host cannot safely execute
            // the generic in-process engine. Keep the verified artifact installed and
            // explicitly inert rather than surfacing a misleading install failure or
            // leaving a durable grant that could resurrect on restart.
            Log.warn("Plugin", error) {
                "Script runtime unavailable; quarantining the approved plugin package"
            }
            state.locallyRevokedArtifacts += stored.artifactIdentity()
            executionAdmission.quarantineUnavailableRuntime(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
            throw ExtensionArtifactNotReviewedException(pluginId)
        }
    }

    /**
     * The publisher D4 compares. A developer-mode package (one `~dev` key, unsigned) is rebuilt all
     * the time; under the same rules it keeps its grant across rebuilds that ask for nothing new.
     */
    private fun StoredPlugin.continuationPublisher(): String? = metadata.publisherId
        ?: DEVELOPER_CONTINUATION_PUBLISHER.takeIf { PluginInstallKeys.isDeveloperKey(manifest.id) }

    /**
     * ADR-0002 S8 transition: a package installed from the original community repository while it
     * was unsigned has no publisher. Its first signed update, from that same URL under the same bare
     * key, counts as the publisher the user has since trusted for the URL (one key only can ever be
     * pinned there), so D4 can keep the user's grant when nothing new is asked.
     */
    private suspend fun StoredPlugin.legacyCommunityPublisher(next: StoredPlugin): String? {
        if (metadata.publisherId != null || PluginInstallKeys.isPublisherScoped(manifest.id)) return null
        val nextPublisher = next.metadata.publisherId ?: return null
        val official = repositoryClient.canonicalBaseUrl(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
        fun canonical(url: String?) = url?.let { runCatching { repositoryClient.canonicalBaseUrl(it) }.getOrNull() }
        if (canonical(metadata.repositoryBaseUrl) != official || canonical(next.metadata.repositoryBaseUrl) != official) return null
        return nextPublisher.takeIf { executionTierGate?.publisherForRepository(official) == it }
    }

    internal suspend fun continuedGrantFor(
        previousStored: StoredPlugin?,
        previousReview: PluginEventGrantReview?,
        stored: StoredPlugin,
        review: PluginEventGrantReview,
    ): PluginGrantSelection? {
        val admission = eventGrantAdmission ?: return null
        if (previousStored == null || previousReview == null) return null
        if (state.isLocallyRevoked(previousReview.artifact)) return null
        val previousGrant = admission.grantedSelection(previousReview.artifact) ?: return null
        return UpdateGrantContinuation.continuedSelection(
            previousPublisherId = previousStored.continuationPublisher() ?: previousStored.legacyCommunityPublisher(stored),
            nextPublisherId = stored.continuationPublisher(),
            previousReview = previousReview,
            previousGrant = previousGrant,
            nextReview = review,
            previousSources = previousStored.manifest.sources.orEmpty(),
            nextSources = stored.manifest.sources.orEmpty(),
        )
    }

    /** The verified review of the installed artifact of [pluginId] (granted or not). */
    suspend fun currentEventGrantReview(pluginId: String): PluginEventGrantReview? = state.packageMutationMutex.withLock {
        packageStore.get(pluginId)?.eventGrantReview(includeEmpty = true)
    }

    /** What the user granted the installed artifact of [pluginId], or null when it is not granted. */
    suspend fun grantedSelection(pluginId: String): PluginGrantSelection? = state.packageMutationMutex.withLock {
        val stored = packageStore.get(pluginId) ?: return@withLock null
        eventGrantAdmission?.grantedSelection(stored.artifactIdentity())
    }

    /**
     * Applies source settings → permissions (ADR-0002 §6.5) to an installed plugin: sensitive items
     * can be withdrawn, and declared content host families switched on or off. The plugin reloads
     * under a new generation, so the old runtime's authority ends now.
     */
    @Suppress("SuspendFunSwallowedCancellation") // Runs under NonCancellable and rethrows after restoring the previous runtime.
    suspend fun narrowGrant(pluginId: String, selection: PluginGrantSelection): Unit = state.packageMutationMutex.withLock {
        state.invalidateWebChallengeGrants()
        val stored = requireNotNull(packageStore.get(pluginId)) { "Extension '$pluginId' is not installed" }
        val admission = requireNotNull(eventGrantAdmission) { "Plugin event grant admission is unavailable" }
        val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        require(selection.runtimePermissions.containsAll(
            review.requestedRuntimePermissions intersect SandboxTierCeiling.ORDINARY_RUNTIME_PERMISSIONS,
        )) { "Ordinary runtime permissions cannot be withdrawn; uninstall the plugin instead" }
        withContext(NonCancellable) {
            // The old runtime goes first so nothing keeps the wider authority; if the narrowed grant
            // cannot be stored, the previous grant still stands and its runtime comes back.
            state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(pluginId) }
            try {
                admission.narrow(stored.artifactIdentity(), selection)
            } catch (error: Throwable) {
                runCatchingCancellable { state.lifecycleMutex.withLock { runtimeLoader.loadStored(stored) } }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            state.lifecycleMutex.withLock { runtimeLoader.loadStored(stored) }
        }
    }

    suspend fun revokeEventGrants(pluginId: String): Unit = state.packageMutationMutex.withLock {
        state.invalidateWebChallengeGrants()
        val stored = packageStore.get(pluginId) ?: return@withLock
        val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        withContext(NonCancellable) {
            state.locallyRevokedArtifacts += review.artifact
            var failure: Throwable? = null
            runCatchingCancellable { eventGrantAdmission?.revoke(stored.artifactIdentity()) }
                .exceptionOrNull()?.let { failure = it }
            // Always tear down the runtime even when grant persistence fails. In particular, do
            // not call loadStored here: a partially removed durable grant could recreate the same
            // sensitive runtime during the failure path.
            runCatchingCancellable { state.lifecycleMutex.withLock { runtimeLoader.unloadPlugin(pluginId) } }
                .exceptionOrNull()?.let { unloadFailure ->
                    if (failure == null) failure = unloadFailure else failure?.addSuppressed(unloadFailure)
                }
            failure?.let { throw it }

            // Successful revocation stages the exact artifact for a fresh review while leaving
            // it installed and inert. If staging itself fails, the runtime remains unloaded.
            eventGrantAdmission?.hydrate(review)
        }
    }
}
