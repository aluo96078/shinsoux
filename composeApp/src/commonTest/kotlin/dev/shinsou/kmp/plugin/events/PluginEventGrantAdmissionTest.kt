package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginEventGrantAdmissionTest {
    @Test
    fun approvedExactGrantHydratesAfterRestartAndRevokes() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val firstAuthorizer = MutablePluginSystemEventAuthorizer()
        val first = KeyValuePluginEventGrantAdmission(store, firstAuthorizer)
        val review = review()
        first.stage(review)
        first.approve(review.artifact, review.requestedPermissions)
        assertTrue(allowed(firstAuthorizer, review))

        val restartedAuthorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, restartedAuthorizer)
        restarted.hydrate(review)
        assertTrue(allowed(restartedAuthorizer, review))
        restarted.revoke(review.artifact)
        assertFalse(allowed(restartedAuthorizer, review))
        assertNull(restarted.pending(review.artifact))
    }

    @Test
    fun approvalMustMatchVerifiedRequestedSet() = runTest {
        val admission = KeyValuePluginEventGrantAdmission(
            InMemoryPluginKeyValueStore(),
            MutablePluginSystemEventAuthorizer(),
        )
        val review = review()
        admission.stage(review)
        assertFailsWith<IllegalArgumentException> {
            admission.approve(review.artifact, emptySet())
        }
        assertEquals(review, admission.pending(review.artifact))
    }

    @Test
    fun changedPermissionSetFailsClosedAndRequiresNewReview() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val original = review()
        KeyValuePluginEventGrantAdmission(store, MutablePluginSystemEventAuthorizer()).apply {
            stage(original)
            approve(original.artifact, original.requestedPermissions)
        }
        val changed = original.copy(
            requestedPermissions = original.requestedPermissions + PluginHostPermission.REQUEST_LOGOUT,
        )
        val authorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, authorizer)
        restarted.hydrate(changed)
        assertFalse(allowed(authorizer, changed))
        assertEquals(changed, restarted.pending(changed.artifact))
    }

    @Test
    fun changedExactSourceSetFailsClosedAndRequiresNewReview() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val original = review()
        KeyValuePluginEventGrantAdmission(store, MutablePluginSystemEventAuthorizer()).apply {
            stage(original)
            approve(original.artifact, original.requestedPermissions)
        }
        val changed = original.copy(
            sourceKeys = listOf(SourceKey(packageId = "pkg.test", sourceId = "replacement")),
        )
        val authorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, authorizer)
        restarted.hydrate(changed)
        assertFalse(allowed(authorizer, changed))
        assertEquals(changed, restarted.pending(changed.artifact))
    }

    @Test
    fun changedRuntimePermissionSetFailsClosedAndRequiresNewReview() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val original = review().copy(
            requestedRuntimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )
        KeyValuePluginEventGrantAdmission(store, MutablePluginSystemEventAuthorizer()).apply {
            stage(original)
            approve(original.artifact, original.requestedPermissions)
        }
        val changed = original.copy(
            requestedRuntimePermissions = original.requestedRuntimePermissions + PluginRuntimePermission.NETWORK,
        )

        val authorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, authorizer)
        restarted.hydrate(changed)

        assertFalse(allowed(authorizer, changed))
        assertEquals(changed, restarted.pending(changed.artifact))
    }

    @Test
    fun dottedPackageAndVersionSegmentsCannotCollideInDurableKeys() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val admission = KeyValuePluginEventGrantAdmission(store, MutablePluginSystemEventAuthorizer())
        val first = review().copy(
            artifact = PluginArtifactIdentity("a.b", "c", 1, "a".repeat(64)),
            sourceKeys = listOf(SourceKey(packageId = "a.b", sourceId = "source")),
        )
        val second = review().copy(
            artifact = PluginArtifactIdentity("a", "b.c", 1, "a".repeat(64)),
            sourceKeys = listOf(SourceKey(packageId = "a", sourceId = "source")),
        )
        admission.stage(first)
        admission.stage(second)
        assertEquals(first, admission.pending(first.artifact))
        assertEquals(second, admission.pending(second.artifact))
    }

    @Test
    fun userMessageGrantFailsClosedWithoutProductionPresenter() = runTest {
        val admission = KeyValuePluginEventGrantAdmission(
            InMemoryPluginKeyValueStore(),
            MutablePluginSystemEventAuthorizer(),
        )
        assertFailsWith<IllegalArgumentException> {
            admission.stage(
                review().copy(
                    requestedPermissions = setOf(
                        PluginHostPermission.REPORT_DIAGNOSTIC,
                        PluginHostPermission.REPORT_USER_MESSAGE,
                    ),
                ),
            )
        }
    }

    @Test
    fun approvalCommitsOneCompleteAuthoritativeRecord() = runTest {
        val store = RecordingKeyValueStore()
        val admission = KeyValuePluginEventGrantAdmission(store, MutablePluginSystemEventAuthorizer())
        val review = review().copy(
            requestedRuntimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )

        admission.stage(review)
        store.putKeys.clear()
        admission.approve(review.artifact, review.requestedPermissions)

        assertEquals(1, store.putKeys.size)
        assertTrue(store.putKeys.single().startsWith("plugin.events.admission.v2."))
        val restartedAuthorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, restartedAuthorizer)
        restarted.hydrate(review)
        assertTrue(allowed(restartedAuthorizer, review))
    }

    @Test
    fun partialLegacyGrantIsPermanentlyShadowedAndCannotRevive() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val review = review()
        val eventGrant = PluginEventGrant(
            PluginEventGrantKey(review.artifact, review.sourceKeys.single()),
            review.requestedPermissions,
        )
        store.putString(
            legacyGrantKey(review.artifact),
            Json.encodeToString(ListSerializer(PluginEventGrant.serializer()), listOf(eventGrant)),
        )

        val firstAuthorizer = MutablePluginSystemEventAuthorizer()
        val first = KeyValuePluginEventGrantAdmission(store, firstAuthorizer)
        first.hydrate(review)
        assertFalse(allowed(firstAuthorizer, review))
        assertEquals(review, first.pending(review.artifact))

        // Simulate a delayed/rolled-back legacy writer restoring both former halves. The v2
        // pending decision remains authoritative and this must not reconstruct a grant.
        store.putString(
            legacyGrantKey(review.artifact),
            Json.encodeToString(ListSerializer(PluginEventGrant.serializer()), listOf(eventGrant)),
        )
        store.putString(
            legacyRuntimeGrantKey(review.artifact),
            legacyRuntimeApprovalJson(review),
        )
        val restartedAuthorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, restartedAuthorizer)
        restarted.hydrate(review)
        assertFalse(allowed(restartedAuthorizer, review))
        assertEquals(review, restarted.pending(review.artifact))
    }

    @Test
    fun corruptLegacyPendingCannotBeCombinedWithStaleGrantHalves() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val review = review()
        val eventGrant = PluginEventGrant(
            PluginEventGrantKey(review.artifact, review.sourceKeys.single()),
            review.requestedPermissions,
        )
        store.putString(legacyPendingKey(review.artifact), "{corrupt")
        store.putString(
            legacyGrantKey(review.artifact),
            Json.encodeToString(ListSerializer(PluginEventGrant.serializer()), listOf(eventGrant)),
        )
        store.putString(legacyRuntimeGrantKey(review.artifact), legacyRuntimeApprovalJson(review))

        val authorizer = MutablePluginSystemEventAuthorizer()
        val admission = KeyValuePluginEventGrantAdmission(store, authorizer)
        admission.hydrate(review)

        assertFalse(allowed(authorizer, review))
        assertEquals(review, admission.pending(review.artifact))
    }

    @Test
    fun completeMatchingLegacyGrantMigratesOnce() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val review = review().copy(
            requestedRuntimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )
        val eventGrant = PluginEventGrant(
            PluginEventGrantKey(review.artifact, review.sourceKeys.single()),
            review.requestedPermissions,
        )
        store.putString(
            legacyGrantKey(review.artifact),
            Json.encodeToString(ListSerializer(PluginEventGrant.serializer()), listOf(eventGrant)),
        )
        store.putString(legacyRuntimeGrantKey(review.artifact), legacyRuntimeApprovalJson(review))

        val authorizer = MutablePluginSystemEventAuthorizer()
        val admission = KeyValuePluginEventGrantAdmission(store, authorizer)
        admission.hydrate(review)

        assertTrue(allowed(authorizer, review))
        assertNull(store.getString(legacyGrantKey(review.artifact)))
        assertNull(store.getString(legacyRuntimeGrantKey(review.artifact)))
    }

    @Test
    fun corruptAndOversizedAuthoritativeRecordsFailClosedIntoFreshReview() = runTest {
        val review = review()
        listOf("{not-json", "x".repeat(300_000)).forEach { corruptValue ->
            val store = InMemoryPluginKeyValueStore()
            store.putString(authoritativeKey(review.artifact), corruptValue)
            // Even a complete stale legacy grant cannot bypass a present v2 record.
            val eventGrant = PluginEventGrant(
                PluginEventGrantKey(review.artifact, review.sourceKeys.single()),
                review.requestedPermissions,
            )
            store.putString(
                legacyGrantKey(review.artifact),
                Json.encodeToString(ListSerializer(PluginEventGrant.serializer()), listOf(eventGrant)),
            )
            store.putString(legacyRuntimeGrantKey(review.artifact), legacyRuntimeApprovalJson(review))

            val authorizer = MutablePluginSystemEventAuthorizer()
            val admission = KeyValuePluginEventGrantAdmission(store, authorizer)
            admission.hydrate(review)

            assertFalse(allowed(authorizer, review))
            assertEquals(review, admission.pending(review.artifact))
        }
    }

    @Test
    fun revokedTombstoneIgnoresLaterLegacyGrantRestoration() = runTest {
        val store = InMemoryPluginKeyValueStore()
        val review = review()
        val first = KeyValuePluginEventGrantAdmission(store, MutablePluginSystemEventAuthorizer())
        first.stage(review)
        first.approve(review.artifact, review.requestedPermissions)
        first.revoke(review.artifact)

        val eventGrant = PluginEventGrant(
            PluginEventGrantKey(review.artifact, review.sourceKeys.single()),
            review.requestedPermissions,
        )
        store.putString(
            legacyGrantKey(review.artifact),
            Json.encodeToString(ListSerializer(PluginEventGrant.serializer()), listOf(eventGrant)),
        )
        store.putString(legacyRuntimeGrantKey(review.artifact), legacyRuntimeApprovalJson(review))

        val restartedAuthorizer = MutablePluginSystemEventAuthorizer()
        val restarted = KeyValuePluginEventGrantAdmission(store, restartedAuthorizer)
        restarted.hydrate(review)

        assertFalse(allowed(restartedAuthorizer, review))
        assertEquals(review, restarted.pending(review.artifact))
    }

    @Test
    fun failedDurableRevocationStillRemovesTheLiveGrant() = runTest {
        val store = FailingTombstoneKeyValueStore()
        val authorizer = MutablePluginSystemEventAuthorizer()
        val admission = KeyValuePluginEventGrantAdmission(store, authorizer)
        val review = review()
        admission.stage(review)
        admission.approve(review.artifact, review.requestedPermissions)
        assertTrue(allowed(authorizer, review))

        store.failTombstones = true
        assertFailsWith<IllegalStateException> { admission.revoke(review.artifact) }

        assertFalse(allowed(authorizer, review))
        assertFalse(admission.isGranted(review))
    }

    @Test
    fun stageRejectsDuplicateAndUnboundedSourceSets() = runTest {
        val admission = KeyValuePluginEventGrantAdmission(
            InMemoryPluginKeyValueStore(),
            MutablePluginSystemEventAuthorizer(),
        )
        val duplicate = review().copy(sourceKeys = List(2) { review().sourceKeys.single() })
        assertFailsWith<IllegalArgumentException> { admission.stage(duplicate) }

        val tooMany = review().copy(
            sourceKeys = (0..256).map { SourceKey(packageId = "pkg.test", sourceId = "source-$it") },
        )
        assertFailsWith<IllegalArgumentException> { admission.stage(tooMany) }
    }

    private fun review(): PluginEventGrantReview = PluginEventGrantReview(
        artifact = PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
        sourceKeys = listOf(SourceKey(packageId = "pkg.test", sourceId = "source")),
        requestedPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
    )

    private fun allowed(
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

    private fun authoritativeKey(identity: PluginArtifactIdentity): String =
        "plugin.events.admission.v2.${identity.storageKey()}"

    private fun legacyGrantKey(identity: PluginArtifactIdentity): String =
        "plugin.events.grant.${identity.storageKey()}"

    private fun legacyPendingKey(identity: PluginArtifactIdentity): String =
        "plugin.events.pending.${identity.storageKey()}"

    private fun legacyRuntimeGrantKey(identity: PluginArtifactIdentity): String =
        "plugin.runtime-permissions.grant.${identity.storageKey()}"

    private fun PluginArtifactIdentity.storageKey(): String =
        "${packageId.length}:$packageId|${version.length}:$version|$versionCode|$sha256"

    private fun legacyRuntimeApprovalJson(review: PluginEventGrantReview): String =
        """{"artifact":{"packageId":"${review.artifact.packageId}","version":"${review.artifact.version}","versionCode":${review.artifact.versionCode},"sha256":"${review.artifact.sha256}"},"sourceKeys":[{"contractVersion":2,"packageId":"pkg.test","sourceId":"source","legacyLongId":null}],"permissions":[${review.requestedRuntimePermissions.joinToString { "\"${it.name}\"" }}]}"""
}

private class RecordingKeyValueStore : PluginKeyValueStore {
    private val delegate = InMemoryPluginKeyValueStore()
    val putKeys = mutableListOf<String>()

    override suspend fun getString(key: String): String? = delegate.getString(key)

    override suspend fun putString(key: String, value: String) {
        putKeys += key
        delegate.putString(key, value)
    }

    override suspend fun remove(key: String) = delegate.remove(key)
}

private class FailingTombstoneKeyValueStore : PluginKeyValueStore {
    private val delegate = InMemoryPluginKeyValueStore()
    var failTombstones = false

    override suspend fun getString(key: String): String? = delegate.getString(key)

    override suspend fun putString(key: String, value: String) {
        if (failTombstones && key.startsWith("plugin.events.admission.v2.") && "\"state\":\"REVOKED\"" in value) {
            error("Injected admission tombstone failure")
        }
        delegate.putString(key, value)
    }

    override suspend fun remove(key: String) = delegate.remove(key)
}
