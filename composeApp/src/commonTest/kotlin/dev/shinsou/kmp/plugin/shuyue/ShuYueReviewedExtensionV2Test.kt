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
    fun builtInReviewedCataloguePinsWenkuAndBiqugeOpaqueIdsAndDigests() {
        assertEquals(
            listOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw"),
            ShuYueReviewedPluginCatalogV2.profiles.map { it.identity.packageId },
        )
        ShuYueReviewedPluginCatalogV2.profiles.forEach { profile ->
            assertEquals(profile.identity.packageId, profile.sourceId)
            assertEquals(profile.sourceId, profile.descriptor.sources.single().sourceKey.sourceId)
            assertEquals(
                profile.descriptor.sources.single().sourceKey,
                ShuYueReviewedPluginCatalogV2.sourceKeyForLegacySourceId(profile.sourceId),
            )
            assertEquals(64, profile.identity.sha256.length)
            assertEquals(setOf(ContentKind.PLAIN_TEXT), profile.descriptor.supportedContentKinds)
        }
        assertEquals(null, ShuYueReviewedPluginCatalogV2.sourceKeyForLegacySourceId("unknown.source"))
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
