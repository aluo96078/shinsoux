package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.pluginJsonArrayCountsAtMost
import dev.shinsou.kmp.plugin.pluginUtf8ByteCountAtMost
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
public data class PluginEventGrantReview(
    val artifact: PluginArtifactIdentity,
    val sourceKeys: List<SourceKey>,
    val requestedPermissions: Set<PluginHostPermission>,
    /** Sensitive runtime services shown and approved with this exact artifact review. */
    val requestedRuntimePermissions: Set<PluginRuntimePermission> = emptySet(),
)

@Serializable
private enum class PluginEventAdmissionState {
    PENDING,
    GRANTED,
    REVOKED,
}

/**
 * The only authoritative durable admission value for one exact artifact. Keeping the complete
 * review and its state in one value prevents a crash between host-event and runtime-permission
 * writes from assembling a grant which the user never approved as a unit.
 */
@Serializable
private data class PluginEventAdmissionRecord(
    val schemaVersion: Int = EVENT_ADMISSION_SCHEMA_VERSION,
    val artifact: PluginArtifactIdentity,
    val state: PluginEventAdmissionState,
    val review: PluginEventGrantReview? = null,
)

/** Decoder retained only for fail-closed migration of the former split grant representation. */
@Serializable
private data class LegacyPluginRuntimePermissionApproval(
    val artifact: PluginArtifactIdentity,
    val sourceKeys: List<SourceKey>,
    val permissions: Set<PluginRuntimePermission>,
)

