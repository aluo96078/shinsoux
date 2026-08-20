package dev.shinsou.kmp.sync

import dev.shinsou.kmp.backup.SnapshotBackupService
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.withoutPortableSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** Platform-neutral contract for one versioned snapshot file in a remote document store. */
interface SnapshotSyncTransport {
    val initialCapability: SnapshotSyncCapability

    suspend fun capability(): SnapshotSyncCapability

    /** Returns null when this account has never uploaded a Shinsou snapshot. */
    suspend fun readSnapshot(): String?

    /** Replaces the single remote snapshot atomically from the transport's point of view. */
    suspend fun writeSnapshot(encodedEnvelope: String)
}

@Serializable
enum class SnapshotSyncAvailability {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

@Serializable
data class SnapshotSyncCapability(
    val availability: SnapshotSyncAvailability = SnapshotSyncAvailability.CHECKING,
    val serviceName: String = "iCloud Drive",
    val detail: String = "Checking iCloud Drive availability…",
) {
    val available: Boolean get() = availability == SnapshotSyncAvailability.AVAILABLE
}

@Serializable
enum class SnapshotSyncPhase {
    IDLE,
    CHECKING,
    SYNCING,
    SUCCESS,
    ERROR,
    UNAVAILABLE,
}

@Serializable
enum class SnapshotSyncOutcome {
    NO_CHANGES,
    UPLOADED_LOCAL,
    MERGED_AND_UPLOADED,
    UNAVAILABLE,
    ERROR,
}

@Serializable
data class SnapshotSyncResult(
    val outcome: SnapshotSyncOutcome,
    val completedAt: Long,
    val localRevision: Long,
    val remoteRevision: Long? = null,
    val mergedRevision: Long = localRevision,
    val conflicts: List<MergeConflict> = emptyList(),
    val message: String,
) {
    val conflictCount: Int get() = conflicts.size
    val succeeded: Boolean
        get() = outcome != SnapshotSyncOutcome.ERROR && outcome != SnapshotSyncOutcome.UNAVAILABLE
}

data class SnapshotSyncState(
    val capability: SnapshotSyncCapability,
    val phase: SnapshotSyncPhase = SnapshotSyncPhase.IDLE,
    val lastResult: SnapshotSyncResult? = null,
)

/**
 * Pulls, deterministically merges and pushes a complete [dev.shinsou.kmp.backup.BackupEnvelope].
 *
 * Runtime status is intentionally kept outside [dev.shinsou.kmp.data.AppSnapshot]: persisting
 * "last sync" after every push would itself dirty the snapshot and create a foreground-sync loop.
 */
class SnapshotSyncController(
    private val repository: ShinsouRepository,
    private val transport: SnapshotSyncTransport,
    private val deviceId: String,
    private val appVersion: String = "1.0.0",
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val deviceIdProvider: (suspend () -> String)? = null,
    private val writerAllowed: suspend () -> Boolean = { true },
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(SnapshotSyncState(transport.initialCapability))

    val state: StateFlow<SnapshotSyncState> = mutableState.asStateFlow()

    suspend fun refreshCapability(): SnapshotSyncCapability = operationMutex.withLock {
        mutableState.value = mutableState.value.copy(phase = SnapshotSyncPhase.CHECKING)
        if (!legacyWriterAllowedFailClosed()) {
            val blocked = blockedCapability()
            mutableState.value = mutableState.value.copy(
                capability = blocked,
                phase = SnapshotSyncPhase.UNAVAILABLE,
            )
            return@withLock blocked
        }
        val capability = runCatching { transport.capability() }.getOrElse { error ->
            SnapshotSyncCapability(
                availability = SnapshotSyncAvailability.UNAVAILABLE,
                serviceName = transport.initialCapability.serviceName,
                detail = error.message ?: "The remote snapshot service is unavailable.",
            )
        }
        mutableState.value = mutableState.value.copy(
            capability = capability,
            phase = if (capability.available) SnapshotSyncPhase.IDLE else SnapshotSyncPhase.UNAVAILABLE,
        )
        capability
    }

    /** Never throws for ordinary transport/decoding failures; the returned result is UI-safe. */
    suspend fun sync(): SnapshotSyncResult = withContext(NonCancellable) {
        operationMutex.withLock {
            val startedWith = repository.currentSnapshot
            val completedAt = nowEpochMillis()
            if (!legacyWriterAllowedFailClosed()) {
                val blocked = blockedCapability()
                return@withLock finish(
                    capability = blocked,
                    phase = SnapshotSyncPhase.UNAVAILABLE,
                    result = SnapshotSyncResult(
                        outcome = SnapshotSyncOutcome.UNAVAILABLE,
                        completedAt = completedAt,
                        localRevision = startedWith.revision,
                        message = blocked.detail,
                    ),
                )
            }
            val capability = runCatching { transport.capability() }.getOrElse { error ->
                SnapshotSyncCapability(
                    availability = SnapshotSyncAvailability.UNAVAILABLE,
                    serviceName = transport.initialCapability.serviceName,
                    detail = error.message ?: "The remote snapshot service is unavailable.",
                )
            }
            if (!capability.available) {
                return@withLock finish(
                    capability = capability,
                    phase = SnapshotSyncPhase.UNAVAILABLE,
                    result = SnapshotSyncResult(
                        outcome = SnapshotSyncOutcome.UNAVAILABLE,
                        completedAt = completedAt,
                        localRevision = startedWith.revision,
                        message = capability.detail,
                    ),
                )
            }

            mutableState.value = mutableState.value.copy(
                capability = capability,
                phase = SnapshotSyncPhase.SYNCING,
            )
            try {
                val effectiveDeviceId = (deviceIdProvider?.invoke() ?: deviceId).also {
                    require(it.isNotBlank()) { "Snapshot sync device id is unavailable" }
                }
                val remotePayload = transport.readSnapshot()
                if (remotePayload == null) {
                    val localToUpload = repository.currentSnapshot
                    val envelope = SnapshotBackupService.create(
                        localToUpload,
                        completedAt,
                        appVersion,
                        effectiveDeviceId,
                    )
                    transport.writeSnapshot(SnapshotBackupService.encode(envelope))
                    return@withLock finish(
                        capability,
                        SnapshotSyncPhase.SUCCESS,
                        SnapshotSyncResult(
                            outcome = SnapshotSyncOutcome.UPLOADED_LOCAL,
                            completedAt = completedAt,
                            localRevision = localToUpload.revision,
                            mergedRevision = localToUpload.revision,
                            message = "Uploaded the first Shinsou X snapshot to ${capability.serviceName}.",
                        ),
                    )
                }

                val remoteEnvelope = SnapshotBackupService.decode(remotePayload)
                var localSnapshot = repository.currentSnapshot
                if (localSnapshot.withoutPortableSecrets() == remoteEnvelope.snapshot.withoutPortableSecrets()) {
                    return@withLock finish(
                        capability,
                        SnapshotSyncPhase.SUCCESS,
                        SnapshotSyncResult(
                            outcome = SnapshotSyncOutcome.NO_CHANGES,
                            completedAt = completedAt,
                            localRevision = localSnapshot.revision,
                            remoteRevision = remoteEnvelope.snapshot.revision,
                            mergedRevision = localSnapshot.revision,
                            message = "Local data already matches ${capability.serviceName}.",
                        ),
                    )
                }

                var merge: SnapshotMergeResult? = null
                var persisted: AppSnapshot? = null
                var attempts = 0
                while (persisted == null && attempts < MAX_LOCAL_MERGE_ATTEMPTS) {
                    if (localSnapshot.withoutPortableSecrets() == remoteEnvelope.snapshot.withoutPortableSecrets()) {
                        return@withLock finish(
                            capability,
                            SnapshotSyncPhase.SUCCESS,
                            SnapshotSyncResult(
                                outcome = SnapshotSyncOutcome.NO_CHANGES,
                                completedAt = completedAt,
                                localRevision = localSnapshot.revision,
                                remoteRevision = remoteEnvelope.snapshot.revision,
                                mergedRevision = localSnapshot.revision,
                                message = "Local data already matches ${capability.serviceName}.",
                            ),
                        )
                    }
                    val candidate = SnapshotConflictResolver.merge(
                        local = SnapshotReplica(
                            snapshot = localSnapshot,
                            modifiedAt = localSnapshot.revision,
                            deviceId = effectiveDeviceId,
                        ),
                        remote = SnapshotReplica(
                            snapshot = remoteEnvelope.snapshot,
                            modifiedAt = remoteEnvelope.snapshot.revision,
                            deviceId = remoteEnvelope.deviceId ?: "remote",
                        ),
                    )
                    persisted = repository.replaceSnapshotIfRevision(localSnapshot.revision, candidate.snapshot)
                    if (persisted != null) merge = candidate
                    else localSnapshot = repository.currentSnapshot
                    attempts++
                }
                val finalMerge = merge
                    ?: throw IllegalStateException("Local data kept changing while snapshots were being merged. Try syncing again.")
                val finalSnapshot = persisted
                    ?: throw IllegalStateException("The merged snapshot could not be persisted.")
                val mergedEnvelope = SnapshotBackupService.create(
                    snapshot = finalSnapshot,
                    createdAt = completedAt,
                    appVersion = appVersion,
                    deviceId = effectiveDeviceId,
                )
                transport.writeSnapshot(SnapshotBackupService.encode(mergedEnvelope))
                return@withLock finish(
                    capability,
                    SnapshotSyncPhase.SUCCESS,
                    SnapshotSyncResult(
                        outcome = SnapshotSyncOutcome.MERGED_AND_UPLOADED,
                        completedAt = completedAt,
                        localRevision = localSnapshot.revision,
                        remoteRevision = remoteEnvelope.snapshot.revision,
                        mergedRevision = finalSnapshot.revision,
                        conflicts = finalMerge.conflicts,
                        message = if (finalMerge.conflicts.isEmpty()) {
                            "Merged local and remote snapshots."
                        } else {
                            "Merged local and remote snapshots with ${finalMerge.conflicts.size} resolved conflicts."
                        },
                    ),
                )
            } catch (error: Throwable) {
                return@withLock finish(
                    capability,
                    SnapshotSyncPhase.ERROR,
                    SnapshotSyncResult(
                        outcome = SnapshotSyncOutcome.ERROR,
                        completedAt = completedAt,
                        localRevision = startedWith.revision,
                        message = error.message ?: "Snapshot sync failed.",
                    ),
                )
            }
        }
    }

    private fun finish(
        capability: SnapshotSyncCapability,
        phase: SnapshotSyncPhase,
        result: SnapshotSyncResult,
    ): SnapshotSyncResult {
        mutableState.value = SnapshotSyncState(capability, phase, result)
        return result
    }

    /** Metadata failures reserve the remote-writer slot rather than enabling two providers. */
    private suspend fun legacyWriterAllowedFailClosed(): Boolean = try {
        writerAllowed()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private fun blockedCapability(): SnapshotSyncCapability = SnapshotSyncCapability(
        availability = SnapshotSyncAvailability.UNAVAILABLE,
        serviceName = transport.initialCapability.serviceName,
        detail = "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync.",
    )

    private companion object {
        const val MAX_LOCAL_MERGE_ATTEMPTS = 3
    }
}

class UnavailableSnapshotSyncTransport(
    detail: String,
    serviceName: String = "iCloud Drive",
) : SnapshotSyncTransport {
    override val initialCapability = SnapshotSyncCapability(
        availability = SnapshotSyncAvailability.UNAVAILABLE,
        serviceName = serviceName,
        detail = detail,
    )

    override suspend fun capability(): SnapshotSyncCapability = initialCapability

    override suspend fun readSnapshot(): String? = null

    override suspend fun writeSnapshot(encodedEnvelope: String) = Unit
}
