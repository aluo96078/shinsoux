package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Wire-level signed repository documents. Each type has an independent replay counter. */
public enum class RepositorySignedDocumentType(public val wireValue: String) {
    REPOSITORY("repository-meta"),
    INDEX("repository-index"),
    LEGACY_INDEX("repository-legacy-index"),
    SIDECAR("plugin-sidecar"),
}

/** A trust root configured by the application, never learned from the downloaded repository. */
public data class RepositoryTrustRoot(
    val publicKeyBase64Url: String,
    val fingerprint: String,
)

public sealed interface RepositoryTrustDecision {
    /** Require an Ed25519 envelope whose key exactly matches this locally configured root. */
    public data class RequirePinnedSignature(val root: RepositoryTrustRoot) : RepositoryTrustDecision

    /**
     * Explicit escape hatch for local development and migration only. This provides transport and
     * digest integrity, not author authenticity, and must not be described as a signed repository.
     */
    public data object UnsignedDeveloperCompatibility : RepositoryTrustDecision
}

/** Selects trust without consulting attacker-controlled repository metadata. */
public fun interface RepositoryTrustPolicy {
    public suspend fun decisionFor(normalizedBaseUrl: String): RepositoryTrustDecision
}

/** Exact-URL trust roots. An unlisted URL fails closed. */
public class PinnedRepositoryTrustPolicy(
    roots: Map<String, RepositoryTrustRoot>,
) : RepositoryTrustPolicy {
    private val roots: Map<String, RepositoryTrustRoot> = roots.toMap()

    override suspend fun decisionFor(normalizedBaseUrl: String): RepositoryTrustDecision =
        roots[normalizedBaseUrl]?.let(RepositoryTrustDecision::RequirePinnedSignature)
            ?: throw RepositoryTrustException.UntrustedRepository(normalizedBaseUrl)
}

public object RepositoryTrustPolicies {
    /** Secure default: no executable repository is trusted until a key has been pinned. */
    public val REQUIRE_CONFIGURED_PIN: RepositoryTrustPolicy = PinnedRepositoryTrustPolicy(emptyMap())

    /**
     * Compatibility policy for existing unsigned fixtures and explicitly selected developer
     * repositories. Production composition must instead supply [PinnedRepositoryTrustPolicy].
     */
    public val UNSIGNED_DEVELOPER_COMPATIBILITY: RepositoryTrustPolicy = RepositoryTrustPolicy {
        RepositoryTrustDecision.UnsignedDeveloperCompatibility
    }
}

/**
 * Canonical base URL of the official repository while its published documents are still unsigned.
 * Keep this as a base URL (not the reviewed `index.json` location) because repository clients
 * append and independently validate `repo.json`, `index.json`, sidecars, and artifacts.
 */
public const val OFFICIAL_SHINSOU_REPOSITORY_BASE_URL: String =
    "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master"

/** User-facing canonical index location; repository trust is keyed by its normalized base URL. */
public const val OFFICIAL_SHINSOU_REPOSITORY_INDEX_URL: String =
    "$OFFICIAL_SHINSOU_REPOSITORY_BASE_URL/index.json"

/**
 * Temporary, fail-closed compatibility for the one canonical official repository base URL.
 *
 * The input contract is the repository client's normalized base URL. Deliberately use exact
 * equality anyway: alternate schemes, authorities, ports, paths, encodings, queries, fragments,
 * and lookalikes must not inherit this unsigned exception.
 */
public fun isCanonicalOfficialUnsignedRepository(normalizedBaseUrl: String): Boolean =
    normalizedBaseUrl == OFFICIAL_SHINSOU_REPOSITORY_BASE_URL

/** Durable configuration boundary for an out-of-band or user-confirmed author key. */
public interface RepositoryTrustRootStore {
    public suspend fun get(normalizedBaseUrl: String): RepositoryTrustRoot?
    /** First pin is immutable; a different key requires [replaceAfterExplicitApproval]. */
    public suspend fun put(normalizedBaseUrl: String, root: RepositoryTrustRoot)
    public suspend fun replaceAfterExplicitApproval(normalizedBaseUrl: String, root: RepositoryTrustRoot)
    public suspend fun remove(normalizedBaseUrl: String)
}

