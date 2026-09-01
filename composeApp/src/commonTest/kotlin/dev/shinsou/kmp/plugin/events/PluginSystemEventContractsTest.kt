package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PluginSystemEventContractsTest {
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val codec = PluginSystemEventCodec()

    @Test
    fun codecAcceptsOmittedOptionalEnvelopeFieldsAndRejectsUnknownFields() {
        val raw = """
            {"protocol":"dev.shinsou.system","version":1,"kind":"command",
             "name":"auth.login.request","id":"login-1","payloadVersion":1,
             "payload":{"reasonCode":"AUTH_REQUIRED","fallbackMessage":"Please sign in"}}
        """.trimIndent().encodeToByteArray()
        val envelope = codec.decode(raw)
        assertEquals(null, envelope.idempotencyKey)
        assertEquals(null, envelope.contextRef)
        assertEquals("auth.login.request", envelope.name)

        val spoofed = raw.decodeToString().replace(
            "\"payloadVersion\":1,",
            "\"payloadVersion\":1,\"packageId\":\"evil\",",
        )
        assertFailsWith<PluginSystemEventCodecException> { codec.decode(spoofed.encodeToByteArray()) }
    }

    @Test
    fun codecRejectsDuplicateKeysDepthAndOversizeBeforeDecode() {
        val duplicate = """
            {"protocol":"dev.shinsou.system","version":1,"kind":"command",
             "name":"auth.login.request","id":"one","id":"two","payloadVersion":1,"payload":{}}
        """.trimIndent()
        assertFailsWith<PluginSystemEventCodecException> { codec.decode(duplicate.encodeToByteArray()) }

        val deep = """
            {"protocol":"dev.shinsou.system","version":1,"kind":"command","name":"x",
             "id":"one","payloadVersion":1,"payload":{"a":{"b":{"c":{"d":{"e":1}}}}}}
        """.trimIndent()
        assertFailsWith<PluginSystemEventCodecException> { codec.decode(deep.encodeToByteArray()) }

        val limits = PluginSystemEventLimits(maxEnvelopeBytes = 256)
        assertFailsWith<PluginSystemEventCodecException> {
            PluginSystemEventCodec(limits).decode(ByteArray(257) { 'x'.code.toByte() })
        }
    }

    @Test
    fun payloadTypesEnforceSafeTextAndStrictFields() {
        assertFailsWith<IllegalArgumentException> {
            LoginRequestV1(fallbackMessage = "https://evil.example/collect")
        }
        assertFailsWith<IllegalArgumentException> {
            DiagnosticMessageV1(
                code = "plugin.failure",
                severity = PluginDiagnosticSeverity.ERROR,
                fallbackMessage = "<a>click</a>",
            )
        }
        listOf(
            "username=alice password=secret",
            "cookie: session-value",
            "token=opaque-token",
            "Authorization: Bearer abc.def",
            "secret-ref: vault-entry",
            "Failure\n at pkg.Source.run(Source.kt:42)",
        ).forEach { unsafe ->
            assertFailsWith<IllegalArgumentException>(unsafe) {
                DiagnosticMessageV1(
                    code = "plugin.failure",
                    severity = PluginDiagnosticSeverity.ERROR,
                    fallbackMessage = unsafe,
                )
            }
        }
        val payload = JsonObject(
            mapOf(
                "reasonCode" to JsonPrimitive("AUTH_REQUIRED"),
                "unexpected" to JsonPrimitive(true),
            ),
        )
        assertFailsWith<PluginSystemEventCodecException> {
            codec.decodePayload(payload, LoginRequestV1.serializer())
        }
    }

    @Test
    fun unknownProtocolVersionAndKindRemainUnsupportedAfterStructuralDecode() {
        fun envelope(protocol: String, version: Int, kind: String): ByteArray = """
            {"protocol":"$protocol","version":$version,"kind":"$kind","name":"auth.login.request",
             "id":"one","payloadVersion":1,"payload":{}}
        """.trimIndent().encodeToByteArray()

        val unknownProtocol = codec.decode(envelope("dev.unknown", 1, "command"))
        assertEquals("dev.unknown", unknownProtocol.protocol)
        val unknownVersion = codec.decode(envelope("dev.shinsou.system", 99, "command"))
        assertEquals(99, unknownVersion.version)
        val unknownKind = codec.decode(envelope("dev.shinsou.system", 1, "future"))
        assertEquals(PluginSystemEventKind.UNKNOWN, unknownKind.kind)
    }

    @Test
    fun capabilityNegotiationDoesNotTurnUnknownRequiredIntoGranted() {
        val result = PluginSystemCapabilityNegotiator().negotiate(
            PluginSystemEventDeclaration(
                minVersion = 1,
                maxVersion = 1,
                required = setOf(PluginSystemEventNames.LOGIN_CAPABILITY, "command.future.request"),
                optional = setOf("event.future.report"),
            ),
        )
        assertFalse(result.enabled)
        assertEquals(setOf("command.future.request"), result.deniedRequiredCapabilities)
        assertEquals(setOf(PluginSystemEventNames.LOGIN_CAPABILITY), result.grantedCapabilities)
    }

    @Test
    fun hostPermissionAndSourceCapabilityAreIndependent() {
        val source = SourceKey(packageId = "pkg", sourceId = "source")
        val artifact = PluginArtifactIdentity("pkg", "1.0.0", 1, digest)
        val scope = BoundPluginScopeFactory(PluginEventClock { 1 }).bind(artifact, source, "runtime", 1)
        val authorizer = MutablePluginSystemEventAuthorizer()
        authorizer.grant(
            PluginEventGrantKey(artifact, source),
            setOf(PluginHostPermission.REQUEST_LOGIN_UI),
        )
        assertEquals(
            PluginEventAuthorizationReason.MISSING_SOURCE_CAPABILITY,
            authorizer.authorize(scope, PluginHostPermission.REQUEST_LOGIN_UI, "LOGIN").reason,
        )
        authorizer.setRuntimeStatus(
            scope,
            PluginEventRuntimeStatus(
                sourceCapabilities = setOf("LOGIN"),
                lifecycle = PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED,
                hasUserInteractionContext = true,
            ),
        )
        assertTrue(authorizer.authorize(scope, PluginHostPermission.REQUEST_LOGIN_UI, "LOGIN").allowed)
        assertFalse(authorizer.authorize(scope, PluginHostPermission.REQUEST_LOGOUT, "LOGIN").allowed)
    }
}
