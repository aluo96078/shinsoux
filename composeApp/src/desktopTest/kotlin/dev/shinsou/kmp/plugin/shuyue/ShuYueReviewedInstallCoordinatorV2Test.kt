@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginVerifier
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.v2.BrowseOptionsSchemaV2
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionPackageV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.ImmutableExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginResultV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.SourceDescriptorV2
import dev.shinsou.kmp.plugin.v2.TextChunkStreamV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShuYueReviewedInstallCoordinatorV2Test {
    @Test
    fun stageReviewAndRejectedDecisionsStayInertUntilExactExplicitApproval() = runTest {
        val fixture = fixture()
        try {
            val review = fixture.coordinator.stage(fixture.candidate(ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY))
            assertEquals(ShuYueReviewStatusV2.REVIEWED, review.reviewStatus)
            assertEquals(0, fixture.runtimeFactory.creations)
            assertEquals(review, fixture.coordinator.review(review.quarantineId))
            assertEquals(0, fixture.runtimeFactory.creations)

            assertFailsWith<IllegalArgumentException> {
                fixture.coordinator.approveAndInstall(
                    fixture.decision(review, userConfirmed = false),
                )
            }
            assertEquals(0, fixture.runtimeFactory.creations)

            assertFailsWith<IllegalArgumentException> {
                fixture.coordinator.approveAndInstall(
                    fixture.decision(
                        review,
                        grantedPermissions = setOf(ShuYueExecutionPermissionV2.EXECUTE_SCRIPT),
                    ),
                )
            }
            assertEquals(0, fixture.runtimeFactory.creations)

            fixture.coordinator.approveAndInstall(fixture.decision(review))
            assertEquals(1, fixture.runtimeFactory.creations)
            assertEquals(
                listOf(fixture.profile.identity.packageId),
                fixture.manager.extensionDescriptorsV2().map(ExtensionPackageV2::packageId),
            )
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun legacyBackupProvenanceHasNoAutomaticExecutionPath() = runTest {
        val fixture = fixture()
        try {
            val review = fixture.coordinator.stage(fixture.candidate(ShuYueScriptProvenanceV2.LEGACY_BACKUP))
            assertEquals(ShuYueScriptProvenanceV2.LEGACY_BACKUP, review.provenance)
            assertEquals(0, fixture.runtimeFactory.creations)
            fixture.coordinator.review(review.quarantineId)
            assertEquals(0, fixture.runtimeFactory.creations)
            assertFailsWith<IllegalArgumentException> {
                fixture.coordinator.approveAndInstall(fixture.decision(review, userConfirmed = false))
            }
            assertEquals(0, fixture.runtimeFactory.creations)
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun durableApprovedInstallationRehydratesAfterRestartAndRevokedTrustFailsClosed() = runTest {
        val bytes = "durable reviewed coordinator fixture".encodeToByteArray()
        val profile = ShuYueReviewedPluginProfileV2(
            identity = ShuYueArtifactIdentityV2(
                packageId = "fixture.durable.reviewed",
                version = "1.0.0",
                versionCode = 12,
                sha256 = Sha256.hex(bytes),
            ),
            displayName = "Durable reviewed fixture",
            sourceId = "fixture.durable.source",
            sourceName = "Durable source",
            languageTag = "en",
            baseUrl = "https://fixture.example",
            capabilities = setOf(ExtensionCapability.CONTENT),
            requiredPermissions = setOf(
                ShuYueExecutionPermissionV2.EXECUTE_SCRIPT,
                ShuYueExecutionPermissionV2.NETWORK,
            ),
        )
        val keyValues = InMemoryPluginKeyValueStore()
        val durable = KeyValueShuYueReviewedStoreV2(keyValues)
        val firstManager = manager(keyValues)
        val firstAdmission = ShuYueReviewedPluginAdmissionV2(
            durable,
            durable,
            durable,
            RecordingReviewedRuntimeFactory(profile),
            listOf(profile),
        )
        val first = ShuYueReviewedInstallCoordinatorV2(
            firstAdmission,
            durable,
            firstManager,
            durable,
        )
        val review = first.stage(
            ShuYueScriptCandidateV2(
                packageId = profile.identity.packageId,
                version = profile.identity.version,
                versionCode = profile.identity.versionCode,
                sourceIds = listOf(profile.sourceId),
                bytes = bytes,
                provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
                reportedSha256 = profile.identity.sha256,
            ),
        )
        first.approveAndInstall(
            ShuYueReviewedInstallApprovalV2(
                quarantineId = review.quarantineId,
                identity = review.identity,
                grantedPermissions = review.requiredPermissions,
                userConfirmed = true,
                replaceInstalledVersion = false,
            ),
        )
        assertNotNull(firstManager.extensionSourceV2(profile.descriptor.sources.single().sourceKey))
        firstManager.close()

        val restartedManager = manager(keyValues)
        val restarted = ShuYueReviewedInstallCoordinatorV2(
            ShuYueReviewedPluginAdmissionV2(
                durable,
                durable,
                durable,
                RecordingReviewedRuntimeFactory(profile),
                listOf(profile),
            ),
            durable,
            restartedManager,
            durable,
        )
        assertEquals(
            listOf(profile.identity.packageId),
            restarted.rehydrateInstalled().installedPackageIds,
        )
        assertNotNull(restartedManager.extensionSourceV2(SourceKey(2, profile.identity.packageId, profile.sourceId)))
        restartedManager.close()

        durable.revoke(profile.identity)
        val blockedManager = manager(keyValues)
        val blocked = ShuYueReviewedInstallCoordinatorV2(
            ShuYueReviewedPluginAdmissionV2(
                durable,
                durable,
                durable,
                RecordingReviewedRuntimeFactory(profile),
                listOf(profile),
            ),
            durable,
            blockedManager,
            durable,
        ).rehydrateInstalled()
        assertEquals(listOf(profile.identity.packageId), blocked.blockedPackageIds)
        assertNull(blockedManager.extensionSourceV2(SourceKey(2, profile.identity.packageId, profile.sourceId)))
        // The marker remains available for an explicit re-approval or uninstall; it never executes.
        assertEquals(profile.identity, durable.getInstalled(profile.identity.packageId)?.identity)
        blockedManager.close()
    }

    @Test
    fun failedVersionReplacementRevokesOnlyTheNewApprovalAndKeepsTheInstalledVersionLive() = runTest {
        val packageId = "fixture.reviewed.replace"
        val oldBytes = "reviewed old version".encodeToByteArray()
        val newBytes = "reviewed broken version".encodeToByteArray()
        fun profile(versionCode: Int, bytes: ByteArray, sourceId: String) = ShuYueReviewedPluginProfileV2(
            identity = ShuYueArtifactIdentityV2(
                packageId = packageId,
                version = "1.0.$versionCode",
                versionCode = versionCode,
                sha256 = Sha256.hex(bytes),
            ),
            displayName = "Reviewed replace fixture",
            sourceId = sourceId,
            sourceName = "Replace source $versionCode",
            languageTag = "en",
            baseUrl = "https://fixture.example",
            capabilities = setOf(ExtensionCapability.CONTENT),
            requiredPermissions = setOf(
                ShuYueExecutionPermissionV2.EXECUTE_SCRIPT,
                ShuYueExecutionPermissionV2.NETWORK,
            ),
        )
        val oldProfile = profile(1, oldBytes, "fixture-old")
        val newProfile = profile(2, newBytes, "fixture-new")
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val installations = InMemoryShuYueReviewedInstallationStoreV2()
        val admission = ShuYueReviewedPluginAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = ShuYueReviewedRuntimeFactoryV2 { artifact ->
                if (artifact.identity == newProfile.identity) error("fixture runtime creation failure")
                val source = InertSource(oldProfile.descriptor.sources.single())
                ImmutableExtensionPackageRuntimeV2(oldProfile.descriptor, listOf(source))
            },
            reviewedProfiles = listOf(oldProfile, newProfile),
        )
        val keyValues = InMemoryPluginKeyValueStore()
        val manager = manager(keyValues)
        val coordinator = ShuYueReviewedInstallCoordinatorV2(
            admission = admission,
            approvals = approvals,
            manager = manager,
            installations = installations,
        )
        try {
            suspend fun stage(profile: ShuYueReviewedPluginProfileV2, bytes: ByteArray) = coordinator.stage(
                ShuYueScriptCandidateV2(
                    packageId = profile.identity.packageId,
                    version = profile.identity.version,
                    versionCode = profile.identity.versionCode,
                    sourceIds = listOf(profile.sourceId),
                    bytes = bytes,
                    provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
                    reportedSha256 = profile.identity.sha256,
                ),
            )

            val oldReview = stage(oldProfile, oldBytes)
            coordinator.approveAndInstall(
                ShuYueReviewedInstallApprovalV2(
                    quarantineId = oldReview.quarantineId,
                    identity = oldReview.identity,
                    grantedPermissions = oldReview.requiredPermissions,
                    userConfirmed = true,
                    replaceInstalledVersion = false,
                ),
            )
            val oldKey = oldProfile.descriptor.sources.single().sourceKey
            assertNotNull(manager.extensionSourceV2(oldKey))

            val newReview = stage(newProfile, newBytes)
            assertFailsWith<IllegalStateException> {
                coordinator.approveAndInstall(
                    ShuYueReviewedInstallApprovalV2(
                        quarantineId = newReview.quarantineId,
                        identity = newReview.identity,
                        grantedPermissions = newReview.requiredPermissions,
                        userConfirmed = true,
                        replaceInstalledVersion = true,
                    ),
                )
            }

            assertTrue(approvals.isTrusted(oldProfile.identity))
            assertEquals(oldProfile.requiredPermissions, approvals.grantedPermissions(oldProfile.identity))
            assertEquals(false, approvals.isTrusted(newProfile.identity))
            assertEquals(emptySet(), approvals.grantedPermissions(newProfile.identity))
            assertEquals(oldProfile.identity, coordinator.installed(packageId)?.identity)
            assertNotNull(manager.extensionSourceV2(oldKey))
            assertNull(manager.extensionSourceV2(newProfile.descriptor.sources.single().sourceKey))
        } finally {
            manager.close()
        }
    }

    private fun fixture(): Fixture {
        val bytes = "reviewed coordinator fixture".encodeToByteArray()
        val identity = ShuYueArtifactIdentityV2(
            packageId = "fixture.reviewed",
            version = "1.0.0",
            versionCode = 11,
            sha256 = Sha256.hex(bytes),
        )
        val profile = ShuYueReviewedPluginProfileV2(
            identity = identity,
            displayName = "Reviewed fixture",
            sourceId = "fixture-source",
            sourceName = "Fixture source",
            languageTag = "en",
            baseUrl = "https://fixture.example",
            capabilities = setOf(ExtensionCapability.CONTENT),
            requiredPermissions = setOf(
                ShuYueExecutionPermissionV2.EXECUTE_SCRIPT,
                ShuYueExecutionPermissionV2.NETWORK,
            ),
        )
        val runtimeFactory = RecordingReviewedRuntimeFactory(profile)
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val admission = ShuYueReviewedPluginAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = runtimeFactory,
            reviewedProfiles = listOf(profile),
        )
        val keyValues = InMemoryPluginKeyValueStore()
        val manager = manager(keyValues)
        return Fixture(
            bytes = bytes,
            profile = profile,
            runtimeFactory = runtimeFactory,
            manager = manager,
            coordinator = ShuYueReviewedInstallCoordinatorV2(admission, approvals, manager),
        )
    }

    private fun manager(keyValues: InMemoryPluginKeyValueStore): PluginManager {
        val storage = KeyValuePluginStorage(keyValues)
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("Network must not run in coordinator test") },
            storage = storage,
        )
        return PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
            ),
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(network, storage),
        )
    }

    private data class Fixture(
        val bytes: ByteArray,
        val profile: ShuYueReviewedPluginProfileV2,
        val runtimeFactory: RecordingReviewedRuntimeFactory,
        val manager: PluginManager,
        val coordinator: ShuYueReviewedInstallCoordinatorV2,
    ) {
        fun candidate(provenance: ShuYueScriptProvenanceV2): ShuYueScriptCandidateV2 =
            ShuYueScriptCandidateV2(
                packageId = profile.identity.packageId,
                version = profile.identity.version,
                versionCode = profile.identity.versionCode,
                sourceIds = listOf(profile.sourceId),
                bytes = bytes,
                provenance = provenance,
                reportedSha256 = profile.identity.sha256,
            )

        fun decision(
            review: ShuYueQuarantineReviewV2,
            grantedPermissions: Set<ShuYueExecutionPermissionV2> = review.requiredPermissions,
            userConfirmed: Boolean = true,
        ): ShuYueReviewedInstallApprovalV2 = ShuYueReviewedInstallApprovalV2(
            quarantineId = review.quarantineId,
            identity = review.identity,
            grantedPermissions = grantedPermissions,
            userConfirmed = userConfirmed,
            replaceInstalledVersion = false,
        )
    }

    private class RecordingReviewedRuntimeFactory(
        private val profile: ShuYueReviewedPluginProfileV2,
    ) : ShuYueReviewedRuntimeFactoryV2 {
        var creations: Int = 0

        override suspend fun create(artifact: ShuYueAdmittedScriptV2): ImmutableExtensionPackageRuntimeV2 {
            creations++
            val source = InertSource(profile.descriptor.sources.single())
            return ImmutableExtensionPackageRuntimeV2(profile.descriptor, listOf(source))
        }
    }

    private class InertSource(
        override val descriptor: SourceDescriptorV2,
    ) : ExtensionSourceV2 {
        override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
        override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
            error("not used")
        override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = error("not used")
        override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
            error("not used")
        override suspend fun details(remotePublicationId: String): RemotePublicationV2 = error("not used")
        override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
            error("not used")
        override suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 =
            error("not used")
        override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = error("not used")
        override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 = error("not used")
        override suspend fun logout(): Unit = error("not used")
        override suspend fun preferences(): List<PreferenceV2> = emptyList()
        override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = Unit
    }
}
