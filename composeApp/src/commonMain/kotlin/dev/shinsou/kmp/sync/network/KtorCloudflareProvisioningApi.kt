package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.provisioning.CloudflareProvisioningApi
import dev.shinsou.kmp.sync.provisioning.InitialWorkspaceClaim
import dev.shinsou.kmp.sync.provisioning.InitialWorkspaceClaimResult
import dev.shinsou.kmp.sync.provisioning.ProvisioningCapabilities
import dev.shinsou.kmp.sync.provisioning.ProvisioningDevice
import dev.shinsou.kmp.sync.provisioning.ProvisioningInvite
import dev.shinsou.kmp.sync.provisioning.ProvisioningKeyEnvelope
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairActivation
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairApproval
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairApprovalEvidence
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairKeyRequirements
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairing
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairingCandidate
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairingCandidateInput
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairingStatus
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairingView
import dev.shinsou.kmp.sync.provisioning.ProvisioningRetainedCheckpoint
import dev.shinsou.kmp.sync.provisioning.ProvisioningDeviceRevocationReceipt
import dev.shinsou.kmp.sync.provisioning.ProvisioningRevocationWorkspaceBinding
import dev.shinsou.kmp.sync.provisioning.SyncProvisioningException
import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.RecoveryClaimReceipt
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceBinding
import dev.shinsou.kmp.sync.v2.SyncCrypto
import dev.shinsou.kmp.sync.v2.SyncAdminDailyUsage
import dev.shinsou.kmp.sync.v2.SyncAdminQuota
import dev.shinsou.kmp.sync.v2.SyncAdminUsage
import dev.shinsou.kmp.sync.v2.SyncAdminUsageTotals
import dev.shinsou.kmp.sync.v2.SyncAdminWorkspaceUsage
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.requireSecret
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Ktor implementation of the Worker's identity and provisioning control plane. */
class KtorCloudflareProvisioningApi(
    private val client: HttpClient,
    private val secretStore: SyncSecretStore,
    private val crypto: SyncCrypto,
    private val nowMillis: () -> Long,
    private val json: Json = SyncNetworkJson,
) : CloudflareProvisioningApi {
    override suspend fun capabilities(endpoint: String): ProvisioningCapabilities {
        val response = client.get(workerUrl(endpoint, "/v1/capabilities")) {
            accept(ContentType.Application.Json)
        }.decodeSuccess<ProvisioningCapabilitiesDto>()
        return ProvisioningCapabilities(
            instanceId = response.instanceId.requireUuid("instance_id"),
            protocolVersion = response.protocolVersion,
            minReaderVersion = response.minReaderVersion,
            minWriterVersion = response.minWriterVersion,
            schemaVersion = response.schemaVersion,
            minSchemaReaderVersion = response.minSchemaReaderVersion,
            minSchemaWriterVersion = response.minSchemaWriterVersion,
            realtime = response.websocket && response.profile == "realtime",
        )
    }

    override suspend fun claimSetup(
        endpoint: String,
        claim: InitialWorkspaceClaim,
    ): InitialWorkspaceClaimResult = claimInitial(endpoint, claim, InitialClaimKind.SETUP)

    override suspend fun redeemInvite(
        endpoint: String,
        claim: InitialWorkspaceClaim,
    ): InitialWorkspaceClaimResult = claimInitial(endpoint, claim, InitialClaimKind.INVITE)

    override suspend fun claimEmergencyReset(
        endpoint: String,
        resetId: String,
        claim: InitialWorkspaceClaim,
    ): InitialWorkspaceClaimResult = claimInitial(
        endpoint,
        claim,
        InitialClaimKind.EMERGENCY_RESET,
        resetId.requireUuid("reset_id"),
    )

    override suspend fun reconcileInitialClaim(session: SyncSession): InitialWorkspaceClaimResult? {
        val access = try {
            authenticate(session)
        } catch (failure: SyncProvisioningException) {
            if (failure.safeCode in AUTH_NOT_COMMITTED_CODES) return null
            throw failure
        }
        val capability = client.post(workerUrl(session.endpoint, "/v1/auth/capability")) {
            bearerAuth(access.accessToken)
            jsonBody(ProvisioningCapabilityRequest(session.workspaceId))
        }.decodeSuccess<ProvisioningCapabilityResponse>()
        if (capability.workspaceId != session.workspaceId || capability.deviceId != session.deviceId) {
            throw SyncProvisioningException("initial_claim_capability_mismatch")
        }
        val bootstrap = client.get(
            workerUrl(session.endpoint, "/v1/workspaces/${session.workspaceId}/bootstrap"),
        ) {
            bearerAuth(capability.capability)
            accept(ContentType.Application.Json)
        }.decodeSuccess<ProvisioningBootstrapProof>()
        if (bootstrap.workspaceId != session.workspaceId || bootstrap.activeKeyEpoch != capability.keyEpoch) {
            throw SyncProvisioningException("initial_claim_bootstrap_mismatch")
        }
        return InitialWorkspaceClaimResult(
            session.instanceId,
            session.userId,
            session.workspaceId,
            session.deviceId,
            capability.keyEpoch,
        )
    }

    override suspend fun reconcileRecoveryClaim(session: SyncSession): RecoveryClaimReceipt? {
        val pending = session.pendingRecovery
            ?: throw SyncProvisioningException("recovery_resume_metadata_missing")
        val access = try {
            authenticate(session)
        } catch (failure: SyncProvisioningException) {
            if (failure.safeCode in AUTH_NOT_COMMITTED_CODES) return null
            throw failure
        }
        val response = client.get(workerUrl(session.endpoint, "/v1/recovery/claim")) {
            bearerAuth(access.accessToken)
            accept(ContentType.Application.Json)
        }
        if (response.status.value == 404) return null
        val receipt = response.decodeSuccess<RecoveryClaimReconciliationResponse>()
        if (receipt.userId != session.userId || receipt.deviceId != session.deviceId ||
            receipt.newRecoverySigningPublicKey != pending.replacementRecoverySigningPublicKey ||
            receipt.newRecoveryWrappingPublicKey != pending.replacementRecoveryWrappingPublicKey
        ) {
            throw SyncProvisioningException("recovery_claim_reconciliation_mismatch")
        }
        return RecoveryClaimReceipt(
            claimId = receipt.claimId.requireUuid("claim_id"),
            userId = receipt.userId.requireUuid("user_id"),
            deviceId = receipt.deviceId.requireUuid("device_id"),
            rotationRequiredWorkspaceIds = receipt.rotationRequiredWorkspaces.map {
                it.requireUuid("workspace_id")
            },
            workspaceBindings = receipt.workspaceBindings.map {
                RecoveryWorkspaceBinding(
                    workspaceId = it.workspaceId.requireUuid("workspace_id"),
                    deviceAuthEpoch = it.deviceAuthEpoch,
                    membershipAuthEpoch = it.membershipAuthEpoch,
                    activeKeyEpoch = it.activeKeyEpoch,
                )
            },
        )
    }

    override suspend fun createInvite(session: SyncSession, ttlSeconds: Int): ProvisioningInvite {
        require(ttlSeconds in 300..7 * 86_400)
        val response = controlPost<CreateInviteRequest, CreateInviteResponse>(
            session,
            "/v1/admin/invites",
            CreateInviteRequest(ttlSeconds),
        )
        return ProvisioningInvite(
            inviteId = response.inviteId.requireUuid("invite_id"),
            secret = EphemeralSyncPayload(response.inviteSecret.requireOpaqueSecret()),
            expiresAtMillis = response.expiresAt,
        )
    }

    override suspend fun createPairing(session: SyncSession): ProvisioningPairing {
        val response = controlPost<CreatePairingRequest, CreatePairingResponse>(
            session,
            "/v1/pairings",
            CreatePairingRequest(session.workspaceId),
        )
        return ProvisioningPairing(
            pairingId = response.pairingId.requireUuid("pairing_id"),
            secret = EphemeralSyncPayload(response.pairingSecret.requireOpaqueSecret()),
            transcriptNonce = response.transcriptNonce.requireOpaqueSecret(),
            expiresAtMillis = response.expiresAt,
        )
    }

    override suspend fun submitPairingCandidate(
        endpoint: String,
        candidate: ProvisioningPairingCandidateInput,
    ): ProvisioningPairingView {
        val path = "/v1/pairings/${candidate.pairingId.requireUuid("pairing_id")}/candidate"
        val body = candidate.secret.use { pairingSecret ->
            candidate.device.deviceToken.use { deviceToken ->
                SubmitPairingCandidateRequest(
                    pairingId = candidate.pairingId,
                    pairingSecret = pairingSecret,
                    device = candidate.device.toDto(deviceToken),
                )
            }
        }
        client.post(workerUrl(endpoint, path)) { jsonBody(body) }.decodeSuccess<SubmitPairingCandidateResponse>()
        return pairingAsCandidate(endpoint, candidate.pairingId, candidate.secret)
    }

    override suspend fun pairingAsCandidate(
        endpoint: String,
        pairingId: String,
        secret: EphemeralSyncPayload,
    ): ProvisioningPairingView = secret.useSuspending { pairingSecret ->
        client.get(workerUrl(endpoint, "/v1/pairings/${pairingId.requireUuid("pairing_id")}")) {
            header(PAIRING_SECRET_HEADER, pairingSecret.requireOpaqueSecret())
            accept(ContentType.Application.Json)
        }.decodeSuccess<PairingViewDto>().toDomain()
    }

    override suspend fun pairingAsSponsor(session: SyncSession, pairingId: String): ProvisioningPairingView {
        val access = authenticate(session)
        return client.get(workerUrl(session.endpoint, "/v1/pairings/${pairingId.requireUuid("pairing_id")}")) {
            bearerAuth(access.accessToken)
            accept(ContentType.Application.Json)
        }.decodeSuccess<PairingViewDto>().toDomain().also { view ->
            if (view.sponsorDeviceId != session.deviceId || view.keyRequirements == null) {
                throw SyncProvisioningException("pairing_sponsor_binding_mismatch")
            }
        }
    }

    override suspend fun approvePairing(
        session: SyncSession,
        pairingId: String,
        approval: ProvisioningPairApproval,
    ) {
        val response = controlPost<PairApprovalRequest, PairApprovalResponse>(
            session,
            "/v1/pairings/${pairingId.requireUuid("pairing_id")}/approve",
            PairApprovalRequest(
                approved = approval.approved,
                keyEnvelopes = approval.envelopes.map(ProvisioningKeyEnvelope::toDto),
                approvalSignature = approval.approvalSignature,
            ),
        )
        val expected = if (approval.approved) "approved" else "cancelled"
        if (response.pairingId != pairingId || response.status != expected) {
            throw SyncProvisioningException("pairing_approval_receipt_mismatch")
        }
    }

    override suspend fun listDevices(session: SyncSession): List<ProvisioningDevice> {
        val access = authenticate(session)
        val response = client.get(workerUrl(session.endpoint, "/v1/devices")) {
            bearerAuth(access.accessToken)
            accept(ContentType.Application.Json)
        }.decodeSuccess<DeviceListResponse>()
        return response.devices.map { device ->
            ProvisioningDevice(
                deviceId = device.deviceId.requireUuid("device_id"),
                displayName = device.displayName,
                platform = device.platform,
                status = device.status,
                lastSeenAtMillis = device.lastSeenAt,
            )
        }
    }

    override suspend fun adminUsage(session: SyncSession): SyncAdminUsage {
        val access = authenticate(session)
        return client.get(workerUrl(session.endpoint, "/v1/admin/usage")) {
            bearerAuth(access.accessToken)
            accept(ContentType.Application.Json)
        }.decodeSuccess<AdminUsageResponse>().toDomain()
    }

    override suspend fun updateAdminQuota(session: SyncSession, quota: SyncAdminQuota): SyncAdminUsage {
        controlPut<UpdateAdminQuotaRequest, UpdateAdminQuotaResponse>(
            session,
            "/v1/admin/quota",
            UpdateAdminQuotaRequest(
                quota.maxUsers,
                quota.maxWorkspacesPerUser,
                quota.maxDevicesPerUser,
                quota.maxWorkspaceBytes,
                quota.maxEventBytes,
                quota.maxCheckpointBytes,
            ),
        )
        // Re-read all counters so the UI never combines a new limit with stale usage metadata.
        return adminUsage(session)
    }

    override suspend fun revokeDevice(
        session: SyncSession,
        deviceId: String,
        revocationId: String,
    ): ProvisioningDeviceRevocationReceipt {
        val canonicalDeviceId = deviceId.requireUuid("device_id")
        val canonicalRevocationId = revocationId.requireUuid("revocation_id")
        val response = controlPost<RevokeDeviceRequest, DeviceRevocationReceiptDto>(
            session,
            "/v1/devices/$canonicalDeviceId/revoke",
            RevokeDeviceRequest(canonicalRevocationId),
        )
        return response.toDomain()
    }

    override suspend fun deviceRevocationReceipt(
        session: SyncSession,
        revocationId: String,
    ): ProvisioningDeviceRevocationReceipt? {
        val access = authenticate(session)
        val response = client.get(
            workerUrl(session.endpoint, "/v1/device-revocations/${revocationId.requireUuid("revocation_id")}"),
        ) {
            bearerAuth(access.accessToken)
            accept(ContentType.Application.Json)
        }
        if (response.status.value == 404) return null
        return response.decodeSuccess<DeviceRevocationReceiptDto>().toDomain()
    }

    private suspend fun claimInitial(
        endpoint: String,
        claim: InitialWorkspaceClaim,
        kind: InitialClaimKind,
        resetId: String? = null,
    ): InitialWorkspaceClaimResult {
        val request = claim.bootstrapOrInviteSecret.use { oneTimeSecret ->
            claim.device.deviceToken.use { deviceToken ->
                InitialClaimRequest(
                    bootstrapSecret = oneTimeSecret.takeIf { kind == InitialClaimKind.SETUP },
                    inviteSecret = oneTimeSecret.takeIf { kind == InitialClaimKind.INVITE },
                    handoffSecret = oneTimeSecret.takeIf { kind == InitialClaimKind.EMERGENCY_RESET },
                    resetId = resetId,
                    userId = claim.userId,
                    workspaceId = claim.workspaceId,
                    displayName = claim.displayName,
                    device = claim.device.toDto(deviceToken),
                    initialKeys = InitialKeysDto(
                        keyCommitment = claim.initialKeys.keyCommitment,
                        deviceWrappedKey = claim.initialKeys.deviceWrappedKey,
                        deviceEnvelopeSignature = claim.initialKeys.deviceEnvelopeSignature,
                        recoverySigningPublicKey = claim.initialKeys.recoverySigningPublicKey,
                        recoveryWrappingPublicKey = claim.initialKeys.recoveryWrappingPublicKey,
                        recoveryWrappedKey = claim.initialKeys.recoveryWrappedKey,
                        recoveryDeviceTrustSignature = claim.initialKeys.recoveryDeviceTrustSignature,
                    ),
                    claimSignature = claim.claimSignature,
                )
            }
        }
        val path = when (kind) {
            InitialClaimKind.SETUP -> "/v1/setup/claim"
            InitialClaimKind.INVITE -> "/v1/invites/redeem"
            InitialClaimKind.EMERGENCY_RESET -> "/v1/emergency-reset/handoff"
        }
        val response = client.post(workerUrl(endpoint, path)) { jsonBody(request) }
            .decodeSuccess<InitialClaimResponse>()
        if (kind == InitialClaimKind.EMERGENCY_RESET && response.resetId?.requireUuid("reset_id") != resetId) {
            throw SyncProvisioningException("emergency_reset_receipt_mismatch")
        }
        return InitialWorkspaceClaimResult(
            instanceId = response.instanceId.requireUuid("instance_id"),
            userId = response.userId.requireUuid("user_id"),
            workspaceId = response.workspaceId.requireUuid("workspace_id"),
            deviceId = response.deviceId.requireUuid("device_id"),
            keyEpoch = response.keyEpoch,
        )
    }

    private suspend fun authenticate(session: SyncSession): ProvisioningAccessTokenResponse {
        val challenge = client.post(workerUrl(session.endpoint, "/v1/auth/challenge")) {
            jsonBody(ProvisioningAuthChallengeRequest(session.deviceId))
        }.decodeSuccess<ProvisioningAuthChallengeResponse>()
        val message = BinaryData.copyOf(
            AUTH_CHALLENGE_DOMAIN +
                "${challenge.challengeId}\n${challenge.challenge}\n${session.deviceId}".encodeToByteArray(),
        )
        val signature = encodeBase64Url(crypto.signDeviceMessage(message).copyBytes())
        val token = secretStore.requireSecret(SyncSecretKey.DeviceCredential).utf8()
        return client.post(workerUrl(session.endpoint, "/v1/auth/token")) {
            jsonBody(
                ProvisioningAuthTokenRequest(
                    session.deviceId,
                    challenge.challengeId,
                    challenge.challenge,
                    token,
                    signature,
                ),
            )
        }.decodeSuccess()
    }

    private suspend inline fun <reified Request : Any, reified Response : Any> controlPost(
        session: SyncSession,
        path: String,
        body: Request,
    ): Response {
        SodiumSyncPrimitives.initialize()
        val access = authenticate(session)
        val rawBody = json.encodeToString(body)
        val timestamp = nowMillis().also { require(it in 1_000_000_000_000L..9_999_999_999_999L) }.toString()
        val nonce = encodeBase64Url(SodiumSyncPrimitives.randomBytes(24))
        val bodyHash = encodeBase64Url(SodiumSyncPrimitives.sha256(rawBody.encodeToByteArray()))
        val message = BinaryData.copyOf(
            CONTROL_REQUEST_DOMAIN +
                "POST\n$path\n$timestamp\n$nonce\n$bodyHash\n${session.deviceId}".encodeToByteArray(),
        )
        val signature = encodeBase64Url(crypto.signDeviceMessage(message).copyBytes())
        return client.post(workerUrl(session.endpoint, path)) {
            bearerAuth(access.accessToken)
            header("X-Shinsou-Timestamp", timestamp)
            header("X-Shinsou-Nonce", nonce)
            header("X-Shinsou-Signature", signature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }.decodeSuccess()
    }

    private suspend inline fun <reified Request : Any, reified Response : Any> controlPut(
        session: SyncSession,
        path: String,
        body: Request,
    ): Response {
        SodiumSyncPrimitives.initialize()
        val access = authenticate(session)
        val rawBody = json.encodeToString(body)
        val timestamp = nowMillis().also { require(it in 1_000_000_000_000L..9_999_999_999_999L) }.toString()
        val nonce = encodeBase64Url(SodiumSyncPrimitives.randomBytes(24))
        val bodyHash = encodeBase64Url(SodiumSyncPrimitives.sha256(rawBody.encodeToByteArray()))
        val message = BinaryData.copyOf(
            CONTROL_REQUEST_DOMAIN +
                "PUT\n$path\n$timestamp\n$nonce\n$bodyHash\n${session.deviceId}".encodeToByteArray(),
        )
        val signature = encodeBase64Url(crypto.signDeviceMessage(message).copyBytes())
        return client.put(workerUrl(session.endpoint, path)) {
            bearerAuth(access.accessToken)
            header("X-Shinsou-Timestamp", timestamp)
            header("X-Shinsou-Nonce", nonce)
            header("X-Shinsou-Signature", signature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }.decodeSuccess()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(value: Any) {
        contentType(ContentType.Application.Json)
        val body = when (value) {
            is InitialClaimRequest -> json.encodeToString(value)
            is ProvisioningAuthChallengeRequest -> json.encodeToString(value)
            is ProvisioningAuthTokenRequest -> json.encodeToString(value)
            is ProvisioningCapabilityRequest -> json.encodeToString(value)
            is SubmitPairingCandidateRequest -> json.encodeToString(value)
            else -> error("Unsupported provisioning request body")
        }
        setBody(body)
    }

    private suspend inline fun <reified T> HttpResponse.decodeSuccess(): T {
        if (status.value !in 200..299) throw decodeFailure()
        return runCatching { json.decodeFromString<T>(bodyAsText()) }
            .getOrElse { throw SyncProvisioningException("malformed_provisioning_response", it) }
    }

    private suspend fun HttpResponse.decodeFailure(): SyncProvisioningException {
        val envelope = runCatching { json.decodeFromString<ProvisioningErrorEnvelope>(bodyAsText()) }.getOrNull()
        val code = envelope?.error?.code?.takeIf { SAFE_ERROR.matches(it) } ?: "provisioning_http_${status.value}"
        return SyncProvisioningException(code)
    }
}

private fun workerUrl(endpoint: String, path: String): String {
    val base = endpoint.trim().trimEnd('/')
    if (!isAllowedSyncEndpoint(base)) throw SyncProvisioningException("invalid_sync_endpoint")
    require(path.startsWith('/') && !path.startsWith("//"))
    return base + path
}

private fun String.requireUuid(label: String): String = lowercase().also {
    if (!UUID.matches(it)) throw SyncProvisioningException("invalid_$label")
}

private fun String.requireOpaqueSecret(): String = also {
    if (!OPAQUE.matches(it) || it.length !in 32..512) throw SyncProvisioningException("invalid_opaque_secret")
}

private fun dev.shinsou.kmp.sync.v2.SecretMaterial.utf8(): String {
    var output: String? = null
    useBytes { output = it.decodeToString(throwOnInvalidSequence = true) }
    return requireNotNull(output)
}

private fun dev.shinsou.kmp.sync.provisioning.ProvisioningDeviceRegistration.toDto(token: String) =
    DeviceDto(deviceId, displayName, platform, signingPublicKey, wrappingPublicKey, token)

private fun ProvisioningKeyEnvelope.toDto() =
    KeyEnvelopeDto(keyEpoch, keyCommitment, wrappedKey, signature)

private fun AdminUsageResponse.toDomain() = SyncAdminUsage(
    generatedAtMillis = generatedAt,
    quota = quota.toDomain(),
    totals = SyncAdminUsageTotals(
        totals.activeUsers,
        totals.activeDevices,
        totals.activeWorkspaces,
        totals.committedBytes,
        totals.reservedBytes,
    ),
    workspaces = workspaces.map {
        SyncAdminWorkspaceUsage(
            it.workspaceId.requireUuid("workspace_id"),
            it.status,
            it.headSeq,
            it.committedBytes,
            it.reservedBytes,
            it.maximumBytes,
        )
    },
    daily = daily.map {
        SyncAdminDailyUsage(
            it.day,
            it.eventsWritten,
            it.eventBytesWritten,
            it.checkpointsWritten,
            it.checkpointBytesWritten,
        )
    },
)

private fun AdminQuotaDto.toDomain() = SyncAdminQuota(
    maxUsers,
    maxWorkspacesPerUser,
    maxDevicesPerUser,
    maxWorkspaceBytes,
    maxEventBytes,
    maxCheckpointBytes,
)

private fun DeviceRevocationReceiptDto.toDomain() = ProvisioningDeviceRevocationReceipt(
    revocationId = revocationId.requireUuid("revocation_id"),
    actorDeviceId = actorDeviceId.requireUuid("device_id"),
    revokedDeviceId = revokedDeviceId.requireUuid("device_id"),
    committedAtMillis = committedAt,
    workspaceBindings = workspaceBindings.map { binding ->
        ProvisioningRevocationWorkspaceBinding(
            workspaceId = binding.workspaceId.requireUuid("workspace_id"),
            revokedAtKeyEpoch = binding.revokedAtKeyEpoch,
            directoryEpochAfterRevocation = binding.directoryEpochAfterRevocation,
            currentActiveKeyEpoch = binding.currentActiveKeyEpoch,
            currentRotationRequired = binding.currentRotationRequired,
            coveringRotationId = binding.coveringRotationId?.requireUuid("rotation_id"),
            coveringProposerDeviceId = binding.coveringProposerDeviceId?.requireUuid("device_id"),
        )
    },
)

private fun PairingViewDto.toDomain(): ProvisioningPairingView = ProvisioningPairingView(
    pairingId = pairingId.requireUuid("pairing_id"),
    workspaceId = workspaceId.requireUuid("workspace_id"),
    sponsorDeviceId = sponsorDeviceId.requireUuid("device_id"),
    sponsorSigningPublicKey = sponsorSigningPublicKey,
    sponsorWrappingPublicKey = sponsorWrappingPublicKey,
    transcriptNonce = transcriptNonce,
    status = when (status) {
        "open" -> ProvisioningPairingStatus.OPEN
        "candidate" -> ProvisioningPairingStatus.CANDIDATE
        "approved" -> ProvisioningPairingStatus.APPROVED
        "cancelled", "expired" -> ProvisioningPairingStatus.CANCELLED
        else -> throw SyncProvisioningException("unknown_pairing_status")
    },
    expiresAtMillis = expiresAt,
    candidate = candidate?.let {
        ProvisioningPairingCandidate(
            it.deviceId.requireUuid("device_id"),
            it.displayName,
            it.platform,
            it.signingPublicKey,
            it.wrappingPublicKey,
            it.tokenCommitment,
        )
    },
    confirmationCode = shortCode?.also {
        if (!it.matches(Regex("^[0-9]{6}$"))) throw SyncProvisioningException("invalid_pairing_code")
    },
    keyRequirements = keyRequirements?.let {
        ProvisioningPairKeyRequirements(
            requiredKeyEpochs = it.requiredKeyEpochs,
            activeKeyEpoch = it.activeKeyEpoch,
            headSeq = it.headSeq,
            retainedStableCheckpoints = it.retainedStableCheckpoints.map { checkpoint ->
                ProvisioningRetainedCheckpoint(
                    checkpoint.checkpointId,
                    checkpoint.throughWorkspaceSeq,
                    checkpoint.keyEpoch,
                    checkpoint.ciphertextSha256,
                )
            },
            recoveryBaseCheckpointId = it.recoveryBaseCheckpointId,
            recoveryBaseThroughWorkspaceSeq = it.recoveryBaseThroughWorkspaceSeq,
        )
    },
    activation = activation?.let {
        ProvisioningPairActivation(
            userId = it.userId.requireUuid("user_id"),
            workspaceId = it.workspaceId.requireUuid("workspace_id"),
            deviceId = it.deviceId.requireUuid("device_id"),
            deviceAuthEpoch = it.deviceAuthEpoch,
            membershipAuthEpoch = it.membershipAuthEpoch,
            activeKeyEpoch = it.activeKeyEpoch,
            keyEnvelopes = it.keyEnvelopes.map { envelope ->
                ProvisioningKeyEnvelope(
                    envelope.keyEpoch,
                    envelope.keyCommitment,
                    envelope.wrappedKey,
                    envelope.wrappedByDeviceId,
                    envelope.signature,
                )
            },
            approval = ProvisioningPairApprovalEvidence(
                it.approval.attestorDeviceId,
                it.approval.attestorPublicKey,
                it.approval.signatureDomain,
                it.approval.manifestJson,
                it.approval.signature,
            ),
        )
    },
)

@Serializable private data class ProvisioningCapabilitiesDto(
    val instanceId: String,
    val protocolVersion: Int,
    val minReaderVersion: Int,
    val minWriterVersion: Int,
    val schemaVersion: Int,
    val minSchemaReaderVersion: Int,
    val minSchemaWriterVersion: Int,
    val profile: String,
    val websocket: Boolean,
)

@Serializable private data class AdminQuotaDto(
    val quotaProfileId: String,
    val maxUsers: Int,
    val maxWorkspacesPerUser: Int,
    val maxDevicesPerUser: Int,
    val maxWorkspaceBytes: Long,
    val maxEventBytes: Int,
    val maxCheckpointBytes: Int,
)
@Serializable private data class AdminUsageTotalsDto(
    val activeUsers: Int,
    val activeDevices: Int,
    val activeWorkspaces: Int,
    val committedBytes: Long,
    val reservedBytes: Long,
)
@Serializable private data class AdminWorkspaceUsageDto(
    val workspaceId: String,
    val ownerUserId: String,
    val status: String,
    val headSeq: Long,
    val committedBytes: Long,
    val reservedBytes: Long,
    val maximumBytes: Long,
)
@Serializable private data class AdminDailyUsageDto(
    val day: String,
    val eventsWritten: Long,
    val eventBytesWritten: Long,
    val checkpointsWritten: Long,
    val checkpointBytesWritten: Long,
)
@Serializable private data class AdminUsageResponse(
    val generatedAt: Long,
    val quota: AdminQuotaDto,
    val totals: AdminUsageTotalsDto,
    val workspaces: List<AdminWorkspaceUsageDto>,
    val daily: List<AdminDailyUsageDto>,
)
@Serializable private data class UpdateAdminQuotaRequest(
    val maxUsers: Int,
    val maxWorkspacesPerUser: Int,
    val maxDevicesPerUser: Int,
    val maxWorkspaceBytes: Long,
    val maxEventBytes: Int,
    val maxCheckpointBytes: Int,
)
@Serializable private data class UpdateAdminQuotaResponse(
    val updatedAt: Long,
    val quota: AdminQuotaDto,
)

@Serializable private data class DeviceDto(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
    val deviceToken: String,
)

@Serializable private data class InitialKeysDto(
    val keyCommitment: String,
    val deviceWrappedKey: String,
    val deviceEnvelopeSignature: String,
    val recoverySigningPublicKey: String,
    val recoveryWrappingPublicKey: String,
    val recoveryWrappedKey: String,
    val recoveryDeviceTrustSignature: String,
)

@Serializable private data class InitialClaimRequest(
    val bootstrapSecret: String? = null,
    val inviteSecret: String? = null,
    val handoffSecret: String? = null,
    val resetId: String? = null,
    val userId: String,
    val workspaceId: String,
    val displayName: String,
    val device: DeviceDto,
    val initialKeys: InitialKeysDto,
    val claimSignature: String,
)

private enum class InitialClaimKind { SETUP, INVITE, EMERGENCY_RESET }

@Serializable private data class InitialClaimResponse(
    val instanceId: String,
    val userId: String,
    val workspaceId: String,
    val deviceId: String,
    val keyEpoch: Int,
    val resetId: String? = null,
)

@Serializable private data class ProvisioningAuthChallengeRequest(val deviceId: String)
@Serializable private data class ProvisioningAuthChallengeResponse(
    val challengeId: String,
    val challenge: String,
    val expiresAt: Long,
)
@Serializable private data class ProvisioningAuthTokenRequest(
    val deviceId: String,
    val challengeId: String,
    val challenge: String,
    val deviceToken: String,
    val signature: String,
)
@Serializable private data class ProvisioningAccessTokenResponse(val accessToken: String, val expiresAt: Long)
@Serializable private data class ProvisioningCapabilityRequest(val workspaceId: String)
@Serializable private data class ProvisioningCapabilityResponse(
    val capability: String,
    val workspaceId: String,
    val deviceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val keyEpoch: Int,
    val expiresAt: Long,
)
@Serializable private data class ProvisioningBootstrapProof(val workspaceId: String, val activeKeyEpoch: Int)
@Serializable private data class ProvisioningRecoveryWorkspaceBindingDto(
    val workspaceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val activeKeyEpoch: Int,
)
@Serializable private data class RecoveryClaimReconciliationResponse(
    val claimId: String,
    val userId: String,
    val deviceId: String,
    val newRecoverySigningPublicKey: String,
    val newRecoveryWrappingPublicKey: String,
    val rotationRequiredWorkspaces: List<String>,
    val workspaceBindings: List<ProvisioningRecoveryWorkspaceBindingDto>,
)

@Serializable private data class CreateInviteRequest(val ttlSeconds: Int)
@Serializable private data class CreateInviteResponse(val inviteId: String, val inviteSecret: String, val expiresAt: Long)
@Serializable private data class CreatePairingRequest(val workspaceId: String)
@Serializable private data class CreatePairingResponse(
    val pairingId: String,
    val pairingSecret: String,
    val transcriptNonce: String,
    val expiresAt: Long,
)
@Serializable private data class SubmitPairingCandidateRequest(
    val pairingId: String,
    val pairingSecret: String,
    val device: DeviceDto,
)
@Serializable private data class SubmitPairingCandidateResponse(val pairingId: String, val status: String, val shortCode: String)

@Serializable private data class PairingCandidateDto(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
    val tokenCommitment: String,
)
@Serializable private data class RetainedCheckpointDto(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val ciphertextSha256: String,
)
@Serializable private data class PairKeyRequirementsDto(
    val requiredKeyEpochs: List<Int>,
    val activeKeyEpoch: Int,
    val headSeq: Long,
    val retainedStableCheckpoints: List<RetainedCheckpointDto>,
    val recoveryBaseCheckpointId: String? = null,
    val recoveryBaseThroughWorkspaceSeq: Long,
)
@Serializable private data class KeyEnvelopeDto(
    val keyEpoch: Int,
    val keyCommitment: String,
    val wrappedKey: String,
    val signature: String,
    val wrappedByDeviceId: String? = null,
)
@Serializable private data class PairApprovalEvidenceDto(
    val attestorDeviceId: String,
    val attestorPublicKey: String,
    val signatureDomain: String,
    val manifestJson: String,
    val signature: String,
)
@Serializable private data class PairActivationDto(
    val userId: String,
    val workspaceId: String,
    val deviceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val activeKeyEpoch: Int,
    val keyEnvelopes: List<KeyEnvelopeDto>,
    val approval: PairApprovalEvidenceDto,
)
@Serializable private data class PairingViewDto(
    val pairingId: String,
    val workspaceId: String,
    val sponsorDeviceId: String,
    val sponsorSigningPublicKey: String,
    val sponsorWrappingPublicKey: String,
    val transcriptNonce: String,
    val status: String,
    val expiresAt: Long,
    val candidate: PairingCandidateDto? = null,
    val shortCode: String? = null,
    val keyRequirements: PairKeyRequirementsDto? = null,
    val activation: PairActivationDto? = null,
)
@Serializable private data class PairApprovalRequest(
    val approved: Boolean,
    val keyEnvelopes: List<KeyEnvelopeDto> = emptyList(),
    val approvalSignature: String? = null,
)
@Serializable private data class PairApprovalResponse(val pairingId: String, val status: String)

@Serializable private data class DeviceListEntryDto(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val status: String,
    val lastSeenAt: Long? = null,
)
@Serializable private data class DeviceListResponse(val devices: List<DeviceListEntryDto>)
@Serializable private data class RevokeDeviceRequest(val revocationId: String)
@Serializable private data class DeviceRevocationWorkspaceBindingDto(
    val workspaceId: String,
    val revokedAtKeyEpoch: Int,
    val directoryEpochAfterRevocation: Long,
    val currentActiveKeyEpoch: Int,
    val currentRotationRequired: Boolean,
    val coveringRotationId: String? = null,
    val coveringProposerDeviceId: String? = null,
)
@Serializable private data class DeviceRevocationReceiptDto(
    val revocationId: String,
    val actorDeviceId: String,
    val revokedDeviceId: String,
    val committedAt: Long,
    val workspaceBindings: List<DeviceRevocationWorkspaceBindingDto>,
)
@Serializable private data class ProvisioningErrorEnvelope(val error: ProvisioningErrorBody)
@Serializable private data class ProvisioningErrorBody(val code: String, val message: String = code, val details: JsonObject? = null)

private val AUTH_CHALLENGE_DOMAIN = "shinsou:auth-challenge:v1\u0000".encodeToByteArray()
private val CONTROL_REQUEST_DOMAIN = "shinsou:control-request:v1\u0000".encodeToByteArray()
private const val PAIRING_SECRET_HEADER = "X-Shinsou-Pairing-Secret"
private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val OPAQUE = Regex("^[A-Za-z0-9_-]+$")
private val SAFE_ERROR = Regex("^[a-z0-9_]{1,80}$")
private val AUTH_NOT_COMMITTED_CODES = setOf("device_authentication_failed", "http_401", "provisioning_http_401")
