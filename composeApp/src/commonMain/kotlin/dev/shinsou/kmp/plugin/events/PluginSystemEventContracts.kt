@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

/** The protocol namespace is deliberately separate from repository and content contracts. */
public object PluginSystemEventProtocol {
    public const val NAME: String = "dev.shinsou.system"
    public const val VERSION: Int = 1
}

/** Names are registry keys, not an open-ended action language. */
public object PluginSystemEventNames {
    public const val AUTH_LOGIN_REQUEST: String = "auth.login.request"
    public const val SOURCE_REFRESH_REQUEST: String = "source.refresh.request"
    public const val AUTH_LOGOUT_REQUEST: String = "auth.logout.request"
    public const val DIAGNOSTIC_MESSAGE_REPORT: String = "diagnostic.message.report"

    public const val LOGIN_CAPABILITY: String = "command.auth.login.request"
    public const val REFRESH_CAPABILITY: String = "command.source.refresh.request"
    public const val LOGOUT_CAPABILITY: String = "command.auth.logout.request"
    public const val DIAGNOSTIC_CAPABILITY: String = "event.diagnostic.message.report"

    public val V1: Set<String> = setOf(
        LOGIN_CAPABILITY,
        REFRESH_CAPABILITY,
        LOGOUT_CAPABILITY,
        DIAGNOSTIC_CAPABILITY,
    )
}

/** Host permissions are intentionally not [dev.shinsou.kmp.plugin.v2.ExtensionCapability]. */
@Serializable
public enum class PluginHostPermission {
    REQUEST_LOGIN_UI,
    REQUEST_SOURCE_REFRESH,
    REQUEST_LOGOUT,
    REPORT_DIAGNOSTIC,
    REPORT_USER_MESSAGE,
    REQUEST_BROWSER_CHALLENGE,
}

@Serializable
public enum class PluginSystemEventLane {
    MODAL,
    REFRESH,
    TRANSIENT,
}

@Serializable(with = PluginSystemEventKindSerializer::class)
public enum class PluginSystemEventKind {
    COMMAND,

    EVENT,

    /** Unknown wire kinds are retained as unsupported instead of becoming executable actions. */
    UNKNOWN,
}

public object PluginSystemEventKindSerializer : KSerializer<PluginSystemEventKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PluginSystemEventKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PluginSystemEventKind): Unit =
        encoder.encodeString(
            when (value) {
                PluginSystemEventKind.COMMAND -> "command"
                PluginSystemEventKind.EVENT -> "event"
                PluginSystemEventKind.UNKNOWN -> "unknown"
            },
        )

    override fun deserialize(decoder: Decoder): PluginSystemEventKind = when (decoder.decodeString()) {
        "command" -> PluginSystemEventKind.COMMAND
        "event" -> PluginSystemEventKind.EVENT
        else -> PluginSystemEventKind.UNKNOWN
    }
}

@Serializable
public enum class PluginEventDisposition {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("deduplicated")
    DEDUPLICATED,

    @SerialName("denied")
    DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("throttled")
    THROTTLED,

    @SerialName("invalid")
    INVALID,

    @SerialName("busy")
    BUSY,

    @SerialName("runtime_closed")
    RUNTIME_CLOSED,
}

/** The only receipt returned by the synchronous bridge ingress. */
@Serializable
public data class PluginEventReceipt(
    val protocol: String = PluginSystemEventProtocol.NAME,
    val version: Int = PluginSystemEventProtocol.VERSION,
    val messageId: String,
    val disposition: PluginEventDisposition,
    val operationRef: String? = null,
    val retryAfterMillis: Long? = null,
) {
    init {
        require(protocol == PluginSystemEventProtocol.NAME) { "Invalid system event receipt protocol" }
        require(version == PluginSystemEventProtocol.VERSION) { "Invalid system event receipt version" }
        require(messageId.isEmpty() || isSafeAsciiIdentifier(messageId, 64)) {
            "Invalid system event receipt message id"
        }
        require(operationRef == null || isSafeAsciiIdentifier(operationRef, 64)) {
            "Invalid system event receipt operation reference"
        }
        require(retryAfterMillis == null || retryAfterMillis >= 0) {
            "Invalid system event retry hint"
        }
        if (disposition != PluginEventDisposition.THROTTLED) {
            require(retryAfterMillis == null) { "Only throttled receipts may carry a retry hint" }
        }
        if (disposition != PluginEventDisposition.ACCEPTED) {
            require(operationRef == null) { "Only accepted receipts may carry an operation reference" }
        }
    }
}

