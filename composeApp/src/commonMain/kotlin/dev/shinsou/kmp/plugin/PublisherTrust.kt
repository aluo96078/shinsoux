package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * ADR-0002 S5: trust in third-party code is trust in a publisher's signing key (§8.2–§8.4), not in
 * a repository URL. Everything here is device-local state in the plugin key-value store, which is
 * never part of a portable backup or sync payload, and nothing is learned from what a repository
 * says about itself: the key, its fingerprint and every document are verified locally.
 */

/** D3: how a publisher's first key may be trusted. Set globally; a publisher may be made stricter. */
public enum class PublisherTrustMode {
    /** (a) Trust on first use; a fingerprint, when one is supplied, must match. */
    TOFU_WITH_OPTIONAL_MATCH,

    /** (b) Never trust a publisher without matching its full fingerprint. */
    REQUIRE_FINGERPRINT_MATCH,
}

/** How the publisher's key came to be trusted (the `verification` field of §8.2). */
public enum class PublisherVerification { TOFU, FINGERPRINT_MATCHED, OFFICIAL_BUILT_IN }

public enum class PublisherTrustStatus {
    TRUSTED,

    /** TOFU-trusted while mode (b) applies: installed packages keep running, nothing new installs. */
    PENDING_FINGERPRINT_MATCH,

    /** The publisher's current key revoked itself: every package of it is inert. */
    KEY_REVOKED,

    /** The user replaced the key after it was lost: the old publisher's packages need re-authorization. */
    KEY_REPLACED,
}

/**
 * One publisher. [publisherId] is the fingerprint of its first key and survives rotation (§10.3);
 * a key replaced after loss starts a new publisher.
 */
@Serializable
public data class PublisherTrustRecord(
    val publisherId: String,
    val currentPublicKey: String,
    val currentFingerprint: String,
    val retiredFingerprints: List<String> = emptyList(),
    val retiredPublicKeys: List<String> = emptyList(),
    val revokedFingerprints: List<String> = emptyList(),
    val verification: PublisherVerification,
    /** The publisher was put under mode (b) although the global mode may be (a). */
    val requireFingerprintMatch: Boolean = false,
    val keyRevoked: Boolean = false,
    val replacedBy: String? = null,
    /** Highest rotation `effectiveSequence` applied; -1 before any rotation. */
    val rotationSequence: Long = -1,
) {
    val currentKey: RepositoryTrustRoot get() = RepositoryTrustRoot(currentPublicKey, currentFingerprint)

    /** Watermark scope shared by every mirror of this publisher. */
    val stateScope: String get() = "publisher:$publisherId"

    public fun status(globalMode: PublisherTrustMode): PublisherTrustStatus = when {
        replacedBy != null -> PublisherTrustStatus.KEY_REPLACED
        keyRevoked -> PublisherTrustStatus.KEY_REVOKED
        verification == PublisherVerification.TOFU &&
            (globalMode == PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH || requireFingerprintMatch) ->
            PublisherTrustStatus.PENDING_FINGERPRINT_MATCH
        else -> PublisherTrustStatus.TRUSTED
    }
}

public sealed class PublisherTrustException(message: String) : IllegalArgumentException(message) {
    public class FingerprintRequired : PublisherTrustException("This publisher can only be trusted by matching its full fingerprint")
    public class FingerprintMismatch : PublisherTrustException("The publisher's key does not match the given fingerprint")
    public class KeyRevoked(publisherId: String) : PublisherTrustException("Publisher $publisherId revoked its key")
    public class KeyReplaced(publisherId: String) : PublisherTrustException("Publisher $publisherId was replaced by a new key")
    public class Blocked(entry: PluginBlockEntry) : PublisherTrustException("Blocked on this device: $entry")
    public class NotTrusted(subject: String) : PublisherTrustException("No trusted publisher for $subject")
    public class NotInstallable(reason: String) : PublisherTrustException("Not installable: $reason")
    public class InvalidDocument(reason: String) : PublisherTrustException("Invalid publisher document: $reason")
}

/** §8.2 fingerprints: SHA-256 of the raw Ed25519 public key. */
public object PublisherFingerprint {
    /** The full value, as envelopes carry it: `sha256:` + unpadded base64url of the 32-byte digest. */
    public fun of(publicKey: ByteArray): String =
        "sha256:${SodiumSyncPrimitives.base64UrlEncode(SodiumSyncPrimitives.sha256(publicKey))}"

