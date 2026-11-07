@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shinsou.kmp.plugin

import com.ionspin.kotlin.crypto.signature.Signature
import dev.shinsou.kmp.plugin.sandbox.BridgeOutcome
import dev.shinsou.kmp.plugin.sandbox.SandboxErrorCode
import dev.shinsou.kmp.plugin.sandbox.SandboxFrame
import dev.shinsou.kmp.plugin.sandbox.SandboxFrameCodec
import dev.shinsou.kmp.plugin.sandbox.SandboxLimitKind
import dev.shinsou.kmp.plugin.sandbox.SandboxLimits
import dev.shinsou.kmp.plugin.sandbox.SandboxPeer
import dev.shinsou.kmp.plugin.sandbox.SandboxSourceFailure
import dev.shinsou.kmp.sync.crypto.SodiumKeyPair
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The plugin publishing contract shared with the `@shinsou/plugin` CLI (fixtures/plugin-contract/v1).
 *
 * Each vector file is produced by this test from the app's own code (canonical JSON, the
 * `shinsou-signed-envelope-v1` signer and verifier, the SandboxProtocol v1 codec), and the files are
 * then checked in: by default the test regenerates them in memory and fails if a checked-in file
 * differs, so any change to the wire contract shows up as a fixture diff. The CLI asserts the same
 * files byte for byte. Regenerate with `./gradlew :composeApp:desktopTest -PwritePluginContractVectors=true
 * --tests '*PluginContractVectorTest'` and review the diff: it is a protocol change.
 */
class PluginContractVectorTest {
    private val directory = File(
        System.getProperty("shinsou.pluginContractFixtures")
            ?.takeIf(String::isNotBlank)
            ?: "../fixtures/plugin-contract/v1",
    )
    private val writeVectors = System.getProperty("shinsou.writePluginContractVectors") == "true"
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    @Test
    fun publisherKeysMatchTheFixture() = runTest {
        SodiumSyncPrimitives.initialize()
        val keys = buildJsonArray {
            TEST_KEYS.forEach { (name, label) ->
                val seed = seedFor(label)
                val pair = keyPair(seed)
                val fingerprint = PublisherFingerprint.of(pair.publicKey)
                add(
                    buildJsonObject {
                        put("name", name)
                        put("seedLabel", label)
                        put("seedHex", seed.toHex())
                        put("publicKey", SodiumSyncPrimitives.base64UrlEncode(pair.publicKey))
                        put("fingerprint", fingerprint)
                        put("displayFingerprint", PublisherFingerprint.display(fingerprint))
                    },
                )
                assertEquals(fingerprint, PublisherFingerprint.parse(fingerprint))
                // The hex form a user may paste resolves to the same fingerprint.
                assertEquals(fingerprint, PublisherFingerprint.parse(SodiumSyncPrimitives.sha256(pair.publicKey).toHex()))
            }
        }
        check("keys.json", buildJsonObject {
            put("note", "TEST KEYS ONLY. Seeds are SHA-256 of the seed label; never use them to sign real repositories.")
            put("keys", keys)
        })
    }

    @Test
    fun canonicalJsonMatchesTheFixture() = runTest {
        SodiumSyncPrimitives.initialize()
        val valid = buildJsonArray {
            CANONICAL_VALID.forEach { (name, text) ->
                val canonical = canonicalSyncJson(Json.parseToJsonElement(text))
                add(
                    buildJsonObject {
                        put("name", name)
                        put("json", text)
                        put("canonical", canonical)
                        put("sha256Hex", SodiumSyncPrimitives.sha256(canonical.encodeToByteArray()).toHex())
                    },
                )
            }
        }
        val invalid = buildJsonArray {
            CANONICAL_INVALID.forEach { (name, text) ->
                assertFailsWith<Throwable>("$name must be rejected") { canonicalSyncJson(Json.parseToJsonElement(text)) }
                add(buildJsonObject { put("name", name); put("json", text) })
            }
        }
        check("canonical-json.json", buildJsonObject {
            put("valid", valid)
            put("invalid", invalid)
        })
    }