public class KeyValueRepositoryTrustRootStore(
    private val keyValueStore: PluginKeyValueStore,
    private val json: kotlinx.serialization.json.Json = PluginJson,
) : RepositoryTrustRootStore {
    private val mutex = Mutex()

    override suspend fun get(normalizedBaseUrl: String): RepositoryTrustRoot? = mutex.withLock {
        read(normalizedBaseUrl)?.also { RepositoryTrustRootValidation.requireValid(it) }
    }

    override suspend fun put(normalizedBaseUrl: String, root: RepositoryTrustRoot): Unit = mutex.withLock {
        RepositoryTrustRootValidation.requireValid(root)
        val current = read(normalizedBaseUrl)
        if (current != null && current != root) throw RepositoryTrustException.KeySubstitution()
        persist(normalizedBaseUrl, root)
    }

    override suspend fun replaceAfterExplicitApproval(
        normalizedBaseUrl: String,
        root: RepositoryTrustRoot,
    ): Unit = mutex.withLock {
        RepositoryTrustRootValidation.requireValid(root)
        persist(normalizedBaseUrl, root)
    }

    private suspend fun persist(normalizedBaseUrl: String, root: RepositoryTrustRoot) {
        val encoded = json.encodeToString(PersistedTrustRoot(root.publicKeyBase64Url, root.fingerprint))
        val storageKey = key(normalizedBaseUrl)
        keyValueStore.putString(storageKey, encoded)
        check(keyValueStore.getString(storageKey) == encoded) { "Could not verify repository trust-root write" }
    }

    private suspend fun read(normalizedBaseUrl: String): RepositoryTrustRoot? =
        keyValueStore.getString(key(normalizedBaseUrl))?.let { encoded ->
            if (pluginUtf8ByteCountAtMost(encoded, MAX_REPOSITORY_TRUST_ROOT_BYTES) == null) {
                throw RepositoryTrustException.Malformed("Persisted repository trust root exceeds its size limit")
            }
            val persisted = runCatching { json.decodeFromString<PersistedTrustRoot>(encoded) }
                .getOrElse { throw RepositoryTrustException.Malformed("Invalid persisted repository trust root", it) }
            RepositoryTrustRoot(persisted.publicKeyBase64Url, persisted.fingerprint)
        }

    override suspend fun remove(normalizedBaseUrl: String): Unit = mutex.withLock {
        val storageKey = key(normalizedBaseUrl)
        keyValueStore.remove(storageKey)
        check(keyValueStore.getString(storageKey) == null) { "Could not verify repository trust-root removal" }
    }

    private fun key(normalizedBaseUrl: String): String =
        "plugin.repository.trust-root.v1.${Sha256.hex(normalizedBaseUrl.encodeToByteArray())}"

    @Serializable
    private data class PersistedTrustRoot(val publicKeyBase64Url: String, val fingerprint: String)

    private companion object {
        const val MAX_REPOSITORY_TRUST_ROOT_BYTES: Int = 16 * 1_024
    }
}

/**
 * Production policy backed by durable pins. Unsigned migration/developer compatibility requires
 * supplying a separate, clearly named predicate; the default constructor rejects every unpinned
 * URL. A configured pin always takes precedence so a compatibility URL returns to authenticated
 * verification as soon as the host provisions its trust root.
 */
public class ConfiguredRepositoryTrustPolicy(
    private val roots: RepositoryTrustRootStore,
    private val allowUnsignedDeveloperCompatibility: (String) -> Boolean = { false },
) : RepositoryTrustPolicy {
    override suspend fun decisionFor(normalizedBaseUrl: String): RepositoryTrustDecision =
        roots.get(normalizedBaseUrl)?.let(RepositoryTrustDecision::RequirePinnedSignature)
            ?: if (allowUnsignedDeveloperCompatibility(normalizedBaseUrl)) {
                RepositoryTrustDecision.UnsignedDeveloperCompatibility
            } else {
                throw RepositoryTrustException.UntrustedRepository(normalizedBaseUrl)
            }
}