    /** What the UI shows: the first 128 bits in hex, four characters a group. Never used to compare. */
    public fun display(fingerprint: String): String {
        val digest = digestOf(fingerprint) ?: return fingerprint
        return digest.copyOf(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .uppercase().chunked(4).joinToString(" ")
    }

    /**
     * A user- or link-supplied full fingerprint in canonical form, or null. Accepts the `sha256:`
     * base64url form or all 64 hex digits (spaces, colons and dashes ignored, any case); the 128-bit
     * display form is deliberately not enough.
     */
    public fun parse(input: String): String? {
        val text = input.trim()
        if (text.startsWith("sha256:")) return text.takeIf { digestOf(it) != null }
        val hex = text.filterNot { it == ' ' || it == ':' || it == '-' }.lowercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val bytes = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return "sha256:${SodiumSyncPrimitives.base64UrlEncode(bytes)}"
    }

    private fun digestOf(fingerprint: String): ByteArray? {
        if (!fingerprint.startsWith("sha256:") || fingerprint.length > 64) return null
        val encoded = fingerprint.removePrefix("sha256:")
        val bytes = runCatching { SodiumSyncPrimitives.base64UrlDecode(encoded) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == 32 && SodiumSyncPrimitives.base64UrlEncode(it) == encoded }
    }
}

/** `shinsou://add-repository?url=…&fp=…` (§8.1): the repository to add and, optionally, the fingerprint to match. */
public data class AddRepositoryLink(val repositoryUrl: String, val fingerprint: String?) {
    public companion object {
        /** Null for anything that is not a well-formed add-repository link with an HTTPS URL. */
        public fun parse(link: String): AddRepositoryLink? {
            val url = runCatching { Url(link) }.getOrNull() ?: return null
            if (url.protocol.name != "shinsou" || url.host != "add-repository") return null
            val repository = url.parameters.getAll("url")?.singleOrNull() ?: return null
            if (!repository.startsWith("https://")) return null
            val fingerprints = url.parameters.getAll("fp")
            if (fingerprints != null && fingerprints.size != 1) return null
            val fingerprint = fingerprints?.single()?.let { PublisherFingerprint.parse(it) ?: return null }
            return AddRepositoryLink(repository, fingerprint)
        }
    }
}

/**
 * Durable publisher trust (§8.2, §8.3, §8.4). A URL maps to one publisher; one publisher may serve
 * many URLs (mirrors) without trusting it again; a URL presenting another key is a key
 * substitution, which only [replaceAfterExplicitApproval] resolves.
 */
public class PublisherTrustStore(
    private val keyValueStore: PluginKeyValueStore,
    private val securityState: RepositorySecurityStateStore,
    private val json: kotlinx.serialization.json.Json = PluginJson,
) {
    private val mutex = Mutex()
    private val verifier = RepositorySignedDocumentVerifier(
        policy = { throw IllegalStateException("publisher documents name their key explicitly") },
        stateStore = securityState,
    )

    public suspend fun globalMode(): PublisherTrustMode = mutex.withLock { readMode() }

    /** Switching to (b) leaves TOFU publishers [PublisherTrustStatus.PENDING_FINGERPRINT_MATCH] until matched. */
    public suspend fun setGlobalMode(mode: PublisherTrustMode): Unit = mutex.withLock {
        writeVerified(MODE_KEY, mode.name)
    }

    public suspend fun publisher(publisherId: String): PublisherTrustRecord? = mutex.withLock { readRecord(publisherId) }

    public suspend fun publisherForRepository(normalizedBaseUrl: String): PublisherTrustRecord? = mutex.withLock {
        readString(urlKey(normalizedBaseUrl))?.let { readRecord(it) }
    }

    public suspend fun publishers(): List<PublisherTrustRecord> = mutex.withLock { readIndex().mapNotNull { readRecord(it) } }

    /**
     * Trusts the key a repository presented, after the user confirmed it. [expectedFingerprint] comes
     * from a deep link or the user; it must match when given and is required under mode (b), globally
     * or for this repository ([requireFingerprintMatch]).
     */
    public suspend fun trustRepository(
        normalizedBaseUrl: String,
        presentedKey: RepositoryTrustRoot,
        expectedFingerprint: String? = null,
        requireFingerprintMatch: Boolean = false,
    ): PublisherTrustRecord = mutex.withLock {
        requireValidKey(presentedKey)
        val matched = checkFingerprint(presentedKey, expectedFingerprint, requireFingerprintMatch || readMode() == PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH)
        readString(urlKey(normalizedBaseUrl))?.let { mapped ->
            val record = requireNotNull(readRecord(mapped)) { "Dangling repository mapping" }
            if (record.currentFingerprint != presentedKey.fingerprint) throw RepositoryTrustException.KeySubstitution()
            requireUsable(record)
            return@withLock upgrade(record, matched, requireFingerprintMatch)
        }
        val owner = readString(keyKey(presentedKey.fingerprint))?.let { readRecord(it) }
        if (owner != null) {
            // A mirror: the same publisher, reached through another URL. A retired key is stale.
            if (owner.currentFingerprint != presentedKey.fingerprint) throw RepositoryTrustException.KeySubstitution()
            requireUsable(owner)
            writeVerified(urlKey(normalizedBaseUrl), owner.publisherId)
            return@withLock upgrade(owner, matched, requireFingerprintMatch)
        }
        val record = PublisherTrustRecord(
            publisherId = presentedKey.fingerprint,
            currentPublicKey = presentedKey.publicKeyBase64Url,
            currentFingerprint = presentedKey.fingerprint,
            verification = if (matched) PublisherVerification.FINGERPRINT_MATCHED else PublisherVerification.TOFU,
            requireFingerprintMatch = requireFingerprintMatch,
        )
        writeRecord(record)
        writeVerified(keyKey(presentedKey.fingerprint), record.publisherId)
        writeVerified(urlKey(normalizedBaseUrl), record.publisherId)
        record
    }

    /** The app's own key (§10.1, D9): trusted without TOFU, shown as official. */
    public suspend fun trustBuiltIn(normalizedBaseUrl: String, key: RepositoryTrustRoot): PublisherTrustRecord = mutex.withLock {
        requireValidKey(key)
        val existing = readString(keyKey(key.fingerprint))?.let { readRecord(it) }
        val record = existing?.copy(verification = PublisherVerification.OFFICIAL_BUILT_IN) ?: PublisherTrustRecord(
            publisherId = key.fingerprint,
            currentPublicKey = key.publicKeyBase64Url,
            currentFingerprint = key.fingerprint,
            verification = PublisherVerification.OFFICIAL_BUILT_IN,
        )
        writeRecord(record)
        writeVerified(keyKey(key.fingerprint), record.publisherId)
        writeVerified(urlKey(normalizedBaseUrl), record.publisherId)
        record
    }

    /** Matches a TOFU publisher's full fingerprint later (for example after switching to mode (b)). */
    public suspend fun confirmFingerprint(publisherId: String, fingerprint: String): PublisherTrustRecord = mutex.withLock {
        val record = readRecord(publisherId) ?: throw PublisherTrustException.NotTrusted(publisherId)
        requireUsable(record)
        val expected = PublisherFingerprint.parse(fingerprint) ?: throw PublisherTrustException.FingerprintMismatch()
        if (expected != record.currentFingerprint) throw PublisherTrustException.FingerprintMismatch()
        upgrade(record, matched = true, requireFingerprintMatch = record.requireFingerprintMatch)
    }

    /** Puts one publisher under mode (b); there is no way back to (a) for it while the global mode is (b). */
    public suspend fun requireFingerprintMatch(publisherId: String): PublisherTrustRecord = mutex.withLock {
        val record = readRecord(publisherId) ?: throw PublisherTrustException.NotTrusted(publisherId)
        record.copy(requireFingerprintMatch = true).also { writeRecord(it) }
    }

    /**
     * The key was lost (§8.3): the user trusts [newKey] for this URL as a new publisher. The old
     * publisher is marked replaced, so its installed packages need authorization again. A publisher
     * the user put under mode (b) stays under it. The old publisher is retired first, so an
     * interrupted replacement never leaves it usable; repeating the call completes it.
     */
    public suspend fun replaceAfterExplicitApproval(
        normalizedBaseUrl: String,
        newKey: RepositoryTrustRoot,
        expectedFingerprint: String? = null,
    ): PublisherTrustRecord = mutex.withLock {
        requireValidKey(newKey)
        val old = readString(urlKey(normalizedBaseUrl))?.let { readRecord(it) }
        val strictForThisPublisher = old?.requireFingerprintMatch == true
        val strict = strictForThisPublisher || readMode() == PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH
        val matched = checkFingerprint(newKey, expectedFingerprint, strict)
        val owner = readString(keyKey(newKey.fingerprint))
        // Only the replacement this URL's publisher already names may be resumed.
        val resuming = owner != null && owner == newKey.fingerprint && old?.replacedBy == owner
        if (owner != null && !resuming) throw RepositoryTrustException.KeySubstitution()
        if (old != null && old.publisherId != newKey.fingerprint && old.replacedBy == null) {
            writeRecord(old.copy(replacedBy = newKey.fingerprint))
        }
        val record = readRecord(newKey.fingerprint)?.takeIf { resuming } ?: PublisherTrustRecord(
            publisherId = newKey.fingerprint,
            currentPublicKey = newKey.publicKeyBase64Url,
            currentFingerprint = newKey.fingerprint,
            verification = if (matched) PublisherVerification.FINGERPRINT_MATCHED else PublisherVerification.TOFU,
            requireFingerprintMatch = strictForThisPublisher,
        )
        writeRecord(record)
        writeVerified(keyKey(newKey.fingerprint), record.publisherId)
        writeVerified(urlKey(normalizedBaseUrl), record.publisherId)
        record
    }

    /**
     * §8.3 rotation, fetched from [normalizedBaseUrl]: the publisher's current key signs the envelope,
     * and the new key signs [rotationStatement] (in the payload as `newKeySignature`). The new key
     * then signs everything; the old one is kept as retired. [PublisherTrustRecord.publisherId] stays.
     */
    public suspend fun applyRotation(normalizedBaseUrl: String, envelope: JsonObject): PublisherTrustRecord = mutex.withLock {
        val record = mappedRecord(normalizedBaseUrl)
        requireUsable(record)
        val verified = verifier.verifyEnvelope(
            normalizedBaseUrl, RepositorySignedDocumentType.PUBLISHER_KEY_ROTATION, ROTATION_DOCUMENT_ID,
            envelope, record.currentKey, record.stateScope,
        )
        val payload = verified.payload as? JsonObject ?: throw PublisherTrustException.InvalidDocument("rotation payload")
        requireFields(payload, setOf("publisherId", "newPublicKey", "newKeyFingerprint", "effectiveSequence", "newKeySignature"))
        if (payload.string("publisherId") != record.publisherId) throw PublisherTrustException.InvalidDocument("rotation names another publisher")
        val newKey = RepositoryTrustRoot(payload.string("newPublicKey"), payload.string("newKeyFingerprint"))
        requireValidKey(newKey)
        val effective = payload["effectiveSequence"]?.jsonPrimitive?.longOrNull
            ?: throw PublisherTrustException.InvalidDocument("rotation sequence")
        if (effective <= record.rotationSequence) throw PublisherTrustException.InvalidDocument("rotation is not newer than the last one")
        if (readString(keyKey(newKey.fingerprint)) != null) throw RepositoryTrustException.KeySubstitution()
        val statement = rotationStatement(record.publisherId, record.currentFingerprint, newKey, effective)
        val signature = RepositoryTrustRootValidation.canonicalBase64Url(payload.string("newKeySignature"), 64)
        val newPublicKey = RepositoryTrustRootValidation.canonicalBase64Url(newKey.publicKeyBase64Url, 32)
        if (!SodiumSyncPrimitives.verifyEd25519(statement, signature, newPublicKey)) throw RepositoryTrustException.InvalidSignature()
        val rotated = record.copy(
            currentPublicKey = newKey.publicKeyBase64Url,
            currentFingerprint = newKey.fingerprint,
            retiredFingerprints = record.retiredFingerprints + record.currentFingerprint,
            retiredPublicKeys = record.retiredPublicKeys + record.currentPublicKey,
            rotationSequence = effective,
        )
        // The new state first: an interrupted call leaves the rotation applied and its watermark behind,
        // never the watermark ahead of the state it guards.
        writeRecord(rotated)
        writeVerified(keyKey(newKey.fingerprint), record.publisherId)
        verifier.commit(verified.admission)
        rotated
    }

    /**
     * §8.4 key self-revocation, signed by the revoked key itself (current or retired). Revoking the
     * current key makes the publisher inert until the user trusts a new key; a retired key only
     * stops verifying anything.
     */
    public suspend fun applyKeyRevocation(normalizedBaseUrl: String, envelope: JsonObject): PublisherTrustRecord = mutex.withLock {
        val record = mappedRecord(normalizedBaseUrl)
        val presented = envelope["keyFingerprint"]?.jsonPrimitive?.contentOrNull
            ?: throw PublisherTrustException.InvalidDocument("key revocation names no key")
        val key = when (presented) {
            record.currentFingerprint -> record.currentKey
            in record.retiredFingerprints -> {
                val index = record.retiredFingerprints.indexOf(presented)
                RepositoryTrustRoot(record.retiredPublicKeys[index], presented)
            }
            else -> throw RepositoryTrustException.KeySubstitution()
        }
        // Each key has its own watermark: a retired key must not be able to block the current key's revocation.
        val verified = verifier.verifyEnvelope(
            normalizedBaseUrl, RepositorySignedDocumentType.KEY_REVOKED, KEY_REVOKED_DOCUMENT_ID,
            envelope, key, "${record.stateScope}/key/$presented",
        )
        val payload = verified.payload as? JsonObject ?: throw PublisherTrustException.InvalidDocument("key revocation payload")
        requireFields(payload, setOf("publisherId", "revokedKeyFingerprint"))
        if (payload.string("publisherId") != record.publisherId || payload.string("revokedKeyFingerprint") != presented) {
            throw PublisherTrustException.InvalidDocument("key revocation names another key")
        }
        val revoked = if (presented == record.currentFingerprint) {
            record.copy(keyRevoked = true, revokedFingerprints = (record.revokedFingerprints + presented).distinct())
        } else {
            record.copy(revokedFingerprints = (record.revokedFingerprints + presented).distinct())
        }
        writeRecord(revoked)
        verifier.commit(verified.admission)
        revoked
    }

    private suspend fun mappedRecord(normalizedBaseUrl: String): PublisherTrustRecord =
        readString(urlKey(normalizedBaseUrl))?.let { readRecord(it) }
            ?: throw PublisherTrustException.NotTrusted(normalizedBaseUrl)

    private fun requireUsable(record: PublisherTrustRecord) {
        if (record.replacedBy != null) throw PublisherTrustException.KeyReplaced(record.publisherId)
        if (record.keyRevoked) throw PublisherTrustException.KeyRevoked(record.publisherId)
    }

    /** True when the key's fingerprint was matched; throws when it must be and was not. */
    private fun checkFingerprint(key: RepositoryTrustRoot, expected: String?, strict: Boolean): Boolean {
        if (expected == null) {
            if (strict) throw PublisherTrustException.FingerprintRequired()
            return false
        }
        val canonical = PublisherFingerprint.parse(expected) ?: throw PublisherTrustException.FingerprintMismatch()
        if (canonical != key.fingerprint) throw PublisherTrustException.FingerprintMismatch()
        return true
    }

    private suspend fun upgrade(record: PublisherTrustRecord, matched: Boolean, requireFingerprintMatch: Boolean): PublisherTrustRecord {
        val upgraded = record.copy(
            verification = if (matched && record.verification == PublisherVerification.TOFU) PublisherVerification.FINGERPRINT_MATCHED else record.verification,
            requireFingerprintMatch = record.requireFingerprintMatch || requireFingerprintMatch,
        )
        if (upgraded != record) writeRecord(upgraded)
        return upgraded
    }

    private suspend fun requireValidKey(key: RepositoryTrustRoot) {
        SodiumSyncPrimitives.initialize()
        RepositoryTrustRootValidation.requireValid(key)
    }

    private suspend fun readMode(): PublisherTrustMode =
        readString(MODE_KEY)?.let { stored -> PublisherTrustMode.entries.firstOrNull { it.name == stored } }
            ?: PublisherTrustMode.TOFU_WITH_OPTIONAL_MATCH

    private suspend fun readRecord(publisherId: String): PublisherTrustRecord? = readString(recordKey(publisherId))?.let {
        runCatching { json.decodeFromString<PublisherTrustRecord>(it) }
            .getOrElse { error -> throw RepositoryTrustException.Malformed("Invalid persisted publisher record", error) }
    }

    private suspend fun writeRecord(record: PublisherTrustRecord) {
        writeVerified(recordKey(record.publisherId), json.encodeToString(record))
        val index = readIndex()
        if (record.publisherId !in index) writeVerified(INDEX_KEY, json.encodeToString(index + record.publisherId))
    }

    private suspend fun readIndex(): List<String> = readString(INDEX_KEY)?.let {
        runCatching { json.decodeFromString<List<String>>(it) }
            .getOrElse { error -> throw RepositoryTrustException.Malformed("Invalid persisted publisher index", error) }
    }.orEmpty()

    private suspend fun readString(key: String): String? = keyValueStore.getString(key)?.also {
        if (pluginUtf8ByteCountAtMost(it, MAX_STATE_BYTES) == null) {
            throw RepositoryTrustException.Malformed("Persisted publisher trust state exceeds its size limit")
        }
    }

    private suspend fun writeVerified(key: String, value: String) {
        keyValueStore.putString(key, value)
        check(keyValueStore.getString(key) == value) { "Could not verify publisher trust write" }
    }

    public companion object {
        public const val ROTATION_DOCUMENT_ID: String = "publisher-key-rotation.json"
        public const val KEY_REVOKED_DOCUMENT_ID: String = "publisher-key-revoked.json"
        private val ROTATION_DOMAIN = "dev.shinsou.publisher.rotation.v1\u0000".encodeToByteArray()
        private const val PREFIX = "plugin.publisher.trust.v1"
        private const val MODE_KEY = "$PREFIX.mode"
        private const val INDEX_KEY = "$PREFIX.publishers"
        private const val MAX_STATE_BYTES = 64 * 1_024

        /** What the new key signs in a rotation: it agrees to take over this publisher from the old key. */
        public fun rotationStatement(
            publisherId: String,
            oldKeyFingerprint: String,
            newKey: RepositoryTrustRoot,
            effectiveSequence: Long,
        ): ByteArray = ROTATION_DOMAIN + canonicalSyncJson(
            JsonObject(
                mapOf(
                    "effectiveSequence" to JsonPrimitive(effectiveSequence),
                    "newKeyFingerprint" to JsonPrimitive(newKey.fingerprint),
                    "newPublicKey" to JsonPrimitive(newKey.publicKeyBase64Url),
                    "oldKeyFingerprint" to JsonPrimitive(oldKeyFingerprint),
                    "publisherId" to JsonPrimitive(publisherId),
                ),
            ),
        ).encodeToByteArray()

        private fun recordKey(publisherId: String) = "$PREFIX.record.${Sha256.hex(publisherId.encodeToByteArray())}"
        private fun urlKey(url: String) = "$PREFIX.url.${Sha256.hex(url.encodeToByteArray())}"
        private fun keyKey(fingerprint: String) = "$PREFIX.key.${Sha256.hex(fingerprint.encodeToByteArray())}"
    }
}

/**
 * §8.4 artifact withdrawals and the D8 freshness of a publisher's signed documents. The latest
 * `publisher-revocations` document replaces the previous one; its sequence never goes back.
 */
public class PublisherRevocationStore(
    private val keyValueStore: PluginKeyValueStore,
    private val publishers: PublisherTrustStore,
    securityState: RepositorySecurityStateStore,
    private val json: kotlinx.serialization.json.Json = PluginJson,
) {
    private val mutex = Mutex()
    private val verifier = RepositorySignedDocumentVerifier(
        policy = { throw IllegalStateException("publisher documents name their key explicitly") },
        stateStore = securityState,
    )

    /** Payload: `publisherId`, `revokedArtifacts` (SHA-256 hex), `expiresAt` (Unix seconds). */
    public suspend fun applyRevocations(normalizedBaseUrl: String, envelope: JsonObject): PublisherRevocations = mutex.withLock {
        val record = publishers.publisherForRepository(normalizedBaseUrl) ?: throw PublisherTrustException.NotTrusted(normalizedBaseUrl)
        if (record.keyRevoked) throw PublisherTrustException.KeyRevoked(record.publisherId)
        if (record.replacedBy != null) throw PublisherTrustException.KeyReplaced(record.publisherId)
        val verified = verifier.verifyEnvelope(
            normalizedBaseUrl, RepositorySignedDocumentType.REVOCATIONS, REVOCATIONS_DOCUMENT_ID,
            envelope, record.currentKey, record.stateScope,
        )
        val payload = verified.payload as? JsonObject ?: throw PublisherTrustException.InvalidDocument("revocations payload")
        requireFields(payload, setOf("publisherId", "revokedArtifacts", "expiresAt"))
        if (payload.string("publisherId") != record.publisherId) throw PublisherTrustException.InvalidDocument("revocations name another publisher")
        val artifacts = (payload["revokedArtifacts"] as? JsonArray ?: throw PublisherTrustException.InvalidDocument("revokedArtifacts"))
            .map { it.jsonPrimitive.contentOrNull?.takeIf(SHA256_HEX::matches) ?: throw PublisherTrustException.InvalidDocument("artifact digest") }
        if (artifacts.size > MAX_REVOKED_ARTIFACTS) throw PublisherTrustException.InvalidDocument("too many revoked artifacts")
        val expiresAt = payload["expiresAt"]?.jsonPrimitive?.longOrNull ?: throw PublisherTrustException.InvalidDocument("expiresAt")
        val revocations = PublisherRevocations(requireNotNull(verified.admission).sequence, artifacts.distinct(), expiresAt)
        // The stored list is authoritative on its own: it never goes back even if the watermark lags.
        val stored = read(record.publisherId)
        if (stored != null && (revocations.sequence < stored.sequence || revocations.sequence == stored.sequence && revocations != stored)) {
            throw RepositoryTrustException.Replay(RepositorySignedDocumentType.REVOCATIONS, revocations.sequence, stored.sequence)
        }
        // Enforce first, then advance the watermark: an interruption never leaves an admitted withdrawal unenforced.
        val encoded = json.encodeToString(revocations)
        keyValueStore.putString(key(record.publisherId), encoded)
        check(keyValueStore.getString(key(record.publisherId)) == encoded) { "Could not verify publisher revocations write" }
        verifier.commit(verified.admission)
        revocations
    }

    public suspend fun revocations(publisherId: String): PublisherRevocations? = mutex.withLock { read(publisherId) }

    private suspend fun read(publisherId: String): PublisherRevocations? = keyValueStore.getString(key(publisherId))?.let {
        runCatching { json.decodeFromString<PublisherRevocations>(it) }
            .getOrElse { error -> throw RepositoryTrustException.Malformed("Invalid persisted publisher revocations", error) }
    }

    private fun key(publisherId: String) = "plugin.publisher.revocations.v1.${Sha256.hex(publisherId.encodeToByteArray())}"

    public companion object {
        public const val REVOCATIONS_DOCUMENT_ID: String = "publisher-revocations.json"
        private const val MAX_REVOKED_ARTIFACTS = 4_096
    }
}

@Serializable
public data class PublisherRevocations(
    val sequence: Long,
    val revokedArtifacts: List<String>,
    /** Unix seconds. Past it, nothing new installs from this publisher; installed packages keep running (D8). */
    val expiresAt: Long,
) {
    public fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds >= expiresAt
}

/** D5: what the user blocked on this device. Never signed, downloaded, backed up or synced. */
@Serializable
public sealed interface PluginBlockEntry {
    /** [publisherId] is [NO_PUBLISHER] for packages without one (the reviewed catalogues, unsigned). */
    @Serializable
    public data class Package(val packageId: String, val publisherId: String) : PluginBlockEntry {
        public companion object {
            public const val NO_PUBLISHER: String = ""
        }
    }

