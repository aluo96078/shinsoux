package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.sync.crypto.SodiumKeyPair
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ReviewedLocalRepositoryTrustTest {
    @Test
    fun configuredLocalPinRequiresSignatureInsteadOfFallingBackToReviewedBytes() = runTest {
        SodiumSyncPrimitives.initialize()
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        val values = InMemoryPluginKeyValueStore()
        val root = trustRoot(keys)
        val roots = KeyValueRepositoryTrustRootStore(values).also {
            it.put(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL, root)
        }
        val policy = ConfiguredRepositoryTrustPolicy(
            roots = roots,
            allowUnsignedDeveloperCompatibility = { candidate ->
                ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081.admits(candidate)
            },
        )
        var genericRequests = 0
        var localRequests = 0
        val http = HttpClient(MockEngine {
            genericRequests += 1
            error("Reviewed local requests must not use generic HTTP")
        })
        try {
            val client = ExtensionRepositoryClient(
                client = http,
                repositoryTrustPolicy = policy,
                repositorySecurityState = KeyValueRepositorySecurityStateStore(values),
                reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
                reviewedLocalRepositoryTransport = PluginHttpTransport {
                    localRequests += 1
                    PluginHttpResponse(200, """{"meta":{"name":"Local"}}""".encodeToByteArray())
                },
            )

            val failure = assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                client.fetchRepository(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            }
            assertIs<RepositoryTrustException.SignatureRequired>(failure.cause)
            assertEquals(1, localRequests)
            assertEquals(0, genericRequests)
        } finally {
            http.close()
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun malformedTrustPolicyCannotFallBackAndFailsBeforeIo() = runTest {
        var genericRequests = 0
        var localRequests = 0
        val http = HttpClient(MockEngine {
            genericRequests += 1
            error("Malformed trust policy must fail before generic HTTP")
        })
        try {
            val client = ExtensionRepositoryClient(
                client = http,
                repositoryTrustPolicy = RepositoryTrustPolicy {
                    throw RepositoryTrustException.Malformed("Injected malformed trust policy")
                },
                reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
                reviewedLocalRepositoryTransport = PluginHttpTransport {
                    localRequests += 1
                    error("Malformed trust policy must fail before local HTTP")
                },
            )

            assertFailsWith<RepositoryTrustException.Malformed> {
                client.fetchRepository(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            }
            assertEquals(0, localRequests)
            assertEquals(0, genericRequests)
        } finally {
            http.close()
        }
    }

    @Test
    fun fixedLocalRepositoryRejectsLegacyAndAmbiguousV2Indexes() = runTest {
        var genericRequests = 0
        var localRequests = 0
        val ambiguousV2 =
            """{"format":"shinsou-extension-v2","contractVersion":2,"packages":[],"shinsou":[]}"""
        val http = HttpClient(MockEngine {
            genericRequests += 1
            error("Reviewed local requests must not use generic HTTP")
        })
        try {
            val client = ExtensionRepositoryClient(
                client = http,
                cacheToken = { 1L },
                reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
                reviewedLocalRepositoryTransport = PluginHttpTransport {
                    localRequests += 1
                    PluginHttpResponse(200, ambiguousV2.encodeToByteArray())
                },
            )

            assertFailsWith<ExtensionRepositoryException.InvalidUrl> {
                client.fetchLegacyIndex(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            }
            assertEquals(0, localRequests, "Legacy rejection must happen before transport")
            assertFailsWith<RepositoryTrustException.Malformed> {
                client.fetchPluginIndex(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            }
            assertEquals(1, localRequests)
            assertEquals(0, genericRequests)
        } finally {
            http.close()
        }
    }

    @Test
    fun localTransportCancellationIsPropagatedUnchanged() = runTest {
        var genericRequests = 0
        var localRequests = 0
        val http = HttpClient(MockEngine {
            genericRequests += 1
            error("Reviewed local requests must not use generic HTTP")
        })
        try {
            val client = ExtensionRepositoryClient(
                client = http,
                reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
                reviewedLocalRepositoryTransport = PluginHttpTransport {
                    localRequests += 1
                    throw CancellationException("Injected local cancellation")
                },
            )

            val failure = assertFailsWith<CancellationException> {
                client.fetchRepository(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            }
            assertEquals("Injected local cancellation", failure.message)
            assertEquals(1, localRequests)
            assertEquals(0, genericRequests)
        } finally {
            http.close()
        }
    }

    private fun trustRoot(keys: SodiumKeyPair): RepositoryTrustRoot {
        val publicKey = SodiumSyncPrimitives.base64UrlEncode(keys.publicKey)
        val fingerprint =
            "sha256:${SodiumSyncPrimitives.base64UrlEncode(SodiumSyncPrimitives.sha256(keys.publicKey))}"
        return RepositoryTrustRoot(publicKey, fingerprint)
    }
}