/**
 * Native transport seam. Submission is synchronous, CPU-only and bounded; implementations must
 * never wait for UI, domain work, or plugin execution before returning the receipt.
 */
public fun interface ScopedPluginSystemEventSink {
    public fun submit(scope: BoundPluginScope, utf8Envelope: ByteArray): PluginEventReceipt
}

/** Exact installed artifact identity. A package id alone is never an authorization key. */
@Serializable
public data class PluginArtifactIdentity(
    val packageId: String,
    val version: String,
    val versionCode: Int,
    val sha256: String,
) {
    init {
        require(isSafeAsciiIdentifier(packageId, 128)) { "Invalid plugin package id" }
        require(isSafeAsciiIdentifier(version, 64)) { "Invalid plugin version" }
        require(versionCode > 0) { "Plugin version code must be positive" }
        require(SHA256_PATTERN.matches(sha256)) { "Plugin digest must be lowercase SHA-256" }
    }
}

/**
 * Runtime-injected scope. The constructor is internal so a transport cannot construct a scope
 * from fields received over the wire; platform code obtains one from [BoundPluginScopeFactory].
 */
public class BoundPluginScope internal constructor(
    public val artifactIdentity: PluginArtifactIdentity,
    public val sourceKey: SourceKey,
    public val runtimeInstanceId: String,
    public val runtimeGeneration: Long,
    public val invocationContext: String?,
    public val receivedAtMonotonicMillis: Long,
    private val binding: Any,
) {
    init {
        require(artifactIdentity.packageId == sourceKey.packageId) {
            "Plugin artifact and source package identities must match"
        }
        require(isSafeAsciiIdentifier(runtimeInstanceId, 128)) { "Invalid runtime instance id" }
        require(runtimeGeneration > 0) { "Runtime generation must be positive" }
        invocationContext?.let { require(isSafeAsciiIdentifier(it, 64)) { "Invalid invocation context" } }
    }

    /** Internal comparison key; never put this value in a receipt or UI DTO. */
    internal val runtimeKey: String
        get() = "$runtimeInstanceId#$runtimeGeneration#${artifactIdentity.sha256}"

    /** Internal marker used to keep scope objects host-created even on JVM tests. */
    internal fun isHostBound(): Boolean = binding === HOST_BINDING

    public companion object {
        private val HOST_BINDING: Any = Any()

        internal fun hostBinding(): Any = HOST_BINDING
    }
}

/** Host-only factory for injecting identity and generation at runtime creation. */
public class BoundPluginScopeFactory(
    private val clock: PluginEventClock = SystemPluginEventClock,
) {
    public fun bind(
        artifactIdentity: PluginArtifactIdentity,
        sourceKey: SourceKey,
        runtimeInstanceId: String,
        runtimeGeneration: Long,
        invocationContext: String? = null,
    ): BoundPluginScope = BoundPluginScope(
        artifactIdentity = artifactIdentity,
        sourceKey = sourceKey,
        runtimeInstanceId = runtimeInstanceId,
        runtimeGeneration = runtimeGeneration,
        invocationContext = invocationContext,
        receivedAtMonotonicMillis = clock.nowMillis(),
        binding = BoundPluginScope.hostBinding(),
    )
}

/** Injectable monotonic clock makes expiry and throttling deterministic in common tests. */
public fun interface PluginEventClock {
    public fun nowMillis(): Long
}