/** Conservative predicate intended only for an explicitly enabled local development workflow. */
public fun isLocalUnsignedDeveloperRepository(normalizedBaseUrl: String): Boolean {
    val authority = normalizedBaseUrl.substringAfter("://", "").substringBefore('/').substringBeforeLast('@')
    val host = if (authority.startsWith('[')) authority.substringAfter('[').substringBefore(']')
    else authority.substringBefore(':')
    val normalized = host.lowercase()
    if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".local")) return true
    val parts = normalized.split('.').map(String::toIntOrNull)
    if (parts.size != 4 || parts.any { it == null || it !in 0..255 }) return false
    val first = parts[0]!!
    val second = parts[1]!!
    return first == 10 || first == 127 || first == 169 && second == 254 ||
        first == 172 && second in 16..31 || first == 192 && second == 168
}

public sealed class RepositoryTrustException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause) {
    public class UntrustedRepository(baseUrl: String) :
        RepositoryTrustException("Repository has no pinned author trust root: $baseUrl")

    public class SignatureRequired(type: RepositorySignedDocumentType) :
        RepositoryTrustException("Pinned Ed25519 signature required for ${type.wireValue}")

    public class Malformed(message: String, cause: Throwable? = null) :
        RepositoryTrustException(message, cause)

    public class KeySubstitution : RepositoryTrustException("Repository signing key does not match its pinned trust root")
    public class InvalidSignature : RepositoryTrustException("Invalid repository author signature")
    public class UnauthenticatedArtifact(packageId: String) :
        RepositoryTrustException("Plugin '$packageId' was not selected from the current authenticated repository index")
    public class Replay(type: RepositorySignedDocumentType, received: Long, highest: Long) :
        RepositoryTrustException("Repository ${type.wireValue} sequence $received is below admitted sequence $highest")

    public class Equivocation(label: String, sequence: Long) :
        RepositoryTrustException("Repository $label reused sequence/version $sequence for different authenticated content")

    public class Downgrade(packageId: String, received: Int, highest: Int) :
        RepositoryTrustException("Plugin '$packageId' versionCode $received is below admitted versionCode $highest")

    public class DurableSecurityStateRequired : RepositoryTrustException(
        "Pinned repository trust requires a durable replay and downgrade security-state store",
    )
}

/** Durable replay and package rollback boundary. */
public interface RepositorySecurityStateStore {
    /**
     * True only when writes survive a process restart. Custom implementations default to false,
     * so pinned trust requires the host to explicitly attest its persistence semantics.
     */
    public val isDurable: Boolean get() = false

    /** Checks without advancing the signed-document watermark. */
    public suspend fun requireDocumentNotReplayed(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    )

    /** Atomically admits a signed document sequence and rejects replay/equivocation. */
    public suspend fun admitDocument(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    )

    /** Checks without advancing the package watermark. */
    public suspend fun requireArtifactNotDowngraded(
        normalizedBaseUrl: String,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    )

    /** Advances the package watermark only after the artifact has been fully downloaded and verified. */
    public suspend fun admitArtifact(
        normalizedBaseUrl: String,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    )

    /**
     * Secure constructor default. It is deliberately not a no-op: a pinned repository must
     * replace this sentinel with a durable implementation before any trusted network request.
     * Explicit unsigned developer compatibility may operate without consulting it.
     */
    public data object RequireDurableState : RepositorySecurityStateStore {
        override suspend fun requireDocumentNotReplayed(
            normalizedBaseUrl: String,
            type: RepositorySignedDocumentType,
            documentId: String,
            sequence: Long,
            authenticatedPayloadSha256: String,
        ): Nothing = throw RepositoryTrustException.DurableSecurityStateRequired()

