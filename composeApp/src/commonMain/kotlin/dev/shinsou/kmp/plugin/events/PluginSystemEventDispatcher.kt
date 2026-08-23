package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.math.ceil
import kotlin.math.min

/** Stable status reported after the asynchronous handler has run. */
public enum class PluginEventExecutionStatus {
    SUCCEEDED,
    CANCELLED,
    FAILED,
    EXPIRED,
    SUPPRESSED,
}

public data class PluginEventOutcome(
    val status: PluginEventExecutionStatus,
    val errorCode: String? = null,
) {
    init {
        require(errorCode == null || isSafeAsciiIdentifier(errorCode, 64)) {
            "Invalid plugin event error code"
        }
        if (status == PluginEventExecutionStatus.FAILED) require(!errorCode.isNullOrBlank())
    }

    public companion object {
        public val Succeeded: PluginEventOutcome = PluginEventOutcome(PluginEventExecutionStatus.SUCCEEDED)
        public val Cancelled: PluginEventOutcome = PluginEventOutcome(PluginEventExecutionStatus.CANCELLED)
        public val Suppressed: PluginEventOutcome = PluginEventOutcome(PluginEventExecutionStatus.SUPPRESSED)

        public fun failed(code: String = "handler_failed"): PluginEventOutcome =
            PluginEventOutcome(PluginEventExecutionStatus.FAILED, code)

        public fun expired(code: String = "expired"): PluginEventOutcome =
            PluginEventOutcome(PluginEventExecutionStatus.EXPIRED, code)
    }
}

public data class PluginEventExecutionReport(
    val eventId: String,
    val eventName: String,
    val status: PluginEventExecutionStatus,
    val errorCode: String? = null,
    val occurrenceCount: Int = 1,
    val sourceKey: dev.shinsou.kmp.domain.model.SourceKey? = null,
)

public fun interface PluginEventObserver {
    public fun onCompleted(report: PluginEventExecutionReport)
    public fun onRuntimeClosed(scope: BoundPluginScope) {}
    public fun onArtifactInvalidated(identity: PluginArtifactIdentity) {}
}

public class PluginEventObserverGroup(
    private vararg val observers: PluginEventObserver,
) : PluginEventObserver {
    override fun onCompleted(report: PluginEventExecutionReport) = observers.forEach {
        runCatching { it.onCompleted(report) }
    }
    override fun onRuntimeClosed(scope: BoundPluginScope) = observers.forEach {
        runCatching { it.onRuntimeClosed(scope) }
    }
    override fun onArtifactInvalidated(identity: PluginArtifactIdentity) = observers.forEach {
        runCatching { it.onArtifactInvalidated(identity) }
    }
}

/** Host-owned issuer/validator for short-lived context handles. */
public fun interface PluginEventContextReferenceValidator {
    public fun accepts(scope: BoundPluginScope, contextRef: String): Boolean
}

/** Context visible to a typed host handler; no raw wire envelope or plugin-provided identity. */
public data class AuthorizedPluginEventContext(
    public val scope: BoundPluginScope,
    public val eventId: String,
    public val eventName: String,
    public val kind: PluginSystemEventKind,
    public val contextRef: String? = null,
    public val occurrenceCount: Int = 1,
)

/** Host-registered, typed event handler. Plugins cannot add registry entries. */
public interface PluginSystemEventHandler<P : Any> {
    public val name: String
    public val kind: PluginSystemEventKind
    public val payloadVersion: Int
    public val lane: PluginSystemEventLane
    public val requiredPermission: PluginHostPermission
    public val requiredSourceCapability: String?

    public fun decodeAndValidate(payload: JsonElement): P

    public suspend fun handle(
        context: AuthorizedPluginEventContext,
        payload: P,
    ): PluginEventOutcome
}

/** Convenient typed adapter for host code and tests. */
public class TypedPluginSystemEventHandler<P : Any>(
    override val name: String,
    override val kind: PluginSystemEventKind,
    override val payloadVersion: Int,
    override val lane: PluginSystemEventLane,
    override val requiredPermission: PluginHostPermission,
    override val requiredSourceCapability: String? = null,
    private val decode: (JsonElement) -> P,
    private val execute: suspend (AuthorizedPluginEventContext, P) -> PluginEventOutcome,
) : PluginSystemEventHandler<P> {
    init {
        require(isSafeAsciiIdentifier(name, 96)) { "Invalid event handler name" }
        require(payloadVersion > 0) { "Invalid event handler payload version" }
        requiredSourceCapability?.let { requireSafeCapabilityId(it) }
    }

    override fun decodeAndValidate(payload: JsonElement): P = decode(payload)

    override suspend fun handle(
        context: AuthorizedPluginEventContext,
        payload: P,
    ): PluginEventOutcome = execute(context, payload)
}

