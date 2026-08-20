package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.provisioning.ProvisioningRevocationWorkspaceBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShinsouSyncRuntimeRevocationTest {
    @Test
    fun uncoveredReceiptRotatesExactlyOnceFromRevokedEpoch() {
        assertEquals(
            RevocationReconciliationAction.ROTATE_LATEST_ONCE,
            revocationReconciliationAction(7, binding(revokedAt = 7, current = 7, required = true)),
        )
    }

    @Test
    fun coveredReceiptOnlyFinishesCheckpointWhenNoLaterRotationIsRequired() {
        assertEquals(
            RevocationReconciliationAction.VERIFY_COVERING_ROTATION,
            revocationReconciliationAction(7, coveredBinding(current = 8, required = false)),
        )
    }

    @Test
    fun coveredReceiptRotatesOnceFromLatestEpochWhenAnotherRevocationIsPending() {
        assertEquals(
            RevocationReconciliationAction.ROTATE_LATEST_ONCE,
            revocationReconciliationAction(8, coveredBinding(current = 8, required = true)),
        )
    }

    @Test
    fun rollbackAndLocalEpochOutsideReceiptFailClosed() {
        assertFailsWith<SyncInvariantViolation> {
            revocationReconciliationAction(6, binding(revokedAt = 7, current = 7, required = true))
        }
        assertFailsWith<SyncInvariantViolation> {
            revocationReconciliationAction(9, coveredBinding(current = 8, required = false))
        }
    }

    @Test
    fun uncoveredAndCoveredShapesRejectContradictoryEvidence() {
        assertFailsWith<SyncInvariantViolation> {
            revocationReconciliationAction(
                7,
                binding(revokedAt = 7, current = 7, required = false),
            )
        }
        assertFailsWith<SyncInvariantViolation> {
            revocationReconciliationAction(
                7,
                binding(revokedAt = 7, current = 8, required = false),
            )
        }
    }

    @Test
    fun installedRemoteChainMustBeContiguousAndMatchExactCoveringManifest() {
        val receipt = coveredBinding(current = 9, required = false)
        validateRevocationRotationEvidence(
            localEpochBeforeRefresh = 7,
            binding = receipt,
            installed = InstalledRemoteRotations(
                manifests = listOf(
                    manifest(ROTATION_ID, PROPOSER_ID, 7, 8),
                    manifest(SECOND_ROTATION_ID, SECOND_PROPOSER_ID, 8, 9),
                ),
                session = session(activeKeyEpoch = 9),
            ),
        )

        assertFailsWith<SyncInvariantViolation> {
            validateRevocationRotationEvidence(
                localEpochBeforeRefresh = 7,
                binding = receipt,
                installed = InstalledRemoteRotations(
                    manifests = listOf(
                        manifest(SECOND_ROTATION_ID, PROPOSER_ID, 7, 8),
                        manifest(ROTATION_ID, SECOND_PROPOSER_ID, 8, 9),
                    ),
                    session = session(activeKeyEpoch = 9),
                ),
            )
        }
        assertFailsWith<SyncInvariantViolation> {
            validateRevocationRotationEvidence(
                localEpochBeforeRefresh = 7,
                binding = receipt,
                installed = InstalledRemoteRotations(
                    manifests = listOf(manifest(ROTATION_ID, PROPOSER_ID, 8, 9)),
                    session = session(activeKeyEpoch = 9),
                ),
            )
        }
    }

    @Test
    fun receiptRaceToHigherServerEpochFailsBeforeSessionAdvance() {
        assertFailsWith<SyncInvariantViolation> {
            validateRevocationRotationEvidence(
                localEpochBeforeRefresh = 7,
                binding = coveredBinding(current = 8, required = false),
                installed = InstalledRemoteRotations(
                    manifests = listOf(
                        manifest(ROTATION_ID, PROPOSER_ID, 7, 8),
                        manifest(SECOND_ROTATION_ID, SECOND_PROPOSER_ID, 8, 9),
                    ),
                    session = session(activeKeyEpoch = 9),
                ),
            )
        }
    }

    @Test
    fun survivingDeviceLeaseRaceWaitsForWinnerAndNeverRotatesAfterGateClears() {
        assertEquals(
            RequiredRotationRaceAction.WAIT_FOR_WINNER,
            requiredRotationRaceAction(attemptedEpoch = 7, refreshedEpoch = 7, rotationRequired = true),
        )
        assertEquals(
            RequiredRotationRaceAction.WINNER_COMPLETED,
            requiredRotationRaceAction(attemptedEpoch = 7, refreshedEpoch = 8, rotationRequired = false),
        )
        assertEquals(
            RequiredRotationRaceAction.RETRY_LATEST_REQUIRED,
            requiredRotationRaceAction(attemptedEpoch = 7, refreshedEpoch = 8, rotationRequired = true),
        )
        assertFailsWith<SyncInvariantViolation> {
            requiredRotationRaceAction(attemptedEpoch = 8, refreshedEpoch = 7, rotationRequired = false)
        }
        assertFailsWith<SyncInvariantViolation> {
            requiredRotationRaceAction(attemptedEpoch = 8, refreshedEpoch = 8, rotationRequired = false)
        }
    }

    private fun coveredBinding(current: Int, required: Boolean) = binding(
        revokedAt = 7,
        current = current,
        required = required,
        rotationId = ROTATION_ID,
        proposerId = PROPOSER_ID,
    )

    private fun binding(
        revokedAt: Int,
        current: Int,
        required: Boolean,
        rotationId: String? = null,
        proposerId: String? = null,
    ) = ProvisioningRevocationWorkspaceBinding(
        workspaceId = WORKSPACE_ID,
        revokedAtKeyEpoch = revokedAt,
        directoryEpochAfterRevocation = 4,
        currentActiveKeyEpoch = current,
        currentRotationRequired = required,
        coveringRotationId = rotationId,
        coveringProposerDeviceId = proposerId,
    )

    private fun manifest(
        rotationId: String,
        proposerId: String,
        fromEpoch: Int,
        toEpoch: Int,
    ) = KeyRotationManifest(
        rotationId = rotationId,
        workspaceId = WORKSPACE_ID,
        fromEpoch = fromEpoch,
        toEpoch = toEpoch,
        proposerDeviceId = proposerId,
        proposerDeviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        keyCommitmentBase64Url = "commitment",
        recipientEnvelopeHashes = mapOf(DEVICE_ID to "envelope"),
        recipientAuthEpochs = mapOf(DEVICE_ID to 1),
        recoveryEnvelopeHashBase64Url = "recovery",
        recoveryAuthEpoch = 1,
        expiresAtMillis = 10_000,
    )

    private fun session(activeKeyEpoch: Int) = SyncSession(
        endpoint = "http://localhost:8787",
        instanceId = INSTANCE_ID,
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        deviceId = DEVICE_ID,
        deviceDisplayName = "Test",
        platform = "other",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = activeKeyEpoch,
    )

    private companion object {
        const val INSTANCE_ID = "10000000-0000-4000-8000-000000000001"
        const val USER_ID = "20000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "30000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "40000000-0000-4000-8000-000000000004"
        const val ROTATION_ID = "50000000-0000-4000-8000-000000000005"
        const val PROPOSER_ID = "60000000-0000-4000-8000-000000000006"
        const val SECOND_ROTATION_ID = "70000000-0000-4000-8000-000000000007"
        const val SECOND_PROPOSER_ID = "80000000-0000-4000-8000-000000000008"
    }
}