        override suspend fun admitDocument(
            normalizedBaseUrl: String,
            type: RepositorySignedDocumentType,
            documentId: String,
            sequence: Long,
            authenticatedPayloadSha256: String,
        ): Nothing = throw RepositoryTrustException.DurableSecurityStateRequired()

        override suspend fun requireArtifactNotDowngraded(
            normalizedBaseUrl: String,
            packageId: String,
            versionCode: Int,
            artifactSha256: String,
        ): Nothing = throw RepositoryTrustException.DurableSecurityStateRequired()

        override suspend fun admitArtifact(
            normalizedBaseUrl: String,
            packageId: String,
            versionCode: Int,
            artifactSha256: String,
        ): Nothing = throw RepositoryTrustException.DurableSecurityStateRequired()
    }

    /**
     * Explicit no-op for unsigned local/developer repositories only. [ExtensionRepositoryClient]
     * rejects this object whenever the selected trust decision requires a pinned signature.
     */
    public data object Disabled : RepositorySecurityStateStore {
        override suspend fun requireDocumentNotReplayed(
            normalizedBaseUrl: String,
            type: RepositorySignedDocumentType,
            documentId: String,
            sequence: Long,
            authenticatedPayloadSha256: String,
        ): Unit = Unit

        override suspend fun admitDocument(
            normalizedBaseUrl: String,
            type: RepositorySignedDocumentType,
            documentId: String,
            sequence: Long,
            authenticatedPayloadSha256: String,
        ): Unit = Unit

        override suspend fun requireArtifactNotDowngraded(
            normalizedBaseUrl: String,
            packageId: String,
            versionCode: Int,
            artifactSha256: String,
        ): Unit = Unit

        override suspend fun admitArtifact(
            normalizedBaseUrl: String,
            packageId: String,
            versionCode: Int,
            artifactSha256: String,
        ): Unit = Unit
    }
}

/**
 * Enforces the composition invariant that authenticated repository state survives process
 * restarts. Custom host implementations are trusted boundaries; the two built-in non-durable
 * sentinels are never accepted for a pinned decision.
 */
internal fun RepositorySecurityStateStore.requireDurableFor(decision: RepositoryTrustDecision) {
    if (decision is RepositoryTrustDecision.RequirePinnedSignature && !isDurable) {
        throw RepositoryTrustException.DurableSecurityStateRequired()
    }
}

/** Whether this configured store should track optional unsigned developer traffic. */
internal fun RepositorySecurityStateStore.isConfigured(): Boolean =
    this !== RepositorySecurityStateStore.RequireDurableState &&
        this !== RepositorySecurityStateStore.Disabled

