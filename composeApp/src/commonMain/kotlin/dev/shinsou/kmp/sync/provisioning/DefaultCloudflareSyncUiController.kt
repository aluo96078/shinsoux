package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiState
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.RecoveryKitExport
import dev.shinsou.kmp.sync.v2.SyncDeploymentRequest
import dev.shinsou.kmp.sync.v2.SyncDeviceSummary
import dev.shinsou.kmp.sync.v2.SyncEnginePhase
import dev.shinsou.kmp.sync.v2.SyncEntityKey
import dev.shinsou.kmp.sync.v2.SyncPairingCandidate
import dev.shinsou.kmp.sync.v2.SyncAdminQuota
import dev.shinsou.kmp.sync.v2.PendingDeviceRevocation
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncShareRequest
import dev.shinsou.kmp.sync.v2.SYNC_PROTOCOL_VERSION
import dev.shinsou.kmp.sync.v2.SYNC_STATE_SCHEMA_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Production state machine for setup, invite, pairing, and device management. */
class DefaultCloudflareSyncUiController(
    private val scope: CoroutineScope,
    private val configuration: CloudflareProvisioningConfiguration,
    private val installationStore: SyncInstallationStore,
    private val sessionStore: SyncSessionStore,
    private val secretStore: SyncSecretStore,
    private val api: CloudflareProvisioningApi,
    private val provisioningCrypto: SyncProvisioningCrypto,
    private val activationGate: SyncProvisioningActivationGate,
    private val recoveryDelegate: SyncRecoveryUiDelegate? = null,
    private val nowMillis: () -> Long,
) : CloudflareSyncUiController {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(
        CloudflareSyncUiState(deploymentUrl = configuration.deployUrl),
    )
    private var pendingBootstrapSecret: EphemeralSyncPayload? = null
    private var pendingSetupMetadata: ParsedSyncOneTimePayload? = null
    private var pendingCandidate: PendingPairCandidate? = null
    private var candidatePollingJob: Job? = null
    private var sponsorPollingJob: Job? = null
    private val sponsorCandidateViews = mutableMapOf<String, ProvisioningPairingView>()

    override val state: StateFlow<CloudflareSyncUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val session = try {
                sessionStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Session metadata owns the provider switch. An unreadable/corrupt store must
                // never be presented as NOT_CONFIGURED, otherwise legacy sync could start a
                // second writer against an unknown Cloudflare session.
                publishFailure(SyncProvisioningException("sync_session_unavailable", failure))
                return@launch
            }
            if (session != null) {
                mutableState.value = mutableState.value.copy(
                    status = session.status,
                    endpoint = session.endpoint,
                    deviceDisplayName = session.deviceDisplayName,
                )
                if (session.status == SyncSessionStatus.READY) {
                    try {
                        // READY is the durable commit point. A crash between saving it and
                        // deleting an already-consumed one-time payload must not retain secrets.
                        secretStore.delete(SyncSecretKey.PendingBootstrapSecret)
                        secretStore.delete(SyncSecretKey.PendingInvitePayload)
                        secretStore.delete(SyncSecretKey.PendingPairingPayload)
                        if (session.pendingDeviceRevocation != null) {
                            operationMutex.withLock {
                                resumePendingDeviceRevocationLocked(session, lookupFirst = true)
                            }
                        } else {
                            refreshDevices()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        publishOperationFailure(failure)
                    }
                }
            }
            // A deployment has no SyncSession row until the setup link is claimed, but the
            // bootstrap secret is durable. Restore the visible state after process death so the
            // user can reopen the deployment page without generating a different secret.
            if (session == null) {
                try {
                    if (loadPendingBootstrapSecret() != null) {
                        mutableState.value = mutableState.value.copy(
                            status = SyncSessionStatus.DEPLOYING,
                            deviceDisplayName = configuration.deviceDisplayName,
                            busy = false,
                            diagnostic = null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    publishOperationFailure(failure)
                }
            }
            if (session?.status != SyncSessionStatus.READY) {
                try {
                    resumePendingProvisioning(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    publishOperationFailure(failure)
                }
            }
        }
        activationGate.engineState?.let { engineState ->
            scope.launch {
                engineState.collectLatest { engine ->
                    val current = mutableState.value
                    val engineFailureVisible = engine.diagnostic != null &&
                        engine.phase in SYNC_ENGINE_FAILURE_PHASES
                    mutableState.value = current.copy(
                        phase = engine.phase,
                        cursor = engine.cursor,
                        remoteHead = engine.remoteHead,
                        pendingDrafts = engine.draftCount,
                        pendingUploads = engine.outboxCount,
                        lastSuccessfulSyncAtMillis = engine.lastSuccessfulSyncAtMillis,
                        // Realtime delivery is best-effort and is deliberately not represented as
                        // a provisioning/sync failure. Only durable engine failure phases should
                        // replace the controller diagnostic; clear a stale generic error once the
                        // engine has recovered.
                        diagnostic = when {
                            engineFailureVisible -> SYNC_ENGINE_ERROR_DIAGNOSTIC
                            current.diagnostic == SYNC_ENGINE_ERROR_DIAGNOSTIC -> null
                            else -> current.diagnostic
                        },
                    )
                }
            }
        }
        activationGate.materializationDiagnostics?.let { diagnostics ->
            scope.launch {
                diagnostics.collectLatest { report ->
                    mutableState.value = mutableState.value.copy(
                        materializationIssues = report.issues,
                        repositoryTrustConfirmations = report.repositoryTrustConfirmations,
                    )
                }
            }
        }
    }

    override suspend fun beginDeployment(): SyncDeploymentRequest = action {
        if (sessionStore.load() != null) {
            throw SyncProvisioningException("sync_already_configured")
        }
        if (pendingSetupMetadata != null) {
            throw SyncProvisioningException("sync_setup_in_progress")
        }

        // Deployment is an external, multi-step flow. Reusing the durable secret makes the
        // button safe to tap again after a browser failure or process restart; generating a new
        // value here would leave the already-open Cloudflare page with an unusable secret.
        val existingSecret = pendingBootstrapSecret ?: loadPendingBootstrapSecret()
        val secret = existingSecret ?: provisioningCrypto.generateOneTimeSecret().also { generated ->
            generated.useSuspending { raw ->
                secretStore.write(
                    SyncSecretKey.PendingBootstrapSecret,
                    SecretMaterial(raw.encodeToByteArray().asList()),
                )
            }
        }
        pendingBootstrapSecret = secret
        mutableState.value = CloudflareSyncUiState(
            status = SyncSessionStatus.DEPLOYING,
            deploymentUrl = configuration.deployUrl,
            deviceDisplayName = configuration.deviceDisplayName,
            busy = true,
        )
        SyncDeploymentRequest(configuration.deployUrl, secret)
    }

    override suspend fun submitOneTimeLinkOrCode(value: String) = action {
        val trimmed = value.trim()
        val payload = if (pendingSetupMetadata != null && isBareSecret(trimmed)) {
            pendingSetupMetadata!!.copy(secret = EphemeralSyncPayload(trimmed))
        } else {
            SyncOneTimeLinkCodec.parse(trimmed)
        }
        when (payload.action) {
            SyncOneTimeAction.SETUP -> consumeSetup(payload)
            SyncOneTimeAction.INVITE -> consumeInitialClaim(payload, setup = false)
            SyncOneTimeAction.PAIR -> consumePairing(payload)
            SyncOneTimeAction.EMERGENCY_RESET -> consumeInitialClaim(
                payload,
                setup = false,
                emergencyReset = true,
            )
        }
    }

    override suspend fun createUserInvite(): SyncShareRequest = action {
        val session = requireReadySession()
        val invite = api.createInvite(session)
        val link = SyncOneTimeLinkCodec.encodeLink(
            ParsedSyncOneTimePayload(
                SyncOneTimeAction.INVITE,
                session.endpoint,
                session.instanceId,
                sessionId = null,
                secret = invite.secret,
            ),
        )
        SyncShareRequest("Shinsou X user invite", link, expiresAtMillis = invite.expiresAtMillis).also {
            mutableState.value = mutableState.value.copy(activeShare = it)
        }
    }

    override suspend fun createDevicePairing(): SyncShareRequest = action {
        val session = requireReadySession()
        val pairing = api.createPairing(session)
        val link = SyncOneTimeLinkCodec.encodeLink(
            ParsedSyncOneTimePayload(
                SyncOneTimeAction.PAIR,
                session.endpoint,
                session.instanceId,
                sessionId = pairing.pairingId,
                secret = pairing.secret,
            ),
        )
        val share = SyncShareRequest("Add a Shinsou X device", link, expiresAtMillis = pairing.expiresAtMillis)
        mutableState.value = mutableState.value.copy(activeShare = share)
        startSponsorPolling(session, pairing.pairingId, pairing.expiresAtMillis)
        share
    }

    override suspend fun approvePairing(pairingId: String, approved: Boolean) = action {
        val session = requireReadySession()
        val view = sponsorCandidateViews[pairingId]
            ?: api.pairingAsSponsor(session, pairingId).also { sponsorCandidateViews[pairingId] = it }
        val approval = if (approved) {
            provisioningCrypto.preparePairApproval(session, view)
        } else {
            ProvisioningPairApproval(approved = false)
        }
        try {
            api.approvePairing(session, pairingId, approval)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ambiguous: Throwable) {
            // The POST may have committed before its response was lost. Only the exact terminal
            // state for the same immutable transcript is accepted as reconciliation evidence.
            val reconciled = try {
                api.pairingAsSponsor(session, pairingId)
            } catch (_: Throwable) {
                throw ambiguous
            }
            if (!pairingApprovalWasCommitted(view, reconciled, approved)) throw ambiguous
        }
        sponsorCandidateViews.remove(pairingId)
        mutableState.value = mutableState.value.copy(
            pairingCandidates = mutableState.value.pairingCandidates.filterNot { it.pairingId == pairingId },
        )
        Unit
    }

    override suspend fun dismissShare() {
        operationMutex.withLock {
            mutableState.value = mutableState.value.copy(activeShare = null)
        }
    }

    override suspend fun syncNow() = action {
        requireReadySession()
        activationGate.syncNow()
    }

    override suspend fun retryMaterialization() = action {
        requireReadySession()
        activationGate.retryMaterialization()
    }

    override suspend fun repairIdentityCollision(key: SyncEntityKey) = action {
        requireReadySession()
        activationGate.repairIdentityCollision(key)
    }

    override suspend fun acceptRepositoryTrust(baseUrl: String, proposedFingerprint: String) = action {
        requireReadySession()
        activationGate.acceptRepositoryTrust(baseUrl, proposedFingerprint)
    }

    override suspend fun rejectRepositoryTrust(baseUrl: String, proposedFingerprint: String) = action {
        requireReadySession()
        activationGate.rejectRepositoryTrust(baseUrl, proposedFingerprint)
    }

    override suspend fun refreshDevices() = action {
        val session = requireReadySession()
        refreshDevicesLocked(session)
    }

    override suspend fun refreshAdminUsage() = action {
        refreshAdminUsageLocked(requireReadySession())
    }

    override suspend fun updateAdminQuota(quota: SyncAdminQuota) = action {
        val session = requireReadySession()
        if (mutableState.value.adminUsage == null) throw SyncProvisioningException("admin_required")
        mutableState.value = mutableState.value.copy(adminUsage = api.updateAdminQuota(session, quota))
    }

    override suspend fun revokeDevice(deviceId: String) = action {
        val session = requireReadySession()
        if (deviceId == session.deviceId) throw SyncProvisioningException("cannot_revoke_current_device")
        val pending = session.pendingDeviceRevocation
        if (pending != null && pending.targetDeviceId != deviceId) {
            throw SyncProvisioningException("device_revocation_already_pending")
        }
        val durable = if (pending == null) {
            session.copy(
                pendingDeviceRevocation = PendingDeviceRevocation(
                    provisioningCrypto.generateUuid(),
                    deviceId,
                ),
            ).also { sessionStore.save(it) }
        } else {
            session
        }
        resumePendingDeviceRevocationLocked(durable, lookupFirst = pending != null)
    }

    override suspend fun exportRecoveryKit(): RecoveryKitExport = action {
        val session = requireReadySession()
        RecoveryKitExport(
            suggestedFileName = "shinsou-recovery-${session.userId.take(8)}.txt",
            encoded = provisioningCrypto.exportRecoveryKit(session),
        )
    }

    override suspend fun importRecoveryKit(encoded: String) = action {
        val delegate = recoveryDelegate ?: throw SyncProvisioningException("recovery_controller_unavailable")
        val activation = delegate.recoverAndVerify(
            EphemeralSyncPayload(encoded),
            installationStore.loadOrCreate(),
            configuration.deviceDisplayName,
            configuration.platform,
        )
        applyRecoveredActivation(activation)
    }

    private suspend fun applyRecoveredActivation(activation: RecoveryUiActivation) {
        if (activation.readySession.status != SyncSessionStatus.READY) {
            throw SyncProvisioningException("recovery_activation_not_ready")
        }
        if (activation.readySession.pendingRecovery != null) {
            throw SyncProvisioningException("recovery_activation_still_pending")
        }
        sessionStore.save(activation.readySession)
        secretStore.delete(SyncSecretKey.PendingBootstrapSecret)
        secretStore.delete(SyncSecretKey.PendingInvitePayload)
        secretStore.delete(SyncSecretKey.PendingPairingPayload)
        mutableState.value = mutableState.value.copy(
            status = SyncSessionStatus.READY,
            endpoint = activation.readySession.endpoint,
            deviceDisplayName = activation.readySession.deviceDisplayName,
            activeShare = SyncShareRequest(
                title = "Save your replacement Shinsou X Recovery Kit",
                payload = activation.replacementKit,
                expiresAtMillis = Long.MAX_VALUE,
            ),
            diagnostic = null,
        )
        refreshDevicesLocked(activation.readySession)
    }

    override suspend fun leaveWorkspace() = action {
        candidatePollingJob?.cancel()
        sponsorPollingJob?.cancel()
        activationGate.leaveWorkspace()
        secretStore.delete(SyncSecretKey.PendingBootstrapSecret)
        secretStore.delete(SyncSecretKey.PendingInvitePayload)
        secretStore.delete(SyncSecretKey.PendingPairingPayload)
        sessionStore.clear()
        pendingBootstrapSecret = null
        pendingSetupMetadata = null
        pendingCandidate = null
        sponsorCandidateViews.clear()
        mutableState.value = CloudflareSyncUiState(deploymentUrl = configuration.deployUrl)
    }

    private suspend fun consumeSetup(payload: ParsedSyncOneTimePayload) {
        pendingSetupMetadata = payload.copy(secret = null)
        val secret = payload.secret ?: pendingBootstrapSecret ?: loadPendingBootstrapSecret()
        if (secret == null) {
            mutableState.value = mutableState.value.copy(
                status = SyncSessionStatus.LINKING,
                endpoint = payload.endpoint,
                deviceDisplayName = configuration.deviceDisplayName,
                diagnostic = "bootstrap_secret_required",
            )
            return
        }
        consumeInitialClaim(payload.copy(secret = secret), setup = true)
        pendingBootstrapSecret = null
        pendingSetupMetadata = null
    }

    private suspend fun consumeInitialClaim(
        payload: ParsedSyncOneTimePayload,
        setup: Boolean,
        emergencyReset: Boolean = false,
    ) {
        val secret = payload.secret ?: throw SyncProvisioningException("one_time_secret_required")
        val capabilities = api.capabilities(payload.endpoint)
        requireCompatible(capabilities, payload.instanceId)
        val installation = installationStore.loadOrCreate()
        var existing = sessionStore.load()
        if (emergencyReset) {
            val resetWorkspaceId = payload.workspaceId
                ?: throw SyncProvisioningException("emergency_reset_workspace_required")
            val resumable = existing?.status == SyncSessionStatus.LINKING &&
                existing.endpoint == payload.endpoint && existing.instanceId == capabilities.instanceId &&
                existing.userId == payload.userId && existing.workspaceId == resetWorkspaceId
            if (existing != null && !resumable) {
                activationGate.leaveWorkspace()
                sessionStore.clear()
                existing = null
            }
            if (!resumable) clearEmergencyIdentitySecrets(resetWorkspaceId)
        } else if (existing?.status == SyncSessionStatus.READY) {
            throw SyncProvisioningException("sync_already_configured")
        }
        if (!setup) persistPendingPayload(SyncSecretKey.PendingInvitePayload, payload)
        val linking = if (existing != null && existing.status == SyncSessionStatus.LINKING &&
            existing.endpoint == payload.endpoint && existing.instanceId == capabilities.instanceId &&
            (if (emergencyReset) {
                existing.userId == payload.userId && existing.workspaceId == payload.workspaceId
            } else {
                existing.deviceId == installation.deviceId
            })
        ) {
            existing
        } else {
            SyncSession(
                endpoint = payload.endpoint,
                instanceId = capabilities.instanceId,
                userId = if (emergencyReset) {
                    payload.userId ?: throw SyncProvisioningException("emergency_reset_user_required")
                } else {
                    provisioningCrypto.generateUuid()
                },
                workspaceId = if (emergencyReset) {
                    payload.workspaceId ?: throw SyncProvisioningException("emergency_reset_workspace_required")
                } else {
                    provisioningCrypto.generateUuid()
                },
                deviceId = if (emergencyReset) provisioningCrypto.generateUuid() else installation.deviceId,
                deviceDisplayName = configuration.deviceDisplayName,
                platform = configuration.platform,
                status = SyncSessionStatus.LINKING,
                deviceAuthEpoch = 1,
                membershipAuthEpoch = 1,
                activeKeyEpoch = 1,
            )
        }
        sessionStore.save(linking)
        mutableState.value = mutableState.value.copy(
            status = SyncSessionStatus.LINKING,
            endpoint = linking.endpoint,
            deviceDisplayName = linking.deviceDisplayName,
            diagnostic = null,
        )
        val prepared = provisioningCrypto.prepareInitialWorkspace(linking, configuration.userDisplayName, secret)
        val result = try {
            when {
                setup -> api.claimSetup(linking.endpoint, prepared.claim)
                emergencyReset -> api.claimEmergencyReset(
                    linking.endpoint,
                    payload.sessionId ?: throw SyncProvisioningException("emergency_reset_id_required"),
                    prepared.claim,
                )
                else -> api.redeemInvite(linking.endpoint, prepared.claim)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ambiguous: Throwable) {
            api.reconcileInitialClaim(linking) ?: throw ambiguous
        }
        if (result.instanceId != linking.instanceId || result.userId != linking.userId ||
            result.workspaceId != linking.workspaceId || result.deviceId != linking.deviceId || result.keyEpoch != 1
        ) throw SyncProvisioningException("initial_claim_receipt_mismatch")
        if (setup) {
            secretStore.delete(SyncSecretKey.PendingBootstrapSecret)
            pendingBootstrapSecret = null
        }
        val trust = ProvisioningTrustContext.InitialSelfAnchor(
            deviceId = linking.deviceId,
            signingPublicKey = prepared.claim.device.signingPublicKey,
            wrappingPublicKey = prepared.claim.device.wrappingPublicKey,
            recoverySigningPublicKey = prepared.claim.initialKeys.recoverySigningPublicKey,
        )
        activationGate.seedSnapshotAndVerifyInitialCheckpoint(linking, trust)
        val ready = linking.copy(status = SyncSessionStatus.READY)
        sessionStore.save(ready)
        secretStore.delete(SyncSecretKey.PendingInvitePayload)
        activationGate.syncNow()
        val recoveryShare = SyncShareRequest(
            title = if (emergencyReset) {
                "Save your replacement Shinsou X Recovery Kit"
            } else {
                "Save your Shinsou X Recovery Kit"
            },
            payload = prepared.recoveryKit,
            expiresAtMillis = Long.MAX_VALUE,
        )
        mutableState.value = mutableState.value.copy(
            status = SyncSessionStatus.READY,
            phase = SyncEnginePhase.READY,
            endpoint = ready.endpoint,
            deviceDisplayName = ready.deviceDisplayName,
            activeShare = recoveryShare,
            diagnostic = null,
        )
        refreshDevicesLocked(ready)
    }

    private suspend fun clearEmergencyIdentitySecrets(workspaceId: String) {
        secretStore.delete(SyncSecretKey.WorkspaceEpochKey(workspaceId, 1))
        secretStore.delete(SyncSecretKey.WorkspaceCapability(workspaceId))
        secretStore.delete(SyncSecretKey.AccessToken)
        secretStore.delete(SyncSecretKey.DeviceCredential)
        secretStore.delete(SyncSecretKey.DeviceSigningPrivateKey)
        secretStore.delete(SyncSecretKey.DeviceWrappingPrivateKey)
        secretStore.delete(SyncSecretKey.RecoverySigningPrivateKey)
        secretStore.delete(SyncSecretKey.RecoveryWrappingPrivateKey)
        secretStore.delete(SyncSecretKey.PendingRecoverySigningPrivateKey)
        secretStore.delete(SyncSecretKey.PendingRecoveryWrappingPrivateKey)
    }

    private suspend fun consumePairing(payload: ParsedSyncOneTimePayload) {
        val secret = payload.secret ?: throw SyncProvisioningException("pairing_secret_required")
        val pairingId = payload.sessionId ?: throw SyncProvisioningException("pairing_id_required")
        val capabilities = api.capabilities(payload.endpoint)
        requireCompatible(capabilities, payload.instanceId)
        persistPendingPayload(SyncSecretKey.PendingPairingPayload, payload)
        if (sessionStore.load()?.status == SyncSessionStatus.READY) {
            throw SyncProvisioningException("sync_already_configured")
        }
        val installation = installationStore.loadOrCreate()
        val input = provisioningCrypto.preparePairingCandidate(
            installation.deviceId,
            configuration.deviceDisplayName,
            configuration.platform,
            pairingId,
            secret,
        )
        var view = api.pairingAsCandidate(payload.endpoint, pairingId, secret)
        if (view.status == ProvisioningPairingStatus.OPEN) {
            view = api.submitPairingCandidate(payload.endpoint, input)
        }
        ensureOwnCandidate(view, input)
        val code = provisioningCrypto.confirmationCode(view)
        pendingCandidate = PendingPairCandidate(payload.endpoint, capabilities.instanceId, pairingId, secret)
        mutableState.value = mutableState.value.copy(
            status = SyncSessionStatus.LINKING,
            endpoint = payload.endpoint,
            deviceDisplayName = configuration.deviceDisplayName,
            activeShare = SyncShareRequest(
                title = "Confirm this code on your existing device",
                payload = EphemeralSyncPayload("shinsou-pair-confirmation:$code"),
                confirmationCode = code,
                expiresAtMillis = view.expiresAtMillis,
            ),
            diagnostic = null,
        )
        if (view.status == ProvisioningPairingStatus.APPROVED) {
            finishPairActivation(view, requireNotNull(pendingCandidate))
        } else {
            startCandidatePolling(requireNotNull(pendingCandidate), view.expiresAtMillis)
        }
    }

    private fun startCandidatePolling(pending: PendingPairCandidate, expiresAt: Long) {
        candidatePollingJob?.cancel()
        candidatePollingJob = scope.launch {
            while (nowMillis() < expiresAt) {
                delay(configuration.pairingPollMillis)
                val view = try {
                    api.pairingAsCandidate(pending.endpoint, pending.pairingId, pending.secret)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    continue
                }
                when (view.status) {
                    ProvisioningPairingStatus.APPROVED -> {
                        try {
                            operationMutex.withLock { finishPairActivation(view, pending) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            publishOperationFailure(failure)
                        }
                        return@launch
                    }
                    ProvisioningPairingStatus.CANCELLED -> {
                        publishFailure(SyncProvisioningException("pairing_cancelled"))
                        return@launch
                    }
                    else -> Unit
                }
            }
            publishFailure(SyncProvisioningException("pairing_expired"))
        }
    }

    private fun startSponsorPolling(session: SyncSession, pairingId: String, expiresAt: Long) {
        sponsorPollingJob?.cancel()
        sponsorPollingJob = scope.launch {
            while (nowMillis() < expiresAt) {
                delay(configuration.pairingPollMillis)
                val view = try {
                    api.pairingAsSponsor(session, pairingId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    continue
                }
                if (view.status == ProvisioningPairingStatus.CANDIDATE && view.candidate != null) {
                    val code = try {
                        provisioningCrypto.confirmationCode(view)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        publishOperationFailure(failure)
                        return@launch
                    }
                    val candidate = requireNotNull(view.candidate)
                    operationMutex.withLock {
                        sponsorCandidateViews[pairingId] = view
                        mutableState.value = mutableState.value.copy(
                            pairingCandidates = listOf(
                                SyncPairingCandidate(
                                    pairingId,
                                    candidate.displayName,
                                    candidate.platform,
                                    code,
                                    view.expiresAtMillis,
                                ),
                            ),
                        )
                    }
                    return@launch
                }
                if (view.status == ProvisioningPairingStatus.CANCELLED) return@launch
            }
        }
    }

    private suspend fun finishPairActivation(view: ProvisioningPairingView, pending: PendingPairCandidate) {
        val code = provisioningCrypto.confirmationCode(view)
        val linking = provisioningCrypto.installPairActivation(
            pending.endpoint,
            pending.instanceId,
            configuration.deviceDisplayName,
            configuration.platform,
            view,
        )
        sessionStore.save(linking)
        val approval = view.activation?.approval ?: throw SyncProvisioningException("pairing_approval_evidence_missing")
        activationGate.verifyPairedWorkspaceAndCatchUp(
            linking,
            ProvisioningTrustContext.PairingSponsorAnchor(
                view.sponsorDeviceId,
                view.sponsorSigningPublicKey,
                view.sponsorWrappingPublicKey,
                code,
                approval,
            ),
        )
        val ready = linking.copy(status = SyncSessionStatus.READY)
        sessionStore.save(ready)
        secretStore.delete(SyncSecretKey.PendingPairingPayload)
        pendingCandidate = null
        activationGate.syncNow()
        mutableState.value = mutableState.value.copy(
            status = SyncSessionStatus.READY,
            phase = SyncEnginePhase.READY,
            endpoint = ready.endpoint,
            deviceDisplayName = ready.deviceDisplayName,
            activeShare = null,
            diagnostic = null,
        )
        refreshDevicesLocked(ready)
    }

    private fun ensureOwnCandidate(
        view: ProvisioningPairingView,
        input: ProvisioningPairingCandidateInput,
    ) {
        val candidate = view.candidate ?: throw SyncProvisioningException("pairing_candidate_missing")
        val expected = input.device
        val expectedTokenHash = expected.deviceToken.use { token ->
            val bytes = decodeBase64Url(token)
            try {
                dev.shinsou.kmp.sync.network.encodeBase64Url(
                    dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives.sha256(bytes),
                )
            } finally {
                bytes.fill(0)
            }
        }
        if (candidate.deviceId != expected.deviceId || candidate.signingPublicKey != expected.signingPublicKey ||
            candidate.wrappingPublicKey != expected.wrappingPublicKey || candidate.tokenCommitment != expectedTokenHash
        ) throw SyncProvisioningException("pairing_candidate_replaced")
    }

    private suspend fun pairingApprovalWasCommitted(
        expected: ProvisioningPairingView,
        actual: ProvisioningPairingView,
        approved: Boolean,
    ): Boolean {
        val expectedStatus = if (approved) {
            ProvisioningPairingStatus.APPROVED
        } else {
            ProvisioningPairingStatus.CANCELLED
        }
        if (actual.status != expectedStatus || actual.pairingId != expected.pairingId ||
            actual.workspaceId != expected.workspaceId ||
            actual.sponsorDeviceId != expected.sponsorDeviceId ||
            actual.sponsorSigningPublicKey != expected.sponsorSigningPublicKey ||
            actual.sponsorWrappingPublicKey != expected.sponsorWrappingPublicKey ||
            actual.transcriptNonce != expected.transcriptNonce ||
            actual.expiresAtMillis != expected.expiresAtMillis || actual.candidate != expected.candidate
        ) {
            return false
        }
        // Recompute the complete transcript code so a server cannot reconcile a terminal status
        // while substituting any sponsor/candidate key or token commitment.
        return try {
            provisioningCrypto.confirmationCode(actual)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun refreshDevicesLocked(session: SyncSession) {
        mutableState.value = mutableState.value.copy(
            devices = api.listDevices(session).map { device ->
                SyncDeviceSummary(
                    device.deviceId,
                    device.displayName,
                    device.platform,
                    device.deviceId == session.deviceId,
                    device.status != "active",
                    device.lastSeenAtMillis,
                )
            },
        )
        refreshAdminUsageLocked(session)
    }

    private suspend fun resumePendingDeviceRevocationLocked(
        session: SyncSession,
        lookupFirst: Boolean,
    ) {
        val pending = session.pendingDeviceRevocation
            ?: throw SyncProvisioningException("device_revocation_resume_metadata_missing")
        val receipt = if (lookupFirst) {
            lookupDeviceRevocationAfterAmbiguous(session, pending.revocationId)
                ?: commitOrReconcileDeviceRevocation(session, pending)
        } else {
            commitOrReconcileDeviceRevocation(session, pending)
        }
        validateDeviceRevocationReceipt(session, pending, receipt)
        try {
            activationGate.reconcileDeviceRevocation(session, receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Throwable) {
            // Another valid device can win the single-proposer rotation lease after this receipt
            // was read. Re-read the exact permanent operation receipt once: if its dynamic
            // rotation coverage advanced, the runtime can verify/install that signed rotation
            // instead of leaving a completed revoke stranded until the next app launch.
            val refreshedReceipt = lookupDeviceRevocationAfterAmbiguous(session, pending.revocationId)
            if (refreshedReceipt == null || refreshedReceipt == receipt) throw firstFailure
            validateDeviceRevocationReceipt(session, pending, refreshedReceipt)
            activationGate.reconcileDeviceRevocation(session, refreshedReceipt)
        }

        // Rotation may have advanced the durable key epoch. Never overwrite it with the stale
        // pre-request session merely to clear the operation marker.
        val current = sessionStore.load()
            ?: throw SyncProvisioningException("device_revocation_session_disappeared")
        if (current.endpoint != session.endpoint || current.instanceId != session.instanceId ||
            current.userId != session.userId || current.workspaceId != session.workspaceId ||
            current.deviceId != session.deviceId || current.pendingDeviceRevocation != pending
        ) {
            throw SyncProvisioningException("device_revocation_session_changed")
        }
        val cleared = current.copy(pendingDeviceRevocation = null)
        sessionStore.save(cleared)
        refreshDevicesLocked(cleared)
    }

    private suspend fun commitOrReconcileDeviceRevocation(
        session: SyncSession,
        pending: PendingDeviceRevocation,
    ): ProvisioningDeviceRevocationReceipt {
        try {
            return api.revokeDevice(session, pending.targetDeviceId, pending.revocationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Throwable) {
            lookupDeviceRevocationAfterAmbiguous(session, pending.revocationId)?.let { return it }
            try {
                // The first request may have failed before reaching the Worker. A new control
                // nonce with the same signed operation id is safe and cannot revoke another target.
                return api.revokeDevice(session, pending.targetDeviceId, pending.revocationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: Throwable) {
                lookupDeviceRevocationAfterAmbiguous(session, pending.revocationId)?.let { return it }
                throw if (retryFailure is SyncProvisioningException) retryFailure
                else SyncProvisioningException("device_revocation_ambiguous", retryFailure)
            }
        }
    }

    private suspend fun lookupDeviceRevocationAfterAmbiguous(
        session: SyncSession,
        revocationId: String,
    ): ProvisioningDeviceRevocationReceipt? = try {
        api.deviceRevocationReceipt(session, revocationId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun validateDeviceRevocationReceipt(
        session: SyncSession,
        pending: PendingDeviceRevocation,
        receipt: ProvisioningDeviceRevocationReceipt,
    ) {
        if (receipt.revocationId != pending.revocationId ||
            receipt.actorDeviceId != session.deviceId ||
            receipt.revokedDeviceId != pending.targetDeviceId ||
            receipt.committedAtMillis <= 0 || receipt.workspaceBindings.isEmpty() ||
            receipt.workspaceBindings.map { it.workspaceId }.distinct().size != receipt.workspaceBindings.size ||
            receipt.workspaceBindings.none { it.workspaceId == session.workspaceId }
        ) throw SyncProvisioningException("device_revocation_receipt_mismatch")

        receipt.workspaceBindings.forEach { binding ->
            if (!binding.workspaceId.matches(REVOCATION_UUID) || binding.revokedAtKeyEpoch <= 0 ||
                binding.directoryEpochAfterRevocation <= 0 ||
                binding.currentActiveKeyEpoch < binding.revokedAtKeyEpoch
            ) throw SyncProvisioningException("device_revocation_binding_invalid")
            val covered = binding.currentActiveKeyEpoch > binding.revokedAtKeyEpoch
            if (covered) {
                if (binding.coveringRotationId?.matches(REVOCATION_UUID) != true ||
                    binding.coveringProposerDeviceId?.matches(REVOCATION_UUID) != true
                ) throw SyncProvisioningException("device_revocation_coverage_invalid")
            } else if (!binding.currentRotationRequired || binding.coveringRotationId != null ||
                binding.coveringProposerDeviceId != null
            ) {
                throw SyncProvisioningException("device_revocation_coverage_invalid")
            }
        }
    }

    private suspend fun refreshAdminUsageLocked(session: SyncSession) {
        val usage = try {
            api.adminUsage(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncProvisioningException) {
            if (failure.safeCode == "admin_required") {
                mutableState.value = mutableState.value.copy(adminUsage = null)
                return
            }
            throw failure
        }
        mutableState.value = mutableState.value.copy(adminUsage = usage)
    }

    private suspend fun requireReadySession(): SyncSession = sessionStore.load()
        ?.takeIf { it.status == SyncSessionStatus.READY }
        ?: throw SyncProvisioningException("sync_session_not_ready")

    private fun requireCompatible(capabilities: ProvisioningCapabilities, linkInstanceId: String?) {
        if (!capabilities.isCompatibleWith(
                protocolReaderVersion = SYNC_PROTOCOL_VERSION,
                protocolWriterVersion = SYNC_PROTOCOL_VERSION,
                schemaReaderVersion = SYNC_STATE_SCHEMA_VERSION,
                schemaWriterVersion = SYNC_STATE_SCHEMA_VERSION,
            )
        ) {
            throw SyncProvisioningException("sync_protocol_incompatible")
        }
        if (linkInstanceId != null && linkInstanceId != capabilities.instanceId) {
            throw SyncProvisioningException("sync_instance_mismatch")
        }
    }

    private fun isBareSecret(value: String): Boolean = runCatching {
        value.length in 32..512 && decodeBase64Url(value).size >= 24
    }.getOrDefault(false)

    private suspend fun loadPendingBootstrapSecret(): EphemeralSyncPayload? =
        when (val result = secretStore.read(SyncSecretKey.PendingBootstrapSecret)) {
            is SyncSecretReadResult.Available -> {
                var raw: String? = null
                result.material.useBytes { raw = it.decodeToString(throwOnInvalidSequence = true) }
                val value = requireNotNull(raw)
                if (!isBareSecret(value)) throw SyncProvisioningException("pending_bootstrap_secret_corrupt")
                EphemeralSyncPayload(value)
            }
            SyncSecretReadResult.Missing -> null
            is SyncSecretReadResult.Unavailable -> throw SyncProvisioningException("pending_bootstrap_secret_unavailable")
            is SyncSecretReadResult.Corrupt -> throw SyncProvisioningException("pending_bootstrap_secret_corrupt")
        }

    private suspend fun persistPendingPayload(key: SyncSecretKey, payload: ParsedSyncOneTimePayload) {
        val encoded = SyncOneTimeLinkCodec.encodeLink(payload)
        encoded.useSuspending { raw ->
            secretStore.write(key, SecretMaterial(raw.encodeToByteArray().asList()))
        }
        when (key) {
            SyncSecretKey.PendingInvitePayload -> secretStore.delete(SyncSecretKey.PendingPairingPayload)
            SyncSecretKey.PendingPairingPayload -> secretStore.delete(SyncSecretKey.PendingInvitePayload)
            else -> Unit
        }
    }

    private suspend fun resumePendingProvisioning(existingSession: SyncSession?) {
        val invite = readPendingPayload(SyncSecretKey.PendingInvitePayload)
        val pairing = readPendingPayload(SyncSecretKey.PendingPairingPayload)
        if (invite != null && pairing != null) throw SyncProvisioningException("multiple_pending_provisioning_flows")
        when {
            existingSession?.status == SyncSessionStatus.LINKING && existingSession.pendingRecovery != null -> {
                val delegate = recoveryDelegate
                    ?: throw SyncProvisioningException("recovery_controller_unavailable")
                val activation = delegate.resumePendingRecovery(existingSession)
                    ?: throw SyncProvisioningException("recovery_resume_metadata_missing")
                applyRecoveredActivation(activation)
            }
            pairing != null -> submitOneTimeLinkOrCode(pairing)
            invite != null -> submitOneTimeLinkOrCode(invite)
            existingSession?.status == SyncSessionStatus.LINKING -> resumeCommittedInitialSession(existingSession)
        }
    }

    private suspend fun resumeCommittedInitialSession(linking: SyncSession) {
        val reconciled = api.reconcileInitialClaim(linking) ?: return
        if (reconciled.instanceId != linking.instanceId || reconciled.userId != linking.userId ||
            reconciled.workspaceId != linking.workspaceId || reconciled.deviceId != linking.deviceId ||
            reconciled.keyEpoch != linking.activeKeyEpoch
        ) throw SyncProvisioningException("initial_claim_reconciliation_mismatch")
        activationGate.seedSnapshotAndVerifyInitialCheckpoint(
            linking,
            provisioningCrypto.initialTrustContext(linking),
        )
        val ready = linking.copy(status = SyncSessionStatus.READY)
        sessionStore.save(ready)
        secretStore.delete(SyncSecretKey.PendingInvitePayload)
        activationGate.syncNow()
        mutableState.value = mutableState.value.copy(
            status = SyncSessionStatus.READY,
            phase = SyncEnginePhase.READY,
            endpoint = ready.endpoint,
            deviceDisplayName = ready.deviceDisplayName,
            activeShare = SyncShareRequest(
                title = "Save your Shinsou X Recovery Kit",
                payload = provisioningCrypto.exportRecoveryKit(ready),
                expiresAtMillis = Long.MAX_VALUE,
            ),
            diagnostic = null,
        )
        refreshDevicesLocked(ready)
    }

    private suspend fun readPendingPayload(key: SyncSecretKey): String? =
        when (val result = secretStore.read(key)) {
            is SyncSecretReadResult.Available -> {
                var raw: String? = null
                result.material.useBytes { raw = it.decodeToString(throwOnInvalidSequence = true) }
                requireNotNull(raw).also { SyncOneTimeLinkCodec.parse(it) }
            }
            SyncSecretReadResult.Missing -> null
            is SyncSecretReadResult.Unavailable -> throw SyncProvisioningException("pending_provisioning_unavailable")
            is SyncSecretReadResult.Corrupt -> throw SyncProvisioningException("pending_provisioning_corrupt")
        }

    private suspend fun <T> action(block: suspend () -> T): T = operationMutex.withLock {
        mutableState.value = mutableState.value.copy(busy = true, diagnostic = null)
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publishOperationFailure(failure)
            if (failure is SyncProvisioningException) throw failure
            throw SyncProvisioningException("provisioning_failed", failure)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }

    /**
     * A control-plane request failing does not roll back a session that already crossed the
     * durable READY commit point. Keep that lifecycle state (and its controls) available while
     * surfacing the request diagnostic so the same action can be retried. Conversely, a durable
     * revocation wins over stale UI state, and an unreadable/non-ready session still fails closed.
     */
    private suspend fun publishOperationFailure(failure: Throwable) {
        val durableSession = try {
            sessionStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (durableSession?.status) {
            SyncSessionStatus.READY -> publishFailure(failure, durableSession)
            SyncSessionStatus.REVOKED -> {
                val code = (failure as? SyncProvisioningException)?.safeCode ?: "provisioning_failed"
                mutableState.value = mutableState.value.copy(
                    status = SyncSessionStatus.REVOKED,
                    endpoint = durableSession.endpoint,
                    deviceDisplayName = durableSession.deviceDisplayName,
                    phase = SyncEnginePhase.REVOKED,
                    busy = false,
                    diagnostic = code,
                )
            }
            else -> publishFailure(failure)
        }
    }

    private fun publishFailure(failure: Throwable, readySession: SyncSession? = null) {
        val code = (failure as? SyncProvisioningException)?.safeCode ?: "provisioning_failed"
        mutableState.value = if (readySession != null) {
            mutableState.value.copy(
                status = SyncSessionStatus.READY,
                endpoint = readySession.endpoint,
                deviceDisplayName = readySession.deviceDisplayName,
                busy = false,
                diagnostic = code,
            )
        } else {
            mutableState.value.copy(
                status = SyncSessionStatus.ERROR,
                phase = SyncEnginePhase.ERROR,
                busy = false,
                diagnostic = code,
            )
        }
    }

    private data class PendingPairCandidate(
        val endpoint: String,
        val instanceId: String,
        val pairingId: String,
        val secret: EphemeralSyncPayload,
    ) {
        override fun toString(): String =
            "PendingPairCandidate(endpoint=$endpoint, instanceId=$instanceId, pairingId=$pairingId, secret=REDACTED)"
    }
}

private const val SYNC_ENGINE_ERROR_DIAGNOSTIC = "sync_engine_error"

private val SYNC_ENGINE_FAILURE_PHASES = setOf(
    SyncEnginePhase.ERROR,
    SyncEnginePhase.RATE_LIMITED,
    SyncEnginePhase.QUOTA_EXCEEDED,
    SyncEnginePhase.KEY_ROTATION_REQUIRED,
    SyncEnginePhase.INCOMPATIBLE,
)

private val REVOCATION_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