    @Test
    fun signedEnvelopesMatchTheFixtureAndVerify() = runTest {
        SodiumSyncPrimitives.initialize()
        val a = keyPair(seedFor(TEST_KEYS[0].second))
        val b = keyPair(seedFor(TEST_KEYS[1].second))
        val fa = PublisherFingerprint.of(a.publicKey)
        val fb = PublisherFingerprint.of(b.publicKey)

        val rotationStatement = PublisherTrustStore.rotationStatement(fa, fa, publisherRoot(b), 2)
        val newKeySignature = SodiumSyncPrimitives.signEd25519(rotationStatement, b.privateKey)
        val cases = listOf(
            EnvelopeCase("repository-meta", "publisher-a", a, REPOSITORY, RepositorySignedDocumentType.REPOSITORY, "repo.json", 1,
                buildJsonObject {
                    putJsonObject("meta") {
                        put("name", "Example Publisher")
                        put("shortName", "Example")
                        put("website", "https://plugins.example.test")
                        put("signingKeyFingerprint", fa)
                    }
                }),
            EnvelopeCase("repository-meta-mirror", "publisher-a", a, MIRROR, RepositorySignedDocumentType.REPOSITORY, "repo.json", 1,
                buildJsonObject {
                    putJsonObject("meta") {
                        put("name", "Example Publisher")
                        put("shortName", "Example")
                        put("website", "https://plugins.example.test")
                        put("signingKeyFingerprint", fa)
                    }
                }),
            EnvelopeCase("repository-index", "publisher-a", a, REPOSITORY, RepositorySignedDocumentType.INDEX, "index.json", 7,
                buildJsonObject {
                    put("format", "shinsou-extension-v2")
                    put("contractVersion", 2)
                    putJsonArray("plugins") {
                        add(buildJsonObject {
                            put("id", "example.novel")
                            put("name", "範例小說 ✓")
                            put("version", "1.2.0")
                            put("versionCode", 12)
                            put("script", "plugins/example.novel.js")
                            put("sha256", "0".repeat(64))
                            put("size", 4_096)
                        })
                    }
                }),
            EnvelopeCase("plugin-sidecar", "publisher-a", a, REPOSITORY, RepositorySignedDocumentType.SIDECAR, "sidecars/example.novel.json", 3,
                buildJsonObject {
                    put("format", "shinsou-extension-sidecar-v2")
                    put("contractVersion", 2)
                    put("packageId", "example.novel")
                    put("version", "1.2.0")
                    put("versionCode", 12)
                    putJsonArray("contentKinds") { add(JsonPrimitive("PLAIN_TEXT")) }
                    put("escapes", "quote\" backslash\\ newline\n tab\t control\u0001 emoji 😀")
                }),
            EnvelopeCase("publisher-revocations", "publisher-a", a, REPOSITORY, RepositorySignedDocumentType.REVOCATIONS,
                PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID, 4,
                buildJsonObject {
                    put("publisherId", fa)
                    putJsonArray("revokedArtifacts") { add(JsonPrimitive("a".repeat(64))) }
                    put("expiresAt", 1_800_000_000L)
                }),
            EnvelopeCase("publisher-key-rotation", "publisher-a", a, REPOSITORY, RepositorySignedDocumentType.PUBLISHER_KEY_ROTATION,
                PublisherTrustStore.ROTATION_DOCUMENT_ID, 5,
                buildJsonObject {
                    put("publisherId", fa)
                    put("newPublicKey", SodiumSyncPrimitives.base64UrlEncode(b.publicKey))
                    put("newKeyFingerprint", fb)
                    put("effectiveSequence", 2)
                    put("newKeySignature", SodiumSyncPrimitives.base64UrlEncode(newKeySignature))
                }),
            EnvelopeCase("publisher-key-revoked", "publisher-a", a, REPOSITORY, RepositorySignedDocumentType.KEY_REVOKED,
                PublisherTrustStore.KEY_REVOKED_DOCUMENT_ID, 1,
                buildJsonObject {
                    put("publisherId", fa)
                    put("revokedKeyFingerprint", fa)
                }),
        )
        val verifier = RepositorySignedDocumentVerifier(RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN, RepositorySecurityStateStore.Disabled)
        val valid = buildJsonArray {
            cases.forEach { case ->
                val message = RepositorySignedDocumentVerifier.signingMessage(
                    case.repository, case.type, case.documentId, case.sequence, PublisherFingerprint.of(case.key.publicKey), case.payload,
                )
                val envelope = signedPublisherEnvelope(case.key, case.repository, case.type, case.documentId, case.sequence, case.payload)
                val verified = verifier.verifyEnvelope(
                    case.repository, case.type, case.documentId, envelope, publisherRoot(case.key), case.repository,
                )
                assertEquals(case.payload, verified.payload)
                add(
                    buildJsonObject {
                        put("name", case.name)
                        put("signer", case.signer)
                        put("repository", case.repository)
                        put("payloadType", case.type.wireValue)
                        put("documentId", case.documentId)
                        put("sequence", case.sequence)
                        put("signingMessageBase64", SodiumSyncPrimitives.base64UrlEncode(message))
                        put("signingMessageSha256Hex", SodiumSyncPrimitives.sha256(message).toHex())
                        put("payloadCanonicalSha256Hex", SodiumSyncPrimitives.sha256(canonicalSyncJson(case.payload).encodeToByteArray()).toHex())
                        put("envelope", envelope)
                    },
                )
            }
        }
        // The two mirror signatures of the same payload differ: a signature is bound to its URL.
        assertTrue(valid[0].jsonObject["envelope"] != valid[1].jsonObject["envelope"])

        val meta = cases.first()
        val good = signedPublisherEnvelope(a, meta.repository, meta.type, meta.documentId, meta.sequence, meta.payload)
        val invalidCases = listOf(
            InvalidCase("tampered-payload", "InvalidSignature", REPOSITORY, "publisher-a",
                JsonObject(good + ("payload" to buildJsonObject {
                    putJsonObject("meta") {
                        put("name", "Tampered Publisher")
                        put("shortName", "Example")
                        put("website", "https://plugins.example.test")
                        put("signingKeyFingerprint", fa)
                    }
                }))),
            InvalidCase("other-repository", "InvalidSignature", MIRROR, "publisher-a", good),
            InvalidCase("pinned-to-another-key", "KeySubstitution", REPOSITORY, "publisher-b", good),
            InvalidCase("unknown-field", "Malformed", REPOSITORY, "publisher-a", JsonObject(good + ("extra" to JsonPrimitive(true)))),
            InvalidCase("padded-public-key", "Malformed", REPOSITORY, "publisher-a",
                JsonObject(good + ("publicKey" to JsonPrimitive(good.getValue("publicKey").jsonPrimitive.content + "=")))),
            InvalidCase("wrong-payload-type", "Malformed", REPOSITORY, "publisher-a",
                JsonObject(good + ("payloadType" to JsonPrimitive("repository-index")))),
            InvalidCase("negative-sequence", "Malformed", REPOSITORY, "publisher-a",
                JsonObject(good + ("sequence" to JsonPrimitive(-1)))),
            InvalidCase("meta-fingerprint-mismatch", "Malformed", REPOSITORY, "publisher-a",
                signedPublisherEnvelope(a, REPOSITORY, RepositorySignedDocumentType.REPOSITORY, "repo.json", 2, buildJsonObject {
                    putJsonObject("meta") {
                        put("name", "Example Publisher")
                        put("signingKeyFingerprint", fb)
                    }
                })),
        )
        val invalid = buildJsonArray {
            invalidCases.forEach { case ->
                val pinned = publisherRoot(if (case.pinnedKey == "publisher-a") a else b)
                val error = assertFailsWith<RepositoryTrustException>(case.name) {
                    verifier.verifyEnvelope(case.repository, RepositorySignedDocumentType.REPOSITORY, "repo.json", case.envelope, pinned, case.repository)
                }
                assertEquals(case.expectedError, error::class.simpleName, case.name)
                add(
                    buildJsonObject {
                        put("name", case.name)
                        put("repository", case.repository)
                        put("payloadType", RepositorySignedDocumentType.REPOSITORY.wireValue)
                        put("documentId", "repo.json")
                        put("pinnedKey", case.pinnedKey)
                        put("expectedError", case.expectedError)
                        put("envelope", case.envelope)
                    },
                )
            }
        }
        check("envelopes.json", buildJsonObject {
            put("format", RepositorySignedDocumentVerifier.ENVELOPE_FORMAT)
            put("signatureDomain", "dev.shinsou.repository.signature.v1\\u0000")
            put("rotationDomain", "dev.shinsou.publisher.rotation.v1\\u0000")
            putJsonObject("rotationStatement") {
                put("publisherId", fa)
                put("oldKeyFingerprint", fa)
                put("newKey", "publisher-b")
                put("effectiveSequence", 2)
                put("messageBase64", SodiumSyncPrimitives.base64UrlEncode(rotationStatement))
            }
            put("valid", valid)
            put("invalid", invalid)
        })
    }