/** Key-value implementation shared by Android, iOS, Desktop, and future encrypted stores. */
public class KeyValueRepositorySecurityStateStore(
    private val keyValueStore: PluginKeyValueStore,
    private val json: kotlinx.serialization.json.Json = PluginJson,
) : RepositorySecurityStateStore {
    private val mutex = Mutex()
    override val isDurable: Boolean = true

    override suspend fun requireDocumentNotReplayed(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    ): Unit = mutex.withLock {
        validateDocumentWatermark(
            read(documentKey(normalizedBaseUrl, type, documentId)),
            type,
            sequence,
            authenticatedPayloadSha256,
        )
    }

    override suspend fun admitDocument(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    ): Unit = mutex.withLock {
        require(sequence >= 0) { "Repository sequence must be non-negative" }
        require(documentId.isNotBlank() && documentId.length <= 512) { "Invalid repository document id" }
        val key = documentKey(normalizedBaseUrl, type, documentId)
        val current = read<DocumentWatermark>(key)
        validateDocumentWatermark(current, type, sequence, authenticatedPayloadSha256)
        if (current?.sequence == sequence) return@withLock
        writeVerified(key, DocumentWatermark(sequence, authenticatedPayloadSha256))
    }

    private fun validateDocumentWatermark(
        current: DocumentWatermark?,
        type: RepositorySignedDocumentType,
        sequence: Long,
        authenticatedPayloadSha256: String,
    ) {
        require(sequence >= 0) { "Repository sequence must be non-negative" }
        require(SHA256_HEX.matches(authenticatedPayloadSha256)) { "Invalid payload SHA-256" }
        if (current == null) return
        if (sequence < current.sequence) throw RepositoryTrustException.Replay(type, sequence, current.sequence)
        if (sequence == current.sequence && authenticatedPayloadSha256 != current.payloadSha256) {
            throw RepositoryTrustException.Equivocation(type.wireValue, sequence)
        }
    }

    override suspend fun requireArtifactNotDowngraded(
        normalizedBaseUrl: String,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    ): Unit = mutex.withLock {
        requireArtifactAllowed(read(artifactKey(normalizedBaseUrl, packageId)), packageId, versionCode, artifactSha256)
    }

    override suspend fun admitArtifact(
        normalizedBaseUrl: String,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    ): Unit = mutex.withLock {
        val key = artifactKey(normalizedBaseUrl, packageId)
        val current = read<ArtifactWatermark>(key)
        requireArtifactAllowed(current, packageId, versionCode, artifactSha256)
        if (current?.versionCode == versionCode) return@withLock
        writeVerified(key, ArtifactWatermark(versionCode, artifactSha256))
    }

    private fun requireArtifactAllowed(
        current: ArtifactWatermark?,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    ) {
        require(versionCode >= 0) { "Plugin versionCode must be non-negative" }
        require(SHA256_HEX.matches(artifactSha256)) { "Invalid artifact SHA-256" }
        if (current == null) return
        if (versionCode < current.versionCode) {
            throw RepositoryTrustException.Downgrade(packageId, versionCode, current.versionCode)
        }
        if (versionCode == current.versionCode && artifactSha256 != current.artifactSha256) {
            throw RepositoryTrustException.Equivocation(packageId, versionCode.toLong())
        }
    }

    private suspend inline fun <reified T> read(key: String): T? = keyValueStore.getString(key)?.let { encoded ->
        if (pluginUtf8ByteCountAtMost(encoded, MAX_REPOSITORY_SECURITY_STATE_BYTES) == null) {
            throw RepositoryTrustException.Malformed("Persisted repository security state exceeds its size limit")
        }
        runCatching { json.decodeFromString<T>(encoded) }
            .getOrElse { throw RepositoryTrustException.Malformed("Invalid persisted repository security state", it) }
    }

    private suspend inline fun <reified T> writeVerified(key: String, value: T) {
        val encoded = json.encodeToString(value)
        keyValueStore.putString(key, encoded)
        check(keyValueStore.getString(key) == encoded) { "Could not verify repository security state write" }
    }

    private fun documentKey(baseUrl: String, type: RepositorySignedDocumentType, documentId: String): String =
        "$STATE_PREFIX.document.${stableKey(baseUrl)}.${type.wireValue}.${stableKey(documentId)}"

    private fun artifactKey(baseUrl: String, packageId: String): String =
        "$STATE_PREFIX.artifact.${stableKey(baseUrl)}.${stableKey(packageId)}"

    private fun stableKey(value: String): String = Sha256.hex(value.encodeToByteArray())

    @Serializable
    private data class DocumentWatermark(val sequence: Long, val payloadSha256: String)

    @Serializable
    private data class ArtifactWatermark(val versionCode: Int, val artifactSha256: String)

    private companion object {
        const val STATE_PREFIX = "plugin.repository.security.v1"
        const val MAX_REPOSITORY_SECURITY_STATE_BYTES: Int = 4 * 1_024
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

/** Verifies signed JSON envelopes before any payload is decoded as repository metadata. */
internal class RepositorySignedDocumentVerifier(
    private val policy: RepositoryTrustPolicy,
    private val stateStore: RepositorySecurityStateStore,
) {
    suspend fun verifiedPayload(
        normalizedBaseUrl: String,
        expectedType: RepositorySignedDocumentType,
        expectedDocumentId: String,
        downloadedRoot: JsonElement,
        decision: RepositoryTrustDecision? = null,
    ): VerifiedRepositoryPayload {
        val selectedDecision = decision ?: policy.decisionFor(normalizedBaseUrl)
        stateStore.requireDurableFor(selectedDecision)
        val envelope = downloadedRoot as? JsonObject
        val isEnvelope = envelope?.get("format")?.jsonPrimitive?.contentOrNull == ENVELOPE_FORMAT
        if (!isEnvelope) {
            if (selectedDecision is RepositoryTrustDecision.UnsignedDeveloperCompatibility) {
                return VerifiedRepositoryPayload(downloadedRoot, null)
            }
            throw RepositoryTrustException.SignatureRequired(expectedType)
        }
        if (selectedDecision !is RepositoryTrustDecision.RequirePinnedSignature) {
            // A signed envelope is still verified in compatibility mode only when a root was
            // selected. Silently trusting its self-asserted key would be weaker than unsigned mode.
            throw RepositoryTrustException.UntrustedRepository(normalizedBaseUrl)
        }
        return verifyEnvelope(
            normalizedBaseUrl,
            expectedType,
            expectedDocumentId,
            requireNotNull(envelope),
            selectedDecision.root,
        )
    }

    private suspend fun verifyEnvelope(
        normalizedBaseUrl: String,
        expectedType: RepositorySignedDocumentType,
        expectedDocumentId: String,
        envelope: JsonObject,
        pinned: RepositoryTrustRoot,
    ): VerifiedRepositoryPayload {
        try {
            SodiumSyncPrimitives.initialize()
            require(envelope.keys == ENVELOPE_FIELDS) { "Unknown or missing signed-envelope field" }
            require(envelope.requiredString("format") == ENVELOPE_FORMAT)
            require(envelope.requiredString("algorithm") == "Ed25519")
            require(envelope.requiredString("payloadType") == expectedType.wireValue)
            val sequence = envelope["sequence"]?.jsonPrimitive?.longOrNull
                ?: error("Missing signed-envelope sequence")
            require(sequence >= 0) { "Repository sequence must be non-negative" }
            val wirePublicKey = canonicalBase64Url(envelope.requiredString("publicKey"), PUBLIC_KEY_BYTES)
            val wireSignature = canonicalBase64Url(envelope.requiredString("signature"), SIGNATURE_BYTES)
            val wireFingerprint = envelope.requiredString("keyFingerprint")
            val pinnedPublicKey = canonicalBase64Url(pinned.publicKeyBase64Url, PUBLIC_KEY_BYTES)

            val calculatedFingerprint = fingerprint(pinnedPublicKey)
            if (pinned.fingerprint != calculatedFingerprint || wireFingerprint != calculatedFingerprint ||
                !SodiumSyncPrimitives.constantTimeEquals(wirePublicKey, pinnedPublicKey)
            ) {
                throw RepositoryTrustException.KeySubstitution()
            }
            val payload = envelope.getValue("payload")
            if (expectedType == RepositorySignedDocumentType.REPOSITORY) {
                val advertised = (payload as? JsonObject)
                    ?.get("meta")
                    ?.let { it as? JsonObject }
                    ?.get("signingKeyFingerprint")
                    ?.jsonPrimitive
                    ?.contentOrNull
                require(advertised == wireFingerprint) {
                    "Repository metadata signing fingerprint does not match the authenticated key"
                }
            }
            val message = signingMessage(
                normalizedBaseUrl,
                expectedType,
                expectedDocumentId,
                sequence,
                wireFingerprint,
                payload,
            )
            if (!SodiumSyncPrimitives.verifyEd25519(message, wireSignature, pinnedPublicKey)) {
                throw RepositoryTrustException.InvalidSignature()
            }
            val payloadSha = Sha256.hex(canonicalSyncJson(payload).encodeToByteArray())
            val admission = RepositoryDocumentAdmission(
                normalizedBaseUrl,
                expectedType,
                expectedDocumentId,
                sequence,
                payloadSha,
                wireFingerprint,
            )
            stateStore.requireDocumentNotReplayed(
                admission.normalizedBaseUrl,
                admission.type,
                admission.documentId,
                admission.sequence,
                admission.payloadSha256,
            )
            return VerifiedRepositoryPayload(payload, admission)
        } catch (error: RepositoryTrustException) {
            throw error
        } catch (error: Throwable) {
            throw RepositoryTrustException.Malformed("Malformed signed repository envelope", error)
        }
    }

    suspend fun commit(admission: RepositoryDocumentAdmission?) {
        if (admission == null) return
        stateStore.admitDocument(
            admission.normalizedBaseUrl,
            admission.type,
            admission.documentId,
            admission.sequence,
            admission.payloadSha256,
        )
    }

    private fun canonicalBase64Url(value: String, expectedBytes: Int): ByteArray =
        RepositoryTrustRootValidation.canonicalBase64Url(value, expectedBytes)

    private fun fingerprint(publicKey: ByteArray): String = RepositoryTrustRootValidation.fingerprint(publicKey)

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: error("Missing '$key'")

    companion object {
        const val ENVELOPE_FORMAT: String = "shinsou-signed-envelope-v1"
        val SIGNATURE_DOMAIN: ByteArray = "dev.shinsou.repository.signature.v1\u0000".encodeToByteArray()
        private const val PUBLIC_KEY_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private val ENVELOPE_FIELDS = setOf(
            "format",
            "algorithm",
            "keyFingerprint",
            "publicKey",
            "payloadType",
            "sequence",
            "payload",
            "signature",
        )

        /** Canonical bytes to sign in the repository publication pipeline. */
        public suspend fun signingMessage(
            normalizedBaseUrl: String,
            type: RepositorySignedDocumentType,
            documentId: String,
            sequence: Long,
            keyFingerprint: String,
            payload: JsonElement,
        ): ByteArray {
            require(sequence >= 0) { "Repository sequence must be non-negative" }
            require(documentId.isNotBlank() && documentId.length <= 512) { "Invalid repository document id" }
            val statement = JsonObject(
                linkedMapOf(
                    "algorithm" to JsonPrimitive("Ed25519"),
                    "documentId" to JsonPrimitive(documentId),
                    "format" to JsonPrimitive(ENVELOPE_FORMAT),
                    "keyFingerprint" to JsonPrimitive(keyFingerprint),
                    "payload" to payload,
                    "payloadType" to JsonPrimitive(type.wireValue),
                    "repository" to JsonPrimitive(normalizedBaseUrl),
                    "sequence" to JsonPrimitive(sequence),
                ),
            )
            return SIGNATURE_DOMAIN + canonicalSyncJson(statement).encodeToByteArray()
        }
    }
}

internal data class VerifiedRepositoryPayload(
    val payload: JsonElement,
    val admission: RepositoryDocumentAdmission?,
)

internal data class RepositoryDocumentAdmission(
    val normalizedBaseUrl: String,
    val type: RepositorySignedDocumentType,
    val documentId: String,
    val sequence: Long,
    val payloadSha256: String,
    /** Fingerprint of the exact pinned key which verified this document. */
    val keyFingerprint: String,
)

private object RepositoryTrustRootValidation {
    suspend fun requireValid(root: RepositoryTrustRoot) {
        SodiumSyncPrimitives.initialize()
        val publicKey = canonicalBase64Url(root.publicKeyBase64Url, 32)
        require(root.fingerprint == fingerprint(publicKey)) { "Repository trust-root fingerprint does not match its key" }
    }

    fun canonicalBase64Url(value: String, expectedBytes: Int): ByteArray {
        require(value.length <= 128) { "Encoded signing material is too large" }
        val decoded = SodiumSyncPrimitives.base64UrlDecode(value)
        require(decoded.size == expectedBytes) { "Invalid signing material size" }
        require(SodiumSyncPrimitives.base64UrlEncode(decoded) == value) { "Non-canonical base64url signing material" }
        return decoded
    }

    fun fingerprint(publicKey: ByteArray): String =
        "sha256:${SodiumSyncPrimitives.base64UrlEncode(SodiumSyncPrimitives.sha256(publicKey))}"
}
