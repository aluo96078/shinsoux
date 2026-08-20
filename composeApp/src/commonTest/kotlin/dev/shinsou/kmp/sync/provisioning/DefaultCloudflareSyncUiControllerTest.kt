package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.persistence.SyncInstallationIdentity
import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.InMemorySyncSessionStore
import dev.shinsou.kmp.sync.v2.MaterializationIssue
import dev.shinsou.kmp.sync.v2.MaterializationIssueKind
import dev.shinsou.kmp.sync.v2.RepositoryTrustConfirmation
import dev.shinsou.kmp.sync.v2.SyncEntityKey
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncEngineState
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncMaterializationDiagnostics
import dev.shinsou.kmp.sync.v2.SyncAdminDailyUsage
import dev.shinsou.kmp.sync.v2.SyncAdminQuota
import dev.shinsou.kmp.sync.v2.SyncAdminUsage
import dev.shinsou.kmp.sync.v2.SyncAdminUsageTotals
import dev.shinsou.kmp.sync.v2.SyncAdminWorkspaceUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultCloudflareSyncUiControllerTest {
    @Test
    fun deploymentCanBeReopenedWithoutRotatingTheBootstrapSecret() = runTest {
        val fixture = fixture()
        val firstController = fixture.controller(this)
        runCurrent()

        val firstDeployment = firstController.beginDeployment()
        val firstSecret = firstDeployment.bootstrapSecret.use { it }

        // A process recreation must retain the in-progress external deployment and its secret.
        val recreated = fixture.controller(this)
        runCurrent()
        assertEquals(SyncSessionStatus.DEPLOYING, recreated.state.value.status)

        val reopened = recreated.beginDeployment()
        assertEquals(firstDeployment.deployUrl, reopened.deployUrl)
        assertEquals(firstSecret, reopened.bootstrapSecret.use { it })
        assertEquals(SyncSessionStatus.DEPLOYING, recreated.state.value.status)
    }

    @Test
    fun processRestartConsumesSecretlessSetupWithStrictStoredBootstrapSecret() = runTest {
        val fixture = fixture()
        val firstController = fixture.controller(this)
        runCurrent()
        val deployment = firstController.beginDeployment()
        val bootstrapSecret = deployment.bootstrapSecret.use { it }
        assertIs<SyncSecretReadResult.Available>(fixture.secrets.read(SyncSecretKey.PendingBootstrapSecret))

        // Simulate process/controller recreation. Only strict stores survive; controller memory does not.
        val recreated = fixture.controller(this)
        runCurrent()
        recreated.submitOneTimeLinkOrCode(SETUP_LINK)

        assertEquals(SyncSessionStatus.READY, recreated.state.value.status)
        assertEquals(1, fixture.activation.initialSeedCount)
        assertEquals(SyncSessionStatus.LINKING, fixture.activation.seedSessionStatus)
        assertEquals(SyncSecretReadResult.Missing, fixture.secrets.read(SyncSecretKey.PendingBootstrapSecret))
        assertEquals(SyncSessionStatus.READY, fixture.sessions.load()?.status)
        assertTrue(bootstrapSecret !in recreated.state.value.toString())
        assertTrue(bootstrapSecret !in fixture.api.lastClaim.toString())
    }

    @Test
    fun secretlessSetupCanQueueMetadataThenAcceptBareManualSecret() = runTest {
        val fixture = fixture()
        val controller = fixture.controller(this)
        runCurrent()
        controller.submitOneTimeLinkOrCode(SETUP_LINK)
        assertEquals(SyncSessionStatus.LINKING, controller.state.value.status)
        assertEquals("bootstrap_secret_required", controller.state.value.diagnostic)

        controller.submitOneTimeLinkOrCode(BOOTSTRAP_SECRET)
        assertEquals(SyncSessionStatus.READY, controller.state.value.status)
        assertEquals(1, fixture.activation.initialSeedCount)
    }

    @Test
    fun initialCheckpointGateFailureNeverPublishesReady() = runTest {
        val fixture = fixture(failInitialActivation = true)
        val controller = fixture.controller(this)
        runCurrent()
        controller.submitOneTimeLinkOrCode(SETUP_LINK)
        val failure = assertFailsWith<SyncProvisioningException> {
            controller.submitOneTimeLinkOrCode(BOOTSTRAP_SECRET)
        }

        assertEquals("initial_checkpoint_verification_failed", failure.safeCode)
        assertEquals(SyncSessionStatus.ERROR, controller.state.value.status)
        assertEquals(SyncSessionStatus.LINKING, fixture.sessions.load()?.status)
        assertNull(controller.state.value.lastSuccessfulSyncAtMillis)
    }

    @Test
    fun corruptPersistedBootstrapSecretFailsClosedWithoutCallingClaim() = runTest {
        val fixture = fixture()
        fixture.secrets.forceResult(
            SyncSecretKey.PendingBootstrapSecret,
            SyncSecretReadResult.Corrupt("test corruption"),
        )
        val controller = fixture.controller(this)
        runCurrent()

        val failure = assertFailsWith<SyncProvisioningException> {
            controller.submitOneTimeLinkOrCode(SETUP_LINK)
        }

        assertEquals("pending_bootstrap_secret_corrupt", failure.safeCode)
        assertEquals(SyncSessionStatus.ERROR, controller.state.value.status)
        assertNull(fixture.api.lastClaim)
    }

    @Test
    fun setupLostResponseReconcilesExactPersistedIdentityBeforeReady() = runTest {
        val fixture = fixture()
        fixture.api.loseNextInitialResponse = true
        val controller = fixture.controller(this)
        runCurrent()
        controller.submitOneTimeLinkOrCode(SETUP_LINK)
        controller.submitOneTimeLinkOrCode(BOOTSTRAP_SECRET)

        val claim = assertNotNull(fixture.api.lastClaim)
        val session = assertNotNull(fixture.sessions.load())
        assertEquals(claim.userId, session.userId)
        assertEquals(claim.workspaceId, session.workspaceId)
        assertEquals(claim.device.deviceId, session.deviceId)
        assertEquals(1, fixture.api.reconcileCount)
        assertEquals(1, fixture.activation.initialSeedCount)
        assertEquals(1, fixture.activation.syncNowCount)
        assertEquals(SyncSessionStatus.READY, session.status)
    }

    @Test
    fun inviteRetryAfterProcessDeathReusesIdsKeysAndCredential() = runTest {
        val fixture = fixture()
        fixture.api.failInitialBeforeCommit = true
        val first = fixture.controller(backgroundScope)
        runCurrent()
        assertFailsWith<SyncProvisioningException> {
            first.submitOneTimeLinkOrCode(INVITE_LINK)
        }
        val original = assertNotNull(fixture.api.claims.singleOrNull())
        val linking = assertNotNull(fixture.sessions.load())
        assertEquals(SyncSessionStatus.LINKING, linking.status)
        assertIs<SyncSecretReadResult.Available>(fixture.secrets.read(SyncSecretKey.PendingInvitePayload))

        fixture.api.failInitialBeforeCommit = false
        fixture.controller(backgroundScope)
        runCurrent()

        val retried = fixture.api.claims.last()
        assertEquals(2, fixture.api.claims.size)
        assertEquals(original.userId, retried.userId)
        assertEquals(original.workspaceId, retried.workspaceId)
        assertEquals(original.device.deviceId, retried.device.deviceId)
        assertEquals(original.device.signingPublicKey, retried.device.signingPublicKey)
        assertEquals(original.device.wrappingPublicKey, retried.device.wrappingPublicKey)
        assertEquals(
            original.device.deviceToken.use { it },
            retried.device.deviceToken.use { it },
        )
        assertEquals(SyncSessionStatus.READY, fixture.sessions.load()?.status)
        assertEquals(SyncSecretReadResult.Missing, fixture.secrets.read(SyncSecretKey.PendingInvitePayload))
    }

    @Test
    fun operatorEmergencyHandoffReplacesReadyIdentityAndUsesExactOwnerWorkspace() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.secrets.write(
            SyncSecretKey.DeviceCredential,
            SecretMaterial("old-device-credential".encodeToByteArray().asList()),
        )
        val controller = fixture.controller(this)
        runCurrent()

        controller.submitOneTimeLinkOrCode(EMERGENCY_RESET_LINK)

        val session = assertNotNull(fixture.sessions.load())
        val claim = assertNotNull(fixture.api.lastClaim)
        assertEquals(RESET_ID, fixture.api.lastEmergencyResetId)
        assertEquals(USER_ID, session.userId)
        assertEquals(WORKSPACE_ID, session.workspaceId)
        assertTrue(session.deviceId != DEVICE_ID)
        assertEquals(session.deviceId, claim.device.deviceId)
        assertEquals(1, fixture.activation.leaveCount)
        assertEquals(1, fixture.activation.initialSeedCount)
        assertEquals(SyncSessionStatus.READY, session.status)
        assertEquals("Save your replacement Shinsou X Recovery Kit", controller.state.value.activeShare?.title)
        assertEquals(SyncSecretReadResult.Missing, fixture.secrets.read(SyncSecretKey.PendingInvitePayload))
    }

    @Test
    fun pairingCandidateRestartReusesDeviceSecretsWithoutResubmitting() = runTest {
        val fixture = fixture()
        fixture.api.enableCandidatePairing()
        val first = fixture.controller(backgroundScope)
        runCurrent()
        first.submitOneTimeLinkOrCode(PAIR_LINK)
        val signing = assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.DeviceSigningPrivateKey),
        ).material
        val wrapping = assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.DeviceWrappingPrivateKey),
        ).material
        val credential = assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.DeviceCredential),
        ).material
        assertEquals(1, fixture.api.candidateSubmitCount)

        val recreated = fixture.controller(backgroundScope)
        runCurrent()

        assertEquals(1, fixture.api.candidateSubmitCount)
        assertEquals(signing, assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.DeviceSigningPrivateKey),
        ).material)
        assertEquals(wrapping, assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.DeviceWrappingPrivateKey),
        ).material)
        assertEquals(credential, assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.DeviceCredential),
        ).material)
        assertEquals(SyncSessionStatus.LINKING, recreated.state.value.status)
        assertNotNull(recreated.state.value.activeShare?.confirmationCode)
    }

    @Test
    fun sponsorTreatsMatchingApprovedViewAsSuccessAfterLostResponse() = runTest {
        val fixture = fixture()
        val ready = readySponsorSession()
        val prepared = fixture.provisioningCrypto.prepareInitialWorkspace(
            ready.copy(status = SyncSessionStatus.LINKING),
            "Owner",
            EphemeralSyncPayload(BOOTSTRAP_SECRET),
        )
        fixture.sessions.save(ready)
        fixture.api.enableSponsorPairing(prepared.claim, ready)
        val controller = fixture.controller(backgroundScope)
        runCurrent()

        controller.approvePairing(PAIRING_ID, approved = true)

        assertEquals(1, fixture.api.approveCount)
        assertEquals(SyncSessionStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.pairingCandidates.isEmpty())
    }

    @Test
    fun unreadableSessionMetadataFailsClosedAsConfiguredError() = runTest {
        val fixture = fixture()
        val unavailable = object : SyncSessionStore {
            override suspend fun load(): SyncSession? = error("storage unavailable")
            override suspend fun save(session: SyncSession) = Unit
            override suspend fun clear() = Unit
        }
        val controller = fixture.controller(this, unavailable)
        runCurrent()

        assertEquals(SyncSessionStatus.ERROR, controller.state.value.status)
        assertEquals("sync_session_unavailable", controller.state.value.diagnostic)
        assertTrue(controller.state.value.configured)
        assertNull(fixture.api.lastClaim)
    }

    @Test
    fun adminUsageIsPublishedAndSignedQuotaResultReplacesStaleMetadata() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.api.adminUsageResponse = adminUsage(SyncAdminQuota(25, 1, 10, 262_144_000, 32_768, 33_554_432))
        val controller = fixture.controller(this)
        runCurrent()

        assertEquals(2, controller.state.value.adminUsage?.totals?.activeUsers)
        val replacement = SyncAdminQuota(30, 1, 12, 300_000_000, 32_768, 33_554_432)
        controller.updateAdminQuota(replacement)

        assertEquals(1, fixture.api.adminQuotaUpdateCount)
        assertEquals(replacement, controller.state.value.adminUsage?.quota)
        assertNull(controller.state.value.diagnostic)
    }

    @Test
    fun readySessionKeepsControlsAndCanRetryAfterInitialDeviceRefreshFailure() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.api.listDevicesFailuresRemaining = 1
        val controller = fixture.controller(this)
        runCurrent()

        assertEquals(1, fixture.api.listDevicesCount)
        assertEquals(SyncSessionStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.ready)
        assertEquals("device_list_temporarily_unavailable", controller.state.value.diagnostic)

        controller.refreshDevices()

        assertEquals(2, fixture.api.listDevicesCount)
        assertEquals(SyncSessionStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.ready)
        assertEquals(1, controller.state.value.devices.size)
        assertNull(controller.state.value.diagnostic)
    }

    @Test
    fun readySessionKeepsControlsAndCanRetryAfterAdminUsageRefreshFailure() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.api.adminUsageResponse = adminUsage(SyncAdminQuota(25, 1, 10, 262_144_000, 32_768, 33_554_432))
        fixture.api.adminUsageFailuresRemaining = 1
        val controller = fixture.controller(this)
        runCurrent()

        assertEquals(SyncSessionStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.ready)
        assertEquals("admin_usage_temporarily_unavailable", controller.state.value.diagnostic)
        assertNull(controller.state.value.adminUsage)

        controller.refreshAdminUsage()

        assertEquals(SyncSessionStatus.READY, controller.state.value.status)
        assertNotNull(controller.state.value.adminUsage)
        assertNull(controller.state.value.diagnostic)
    }

    @Test
    fun durableRevocationStillWinsOverAStaleReadyUiDuringAnAction() = runTest {
        val fixture = fixture()
        val ready = readySponsorSession()
        fixture.sessions.save(ready)
        val controller = fixture.controller(this)
        runCurrent()
        fixture.sessions.save(ready.copy(status = SyncSessionStatus.REVOKED))

        val failure = assertFailsWith<SyncProvisioningException> { controller.refreshDevices() }

        assertEquals("sync_session_not_ready", failure.safeCode)
        assertEquals(SyncSessionStatus.REVOKED, controller.state.value.status)
        assertEquals(dev.shinsou.kmp.sync.v2.SyncEnginePhase.REVOKED, controller.state.value.phase)
        assertTrue(!controller.state.value.ready)
    }

    @Test
    fun revokeLostResponseUsesExactReceiptThenClearsPendingOnlyAfterActivation() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.api.loseNextRevocationResponse = true
        val controller = fixture.controller(this)
        runCurrent()

        controller.revokeDevice(CANDIDATE_ID)

        assertEquals(1, fixture.api.revocationPostIds.size)
        assertEquals(1, fixture.api.revocationLookupCount)
        assertEquals(1, fixture.activation.revocationCount)
        assertNull(fixture.sessions.load()?.pendingDeviceRevocation)
        assertEquals(2, fixture.sessions.load()?.activeKeyEpoch)
    }

    @Test
    fun revokeBeforeCommitFailureRetriesWithSameDurableOperationId() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.api.failNextRevocationBeforeCommit = true
        val controller = fixture.controller(this)
        runCurrent()

        controller.revokeDevice(CANDIDATE_ID)

        assertEquals(2, fixture.api.revocationPostIds.size)
        assertEquals(1, fixture.api.revocationPostIds.distinct().size)
        assertEquals(1, fixture.activation.revocationCount)
        assertNull(fixture.sessions.load()?.pendingDeviceRevocation)
    }

    @Test
    fun activationFailureKeepsPendingAndStartupResumesExactReceipt() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.activation.failRevocation = true
        val first = fixture.controller(this)
        runCurrent()
        assertFailsWith<SyncProvisioningException> { first.revokeDevice(CANDIDATE_ID) }
        val pending = assertNotNull(fixture.sessions.load()?.pendingDeviceRevocation)

        fixture.activation.failRevocation = false
        val recreated = fixture.controller(this)
        runCurrent()

        assertEquals(pending.revocationId, fixture.api.revocationPostIds.single())
        assertEquals(2, fixture.activation.revocationCount)
        assertNull(fixture.sessions.load()?.pendingDeviceRevocation)
        assertEquals(SyncSessionStatus.READY, recreated.state.value.status)
    }

    @Test
    fun lostRotationLeaseReloadsAdvancedReceiptAndFinishesWithoutAnotherRevoke() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.activation.failNextRevocation = true
        fixture.api.revocationLookupOverride = deviceRevocationReceipt().copy(
            workspaceBindings = listOf(
                deviceRevocationReceipt().workspaceBindings.single().copy(
                    currentActiveKeyEpoch = 2,
                    currentRotationRequired = false,
                    coveringRotationId = "00000000-0000-4000-8000-00000000000b",
                    coveringProposerDeviceId = "00000000-0000-4000-8000-00000000000c",
                ),
            ),
        )
        val controller = fixture.controller(this)
        runCurrent()

        controller.revokeDevice(CANDIDATE_ID)

        assertEquals(1, fixture.api.revocationPostIds.size)
        assertEquals(1, fixture.api.revocationLookupCount)
        assertEquals(2, fixture.activation.revocationCount)
        assertEquals(2, fixture.activation.revocationReceipts.last().workspaceBindings.single().currentActiveKeyEpoch)
        assertNull(fixture.sessions.load()?.pendingDeviceRevocation)
    }

    @Test
    fun mismatchedRevocationReceiptFailsClosedAndNeverRotates() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.api.revocationReceiptOverride = deviceRevocationReceipt().copy(actorDeviceId = SPONSOR_ID)
        val controller = fixture.controller(this)
        runCurrent()

        val failure = assertFailsWith<SyncProvisioningException> {
            controller.revokeDevice(CANDIDATE_ID)
        }

        assertEquals("device_revocation_receipt_mismatch", failure.safeCode)
        assertEquals(0, fixture.activation.revocationCount)
        assertNotNull(fixture.sessions.load()?.pendingDeviceRevocation)
    }

    @Test
    fun contradictoryCoveredReceiptFailsClosedAndKeepsPendingOperation() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        val invalidBinding = deviceRevocationReceipt().workspaceBindings.single().copy(
            currentActiveKeyEpoch = 2,
            currentRotationRequired = false,
            coveringRotationId = null,
            coveringProposerDeviceId = null,
        )
        fixture.api.revocationReceiptOverride = deviceRevocationReceipt().copy(
            workspaceBindings = listOf(invalidBinding),
        )
        val controller = fixture.controller(this)
        runCurrent()

        val failure = assertFailsWith<SyncProvisioningException> {
            controller.revokeDevice(CANDIDATE_ID)
        }

        assertEquals("device_revocation_coverage_invalid", failure.safeCode)
        assertEquals(0, fixture.activation.revocationCount)
        assertNotNull(fixture.sessions.load()?.pendingDeviceRevocation)
    }

    @Test
    fun durableMaterializationDiagnosticsAndTrustReviewAreForwardedToTheUi() = runTest {
        val fixture = fixture()
        fixture.sessions.save(readySponsorSession())
        fixture.activation.exposeDiagnostics = true
        val controller = fixture.controller(backgroundScope)
        runCurrent()
        val repositoryKey = SyncEntityKey.extensionRepository("https://repo.example/index.json")
        val issue = MaterializationIssue(MaterializationIssueKind.ORPHAN, repositoryKey, "Missing parent")
        val request = RepositoryTrustConfirmation(
            repositoryKey,
            repositoryKey.canonicalValue,
            trustedFingerprint = "old-key",
            proposedFingerprint = "new-key",
        )
        fixture.activation.diagnostics.value = SyncMaterializationDiagnostics(listOf(issue), listOf(request))
        runCurrent()

        assertEquals(listOf(issue), controller.state.value.materializationIssues)
        assertEquals(listOf(request), controller.state.value.repositoryTrustConfirmations)

        controller.rejectRepositoryTrust(request.baseUrl, request.proposedFingerprint)
        controller.acceptRepositoryTrust(request.baseUrl, request.proposedFingerprint)
        controller.retryMaterialization()
        controller.repairIdentityCollision(repositoryKey)

        assertEquals(listOf(request.baseUrl to request.proposedFingerprint), fixture.activation.rejectedTrust)
        assertEquals(listOf(request.baseUrl to request.proposedFingerprint), fixture.activation.acceptedTrust)
        assertEquals(1, fixture.activation.materializationRetryCount)
        assertEquals(listOf(repositoryKey), fixture.activation.repairedIdentityKeys)
    }

    private suspend fun fixture(failInitialActivation: Boolean = false): Fixture {
        val secrets = InMemorySyncSecretStore()
        val sessions = InMemorySyncSessionStore()
        val codec = DeterministicSyncEventCodec()
        val syncCrypto = SodiumSyncCrypto(secrets, codec, InMemorySyncDevicePublicKeyResolver())
        return Fixture(
            secrets,
            sessions,
            FakeProvisioningApi(),
            FakeActivationGate(failInitialActivation, sessions),
            SyncProvisioningCrypto(
                secrets,
                syncCrypto,
                RecoveryKitManager(secrets),
                nowMillis = { NOW },
            ),
        )
    }

    private data class Fixture(
        val secrets: InMemorySyncSecretStore,
        val sessions: InMemorySyncSessionStore,
        val api: FakeProvisioningApi,
        val activation: FakeActivationGate,
        val provisioningCrypto: SyncProvisioningCrypto,
    ) {
        fun controller(
            scope: CoroutineScope,
            sessionStore: SyncSessionStore = sessions,
        ) = DefaultCloudflareSyncUiController(
            scope = scope,
            configuration = CloudflareProvisioningConfiguration(
                deployUrl = "https://deploy.example.test/template",
                userDisplayName = "Owner",
                deviceDisplayName = "Test Phone",
                platform = "ios",
                pairingPollMillis = 100,
            ),
            installationStore = object : SyncInstallationStore {
                override suspend fun loadOrCreate() = SyncInstallationIdentity(
                    installationId = INSTALLATION_ID,
                    deviceId = DEVICE_ID,
                )
            },
            sessionStore = sessionStore,
            secretStore = secrets,
            api = api,
            provisioningCrypto = provisioningCrypto,
            activationGate = activation,
            nowMillis = { NOW },
        )
    }

    private class FakeActivationGate(
        private val failInitial: Boolean,
        private val sessions: SyncSessionStore,
    ) : SyncProvisioningActivationGate {
        override val engineState: StateFlow<SyncEngineState>? = null
        val diagnostics = MutableStateFlow(SyncMaterializationDiagnostics())
        var exposeDiagnostics = false
        override val materializationDiagnostics: StateFlow<SyncMaterializationDiagnostics>?
            get() = diagnostics.takeIf { exposeDiagnostics }
        var initialSeedCount = 0
        var seedSessionStatus: SyncSessionStatus? = null
        var syncNowCount = 0
        var revocationCount = 0
        var leaveCount = 0
        val revocationReceipts = mutableListOf<ProvisioningDeviceRevocationReceipt>()
        var failRevocation = false
        var failNextRevocation = false
        var materializationRetryCount = 0
        val repairedIdentityKeys = mutableListOf<SyncEntityKey>()
        val acceptedTrust = mutableListOf<Pair<String, String>>()
        val rejectedTrust = mutableListOf<Pair<String, String>>()

        override suspend fun seedSnapshotAndVerifyInitialCheckpoint(
            linkingSession: SyncSession,
            trustContext: ProvisioningTrustContext.InitialSelfAnchor,
        ) {
            initialSeedCount++
            seedSessionStatus = linkingSession.status
            assertEquals(linkingSession.deviceId, trustContext.deviceId)
            if (failInitial) throw SyncProvisioningException("initial_checkpoint_verification_failed")
        }

        override suspend fun verifyPairedWorkspaceAndCatchUp(
            linkingSession: SyncSession,
            trustContext: ProvisioningTrustContext.PairingSponsorAnchor,
        ) = Unit

        override suspend fun verifyRecoveredWorkspaceAndCatchUp(
            linkingSession: SyncSession,
            trustContext: ProvisioningTrustContext.RecoveryAnchor,
        ) = Unit

        override suspend fun syncNow() {
            syncNowCount++
        }
        override suspend fun retryMaterialization() {
            materializationRetryCount++
        }
        override suspend fun repairIdentityCollision(key: SyncEntityKey) {
            repairedIdentityKeys += key
        }
        override suspend fun acceptRepositoryTrust(baseUrl: String, proposedFingerprint: String) {
            acceptedTrust += baseUrl to proposedFingerprint
        }
        override suspend fun rejectRepositoryTrust(baseUrl: String, proposedFingerprint: String) {
            rejectedTrust += baseUrl to proposedFingerprint
        }
        override suspend fun rotateAfterRevocation(session: SyncSession, revokedDeviceId: String) {
            revocationCount++
            if (failRevocation || failNextRevocation) {
                failNextRevocation = false
                throw SyncProvisioningException("revocation_rotation_failed")
            }
            val durable = requireNotNull(sessions.load())
            sessions.save(durable.copy(activeKeyEpoch = durable.activeKeyEpoch + 1))
        }
        override suspend fun reconcileDeviceRevocation(
            session: SyncSession,
            receipt: ProvisioningDeviceRevocationReceipt,
        ) {
            revocationReceipts += receipt
            rotateAfterRevocation(session, receipt.revokedDeviceId)
        }
        override suspend fun rotateAfterRecovery(session: SyncSession) = Unit
        override suspend fun leaveWorkspace() {
            leaveCount++
        }
    }

    private class FakeProvisioningApi : CloudflareProvisioningApi {
        var lastClaim: InitialWorkspaceClaim? = null
        val claims = mutableListOf<InitialWorkspaceClaim>()
        var loseNextInitialResponse = false
        var failInitialBeforeCommit = false
        var reconcileCount = 0
        var lastEmergencyResetId: String? = null
        var candidateSubmitCount = 0
        var approveCount = 0
        var adminQuotaUpdateCount = 0
        var adminUsageResponse: SyncAdminUsage? = null
        var adminUsageFailuresRemaining = 0
        var listDevicesCount = 0
        var listDevicesFailuresRemaining = 0
        var loseNextRevocationResponse = false
        var failNextRevocationBeforeCommit = false
        var revocationLookupCount = 0
        val revocationPostIds = mutableListOf<String>()
        var revocationReceiptOverride: ProvisioningDeviceRevocationReceipt? = null
        var revocationLookupOverride: ProvisioningDeviceRevocationReceipt? = null
        private var committedRevocation: ProvisioningDeviceRevocationReceipt? = null
        private var committedInitial: InitialWorkspaceClaimResult? = null
        private var candidateView: ProvisioningPairingView? = null
        private var sponsorView: ProvisioningPairingView? = null

        override suspend fun capabilities(endpoint: String) =
            ProvisioningCapabilities(INSTANCE_ID, 1, 1, 1, 1, 1, 1, realtime = true)

        override suspend fun claimSetup(endpoint: String, claim: InitialWorkspaceClaim): InitialWorkspaceClaimResult {
            lastClaim = claim
            claims += claim
            if (failInitialBeforeCommit) throw IllegalStateException("request failed before commit")
            val receipt = InitialWorkspaceClaimResult(
                INSTANCE_ID,
                claim.userId,
                claim.workspaceId,
                claim.device.deviceId,
                1,
            )
            committedInitial = receipt
            if (loseNextInitialResponse) {
                loseNextInitialResponse = false
                throw IllegalStateException("response lost")
            }
            return receipt
        }

        override suspend fun redeemInvite(endpoint: String, claim: InitialWorkspaceClaim) =
            claimSetup(endpoint, claim)

        override suspend fun claimEmergencyReset(
            endpoint: String,
            resetId: String,
            claim: InitialWorkspaceClaim,
        ): InitialWorkspaceClaimResult {
            lastEmergencyResetId = resetId
            return claimSetup(endpoint, claim)
        }

        override suspend fun reconcileInitialClaim(session: SyncSession): InitialWorkspaceClaimResult? {
            reconcileCount++
            return committedInitial
        }

        override suspend fun createInvite(session: SyncSession, ttlSeconds: Int) =
            error("not used")

        override suspend fun createPairing(session: SyncSession) = error("not used")

        override suspend fun submitPairingCandidate(
            endpoint: String,
            candidate: ProvisioningPairingCandidateInput,
        ): ProvisioningPairingView {
            candidateSubmitCount++
            return candidateView(candidate).also { candidateView = it }
        }

        override suspend fun pairingAsCandidate(
            endpoint: String,
            pairingId: String,
            secret: EphemeralSyncPayload,
        ): ProvisioningPairingView = candidateView ?: openPairingView()

        override suspend fun pairingAsSponsor(session: SyncSession, pairingId: String): ProvisioningPairingView =
            sponsorView ?: error("not used")

        override suspend fun approvePairing(
            session: SyncSession,
            pairingId: String,
            approval: ProvisioningPairApproval,
        ) {
            approveCount++
            if (approval.approved) {
                sponsorView = requireNotNull(sponsorView).copy(status = ProvisioningPairingStatus.APPROVED)
            } else {
                sponsorView = requireNotNull(sponsorView).copy(status = ProvisioningPairingStatus.CANCELLED)
            }
            throw IllegalStateException("approval response lost")
        }

        override suspend fun listDevices(session: SyncSession): List<ProvisioningDevice> {
            listDevicesCount++
            if (listDevicesFailuresRemaining > 0) {
                listDevicesFailuresRemaining--
                throw SyncProvisioningException("device_list_temporarily_unavailable")
            }
            return listOf(
                ProvisioningDevice(session.deviceId, session.deviceDisplayName, session.platform, "active", NOW),
            )
        }

        override suspend fun adminUsage(session: SyncSession): SyncAdminUsage {
            if (adminUsageFailuresRemaining > 0) {
                adminUsageFailuresRemaining--
                throw SyncProvisioningException("admin_usage_temporarily_unavailable")
            }
            return adminUsageResponse ?: throw SyncProvisioningException("admin_required")
        }

        override suspend fun updateAdminQuota(session: SyncSession, quota: SyncAdminQuota): SyncAdminUsage {
            adminQuotaUpdateCount++
            return requireNotNull(adminUsageResponse).copy(quota = quota).also { adminUsageResponse = it }
        }

        override suspend fun revokeDevice(
            session: SyncSession,
            deviceId: String,
            revocationId: String,
        ): ProvisioningDeviceRevocationReceipt {
            revocationPostIds += revocationId
            if (failNextRevocationBeforeCommit) {
                failNextRevocationBeforeCommit = false
                throw IllegalStateException("request failed before commit")
            }
            val receipt = revocationReceiptOverride?.copy(revocationId = revocationId)
                ?: deviceRevocationReceipt(
                    revocationId = revocationId,
                    actorDeviceId = session.deviceId,
                    targetDeviceId = deviceId,
                    workspaceId = session.workspaceId,
                )
            committedRevocation = receipt
            if (loseNextRevocationResponse) {
                loseNextRevocationResponse = false
                throw IllegalStateException("revocation response lost")
            }
            return receipt
        }

        override suspend fun deviceRevocationReceipt(
            session: SyncSession,
            revocationId: String,
        ): ProvisioningDeviceRevocationReceipt? {
            revocationLookupCount++
            return (revocationLookupOverride?.copy(revocationId = revocationId) ?: committedRevocation)
                ?.takeIf { it.revocationId == revocationId }
        }

        fun enableCandidatePairing() {
            candidateView = null
        }

        suspend fun enableSponsorPairing(claim: InitialWorkspaceClaim, session: SyncSession) {
            SodiumSyncPrimitives.initialize()
            val candidateSigning = SodiumSyncPrimitives.generateEd25519KeyPair()
            val candidateWrapping = SodiumSyncPrimitives.generateX25519KeyPair()
            try {
                sponsorView = ProvisioningPairingView(
                    pairingId = PAIRING_ID,
                    workspaceId = session.workspaceId,
                    sponsorDeviceId = session.deviceId,
                    sponsorSigningPublicKey = claim.device.signingPublicKey,
                    sponsorWrappingPublicKey = claim.device.wrappingPublicKey,
                    transcriptNonce = TRANSCRIPT_NONCE,
                    status = ProvisioningPairingStatus.CANDIDATE,
                    expiresAtMillis = NOW + 300_000,
                    candidate = ProvisioningPairingCandidate(
                        CANDIDATE_ID,
                        "New phone",
                        "android",
                        encodeBase64Url(candidateSigning.publicKey),
                        encodeBase64Url(candidateWrapping.publicKey),
                        encodeBase64Url(ByteArray(32) { 7 }),
                    ),
                    confirmationCode = null,
                    keyRequirements = ProvisioningPairKeyRequirements(
                        requiredKeyEpochs = listOf(1),
                        activeKeyEpoch = 1,
                        headSeq = 0,
                        retainedStableCheckpoints = emptyList(),
                        recoveryBaseCheckpointId = null,
                        recoveryBaseThroughWorkspaceSeq = 0,
                    ),
                    activation = null,
                )
            } finally {
                candidateSigning.privateKey.fill(0)
                candidateWrapping.privateKey.fill(0)
            }
        }

        private fun openPairingView() = ProvisioningPairingView(
            pairingId = PAIRING_ID,
            workspaceId = WORKSPACE_ID,
            sponsorDeviceId = SPONSOR_ID,
            sponsorSigningPublicKey = "sponsor-signing-key",
            sponsorWrappingPublicKey = "sponsor-wrapping-key",
            transcriptNonce = TRANSCRIPT_NONCE,
            status = ProvisioningPairingStatus.OPEN,
            expiresAtMillis = NOW + 300_000,
            candidate = null,
            confirmationCode = null,
            keyRequirements = null,
            activation = null,
        )

        private suspend fun candidateView(input: ProvisioningPairingCandidateInput): ProvisioningPairingView {
            val token = input.device.deviceToken.use { decodeBase64Url(it) }
            val commitment = try {
                encodeBase64Url(SodiumSyncPrimitives.sha256(token))
            } finally {
                token.fill(0)
            }
            return openPairingView().copy(
                status = ProvisioningPairingStatus.CANDIDATE,
                candidate = ProvisioningPairingCandidate(
                    input.device.deviceId,
                    input.device.displayName,
                    input.device.platform,
                    input.device.signingPublicKey,
                    input.device.wrappingPublicKey,
                    commitment,
                ),
            )
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val INSTANCE_ID = "00000000-0000-4000-8000-000000000001"
        const val INSTALLATION_ID = "00000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000004"
        const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000005"
        const val USER_ID = "00000000-0000-4000-8000-000000000006"
        const val SPONSOR_ID = "00000000-0000-4000-8000-000000000007"
        const val PAIRING_ID = "00000000-0000-4000-8000-000000000008"
        const val RESET_ID = "00000000-0000-4000-8000-000000000020"
        const val CANDIDATE_ID = "00000000-0000-4000-8000-000000000009"
        const val TRANSCRIPT_NONCE = "dHJhbnNjcmlwdC1ub25jZS0zMi1ieXRlcy1sb25nISE"
        const val BOOTSTRAP_SECRET = "Ym9vdHN0cmFwLXNlY3JldC0zMi1ieXRlcy1sb25nISEh"
        const val SETUP_LINK =
            "shinsou://sync/setup?endpoint=https%3A%2F%2Fsync.example.test&instance=$INSTANCE_ID"
        const val INVITE_LINK =
            "shinsou://sync/invite?endpoint=https%3A%2F%2Fsync.example.test&instance=$INSTANCE_ID&secret=$BOOTSTRAP_SECRET"
        const val PAIR_LINK =
            "shinsou://sync/pair?endpoint=https%3A%2F%2Fsync.example.test&instance=$INSTANCE_ID&session=$PAIRING_ID&secret=$BOOTSTRAP_SECRET"
        const val EMERGENCY_RESET_LINK =
            "shinsou://sync/emergency-reset?endpoint=https%3A%2F%2Fsync.example.test" +
                "&instance=$INSTANCE_ID&session=$RESET_ID&user=$USER_ID&workspace=$WORKSPACE_ID" +
                "&secret=$BOOTSTRAP_SECRET"

        fun readySponsorSession() = SyncSession(
            endpoint = "https://sync.example.test",
            instanceId = INSTANCE_ID,
            userId = USER_ID,
            workspaceId = WORKSPACE_ID,
            deviceId = DEVICE_ID,
            deviceDisplayName = "Test Phone",
            platform = "ios",
            status = SyncSessionStatus.READY,
            deviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            activeKeyEpoch = 1,
        )

        fun adminUsage(quota: SyncAdminQuota) = SyncAdminUsage(
            generatedAtMillis = NOW,
            quota = quota,
            totals = SyncAdminUsageTotals(2, 3, 2, 1_200, 300),
            workspaces = listOf(
                SyncAdminWorkspaceUsage(WORKSPACE_ID, "active", 9, 1_200, 300, quota.maxWorkspaceBytes),
            ),
            daily = listOf(SyncAdminDailyUsage("2026-08-20", 7, 700, 2, 2_000)),
        )

        fun deviceRevocationReceipt(
            revocationId: String = "00000000-0000-4000-8000-00000000000a",
            actorDeviceId: String = DEVICE_ID,
            targetDeviceId: String = CANDIDATE_ID,
            workspaceId: String = WORKSPACE_ID,
        ) = ProvisioningDeviceRevocationReceipt(
            revocationId = revocationId,
            actorDeviceId = actorDeviceId,
            revokedDeviceId = targetDeviceId,
            committedAtMillis = NOW,
            workspaceBindings = listOf(
                ProvisioningRevocationWorkspaceBinding(
                    workspaceId = workspaceId,
                    revokedAtKeyEpoch = 1,
                    directoryEpochAfterRevocation = 2,
                    currentActiveKeyEpoch = 1,
                    currentRotationRequired = true,
                ),
            ),
        )
    }
}