public object SystemPluginEventClock : PluginEventClock {
    private val origin: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

/** Hard host limits. None of these values can be raised by a plugin declaration. */
@Serializable
public data class PluginSystemEventLimits(
    val maxEnvelopeBytes: Int = 8 * 1024,
    val maxNestingDepth: Int = 4,
    val maxMapEntries: Int = 16,
    val maxListEntries: Int = 16,
    val maxIdentifierBytes: Int = 64,
    val maxEventNameBytes: Int = 96,
    val maxReasonBytes: Int = 256,
    val maxDiagnosticBytes: Int = 512,
    val maxPerSourcePending: Int = 32,
    val maxGlobalPending: Int = 128,
    val tokenBurst: Int = 5,
    val tokenPerMinute: Int = 20,
    val ttlMillis: Long = 30_000,
    val loginCooldownMillis: Long = 1_000,
    val diagnosticAggregationMillis: Long = 5_000,
    /** Maximum number of completed diagnostic aggregation windows retained per gateway. */
    val maxDiagnosticAggregations: Int = 256,
) {
    init {
        require(maxEnvelopeBytes in 256..(64 * 1024))
        require(maxNestingDepth in 1..16)
        require(maxMapEntries in 1..256)
        require(maxListEntries in 1..256)
        require(maxIdentifierBytes in 1..256)
        require(maxEventNameBytes in 1..512)
        require(maxReasonBytes in 1..(64 * 1024))
        require(maxDiagnosticBytes in 1..(64 * 1024))
        require(maxPerSourcePending in 1..1024)
        require(maxGlobalPending in 1..4096)
        require(tokenBurst in 1..1024)
        require(tokenPerMinute in 1..60_000)
        require(ttlMillis > 0)
        require(loginCooldownMillis >= 0)
        require(diagnosticAggregationMillis >= 0)
        require(maxDiagnosticAggregations in 1..4096)
    }
}

@Serializable
public data class LoginRequestV1(
    val reasonCode: String? = null,
    val fallbackMessage: String? = null,
) {
    init {
        reasonCode?.let { requireSafeCode(it) }
        fallbackMessage?.let { requireSafePlainText(it, 256) }
    }
}

@Serializable
public enum class SourceRefreshScope {
    SELF,
    ACTIVE_CONTEXT,
}

@Serializable
public data class SourceRefreshRequestV1(
    val scope: SourceRefreshScope = SourceRefreshScope.SELF,
    val reasonCode: String? = null,
) {
    init { reasonCode?.let { requireSafeCode(it) } }
}

@Serializable
public data class LogoutRequestV1(
    val reasonCode: String? = null,
    val fallbackMessage: String? = null,
) {
    init {
        reasonCode?.let { requireSafeCode(it) }
        fallbackMessage?.let { requireSafePlainText(it, 256) }
    }
}

@Serializable
public enum class PluginDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

@Serializable
public data class DiagnosticMessageV1(
    val code: String,
    val operation: String? = null,
    val severity: PluginDiagnosticSeverity,
    val retryable: Boolean? = null,
    val fallbackMessage: String,
) {
    init {
        requireSafeCode(code)
        require(operation == null || isSafeAsciiIdentifier(operation, 64)) {
            "Invalid diagnostic operation"
        }
        requireSafePlainText(fallbackMessage, 512)
        require(!code.startsWith("host.", ignoreCase = true)) {
            "Plugin diagnostics cannot use the host namespace"
        }
    }
}

/** Declaration used during host/plugin capability negotiation. */
@Serializable
public data class PluginSystemEventDeclaration(
    val minVersion: Int,
    val maxVersion: Int,
    val required: Set<String> = emptySet(),
    val optional: Set<String> = emptySet(),
) {
    init {
        require(minVersion > 0 && maxVersion >= minVersion) { "Invalid event protocol range" }
        require(required.size <= 16 && optional.size <= 16) { "Too many event capabilities" }
        require(required.intersect(optional).isEmpty()) { "Capability cannot be required and optional" }
        (required + optional).forEach { requireSafeCapabilityId(it) }
    }
}

@Serializable
public data class PluginSystemEventNegotiation(
    val enabled: Boolean,
    val version: Int? = null,
    val grantedCapabilities: Set<String> = emptySet(),
    val deniedRequiredCapabilities: Set<String> = emptySet(),
    val hardLimits: PluginSystemEventLimits = PluginSystemEventLimits(),
)

public class PluginSystemCapabilityNegotiator(
    private val supportedVersion: Int = PluginSystemEventProtocol.VERSION,
    supportedCapabilities: Set<String> = PluginSystemEventNames.V1,
    private val hardLimits: PluginSystemEventLimits = PluginSystemEventLimits(),
) {
    private val supported: Set<String> = supportedCapabilities.toSet()

    init { require(supportedVersion > 0) }

    public fun negotiate(declaration: PluginSystemEventDeclaration): PluginSystemEventNegotiation {
        if (supportedVersion !in declaration.minVersion..declaration.maxVersion) {
            return PluginSystemEventNegotiation(
                enabled = false,
                deniedRequiredCapabilities = declaration.required,
                hardLimits = hardLimits,
            )
        }
        val denied = declaration.required - supported
        return PluginSystemEventNegotiation(
            enabled = denied.isEmpty(),
            version = supportedVersion.takeIf { denied.isEmpty() },
            grantedCapabilities = (declaration.required + declaration.optional).intersect(supported),
            deniedRequiredCapabilities = denied,
            hardLimits = hardLimits,
        )
    }
}

/** Artifact/source grant key. A null source key is a package-wide grant, never a cross-artifact grant. */
@Serializable
public data class PluginEventGrantKey(
    val artifact: PluginArtifactIdentity,
    val sourceKey: SourceKey? = null,
)

@Serializable
public data class PluginEventGrant(
    val key: PluginEventGrantKey,
    val permissions: Set<PluginHostPermission>,
)

@Serializable
public enum class PluginRuntimeLifecycle {
    OPEN_FOREGROUND_UNLOCKED,
    OPEN_FOREGROUND_LOCKED,
    OPEN_BACKGROUND,
    DISABLED,
    CLOSED,
}

public data class PluginEventRuntimeStatus(
    val lifecycle: PluginRuntimeLifecycle = PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED,
    val hasUserInteractionContext: Boolean = false,
    val sourceCapabilities: Set<String> = emptySet(),
)

public enum class PluginEventAuthorizationReason {
    ALLOWED,
    NO_EXACT_GRANT,
    MISSING_PERMISSION,
    MISSING_SOURCE_CAPABILITY,
    LIFECYCLE,
    INTERACTION_REQUIRED,
    REVOKED,
}

public data class PluginEventAuthorization(
    val allowed: Boolean,
    val reason: PluginEventAuthorizationReason,
)

/** Synchronous authorization seam used by the bounded gateway. */
public fun interface PluginSystemEventAuthorizer {
    public fun authorize(
        scope: BoundPluginScope,
        requiredPermission: PluginHostPermission,
        requiredSourceCapability: String?,
    ): PluginEventAuthorization

    /**
     * Re-authorizes an event that already passed admission while a user interaction context was
     * active. The interaction context is intentionally an admission-time fact: the dispatcher is
     * asynchronous, so the short-lived UI context may end before the queued handler starts. All
     * other runtime, lifecycle, capability, and exact-grant checks must still be performed.
     *
     * Custom authorizers that do not model this distinction retain the fail-closed default by
     * delegating to [authorize].
     */
    public fun authorizeQueuedEvent(
        scope: BoundPluginScope,
        requiredPermission: PluginHostPermission,
        requiredSourceCapability: String?,
        interactionContextAdmitted: Boolean,
    ): PluginEventAuthorization = authorize(scope, requiredPermission, requiredSourceCapability)
}

public object DenyAllPluginSystemEventAuthorizer : PluginSystemEventAuthorizer {
    override fun authorize(
        scope: BoundPluginScope,
        requiredPermission: PluginHostPermission,
        requiredSourceCapability: String?,
    ): PluginEventAuthorization = PluginEventAuthorization(
        allowed = false,
        reason = PluginEventAuthorizationReason.NO_EXACT_GRANT,
    )
}

/**
 * Small host-owned in-memory policy useful for composition and common tests. Production hosts may
 * implement [PluginSystemEventAuthorizer] over their reviewed-artifact approval store instead.
 */
public class MutablePluginSystemEventAuthorizer : PluginSystemEventAuthorizer {
    private val lock: SynchronousLock = SynchronousLock()
    private val grants = linkedMapOf<PluginEventGrantKey, Set<PluginHostPermission>>()
    private val statuses = linkedMapOf<String, PluginEventRuntimeStatus>()
    private val revokedRuntimeKeys = linkedSetOf<String>()

