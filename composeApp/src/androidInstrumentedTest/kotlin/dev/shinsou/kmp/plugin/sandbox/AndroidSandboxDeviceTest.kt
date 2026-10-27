package dev.shinsou.kmp.plugin.sandbox

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-0002 S2 on a real device: the sandbox runs in isolated processes (one per package) and the
 * host drives it through the same SandboxProtocol v1 stack as every other platform.
 *
 * Run with `./gradlew :composeApp:connectedDebugAndroidTest -PshinsouSandboxDeviceTest=true`
 * (installs as dev.aluo.shinsoux.sandboxtest, so a real installation is never touched).
 */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = AndroidSandboxTransportProvider(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = ArrayList<AndroidSandboxTransport>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        scope.cancel()
    }

    private val source = SourceIndexEntry(
        name = "Device",
        lang = "zh",
        id = 7,
        baseUrl = "https://www.example.test",
        requestOrigins = setOf("https://www.example.test"),
        credentialOrigins = setOf("https://www.example.test"),
    )

    private fun manifest(id: String = "dev.example.device") = PluginManifest(
        id = id, name = "Device", version = "1.0.0", versionCode = 1, lang = "zh",
        script = "index.js", signature = "b".repeat(64), sources = listOf(source),
    )

    private val script = """
        var source = {
          name: 'Device', baseUrl: 'https://www.example.test', supportsLatest: true, supportsLogin: true,
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
          challenge: async function () { await bridge.net.fetch('https://www.example.test/api/1'); throw new Error('SHINSOU_SOURCE_HTTP_CHALLENGE'); },
          // ADR-0002 §13 malicious corpus
          spin: function () { for (;;) {} },
          recurse: function () { function f(n) { return f(n + 1) + 1; } return f(0); },
          hoard: function () { var a = []; for (;;) a.push(new Array(1 << 16).fill(a.length)); },
          bigString: function () { var s = 'x'; for (;;) s = s + s; },
          backtrack: function () { return /^(a+)+${'$'}/.test('a'.repeat(40) + 'b'); },
          proxyBomb: function () { return new Proxy({}, { ownKeys: function () { for (;;) {} }, get: function () { for (;;) {} } }); },
          getterBomb: function () { var o = {}; Object.defineProperty(o, 'x', { enumerable: true, get: function () { for (;;) {} } }); return o; },
          tamper: function () {
            bridge.httpGet = function () { return 'forged'; };
            Object.prototype.toJSON = function () { return 'polluted'; };
            globalThis.__shinsouMetadata = function () { return { supportsLogin: true, baseUrl: 'https://evil.test' }; };
            return { mangas: [], hasNextPage: false };
          },
          forbiddenHosts: function () {
            return ['http://127.0.0.1/', 'http://10.0.0.1/', 'http://[::1]/', 'http://www.example.test/', 'https://evil.test/']
              .map(function (u) { return String(bridge.httpGet(u)); });
          },
          headerInjection: function () { return String(bridge.httpGet('https://www.example.test/h', { 'X-A': 'a\r\nHost: evil.test' })); },
          hugeResult: function () { return 'x'.repeat(5 << 20); },
          bridgeFlood: function () { for (;;) bridge.httpGet('https://evil.test/'); },
          fetchFlood: async function () {
            var all = [];
            for (var i = 0; i < 200; i++) all.push(bridge.net.fetch('https://www.example.test/api/1').then(function () { return 'ok'; }, function () { return 'rejected'; }));
            var r = await Promise.all(all);
            return r.filter(function (x) { return x === 'rejected'; }).length;
          },
          neverSettles: function () { return new Promise(function () {}); },
          events: async function () { for (var i = 0; i < 1e7; i++) await null; return 'done'; }
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
                return PluginHttpResponse(200, body.encodeToByteArray())
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

    private val recordingProvider = SandboxTransportProvider { id ->
        (provider.open(id) as AndroidSandboxTransport).also { synchronized(opened) { opened += it } }
    }

    private suspend fun runtime(
        id: String = "dev.example.device",
        env: ScriptPluginEnvironment = environment(),
        lifecycle: SandboxLifecycle? = null,
    ): SandboxScriptPluginRuntime =
        SandboxRuntimeFactory(transports = recordingProvider, lifecycle = lifecycle)
            .createForSource(script, manifest(id), source, env) as SandboxScriptPluginRuntime

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    /** pid → (user, name) of every process of this app, isolated ones included. */
    private fun appProcesses(): Map<Int, Pair<String, String>> =
        shell("ps -A -o PID,USER,NAME").lines().drop(1).mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 3 || !f[2].startsWith(context.packageName)) null else f[0].toInt() to (f[1] to f[2])
        }.toMap()

    private fun pssKb(pid: Int): String =
        Regex("TOTAL PSS:\\s+(\\d+)").find(shell("dumpsys meminfo $pid"))?.groupValues?.get(1) ?: "?"

    @Test
    fun theHostDrivesTheSandboxInAnIsolatedProcess() = runBlocking<Unit> {
        val env = environment()
        env.storage.setCredential(7, PluginCredential("alice", "old"))
        val t0 = SystemClock.elapsedRealtime()
        val runtime = runtime(env = env)
        val coldStartMs = SystemClock.elapsedRealtime() - t0
        try {
            withTimeout(30_000) {
                assertTrue(runtime.supportsLatest)
                assertEquals("script", runtime.headers["X-From"])
                val popular = runtime.getPopularManga(1)
                assertEquals(listOf(SManga(url = "/m/1", title = "第一"), SManga(url = "/m/2", title = "Second")), popular.mangas)
                assertTrue(popular.hasNextPage)
                assertEquals(listOf("One", "Two"), runtime.getLatestUpdates(1).mangas.map { it.title })
                assertEquals("secret:{\"error\":\"PERMISSION_DENIED\"}", runtime.getMangaDetails(SManga(url = "/m/1", title = "x")).description)
                assertTrue(runtime.login("alice", "new"))
                assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", assertFailsWith<SandboxInvocationException> { runtime.invokeForTest("challenge") }.sourceFailureMarker())
            }
            repeat(50) { runtime.invokeForTest("getFilterList") } // warm up
            val rounds = (1..200).map {
                val c0 = System.nanoTime()
                runtime.invokeForTest("getFilterList")
                (System.nanoTime() - c0) / 1_000
            }.sorted()
            Log.i(TAG, "device: cold start $coldStartMs ms (bind + lockdown + bootstrap + script + metadata), " +
                "hook round trip median ${rounds[100]} µs, p90 ${rounds[180]} µs")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun everyPackageGetsItsOwnIsolatedProcessThatCannotEscape() = runBlocking<Unit> {
        val t0 = SystemClock.elapsedRealtime()
        val a = provider.open("pkg.alpha") as AndroidSandboxTransport
        val t1 = SystemClock.elapsedRealtime()
        val b = provider.open("pkg.beta") as AndroidSandboxTransport
        Log.i(TAG, "device: process start (bind + lockdown) ${t1 - t0} ms, second ${SystemClock.elapsedRealtime() - t1} ms")
        opened += a
        opened += b
        val procs = appProcesses()
        val host = procs[Process.myPid()] ?: fail("host process missing from ps: $procs")
        val pa = procs[a.pid] ?: fail("sandbox ${a.pid} missing: $procs")
        val pb = procs[b.pid] ?: fail("sandbox ${b.pid} missing: $procs")
        assertNotEquals(a.pid, b.pid)
        assertTrue(pa.first != pb.first && pa.first != host.first, "uids: host ${host.first}, ${pa.first}, ${pb.first}")
        assertTrue(pa.first.startsWith("u0_i") && pb.first.startsWith("u0_i"), "not isolated uids: ${pa.first}, ${pb.first}")
        Log.i(TAG, "device: host ${host.first}/${Process.myPid()}, alpha ${pa.first}/${a.pid}, beta ${pb.first}/${b.pid}")
        val zygote = procs.entries.firstOrNull { it.value.second.endsWith("_zygote") }
        Log.i(TAG, "device: PSS alpha ${pssKb(a.pid)} KiB, beta ${pssKb(b.pid)} KiB, app zygote ${zygote?.let { pssKb(it.key) }} KiB (${zygote?.value})")

        val secret = File(context.filesDir, "sandbox-probe-secret").apply { writeText("secret") }
        val prefs = File(context.dataDir, "shared_prefs").apply { mkdirs() }
        val dbs = context.getDatabasePath("probe.db").apply { parentFile?.mkdirs(); writeText("db") }
        for (path in listOf(null, secret.path, prefs.path, dbs.path, context.dataDir.path, "/sdcard/", "/proc/net/tcp")) {
            // bits: 1 inet socket, 2 inet6 socket, 4 open(path), 8 fork, 16 exec, 32 netlink/packet/
            // bluetooth/vsock socket, 64 AF_UNIX socketpair broken by the filter, 128 the seccomp
            // filter did not answer socket(AF_INET) (only the uid did)
            assertEquals(0, a.probe(path), "sandbox could do something forbidden with $path")
        }
        secret.delete()
        dbs.delete()
    }

    @Test
    fun theMaliciousCorpusFailsWithStableErrorsAndTheHostCarriesOn() = runBlocking<Unit> {
        val runtime = runtime()
        try {
            val limits = listOf("spin", "recurse", "hoard", "bigString", "backtrack", "proxyBomb", "getterBomb", "hugeResult", "bridgeFlood", "neverSettles", "events")
            for (hook in limits) {
                val t0 = SystemClock.elapsedRealtime()
                val e = runCatching { runtime.invokeForTest(hook) }.exceptionOrNull()
                val ms = SystemClock.elapsedRealtime() - t0
                // A limit, or another stable sandbox code (SCRIPT_ERROR, PROMISE_STALLED); never a crash.
                assertTrue(e is PluginResourceLimitException || e is SandboxInvocationException, "$hook: expected a stable failure, got $e")
                assertTrue(ms < 8_000, "$hook took $ms ms")
                Log.i(TAG, "corpus: $hook -> ${e?.message} in $ms ms")
                assertEquals(1, runtime.getFilterList().size, "after $hook")
            }

            // A script can rewrite its own world, never the host's.
            runtime.invokeForTest("tamper")
            assertEquals("https://www.example.test", runtime.baseUrl)
            val denied = runtime.invokeForTest("forbiddenHosts").toString()
            assertTrue("forged" !in denied && requests.none { "127.0.0.1" in it || "10.0.0.1" in it || "::1" in it || "evil.test" in it || it.startsWith("http:") }, "$denied / $requests")
            runtime.invokeForTest("headerInjection")
            // The fetch queue is bounded: everything beyond in-flight + queue limits is rejected, not buffered.
            val rejected = runtime.invokeForTest("fetchFlood").toString().toInt()
            assertTrue(rejected >= 200 - 6 - 64, "only $rejected of 200 fetches were rejected")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun cancellationAndTimeLimitsHoldAcrossTheProcessBoundary() = runBlocking<Unit> {
        val runtime = runtime()
        try {
            val t0 = SystemClock.elapsedRealtime()
            assertFailsWith<PluginResourceLimitException> { runtime.invokeForTest("spin") }
            val ms = SystemClock.elapsedRealtime() - t0
            assertTrue(ms in 2_500..5_500, "time limit took $ms ms")
            val call = async(Dispatchers.Default) { runCatching { runtime.invokeForTest("spin") } }
            delay(300)
            val c0 = SystemClock.elapsedRealtime()
            call.cancel()
            call.join()
            val cancelMs = SystemClock.elapsedRealtime() - c0
            assertTrue(cancelMs < 1_500, "cancel took $cancelMs ms")
            assertEquals(1, runtime.getFilterList().size)
            Log.i(TAG, "device: time limit $ms ms, cancel $cancelMs ms")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun killingASandboxLeavesTheHostAndOtherSandboxesRunning() = runBlocking<Unit> {
        val victim = runtime("pkg.victim")
        val bystander = runtime("pkg.bystander")
        try {
            val pid = synchronized(opened) { opened.first().pid }
            val output = shell("am crash $pid")
            delay(500)
            if (pid in appProcesses()) fail("could not kill sandbox $pid: $output")
            assertFailsWith<SandboxBrokenException> { victim.getFilterList() }
            assertEquals(1, bystander.getFilterList().size)
            // The next call starts a fresh sandbox for the victim.
            assertEquals(1, victim.getFilterList().size)
            assertEquals(3, opened.size)
        } finally {
            victim.close()
            bystander.close()
        }
    }

    @Test
    fun theLifecycleStopsIdleAndLeastRecentlyUsedProcesses() = runBlocking<Unit> {
        val lifecycle = SandboxLifecycle(maxLive = 2, idleMs = 3_000, nowMs = SystemClock::elapsedRealtime, scope = scope)
        val runtimes = (1..3).map { runtime("pkg.lru$it", lifecycle = lifecycle) }
        try {
            delay(500)
            val pids = opened.map { it.pid }
            val live = appProcesses().keys
            assertEquals(listOf(false, true, true), pids.map { it in live }, "LRU: $pids live $live")
            delay(6_000)
            val after = appProcesses().keys
            assertTrue(pids.none { it in after }, "idle sandboxes still alive: $pids in $after")
            assertEquals(1, runtimes[0].getFilterList().size) // restarts on demand
            assertEquals(4, opened.size)
        } finally {
            runtimes.forEach { it.close() }
        }
    }

    @Test
    fun closingTheRuntimeEndsTheProcess() = runBlocking<Unit> {
        val runtime = runtime()
        val pid = opened.single().pid
        assertTrue(pid in appProcesses())
        runtime.close()
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (pid in appProcesses() && SystemClock.elapsedRealtime() < deadline) delay(100)
        assertTrue(pid !in appProcesses(), "sandbox $pid outlived its runtime")
    }

    private companion object {
        const val TAG = "SSC-S2"
    }
}
