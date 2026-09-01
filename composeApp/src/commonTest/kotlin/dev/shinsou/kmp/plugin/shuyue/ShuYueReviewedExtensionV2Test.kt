@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.v2.BrowseOptionsSchemaV2
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionHostFacadeV2
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
import dev.shinsou.kmp.plugin.v2.TextPayloadSourceV2
import dev.shinsou.kmp.plugin.v2.UnitContentPayload
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShuYueReviewedExtensionV2Test {
    @Test
    fun quarantineNeverExecutesAndAdmissionRequiresExactTrustDigestAndPermissions() = runTest {
        val script = "reviewed fixture script".encodeToByteArray()
        val profile = profile(Sha256.hex(script))
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val runtimeFactory = RecordingFactory(profile)
        val admission = ShuYueReviewedPluginAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = runtimeFactory,
            reviewedProfiles = listOf(profile),
        )
        val staged = admission.quarantine(candidate(script))

        assertEquals(ShuYueReviewStatusV2.REVIEWED, staged.reviewStatus)
        assertEquals(0, runtimeFactory.creations)
        assertFailsWith<ShuYueAdmissionException.NotTrusted> {
            admission.createRuntime(staged.quarantineId)
        }
        assertEquals(0, runtimeFactory.creations)

        approvals.trust(staged.identity)
        approvals.grant(staged.identity, setOf(ShuYueExecutionPermissionV2.EXECUTE_SCRIPT))
        val missing = assertFailsWith<ShuYueAdmissionException.MissingPermissions> {
            admission.createRuntime(staged.quarantineId)
        }
        assertEquals(setOf(ShuYueExecutionPermissionV2.NETWORK), missing.missing)
        assertEquals(0, runtimeFactory.creations)

        approvals.grant(staged.identity, profile.requiredPermissions)
        val runtime = admission.createRuntime(staged.quarantineId)
        assertEquals(1, runtimeFactory.creations)
        assertContentEquals(script, runtimeFactory.receivedBytes)

        val source = requireNotNull(ExtensionHostFacadeV2(runtime).source(profile.descriptor.sources.single().sourceKey))
        assertEquals("body", assertIs<UnitContentPayload.InlineTextPayload>(
            source.content("book", "chapter").representations.single(),
        ).source.text)
        assertEquals(1, runtimeFactory.contentCalls)

        approvals.revokeTrust(staged.identity)
        assertFailsWith<ShuYueAdmissionException.NotTrusted> { source.content("book", "chapter") }
        assertEquals(1, runtimeFactory.contentCalls)
    }

    @Test
    fun mismatchedReportedDigestRemainsQuarantinedButCannotBeAdmitted() = runTest {
        val script = "reviewed fixture script".encodeToByteArray()
        val profile = profile(Sha256.hex(script))
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val factory = RecordingFactory(profile)
        val admission = ShuYueReviewedPluginAdmissionV2(
            InMemoryShuYueScriptQuarantineStoreV2(),
            approvals,
            approvals,
            factory,
            listOf(profile),
        )
        val staged = admission.quarantine(candidate(script, reported = "0".repeat(64)))
        approvals.trust(staged.identity)
        approvals.grant(staged.identity, profile.requiredPermissions)

        assertEquals(ShuYueReviewStatusV2.DIGEST_MISMATCH, staged.reviewStatus)
        assertFailsWith<ShuYueAdmissionException.NotReviewed> {
            admission.createRuntime(staged.quarantineId)
        }
        assertEquals(0, factory.creations)
    }

    @Test
    fun duplicatePackageVersionIsAdmittedOnlyForItsExactPinnedDigest() = runTest {
        val firstBytes = "reviewed v2 body A".encodeToByteArray()
        val secondBytes = "reviewed v2 body B".encodeToByteArray()
        val firstProfile = profile(Sha256.hex(firstBytes))
        val secondProfile = profile(Sha256.hex(secondBytes))
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val factory = RecordingFactory(firstProfile)
        val admission = ShuYueReviewedPluginAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = factory,
            reviewedProfiles = listOf(firstProfile, secondProfile),
        )

        val first = admission.quarantine(candidate(firstBytes))
        val second = admission.quarantine(candidate(secondBytes))
        assertEquals(ShuYueReviewStatusV2.REVIEWED, first.reviewStatus)
        assertEquals(ShuYueReviewStatusV2.REVIEWED, second.reviewStatus)
        assertEquals(firstProfile.identity, first.identity)
        assertEquals(secondProfile.identity, second.identity)

        approvals.trust(first.identity)
        approvals.grant(first.identity, firstProfile.requiredPermissions)
        approvals.trust(second.identity)
        approvals.grant(second.identity, secondProfile.requiredPermissions)
        admission.createRuntime(first.quarantineId)
        admission.createRuntime(second.quarantineId)
        assertEquals(2, factory.creations)

        val unknownDigest = admission.quarantine(
            candidate("reviewed v2 body C".encodeToByteArray()),
        )
        assertEquals(ShuYueReviewStatusV2.DIGEST_MISMATCH, unknownDigest.reviewStatus)
    }

    @Test
    fun admittedFactoryCannotReplaceTheReviewedRuntimeDescriptor() = runTest {
        val script = "reviewed fixture script".encodeToByteArray()
        val profile = profile(Sha256.hex(script))
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val wrongDescriptor = profile.descriptor.copy(version = "9.9.9")
        val admission = ShuYueReviewedPluginAdmissionV2(
            InMemoryShuYueScriptQuarantineStoreV2(),
            approvals,
            approvals,
            ShuYueReviewedRuntimeFactoryV2 {
                ImmutableExtensionPackageRuntimeV2(
                    wrongDescriptor,
                    listOf(RecordingSource(wrongDescriptor.sources.single()) {}),
                )
            },
            listOf(profile),
        )
        val staged = admission.quarantine(candidate(script))
        approvals.trust(staged.identity)
        approvals.grant(staged.identity, profile.requiredPermissions)

        assertFailsWith<ShuYueAdmissionException.RuntimeMismatch> {
            admission.createRuntime(staged.quarantineId)
        }
    }

    @Test
    fun builtInReviewedCataloguePinsWenkuBiqugeAndBiliMangaOpaqueIdsAndDigests() {
        assertEquals(
            setOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw", "zh.bilimanga"),
            ShuYueReviewedPluginCatalogV2.profiles.map { it.identity.packageId }.toSet(),
        )
        ShuYueReviewedPluginCatalogV2.profiles.forEach { profile ->
            assertEquals(profile.sourceId, profile.descriptor.sources.first().sourceKey.sourceId)
            profile.sourceIds.forEach { sourceId ->
                assertEquals(
                    SourceKey(2, profile.identity.packageId, sourceId),
                    ShuYueReviewedPluginCatalogV2.sourceKeyForLegacySourceId(sourceId),
                )
            }
            assertEquals(64, profile.identity.sha256.length)
            assertEquals(
                profile.sourceProfiles.flatMap { it.supportedContentKinds }.toSet(),
                profile.descriptor.supportedContentKinds,
            )
        }
        assertEquals(null, ShuYueReviewedPluginCatalogV2.sourceKeyForLegacySourceId("unknown.source"))
        assertEquals(
            setOf("zh.wenku8.api", "zh.biquge.tw", "zh.bilimanga"),
            ShuYueReviewedPluginCatalogV2.installableProfiles.map { it.identity.packageId }.toSet(),
        )
        assertTrue(
            ShuYueReviewedPluginCatalogV2.profiles.filter { it.identity.packageId == "zh.wenku8" }
                .all { it.legacyCompatibilityOnly },
        )
        assertEquals(
            "aaa7875360a52dd3393288bbb4f1e85d38ddd6a42041a0e489d7585db8bb5996",
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile("zh.wenku8.api", "1.0.4", 5, null)
                ?.identity?.sha256,
        )
        assertEquals(
            "5a9d1ac0d8263629e82332a88b2a7ed4eb6efb857804a8ae6ae946b2eb23b627",
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.wenku8.api",
                "1.0.4",
                5,
                "5a9d1ac0d8263629e82332a88b2a7ed4eb6efb857804a8ae6ae946b2eb23b627",
            )?.identity?.sha256,
        )
        assertEquals(
            null,
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.wenku8.api",
                "1.0.4",
                5,
                "f".repeat(64),
            ),
        )
        val wenkuApiProfiles = ShuYueReviewedPluginCatalogV2.profiles
            .filter { it.identity.packageId == "zh.wenku8.api" }
        assertTrue(wenkuApiProfiles.all { ExtensionCapability.FAVORITE !in it.capabilities })
        assertTrue(
            wenkuApiProfiles.all {
                ShuYueExecutionPermissionV2.FAVORITE_MUTATION !in it.requiredPermissions
            },
        )
        assertEquals(
            "75e67a5937b9a93956f71e1f97f8738fbdabce6b7e7090c90779479e32cae56c",
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.wenku8.api",
                "1.0.5",
                6,
                "75e67a5937b9a93956f71e1f97f8738fbdabce6b7e7090c90779479e32cae56c",
            )?.identity?.sha256,
        )
        val biliProfiles = ShuYueReviewedPluginCatalogV2.profiles
            .filter { it.identity.packageId == "zh.bilimanga" }
            .sortedBy { it.identity.versionCode }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), biliProfiles.map { it.identity.versionCode })
        val currentBili = requireNotNull(
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.bilimanga",
                "1.5.2",
                8,
                "961c4ee367bba45035825f38dc64fefad5431ddfbc548afd04025c4adaf40a99",
            ),
        )
        assertTrue(ExtensionCapability.LOGIN in currentBili.capabilities)
        assertTrue(ShuYueExecutionPermissionV2.COOKIE_STORAGE in currentBili.requiredPermissions)
        assertTrue(ShuYueExecutionPermissionV2.CREDENTIAL_ACCESS in currentBili.requiredPermissions)
        assertTrue(ShuYueExecutionPermissionV2.LOGIN_PROMPT in currentBili.requiredPermissions)
        assertTrue(ShuYueExecutionPermissionV2.BROWSER_CHALLENGE in currentBili.requiredPermissions)
        val challengeBili = requireNotNull(
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.bilimanga",
                "1.5.3",
                9,
                "be9855b88606ee13187b4236bfb04b01dacabbd2db35b3686aef61897f5cb63b",
            ),
        )
        assertEquals(
            "https://www.bilimanga.net/login.php",
            challengeBili.sourceProfiles.single { it.sourceId == "zh.bilimanga.manga" }.webChallengeUrl,
        )
        assertNull(challengeBili.sourceProfiles.single { it.sourceId == "zh.bilimanga.novel" }.webChallengeUrl)
        val currentChallengeBili = requireNotNull(
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.bilimanga",
                "1.5.4",
                10,
                "949464e17bb9478c07a8ae03aee76ca81afe4bb57d574a3098735bb236850ee1",
            ),
        )
        assertEquals(
            "https://tw.linovelib.com/login.php",
            currentChallengeBili.sourceProfiles.single { it.sourceId == "zh.bilimanga.novel" }.webChallengeUrl,
        )
        assertEquals(
            "https://www.bilimanga.net/login.php",
            currentChallengeBili.sourceProfiles.single { it.sourceId == "zh.bilimanga.manga" }.webChallengeUrl,
        )
        val browserSessionBili = requireNotNull(
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.bilimanga",
                "1.5.5",
                11,
                "c2481811290f18ce91d8274c76081faa04f1ca29b091f0205e842c37a0d1f0e8",
            ),
        )
        assertEquals(
            setOf("zh.bilimanga.novel", "zh.bilimanga.manga"),
            browserSessionBili.sourceProfiles.map { it.sourceId }.toSet(),
        )
        val splitBiliNovel = requireNotNull(
            ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                "zh.bilimanga",
                "1.6.0",
                12,
                "f740eb9fb1da98774f32aa8a555829a828ea799fc6987781ad594a4a6eceb0f5",
            ),
        )
        assertEquals(listOf("zh.bilimanga.novel"), splitBiliNovel.sourceIds)
        assertEquals(setOf(ContentKind.PLAIN_TEXT), splitBiliNovel.sourceProfiles.single().supportedContentKinds)
    }

    private fun candidate(bytes: ByteArray, reported: String? = Sha256.hex(bytes)): ShuYueScriptCandidateV2 =
        ShuYueScriptCandidateV2(
            packageId = "fixture.shuyue",
            version = "1.0.0",
            versionCode = 1,
            sourceIds = listOf("fixture.source"),
            bytes = bytes,
            provenance = ShuYueScriptProvenanceV2.LEGACY_BACKUP,
            reportedSha256 = reported,
        )

    private fun profile(digest: String): ShuYueReviewedPluginProfileV2 = ShuYueReviewedPluginProfileV2(
        identity = ShuYueArtifactIdentityV2("fixture.shuyue", "1.0.0", 1, digest),
        displayName = "Fixture",
        sourceId = "fixture.source",
        sourceName = "Fixture source",
        languageTag = "en",
        baseUrl = "https://fixture.example",
        capabilities = setOf(ExtensionCapability.CONTENT),
        requiredPermissions = setOf(
            ShuYueExecutionPermissionV2.EXECUTE_SCRIPT,
            ShuYueExecutionPermissionV2.NETWORK,
        ),
    )

    private class RecordingFactory(
        private val profile: ShuYueReviewedPluginProfileV2,
    ) : ShuYueReviewedRuntimeFactoryV2 {
        var creations: Int = 0
        var contentCalls: Int = 0
        var receivedBytes: ByteArray = ByteArray(0)

        override suspend fun create(artifact: ShuYueAdmittedScriptV2): ImmutableExtensionPackageRuntimeV2 {
            creations++
            receivedBytes = artifact.copyBytes()
            return ImmutableExtensionPackageRuntimeV2(
                profile.descriptor,
                listOf(RecordingSource(profile.descriptor.sources.single()) { contentCalls++ }),
            )
        }
    }

    private class RecordingSource(
        override val descriptor: SourceDescriptorV2,
        private val onContent: () -> Unit,
    ) : ExtensionSourceV2 {
        override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
        override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
            PagedResultV2(emptyList(), false)
        override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> =
            PagedResultV2(emptyList(), false)
        override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
            PagedResultV2(emptyList(), false)
        override suspend fun details(remotePublicationId: String): RemotePublicationV2 =
            RemotePublicationV2(remotePublicationId, "Book")
        override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
            PagedResultV2(emptyList(), false)
        override suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 {
            onContent()
            return UnitContentResultV2(
                schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                sourceKey = descriptor.sourceKey,
                remotePublicationId = remotePublicationId,
                remoteUnitId = remoteUnitId,
                representations = listOf(
                    UnitContentPayload.InlineTextPayload(
                        schemaVersion = 2,
                        representationId = "text",
                        sourceKey = descriptor.sourceKey,
                        remoteUnitId = remoteUnitId,
                        source = TextPayloadSourceV2.InlineTextPayload("body"),
                        blocks = listOf(TextBlock("body", 0, 4)),
                    ),
                ),
            )
        }
        override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = error("not used")
        override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 = LoginResultV2(false)
        override suspend fun logout(): Unit = Unit
        override suspend fun preferences(): List<PreferenceV2> = emptyList()
        override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = Unit
    }
}
