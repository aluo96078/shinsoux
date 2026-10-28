package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.PluginCookie
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginExecutionLimits
import dev.shinsou.kmp.plugin.PluginHostResolution
import dev.shinsou.kmp.plugin.PluginHostResolver
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginNetworkPolicy
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxBridgeDispatcherTest {
    private val source = SourceIndexEntry(
        name = "Example",
        lang = "zh",
        id = 42,
        baseUrl = "https://www.example.test",
        requestOrigins = setOf("https://www.example.test"),
        credentialOrigins = setOf("https://www.example.test"),
    )

    private class Fixture(
        permissions: Set<PluginRuntimePermission>,
        limits: PluginExecutionLimits = PluginExecutionLimits(),
        policy: SandboxBridgePolicy = SandboxBridgePolicy(),
        source: SourceIndexEntry,
        val response: (PluginHttpRequest) -> PluginHttpResponse = { PluginHttpResponse(200, "hello".encodeToByteArray()) },
    ) {
        val requests = ArrayList<PluginHttpRequest>()
        var generation = 1L
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        private val transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
                requests += request
                return response(request)
            }

            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                requests += request
                return response(request)
            }
        }
        val environment = ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = transport,
                storage = storage,
                policy = PluginNetworkPolicy(requestOrigins = source.requestOrigins, credentialOrigins = source.credentialOrigins),
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            ),
            storage = storage,
            runtimePermissions = permissions,
            executionLimits = limits,
        )
        val dispatcher = SandboxBridgeDispatcher(
            environment = environment,
            source = source,
            policy = policy,
            currentGeneration = { generation },
            nowEpochMillis = { 1_000_000L },
        )
    }

    private fun fixture(
        vararg permissions: PluginRuntimePermission,
        limits: PluginExecutionLimits = PluginExecutionLimits(),
        policy: SandboxBridgePolicy = SandboxBridgePolicy(),
        response: (PluginHttpRequest) -> PluginHttpResponse = { PluginHttpResponse(200, "hello".encodeToByteArray()) },
    ) = Fixture(permissions.toSet(), limits, policy, source, response)

    private fun invocation(login: Boolean = false) = SandboxInvocation(callId = 1, generation = 1, loginInvocation = login)

    private fun err(code: SandboxErrorCode) = BridgeOutcome.Error(code)

    @Test
    fun networkNeedsThePermissionAndReturnsTheExistingShapes() = runTest {
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), fixture().dispatcher.dispatch(invocation(), "httpGet", "[\"https://www.example.test/a\"]"))
        val f = fixture(PluginRuntimePermission.NETWORK)
        assertEquals(BridgeOutcome.Value("\"hello\""), f.dispatcher.dispatch(invocation(), "httpGet", "[\"https://www.example.test/a\"]"))
        val response = f.dispatcher.dispatch(invocation(), "httpGetResponse", "[\"https://www.example.test/a\",{}]") as BridgeOutcome.Value
        val obj = Json.parseToJsonElement(response.json!!).jsonObject
        assertEquals(200, obj["status"]!!.jsonPrimitive.int)
        assertEquals("hello", obj["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun undeclaredOriginsAndHostFailuresAreStableCodesWithoutMessages() = runTest {
        val f = fixture(PluginRuntimePermission.NETWORK)
        assertEquals(err(SandboxErrorCode.NETWORK_FAILED), f.dispatcher.dispatch(invocation(), "httpGet", "[\"https://evil.test/\"]"))
        assertTrue(f.requests.isEmpty())
        val broken = fixture(PluginRuntimePermission.NETWORK, response = { throw IllegalStateException("secret host detail") })
        assertEquals(err(SandboxErrorCode.NETWORK_FAILED), broken.dispatcher.dispatch(invocation(), "httpGet", "[\"https://www.example.test/\"]"))
    }

    @Test
    fun netFetchFiltersCredentialHeadersAndOnlyAllowsGetAndPost() = runTest {
        val f = fixture(PluginRuntimePermission.NETWORK, response = {
            PluginHttpResponse(
                201,
                "{\"ok\":true}".encodeToByteArray(),
                mapOf("Content-Type" to listOf("application/json"), "Set-Cookie" to listOf("sid=1"), "WWW-Authenticate" to listOf("Basic")),
            )
        })
        val out = f.dispatcher.dispatchAsync(
            invocation(), "net.fetch",
            "[\"https://www.example.test/api\",{\"method\":\"POST\",\"headers\":{\"X-A\":\"1\"},\"body\":\"q=1\"}]",
        ) as BridgeOutcome.Value
        val obj = Json.parseToJsonElement(out.json!!).jsonObject
        assertEquals(201, obj["status"]!!.jsonPrimitive.int)
        assertEquals(setOf("content-type"), (obj["headers"] as JsonObject).keys)
        assertEquals("POST", f.requests.single().method)
        assertEquals("q=1", f.requests.single().body.decodeToString())

        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatchAsync(invocation(), "net.fetch", "[\"https://www.example.test/\",{\"method\":\"DELETE\"}]"))
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatchAsync(invocation(), "net.fetch", "[\"https://www.example.test/\",{\"body\":\"x\"}]"))
        assertEquals(err(SandboxErrorCode.UNKNOWN_OPERATION), f.dispatcher.dispatchAsync(invocation(), "httpGet", "[\"https://www.example.test/\"]"))
    }

    @Test
    fun everyCallCountsAndRevocationOrCloseRefusesFurtherCalls() = runTest {
        val f = fixture(limits = PluginExecutionLimits(maxBridgeCallsPerInvocation = 2))
        val inv = invocation()
        f.dispatcher.dispatch(inv, "getPreference", "[\"k\"]")
        f.dispatcher.dispatch(inv, "getPreference", "[\"k\"]")
        assertEquals(err(SandboxErrorCode.LIMIT_BRIDGE), f.dispatcher.dispatch(inv, "getPreference", "[\"k\"]"))

        val g = fixture()
        val stale = invocation()
        g.generation = 2
        assertEquals(err(SandboxErrorCode.REVOKED), g.dispatcher.dispatch(stale, "getPreference", "[\"k\"]"))
        val closed = SandboxInvocation(1, 2).also { it.closed = true }
        assertEquals(err(SandboxErrorCode.REVOKED), g.dispatcher.dispatch(closed, "getPreference", "[\"k\"]"))
    }

    @Test
    fun domLoggingAndUnknownOperationsNeverReachTheHost() = runTest {
        val f = fixture(*PluginRuntimePermission.LEGACY_COMPATIBILITY.toTypedArray())
        for (op in listOf("domParse", "domSelect", "parseHtml", "log", "nope", "__proto__")) {
            assertEquals(err(SandboxErrorCode.UNKNOWN_OPERATION), f.dispatcher.dispatch(invocation(), op, "[]"), op)
        }
    }

    @Test
    fun malformedOrOversizedArgumentsAreRejected() = runTest {
        val f = fixture(limits = PluginExecutionLimits(maxBridgeArgumentBytes = 64, maxResultArrayElements = 4, maxResultObjectEntries = 4, maxResultTotalElements = 16))
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatch(invocation(), "getPreference", "not json"))
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatch(invocation(), "getPreference", "{\"a\":1}"))
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatch(invocation(), "getPreference", "[1,2,3,4,5]"))
        assertEquals(err(SandboxErrorCode.LIMIT_BRIDGE), f.dispatcher.dispatch(invocation(), "getPreference", "[\"" + "x".repeat(100) + "\"]"))
        // Each array is within its own limit, but the total node count is not.
        val g = fixture()
        val wide = "[" + List(511) { "[" + List(4_096) { "0" }.joinToString(",") + "]" }.joinToString(",") + "]"
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), g.dispatcher.dispatch(invocation(), "getPreference", wide))
        val manyKeys = "[{" + List(5_000) { "\"k$it\":0" }.joinToString(",") + "}]"
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), g.dispatcher.dispatch(invocation(), "getPreference", manyKeys))
    }

    @Test
    fun oversizedResultsAreRefused() = runTest {
        val f = fixture(PluginRuntimePermission.NETWORK, limits = PluginExecutionLimits(maxBridgeResultBytes = 16), response = {
            PluginHttpResponse(200, "x".repeat(64).encodeToByteArray())
        })
        assertEquals(err(SandboxErrorCode.LIMIT_RESULT), f.dispatcher.dispatch(invocation(), "httpGet", "[\"https://www.example.test/\"]"))
    }

    @Test
    fun passwordsAreOnlyReachableDuringTheLoginInvocation() = runTest {
        val f = fixture(PluginRuntimePermission.CREDENTIAL_ACCESS)
        f.storage.setCredential(42, PluginCredential("alice", "s3cret"))
        assertEquals(BridgeOutcome.Value("\"alice\""), f.dispatcher.dispatch(invocation(), "getCredentialUsername", "[]"))
        assertEquals(BridgeOutcome.Value("true"), f.dispatcher.dispatch(invocation(), "hasCredential", "[]"))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), f.dispatcher.dispatch(invocation(), "getCredentialPassword", "[]"))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), f.dispatcher.dispatch(invocation(), "setCredential", "[\"a\",\"b\"]"))
        assertEquals(BridgeOutcome.Value("\"s3cret\""), f.dispatcher.dispatch(invocation(login = true), "getCredentialPassword", "[]"))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), fixture().dispatcher.dispatch(invocation(login = true), "getCredentialPassword", "[]"))
    }

    @Test
    fun cookieValuesNeedTheReadGrantAndStayOnCredentialOrigins() = runTest {
        val f = fixture(PluginRuntimePermission.COOKIE_STORAGE)
        f.storage.setCookie(42, PluginCookie("sid", "v1", "www.example.test"))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), f.dispatcher.dispatch(invocation(), "getCookie", "[\"sid\",\"https://www.example.test/\"]"))

        val g = fixture(PluginRuntimePermission.COOKIE_STORAGE, policy = SandboxBridgePolicy(allowCookieRead = true))
        g.storage.setCookie(42, PluginCookie("sid", "v1", "www.example.test"))
        assertEquals(BridgeOutcome.Value("\"v1\""), g.dispatcher.dispatch(invocation(), "getCookie", "[\"sid\",\"https://www.example.test/\"]"))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), g.dispatcher.dispatch(invocation(), "getCookies", "[\"https://evil.test/\"]"))
    }

    @Test
    fun cookieWritesAreConfinedToCredentialOriginsAndNeverAPublicSuffix() = runTest {
        val f = fixture(PluginRuntimePermission.COOKIE_STORAGE)
        val set = { domain: String -> "[\"sid\",\"v\",\"$domain\",\"/\",0]" }
        assertEquals(BridgeOutcome.Value("true"), f.dispatcher.dispatch(invocation(), "setCookie", set("www.example.test")))
        assertEquals(BridgeOutcome.Value("true"), f.dispatcher.dispatch(invocation(), "setCookie", set(".example.test")))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), f.dispatcher.dispatch(invocation(), "setCookie", set("evil.test")))
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatch(invocation(), "setCookie", set("test")))
        assertEquals(err(SandboxErrorCode.BAD_ARGUMENT), f.dispatcher.dispatch(invocation(), "setCookie", set("co.uk")))
        assertEquals(err(SandboxErrorCode.PERMISSION_DENIED), fixture().dispatcher.dispatch(invocation(), "setCookie", set("www.example.test")))
    }

    @Test
    fun loginPromptIsFalseWithoutTheGrant() = runTest {
        assertEquals(BridgeOutcome.Value("false"), fixture().dispatcher.dispatch(invocation(), "requestLogin", "[\"why\"]"))
        assertFalse(fixture().environment.runtimePermissions.contains(PluginRuntimePermission.LOGIN_PROMPT))
    }
}