    public fun grant(
        key: PluginEventGrantKey,
        permissions: Set<PluginHostPermission>,
    ): Unit = lock.withLock {
        grants[key] = permissions.toSet()
    }

    public fun revoke(key: PluginEventGrantKey): Unit = lock.withLock {
        grants.remove(key)
    }

    public fun setRuntimeStatus(
        scope: BoundPluginScope,
        status: PluginEventRuntimeStatus,
    ): Unit = lock.withLock {
        statuses[scope.runtimeKey] = status
        if (status.lifecycle == PluginRuntimeLifecycle.CLOSED) revokedRuntimeKeys += scope.runtimeKey
        else revokedRuntimeKeys -= scope.runtimeKey
    }

    public fun closeRuntime(scope: BoundPluginScope): Unit = lock.withLock {
        revokedRuntimeKeys += scope.runtimeKey
        statuses[scope.runtimeKey] = PluginEventRuntimeStatus(lifecycle = PluginRuntimeLifecycle.CLOSED)
    }

    public fun clearRuntime(scope: BoundPluginScope): Unit = lock.withLock {
        statuses.remove(scope.runtimeKey)
        revokedRuntimeKeys.remove(scope.runtimeKey)
    }

    public fun setUserInteractionContext(scope: BoundPluginScope, active: Boolean): Unit = lock.withLock {
        val current = statuses[scope.runtimeKey] ?: PluginEventRuntimeStatus()
        statuses[scope.runtimeKey] = current.copy(hasUserInteractionContext = active)
    }

