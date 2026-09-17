package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewedBrowserImageIntegrationTest {
    @Test fun exactBiliUsesPinnedBrowserButCoversAndRevokedScopesDoNot() = runTest {
        val repository = listOf(Path.of("../shinsou_plugin"), Path.of("../../shinsou_plugin"))
            .map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }
            ?: return@runTest
        val http = HttpClient(MockEngine { request ->
            respond(Files.readAllBytes(repository.resolve(request.url.encodedPath.substringAfter("refs/heads/master/"))), HttpStatusCode.OK)
        })
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val client = ExtensionRepositoryClient(http, repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY)
        var browserCalls = 0
        var nativeCalls = 0
        var activeBrowserCalls = 0
        var maximumBrowserCalls = 0
        var browserBarrier: CompletableDeferred<Unit>? = null
        var browserEntered: CompletableDeferred<Unit>? = null
        val image = PluginHttpResponse(200, byteArrayOf(1, 2, 3), mapOf("Content-Type" to listOf("image/avif")))
        var resolvedAddresses = listOf("93.184.216.34")
        val resolver = PluginHostResolver { resolvedAddresses }
        val native = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("Must pin DNS")
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                nativeCalls++; return image
            }
        }
        val browser = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("Must pin DNS")
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                validateReviewedBrowserImageRequest(request, resolution)
                assertTrue(request.headers.isEmpty())
                browserCalls++
                activeBrowserCalls++
                maximumBrowserCalls = maxOf(maximumBrowserCalls, activeBrowserCalls)
                browserEntered?.complete(Unit)
                try { browserBarrier?.await() } finally { activeBrowserCalls-- }
                return image
            }
        }
        val manager = PluginManager(client, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keys)),
            RhinoScriptPluginRuntimeFactory(), ScriptPluginEnvironment(
                PluginNetworkClient(native, storage, hostResolver = resolver), storage, hostResolver = resolver,
            ), eventGrantAdmission = KeyValuePluginEventGrantAdmission(keys, MutablePluginSystemEventAuthorizer()),
            reviewedImageTransport = browser,
        )
        try {
            val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
            val entries = when (index) {
                is RepositoryIndex.Combined -> index.plugins
                is RepositoryIndex.Plugins -> index.entries
                else -> error("Expected v2 index")
            }
            val entry = entries.single { it.id == "zh.bilimanga.manga" }
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
            manager.approveCurrentEventGrantReview(entry.id, setOf(PluginHostPermission.REQUEST_LOGIN_UI))
            val id = requireNotNull(entry.sources).single().id
            val content = assertNotNull(manager.contentNetworkForSource(id))
            storage.setCookie(id, PluginCookie("secret", "must-not-leak", "i.motiezw.com"))
            content.get(id, IMAGE, mapOf("Referer" to "https://www.bilimanga.net", "User-Agent" to "legacy"))
            assertEquals(1, browserCalls)
            assertEquals(0, nativeCalls)
            content.get(id, "https://www.bilimanga.net/cover.jpg")
            assertEquals(1, browserCalls)
            assertEquals(1, nativeCalls)
            assertFailsWith<IllegalArgumentException> { content.get(id, IMAGE, mapOf("Cookie" to "secret=x")) }

            // The browser surface is deliberately scarce even when callers fan out.
            browserBarrier = CompletableDeferred()
            browserEntered = CompletableDeferred()
            val concurrent = List(3) { async { content.get(id, IMAGE) } }
            // The production per-host gate spaces starts by 200 ms. runTest uses virtual delay
            // while that gate intentionally reads the wall clock, so yielding alone cannot make
            // the second request eligible. Advance one bounded interval at a time without using
            // advanceUntilIdle (the first two transports are deliberately parked at the barrier).
            repeat(4) {
                if (maximumBrowserCalls < 2) {
                    advanceTimeBy(1_000)
                    runCurrent()
                }
            }
            assertEquals(2, maximumBrowserCalls)
            browserBarrier!!.complete(Unit)
            concurrent.awaitAll()

            // Cancellation must release both scarce browser permits, even while the transports
            // themselves are suspended. A subsequent image must enter without completing the
            // cancelled calls' barrier.
            browserBarrier = CompletableDeferred()
            maximumBrowserCalls = 0
            val cancelled = List(2) { async { content.get(id, IMAGE) } }
            repeat(4) {
                if (maximumBrowserCalls < 2) {
                    advanceTimeBy(1_000)
                    runCurrent()
                }
            }
            assertEquals(2, maximumBrowserCalls)
            cancelled.forEach { it.cancelAndJoin() }
            assertEquals(0, activeBrowserCalls)
            browserBarrier = null
            content.get(id, IMAGE)
            assertEquals(7, browserCalls)

            // A reviewed identity and URL never override the ordinary public-DNS boundary.
            browserBarrier = null
            browserEntered = null
            resolvedAddresses = listOf("127.0.0.1")
            assertFailsWith<IllegalArgumentException> { content.get(id, IMAGE) }
            assertEquals(7, browserCalls)
            resolvedAddresses = listOf("93.184.216.34")

            // Authority is checked again after transport completion, not merely at dispatch.
            browserBarrier = CompletableDeferred()
            browserEntered = CompletableDeferred()
            val revokedInFlight = async { runCatching { content.get(id, IMAGE) } }
            browserEntered!!.await()
            manager.setPluginTrusted(entry.id, false)
            browserBarrier!!.complete(Unit)
            assertTrue(revokedInFlight.await().isFailure)
            assertFailsWith<IllegalArgumentException> { content.get(id, IMAGE) }
            assertEquals(8, browserCalls)
        } finally { manager.close(); http.close() }
    }

    companion object { const val IMAGE = "https://i.motiezw.com/1/1615/133085/2911882.avif" }
}
