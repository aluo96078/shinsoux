package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateGrantContinuationTest {
    private val key = SourceKey(packageId = "pkg.test", sourceId = "1", legacyLongId = 1)
    private val runtime = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK, PluginRuntimePermission.COOKIE_READ)

    private fun review(sha: Char, runtime: Set<PluginRuntimePermission> = this.runtime, host: Set<PluginHostPermission> = setOf(PluginHostPermission.REPORT_DIAGNOSTIC), suffixes: Set<String> = setOf("cdn.example.com"), keys: List<SourceKey> = listOf(key)) =
        PluginEventGrantReview(PluginArtifactIdentity("pkg.test", "1.0.0", 1, sha.toString().repeat(64)), keys, host, runtime, suffixes)

    private fun source(requestOrigins: Set<String> = setOf("https://api.example.com"), id: Long = 1) = SourceIndexEntry(
        name = "S", lang = "en", id = id, baseUrl = "https://www.example.com", requestOrigins = requestOrigins,
        contentOrigins = setOf("https://img.example.com"), originPolicyVersion = 2,
    )

    private val previousGrant = PluginGrantSelection(
        hostPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
        // The user declined COOKIE_READ.
        runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
        contentHostSuffixes = setOf("cdn.example.com"),
    )

    private fun continued(
        next: PluginEventGrantReview = review('b'),
        nextSources: List<SourceIndexEntry> = listOf(source()),
        publisher: String? = "sha256:publisher",
        nextPublisher: String? = publisher,
    ) = UpdateGrantContinuation.continuedSelection(publisher, nextPublisher, review('a'), previousGrant, next, listOf(source()), nextSources)

    @Test
    fun anUpdateAskingForNothingNewKeepsThePreviousChoiceIncludingDeclinedItems() {
        assertEquals(previousGrant, continued())
        // Asking for less narrows the carried-over grant.
        assertEquals(
            previousGrant.copy(contentHostSuffixes = emptySet()),
            continued(next = review('b', suffixes = emptySet())),
        )
    }

    @Test
    fun anythingNewOrAnotherPublisherNeedsAFreshReview() {
        assertNull(continued(publisher = null))
        assertNull(continued(nextPublisher = "sha256:someone-else"))
        assertNull(continued(next = review('b', runtime = runtime + PluginRuntimePermission.CREDENTIAL_ACCESS)))
        assertNull(continued(next = review('b', host = setOf(PluginHostPermission.REQUEST_LOGIN_UI))))
        assertNull(continued(next = review('b', suffixes = setOf("cdn.example.com", "img.example.net"))))
        assertNull(continued(nextSources = listOf(source(requestOrigins = setOf("https://api.example.com", "https://evil.example")))))
        val second = SourceKey(packageId = "pkg.test", sourceId = "2", legacyLongId = 2)
        assertNull(continued(next = review('b', keys = listOf(key, second)), nextSources = listOf(source(), source(id = 2))))
        // The same artifact is not an update.
        assertNull(continued(next = review('a')))
    }
}