    public fun setRuntimeLifecycle(scope: BoundPluginScope, lifecycle: PluginRuntimeLifecycle): Unit = lock.withLock {
        val current = statuses[scope.runtimeKey] ?: PluginEventRuntimeStatus()
        statuses[scope.runtimeKey] = current.copy(
            lifecycle = lifecycle,
            hasUserInteractionContext = current.hasUserInteractionContext &&
                lifecycle == PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED,
        )
    }

    /** Exact reviewed/user grants only; requested repository permissions never enter this map. */
    public fun grantedPermissions(scope: BoundPluginScope): Set<PluginHostPermission> = lock.withLock {
        grants[PluginEventGrantKey(scope.artifactIdentity, scope.sourceKey)]
            ?: grants[PluginEventGrantKey(scope.artifactIdentity, null)]
            ?: emptySet()
    }

    override fun authorize(
        scope: BoundPluginScope,
        requiredPermission: PluginHostPermission,
        requiredSourceCapability: String?,
    ): PluginEventAuthorization = authorizeInternal(
        scope = scope,
        requiredPermission = requiredPermission,
        requiredSourceCapability = requiredSourceCapability,
        interactionContextAdmitted = false,
    )

    override fun authorizeQueuedEvent(
        scope: BoundPluginScope,
        requiredPermission: PluginHostPermission,
        requiredSourceCapability: String?,
        interactionContextAdmitted: Boolean,
    ): PluginEventAuthorization = authorizeInternal(
        scope = scope,
        requiredPermission = requiredPermission,
        requiredSourceCapability = requiredSourceCapability,
        interactionContextAdmitted = interactionContextAdmitted,
    )

