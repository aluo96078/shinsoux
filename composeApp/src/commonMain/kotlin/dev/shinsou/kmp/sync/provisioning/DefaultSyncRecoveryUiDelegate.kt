package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.persistence.SyncInstallationIdentity
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.trust.RecoveryKitPublicMetadata
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.PendingSyncRecovery
import dev.shinsou.kmp.sync.v2.RecoveryClaimReceipt
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceBinding
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncRecoveryCoordinator
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SYNC_PROTOCOL_VERSION
import dev.shinsou.kmp.sync.v2.SYNC_STATE_SCHEMA_VERSION
import kotlinx.coroutines.CancellationException

/**
 * Recovery UI boundary for the first-version one-private-workspace account model. The durable
 * session remains LINKING until the recovery attestation, full keyring catch-up, immediate epoch
 * rotation and post-rotation checkpoint have all crossed the runtime verification boundary.
 */
class DefaultSyncRecoveryUiDelegate(
    private val provisioningApi: CloudflareProvisioningApi,
    private val recoveryKitManager: RecoveryKitManager,
    private val recoveryCoordinator: SyncRecoveryCoordinator,
    private val sessionStore: SyncSessionStore,
    private val activationGate: SyncProvisioningActivationGate,
) : SyncRecoveryUiDelegate {
    override suspend fun recoverAndVerify(
        encodedKit: EphemeralSyncPayload,
        installation: SyncInstallationIdentity,
        deviceDisplayName: String,
        platform: String,
    ): RecoveryUiActivation {
        try {
            if (sessionStore.load() != null) {
                throw SyncProvisioningException("recovery_requires_empty_session")
            }
            val metadata = encodedKit.useSuspending { encoded ->
                recoveryKitManager.importAndInstall(
                    SecretMaterial(encoded.encodeToByteArray().asList()),
                )
            }
            val capabilities = provisioningApi.capabilities(metadata.endpoint)
            if (capabilities.instanceId != metadata.instanceId) {
                throw SyncProvisioningException("sync_instance_mismatch")
            }
            if (!capabilities.isCompatibleWith(
                    protocolReaderVersion = SYNC_PROTOCOL_VERSION,
                    protocolWriterVersion = SYNC_PROTOCOL_VERSION,
                    schemaReaderVersion = SYNC_STATE_SCHEMA_VERSION,
                    schemaWriterVersion = SYNC_STATE_SCHEMA_VERSION,
                )
            ) {
                throw SyncProvisioningException("sync_protocol_incompatible")
            }

            val prepared = recoveryCoordinator.prepare(
                metadata = metadata,
                deviceDisplayName = deviceDisplayName,
                platform = platform,
                deviceId = installation.deviceId,
            )
            if (prepared.challenge.workspaces.size != 1) {
                throw SyncProvisioningException("recovery_workspace_count_unsupported")
            }
            val challengedWorkspace = prepared.challenge.workspaces.single()
            val claimedWorkspace = prepared.request.workspaceEnvelopes.singleOrNull()
                ?.takeIf { it.workspaceId == challengedWorkspace.workspaceId }
                ?: throw SyncProvisioningException("recovery_workspace_claim_missing")
            val pendingRecovery = PendingSyncRecovery(
                recoverySigningPublicKey = metadata.recoverySigningPublicKey,
                replacementRecoverySigningPublicKey =
                    prepared.replacementKit.metadata.recoverySigningPublicKey,
                replacementRecoveryWrappingPublicKey =
                    prepared.replacementKit.metadata.recoveryWrappingPublicKey,
                replacementCreatedAtMillis = prepared.replacementKit.metadata.createdAt,
                deviceSigningPublicKey = prepared.request.device.signingPublicKeyBase64Url,
                deviceWrappingPublicKey = prepared.request.device.wrappingPublicKeyBase64Url,
                claimedKeyEpoch = claimedWorkspace.keyEpoch,
                claimedKeyCommitmentBase64Url = claimedWorkspace.keyCommitmentBase64Url,
            )
            val durableLinking = SyncSession(
                endpoint = metadata.endpoint,
                instanceId = metadata.instanceId,
                userId = metadata.userId,
                workspaceId = challengedWorkspace.workspaceId,
                deviceId = installation.deviceId,
                deviceDisplayName = deviceDisplayName,
                platform = platform,
                status = SyncSessionStatus.LINKING,
                deviceAuthEpoch = 0,
                membershipAuthEpoch = 0,
                activeKeyEpoch = challengedWorkspace.keyEpoch,
                pendingRecovery = pendingRecovery,
            )
            // Persist before the claim request. A lost response therefore cannot make the server
            // activate an identity that the client has forgotten how to reconcile.
            sessionStore.save(durableLinking)
            val completed = recoveryCoordinator.submit(prepared)
            return finishCommittedRecovery(
                linking = durableLinking,
                receipt = completed.receipt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncProvisioningException) {
            throw failure
        } catch (failure: Throwable) {
            throw SyncProvisioningException("recovery_activation_failed", failure)
        }
    }

    override suspend fun resumePendingRecovery(session: SyncSession): RecoveryUiActivation? {
        val pending = session.pendingRecovery ?: return null
        if (session.status != SyncSessionStatus.LINKING) {
            throw SyncProvisioningException("recovery_linking_session_mismatch")
        }
        return try {
            val receipt = provisioningApi.reconcileRecoveryClaim(session)
                ?: throw SyncProvisioningException("recovery_claim_not_committed")
            finishCommittedRecovery(session, receipt, pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncProvisioningException) {
            throw failure
        } catch (failure: Throwable) {
            throw SyncProvisioningException("recovery_reconciliation_failed", failure)
        }
    }

    private suspend fun finishCommittedRecovery(
        linking: SyncSession,
        receipt: RecoveryClaimReceipt,
        pending: PendingSyncRecovery = linking.pendingRecovery
            ?: throw SyncProvisioningException("recovery_resume_metadata_missing"),
    ): RecoveryUiActivation {
        val binding = validateReceipt(linking, pending, receipt)
        val replacementMetadata = RecoveryKitPublicMetadata(
            endpoint = linking.endpoint,
            instanceId = linking.instanceId,
            userId = linking.userId,
            recoverySigningPublicKey = pending.replacementRecoverySigningPublicKey,
            recoveryWrappingPublicKey = pending.replacementRecoveryWrappingPublicKey,
            createdAt = pending.replacementCreatedAtMillis,
        )
        // This is idempotent across a crash in key activation: a fully activated replacement has
        // no staged keys, while a partially cleaned duplicate is safely completed by the manager.
        recoveryKitManager.activateStagedReplacement()
        val replacementKit = recoveryKitManager.exportInstalled(replacementMetadata).toEphemeralPayload()

        if (linking.activeKeyEpoch !in binding.activeKeyEpoch..binding.activeKeyEpoch + 1) {
            throw SyncProvisioningException("recovery_rotation_epoch_mismatch")
        }
        if ((linking.deviceAuthEpoch != 0L && linking.deviceAuthEpoch != binding.deviceAuthEpoch) ||
            (linking.membershipAuthEpoch != 0L &&
                linking.membershipAuthEpoch != binding.membershipAuthEpoch)
        ) {
            throw SyncProvisioningException("recovery_workspace_binding_mismatch")
        }
        val boundLinking = linking.copy(
            deviceAuthEpoch = binding.deviceAuthEpoch,
            membershipAuthEpoch = binding.membershipAuthEpoch,
        )
        sessionStore.save(boundLinking)

        val trust = ProvisioningTrustContext.RecoveryAnchor(
            deviceId = boundLinking.deviceId,
            signingPublicKey = pending.deviceSigningPublicKey,
            wrappingPublicKey = pending.deviceWrappingPublicKey,
            // The directory recovery attestation was signed by the imported, now-replaced Kit.
            recoverySigningPublicKey = pending.recoverySigningPublicKey,
        )
        activationGate.verifyRecoveredWorkspaceAndCatchUp(boundLinking, trust)
        val caughtUp = requireMatchingLinkingSession(boundLinking)
        // Runtime treats this as an idempotent ensure operation. If a previous process already
        // persisted claimedKeyEpoch + 1, it verifies the replacement checkpoint without rotating
        // again; otherwise it performs the mandatory recovery rotation first.
        activationGate.rotateAfterRecovery(caughtUp)
        val rotated = requireMatchingLinkingSession(caughtUp)
        if (rotated.activeKeyEpoch != binding.activeKeyEpoch + 1) {
            throw SyncProvisioningException("recovery_rotation_not_committed")
        }

        val ready = rotated.copy(status = SyncSessionStatus.READY, pendingRecovery = null)
        sessionStore.save(ready)
        // Runtime attaches the mutation bridge only after READY is durable. This final pass also
        // observes the post-rotation checkpoint verified by rotateAfterRecovery.
        activationGate.syncNow()
        return RecoveryUiActivation(readySession = ready, replacementKit = replacementKit)
    }

    private fun validateReceipt(
        linking: SyncSession,
        pending: PendingSyncRecovery,
        receipt: RecoveryClaimReceipt,
    ): RecoveryWorkspaceBinding {
        if (receipt.userId != linking.userId || receipt.deviceId != linking.deviceId ||
            receipt.rotationRequiredWorkspaceIds.toSet() != setOf(linking.workspaceId)
        ) {
            throw SyncProvisioningException("recovery_claim_receipt_mismatch")
        }
        val binding = receipt.workspaceBindings.singleOrNull()
            ?.takeIf { it.workspaceId == linking.workspaceId }
            ?: throw SyncProvisioningException("recovery_workspace_binding_missing")
        if (binding.activeKeyEpoch != pending.claimedKeyEpoch) {
            throw SyncProvisioningException("recovery_workspace_binding_mismatch")
        }
        return binding
    }

    private suspend fun requireMatchingLinkingSession(expected: SyncSession): SyncSession {
        val current = sessionStore.load()
            ?: throw SyncProvisioningException("recovery_linking_session_missing")
        if (current.instanceId != expected.instanceId || current.userId != expected.userId ||
            current.workspaceId != expected.workspaceId || current.deviceId != expected.deviceId ||
            current.status != SyncSessionStatus.LINKING || current.pendingRecovery != expected.pendingRecovery
        ) {
            throw SyncProvisioningException("recovery_linking_session_mismatch")
        }
        return current
    }
}

private fun SecretMaterial.toEphemeralPayload(): EphemeralSyncPayload {
    var encoded: String? = null
    useBytes { bytes -> encoded = bytes.decodeToString(throwOnInvalidSequence = true) }
    return EphemeralSyncPayload(requireNotNull(encoded))
}
