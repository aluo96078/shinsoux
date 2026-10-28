package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
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
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

/**
 * The iOS sandbox on the simulator (ADR-0002 §4.5): the Kotlin host stack driving the sandbox-core
 * WASM guest in WAMR through [IosSandboxTransport]: DOM, bridge, limits and the device's time zone.
 */
class IosSandboxInteropTest {
    private val source = SourceIndexEntry(
        name = "WASM", lang = "zh", id = 7, baseUrl = "https://www.example.test",
        requestOrigins = setOf("https://www.example.test"),
    )
    private val manifest = PluginManifest(
        id = "dev.example.wasm", name = "WASM", version = "1.0.0", versionCode = 1, lang = "zh",
        script = "index.js", signature = "b".repeat(64), sources = listOf(source),
    )
    private val script = """
        var source = {
          name: 'WASM', baseUrl: 'https://www.example.test',
          getPopularManga: function (page) {
            var doc = Jsoup.parse(bridge.httpGet(this.baseUrl + '/list?p=' + page), this.baseUrl);
            var links = doc.select('li a');
            var mangas = [];
            for (var i = 0; i < links.length; i++) mangas.push({ url: links[i].attr('href'), title: links[i].text() });
            return { mangas: mangas, hasNextPage: false };
          },
          zone: function () { return new Date(2025, 0, 15, 12).getTimezoneOffset(); },
          spin: function () { for (;;) {} }
        };
    """.trimIndent()

    private fun environment(): ScriptPluginEnvironment {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val sites = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest) = respond()
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution) = respond()
            private fun respond() = PluginHttpResponse(200, "<ul><li><a href='/m/1'>第一</a><li><a href='/m/2'>Second</a></ul>".encodeToByteArray())
        }
        return ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = sites,
                storage = storage,
                policy = PluginNetworkPolicy(requestOrigins = source.requestOrigins),
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            ),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
            executionLimits = PluginExecutionLimits(invocationWallTimeMillis = 2_000),
        )
    }

    @Test
    fun theHostDrivesTheWasmSandbox() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = SandboxRuntimeFactory(IosSandboxTransportProvider(), iosSandboxLifecycle(scope))
        val start = TimeSource.Monotonic.markNow()
        val runtime = factory.createForSource(script, manifest, source, environment()) as SandboxScriptPluginRuntime
        val coldStart = start.elapsedNow()
        try {
            val page = runtime.getPopularManga(1)
            assertEquals(listOf("第一", "Second"), page.mangas.map { it.title })

            // Local dates follow the device's zone, as in JavaScriptCore (patches/quickjs-wasi-timezone.patch).
            val january = NSDate(timeIntervalSinceReferenceDate = 1_736_942_400.0 - 978_307_200.0) // 2025-01-15T12:00Z
            val expected = -NSTimeZone.localTimeZone.secondsFromGMTForDate(january).toInt() / 60
            assertEquals(expected.toString(), runtime.invokeForTest("zone").toString())

            val t0 = TimeSource.Monotonic.markNow()
            assertFailsWith<PluginResourceLimitException> { runtime.invokeForTest("spin") }
            val limited = t0.elapsedNow().inWholeMilliseconds
            assertTrue(limited in 1_500..5_000, "time limit took $limited ms")
            // The sandbox survives a limit: the next call works.
            assertEquals(2, runtime.getPopularManga(1).mangas.size)
            println("ios-sandbox: cold start ${coldStart.inWholeMilliseconds} ms, time limit $limited ms")
        } finally {
            runtime.close()
            scope.cancel()
        }
    }

    @Test
    fun closingRacesWithSendAndReceiveWithoutTouchingAFreedSession() = runBlocking<Unit> {
        val init = SandboxFrameCodec.encode(
            SandboxFrame.Init(1, SANDBOX_PROTOCOL_VERSION, 1, SandboxLimits(16L shl 20, 1L shl 20, 2_000, 2_000, 64, 6, 64, 1L shl 20, 100_000, 512), "var source={};"),
        )
        repeat(40) { round ->
            val transport = IosSandboxTransport.open(64L shl 20)
            val jobs = listOf(
                launch(Dispatchers.Default) { while (transport.receive() != null) Unit },
                launch(Dispatchers.Default) { runCatching { repeat(50) { init.forEach { transport.send(it) } } } },
                launch(Dispatchers.Default) { kotlinx.coroutines.delay((round % 7).toLong()); transport.close() },
                launch(Dispatchers.Default) { transport.close() },
            )
            jobs.forEach { it.join() }
            // Closed and freed: every later call is refused instead of reaching the native session.
            assertEquals(null, transport.receive())
            assertFailsWith<IllegalStateException> { transport.send(byteArrayOf(0)) }
        }
    }
}
