package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Narrow exact-source host ports. Implementations cannot widen a plugin request to a repository. */
public data class ExactPluginSourceTarget(
    val artifactIdentity: PluginArtifactIdentity,
    val sourceKey: SourceKey,
)

public fun interface PluginLoginIntentPort {
    public suspend fun request(target: ExactPluginSourceTarget, eventId: String, payload: LoginRequestV1): PluginEventOutcome
}

public fun interface PluginSourceRefreshPort {
    public suspend fun refreshExactSource(
        target: ExactPluginSourceTarget,
        contextRef: String?,
        payload: SourceRefreshRequestV1,
    ): PluginEventOutcome
}

public fun interface PluginLogoutPort {
    public suspend fun logoutExactSource(
        target: ExactPluginSourceTarget,
        eventId: String,
        payload: LogoutRequestV1,
    ): PluginEventOutcome
}

public fun interface PluginDiagnosticPort {
    public suspend fun report(
        target: ExactPluginSourceTarget,
        eventId: String,
        occurrenceCount: Int,
        payload: DiagnosticMessageV1,
    ): PluginEventOutcome
}

public data class PluginSystemEventHostPorts(
    val login: PluginLoginIntentPort,
    val refresh: PluginSourceRefreshPort,
    val logout: PluginLogoutPort,
    val diagnostic: PluginDiagnosticPort,
)

/** Host-owned exact-source invalidation generations; consumers key reloads by full SourceKey. */
public class ExactSourceRefreshInvalidations {
    private val mutableGenerations = MutableStateFlow<Map<SourceKey, Long>>(emptyMap())
    private val targets = mutableMapOf<SourceKey, ExactPluginSourceTarget>()
    private val lock = SynchronousLock()
    public val generations: StateFlow<Map<SourceKey, Long>> = mutableGenerations.asStateFlow()

    public fun invalidate(target: ExactPluginSourceTarget): Long {
        var next = 0L
        lock.withLock {
            mutableGenerations.update { current ->
                val previous = current[target.sourceKey].takeIf { targets[target.sourceKey] == target } ?: 0L
                next = previous + 1L
                targets[target.sourceKey] = target
                current + (target.sourceKey to next)
            }
        }
        return next
    }

    public fun clear(target: ExactPluginSourceTarget) {
        lock.withLock {
            if (targets[target.sourceKey] != target) return@withLock
            targets.remove(target.sourceKey)
            mutableGenerations.update { it - target.sourceKey }
        }
    }
}

public data class SafePluginDiagnosticRecord(
    val artifactIdentity: PluginArtifactIdentity,
    val sourceKey: SourceKey,
    val eventId: String,
    val code: String,
    val operation: String?,
    val severity: PluginDiagnosticSeverity,
    val fallbackMessage: String,
    val occurrenceCount: Int,
    val recordedAtMillis: Long,
)

/** Runtime-bounded, non-persistent diagnostic log. Raw exceptions and secrets have no field here. */
public class BoundedPluginDiagnosticLog(
    private val capacity: Int = 64,
    private val ttlMillis: Long = 60_000,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : PluginEventObserver {
    private val lock = SynchronousLock()
    private val records = ArrayDeque<SafePluginDiagnosticRecord>()

    init { require(capacity > 0) }

    public fun report(
        target: ExactPluginSourceTarget,
        eventId: String,
        payload: DiagnosticMessageV1,
        occurrenceCount: Int,
    ): PluginEventOutcome = lock.withLock {
        pruneLocked()
        records.removeAll { it.eventId == eventId && it.sourceKey == target.sourceKey }
        records.addLast(SafePluginDiagnosticRecord(
            target.artifactIdentity, target.sourceKey, eventId, payload.code, payload.operation,
            payload.severity, payload.fallbackMessage, occurrenceCount, nowMillis(),
        ))
        while (records.size > capacity) records.removeFirst()
        PluginEventOutcome.Succeeded
    }

    override fun onCompleted(report: PluginEventExecutionReport) {
        val sourceKey = report.sourceKey ?: return
        lock.withLock {
            pruneLocked()
            val index = records.indexOfFirst { it.eventId == report.eventId && it.sourceKey == sourceKey }
            if (index >= 0) records[index] = records[index].copy(occurrenceCount = report.occurrenceCount)
        }
    }

    override fun onRuntimeClosed(scope: BoundPluginScope): Unit = lock.withLock {
        records.removeAll { it.artifactIdentity == scope.artifactIdentity && it.sourceKey == scope.sourceKey }
        Unit
    }

    override fun onArtifactInvalidated(identity: PluginArtifactIdentity): Unit = lock.withLock {
        records.removeAll { it.artifactIdentity == identity }
        Unit
    }

    public fun snapshot(): List<SafePluginDiagnosticRecord> = lock.withLock { pruneLocked(); records.toList() }
    public fun clear(): Unit = lock.withLock { records.clear() }

    private fun pruneLocked() {
        val cutoff = nowMillis() - ttlMillis
        records.removeAll { it.recordedAtMillis < cutoff }
    }
}

/** Registers the four system-v1 messages in host code; plugins cannot extend this registry. */
public fun PluginSystemEventHandlerRegistry.registerV1HostHandlers(
    ports: PluginSystemEventHostPorts,
    json: Json = Json { ignoreUnknownKeys = false; explicitNulls = true },
) {
    register(TypedPluginSystemEventHandler(
        name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
        kind = PluginSystemEventKind.COMMAND,
        payloadVersion = 1,
        lane = PluginSystemEventLane.MODAL,
        requiredPermission = PluginHostPermission.REQUEST_LOGIN_UI,
        requiredSourceCapability = "LOGIN",
        decode = { json.decodeFromJsonElement<LoginRequestV1>(it) },
        execute = { context, payload -> ports.login.request(context.target(), context.eventId, payload) },
    ))
    register(TypedPluginSystemEventHandler(
        name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
        kind = PluginSystemEventKind.COMMAND,
        payloadVersion = 1,
        lane = PluginSystemEventLane.REFRESH,
        requiredPermission = PluginHostPermission.REQUEST_SOURCE_REFRESH,
        decode = { json.decodeFromJsonElement<SourceRefreshRequestV1>(it) },
        execute = { context, payload ->
            ports.refresh.refreshExactSource(context.target(), context.contextRef, payload)
        },
    ))
    register(TypedPluginSystemEventHandler(
        name = PluginSystemEventNames.AUTH_LOGOUT_REQUEST,
        kind = PluginSystemEventKind.COMMAND,
        payloadVersion = 1,
        lane = PluginSystemEventLane.MODAL,
        requiredPermission = PluginHostPermission.REQUEST_LOGOUT,
        requiredSourceCapability = "LOGIN",
        decode = { json.decodeFromJsonElement<LogoutRequestV1>(it) },
        execute = { context, payload ->
            ports.logout.logoutExactSource(context.target(), context.eventId, payload)
        },
    ))
    register(TypedPluginSystemEventHandler(
        name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
        kind = PluginSystemEventKind.EVENT,
        payloadVersion = 1,
        lane = PluginSystemEventLane.TRANSIENT,
        requiredPermission = PluginHostPermission.REPORT_DIAGNOSTIC,
        decode = { json.decodeFromJsonElement<DiagnosticMessageV1>(it) },
        execute = { context, payload ->
            ports.diagnostic.report(context.target(), context.eventId, context.occurrenceCount, payload)
        },
    ))
}

private fun AuthorizedPluginEventContext.target(): ExactPluginSourceTarget = ExactPluginSourceTarget(
    artifactIdentity = scope.artifactIdentity,
    sourceKey = scope.sourceKey,
)
