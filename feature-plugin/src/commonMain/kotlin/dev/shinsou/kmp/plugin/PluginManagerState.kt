@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.v2.ExtensionRuntimeRegistryV2
import dev.shinsou.kmp.plugin.v2.SourceLifecycleControlledExtensionPackageRuntimeV2
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex

/** What [PluginManager] was constructed with, handed unchanged to its collaborators. */
internal data class PluginManagerDependencies(
    val repositoryClient: ExtensionRepositoryClient,
    val packageStore: PluginPackageStore,
    val verifier: PluginVerifier,
    val runtimeFactory: ScriptPluginRuntimeFactory,
    val environment: ScriptPluginEnvironment,
    val eventGrantAdmission: dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission?,
    val executionAdmissionMode: PluginExecutionAdmissionMode,
    val reviewedImageTransport: PluginHttpTransport?,
    val executionTierGate: PluginExecutionTierGate?,
    val developerRepository: (String) -> Boolean,
    val defaultDispatcher: CoroutineDispatcher,
)

/**
 * The mutable state of one [PluginManager], which creates and solely owns it; its collaborators
 * receive this holder instead of keeping copies.
 *
 * Lock order: [packageMutationMutex] before [lifecycleMutex]. [PluginManager]'s own interaction
 * lock is taken only while neither of these is held.
 */
internal class PluginManagerState {
    /** Serializes package mutations without blocking readers of the live source/runtime maps. */
    val packageMutationMutex = Mutex()
    val lifecycleMutex = Mutex()
    val runtimes = mutableMapOf<String, List<ScriptPluginRuntime>>()
    val sources = mutableMapOf<Long, CatalogueSource>()
    val sourceOwners = mutableMapOf<Long, String>()
    /** Inert source reservations; populated before any executable factory is invoked. */
    val sourceReservations = mutableMapOf<Long, String>()
    val extensionRegistryV2 = ExtensionRuntimeRegistryV2()
    val nativeArtifactIdentities = mutableMapOf<String, PluginArtifactIdentity>()
    val nativeLifecycleRuntimes = mutableMapOf<String, SourceLifecycleControlledExtensionPackageRuntimeV2>()
    val disabledNativeSources = mutableSetOf<SourceKey>()
    val eventScopes = mutableMapOf<ScriptPluginRuntime, BoundPluginScope>()
    /** Process-local fail-closed tombstones when durable revocation cannot be confirmed. */
    val locallyRevokedPluginIds = mutableSetOf<String>()
    val locallyRevokedArtifacts = mutableSetOf<PluginArtifactIdentity>()
    /**
     * Exact artifacts this process already failed to execute after review. Their pending review is
     * hidden: approving again would only quarantine the same bytes and re-open the prompt forever.
     */
    val runtimeUnavailableArtifacts = mutableSetOf<PluginArtifactIdentity>()
    var nativeRuntimeGeneration: Long = 0
    /** Object-identity keyed, process-local and deliberately non-serializable browser grants. */
    val webChallengeGrants = mutableListOf<WebChallengeGrant>()

    val continuationNotices =
        MutableSharedFlow<PluginGrantContinuationNotice>(extraBufferCapacity = 16)

    @Volatile var repositoryPackages: Map<String, List<ExtensionDescriptor>> = emptyMap()

    /** Every package/native runtime lifecycle boundary advances the process-local grant epoch. */
    fun invalidateWebChallengeGrants() {
        nativeRuntimeGeneration += 1
        webChallengeGrants.clear()
    }

    fun isLocallyRevoked(artifact: PluginArtifactIdentity): Boolean =
        artifact.packageId in locallyRevokedPluginIds || artifact in locallyRevokedArtifacts
}
