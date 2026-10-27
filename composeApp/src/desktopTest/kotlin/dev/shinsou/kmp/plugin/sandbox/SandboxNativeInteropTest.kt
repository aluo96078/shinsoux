package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginExecutionLimits
import dev.shinsou.kmp.plugin.PluginHostResolution
import dev.shinsou.kmp.plugin.PluginHostResolver
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginNetworkPolicy
import dev.shinsou.kmp.plugin.PluginResourceLimitException
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.sourceFailureMarker
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The host-side Kotlin protocol stack against the real C sandbox core (`sandbox-core`, ADR-0002
 * §17): SandboxRuntimeFactory → SandboxSession → stdin/stdout → shinsou-sandbox → QuickJS.
 *
 * Needs the helper binary: build it with
 *   cd sandbox-core && ./scripts/fetch-deps.sh && cmake -S . -B build/release -DCMAKE_BUILD_TYPE=Release && cmake --build build/release
 * or point SHINSOU_SANDBOX_BIN at one. Without it the test is skipped (it prints why).
 */
class SandboxNativeInteropTest {
    private val binary: File? = sandboxHelperBinary()

    private val source = SourceIndexEntry(
        name = "Interop",
        lang = "zh",
        id = 7,
        baseUrl = "https://www.example.test",
        requestOrigins = setOf("https://www.example.test"),
        credentialOrigins = setOf("https://www.example.test"),
    )
    private val manifest = PluginManifest(
        id = "dev.example.interop", name = "Interop", version = "1.0.0", versionCode = 1, lang = "zh",
        script = "index.js", signature = "b".repeat(64), sources = listOf(source),
    )

    private val script = """
        var source = {
          name: 'Interop', baseUrl: 'https://www.example.test', supportsLatest: true, supportsLogin: true,
          headers: { 'X-From': 'script' },
          getPopularManga: function (page) {
            var doc = Jsoup.parse(bridge.httpGet(this.baseUrl + '/list?p=' + page), this.baseUrl);
            var links = doc.select('li.item a');
            var mangas = [];
            for (var i = 0; i < links.length; i++) mangas.push({ url: links[i].attr('href'), title: links[i].text() });
            return { mangas: mangas, hasNextPage: doc.selectFirst('a.next') != null };
          },
          getLatestUpdates: async function (page) {
            var responses = await Promise.all([1, 2].map(function (n) { return bridge.net.fetch('https://www.example.test/api/' + n); }));
            return { mangas: responses.map(function (r) { var j = r.json(); return { url: j.u, title: j.t }; }), hasNextPage: false };
          },
          getMangaDetails: function (m) { m.description = 'secret:' + JSON.stringify(bridge.getCredentialPassword()); return m; },
          login: function (u, p) { return { loggedIn: bridge.getCredentialPassword() === 'old' && p === 'new' }; },
          getFilterList: function () { return [{ type: 'header', name: 'Hi' }]; },
          spin: function () { for (;;) {} },
          challenge: async function () { await bridge.net.fetch('https://www.example.test/api/1'); throw new Error('SHINSOU_SOURCE_HTTP_CHALLENGE'); },
          plain: function () { throw new Error('SHINSOU_SOURCE_HTTP_CHALLENGE (with details)'); }
        };
    """.trimIndent()

    private val requests = ArrayList<String>()

