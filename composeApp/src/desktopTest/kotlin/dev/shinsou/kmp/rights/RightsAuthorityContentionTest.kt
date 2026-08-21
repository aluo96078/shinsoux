package dev.shinsou.kmp.rights

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RightsAuthorityContentionTest {
    @Test
    fun blockedInvalidationListenerDoesNotHoldThePolicyLock() {
        val authority = InMemoryRightsAuthority()
        val scope = scope()
        val reference = RightsGrantRef("44444444-4444-4444-8444-444444444444")
        authority.admit(grant(reference, scope))
        val listenerEntered = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        authority.observeInvalidations {
            listenerEntered.countDown()
            check(releaseListener.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val revoke = executor.submit { authority.revoke(reference) }
            assertTrue(listenerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val resolve = executor.submit {
                authority.resolve(reference, scope, nowEpochMillis = 1)
            }

            assertNull(resolve.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            releaseListener.countDown()
            revoke.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            releaseListener.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun scope(): RightsScope {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        return RightsScope(
            publicationId = publication,
            acquisitionId = "22222222-2222-4222-8222-222222222222",
            unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
            contentRevision = 1,
        )
    }

    private fun grant(reference: RightsGrantRef, scope: RightsScope): RightsGrant = RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = reference,
        scope = scope,
        provenance = RightsProvenance.HostPolicy("contention-test"),
        protectionScheme = ProtectionScheme.None,
        validFromEpochMillis = 0,
        validUntilEpochMillis = null,
        allowedOperations = setOf(ContentOperation.DISPLAY),
    )

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