private data class HandlerKey(
    val protocolVersion: Int,
    val kind: PluginSystemEventKind,
    val name: String,
    val payloadVersion: Int,
)

/** Registry key is protocol + kind + name + payload version; action fallback is impossible. */
public class PluginSystemEventHandlerRegistry {
    private val handlers = linkedMapOf<HandlerKey, PluginSystemEventHandler<*>>()

    public fun register(handler: PluginSystemEventHandler<*>): Unit {
        val key = HandlerKey(
            protocolVersion = PluginSystemEventProtocol.VERSION,
            kind = handler.kind,
            name = handler.name,
            payloadVersion = handler.payloadVersion,
        )
        require(handlers.put(key, handler) == null) {
            "Duplicate system event handler ${handler.kind}:${handler.name}:${handler.payloadVersion}"
        }
    }

    public fun unregister(
        kind: PluginSystemEventKind,
        name: String,
        payloadVersion: Int,
    ): Boolean = handlers.remove(HandlerKey(PluginSystemEventProtocol.VERSION, kind, name, payloadVersion)) != null

    public fun find(
        protocolVersion: Int,
        kind: PluginSystemEventKind,
        name: String,
        payloadVersion: Int,
    ): PluginSystemEventHandler<*>? = handlers[HandlerKey(protocolVersion, kind, name, payloadVersion)]

    public fun supports(kind: PluginSystemEventKind, name: String, payloadVersion: Int = 1): Boolean =
        find(PluginSystemEventProtocol.VERSION, kind, name, payloadVersion) != null

    public fun capabilityIds(): Set<String> = handlers.values.mapTo(linkedSetOf()) { handler ->
        val prefix = if (handler.kind == PluginSystemEventKind.COMMAND) "command" else "event"
        "$prefix.${handler.name}"
    }

    internal fun requiredPermission(capabilityId: String): PluginHostPermission? = handlers.values
        .firstOrNull { handler ->
            val prefix = if (handler.kind == PluginSystemEventKind.COMMAND) "command" else "event"
            "$prefix.${handler.name}" == capabilityId
        }?.requiredPermission
}

private data class EventAdmissionKey(
    val runtimeKey: String,
    val sourceKey: String,
    val name: String,
    val dedupeKey: String,
)

private data class QueuedPluginEvent(
    val scope: BoundPluginScope,
    val envelope: PluginSystemEventEnvelope,
    val handler: PluginSystemEventHandler<Any>,
    val payload: Any,
    val admissionKey: EventAdmissionKey,
    val sourcePendingKey: String,
    val receivedAtMillis: Long,
    /** Exact validated event identity delivered to host ports and completion observers. */
    val hostEventId: String,
)

private data class TokenBucket(
    var tokens: Double,
    var lastMillis: Long,
)

/**
 * Bounded, synchronous ingress and asynchronous three-lane dispatcher. The public [submit]
 * method performs decoding, authorization, dedupe, throttling and a non-blocking channel send;
 * handlers always run after the native/plugin invocation has returned.
 */