    private fun authorizeInternal(
        scope: BoundPluginScope,
        requiredPermission: PluginHostPermission,
        requiredSourceCapability: String?,
        interactionContextAdmitted: Boolean,
    ): PluginEventAuthorization = lock.withLock {
        if (scope.runtimeKey in revokedRuntimeKeys) {
            return@withLock PluginEventAuthorization(false, PluginEventAuthorizationReason.REVOKED)
        }
        val status = statuses[scope.runtimeKey] ?: PluginEventRuntimeStatus()
        if (status.lifecycle == PluginRuntimeLifecycle.CLOSED || status.lifecycle == PluginRuntimeLifecycle.DISABLED) {
            return@withLock PluginEventAuthorization(false, PluginEventAuthorizationReason.LIFECYCLE)
        }
        val sourceGrant = grants[PluginEventGrantKey(scope.artifactIdentity, scope.sourceKey)]
        val packageGrant = grants[PluginEventGrantKey(scope.artifactIdentity, null)]
        val permissions = sourceGrant ?: packageGrant
            ?: return@withLock PluginEventAuthorization(false, PluginEventAuthorizationReason.NO_EXACT_GRANT)
        if (requiredPermission !in permissions) {
            return@withLock PluginEventAuthorization(false, PluginEventAuthorizationReason.MISSING_PERMISSION)
        }
        if (requiredSourceCapability != null && requiredSourceCapability !in status.sourceCapabilities) {
            return@withLock PluginEventAuthorization(
                false,
                PluginEventAuthorizationReason.MISSING_SOURCE_CAPABILITY,
            )
        }
        if (requiredPermission == PluginHostPermission.REQUEST_LOGIN_UI ||
            requiredPermission == PluginHostPermission.REQUEST_LOGOUT
        ) {
            if (status.lifecycle != PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED) {
                return@withLock PluginEventAuthorization(false, PluginEventAuthorizationReason.LIFECYCLE)
            }
            if (!status.hasUserInteractionContext && !interactionContextAdmitted) {
                return@withLock PluginEventAuthorization(false, PluginEventAuthorizationReason.INTERACTION_REQUIRED)
            }
        }
        PluginEventAuthorization(true, PluginEventAuthorizationReason.ALLOWED)
    }
}

/** Strict codec shared by both native transports. */
public class PluginSystemEventCodec(
    public val limits: PluginSystemEventLimits = PluginSystemEventLimits(),
) {
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
        isLenient = false
        allowTrailingComma = false
    }

    public fun decode(utf8Envelope: ByteArray): PluginSystemEventEnvelope {
        if (utf8Envelope.size > limits.maxEnvelopeBytes) {
            throw PluginSystemEventCodecException("System event envelope exceeds the byte limit")
        }
        val text = try {
            utf8Envelope.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("System event envelope is not valid UTF-8")
        }
        JsonStructureValidator(text, limits).validate()
        val root = try {
            json.decodeFromString(JsonElement.serializer(), text)
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("System event envelope is not valid JSON")
        }
        val objectRoot = root as? JsonObject
            ?: throw PluginSystemEventCodecException("System event envelope must be an object")
        try {
            requireKeys(objectRoot, ENVELOPE_KEYS, ENVELOPE_REQUIRED_KEYS, "envelope")
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("Unknown or missing envelope field")
        }
        val envelope = try {
            json.decodeFromJsonElement(PluginSystemEventEnvelope.serializer(), objectRoot)
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("System event envelope has an invalid schema")
        }
        try {
            envelope.validate(limits)
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("System event envelope is outside its limits")
        }
        return envelope
    }

    public fun encode(envelope: PluginSystemEventEnvelope): ByteArray {
        envelope.validate(limits)
        val encoded = json.encodeToString(PluginSystemEventEnvelope.serializer(), envelope)
        val bytes = encoded.encodeToByteArray()
        require(bytes.size <= limits.maxEnvelopeBytes) {
            "System event envelope exceeds the byte limit"
        }
        JsonStructureValidator(encoded, limits).validate()
        return bytes
    }

    public fun <P> decodePayload(
        envelope: PluginSystemEventEnvelope,
        serializer: KSerializer<P>,
        validate: (P) -> Unit = {},
    ): P = decodePayload(envelope.payload, serializer, validate)

    public fun <P> decodePayload(
        payload: JsonElement,
        serializer: KSerializer<P>,
        validate: (P) -> Unit = {},
    ): P {
        val decoded = try {
            json.decodeFromJsonElement(serializer, payload)
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("System event payload has an invalid schema")
        }
        try {
            validate(decoded)
        } catch (_: Throwable) {
            throw PluginSystemEventCodecException("System event payload is outside its limits")
        }
        return decoded
    }

