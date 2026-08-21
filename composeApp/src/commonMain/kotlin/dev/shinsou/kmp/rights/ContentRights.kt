package dev.shinsou.kmp.rights

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact host operations used for all content capability decisions. */
@Serializable
public enum class ContentOperation {
    DISPLAY,
    OFFLINE_STORE,
    SYNC_BLOB,
    EXPORT,
    COPY,
    PRINT,
    TTS,
    SEARCH_INDEX,
    ANNOTATE,
}

/** Compatibility alias for capability-oriented callers. */
public typealias ContentCapability = ContentOperation

@Serializable
public enum class RightsDecision {
    ALLOW,
    DENY,
}

/** Every grant has a typed protection scheme; unsupported providers fail closed. */
@Serializable
public sealed interface ProtectionScheme {
    @Serializable
    @SerialName("none")
    public data object None : ProtectionScheme

    @Serializable
    @SerialName("provider")
    public data class Provider(
        val providerId: String,
        val schemeVersion: Int,
    ) : ProtectionScheme {
        init {
            requireSafeIdentifier(providerId, "Protection provider id")
            require(schemeVersion > 0) { "Protection scheme version must be positive" }
        }
    }

    @Serializable
    @SerialName("encrypted")
    public data class Encrypted(
        val providerId: String,
        val schemeVersion: Int,
        /** Opaque provider evidence reference; key material must never enter this model. */
        val evidenceReference: String,
    ) : ProtectionScheme {
        init {
            requireSafeIdentifier(providerId, "Encryption provider id")
            require(schemeVersion > 0) { "Encryption scheme version must be positive" }
            requireSafeIdentifier(evidenceReference, "Encryption evidence reference")
        }
    }
}

/** A typed scope prevents a grant for one acquisition/unit being reused for another. */
@Serializable
public data class RightsScope(
    val publicationId: PublicationKey,
    val acquisitionId: String,
    val unitId: UnitKey? = null,
    val manifestId: String? = null,
    val contentRevision: Long? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        publicationId.validate()
        requireUuid(acquisitionId, "Rights acquisition id")
        unitId?.validate()
        require(unitId == null || unitId.publicationKey == publicationId) {
            "Rights unit must belong to the publication scope"
        }
        manifestId?.let { requireUuid(it, "Rights manifest id") }
        require(contentRevision == null || contentRevision >= 0) {
            "Rights content revision must be non-negative"
        }
    }
}

/** A host-owned opaque reference persisted by Acquisition. */
@Serializable
public data class RightsGrantRef(
    val value: String,
) {
    init { requireUuid(value, "Rights grant reference") }

    public fun validate(): Unit = requireUuid(value, "Rights grant reference")
}

/** Constraints are explicit and typed; no unknown string map can accidentally elevate access. */
@Serializable
public sealed interface RightsConstraint {
    @Serializable
    @SerialName("max_offline_bytes")
    public data class MaxOfflineBytes(val bytes: Long) : RightsConstraint {
        init { require(bytes >= 0) { "Offline byte limit must be non-negative" } }
    }

    @Serializable
    @SerialName("max_text_chars")
    public data class MaxTextChars(val chars: Long) : RightsConstraint {
        init { require(chars >= 0) { "Text character limit must be non-negative" } }
    }

    @Serializable
    @SerialName("watermark_required")
    public data object WatermarkRequired : RightsConstraint

    /**
     * Applies a constraint only to the listed host operations.
     *
     * The unscoped constraints above retain their compatibility defaults (for example,
     * [MaxOfflineBytes] only applies to [ContentOperation.OFFLINE_STORE]).  A plugin or a
     * host policy that needs a different, explicit scope can use this wrapper.  The wrapper is
     * still a restriction: it can never add an operation to a grant.
     */
    @Serializable
    @SerialName("for_operations")
    public data class ForOperations(
        val operations: Set<ContentOperation>,
        val constraint: RightsConstraint,
    ) : RightsConstraint {
        init {
            require(operations.isNotEmpty()) { "A scoped rights constraint needs an operation" }
            require(operations.size <= ContentOperation.entries.size) {
                "A scoped rights constraint contains unsupported operations"
            }
            require(constraint !is ForOperations) {
                "Rights constraints cannot be nested"
            }
        }
    }
}