    @Serializable
    public data class Artifact(val sha256: String) : PluginBlockEntry

    @Serializable
    public data class Publisher(val publisherId: String) : PluginBlockEntry
}

public class LocalPluginBlocklist(
    private val keyValueStore: PluginKeyValueStore,
    private val json: kotlinx.serialization.json.Json = PluginJson,
) {
    private val mutex = Mutex()

    public suspend fun entries(): List<PluginBlockEntry> = mutex.withLock { read() }

    public suspend fun block(entry: PluginBlockEntry): Unit = mutex.withLock {
        if (entry is PluginBlockEntry.Artifact) require(SHA256_HEX.matches(entry.sha256)) { "Invalid artifact SHA-256" }
        val current = read()
        if (entry in current) return@withLock
        require(current.size < MAX_ENTRIES) { "The block list is full" }
        write(current + entry)
    }

    public suspend fun unblock(entry: PluginBlockEntry): Unit = mutex.withLock { write(read() - entry) }

    /** The first entry that blocks this package, artifact or publisher, if any. */
    public suspend fun blocking(packageId: String?, publisherId: String?, artifactSha256: String?): PluginBlockEntry? =
        mutex.withLock {
            read().firstOrNull { entry ->
                when (entry) {
                    is PluginBlockEntry.Package ->
                        entry.packageId == packageId && entry.publisherId == (publisherId ?: PluginBlockEntry.Package.NO_PUBLISHER)
                    is PluginBlockEntry.Artifact -> entry.sha256 == artifactSha256?.lowercase()
                    is PluginBlockEntry.Publisher -> entry.publisherId == publisherId
                }
            }
        }

    private suspend fun read(): List<PluginBlockEntry> = keyValueStore.getString(KEY)?.let {
        runCatching { json.decodeFromString<List<PluginBlockEntry>>(it) }
            .getOrElse { error -> throw RepositoryTrustException.Malformed("Invalid persisted block list", error) }
    }.orEmpty()

    private suspend fun write(entries: List<PluginBlockEntry>) {
        val encoded = json.encodeToString(entries)
        keyValueStore.putString(KEY, encoded)
        check(keyValueStore.getString(KEY) == encoded) { "Could not verify block list write" }
    }

    private companion object {
        const val KEY = "plugin.blocklist.v1"
        const val MAX_ENTRIES = 1_024
    }
}

/**
 * Repository trust by publisher (§8.5): a URL mapped to a trusted publisher requires that
 * publisher's current key and shares its watermarks with every mirror. Revoked, replaced or
 * user-blocked publishers are refused before any request. URLs without a publisher fall back to
 * [fallback] (URL pins and the official unsigned exception).
 */
public class PublisherRepositoryTrustPolicy(
    private val publishers: PublisherTrustStore,
    private val blocklist: LocalPluginBlocklist,
    private val fallback: RepositoryTrustPolicy,
) : RepositoryTrustPolicy {
    override suspend fun decisionFor(normalizedBaseUrl: String): RepositoryTrustDecision {
        val record = publishers.publisherForRepository(normalizedBaseUrl) ?: return fallback.decisionFor(normalizedBaseUrl)
        if (record.replacedBy != null) throw PublisherTrustException.KeyReplaced(record.publisherId)
        if (record.keyRevoked) throw PublisherTrustException.KeyRevoked(record.publisherId)
        blocklist.blocking(null, record.publisherId, null)?.let { throw PublisherTrustException.Blocked(it) }
        return RepositoryTrustDecision.RequirePinnedSignature(record.currentKey, record.stateScope)
    }
}

/** §10.1: where a package's code runs. Decided from local state only, never from what a repository says. */
public sealed interface ExecutionTier {
    public data object ReviewedInProcess : ExecutionTier
    public data class Sandboxed(val development: Boolean = false, val warnings: Set<ExecutionTierWarning> = emptySet()) : ExecutionTier
    public data class Inert(val reason: InertReason) : ExecutionTier
}

public enum class ExecutionTierWarning {
    /** Mode (b) applies and the publisher was only TOFU-trusted. */
    FINGERPRINT_NOT_MATCHED,

