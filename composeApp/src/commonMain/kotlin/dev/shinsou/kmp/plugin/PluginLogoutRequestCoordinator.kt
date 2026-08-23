package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class PluginLogoutConfirmation(
    val eventId: String,
    val target: ExactPluginSourceTarget,
    val sourceName: String,
    val message: String?,
)

internal object ExactPluginSessionOwnership {
    fun ownerKey(storageId: Long): String = "plugin.events.sessionOwner.$storageId"

    fun targetKey(target: ExactPluginSourceTarget): String {
        val artifact = target.artifactIdentity
        val source = target.sourceKey
        return listOf(
            artifact.packageId,
            artifact.version,
            artifact.versionCode.toString(),
            artifact.sha256,
            source.canonicalId,
        ).joinToString("|") { "${it.length}:$it" }
    }

    fun authorizesCleanup(storedOwner: String?, target: ExactPluginSourceTarget): Boolean =
        storedOwner != null && storedOwner == targetKey(target)
}

/** Bounded exact-event confirmation queue. Expired requests can never execute later. */
public class PluginLogoutRequestCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 30_000,
    private val capacity: Int = 4,
) {
    private val mutableRequests = MutableStateFlow<List<PluginLogoutConfirmation>>(emptyList())
    public val requests: StateFlow<List<PluginLogoutConfirmation>> = mutableRequests.asStateFlow()

    init {
        require(timeoutMillis > 0)
        require(capacity > 0)
    }

    public fun request(value: PluginLogoutConfirmation): Boolean {
        var accepted = false
        mutableRequests.update { current ->
            if (current.any { it.eventId == value.eventId || it.target == value.target } || current.size >= capacity) current
            else (current + value).also { accepted = true }
        }
        if (accepted) scope.launch {
            delay(timeoutMillis)
            dismiss(value.eventId)
        }
        return accepted
    }

    /** Atomically consumes one exact event, preventing confirm/dismiss races and replay. */
    public fun take(eventId: String): PluginLogoutConfirmation? {
        var selected: PluginLogoutConfirmation? = null
        mutableRequests.update { current ->
            selected = current.singleOrNull { it.eventId == eventId }
            if (selected == null) current else current.filterNot { it.eventId == eventId }
        }
        return selected
    }

    public fun dismiss(eventId: String) {
        mutableRequests.update { it.filterNot { request -> request.eventId == eventId } }
    }

    public fun clearSource(target: ExactPluginSourceTarget) {
        mutableRequests.update { requests -> requests.filterNot { it.target == target } }
    }

    public fun clearArtifact(identity: PluginArtifactIdentity) {
        mutableRequests.update { requests ->
            requests.filterNot { it.target.artifactIdentity == identity }
        }
    }

    public fun clear() {
        mutableRequests.value = emptyList()
    }

    public fun hasTarget(target: ExactPluginSourceTarget): Boolean =
        requests.value.any { it.target == target }
}
