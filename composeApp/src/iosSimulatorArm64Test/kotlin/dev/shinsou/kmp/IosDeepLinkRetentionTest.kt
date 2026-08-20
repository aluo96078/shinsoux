package dev.shinsou.kmp

import dev.shinsou.kmp.ui.ShinsouDeepLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class IosDeepLinkRetentionTest {
    @Test
    fun deepLinkEmittedBeforeCollectionIsRetainedUntilTheSameEventIsHandled() = runTest {
        val services = IosAppServices()
        val pending = ShinsouDeepLink.OpenManga(42)

        services.emitDeepLink(pending)
        assertSame(pending, services.deepLinks.first())

        // An acknowledgement for an older equivalent event must not clear the currently retained
        // one; this protects one-time provisioning payloads when callbacks overlap.
        services.deepLinkHandled(ShinsouDeepLink.OpenManga(42))
        assertSame(pending, services.deepLinks.first())

        services.deepLinkHandled(pending)
        assertNull(withTimeoutOrNull(1) { services.deepLinks.first() })
    }
}