    /** The publisher's signed documents expired: it may have stopped publishing, or be frozen (D8). */
    PUBLISHER_DOCUMENTS_EXPIRED,
}

public enum class InertReason { BLOCKED_BY_USER, UNSIGNED, PUBLISHER_NOT_TRUSTED, PUBLISHER_KEY_REVOKED, PUBLISHER_KEY_REPLACED, ARTIFACT_REVOKED, NOT_AUTHORIZED }

/** What the host knows about one installed or installable package. */
public data class ExecutionTierCandidate(
    val packageId: String,
    val artifactSha256: String,
    /** The verified publisher of the artifact; null when it was not signed by a trusted publisher. */
    val publisherId: String?,
    /** Exact match in the compiled official reviewed catalogue. */
    val officialReviewedCatalogMatch: Boolean = false,
    /** Transitional: from the canonical official repository while it is still unsigned. */
    val fromCanonicalOfficialUnsignedRepository: Boolean = false,
    /** The user authorized this package's execution (the admission record). */
    val userAuthorized: Boolean = false,
    /** Unsigned, from an allowed loopback/LAN developer source. */
    val developerLocalSource: Boolean = false,
)

public class ExecutionTierResolver(
    private val publishers: PublisherTrustStore,
    private val revocations: PublisherRevocationStore,
    private val blocklist: LocalPluginBlocklist,
    /** The app's built-in official publisher, once the official repository is signed. */
    private val officialPublisherId: String? = null,
    private val developerMode: suspend () -> Boolean = { false },
    private val nowEpochSeconds: () -> Long,
) {
    /** The tier an installed package runs in now (checked at launch, rehydrate and after any trust change). */
    public suspend fun resolve(candidate: ExecutionTierCandidate): ExecutionTier {
        // The user's block list comes first, before official status or any grant.
        blocklist.blocking(candidate.packageId, candidate.publisherId, candidate.artifactSha256)?.let {
            return ExecutionTier.Inert(InertReason.BLOCKED_BY_USER)
        }
        val publisherId = candidate.publisherId
            ?: return when {
                // Transitional: the official repository is still unsigned, so it has no publisher yet.
                candidate.officialReviewedCatalogMatch && candidate.fromCanonicalOfficialUnsignedRepository -> ExecutionTier.ReviewedInProcess
                candidate.developerLocalSource && developerMode() -> ExecutionTier.Sandboxed(development = true)
                else -> ExecutionTier.Inert(InertReason.UNSIGNED)
            }
        val record = publishers.publisher(publisherId) ?: return ExecutionTier.Inert(InertReason.PUBLISHER_NOT_TRUSTED)
        val warnings = linkedSetOf<ExecutionTierWarning>()
        when (record.status(publishers.globalMode())) {
            PublisherTrustStatus.KEY_REPLACED -> return ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REPLACED)
            PublisherTrustStatus.KEY_REVOKED -> return ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REVOKED)
            PublisherTrustStatus.PENDING_FINGERPRINT_MATCH -> warnings += ExecutionTierWarning.FINGERPRINT_NOT_MATCHED
            PublisherTrustStatus.TRUSTED -> Unit
        }
        val withdrawn = revocations.revocations(publisherId)
        if (withdrawn != null && candidate.artifactSha256.lowercase() in withdrawn.revokedArtifacts) {
            return ExecutionTier.Inert(InertReason.ARTIFACT_REVOKED)
        }
        // Official status comes after revocation and withdrawal: a revoked official key or a withdrawn
        // official artifact does not run in process either.
        if (candidate.officialReviewedCatalogMatch && officialPublisherId != null && publisherId == officialPublisherId) {
            return ExecutionTier.ReviewedInProcess
        }
        if (!candidate.userAuthorized) return ExecutionTier.Inert(InertReason.NOT_AUTHORIZED)
        if (withdrawn?.isExpired(nowEpochSeconds()) == true) warnings += ExecutionTierWarning.PUBLISHER_DOCUMENTS_EXPIRED
        return ExecutionTier.Sandboxed(warnings = warnings)
    }

    /**
     * Gate for a new install or update: everything [resolve] requires except the grant, and stricter
     * on trust — a publisher pending its fingerprint match installs nothing new, and neither does one
     * without a current signed revocation list. Freshness must be proven, not assumed: a repository
     * withholding the list (a freeze attack) would otherwise never let it expire (§8.4). Installed
     * packages are not affected (D8).
     */
    public suspend fun requireInstallable(packageId: String, publisherId: String, artifactSha256: String) {
        blocklist.blocking(packageId, publisherId, artifactSha256)?.let { throw PublisherTrustException.Blocked(it) }
        val record = publishers.publisher(publisherId) ?: throw PublisherTrustException.NotTrusted(publisherId)
        when (record.status(publishers.globalMode())) {
            PublisherTrustStatus.TRUSTED -> Unit
            PublisherTrustStatus.PENDING_FINGERPRINT_MATCH -> throw PublisherTrustException.NotInstallable("the publisher's fingerprint has not been matched")
            PublisherTrustStatus.KEY_REVOKED -> throw PublisherTrustException.KeyRevoked(publisherId)
            PublisherTrustStatus.KEY_REPLACED -> throw PublisherTrustException.KeyReplaced(publisherId)
        }
        val withdrawn = revocations.revocations(publisherId)
            ?: throw PublisherTrustException.NotInstallable("the publisher has published no revocation list")
        if (artifactSha256.lowercase() in withdrawn.revokedArtifacts) {
            throw PublisherTrustException.NotInstallable("the publisher withdrew this artifact")
        }
        if (withdrawn.isExpired(nowEpochSeconds())) {
            throw PublisherTrustException.NotInstallable("the publisher's signed documents expired")
        }
    }
}

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private fun requireFields(payload: JsonObject, fields: Set<String>) {
    if (payload.keys != fields) throw PublisherTrustException.InvalidDocument("unknown or missing fields")
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw PublisherTrustException.InvalidDocument("'$key'")

/**
 * What [PluginManager] asks before installing a package and before building any runtime for it
 * (ADR-0002 §10.1). Absent, the manager keeps its pre-S5 behaviour.
 */
public interface PluginExecutionTierGate {
    /** The trusted publisher a repository is pinned to, recorded on packages installed from it. */
    public suspend fun publisherForRepository(normalizedBaseUrl: String): String?

    public suspend fun requireInstallable(packageId: String, publisherId: String, artifactSha256: String)

    public suspend fun resolve(candidate: ExecutionTierCandidate): ExecutionTier
}

public class PublisherExecutionTierGate(
    private val publishers: PublisherTrustStore,
    private val resolver: ExecutionTierResolver,
) : PluginExecutionTierGate {
    override suspend fun publisherForRepository(normalizedBaseUrl: String): String? =
        publishers.publisherForRepository(normalizedBaseUrl)?.publisherId

    override suspend fun requireInstallable(packageId: String, publisherId: String, artifactSha256: String): Unit =
        resolver.requireInstallable(packageId, publisherId, artifactSha256)

    override suspend fun resolve(candidate: ExecutionTierCandidate): ExecutionTier = resolver.resolve(candidate)
}

/** The tier decision left this package inert: no runtime is built for it. */
public class PluginExecutionInertException(public val pluginId: String, public val reason: InertReason) :
    ScriptRuntimeUnavailableException("Plugin '$pluginId' cannot run: $reason")

/**
 * Picks the engine by what [PluginManager] decided: an environment carrying in-process provenance
 * (the reviewed official and ShuYue catalogues) runs in [inProcess]; everything else runs in
 * [sandbox]. Without a sandbox on this platform everything else is refused here, never handed to
 * the in-process engine, and the package stays inert.
 */
public class TieredScriptPluginRuntimeFactory(
    private val inProcess: ScriptPluginRuntimeFactory,
    private val sandbox: ScriptPluginRuntimeFactory?,
) : ScriptPluginRuntimeFactory {
    override suspend fun create(script: String, manifest: PluginManifest, environment: ScriptPluginEnvironment): ScriptPluginRuntime =
        engineFor(environment).create(script, manifest, environment)

    override suspend fun createForSource(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = engineFor(environment).createForSource(script, manifest, source, environment)

    private fun engineFor(environment: ScriptPluginEnvironment): ScriptPluginRuntimeFactory = when {
        environment.inProcessScriptProvenance != null -> inProcess
        sandbox != null -> sandbox
        else -> throw ScriptRuntimeUnavailableException("No plugin sandbox on this platform")
    }
}

/** What [ExtensionRepositoryClient.inspectRepositoryKey] found: shown to the user before trusting. */
public data class RepositoryKeyPresentation(
    val normalizedBaseUrl: String,
    /** As the repository names itself: unverified, display only. */
    val name: String,
    val key: RepositoryTrustRoot,
) {
    val displayFingerprint: String get() = PublisherFingerprint.display(key.fingerprint)
}

/**
 * The publisher-trust state the app manages as one unit: trusted keys, withdrawals, the block list,
 * and keeping them current from each publisher's signed documents (§8.3, §8.4).
 */
public class PluginPackageSecurity(
    public val publishers: PublisherTrustStore,
    public val revocations: PublisherRevocationStore,
    public val blocklist: LocalPluginBlocklist,
    private val client: ExtensionRepositoryClient,
) {
    /**
     * Applies the rotation, withdrawals and key revocation a publisher-pinned repository publishes,
     * in that order (a revoked key stops every later fetch). A missing, stale or invalid document is
     * ignored: it can only fail to change trust, never loosen it.
     *
     * [onChanged] runs whenever the durable state ended up different, even if this call is cancelled
     * part way (a later fetch timing out): the caller re-evaluates which packages may run then, so a
     * stored withdrawal never waits for another refresh to take a runtime down. True when it ran.
     */
    public suspend fun syncPublisherDocuments(normalizedBaseUrl: String, onChanged: suspend () -> Unit = {}): Boolean {
        val before = publishers.publisherForRepository(normalizedBaseUrl) ?: return false
        val withdrawnBefore = revocations.revocations(before.publisherId)
        var changed = false
        try {
            ignoringInvalid {
                client.fetchPublisherDocument(normalizedBaseUrl, PublisherTrustStore.ROTATION_DOCUMENT_ID)
                    ?.let { publishers.applyRotation(normalizedBaseUrl, it) }
            }
            ignoringInvalid {
                client.fetchPublisherDocument(normalizedBaseUrl, PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID)
                    ?.let { revocations.applyRevocations(normalizedBaseUrl, it) }
            }
            ignoringInvalid {
                client.fetchPublisherDocument(normalizedBaseUrl, PublisherTrustStore.KEY_REVOKED_DOCUMENT_ID)
                    ?.let { publishers.applyKeyRevocation(normalizedBaseUrl, it) }
            }
        } finally {
            withContext(NonCancellable) {
                changed = publishers.publisher(before.publisherId) != before ||
                    revocations.revocations(before.publisherId) != withdrawnBefore
                if (changed) onChanged()
            }
        }
        return changed
    }

    private suspend fun ignoringInvalid(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline mirror, an already-applied rotation, a replayed list: nothing to apply.
        }
    }
}