public class PluginSystemEventGateway(
    public val registry: PluginSystemEventHandlerRegistry,
    private val authorizer: PluginSystemEventAuthorizer = DenyAllPluginSystemEventAuthorizer,
    public val codec: PluginSystemEventCodec = PluginSystemEventCodec(),
    private val clock: PluginEventClock = SystemPluginEventClock,
    private val observer: PluginEventObserver = PluginEventObserver { },
    private val contextReferenceValidator: PluginEventContextReferenceValidator =
        PluginEventContextReferenceValidator { scope, contextRef -> contextRef == scope.invocationContext },
    dispatcherScope: CoroutineScope? = null,
    public val contextRegistry: PluginEventContextRegistry? = null,
) : ScopedPluginSystemEventSink, AutoCloseable {
    private val limits: PluginSystemEventLimits = codec.limits
    private val ownScope: CoroutineScope = dispatcherScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modal = Channel<QueuedPluginEvent>(limits.maxGlobalPending)
    private val refresh = Channel<QueuedPluginEvent>(limits.maxGlobalPending)
    private val transient = Channel<QueuedPluginEvent>(limits.maxGlobalPending)
    private val stateLock: SynchronousLock = SynchronousLock()
    private val sourceMutexes = linkedMapOf<String, Mutex>()
    private val activeAdmissions = linkedSetOf<EventAdmissionKey>()
    private val refreshDirty = linkedSetOf<EventAdmissionKey>()
    private val diagnosticOccurrences = linkedMapOf<EventAdmissionKey, Int>()
    private val diagnosticLastMillis = linkedMapOf<EventAdmissionKey, Long>()
    private val pendingBySource = linkedMapOf<String, Int>()
    private val runningJobs = linkedMapOf<EventAdmissionKey, Job>()
    private val buckets = linkedMapOf<String, TokenBucket>()
    private val loginCooldowns = linkedMapOf<String, Long>()
    private val invalidRuntimeKeys = linkedSetOf<String>()
    private val invalidArtifactKeys = linkedSetOf<PluginArtifactIdentity>()
    private val latestGenerationByRuntime = linkedMapOf<String, Long>()
    /** Runtime identity is retained only while its generation can still be replayed. */
    private val runtimeArtifacts = linkedMapOf<String, PluginArtifactIdentity>()
    private var pending: Int = 0
    private var operationCounter: Long = 0
    private var closed: Boolean = false
    private val workers: List<Job> = listOf(
        ownScope.launch { consume(modal, serial = true) },
        ownScope.launch { consume(refresh, serial = false) },
        ownScope.launch { consume(transient, serial = false) },
    )

    /** Number of accepted events that have not reached a terminal handler state. */
    public val pendingCount: Int
        get() = stateLock.withLock { pending }

    public val isClosed: Boolean
        get() = stateLock.withLock { closed }

    override fun submit(scope: BoundPluginScope, utf8Envelope: ByteArray): PluginEventReceipt {
        if (!scope.isHostBound()) return receipt("", PluginEventDisposition.INVALID)
        val envelope = try {
            codec.decode(utf8Envelope)
        } catch (_: Throwable) {
            return receipt("", PluginEventDisposition.INVALID)
        }
        val messageId = envelope.id
        if (envelope.protocol != PluginSystemEventProtocol.NAME || envelope.version != PluginSystemEventProtocol.VERSION) {
            return receipt(messageId, PluginEventDisposition.UNSUPPORTED)
        }
        val handler = registry.find(
            protocolVersion = envelope.version,
            kind = envelope.kind,
            name = envelope.name,
            payloadVersion = envelope.payloadVersion,
        ) ?: return receipt(messageId, PluginEventDisposition.UNSUPPORTED)

        @Suppress("UNCHECKED_CAST")
        val typedHandler = handler as PluginSystemEventHandler<Any>
        val payload = try {
            typedHandler.decodeAndValidate(envelope.payload)
        } catch (_: Throwable) {
            return receipt(messageId, PluginEventDisposition.INVALID)
        }

        if (isRuntimeInvalid(scope)) return receipt(messageId, PluginEventDisposition.RUNTIME_CLOSED)
        val authorization = authorizer.authorize(
            scope = scope,
            requiredPermission = typedHandler.requiredPermission,
            requiredSourceCapability = typedHandler.requiredSourceCapability,
        )
        if (!authorization.allowed) return receipt(messageId, PluginEventDisposition.DENIED)
        if (envelope.contextRef != null && !isContextReferenceAccepted(scope, envelope.contextRef)) {
            return receipt(messageId, PluginEventDisposition.DENIED)
        }
        if (typedHandler.requiredPermission == PluginHostPermission.REQUEST_LOGIN_UI &&
            envelope.contextRef != null
        ) {
            return receipt(messageId, PluginEventDisposition.INVALID)
        }
        if (typedHandler.name == PluginSystemEventNames.SOURCE_REFRESH_REQUEST) {
            val refreshPayload = payload as? SourceRefreshRequestV1
            if (refreshPayload?.scope == SourceRefreshScope.ACTIVE_CONTEXT && envelope.contextRef == null) {
                return receipt(messageId, PluginEventDisposition.DENIED)
            }
            if (refreshPayload?.scope == SourceRefreshScope.SELF && envelope.contextRef != null) {
                return receipt(messageId, PluginEventDisposition.INVALID)
            }
        }

        val now = clock.nowMillis()
        val sourcePendingKey = "${scope.runtimeKey}|${scope.sourceKey.canonicalId}"
        val admissionKey = EventAdmissionKey(
            runtimeKey = scope.runtimeKey,
            sourceKey = scope.sourceKey.canonicalId,
            name = typedHandler.name,
            dedupeKey = dedupeKey(envelope, payload),
        )
        return stateLock.withLock {
            pruneDiagnosticAggregationsLocked(now)
            val latestGeneration = latestGenerationByRuntime[scope.runtimeInstanceId]
            if (latestGeneration != null && scope.runtimeGeneration < latestGeneration) {
                return@withLock receipt(messageId, PluginEventDisposition.RUNTIME_CLOSED)
            }
            if (latestGeneration == null || scope.runtimeGeneration > latestGeneration) {
                latestGenerationByRuntime[scope.runtimeInstanceId] = scope.runtimeGeneration
                val staleRuntimePrefix = "${scope.runtimeInstanceId}#"
                val staleRuntimeKeys = runtimeArtifacts.keys.filter { key ->
                    key.startsWith(staleRuntimePrefix) && key != scope.runtimeKey
                }
                val staleJobs = runningJobs.filterKeys {
                    it.runtimeKey.startsWith(staleRuntimePrefix) && it.runtimeKey != scope.runtimeKey
                }.values.toList()
                invalidRuntimeKeys += activeAdmissions.map { it.runtimeKey }
                    .filter { it.startsWith(staleRuntimePrefix) && it != scope.runtimeKey }
                removeAdmissionsLocked {
                    it.runtimeKey.startsWith(staleRuntimePrefix) && it.runtimeKey != scope.runtimeKey
                }
                staleRuntimeKeys.forEach(::clearRuntimeStateLocked)
                staleJobs.forEach { it.cancel() }
            }
            runtimeArtifacts[scope.runtimeKey] = scope.artifactIdentity
            if (closed || scope.runtimeKey in invalidRuntimeKeys || scope.artifactIdentity in invalidArtifactKeys) {
                return@withLock receipt(messageId, PluginEventDisposition.RUNTIME_CLOSED)
            }
            if (activeAdmissions.any {
                    it.runtimeKey == admissionKey.runtimeKey &&
                        it.sourceKey == admissionKey.sourceKey &&
                        it.name == admissionKey.name &&
                        it.dedupeKey == admissionKey.dedupeKey
                }
            ) {
                if (typedHandler.name == PluginSystemEventNames.SOURCE_REFRESH_REQUEST) {
                    refreshDirty += admissionKey
                }
                if (typedHandler.name == PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT) {
                    diagnosticOccurrences[admissionKey] = (diagnosticOccurrences[admissionKey] ?: 1) + 1
                }
                return@withLock receipt(messageId, PluginEventDisposition.DEDUPLICATED)
            }
            val sourceCount = pendingBySource[sourcePendingKey] ?: 0
            if (pending >= limits.maxGlobalPending || sourceCount >= limits.maxPerSourcePending) {
                return@withLock receipt(messageId, PluginEventDisposition.BUSY)
            }
            // One runtime-wide bucket prevents rotating sources/types/message ids from bypassing
            // the host burst, while the second isolates a noisy exact source/event type.
            val tokenRetry = consumeTokenLocked("${scope.runtimeKey}|*", now)
                ?: consumeTokenLocked("$sourcePendingKey|${typedHandler.name}", now)
            if (tokenRetry != null) {
                return@withLock receipt(messageId, PluginEventDisposition.THROTTLED, retryAfterMillis = tokenRetry)
            }
            if (typedHandler.name == PluginSystemEventNames.AUTH_LOGIN_REQUEST) {
                val last = loginCooldowns[sourcePendingKey]
                if (last != null && now - last < limits.loginCooldownMillis) {
                    return@withLock receipt(
                        messageId,
                        PluginEventDisposition.THROTTLED,
                        retryAfterMillis = limits.loginCooldownMillis - (now - last),
                    )
                }
                loginCooldowns[sourcePendingKey] = now
            }
            val operationRef = nextOperationRefLocked()
            val event = QueuedPluginEvent(
                scope = scope,
                envelope = envelope,
                handler = typedHandler,
                payload = payload,
                admissionKey = admissionKey,
                sourcePendingKey = sourcePendingKey,
                receivedAtMillis = now,
                hostEventId = operationRef,
            )
            val sendResult = channelFor(typedHandler.lane).trySend(event)
            if (!sendResult.isSuccess) return@withLock receipt(messageId, PluginEventDisposition.BUSY)
            activeAdmissions += admissionKey
            activeScopeArtifactKeys[admissionKey] = scope.artifactIdentity
            if (typedHandler.name == PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT) {
                val previousAt = diagnosticLastMillis[admissionKey]
                diagnosticOccurrences[admissionKey] = if (
                    previousAt != null && now - previousAt <= limits.diagnosticAggregationMillis
                ) {
                    (diagnosticOccurrences[admissionKey] ?: 0) + 1
                } else {
                    1
                }
                diagnosticLastMillis[admissionKey] = now
            }
            pending++
            pendingBySource[sourcePendingKey] = sourceCount + 1
            return@withLock receipt(
                messageId,
                PluginEventDisposition.ACCEPTED,
                operationRef = operationRef,
            )
        }
    }

    /** Invalidates all queued/running work for one runtime generation. */
    public fun closeRuntime(scope: BoundPluginScope): Unit = stateLock.withLock {
        invalidRuntimeKeys += scope.runtimeKey
        contextRegistry?.clearRuntime(scope)
        removeAdmissionsLocked { it.runtimeKey == scope.runtimeKey }
        runningJobs.filterKeys { it.runtimeKey == scope.runtimeKey }.values.forEach { it.cancel() }
        clearRuntimeStateLocked(scope.runtimeKey)
        if (latestGenerationByRuntime[scope.runtimeInstanceId] == scope.runtimeGeneration) {
            latestGenerationByRuntime.remove(scope.runtimeInstanceId)
        }
        if (authorizer is MutablePluginSystemEventAuthorizer) authorizer.closeRuntime(scope)
        runCatching { observer.onRuntimeClosed(scope) }
    }

    /** Registers only live lifecycle/source facts. Exact-digest grants remain a separate host action. */
    public fun openRuntime(scope: BoundPluginScope, status: PluginEventRuntimeStatus): Unit {
        (authorizer as? MutablePluginSystemEventAuthorizer)?.setRuntimeStatus(scope, status)
    }

    /** Host UI sets this only for the dynamic extent of one foreground user invocation. */
    public fun setUserInteractionContext(scope: BoundPluginScope, active: Boolean): Unit {
        (authorizer as? MutablePluginSystemEventAuthorizer)?.setUserInteractionContext(scope, active)
    }

    public fun setRuntimeLifecycle(scope: BoundPluginScope, lifecycle: PluginRuntimeLifecycle): Unit {
        if (lifecycle == PluginRuntimeLifecycle.DISABLED || lifecycle == PluginRuntimeLifecycle.CLOSED) {
            contextRegistry?.clearRuntime(scope)
        }
        (authorizer as? MutablePluginSystemEventAuthorizer)?.setRuntimeLifecycle(scope, lifecycle)
    }

    /** Installs an exact artifact/source grant supplied by the host approval boundary. */
    public fun grantRuntimePermissions(
        scope: BoundPluginScope,
        permissions: Set<PluginHostPermission>,
    ): Unit {
        (authorizer as? MutablePluginSystemEventAuthorizer)?.grant(
            PluginEventGrantKey(scope.artifactIdentity, scope.sourceKey),
            permissions,
        )
    }

    /** Removes the exact grant before a reviewed runtime is unloaded or revoked. */
    public fun revokeRuntimePermissions(scope: BoundPluginScope): Unit {
        (authorizer as? MutablePluginSystemEventAuthorizer)?.revoke(
            PluginEventGrantKey(scope.artifactIdentity, scope.sourceKey),
        )
    }

    /** Capability negotiation intersects declaration, compiled handlers, and exact host grants. */
    public fun negotiate(
        scope: BoundPluginScope,
        declaration: PluginSystemEventDeclaration,
    ): PluginSystemEventNegotiation {
        val exactGrants = (authorizer as? MutablePluginSystemEventAuthorizer)
            ?.grantedPermissions(scope).orEmpty()
        val supported = registry.capabilityIds().filterTo(linkedSetOf()) { capability ->
            registry.requiredPermission(capability) in exactGrants
        }
        return PluginSystemCapabilityNegotiator(
            supportedCapabilities = supported,
            hardLimits = codec.limits,
        ).negotiate(declaration)
    }

    /** Invalidates stale work when an artifact is replaced or its digest grant is revoked. */
    public fun invalidateArtifact(identity: PluginArtifactIdentity): Unit = stateLock.withLock {
        invalidArtifactKeys += identity
        contextRegistry?.invalidateArtifact(identity)
        val jobs = runningJobs.filterKeys { key -> activeScopeArtifactKeys[key] == identity }.values.toList()
        removeAdmissionsLocked { key ->
            activeScopeArtifactKeys[key]?.let { it == identity } == true
        }
        runtimeArtifacts.filterValues { it == identity }.keys.toList().forEach(::clearRuntimeStateLocked)
        jobs.forEach { it.cancel() }
        runCatching { observer.onArtifactInvalidated(identity) }
    }

    /** Explicit alias used by admission/update code. */
    public fun revoke(scope: BoundPluginScope): Unit = closeRuntime(scope)

    /** Waits only for currently accepted work; this is a host/test utility, never bridge ingress. */
    public suspend fun awaitIdle(timeoutMillis: Long = 5_000): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (isActive && pendingCount != 0) delay(1)
            pendingCount == 0
        } ?: false

    override fun close() {
        stateLock.withLock {
            if (closed) return@withLock
            closed = true
            activeAdmissions.clear()
            activeScopeArtifactKeys.clear()
            refreshDirty.clear()
            diagnosticOccurrences.clear()
            diagnosticLastMillis.clear()
            pendingBySource.clear()
            pending = 0
            invalidRuntimeKeys.clear()
            invalidArtifactKeys.clear()
            latestGenerationByRuntime.clear()
            runtimeArtifacts.clear()
            buckets.clear()
            loginCooldowns.clear()
            sourceMutexes.clear()
            runningJobs.values.forEach { it.cancel() }
            runningJobs.clear()
        }
        contextRegistry?.close()
        modal.close()
        refresh.close()
        transient.close()
        ownScope.cancel()
    }

    private suspend fun consume(channel: Channel<QueuedPluginEvent>, serial: Boolean) {
        for (event in channel) {
            if (serial) process(event) else ownScope.launch { process(event) }
        }
    }

    private suspend fun process(event: QueuedPluginEvent) {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        stateLock.withLock {
            if (activeAdmissions.contains(event.admissionKey) && job != null) runningJobs[event.admissionKey] = job
        }
        var outcome: PluginEventOutcome? = null
        try {
            outcome = when {
                !isLive(event) -> PluginEventOutcome(PluginEventExecutionStatus.CANCELLED, "runtime_closed")
                clock.nowMillis() - event.receivedAtMillis > limits.ttlMillis -> PluginEventOutcome.expired()
                else -> executeSerial(event)
            }
        } catch (_: CancellationException) {
            outcome = PluginEventOutcome.Cancelled
        } catch (_: Throwable) {
            outcome = PluginEventOutcome.failed()
        } finally {
            finish(event, outcome ?: PluginEventOutcome.failed())
        }
    }

    private suspend fun executeSerial(event: QueuedPluginEvent): PluginEventOutcome {
        val mutex = stateLock.withLock {
            sourceMutexes.getOrPut(event.sourcePendingKey) { Mutex() }
        }
        return mutex.withLock {
            if (!isLive(event)) {
                PluginEventOutcome(PluginEventExecutionStatus.CANCELLED, "runtime_closed")
            } else {
                try {
                    val occurrenceCount = stateLock.withLock {
                        diagnosticOccurrences[event.admissionKey] ?: 1
                    }
                    event.handler.handle(
                        AuthorizedPluginEventContext(
                            scope = event.scope,
                            eventId = event.hostEventId,
                            eventName = event.envelope.name,
                            kind = event.envelope.kind,
                            contextRef = event.envelope.contextRef,
                            occurrenceCount = occurrenceCount,
                        ),
                        event.payload,
                    )
                } catch (_: CancellationException) {
                    PluginEventOutcome.Cancelled
                } catch (_: Throwable) {
                    PluginEventOutcome.failed()
                }
            }
        }
    }

    private fun finish(event: QueuedPluginEvent, outcome: PluginEventOutcome) {
        val occurrenceCount = stateLock.withLock {
            pruneDiagnosticAggregationsLocked(clock.nowMillis())
            var count = 1
            if (activeAdmissions.remove(event.admissionKey)) {
                activeScopeArtifactKeys.remove(event.admissionKey)
                pending = (pending - 1).coerceAtLeast(0)
                val sourceCount = (pendingBySource[event.sourcePendingKey] ?: 1) - 1
                if (sourceCount <= 0) pendingBySource.remove(event.sourcePendingKey)
                else pendingBySource[event.sourcePendingKey] = sourceCount
                if (event.envelope.name == PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT) {
                    count = diagnosticOccurrences[event.admissionKey] ?: 1
                }
            }
            runningJobs.remove(event.admissionKey)
            cleanupSourceMutexLocked(event.sourcePendingKey)
            if (event.envelope.name == PluginSystemEventNames.SOURCE_REFRESH_REQUEST &&
                refreshDirty.remove(event.admissionKey) &&
                !closed &&
                event.scope.runtimeKey !in invalidRuntimeKeys &&
                event.scope.artifactIdentity !in invalidArtifactKeys
            ) {
                val refreshed = event.copy(receivedAtMillis = clock.nowMillis())
                if (channelFor(event.handler.lane).trySend(refreshed).isSuccess) {
                    activeAdmissions += event.admissionKey
                    activeScopeArtifactKeys[event.admissionKey] = event.scope.artifactIdentity
                    pending++
                    pendingBySource[event.sourcePendingKey] = (pendingBySource[event.sourcePendingKey] ?: 0) + 1
                }
            }
            pruneDiagnosticAggregationsLocked(clock.nowMillis())
            count
        }
        try {
            observer.onCompleted(
                PluginEventExecutionReport(
                    eventId = event.hostEventId,
                    eventName = event.envelope.name,
                    status = outcome.status,
                    errorCode = outcome.errorCode,
                    occurrenceCount = occurrenceCount,
                    sourceKey = event.scope.sourceKey,
                ),
            )
        } catch (_: Throwable) {
            // A diagnostic/UI observer is untrusted from the dispatcher perspective.
        }
    }

    private fun isLive(event: QueuedPluginEvent): Boolean = stateLock.withLock {
        !closed &&
            event.scope.runtimeKey !in invalidRuntimeKeys &&
            event.scope.artifactIdentity !in invalidArtifactKeys &&
            activeAdmissions.contains(event.admissionKey) &&
            authorizer.authorize(
                event.scope,
                event.handler.requiredPermission,
                event.handler.requiredSourceCapability,
            ).allowed
    }

    private fun isRuntimeInvalid(scope: BoundPluginScope): Boolean = stateLock.withLock {
        closed || scope.runtimeKey in invalidRuntimeKeys || scope.artifactIdentity in invalidArtifactKeys
    }

    private fun removeAdmissionsLocked(predicate: (EventAdmissionKey) -> Boolean) {
        val removed = activeAdmissions.filter(predicate)
        removed.forEach { key ->
            activeAdmissions.remove(key)
            activeScopeArtifactKeys.remove(key)
            refreshDirty.remove(key)
            pending = (pending - 1).coerceAtLeast(0)
            val sourcePendingKey = "${key.runtimeKey}|${key.sourceKey}"
            val sourceCount = pendingBySource[sourcePendingKey]?.minus(1) ?: 0
            if (sourceCount <= 0) pendingBySource.remove(sourcePendingKey)
            else pendingBySource[sourcePendingKey] = sourceCount
        }
    }

    /** Clears admission-local state for a runtime without touching its replay tombstone. */
    private fun clearRuntimeStateLocked(runtimeKey: String) {
        runtimeArtifacts.remove(runtimeKey)
        val sourcePrefix = "$runtimeKey|"
        buckets.keys.removeAll { it.startsWith(sourcePrefix) }
        loginCooldowns.keys.removeAll { it.startsWith(sourcePrefix) }
        // Invalidated runtimes cannot accept new work; existing coroutine jobs hold their own
        // Mutex reference, so removing the registry entry is safe even before cancellation joins.
        sourceMutexes.keys.removeAll { it.startsWith(sourcePrefix) }
        diagnosticOccurrences.keys.removeAll { it.runtimeKey == runtimeKey }
        diagnosticLastMillis.keys.removeAll { it.runtimeKey == runtimeKey }
    }

    /** Drops idle per-source mutexes once no queued/running work can acquire them. */
    private fun cleanupSourceMutexLocked(sourcePendingKey: String) {
        val separator = sourcePendingKey.indexOf('|')
        if (separator < 0) return
        val runtimeKey = sourcePendingKey.substring(0, separator)
        val sourceKey = sourcePendingKey.substring(separator + 1)
        if (activeAdmissions.any { it.runtimeKey == runtimeKey && it.sourceKey == sourceKey }) return
        if (runningJobs.keys.any { it.runtimeKey == runtimeKey && it.sourceKey == sourceKey }) return
        sourceMutexes.remove(sourcePendingKey)
    }

    /** Keeps completed diagnostic windows bounded and removes them after their aggregation TTL. */
    private fun pruneDiagnosticAggregationsLocked(now: Long) {
        val aggregationWindow = limits.diagnosticAggregationMillis
        val stale = diagnosticLastMillis.filterValues { last ->
            aggregationWindow == 0L || now - last > aggregationWindow
        }.keys.toList()
        stale.forEach {
            diagnosticLastMillis.remove(it)
            diagnosticOccurrences.remove(it)
        }
        while (diagnosticLastMillis.size > limits.maxDiagnosticAggregations) {
            val oldest = diagnosticLastMillis.minByOrNull { it.value }?.key ?: break
            diagnosticLastMillis.remove(oldest)
            diagnosticOccurrences.remove(oldest)
        }
    }

    private fun consumeTokenLocked(sourceTypeKey: String, now: Long): Long? {
        val bucket = buckets.getOrPut(sourceTypeKey) { TokenBucket(limits.tokenBurst.toDouble(), now) }
        val elapsed = (now - bucket.lastMillis).coerceAtLeast(0)
        bucket.tokens = min(
            limits.tokenBurst.toDouble(),
            bucket.tokens + elapsed.toDouble() * limits.tokenPerMinute.toDouble() / 60_000.0,
        )
        bucket.lastMillis = now
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return null
        }
        val wait = ceil((1.0 - bucket.tokens) * 60_000.0 / limits.tokenPerMinute).toLong()
        return wait.coerceAtLeast(1)
    }

    private fun dedupeKey(envelope: PluginSystemEventEnvelope, payload: Any): String = when {
        envelope.name == PluginSystemEventNames.SOURCE_REFRESH_REQUEST -> {
            val request = payload as? SourceRefreshRequestV1
            "refresh:${request?.scope}:${envelope.contextRef.orEmpty()}"
        }
        envelope.name == PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT -> {
            val report = payload as? DiagnosticMessageV1
            "diagnostic:${report?.code}:${report?.operation.orEmpty()}"
        }
        envelope.name == PluginSystemEventNames.AUTH_LOGIN_REQUEST ||
            envelope.name == PluginSystemEventNames.AUTH_LOGOUT_REQUEST -> "serial"
        else -> envelope.idempotencyKey ?: "id:${envelope.id}"
    }

    private fun channelFor(lane: PluginSystemEventLane): Channel<QueuedPluginEvent> = when (lane) {
        PluginSystemEventLane.MODAL -> modal
        PluginSystemEventLane.REFRESH -> refresh
        PluginSystemEventLane.TRANSIENT -> transient
    }

    private fun nextOperationRefLocked(): String {
        operationCounter = (operationCounter + 1).coerceAtLeast(1)
        return "op-$operationCounter"
    }

    private fun receipt(
        messageId: String,
        disposition: PluginEventDisposition,
        operationRef: String? = null,
        retryAfterMillis: Long? = null,
    ): PluginEventReceipt = PluginEventReceipt(
        messageId = messageId.takeIf { isSafeAsciiIdentifier(it, 64) }.orEmpty(),
        disposition = disposition,
        operationRef = operationRef,
        retryAfterMillis = retryAfterMillis,
    )

    private fun isContextReferenceAccepted(scope: BoundPluginScope, contextRef: String): Boolean =
        contextRegistry?.accepts(scope, contextRef) == true ||
            (contextRegistry == null && contextReferenceValidator.accepts(scope, contextRef))

    /** Event-to-artifact index used only for invalidation bookkeeping, never wire output. */
    private val activeScopeArtifactKeys: MutableMap<EventAdmissionKey, PluginArtifactIdentity> = linkedMapOf()
}
