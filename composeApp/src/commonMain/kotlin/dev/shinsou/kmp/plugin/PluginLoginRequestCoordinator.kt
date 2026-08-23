package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.SourceLoginRequest
import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe queue shared by plugin runtime workers and the common UI.
 *
 * Repeated requests from a source keep their original FIFO position. This prevents parallel
 * catalogue/search calls from stacking identical dialogs while preserving requests from other
 * sources.
 */
public class PluginLoginRequestCoordinator : PluginLoginRequester {
    private val mutableLoginRequests = MutableStateFlow<List<SourceLoginRequest>>(emptyList())

    public val loginRequests: StateFlow<List<SourceLoginRequest>> = mutableLoginRequests.asStateFlow()

    override fun request(sourceId: Long, sourceName: String, reason: String?): Boolean = try {
        val request = SourceLoginRequest(
            sourceId = sourceId,
            sourceName = sourceName,
            reason = reason?.trim()?.takeIf(String::isNotEmpty),
        )
        mutableLoginRequests.update { requests ->
            if (requests.any { it.sourceId == sourceId }) requests else requests + request
        }
        true
    } catch (_: Throwable) {
        false
    }

    public fun requestEvent(
        eventId: String,
        target: ExactPluginSourceTarget,
        sourceId: Long,
        sourceName: String,
        reason: String?,
    ): Boolean = try {
        val request = SourceLoginRequest(
            eventId = eventId,
            sourceId = sourceId,
            sourceName = sourceName,
            reason = reason?.trim()?.takeIf(String::isNotEmpty),
            exactTarget = target,
        )
        mutableLoginRequests.update { requests ->
            if (requests.any { it.eventId == eventId || (it.eventId != null && it.exactTarget == target) }) {
                requests
            } else {
                requests + request
            }
        }
        true
    } catch (_: Throwable) {
        false
    }

    public fun dismiss(sourceId: Long) {
        mutableLoginRequests.update { requests -> requests.filterNot { it.sourceId == sourceId } }
    }

    public fun dismissEvent(eventId: String) {
        mutableLoginRequests.update { requests -> requests.filterNot { it.eventId == eventId } }
    }

    public fun clearTarget(target: ExactPluginSourceTarget) {
        mutableLoginRequests.update { it.filterNot { request -> request.exactTarget == target } }
    }

    public fun clearArtifact(identity: PluginArtifactIdentity) {
        mutableLoginRequests.update { it.filterNot { request -> request.exactTarget?.artifactIdentity == identity } }
    }

    public fun hasTarget(target: ExactPluginSourceTarget): Boolean =
        loginRequests.value.any { it.exactTarget == target }

    public fun event(eventId: String): SourceLoginRequest? =
        loginRequests.value.singleOrNull { it.eventId == eventId }
}
