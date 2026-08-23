package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import kotlinx.coroutines.test.runTest
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
}
