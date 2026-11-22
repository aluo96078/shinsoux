@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.manager

import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.model.SourceIndexEntry
import dev.shinsou.kmp.plugin.model.StoredPlugin
import dev.shinsou.kmp.plugin.model.artifactIdentity
import dev.shinsou.kmp.plugin.network.PluginNetworkPolicy

/** The exact-artifact execution gate PluginManager and its collaborators consult before building a runtime. */
internal class PluginExecutionAdmission(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
) {
    private val eventGrantAdmission = deps.eventGrantAdmission
    private val executionAdmissionMode = deps.executionAdmissionMode

    /**
     * [source]'s declared policy with only the content CDN families the user granted (ADR-0002
     * §6.2): one rule for the runtime and for the reader and download content clients. Without an
     * admission store (developer compatibility) the declaration stands; with one, no grant means no
     * dynamic families at all.
     */
    internal fun grantedNetworkPolicy(source: SourceIndexEntry, grant: PluginGrantSelection?): PluginNetworkPolicy {
        val policy = source.networkPolicy()
        if (eventGrantAdmission == null) return policy
        val granted = grant?.contentHostSuffixes.orEmpty()
        return policy.copy(
            contentHostSuffixes = policy.contentHostSuffixes intersect granted,
            ungrantedContentHostSuffixes = policy.contentHostSuffixes - granted,
        )
    }

    internal suspend fun grantFor(stored: StoredPlugin): PluginGrantSelection? =
        eventGrantAdmission?.grantedSelection(stored.artifactIdentity())

    /** Hydrates a pending review or restores a matching exact grant; missing admission denies. */
    internal suspend fun prepareExecutionAdmission(review: PluginEventGrantReview): Boolean {
        // Do not hydrate an in-memory authorizer from a durable grant which this process has
        // already tombstoned after a failed revocation. There is no runtime to authorize, and
        // retaining that grant in memory only widens the consequences of a teardown defect.
        if (state.isLocallyRevoked(review.artifact)) return false
        val admission = eventGrantAdmission
        if (admission != null) {
            admission.hydrate(review)
            return executionAdmissionGranted(review)
        }
        return executionAdmissionGranted(review)
    }

    internal suspend fun executionAdmissionGranted(review: PluginEventGrantReview): Boolean {
        if (state.isLocallyRevoked(review.artifact)) return false
        return eventGrantAdmission?.isGranted(review)
            ?: (executionAdmissionMode == PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY)
    }

    /**
     * Converts an engine-isolation failure into a durable pending/quarantine state.
     *
     * A user approval is an admission decision for one exact artifact, not an override of the
     * host's inability to isolate an in-process JavaScript engine.  If runtime construction fails
     * for that reason, revoke the exact grant first and then stage the same review as pending so
     * the verified bytes remain available for inspection/uninstall without becoming executable on
     * the next restart.
     */
    internal suspend fun quarantineUnavailableRuntime(review: PluginEventGrantReview) {
        state.locallyRevokedArtifacts += review.artifact
        state.runtimeUnavailableArtifacts += review.artifact
        eventGrantAdmission?.revoke(review.artifact)
        eventGrantAdmission?.hydrate(review)
    }
}