    private fun environment(): ScriptPluginEnvironment {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val http = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest) = respond(request)
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution) = respond(request)
            private fun respond(request: PluginHttpRequest): PluginHttpResponse {
                synchronized(requests) { requests += request.url }
                val body = when {
                    request.url.contains("/list") -> "<ul><li class=item><a href='/m/1'>第一</a><li class=item><a href='/m/2'>Second</a></ul><a class=next href='?p=2'>next</a>"
                    request.url.endsWith("/api/1") -> "{\"u\":\"/a/1\",\"t\":\"One\"}"
                    request.url.endsWith("/api/2") -> "{\"u\":\"/a/2\",\"t\":\"Two\"}"
                    else -> ""
                }
                return PluginHttpResponse(200, body.encodeToByteArray(), mapOf("Set-Cookie" to listOf("sid=x")))
            }
        }
        return ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = http,
                storage = storage,
                policy = PluginNetworkPolicy(requestOrigins = source.requestOrigins, credentialOrigins = source.credentialOrigins),
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            ),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK, PluginRuntimePermission.CREDENTIAL_ACCESS),
            executionLimits = PluginExecutionLimits(invocationWallTimeMillis = 3_000),
        )
    }

    @Test
    fun kotlinHostDrivesTheRealCSandbox() = runBlocking {
        val bin = binary ?: run { println("SKIPPED: shinsou-sandbox not built (see class comment)"); return@runBlocking }
        lateinit var transport: SandboxProcessTransport
        val env = environment()
        env.storage.setCredential(7, PluginCredential("alice", "old"))
        val runtime = SandboxRuntimeFactory(transports = { SandboxProcessTransport(listOf(bin.path)).also { transport = it } })
            .createForSource(script, manifest, source, env) as SandboxScriptPluginRuntime
        try {
            withTimeout(30_000) {
                // Metadata came from the bootstrap's __shinsouMetadata via the reserved entry point.
                assertTrue(runtime.supportsLatest)
                assertEquals("script", runtime.headers["X-From"])

                // Sync bridge + the bootstrap's DOM inside the sandbox.
                val popular = runtime.getPopularManga(1)
                assertEquals(listOf(SManga(url = "/m/1", title = "第一"), SManga(url = "/m/2", title = "Second")), popular.mangas)
                assertTrue(popular.hasNextPage)

                // Two concurrent net.fetch calls through AsyncBridgeCall frames.
                assertEquals(listOf("One", "Two"), runtime.getLatestUpdates(1).mangas.map { it.title })

                // Passwords only inside the login hook.
                val details = runtime.getMangaDetails(SManga(url = "/m/1", title = "x"))
                assertEquals("secret:{\"error\":\"PERMISSION_DENIED\"}", details.description)
                assertTrue(runtime.login("alice", "new"))

                assertEquals(1, runtime.getFilterList().size)

                // Host-owned guidance markers cross the boundary as a number, nothing else does.
                assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", assertFailsWith<SandboxInvocationException> { runtime.invokeForTest("challenge") }.sourceFailureMarker())
                assertEquals(null, assertFailsWith<SandboxInvocationException> { runtime.invokeForTest("plain") }.sourceFailureMarker())
                assertTrue(requests.any { it.endsWith("/list?p=1") } && requests.count { "/api/" in it } == 3, "$requests")
            }
        } finally {
            runtime.close()
        }
        assertTrue(!transport.alive || run { delay(200); !transport.alive })
    }

    @Test
    fun cancellationAndTimeLimitsHoldAcrossTheProcessBoundary() = runBlocking {
        val bin = binary ?: run { println("SKIPPED: shinsou-sandbox not built (see class comment)"); return@runBlocking }
        val runtime = SandboxRuntimeFactory(transports = { SandboxProcessTransport(listOf(bin.path)) })
            .createForSource(script, manifest, source, environment()) as SandboxScriptPluginRuntime
        try {
            // The sandbox's own wall-time limit (3 s) ends a busy loop with LIMIT_TIME.
            val t0 = System.nanoTime()
            assertFailsWith<PluginResourceLimitException> { runtime.invokeForTest("spin") }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms in 2_500..5_500, "time limit took $ms ms")

            // Caller cancellation reaches the busy loop through a Cancel frame; the session survives.
            val call = async { runCatching { runtime.invokeForTest("spin") } }
            delay(300)
            val c0 = System.nanoTime()
            call.cancel()
            call.join()
            val cancelMs = (System.nanoTime() - c0) / 1_000_000
            assertTrue(cancelMs < 1_500, "cancel took $cancelMs ms")
            assertEquals(1, runtime.getFilterList().size)
            println("interop: time limit $ms ms, cancel $cancelMs ms")
        } finally {
            runtime.close()
        }
    }
}
