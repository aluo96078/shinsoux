package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.SourceLoginRequest
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

    public fun dismiss(sourceId: Long) {
        mutableLoginRequests.update { requests -> requests.filterNot { it.sourceId == sourceId } }
    }
}
