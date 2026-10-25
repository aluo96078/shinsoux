package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginEventRuntimeStatus
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.shuyue.KeyValueShuYueReviewedStoreV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueArtifactIdentityV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueExecutionPermissionV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueQuarantinedScriptV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewStatusV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedInstallationV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueScriptProvenanceV2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableAuthorityPersistenceTest {
    @Test
    fun pluginTrustRejectsSilentlyDroppedGrantAndRevocationWrites() = runTest {
        val backing = SilentMutationKeyValueStore()
        val trust = KeyValuePluginTrustStore(backing)
        val digest = "a".repeat(64)

        backing.dropPut = { _, _ -> true }
        assertFailsWith<IllegalStateException> { trust.trust("pkg.test", 1, digest) }
        assertFalse(trust.isTrusted("pkg.test", 1, digest))

        backing.dropPut = { _, _ -> false }
        trust.trust("pkg.test", 1, digest)
        assertTrue(KeyValuePluginTrustStore(backing).isTrusted("pkg.test", 1, digest))

        backing.dropPut = { _, _ -> true }
        assertFailsWith<IllegalStateException> { trust.revokeAll("pkg.test") }
        // The caller was told that revocation did not commit; the prior durable grant remains.
        assertTrue(KeyValuePluginTrustStore(backing).isTrusted("pkg.test", 1, digest))

        backing.dropPut = { _, _ -> false }
        trust.revokeAll("pkg.test")
        assertFalse(KeyValuePluginTrustStore(backing).isTrusted("pkg.test", 1, digest))
    }

    @Test
    fun eventGrantNeverBecomesLiveWhenGrantCommitIsSilentlyDropped() = runTest {
        val backing = SilentMutationKeyValueStore()
        val authorizer = MutablePluginSystemEventAuthorizer()
        val admission = KeyValuePluginEventGrantAdmission(backing, authorizer)
        val review = eventReview()
        admission.stage(review)

        backing.dropPut = { key, value ->
            key.startsWith("plugin.events.admission.v2.") && "\"state\":\"GRANTED\"" in value
        }
        assertFailsWith<IllegalStateException> {
            admission.approve(review.artifact, review.requestedPermissions)
        }

        assertFalse(eventAllowed(authorizer, review))
        assertFalse(
            KeyValuePluginEventGrantAdmission(backing, MutablePluginSystemEventAuthorizer())
                .isGranted(review),
        )
    }

    @Test
    fun eventRevocationDetectsSilentTombstoneLossAndStillDeniesThisProcess() = runTest {
        val backing = SilentMutationKeyValueStore()
        val authorizer = MutablePluginSystemEventAuthorizer()
        val admission = KeyValuePluginEventGrantAdmission(backing, authorizer)
        val review = eventReview()
        admission.stage(review)
        admission.approve(review.artifact, review.requestedPermissions)
        assertTrue(eventAllowed(authorizer, review))

        backing.dropPut = { key, value ->
            key.startsWith("plugin.events.admission.v2.") && "\"state\":\"REVOKED\"" in value
        }
        assertFailsWith<IllegalStateException> { admission.revoke(review.artifact) }

        assertFalse(eventAllowed(authorizer, review))
        assertFalse(admission.isGranted(review))
    }

    @Test
    fun shuYueApprovalAndRevocationUseVerifiedTrustCommitMarkers() = runTest {
        val backing = SilentMutationKeyValueStore()
        val store = KeyValueShuYueReviewedStoreV2(backing)
        val identity = shuYueIdentity()
        val permissions = setOf(
            ShuYueExecutionPermissionV2.EXECUTE_SCRIPT,
            ShuYueExecutionPermissionV2.NETWORK,
        )

        backing.dropPut = { key, value -> key.endsWith(".trust") && value == "true" }
        assertFailsWith<IllegalStateException> { store.approve(identity, permissions) }
        assertFalse(store.isTrusted(identity))
        assertFalse(KeyValueShuYueReviewedStoreV2(backing).isTrusted(identity))

        backing.dropPut = { _, _ -> false }
        store.approve(identity, permissions)
        assertTrue(KeyValueShuYueReviewedStoreV2(backing).isTrusted(identity))

        backing.dropPut = { key, value -> key.endsWith(".trust") && value == "false" }
        assertFailsWith<IllegalStateException> { store.revoke(identity) }
        assertFalse(store.isTrusted(identity))

        backing.dropPut = { _, _ -> false }
        store.revoke(identity)
        assertFalse(KeyValueShuYueReviewedStoreV2(backing).isTrusted(identity))

        store.approve(identity, permissions)
        backing.dropRemove = { key -> key.endsWith(".permissions") }
        assertFailsWith<IllegalStateException> { store.revoke(identity) }
        assertFalse(store.isTrusted(identity))
        assertFalse(KeyValueShuYueReviewedStoreV2(backing).isTrusted(identity))
    }

    @Test
    fun shuYueQuarantineAndInstallationCommitsRequireExactReadBack() = runTest {
        val backing = SilentMutationKeyValueStore()
        val store = KeyValueShuYueReviewedStoreV2(backing)
        val bytes = "reviewed".encodeToByteArray()
        val identity = shuYueIdentity(bytes)
        val quarantine = ShuYueQuarantinedScriptV2(
            quarantineId = "q-1",
            identity = identity,
            sourceIds = listOf("source"),
            bytes = bytes,
            provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
            stagedReviewStatus = ShuYueReviewStatusV2.REVIEWED,
        )

        backing.dropPut = { key, _ -> key.startsWith("plugin.shuyue.v2.quarantine.") }
        assertFailsWith<IllegalStateException> { store.put(quarantine) }

        backing.dropPut = { key, _ -> key == "plugin.shuyue.v2.installations" }
        assertFailsWith<IllegalStateException> {
            store.putInstalled(ShuYueReviewedInstallationV2(quarantine.quarantineId, identity))
        }

        backing.dropPut = { _, _ -> false }
        store.putInstalled(ShuYueReviewedInstallationV2(quarantine.quarantineId, identity))
        backing.dropRemove = { key -> key == "plugin.shuyue.v2.installations" }
        assertFailsWith<IllegalStateException> { store.removeInstalled(identity.packageId) }
        assertTrue(KeyValueShuYueReviewedStoreV2(backing).getInstalled(identity.packageId) != null)
    }

    @Test
    fun oversizedPersistedAuthorityCollectionsFailClosedBeforeMaterialization() = runTest {
        val backing = SilentMutationKeyValueStore()
        backing.values["plugin.trustStore.trustedTokens"] =
            List(1_025) { "\"${it}:pkg|1|${"a".repeat(64)}\"" }.joinToString(",", "[", "]")
        assertFalse(KeyValuePluginTrustStore(backing).isTrusted("pkg", 1, "a".repeat(64)))

        val identity = shuYueIdentity()
        val identityKey = approvalIdentityKey(identity)
        backing.values["plugin.shuyue.v2.approval.$identityKey.permissions"] =
            List(ShuYueExecutionPermissionV2.entries.size + 1) { "\"EXECUTE_SCRIPT\"" }
                .joinToString(",", "[", "]")
        assertTrue(KeyValueShuYueReviewedStoreV2(backing).grantedPermissions(identity).isEmpty())
    }

    @Test
    fun repositorySecurityStateRejectsSilentlyDroppedWatermark() = runTest {
        val backing = SilentMutationKeyValueStore().apply { dropPut = { _, _ -> true } }
        val state = KeyValueRepositorySecurityStateStore(backing)

        assertFailsWith<IllegalStateException> {
            state.admitArtifact("https://repo.example", "pkg", 1, "a".repeat(64))
        }
        // No watermark was admitted, so a lower independent value is still accepted for checking.
        state.requireArtifactNotDowngraded("https://repo.example", "pkg", 0, "b".repeat(64))
    }

    private fun eventReview(): PluginEventGrantReview = PluginEventGrantReview(
        artifact = PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
        sourceKeys = listOf(SourceKey(packageId = "pkg.test", sourceId = "source")),
        requestedPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
    )

    private fun eventAllowed(
        authorizer: MutablePluginSystemEventAuthorizer,
        review: PluginEventGrantReview,
    ): Boolean {
        val scope = BoundPluginScopeFactory().bind(
            review.artifact,
            review.sourceKeys.single(),
            "runtime",
            1,
        )
        authorizer.setRuntimeStatus(scope, PluginEventRuntimeStatus(sourceCapabilities = setOf("CATALOGUE")))
        return authorizer.authorize(scope, PluginHostPermission.REPORT_DIAGNOSTIC, null).allowed
    }

    private fun shuYueIdentity(bytes: ByteArray = "reviewed".encodeToByteArray()) =
        ShuYueArtifactIdentityV2("fixture.shuyue", "1.0.0", 1, Sha256.hex(bytes))

    private fun approvalIdentityKey(identity: ShuYueArtifactIdentityV2): String = Sha256.hex(
        listOf(
            identity.packageId,
            identity.version,
            identity.versionCode.toString(),
            identity.sha256,
        ).joinToString("|") { value -> "${value.encodeToByteArray().size}:$value" }.encodeToByteArray(),
    )
}

private class SilentMutationKeyValueStore : PluginKeyValueStore {
    val values = linkedMapOf<String, String>()
    var dropPut: (String, String) -> Boolean = { _, _ -> false }
    var dropRemove: (String) -> Boolean = { false }

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        if (!dropPut(key, value)) values[key] = value
    }

    override suspend fun remove(key: String) {
        if (!dropRemove(key)) values.remove(key)
    }
}