    public fun <P> encodePayload(
        protocolVersion: Int = PluginSystemEventProtocol.VERSION,
        kind: PluginSystemEventKind,
        name: String,
        id: String,
        idempotencyKey: String? = null,
        payloadVersion: Int = 1,
        contextRef: String? = null,
        payload: P,
        serializer: KSerializer<P>,
    ): ByteArray = encode(
        PluginSystemEventEnvelope(
            protocol = PluginSystemEventProtocol.NAME,
            version = protocolVersion,
            kind = kind,
            name = name,
            id = id,
            idempotencyKey = idempotencyKey,
            payloadVersion = payloadVersion,
            contextRef = contextRef,
            payload = json.encodeToJsonElement(serializer, payload).jsonObject,
        ),
    )

    private companion object {
        val ENVELOPE_KEYS: Set<String> = setOf(
            "protocol",
            "version",
            "kind",
            "name",
            "id",
            "idempotencyKey",
            "payloadVersion",
            "contextRef",
            "payload",
        )
        val ENVELOPE_REQUIRED_KEYS: Set<String> = setOf(
            "protocol",
            "version",
            "kind",
            "name",
            "id",
            "payloadVersion",
            "payload",
        )
    }
}

@Serializable
public data class PluginSystemEventEnvelope(
    val protocol: String,
    val version: Int,
    val kind: PluginSystemEventKind,
    val name: String,
    val id: String,
    val idempotencyKey: String? = null,
    val payloadVersion: Int,
    val contextRef: String? = null,
    val payload: JsonObject,
) {
    internal fun validate(limits: PluginSystemEventLimits): Unit {
        require(isSafeAsciiIdentifier(protocol, 128)) { "Invalid system event protocol" }
        require(version > 0) { "Invalid system event version" }
        require(name.isNotEmpty() && isSafeAsciiIdentifier(name, limits.maxEventNameBytes)) {
            "Invalid system event name"
        }
        require(isSafeAsciiIdentifier(id, limits.maxIdentifierBytes)) { "Invalid system event id" }
        idempotencyKey?.let { require(isSafeAsciiIdentifier(it, limits.maxIdentifierBytes)) }
        require(payloadVersion > 0) { "Invalid system event payload version" }
        contextRef?.let { require(isSafeAsciiIdentifier(it, limits.maxIdentifierBytes)) }
        require(payload.keys.size <= limits.maxMapEntries) { "Too many event payload fields" }
    }
}

public class PluginSystemEventCodecException(message: String) : IllegalArgumentException(message)

private class JsonStructureValidator(
    private val input: String,
    private val limits: PluginSystemEventLimits,
) {
    private var index: Int = 0

    public fun validate(): Unit {
        skipWhitespace()
        parseValue(0)
        skipWhitespace()
        if (index != input.length) fail("Trailing JSON data")
    }

    private fun parseValue(depth: Int) {
        if (depth > limits.maxNestingDepth) fail("JSON nesting exceeds the limit")
        when (input.getOrNull(index)) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> fail("Invalid JSON value")
        }
    }

    private fun parseObject(depth: Int) {
        expect('{')
        skipWhitespace()
        val keys = linkedSetOf<String>()
        var count = 0
        if (peek('}')) {
            index++
            return
        }
        while (true) {
            if (++count > limits.maxMapEntries) fail("JSON object has too many entries")
            skipWhitespace()
            if (input.getOrNull(index) != '"') fail("JSON object key must be a string")
            val key = parseString()
            if (!keys.add(key)) fail("Duplicate JSON object key")
            skipWhitespace()
            expect(':')
            skipWhitespace()
            parseValue(depth)
            skipWhitespace()
            when {
                peek('}') -> {
                    index++
                    return
                }
                peek(',') -> index++
                else -> fail("Expected JSON object separator")
            }
        }
    }

    private fun parseArray(depth: Int) {
        expect('[')
        skipWhitespace()
        var count = 0
        if (peek(']')) {
            index++
            return
        }
        while (true) {
            if (++count > limits.maxListEntries) fail("JSON array has too many entries")
            skipWhitespace()
            parseValue(depth)
            skipWhitespace()
            when {
                peek(']') -> {
                    index++
                    return
                }
                peek(',') -> index++
                else -> fail("Expected JSON array separator")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val character = input.getOrNull(index++) ?: fail("Unterminated JSON string")
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    when (val escape = input.getOrNull(index++) ?: fail("Invalid JSON escape")) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = input.substringOrNull(index, index + 4)
                                ?: fail("Invalid JSON unicode escape")
                            if (hex.length != 4 || hex.any { it.digitToIntOrNull(16) == null }) {
                                fail("Invalid JSON unicode escape")
                            }
                            result.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> fail("Invalid JSON escape")
                    }
                }
                character.code < 0x20 -> fail("Control character in JSON string")
                else -> result.append(character)
            }
        }
    }

    private fun parseLiteral(literal: String) {
        if (!input.regionMatches(index, literal, 0, literal.length)) fail("Invalid JSON literal")
        index += literal.length
    }

    private fun parseNumber() {
        val start = index
        if (peek('-')) index++
        when {
            peek('0') -> index++
            input.getOrNull(index)?.let { it in '1'..'9' } == true -> {
                while (input.getOrNull(index)?.let { it in '0'..'9' } == true) index++
            }
            else -> fail("Invalid JSON number")
        }
        if (peek('.')) {
            index++
            if (input.getOrNull(index)?.let { it in '0'..'9' } != true) fail("Invalid JSON fraction")
            while (input.getOrNull(index)?.let { it in '0'..'9' } == true) index++
        }
        if (input.getOrNull(index) == 'e' || input.getOrNull(index) == 'E') {
            index++
            if (peek('+') || peek('-')) index++
            if (input.getOrNull(index)?.let { it in '0'..'9' } != true) fail("Invalid JSON exponent")
            while (input.getOrNull(index)?.let { it in '0'..'9' } == true) index++
        }
        if (index == start) fail("Invalid JSON number")
    }

    private fun skipWhitespace() {
        while (input.getOrNull(index)?.let { it == ' ' || it == '\n' || it == '\r' || it == '\t' } == true) {
            index++
        }
    }

    private fun expect(character: Char) {
        if (input.getOrNull(index++) != character) fail("Expected '$character'")
    }

    private fun peek(character: Char): Boolean = input.getOrNull(index) == character

    private fun fail(message: String): Nothing = throw PluginSystemEventCodecException(message)
}