/** Typed provenance; deserialized records are untrusted until admitted by [RightsAuthority]. */
@Serializable
public sealed interface RightsProvenance {
    @Serializable
    @SerialName("host_policy")
    public data class HostPolicy(val policyId: String) : RightsProvenance {
        init { requireSafeIdentifier(policyId, "Host policy id") }
    }

    @Serializable
    @SerialName("provider_evidence")
    public data class ProviderEvidence(
        val providerId: String,
        val evidenceReference: String,
    ) : RightsProvenance {
        init {
            requireSafeIdentifier(providerId, "Rights provider id")
            requireSafeIdentifier(evidenceReference, "Provider evidence reference")
        }
    }
}

/**
 * A serialized grant is policy data only. It becomes effective only after a host authority admits
 * it and returns a non-serializable [VerifiedRightsGrant].
 */
@Serializable
public data class RightsGrant(
    val schemaVersion: Int,
    val grantId: RightsGrantRef,
    val scope: RightsScope,
    val provenance: RightsProvenance,
    val protectionScheme: ProtectionScheme,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long?,
    val allowedOperations: Set<ContentOperation>,
    val constraints: Set<RightsConstraint> = emptySet(),
    val providerEvidenceRef: String? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported rights grant schema version $schemaVersion"
        }
        grantId.validate()
        scope.validate()
        require(validFromEpochMillis >= 0) { "Rights valid-from time must be non-negative" }
        require(validUntilEpochMillis == null || validUntilEpochMillis >= validFromEpochMillis) {
            "Rights valid-until time must follow valid-from time"
        }
        // Empty is intentional: deny-by-default grants can carry only protection metadata.
        require(allowedOperations.size <= ContentOperation.entries.size) {
            "Rights operation set contains unsupported values"
        }
        constraints.forEach { constraint ->
            when (constraint) {
                is RightsConstraint.MaxOfflineBytes -> Unit
                is RightsConstraint.MaxTextChars -> Unit
                RightsConstraint.WatermarkRequired -> Unit
                is RightsConstraint.ForOperations -> Unit
            }
        }
        providerEvidenceRef?.let { requireSafeIdentifier(it, "Provider evidence reference") }
        when (val scheme = protectionScheme) {
            ProtectionScheme.None -> Unit
            is ProtectionScheme.Provider -> Unit
            is ProtectionScheme.Encrypted -> Unit
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Kotlin's read-only [Set] interface does not guarantee that the backing object is immutable.
 * Authority admission therefore copies policy collections into a private implementation that
 * cannot be cast back to [MutableSet].
 */
private class ImmutableSetSnapshot<E>(elements: Iterable<E>) : AbstractSet<E>() {
    private val values: List<E> = elements.distinct()

    override val size: Int
        get() = values.size

    override fun contains(element: E): Boolean = element in values

    override fun iterator(): Iterator<E> = object : Iterator<E> {
        private var index: Int = 0

        override fun hasNext(): Boolean = index < values.size

        override fun next(): E {
            if (!hasNext()) throw NoSuchElementException()
            return values[index++]
        }
    }
}

private fun <E> Iterable<E>.immutableSetSnapshot(): Set<E> = ImmutableSetSnapshot(this)

private fun RightsConstraint.immutableSnapshot(): RightsConstraint = when (this) {
    is RightsConstraint.MaxOfflineBytes,
    is RightsConstraint.MaxTextChars,
    RightsConstraint.WatermarkRequired,
    -> this
    is RightsConstraint.ForOperations -> copy(
        operations = operations.immutableSetSnapshot(),
        constraint = constraint.immutableSnapshot(),
    )
}

private fun RightsGrant.immutableSnapshot(): RightsGrant = copy(
    allowedOperations = allowedOperations.immutableSetSnapshot(),
    constraints = constraints.map(RightsConstraint::immutableSnapshot).immutableSetSnapshot(),
)

/** Typed operation context used to satisfy grant obligations before an operation is allowed. */
public data class RightsOperationContext(
    val offlineBytes: Long? = null,
    val textCharacters: Long? = null,
    val watermarkApplied: Boolean = false,
) {
    init {
        require(offlineBytes == null || offlineBytes >= 0) { "Offline byte context must be non-negative" }
        require(textCharacters == null || textCharacters >= 0) { "Text character context must be non-negative" }
    }
}

/** Untrusted extension/provider hint; it can only restrict host policy, never create a grant. */
@Serializable
public data class RightsHint(
    val requestedOperations: Set<ContentOperation> = emptySet(),
    val deniedOperations: Set<ContentOperation> = emptySet(),
    val constraints: Set<RightsConstraint> = emptySet(),
    val protectionScheme: ProtectionScheme = ProtectionScheme.None,
) {
    init { validate() }

    public fun validate(): Unit {
        require(requestedOperations.intersect(deniedOperations).isEmpty()) {
            "Rights hint cannot request and deny the same operation"
        }
        constraints.forEach { constraint ->
            when (constraint) {
                is RightsConstraint.MaxOfflineBytes,
                is RightsConstraint.MaxTextChars,
                RightsConstraint.WatermarkRequired,
                -> Unit
                is RightsConstraint.ForOperations -> Unit
            }
        }
    }
}

/**
 * A hint is evaluated only after the host grant has been resolved.  An empty request means that
 * the plugin did not request an additional operation restriction; it is not an allow-all grant.
 */
private fun RightsHint.allowsHostOperation(
    hostOperations: Set<ContentOperation>,
    operation: ContentOperation,
): Boolean {
    val requested = if (requestedOperations.isEmpty()) hostOperations else {
        hostOperations.intersect(requestedOperations)
    }
    return operation in requested && operation !in deniedOperations
}

/** A protection hint may require the host's exact scheme, but it cannot replace it. */
private fun RightsHint.protectionCompatibleWith(hostScheme: ProtectionScheme): Boolean =
    protectionScheme == ProtectionScheme.None || protectionScheme == hostScheme

private fun RightsConstraint.isApplicableTo(operation: ContentOperation): Boolean = when (this) {
    is RightsConstraint.MaxOfflineBytes -> operation == ContentOperation.OFFLINE_STORE
    is RightsConstraint.MaxTextChars -> operation in TEXT_CONSTRAINED_OPERATIONS
    RightsConstraint.WatermarkRequired -> operation in WATERMARK_CONSTRAINED_OPERATIONS
    is RightsConstraint.ForOperations -> operation in operations
}

private fun RightsConstraint.isSatisfiedFor(
    operation: ContentOperation,
    context: RightsOperationContext,
): Boolean {
    // Constraints are scoped restrictions.  A constraint for a different operation is inert and
    // must not accidentally turn an otherwise independently allowed operation into a denial.
    if (!isApplicableTo(operation)) return true
    val base = if (this is RightsConstraint.ForOperations) constraint else this
    return when (base) {
        is RightsConstraint.MaxOfflineBytes ->
            context.offlineBytes != null && context.offlineBytes <= base.bytes
        is RightsConstraint.MaxTextChars ->
            context.textCharacters != null && context.textCharacters <= base.chars
        RightsConstraint.WatermarkRequired -> context.watermarkApplied
        is RightsConstraint.ForOperations -> error("Nested rights constraints are invalid")
    }
}

private val TEXT_CONSTRAINED_OPERATIONS = setOf(
    ContentOperation.DISPLAY,
    ContentOperation.EXPORT,
    ContentOperation.COPY,
    ContentOperation.PRINT,
    ContentOperation.TTS,
    ContentOperation.SEARCH_INDEX,
    ContentOperation.ANNOTATE,
)

private val WATERMARK_CONSTRAINED_OPERATIONS = setOf(
    ContentOperation.DISPLAY,
    ContentOperation.EXPORT,
    ContentOperation.COPY,
    ContentOperation.PRINT,
    ContentOperation.TTS,
    ContentOperation.ANNOTATE,
)

/** Non-serializable admission token. Its private constructor prevents a JSON field from granting trust. */
public class VerifiedRightsGrant private constructor(
    public val grant: RightsGrant,
    public val admittedAtEpochMillis: Long,
    internal val authorityNonce: Any,
    public val protectionAuthorization: ProtectionAuthorization? = null,
    private val admissionStillValid: () -> Boolean,
) {
    init {
        require(admittedAtEpochMillis >= 0) { "Admission timestamp must be non-negative" }
        grant.validate()
    }

    public fun allows(
        operation: ContentOperation,
        nowEpochMillis: Long,
        context: RightsOperationContext = RightsOperationContext(),
        hint: RightsHint? = null,
    ): Boolean {
        // A resolved token is not a lease. Revocation, replacement, and provider-policy changes
        // must take effect even if a caller retained this object after resolve().
        if (!admissionStillValid()) return false
        if (nowEpochMillis < grant.validFromEpochMillis) return false
        if (grant.validUntilEpochMillis != null && nowEpochMillis >= grant.validUntilEpochMillis) return false
        if (hint != null && !hint.allowsHostOperation(grant.allowedOperations, operation)) return false
        if (operation !in grant.allowedOperations) return false
        if (hint != null && !hint.protectionCompatibleWith(grant.protectionScheme)) return false
        val protected = when (val scheme = grant.protectionScheme) {
            ProtectionScheme.None -> true
            is ProtectionScheme.Provider,
            is ProtectionScheme.Encrypted,
            -> protectionAuthorization?.matches(
                scope = grant.scope,
                scheme = scheme,
                operation = operation,
                nowEpochMillis = nowEpochMillis,
                expectedAuthorityNonce = authorityNonce,
            ) == true
        }
        if (!protected) return false
        val constraints = if (hint == null) {
            grant.constraints
        } else {
            grant.constraints + hint.constraints
        }
        return constraints.all { constraint -> constraint.isSatisfiedFor(operation, context) }
    }

    /** Compatibility overload for callers that place the untrusted hint before the context. */
    public fun allows(
        operation: ContentOperation,
        nowEpochMillis: Long,
        hint: RightsHint,
        context: RightsOperationContext = RightsOperationContext(),
    ): Boolean = allows(operation, nowEpochMillis, context, hint)

    internal companion object {
        fun issue(
            grant: RightsGrant,
            nowEpochMillis: Long,
            nonce: Any,
            authorization: ProtectionAuthorization? = null,
            admissionStillValid: () -> Boolean,
        ): VerifiedRightsGrant = VerifiedRightsGrant(
            grant.immutableSnapshot(),
            nowEpochMillis,
            nonce,
            authorization,
            admissionStillValid,
        )
    }
}

/** Non-serializable provider evidence bound to exact grant scope, scheme, operation and lease. */
public class ProtectionAuthorization private constructor(
    public val providerId: String,
    public val schemeVersion: Int,
    public val scope: RightsScope,
    public val operation: ContentOperation,
    public val leaseExpiresAtEpochMillis: Long,
    internal val nonce: Any,
) {
    init {
        requireSafeIdentifier(providerId, "Protection authorization provider id")
        require(schemeVersion > 0) { "Protection authorization scheme version must be positive" }
        scope.validate()
        require(leaseExpiresAtEpochMillis >= 0) { "Protection authorization expiry must be non-negative" }
    }

    internal fun matches(
        scope: RightsScope,
        scheme: ProtectionScheme,
        operation: ContentOperation,
        nowEpochMillis: Long,
        expectedAuthorityNonce: Any,
    ): Boolean {
        val expected = when (scheme) {
            is ProtectionScheme.Provider -> scheme.providerId to scheme.schemeVersion
            is ProtectionScheme.Encrypted -> scheme.providerId to scheme.schemeVersion
            ProtectionScheme.None -> return false
        }
        return this.scope == scope && this.providerId == expected.first &&
            this.schemeVersion == expected.second && this.operation == operation &&
            nowEpochMillis < leaseExpiresAtEpochMillis && nonce === expectedAuthorityNonce
    }

    internal companion object {
        fun issue(
            providerId: String,
            schemeVersion: Int,
            scope: RightsScope,
            operation: ContentOperation,
            leaseExpiresAtEpochMillis: Long,
            nonce: Any,
        ): ProtectionAuthorization = ProtectionAuthorization(
            providerId,
            schemeVersion,
            scope,
            operation,
            leaseExpiresAtEpochMillis,
            nonce,
        )
    }
}

/** Host trust boundary for resolving persisted grant references and current revocation state. */
public interface RightsAuthority {
    public fun resolve(
        reference: RightsGrantRef,
        scope: RightsScope,
        nowEpochMillis: Long,
        authorization: ProtectionAuthorization? = null,
    ): VerifiedRightsGrant?
}

/** In-memory host authority for common tests; production uses the secure policy/ledger store. */
public class InMemoryRightsAuthority : RightsAuthority {
    private data class AdmissionRecord(
        val grant: RightsGrant,
        val marker: Any = Any(),
    )

    private val records = LinkedHashMap<String, AdmissionRecord>()
    private val revoked = HashSet<String>()
    private val authorityNonce = Any()
    private val registeredProviders = LinkedHashMap<String, MutableSet<Int>>()
    private var providerPolicyMarker: Any = Any()

    /** Register a provider scheme before protected grants can be admitted. */
    public fun registerProtectionProvider(providerId: String, schemeVersion: Int = 1) {
        requireSafeIdentifier(providerId, "Protection provider id")
        require(schemeVersion > 0) { "Protection scheme version must be positive" }
        if (registeredProviders.getOrPut(providerId) { LinkedHashSet() }.add(schemeVersion)) {
            providerPolicyMarker = Any()
        }
    }

    /** Convenience overload for a provider implementation already owned by the host. */
    public fun registerProtectionProvider(provider: ProtectionProvider, schemeVersion: Int = 1): Unit =
        registerProtectionProvider(provider.providerId, schemeVersion)

    /** Remove a provider scheme; existing grants fail closed on the next resolution. */
    public fun unregisterProtectionProvider(providerId: String, schemeVersion: Int = 1) {
        registeredProviders[providerId]?.let { versions ->
            val changed = versions.remove(schemeVersion)
            if (versions.isEmpty()) registeredProviders.remove(providerId)
            if (changed) providerPolicyMarker = Any()
        }
    }

    /**
     * Issue provider evidence through this authority's private nonce.  Callers cannot construct
     * an authorization that this authority will accept by copying its scalar fields.
     */
    public fun issueProtectionAuthorization(
        scheme: ProtectionScheme,
        scope: RightsScope,
        operation: ContentOperation,
        leaseExpiresAtEpochMillis: Long,
    ): ProtectionAuthorization? {
        val (providerId, schemeVersion) = when (scheme) {
            is ProtectionScheme.Provider -> scheme.providerId to scheme.schemeVersion
            is ProtectionScheme.Encrypted -> scheme.providerId to scheme.schemeVersion
            ProtectionScheme.None -> return null
        }
        if (!isRegistered(providerId, schemeVersion)) return null
        return ProtectionAuthorization.issue(
            providerId = providerId,
            schemeVersion = schemeVersion,
            scope = scope,
            operation = operation,
            leaseExpiresAtEpochMillis = leaseExpiresAtEpochMillis,
            nonce = authorityNonce,
        )
    }

    public fun admit(grant: RightsGrant) {
        val snapshot = grant.immutableSnapshot()
        snapshot.validate()
        records[snapshot.grantId.value] = AdmissionRecord(snapshot)
        revoked.remove(snapshot.grantId.value)
    }

    public fun revoke(reference: RightsGrantRef) {
        reference.validate()
        revoked += reference.value
    }

    override fun resolve(
        reference: RightsGrantRef,
        scope: RightsScope,
        nowEpochMillis: Long,
        authorization: ProtectionAuthorization?,
    ): VerifiedRightsGrant? {
        if (nowEpochMillis < 0 || reference.value in revoked) return null
        val record = records[reference.value] ?: return null
        val grant = record.grant
        if (grant.scope != scope) return null
        if (!isSupportedScheme(grant.protectionScheme)) return null
        if (grant.protectionScheme != ProtectionScheme.None &&
            (authorization == null || !authorization.matches(
                scope = grant.scope,
                scheme = grant.protectionScheme,
                operation = authorization.operation,
                nowEpochMillis = nowEpochMillis,
                expectedAuthorityNonce = authorityNonce,
            ))
        ) return null
        val resolvedProviderPolicyMarker = providerPolicyMarker
        return VerifiedRightsGrant.issue(
            grant = grant,
            nowEpochMillis = nowEpochMillis,
            nonce = authorityNonce,
            authorization = authorization,
            admissionStillValid = {
                records[reference.value]?.marker === record.marker &&
                    reference.value !in revoked &&
                    isSupportedScheme(grant.protectionScheme) &&
                    (grant.protectionScheme == ProtectionScheme.None ||
                        providerPolicyMarker === resolvedProviderPolicyMarker)
            },
        )
    }

    private fun isSupportedScheme(scheme: ProtectionScheme): Boolean = when (scheme) {
        ProtectionScheme.None -> true
        is ProtectionScheme.Provider -> isRegistered(scheme.providerId, scheme.schemeVersion)
        is ProtectionScheme.Encrypted -> isRegistered(scheme.providerId, scheme.schemeVersion)
    }

    private fun isRegistered(providerId: String, schemeVersion: Int): Boolean =
        registeredProviders[providerId]?.contains(schemeVersion) == true
}

/** Central host evaluator. Missing/unverified/expired/unsupported grants always deny. */
public object ContentRightsEvaluator {
    public fun decide(
        reference: RightsGrantRef?,
        scope: RightsScope,
        operation: ContentOperation,
        nowEpochMillis: Long,
        authority: RightsAuthority,
        authorization: ProtectionAuthorization? = null,
        context: RightsOperationContext = RightsOperationContext(),
        hint: RightsHint? = null,
    ): RightsDecision {
        if (reference == null) return RightsDecision.DENY
        val verified = authority.resolve(reference, scope, nowEpochMillis, authorization)
            ?: return RightsDecision.DENY
        return if (verified.allows(operation, nowEpochMillis, context, hint)) RightsDecision.ALLOW else RightsDecision.DENY
    }

    public fun allows(
        reference: RightsGrantRef?,
        scope: RightsScope,
        operation: ContentOperation,
        nowEpochMillis: Long,
        authority: RightsAuthority,
        authorization: ProtectionAuthorization? = null,
        context: RightsOperationContext = RightsOperationContext(),
        hint: RightsHint? = null,
    ): Boolean = decide(reference, scope, operation, nowEpochMillis, authority, authorization, context, hint) == RightsDecision.ALLOW
}

/** Provider lifecycle boundary; implementations never expose key material through this API. */
public interface ProtectionProvider {
    public val providerId: String
    public suspend fun inspect(scheme: ProtectionScheme): ProtectionStatus
    public suspend fun acquire(scheme: ProtectionScheme, scope: RightsScope): ProtectionLease
    public suspend fun open(lease: ProtectionLease, operation: ContentOperation): ProtectionSession?
    public suspend fun renew(lease: ProtectionLease): ProtectionLease
    public suspend fun expire(lease: ProtectionLease)
    public suspend fun returnOrRevoke(lease: ProtectionLease)
}

@Serializable
public enum class ProtectionStatus {
    SUPPORTED,
    UNSUPPORTED,
    EXPIRED,
    REVOKED,
}

public data class ProtectionLease(
    val providerId: String,
    val leaseReference: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        requireSafeIdentifier(providerId, "Protection lease provider id")
        requireSafeIdentifier(leaseReference, "Protection lease reference")
        require(expiresAtEpochMillis >= 0) { "Protection lease expiration must be non-negative" }
    }

    override fun toString(): String =
        "ProtectionLease(providerId=$providerId, leaseReference=<redacted>, " +
            "expiresAtEpochMillis=$expiresAtEpochMillis)"
}

/** Opaque non-serializable session token returned by a protection provider. */
public interface ProtectionSession {
    public val providerId: String
    public val sessionReference: String
    public val authorization: ProtectionAuthorization
    public suspend fun readChunk(maxBytes: Int): ByteArray?
    public fun close()
}

private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private const val MAX_IDENTIFIER_LENGTH = 512

private fun requireUuid(value: String, label: String) {
    require(UUID_PATTERN.matches(value) && value != NIL_UUID) {
        "$label must be a lowercase non-NIL UUID"
    }
}

private fun requireSafeIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
}