    @Test
    fun sandboxFramesMatchTheFixture() = runTest {
        SodiumSyncPrimitives.initialize()
        val frames = buildJsonArray {
            FRAMES.forEach { case ->
                val body = SandboxFrameCodec.encodeBody(case.frame)
                val envelopes = SandboxFrameCodec.encode(case.frame)
                assertEquals(1, envelopes.size, case.name)
                assertEquals(case.frame, SandboxFrameCodec.decodeBody(body, case.reader), case.name)
                add(
                    buildJsonObject {
                        put("name", case.name)
                        put("direction", if (case.reader == SandboxPeer.SANDBOX) "host-to-sandbox" else "sandbox-to-host")
                        put("frame", case.description)
                        put("bodyHex", body.toHex())
                        put("envelopeHex", envelopes.single().toHex())
                    },
                )
            }
        }
        // A frame from the wrong direction is a protocol violation, not data.
        val ready = SandboxFrameCodec.encodeBody(SandboxFrame.Ready(1, 1))
        assertFailsWith<Throwable> { SandboxFrameCodec.decodeBody(ready, SandboxPeer.SANDBOX) }
        check("sandbox-frames.json", buildJsonObject {
            put("protocolVersion", 1)
            put("maxFrameBytes", 1 shl 20)
            put("chunkPayloadBytes", SandboxFrameCodec.CHUNK_PAYLOAD_BYTES)
            put("frames", frames)
        })
    }

