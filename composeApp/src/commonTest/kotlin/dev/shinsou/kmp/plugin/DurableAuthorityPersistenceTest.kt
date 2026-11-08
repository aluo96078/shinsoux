package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginEventRuntimeStatus
import dev.shinsou.kmp.plugin.events.PluginHostPermission
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
    fun oversizedPersistedAuthorityCollectionsFailClosedBeforeMaterialization() = runTest {
        val backing = SilentMutationKeyValueStore()
        backing.values["plugin.trustStore.trustedTokens"] =
            List(1_025) { "\"${it}:pkg|1|${"a".repeat(64)}\"" }.joinToString(",", "[", "]")
        assertFalse(KeyValuePluginTrustStore(backing).isTrusted("pkg", 1, "a".repeat(64)))
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
