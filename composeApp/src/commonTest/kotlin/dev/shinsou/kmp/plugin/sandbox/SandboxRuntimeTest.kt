package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.InProcessScriptProvenance
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
import dev.shinsou.kmp.plugin.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.sourceFailureMarker
import dev.shinsou.kmp.plugin.sandbox.FakeSandbox.HookOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxRuntimeTest {
    private val source = SourceIndexEntry(
        name = "Example",
        lang = "zh",
        id = 42,
        baseUrl = "https://www.example.test",
        requestOrigins = setOf("https://www.example.test"),
        credentialOrigins = setOf("https://www.example.test"),
    )
    private val manifest = PluginManifest(
        id = "dev.example.sandboxed",
        name = "Example",
        version = "1.0.0",
        versionCode = 1,
        lang = "zh",
        script = "index.js",
        signature = "a".repeat(64),
        sources = listOf(source),
    )

    private val requests = ArrayList<PluginHttpRequest>()
    private val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())

    private fun environment(
        vararg extra: PluginRuntimePermission,
        limits: PluginExecutionLimits = PluginExecutionLimits(),
        provenance: InProcessScriptProvenance? = null,
        networkGate: CompletableDeferred<Unit>? = null,
    ): ScriptPluginEnvironment {
        val transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = respond(request)
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution) = respond(request)
            private suspend fun respond(request: PluginHttpRequest): PluginHttpResponse {
                networkGate?.await()
                requests += request
                return PluginHttpResponse(200, "body:${request.url}".encodeToByteArray())
            }
        }
        return ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = transport,
                storage = storage,
                policy = PluginNetworkPolicy(requestOrigins = source.requestOrigins, credentialOrigins = source.credentialOrigins),
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            ),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT) + extra,
            executionLimits = limits,
            inProcessScriptProvenance = provenance,
        )
    }

    private val metadataHooks: Map<String, suspend FakeSandbox.Hook.(JsonArray) -> HookOutcome> = mapOf(
        "__shinsouSelectSource" to { HookOutcome.Ok("null") },
        "__shinsouMetadata" to {
            HookOutcome.Ok("{\"supportsLatest\":true,\"supportsLogin\":true,\"headers\":{\"X-App\":\"s\"},\"webChallengeUrl\":\"https://www.example.test/cf\"}")
        },
        "__shinsouHasFunction" to { args -> HookOutcome.Ok((args[0].jsonPrimitive.content in setOf("getFilterList", "logout")).toString()) },
    )

    private class Started(val runtime: ScriptPluginRuntime, val sandbox: FakeSandbox)

    private suspend fun TestScope.start(
        hooks: Map<String, suspend FakeSandbox.Hook.(JsonArray) -> HookOutcome> = emptyMap(),
        env: ScriptPluginEnvironment = environment(),
        readyVersion: Int = SANDBOX_PROTOCOL_VERSION,
        scope: CoroutineScope = backgroundScope,
    ): Started {
        lateinit var sandbox: FakeSandbox
        val factory = SandboxRuntimeFactory(transports = {
            FakeSandbox(scope, readyVersion = readyVersion, hooks = metadataHooks + hooks).also { sandbox = it }
        }, pumpScope = scope)
        val runtime = factory.createForSource("var source = {};", manifest, source, env)
        return Started(runtime, sandbox)
    }

    @Test
    fun initSendsTheSelectedSourceAndHostLimitsThenReadsMetadata() = runTest {
        val s = start(env = environment(limits = PluginExecutionLimits(invocationWallTimeMillis = 12_000, maxBridgeCallsPerInvocation = 99)))
        val script = s.sandbox.initScript!!
        assertContains(script, "__shinsouRequestedSourceId=\"42\"")
        assertTrue(script.endsWith("var source = {};"))
        assertEquals(12_000, s.sandbox.initLimits!!.invokeTimeoutMs)
        assertEquals(99, s.sandbox.initLimits!!.maxBridgeCalls)
        assertTrue(s.runtime.supportsLatest)
        assertEquals("https://www.example.test/cf", (s.runtime as SandboxScriptPluginRuntime).webChallengeUrl)
        assertEquals(mapOf("X-App" to "s", "Referer" to "https://www.example.test"), (s.runtime as SandboxScriptPluginRuntime).headers)
    }

    @Test
    fun aReviewedProvenanceIsRefusedBeforeAnySandboxStarts() = runTest {
        val provenance = InProcessScriptProvenance.reviewedArtifact(
            PluginArtifactIdentity(manifest.id, manifest.version, 1, "a".repeat(64)),
            SourceKey(packageId = manifest.id, sourceId = "42"),
            "var source = {};",
        )
        var opened = 0
        val factory = SandboxRuntimeFactory(transports = { opened++; error("must not open") })
        assertFailsWith<IllegalArgumentException> {
            factory.createForSource("var source = {};", manifest, source, environment(provenance = provenance))
        }
        assertEquals(0, opened)
    }

    @Test
    fun anotherProtocolVersionFailsClosed() = runTest {
        lateinit var sandbox: FakeSandbox
        val factory = SandboxRuntimeFactory(transports = { FakeSandbox(backgroundScope, readyVersion = 2, hooks = metadataHooks).also { sandbox = it } }, pumpScope = backgroundScope)
        assertFailsWith<SandboxBrokenException> { factory.createForSource("x", manifest, source, environment()) }
        assertTrue(sandbox.closed)
    }

    @Test
    fun hooksMapResultsAndBridgeCallsGoThroughHostPolicy() = runTest {
        val s = start(
            hooks = mapOf(
                "getPopularManga" to { args ->
                    val page = args[0].jsonPrimitive.content
                    val body = (bridge("httpGet", "[\"https://www.example.test/list?p=$page\"]") as BridgeOutcome.Value).json!!
                    val denied = bridge("httpGet", "[\"https://evil.test/\"]")
                    check(denied == BridgeOutcome.Error(SandboxErrorCode.NETWORK_FAILED))
                    HookOutcome.Ok("{\"mangas\":[{\"url\":\"/m/1\",\"title\":$body}],\"hasNextPage\":true}")
                },
            ),
            env = environment(PluginRuntimePermission.NETWORK),
        )
        val page = s.runtime.getPopularManga(3)
        assertEquals(listOf(SManga(url = "/m/1", title = "body:https://www.example.test/list?p=3")), page.mangas)
        assertTrue(page.hasNextPage)
        assertEquals(listOf("https://www.example.test/list?p=3"), requests.map { it.url })
    }

    @Test
    fun asyncFetchesRunConcurrentlyAndResolveByOpId() = runTest {
        val s = start(
            hooks = mapOf(
                "getPopularManga" to {
                    for (i in 1..3) startAsync(i.toLong(), "net.fetch", "[\"https://www.example.test/$i\"]")
                    val bodies = (3 downTo 1).map { i ->
                        val v = awaitAsync(i.toLong()) as BridgeOutcome.Value
                        kotlinx.serialization.json.Json.parseToJsonElement(v.json!!).jsonObject["body"]!!.jsonPrimitive.content
                    }
                    HookOutcome.Ok("{\"mangas\":[" + bodies.joinToString(",") { "{\"title\":${JsonPrimitive(it)}}" } + "]}")
                },
            ),
            env = environment(PluginRuntimePermission.NETWORK),
        )
        assertEquals(
            listOf("body:https://www.example.test/3", "body:https://www.example.test/2", "body:https://www.example.test/1"),
            s.runtime.getPopularManga(1).mangas.map { it.title },
        )
    }

    @Test
    fun startingTheNextQueuedFetchTheMomentAResultArrivesIsNotAViolation() = runTest {
        // Six in flight; each time one result arrives the sandbox starts the next queued fetch at once.
        val s = start(
            hooks = mapOf("getPopularManga" to {
                for (i in 1..6) startAsync(i.toLong(), "net.fetch", "[\"https://www.example.test/$i\"]")
                var next = 7L
                for (done in 1..20) {
                    awaitAsync(done.toLong())
                    if (next <= 26) { startAsync(next, "net.fetch", "[\"https://www.example.test/$next\"]"); next++ }
                }
                for (rest in 21..26) awaitAsync(rest.toLong())
                HookOutcome.Ok("{\"mangas\":[],\"hasNextPage\":true}")
            }),
            env = environment(PluginRuntimePermission.NETWORK),
        )
        assertTrue(s.runtime.getPopularManga(1).hasNextPage)
        assertFalse(s.sandbox.closed)
        assertEquals(26, requests.size)
    }

    @Test
    fun exceedingTheAsyncInFlightLimitKillsTheSandbox() = runTest {
        val s = start(
            hooks = mapOf("getPopularManga" to {
                for (i in 1..7) startAsync(i.toLong(), "net.fetch", "[\"https://www.example.test/$i\"]")
                HookOutcome.Hang
            }),
            env = environment(PluginRuntimePermission.NETWORK),
        )
        assertFailsWith<SandboxBrokenException> { s.runtime.getPopularManga(1) }
        assertTrue(s.sandbox.closed)
        assertFailsWith<SandboxBrokenException> { s.runtime.getPopularManga(1) }
    }

    @Test
    fun failuresMapToStableErrors() = runTest {
        val s = start(hooks = mapOf(
            "getPopularManga" to { HookOutcome.Fail(SandboxErrorCode.LIMIT_MEMORY) },
            "getLatestUpdates" to { HookOutcome.Fail(SandboxErrorCode.SCRIPT_ERROR) },
            "getFilterList" to { HookOutcome.Ok("[{\"type\":\"header\",\"name\":\"H\"}]") },
        ))
        assertFailsWith<PluginResourceLimitException> { s.runtime.getPopularManga(1) }
        val e = assertFailsWith<SandboxInvocationException> { s.runtime.getLatestUpdates(1) }
        assertEquals(SandboxErrorCode.SCRIPT_ERROR, e.code)
        // A failed hook does not poison the session.
        assertEquals(listOf<dev.shinsou.kmp.plugin.Filter>(dev.shinsou.kmp.plugin.Filter.Header("H")), s.runtime.getFilterList())
        assertFalse(s.sandbox.closed)
    }

    @Test
    fun sourceFailureMarkersReachTheAppsGuidance() = runTest {
        val s = start(hooks = mapOf(
            "getPopularManga" to { HookOutcome.Fail(SandboxErrorCode.SCRIPT_ERROR, SandboxSourceFailure.HTTP_CHALLENGE) },
            "getLatestUpdates" to { HookOutcome.Fail(SandboxErrorCode.SCRIPT_ERROR) },
        ))
        // The same recognition the browse screen and downloads use for Rhino errors.
        val marked = assertFailsWith<SandboxInvocationException> { s.runtime.getPopularManga(1) }
        assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", marked.sourceFailureMarker())
        assertNull(assertFailsWith<SandboxInvocationException> { s.runtime.getLatestUpdates(1) }.sourceFailureMarker())
        // Every protocol marker is one the app recognises.
        for (marker in SandboxSourceFailure.entries) assertEquals(marker.marker, sourceFailureMarker(marker.marker))
    }

    @Test
    fun protocolViolationsKillTheSandbox() = runTest {
        val cases: List<suspend FakeSandbox.Hook.(JsonArray) -> HookOutcome> = listOf(
            { send(SandboxFrame.Result(callId + 1, "{}")); HookOutcome.Custom },
            { send(SandboxFrame.Ready(callId, 1)); HookOutcome.Custom },
            { sendRaw(byteArrayOf(0, 0, 0, 2, 0x13, 0x00)); HookOutcome.Custom },
            { send(SandboxFrame.BridgeCall(callId, 5, "getPreference", "[\"k\"]")); send(SandboxFrame.BridgeCall(callId, 5, "getPreference", "[\"k\"]")); HookOutcome.Hang },
            { die(); HookOutcome.Custom },
        )
        for ((i, case) in cases.withIndex()) {
            val s = start(hooks = mapOf("getPopularManga" to case))
            assertFailsWith<SandboxBrokenException>("case $i") { s.runtime.getPopularManga(1) }
            assertTrue(s.sandbox.closed, "case $i")
        }
    }

    @Test
    fun aHookThatNeverAnswersTimesOutAndIsKilled() = runTest {
        val s = start(
            hooks = mapOf("getPopularManga" to { HookOutcome.Hang }),
            env = environment(limits = PluginExecutionLimits(invocationWallTimeMillis = 500)),
        )
        assertFailsWith<PluginResourceLimitException> { s.runtime.getPopularManga(1) }
        assertTrue(s.sandbox.closed)
    }

    @Test
    fun cancellingTheCallerCancelsTheSandboxInvocationAndKeepsTheSession() = runTest {
        val s = start(hooks = mapOf(
            "getPopularManga" to { awaitCancel(); HookOutcome.Fail(SandboxErrorCode.CANCELLED) },
            "getLatestUpdates" to { HookOutcome.Ok("{\"mangas\":[],\"hasNextPage\":false}") },
        ))
        val call = async { s.runtime.getPopularManga(1) }
        repeat(5) { yield() }
        call.cancel()
        runCatching { call.await() }
        assertTrue(s.sandbox.received.any { it is SandboxFrame.Cancel })
        assertEquals(false, s.sandbox.closed)
        assertEquals(false, s.runtime.getLatestUpdates(1).hasNextPage)
    }

    @Test
    fun aSandboxThatIgnoresCancelIsKilled() = runTest {
        val s = start(hooks = mapOf("getPopularManga" to { HookOutcome.Hang }))
        val call = async { s.runtime.getPopularManga(1) }
        repeat(5) { yield() }
        call.cancel()
        runCatching { call.await() }
        assertTrue(s.sandbox.closed)
    }

    @Test
    fun passwordsAreOnlyReadableInsideTheLoginHook() = runTest {
        storage.setCredential(42, PluginCredential("alice", "pw"))
        var duringBrowse: BridgeOutcome? = null
        var duringLogin: BridgeOutcome? = null
        val s = start(
            hooks = mapOf(
                "getPopularManga" to { duringBrowse = bridge("getCredentialPassword", "[]"); HookOutcome.Ok("{\"mangas\":[]}") },
                "login" to { duringLogin = bridge("getCredentialPassword", "[]"); HookOutcome.Ok("{\"loggedIn\":true}") },
            ),
            env = environment(PluginRuntimePermission.CREDENTIAL_ACCESS),
        )
        s.runtime.getPopularManga(1)
        assertTrue(s.runtime.login("alice", "pw2"))
        assertEquals(BridgeOutcome.Error(SandboxErrorCode.PERMISSION_DENIED), duringBrowse)
        assertEquals(BridgeOutcome.Value("\"pw\""), duringLogin)
        assertEquals("pw2", storage.getCredential(42)?.password)
    }

    @Test
    fun oversizedOrOverShapedResultsAreRejected() = runTest {
        val s = start(
            hooks = mapOf(
                "getPopularManga" to { HookOutcome.Ok("{\"mangas\":[" + List(50) { "{}" }.joinToString(",") + "]}") },
                "getLatestUpdates" to { HookOutcome.Ok("{\"mangas\":[{\"title\":\"" + "x".repeat(200) + "\"}]}") },
            ),
            env = environment(limits = PluginExecutionLimits(maxResultArrayElements = 10, maxResultObjectEntries = 10, maxResultTotalElements = 100, maxResultStringBytes = 100, maxResultBytes = 4_096)),
        )
        assertFailsWith<PluginResourceLimitException> { s.runtime.getPopularManga(1) }
        assertFailsWith<PluginResourceLimitException> { s.runtime.getLatestUpdates(1) }
    }

    @Test
    fun logsAreBoundedAndResetPerListing() = runTest {
        val s = start(
            hooks = mapOf("getPopularManga" to {
                repeat(10) { send(SandboxFrame.Log("line $it".encodeToByteArray())) }
                HookOutcome.Ok("{\"mangas\":[]}")
            }),
            env = environment(limits = PluginExecutionLimits(maxLogEntries = 3)),
        )
        s.runtime.getPopularManga(1)
        assertEquals(listOf("line 7", "line 8", "line 9"), s.runtime.recentLogs)
    }

    @Test
    fun cancellingWhileTheSandboxStopsReadingIsBounded() = runTest {
        val s = start(hooks = mapOf("getPopularManga" to { stopReading(); HookOutcome.Hang }))
        val call = async { s.runtime.getPopularManga(1) }
        repeat(5) { yield() }
        call.cancel()
        runCatching { call.await() } // must return: the Cancel write is bounded too
        assertTrue(s.sandbox.closed)
        // The next call starts a fresh sandbox; this one stops reading too and its time limit kills it.
        assertFailsWith<PluginResourceLimitException> { s.runtime.getPopularManga(1) }
    }

    @Test
    fun teardownWithABlockedWriteDoesNotHangAndTheNextCallIsBounded() = runTest {
        val s = start(
            hooks = mapOf("getPopularManga" to {
                startAsync(1, "net.fetch", "[\"https://www.example.test/a\"]")
                stopReading()
                HookOutcome.Ok("{\"mangas\":[]}")
            }),
            env = environment(PluginRuntimePermission.NETWORK),
        )
        // Writes are queued, so ending the invocation never waits for the stalled sandbox...
        assertEquals(false, s.runtime.getPopularManga(1).hasNextPage)
        // ...and a sandbox that stopped reading cannot answer the next call: its time limit kills it.
        assertFailsWith<PluginResourceLimitException> { s.runtime.getPopularManga(1) }
        assertTrue(s.sandbox.closed)
    }

    @Test
    fun noAsyncResultIsEverDeliveredAfterTheInvocationEnded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = start(
            hooks = mapOf(
                "getPopularManga" to {
                    startAsync(1, "net.fetch", "[\"https://www.example.test/slow\"]")
                    HookOutcome.Ok("{\"mangas\":[]}") // returns without awaiting the fetch
                },
                "getLatestUpdates" to { HookOutcome.Ok("{\"mangas\":[]}") },
            ),
            env = environment(PluginRuntimePermission.NETWORK, networkGate = gate),
        )
        s.runtime.getPopularManga(1)
        gate.complete(Unit)
        repeat(20) { yield() }
        s.runtime.getLatestUpdates(1)
        val frames = s.sandbox.received
        assertTrue(frames.none { it is SandboxFrame.AsyncBridgeResult }, "$frames")
        val cancelAt = frames.indexOfFirst { it is SandboxFrame.AsyncCancel && it.opId == 1L }
        val nextInvokeAt = frames.indexOfLast { it is SandboxFrame.Invoke }
        assertTrue(cancelAt in 0 until nextInvokeAt, "$frames")
        assertFalse(s.sandbox.closed)
    }

    @Test
    fun floodingFramesThatNeedNoAnswerIsBounded() = runTest {
        val logs = start(hooks = mapOf("getPopularManga" to {
            repeat(70_000) { send(SandboxFrame.Log(byteArrayOf(1))); if (it % 256 == 0) yield() }
            HookOutcome.Hang
        }))
        assertFailsWith<SandboxBrokenException> { logs.runtime.getPopularManga(1) }
        assertTrue(logs.sandbox.closed)

        val calls = start(
            hooks = mapOf("getPopularManga" to {
                repeat(100) { send(SandboxFrame.BridgeCall(callId, it + 1L, "getPreference", "[\"k\"]")) }
                HookOutcome.Hang
            }),
            env = environment(limits = PluginExecutionLimits(maxBridgeCallsPerInvocation = 8)),
        )
        assertFailsWith<SandboxBrokenException> { calls.runtime.getPopularManga(1) }
        assertTrue(calls.sandbox.closed)
    }

    @Test
    fun closeKillsTheSandboxAndRefusesFurtherCalls() = runTest {
        val s = start(hooks = mapOf("getPopularManga" to { HookOutcome.Ok("{\"mangas\":[]}") }))
        s.runtime.close()
        assertTrue(s.sandbox.closed)
        assertFailsWith<SandboxBrokenException> { s.runtime.getPopularManga(1) }
    }

    // ---- lifecycle (ADR-0002 §4.6, §7) ----

    private class Fleet(val factory: SandboxRuntimeFactory, val opened: MutableList<FakeSandbox>)

    private fun TestScope.lifecycle(maxLive: Int = 4, idleMs: Long = 60_000, countTimeouts: () -> Boolean = { true }) =
        SandboxLifecycle(
            maxLive = maxLive,
            idleMs = idleMs,
            nowMs = { testScheduler.currentTime },
            countTimeouts = countTimeouts,
            scope = backgroundScope,
        )

    private fun TestScope.fleet(
        lifecycle: SandboxLifecycle,
        hooks: Map<String, suspend FakeSandbox.Hook.(JsonArray) -> HookOutcome>,
        initFailure: () -> SandboxErrorCode? = { null },
    ): Fleet {
        val opened = ArrayList<FakeSandbox>()
        val factory = SandboxRuntimeFactory(
            transports = { FakeSandbox(backgroundScope, initFailure = initFailure(), hooks = metadataHooks + hooks).also { opened += it } },
            lifecycle = lifecycle,
            pumpScope = backgroundScope,
        )
        return Fleet(factory, opened)
    }

    private suspend fun Fleet.create(id: String = manifest.id): ScriptPluginRuntime =
        factory.createForSource("var source = {};", manifest.copy(id = id), source, environment())

    private val emptyPage: Map<String, suspend FakeSandbox.Hook.(JsonArray) -> HookOutcome> =
        mapOf("getPopularManga" to { HookOutcome.Ok("{\"mangas\":[]}") })

    @Test
    fun aCrashedSandboxIsReplacedOnTheNextCallWithTheScriptReloaded() = runTest {
        var calls = 0
        val f = fleet(lifecycle(), mapOf("getPopularManga" to {
            if (++calls == 1) { die(); HookOutcome.Custom } else HookOutcome.Ok("{\"mangas\":[]}")
        }))
        val runtime = f.create()
        assertFailsWith<SandboxBrokenException> { runtime.getPopularManga(1) }
        assertEquals(false, runtime.getPopularManga(1).hasNextPage)
        assertEquals(2, f.opened.size)
        assertTrue(f.opened[0].closed)
        assertContains(f.opened[1].initScript.orEmpty(), "var source = {};")
        assertTrue(f.opened[1].received.any { it is SandboxFrame.Invoke && it.method == "__shinsouSelectSource" })
    }

    @Test
    fun repeatedCrashesDisableThePackageWithoutStartingAnotherSandbox() = runTest {
        val lc = lifecycle()
        val f = fleet(lc, mapOf("getPopularManga" to { die(); HookOutcome.Custom }))
        val runtime = f.create()
        repeat(3) { assertFailsWith<SandboxBrokenException> { runtime.getPopularManga(1) } }
        val opened = f.opened.size
        assertFailsWith<SandboxDisabledException> { runtime.getPopularManga(1) }
        assertFailsWith<SandboxDisabledException> { f.create() }
        assertEquals(opened, f.opened.size)
        assertEquals(0, lc.liveCount())
    }

    @Test
    fun aScriptThatStopsLoadingIsDisabledInsteadOfRespawningForever() = runTest {
        var broken = false
        val f = fleet(
            lifecycle(),
            // Crash once; every restart from then on fails to load the script.
            mapOf("getPopularManga" to { broken = true; die(); HookOutcome.Custom }),
            initFailure = { if (broken) SandboxErrorCode.SCRIPT_ERROR else null },
        )
        val runtime = f.create()
        assertFailsWith<SandboxBrokenException> { runtime.getPopularManga(1) }
        repeat(2) { assertFailsWith<SandboxInvocationException> { runtime.getPopularManga(1) } }
        val opened = f.opened.size
        assertFailsWith<SandboxDisabledException> { runtime.getPopularManga(1) }
        assertEquals(opened, f.opened.size)
    }

    @Test
    fun strikesOutsideTheWindowAreForgotten() = runTest {
        val f = fleet(lifecycle(), mapOf("getPopularManga" to { die(); HookOutcome.Custom }))
        val runtime = f.create()
        repeat(2) { assertFailsWith<SandboxBrokenException> { runtime.getPopularManga(1) } }
        advanceTimeBy(10 * 60_000L)
        // Counting the old strikes would have disabled the package after the first of these.
        repeat(3) { assertFailsWith<SandboxBrokenException> { runtime.getPopularManga(1) } }
        assertFailsWith<SandboxDisabledException> { runtime.getPopularManga(1) }
    }

    @Test
    fun limitViolationsCountButTimeoutsTheHostCannotVouchForDoNot() = runTest {
        var trustClock = false
        val f = fleet(lifecycle(countTimeouts = { trustClock }), mapOf(
            "getPopularManga" to { HookOutcome.Fail(SandboxErrorCode.LIMIT_TIME) },
            "getLatestUpdates" to { HookOutcome.Fail(SandboxErrorCode.LIMIT_MEMORY) },
        ))
        val runtime = f.create()
        repeat(5) { assertFailsWith<PluginResourceLimitException> { runtime.getPopularManga(1) } }
        trustClock = true
        repeat(2) { assertFailsWith<PluginResourceLimitException> { runtime.getPopularManga(1) } }
        assertFailsWith<PluginResourceLimitException> { runtime.getLatestUpdates(1) }
        // A disabled package's live sandbox is stopped too, not only future starts refused.
        assertFailsWith<SandboxDisabledException> { runtime.getPopularManga(1) }
        assertTrue(f.opened.single().closed)
    }

    @Test
    fun idleSandboxesStopAndRestartOnDemand() = runTest {
        val f = fleet(lifecycle(), emptyPage)
        val runtime = f.create()
        runtime.getPopularManga(1)
        advanceTimeBy(59_000)
        assertFalse(f.opened[0].closed)
        advanceTimeBy(20_000)
        assertTrue(f.opened[0].closed)
        assertEquals(false, runtime.getPopularManga(1).hasNextPage)
        assertEquals(2, f.opened.size)
    }

    @Test
    fun theLeastRecentlyUsedIdleSandboxMakesRoom() = runTest {
        val lc = lifecycle(maxLive = 2)
        val f = fleet(lc, emptyPage)
        val a = f.create("a")
        advanceTimeBy(10)
        f.create("b")
        advanceTimeBy(10)
        a.getPopularManga(1) // a is now more recent than b
        advanceTimeBy(10)
        f.create("c")
        assertEquals(listOf(false, true, false), f.opened.map { it.closed })
        assertEquals(2, lc.liveCount())
    }

    @Test
    fun aBusySandboxIsNeverStoppedToMakeRoomOrForIdleness() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lc = lifecycle(maxLive = 1, idleMs = 5_000)
        val f = fleet(lc, mapOf("getPopularManga" to { gate.await(); HookOutcome.Ok("{\"mangas\":[]}") }))
        val a = f.create("a")
        val call = async { a.getPopularManga(1) }
        runCurrent()
        f.create("b") // over the cap, but a is mid-call
        advanceTimeBy(10_000)
        assertFalse(f.opened[0].closed)
        assertTrue(f.opened[1].closed) // b went idle
        gate.complete(Unit)
        assertEquals(false, call.await().hasNextPage)
    }

    /** Holds the lifecycle lock until the returned deferred completes, so bookkeeping must wait. */
    private suspend fun TestScope.holdLifecycle(lc: SandboxLifecycle): CompletableDeferred<Unit> {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backgroundScope.launch { lc.holdForTest { held.complete(Unit); release.await() } }
        held.await()
        return release
    }

    @Test
    fun aCloseCancelledWhileWaitingForTheLifecycleStillUnregisters() = runTest {
        val lc = lifecycle()
        val f = fleet(lc, emptyPage)
        val runtime = f.create()
        assertEquals(1, lc.liveCount())
        val release = holdLifecycle(lc)
        val closing = launch { runtime.close() }
        runCurrent() // close() now waits for the lifecycle lock
        closing.cancel()
        release.complete(Unit)
        closing.join()
        assertTrue(f.opened.single().closed)
        assertEquals(0, lc.liveCount())
    }

    @Test
    fun aCallCancelledWhileWaitingForTheLifecycleStillUnregistersItsDeadSandbox() = runTest {
        val lc = lifecycle()
        val f = fleet(lc, mapOf("getPopularManga" to { awaitCancel(); die(); HookOutcome.Custom }))
        val runtime = f.create()
        val call = async { runtime.getPopularManga(1) }
        runCurrent()
        val release = holdLifecycle(lc)
        call.cancel() // the sandbox dies on Cancel; the call's bookkeeping waits for the lock
        runCurrent()
        release.complete(Unit)
        runCatching { call.await() }
        assertTrue(f.opened.single().closed)
        assertEquals(0, lc.liveCount())
    }
}
