package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection

/**
 * ADR-0002 §6.3 (D4): when an update asks for nothing new, the user's decision on the previous
 * version carries over to it, so publishers can ship fixes without every user approving again.
 *
 * It carries over only when all of these hold; otherwise the update waits for a fresh review:
 * - both versions come from the same publisher key (a package without a publisher never continues);
 * - every requested host permission, runtime permission and content CDN suffix of the update was
 *   also requested by the previous version;
 * - every source of the update existed before, and none of its request, credential, content or
 *   browser-session origins is new (compared exactly).
 *
 * The carried-over grant is the previous grant narrowed to what the update requests: an item the
 * user declined stays declined, and nothing is added.
 */
internal object UpdateGrantContinuation {
    fun continuedSelection(
        previousPublisherId: String?,
        nextPublisherId: String?,
        previousReview: PluginEventGrantReview,
        previousGrant: PluginGrantSelection,
        nextReview: PluginEventGrantReview,
        previousSources: List<SourceIndexEntry>,
        nextSources: List<SourceIndexEntry>,
    ): PluginGrantSelection? {
        if (previousPublisherId == null || previousPublisherId != nextPublisherId) return null
        if (previousReview.artifact == nextReview.artifact) return null
        if (!previousReview.requestedPermissions.containsAll(nextReview.requestedPermissions)) return null
        if (!previousReview.requestedRuntimePermissions.containsAll(nextReview.requestedRuntimePermissions)) return null
        if (!previousReview.requestedContentHostSuffixes.containsAll(nextReview.requestedContentHostSuffixes)) return null
        if (!previousReview.sourceKeys.toSet().containsAll(nextReview.sourceKeys)) return null
        val previousById = previousSources.associateBy { it.canonicalSourceId ?: it.id.toString() }
        val originsUnchanged = nextSources.all { next ->
            val previous = previousById[next.canonicalSourceId ?: next.id.toString()] ?: return@all false
            previous.declaredRequestOrigins().containsAll(next.declaredRequestOrigins()) &&
                previous.networkPolicy().credentialOrigins.containsAll(next.networkPolicy().credentialOrigins) &&
                previous.declaredContentOrigins().containsAll(next.declaredContentOrigins()) &&
                previous.networkPolicy().browserSessionOrigins.containsAll(next.networkPolicy().browserSessionOrigins)
        }
        if (!originsUnchanged) return null
        return PluginGrantSelection(
            hostPermissions = previousGrant.hostPermissions intersect nextReview.requestedPermissions,
            runtimePermissions = previousGrant.runtimePermissions intersect nextReview.requestedRuntimePermissions,
            contentHostSuffixes = previousGrant.contentHostSuffixes intersect nextReview.requestedContentHostSuffixes,
        )
    }
}

/** An update that kept the user's previous grant (ADR-0002 §6.3: the user is always told). */
public data class PluginGrantContinuationNotice(
    val packageId: String,
    val packageName: String,
    val fromVersion: String,
    val toVersion: String,
)