private fun String.substringOrNull(start: Int, end: Int): String? =
    if (start < 0 || end > length || start > end) null else substring(start, end)

internal fun requireKeys(
    value: JsonObject,
    allowed: Set<String>,
    required: Set<String>,
    label: String,
) {
    require(value.keys.all { it in allowed } && required.all { it in value.keys }) {
        "Unknown or missing $label field"
    }
}

internal fun isSafeAsciiIdentifier(value: String, maxBytes: Int): Boolean =
    value.isNotEmpty() && value.encodeToByteArray().size <= maxBytes &&
        value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".:_/-~" }

internal fun requireSafeCode(value: String): Unit {
    require(isSafeAsciiIdentifier(value, 64)) { "Invalid safe code" }
}

internal fun requireSafeCapabilityId(value: String): Unit {
    require(isSafeAsciiIdentifier(value, 128)) { "Invalid capability id" }
}

internal fun requireSafePlainText(value: String, maxBytes: Int): Unit {
    require(value.encodeToByteArray().size <= maxBytes) { "Plain text exceeds its byte limit" }
    require(value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        "Plain text contains a control character"
    }
    require(!Regex("(?i)(https?|javascript):\\S+").containsMatchIn(value)) {
        "Plain text cannot contain links"
    }
    require(value.none { it in "<>`[]{}" }) { "Plain text contains markup syntax" }
    require(!SECRET_TEXT_PATTERN.containsMatchIn(value)) {
        "Plain text resembles credential or secret material"
    }
    require(!STACK_TRACE_PATTERN.containsMatchIn(value)) {
        "Plain text resembles a stack trace"
    }
}

private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
private val SECRET_TEXT_PATTERN: Regex = Regex(
    "(?i)(?:authorization\\s*:\\s*(?:bearer|basic)\\s+\\S+|" +
        "(?:username|password|passwd|credential|cookie|token|api[_-]?key|secret(?:[_-]?ref)?)" +
        "\\s*(?:=|:)\\s*[^\\s,;]+)",
)
private val STACK_TRACE_PATTERN: Regex = Regex("(?m)(?:^|\\n)\\s*at\\s+[^\\n]+\\([^\\n]+:\\d+\\)")