    private fun check(name: String, value: JsonElement) {
        val text = pretty.encodeToString(JsonElement.serializer(), value) + "\n"
        val file = File(directory, name)
        if (writeVectors) {
            directory.mkdirs()
            file.writeText(text)
            return
        }
        if (!file.isFile) fail("Missing ${file.path}; regenerate with -PwritePluginContractVectors=true")
        assertEquals(file.readText(), text, "$name differs from the app's contract; regenerate and review the diff")
    }

    private data class EnvelopeCase(
        val name: String,
        val signer: String,
        val key: SodiumKeyPair,
        val repository: String,
        val type: RepositorySignedDocumentType,
        val documentId: String,
        val sequence: Long,
        val payload: JsonElement,
    )

    private data class InvalidCase(
        val name: String,
        val expectedError: String,
        val repository: String,
        val pinnedKey: String,
        val envelope: JsonObject,
    )

    private data class FrameCase(
        val name: String,
        val reader: SandboxPeer,
        val frame: SandboxFrame,
        val description: JsonObject,
    )

    private companion object {
        const val REPOSITORY = "https://plugins.example.test/repo"
        const val MIRROR = "https://mirror.example.test"
        val TEST_KEYS = listOf(
            "publisher-a" to "shinsou plugin contract v1 test publisher A",
            "publisher-b" to "shinsou plugin contract v1 test publisher B",
        )

        val CANONICAL_VALID = listOf(
            "sorted-keys" to """{"b":1,"a":2,"aa":3,"A":4}""",
            "nested" to """{"z":[3,{"y":null,"x":true}],"a":{"d":false,"c":[]}}""",
            "integers" to """[0,-1,42,9007199254740991,-9007199254740991]""",
            "string-escapes" to "{\"s\":\"quote\\\" backslash\\\\ slash/ nl\\n cr\\r tab\\t bs\\b ff\\f ctl\\u0001 us\\u001f del\\u007f\"}",
            "unicode-text" to "{\"s\":\"ü 中文 😀 \\u2028 \\u00e9\"}",
            "utf16-key-order" to "{\"z\":1,\"é\":2,\"中\":3,\"😀\":4,\"\\uffff\":5}",
            "empty" to """{"o":{},"a":[],"s":""}""",
        )

        val CANONICAL_INVALID = listOf(
            "fraction" to """{"n":1.5}""",
            "exponent" to """{"n":1e3}""",
            "trailing-zero-fraction" to """{"n":1.0}""",
            "negative-zero" to """{"n":-0}""",
            "beyond-safe-integer" to """{"n":9007199254740992}""",
        )

        private val LIMITS = SandboxLimits(64L shl 20, 1L shl 20, 10_000, 30_000, 4_096, 6, 64, 4L shl 20, 65_536, 256)

        val FRAMES: List<FrameCase> = listOf(
            FrameCase("init", SandboxPeer.SANDBOX,
                SandboxFrame.Init(1, 1, 1, LIMITS, "globalThis.source = { name: \"範例\" };"),
                buildJsonObject {
                    put("type", "INIT"); put("callId", 1); put("protocolVersion", 1); put("apiLevel", 1)
                    putJsonArray("limits") { LIMITS.toList().forEach { add(JsonPrimitive(it)) } }
                    put("script", "globalThis.source = { name: \"範例\" };")
                }),
            FrameCase("invoke", SandboxPeer.SANDBOX, SandboxFrame.Invoke(2, "getPopularManga", "[1]"),
                buildJsonObject { put("type", "INVOKE"); put("callId", 2); put("method", "getPopularManga"); put("argsJson", "[1]") }),
            FrameCase("cancel", SandboxPeer.SANDBOX, SandboxFrame.Cancel(2),
                buildJsonObject { put("type", "CANCEL"); put("callId", 2) }),
            FrameCase("bridge-result-value", SandboxPeer.SANDBOX, SandboxFrame.BridgeResult(7, BridgeOutcome.Value("{\"status\":200}")),
                buildJsonObject { put("type", "BRIDGE_RESULT"); put("seq", 7); put("ok", true); put("json", "{\"status\":200}") }),
            FrameCase("bridge-result-null", SandboxPeer.SANDBOX, SandboxFrame.BridgeResult(8, BridgeOutcome.Value(null)),
                buildJsonObject { put("type", "BRIDGE_RESULT"); put("seq", 8); put("ok", true); put("json", JsonNull) }),
            FrameCase("bridge-result-error", SandboxPeer.SANDBOX, SandboxFrame.BridgeResult(9, BridgeOutcome.Error(SandboxErrorCode.PERMISSION_DENIED)),
                buildJsonObject { put("type", "BRIDGE_RESULT"); put("seq", 9); put("ok", false); put("code", SandboxErrorCode.PERMISSION_DENIED.code) }),
            FrameCase("async-bridge-result", SandboxPeer.SANDBOX, SandboxFrame.AsyncBridgeResult(3, BridgeOutcome.Value("\"ok\"")),
                buildJsonObject { put("type", "ASYNC_BRIDGE_RESULT"); put("opId", 3); put("ok", true); put("json", "\"ok\"") }),
            FrameCase("async-cancel", SandboxPeer.SANDBOX, SandboxFrame.AsyncCancel(3),
                buildJsonObject { put("type", "ASYNC_CANCEL"); put("opId", 3) }),
            FrameCase("ready", SandboxPeer.HOST, SandboxFrame.Ready(1, 1),
                buildJsonObject { put("type", "READY"); put("callId", 1); put("protocolVersion", 1) }),
            FrameCase("bridge-call", SandboxPeer.HOST, SandboxFrame.BridgeCall(2, 7, "net.request", "{\"url\":\"https://example.test/\"}"),
                buildJsonObject { put("type", "BRIDGE_CALL"); put("callId", 2); put("seq", 7); put("op", "net.request"); put("argsJson", "{\"url\":\"https://example.test/\"}") }),
            FrameCase("async-bridge-call", SandboxPeer.HOST, SandboxFrame.AsyncBridgeCall(2, 3, "net.fetch", "{\"url\":\"https://example.test/a\"}"),
                buildJsonObject { put("type", "ASYNC_BRIDGE_CALL"); put("callId", 2); put("opId", 3); put("op", "net.fetch"); put("argsJson", "{\"url\":\"https://example.test/a\"}") }),
            FrameCase("result", SandboxPeer.HOST, SandboxFrame.Result(2, "{\"title\":\"第一章 😀\"}"),
                buildJsonObject { put("type", "RESULT"); put("callId", 2); put("valueJson", "{\"title\":\"第一章 😀\"}") }),
            FrameCase("failure", SandboxPeer.HOST, SandboxFrame.Failure(2, SandboxErrorCode.LIMIT_TIME),
                buildJsonObject { put("type", "FAILURE"); put("callId", 2); put("code", SandboxErrorCode.LIMIT_TIME.code); put("sourceFailure", 0) }),
            FrameCase("failure-source-marker", SandboxPeer.HOST, SandboxFrame.Failure(2, SandboxErrorCode.SCRIPT_ERROR, SandboxSourceFailure.HTTP_CHALLENGE),
                buildJsonObject { put("type", "FAILURE"); put("callId", 2); put("code", SandboxErrorCode.SCRIPT_ERROR.code); put("sourceFailure", SandboxSourceFailure.HTTP_CHALLENGE.code) }),
            FrameCase("log", SandboxPeer.HOST, SandboxFrame.Log("debug: 頁面 3".encodeToByteArray()),
                buildJsonObject { put("type", "LOG"); put("bytesHex", "debug: 頁面 3".encodeToByteArray().toHex()) }),
            FrameCase("limit-exceeded", SandboxPeer.HOST, SandboxFrame.LimitExceeded(2, SandboxLimitKind.DOM_NODES),
                buildJsonObject { put("type", "LIMIT_EXCEEDED"); put("callId", 2); put("kind", SandboxLimitKind.DOM_NODES.code) }),
        )

        fun seedFor(label: String): ByteArray = SodiumSyncPrimitives.sha256(label.encodeToByteArray())

        fun keyPair(seed: ByteArray): SodiumKeyPair {
            val pair = Signature.seedKeypair(seed.asUByteArray())
            return SodiumKeyPair(pair.publicKey.asByteArray().copyOf(), pair.secretKey.asByteArray().copyOf())
        }

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