/** Durable exact-digest admission store. Repository requests never become grants without approve(). */
public class KeyValuePluginEventGrantAdmission(
    private val store: PluginKeyValueStore,
    private val authorizer: MutablePluginSystemEventAuthorizer,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    private val mutex = Mutex()
    private val liveGrantKeys = linkedMapOf<PluginArtifactIdentity, Set<PluginEventGrantKey>>()
    /** A failed tombstone write must still prevent this process from rehydrating the old grant. */
    private val locallyRevokedArtifacts = linkedSetOf<PluginArtifactIdentity>()

    public suspend fun stage(review: PluginEventGrantReview): Unit = mutex.withLock {
        requireValidReview(review)
        stageLocked(review)
    }

    public suspend fun pending(artifact: PluginArtifactIdentity): PluginEventGrantReview? = mutex.withLock {
        val authoritative = readAuthoritativeRecord(artifact)
        if (authoritative.present) {
            if (authoritative.record?.state != PluginEventAdmissionState.GRANTED) {
                locallyRevokedArtifacts += artifact
                revokeLive(artifact, liveGrantKeys[artifact].orEmpty())
            }
            return@withLock authoritative.record
                ?.takeIf { it.state == PluginEventAdmissionState.PENDING }
                ?.review
        }

        // A legacy pending value is denial state, so it is safe to migrate on its own. Once the
        // v2 value exists, stale split keys can never become authoritative again.
        val legacyPending = readLegacyPending(artifact)
        if (!legacyPending.present) return@withLock null
        val review = legacyPending.review
        if (review == null) {
            revokeLocked(artifact)
            return@withLock null
        }
        stageLocked(review)
        review
    }

    public suspend fun isGranted(review: PluginEventGrantReview): Boolean = mutex.withLock {
        requireValidReview(review)
        isGrantedLocked(review)
    }

    public suspend fun approve(
        artifact: PluginArtifactIdentity,
        permissions: Set<PluginHostPermission>,
    ): Unit = mutex.withLock {
        val record = readAuthoritativeRecord(artifact)
        val review = when {
            record.present -> record.record
                ?.takeIf { it.state == PluginEventAdmissionState.PENDING }
                ?.review
            else -> readLegacyPending(artifact).review?.also { legacy ->
                // Establish one authoritative pending record before performing the approval
                // read/modify/write transition.
                stageLocked(legacy)
            }
        }
        if (review == null && record.present) {
            locallyRevokedArtifacts += artifact
            revokeLive(artifact, liveGrantKeys[artifact].orEmpty())
        }
        requireNotNull(review) { "No exact plugin event grant review is pending" }
        requireValidReview(review)
        require(review.artifact == artifact) { "Pending review artifact identity does not match approval" }
        require(permissions == review.requestedPermissions) {
            "Approval must match the verified artifact's requested permission set"
        }

        // This single put is the grant commit point. Nothing reaches the live authorizer first.
        writeRecord(grantedRecord(review))
        locallyRevokedArtifacts.remove(artifact)
        cleanupLegacyKeys(artifact)
        grantLive(review)
    }

    public suspend fun hydrate(review: PluginEventGrantReview): Unit = mutex.withLock {
        requireValidReview(review)
        if (isGrantedLocked(review)) {
            grantLive(review)
            return@withLock
        }

        // Commit a non-grant before replacing it with a fresh pending review. If either write
        // fails, the live authorizer is still revoked by revokeLocked and no partially-written
        // grant can be reconstructed on restart.
        revokeLocked(review.artifact)
        stageLocked(review)
    }

    public suspend fun revoke(artifact: PluginArtifactIdentity): Unit = mutex.withLock {
        revokeLocked(artifact)
    }

    private suspend fun stageLocked(review: PluginEventGrantReview) {
        locallyRevokedArtifacts += review.artifact
        val liveKeys = liveGrantKeys[review.artifact].orEmpty() + grantKeys(review)
        val durableKeys = runCatching { durableGrantKeys(review.artifact) }.getOrDefault(emptySet())
        var commitFailure: Throwable? = null
        try {
            writeRecord(pendingRecord(review))
        } catch (error: Throwable) {
            commitFailure = error
        } finally {
            // A failed pending commit must still withdraw any authority already live in this
            // process. The caller sees the persistence error and cannot treat staging as done.
            revokeLive(review.artifact, durableKeys + liveKeys)
        }
        commitFailure?.let { throw it }
        cleanupLegacyKeys(review.artifact)
    }

    private suspend fun isGrantedLocked(review: PluginEventGrantReview): Boolean {
        if (review.artifact in locallyRevokedArtifacts) {
            revokeLive(review.artifact, liveGrantKeys[review.artifact].orEmpty() + grantKeys(review))
            return false
        }

        val authoritative = readAuthoritativeRecord(review.artifact)
        if (authoritative.present) {
            val granted = authoritative.record?.let { record ->
                record.state == PluginEventAdmissionState.GRANTED && record.review == review
            } == true
            if (!granted) {
                revokeLive(review.artifact, liveGrantKeys[review.artifact].orEmpty() + grantKeys(review))
            }
            return granted
        }

        val legacyPending = readLegacyPending(review.artifact)
        if (legacyPending.present) {
            // Corrupt pending state is still unambiguously a non-grant. Replace it with the
            // current verified review instead of allowing complete-looking stale grant halves.
            stageLocked(legacyPending.review ?: review)
            return false
        }

        val legacy = readLegacyGrant(review.artifact)
        if (legacy.matches(review)) {
            // Both former halves must exactly describe the current verified review. The v2 put
            // precedes cleanup, so cleanup interruption can only leave ignored stale values.
            writeRecord(grantedRecord(review))
            cleanupLegacyKeys(review.artifact)
            return true
        }

        if (legacy.anyValuePresent) {
            // Permanently shadow an interrupted legacy approve/revoke. A missing half written
            // later must not turn this partially-revoked state back into authority.
            writeRecord(pendingRecord(review))
            cleanupLegacyKeys(review.artifact)
        }
        revokeLive(review.artifact, liveGrantKeys[review.artifact].orEmpty() + grantKeys(review))
        return false
    }

    private suspend fun revokeLocked(artifact: PluginArtifactIdentity) {
        locallyRevokedArtifacts += artifact
        val liveKeys = liveGrantKeys[artifact].orEmpty()
        val durableKeys = runCatching { durableGrantKeys(artifact) }.getOrDefault(emptySet())
        var commitFailure: Throwable? = null
        try {
            writeRecord(
                PluginEventAdmissionRecord(
                    artifact = artifact,
                    state = PluginEventAdmissionState.REVOKED,
                ),
            )
        } catch (error: Throwable) {
            commitFailure = error
        } finally {
            // Durable failure must never leave an already-hydrated permission live in memory.
            revokeLive(artifact, durableKeys + liveKeys)
        }
        commitFailure?.let { throw it }
        cleanupLegacyKeys(artifact)
    }

    private suspend fun durableGrantKeys(artifact: PluginArtifactIdentity): Set<PluginEventGrantKey> {
        val authoritative = readAuthoritativeRecord(artifact)
        if (authoritative.present) {
            return authoritative.record
                ?.takeIf { it.state == PluginEventAdmissionState.GRANTED }
                ?.review
                ?.sourceKeys
                ?.mapTo(linkedSetOf()) { PluginEventGrantKey(artifact, it) }
                .orEmpty()
        }
        return readLegacyGrant(artifact).eventGrants
            ?.mapTo(linkedSetOf()) { it.key }
            .orEmpty()
    }

    private fun grantLive(review: PluginEventGrantReview) {
        val keys = grantKeys(review)
        liveGrantKeys[review.artifact]
            .orEmpty()
            .filterNotTo(linkedSetOf()) { it in keys }
            .forEach(authorizer::revoke)
        keys.forEach { authorizer.grant(it, review.requestedPermissions) }
        liveGrantKeys[review.artifact] = keys
    }

    private fun grantKeys(review: PluginEventGrantReview): Set<PluginEventGrantKey> =
        review.sourceKeys.mapTo(linkedSetOf()) { PluginEventGrantKey(review.artifact, it) }

    private fun revokeLive(artifact: PluginArtifactIdentity, keys: Set<PluginEventGrantKey>) {
        keys.forEach(authorizer::revoke)
        liveGrantKeys.remove(artifact)
    }

    private suspend fun readAuthoritativeRecord(artifact: PluginArtifactIdentity): RecordRead {
        val encoded = store.getString(recordKey(artifact)) ?: return RecordRead(present = false, record = null)
        val record = decodeBounded(encoded, PluginEventAdmissionRecord.serializer())
            ?.takeIf { it.isValidFor(artifact) }
        return RecordRead(present = true, record = record)
    }

    private suspend fun readLegacyPending(artifact: PluginArtifactIdentity): LegacyPendingRead {
        val encoded = store.getString(legacyPendingKey(artifact))
            ?: return LegacyPendingRead(present = false, review = null)
        val review = decodeBounded(encoded, PluginEventGrantReview.serializer())
            ?.takeIf { it.artifact == artifact && isValidReview(it) }
        return LegacyPendingRead(present = true, review = review)
    }

    private suspend fun readLegacyGrant(artifact: PluginArtifactIdentity): LegacyGrantRead {
        val encodedEvents = store.getString(legacyGrantKey(artifact))
        val encodedRuntime = store.getString(legacyRuntimeGrantKey(artifact))
        val eventGrants = encodedEvents?.let {
            decodeBounded(it, ListSerializer(PluginEventGrant.serializer()))
                ?.takeIf { grants -> grants.size <= MAX_EVENT_GRANT_SOURCES }
        }
        val runtimeApproval = encodedRuntime?.let {
            decodeBounded(it, LegacyPluginRuntimePermissionApproval.serializer())
        }
        return LegacyGrantRead(
            anyValuePresent = encodedEvents != null || encodedRuntime != null,
            eventGrants = eventGrants,
            runtimeApproval = runtimeApproval,
        )
    }

    private fun LegacyGrantRead.matches(review: PluginEventGrantReview): Boolean {
        val grants = eventGrants ?: return false
        val runtime = runtimeApproval ?: return false
        if (runtime != LegacyPluginRuntimePermissionApproval(
                artifact = review.artifact,
                sourceKeys = review.sourceKeys,
                permissions = review.requestedRuntimePermissions,
            )
        ) {
            return false
        }
        return grants.size == review.sourceKeys.size &&
            grants.all {
                it.key.artifact == review.artifact &&
                    it.key.sourceKey != null &&
                    it.permissions == review.requestedPermissions
            } &&
            grants.mapNotNull { it.key.sourceKey }.toSet() == review.sourceKeys.toSet()
    }

    private fun PluginEventAdmissionRecord.isValidFor(expectedArtifact: PluginArtifactIdentity): Boolean {
        if (schemaVersion != EVENT_ADMISSION_SCHEMA_VERSION || artifact != expectedArtifact) return false
        return when (state) {
            PluginEventAdmissionState.PENDING,
            PluginEventAdmissionState.GRANTED,
            -> review?.let { it.artifact == artifact && isValidReview(it) } == true
            PluginEventAdmissionState.REVOKED -> review == null
        }
    }

    private fun isValidReview(review: PluginEventGrantReview): Boolean = runCatching {
        requireValidReview(review)
    }.isSuccess

    private fun requireValidReview(review: PluginEventGrantReview) {
        require(review.sourceKeys.isNotEmpty() && review.sourceKeys.size <= MAX_EVENT_GRANT_SOURCES) {
            "Plugin event grant source count is invalid"
        }
        require(review.sourceKeys.distinct().size == review.sourceKeys.size) {
            "Plugin event grant contains duplicate source identities"
        }
        require(review.sourceKeys.all { sourceKey ->
            sourceKey.packageId == review.artifact.packageId &&
                pluginUtf8ByteCountAtMost(sourceKey.sourceId, MAX_EVENT_SOURCE_ID_BYTES) != null
        }) { "Plugin event grant contains an invalid source identity" }
        require(PluginHostPermission.REPORT_USER_MESSAGE !in review.requestedPermissions) {
            "User-visible plugin messages are unsupported without a safe production presenter"
        }
    }

    private fun pendingRecord(review: PluginEventGrantReview): PluginEventAdmissionRecord =
        PluginEventAdmissionRecord(
            artifact = review.artifact,
            state = PluginEventAdmissionState.PENDING,
            review = review,
        )

    private fun grantedRecord(review: PluginEventGrantReview): PluginEventAdmissionRecord =
        PluginEventAdmissionRecord(
            artifact = review.artifact,
            state = PluginEventAdmissionState.GRANTED,
            review = review,
        )

    private suspend fun writeRecord(record: PluginEventAdmissionRecord) {
        val encoded = json.encodeToString(PluginEventAdmissionRecord.serializer(), record)
        check(pluginUtf8ByteCountAtMost(encoded, MAX_EVENT_ADMISSION_RECORD_BYTES) != null) {
            "Plugin event admission record exceeds its durable size limit"
        }
        val key = recordKey(record.artifact)
        store.putString(key, encoded)
        check(store.getString(key) == encoded) { "Could not verify plugin event admission write" }
    }

    private fun <T> decodeBounded(encoded: String, serializer: KSerializer<T>): T? {
        if (pluginUtf8ByteCountAtMost(encoded, MAX_EVENT_ADMISSION_RECORD_BYTES) == null) return null
        if (!pluginJsonArrayCountsAtMost(encoded, MAX_EVENT_GRANT_SOURCES)) return null
        return runCatching { json.decodeFromString(serializer, encoded) }.getOrNull()
    }

    private suspend fun cleanupLegacyKeys(artifact: PluginArtifactIdentity) {
        // Cleanup is not a security boundary after the authoritative record commit. A failure
        // must not roll back or hide that committed decision.
        runCatching { store.remove(legacyPendingKey(artifact)) }
        runCatching { store.remove(legacyGrantKey(artifact)) }
        runCatching { store.remove(legacyRuntimeGrantKey(artifact)) }
    }

    private fun recordKey(identity: PluginArtifactIdentity): String =
        "plugin.events.admission.v2.${identity.storageKey()}"
    private fun legacyPendingKey(identity: PluginArtifactIdentity): String =
        "plugin.events.pending.${identity.storageKey()}"
    private fun legacyGrantKey(identity: PluginArtifactIdentity): String =
        "plugin.events.grant.${identity.storageKey()}"
    private fun legacyRuntimeGrantKey(identity: PluginArtifactIdentity): String =
        "plugin.runtime-permissions.grant.${identity.storageKey()}"
    private fun PluginArtifactIdentity.storageKey(): String =
        "${packageId.length}:$packageId|${version.length}:$version|$versionCode|$sha256"

    private data class RecordRead(
        val present: Boolean,
        val record: PluginEventAdmissionRecord?,
    )

    private data class LegacyGrantRead(
        val anyValuePresent: Boolean,
        val eventGrants: List<PluginEventGrant>?,
        val runtimeApproval: LegacyPluginRuntimePermissionApproval?,
    )

    private data class LegacyPendingRead(
        val present: Boolean,
        val review: PluginEventGrantReview?,
    )
}

private const val EVENT_ADMISSION_SCHEMA_VERSION: Int = 2
private const val MAX_EVENT_GRANT_SOURCES: Int = 256
private const val MAX_EVENT_SOURCE_ID_BYTES: Int = 256
private const val MAX_EVENT_ADMISSION_RECORD_BYTES: Int = 256 * 1_024
