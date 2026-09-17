package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.sync.crypto.SodiumKeyPair
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RepositoryTrustTest {
    private val clients = mutableListOf<HttpClient>()

    @BeforeTest
    fun initializeCrypto() = runTest { SodiumSyncPrimitives.initialize() }

    @AfterTest
    fun closeClients() {
        clients.forEach(HttpClient::close)
        clients.clear()
    }

    @Test
    fun strictDefaultRejectsUnsignedAndExplicitDeveloperPolicyAcceptsIt() = runTest {
        val unsigned = unsignedIndex()
        val strict = clientFor(unsigned)
        assertFailsWith<RepositoryTrustException.UntrustedRepository> {
            strict.fetchIndex(REPOSITORY)
        }

        val compatibility = clientFor(
            unsigned,
            policy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        assertEquals("pkg", assertIs<RepositoryIndex.Plugins>(compatibility.fetchIndex(REPOSITORY)).entries.single().id)
    }

    @Test
    fun officialUnsignedCompatibilityAcceptsOnlyTheCanonicalNormalizedBaseUrl() = runTest {
        val policy = officialCompatibilityPolicy()

        assertIs<RepositoryTrustDecision.UnsignedDeveloperCompatibility>(
            policy.decisionFor(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL),
        )

        val rejected = listOf(
            "http://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com:443/aluo96078/shinsou_plugin/refs/heads/master",
            "https://RAW.GITHUBUSERCONTENT.COM/aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com./aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com.evil.example/aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/main",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master/extra",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/%6daster",
            OFFICIAL_SHINSOU_REPOSITORY_INDEX_URL,
            "$OFFICIAL_SHINSOU_REPOSITORY_INDEX_URL?mirror=evil",
            "$OFFICIAL_SHINSOU_REPOSITORY_INDEX_URL#evil",
            "$OFFICIAL_SHINSOU_REPOSITORY_BASE_URL/",
            "$OFFICIAL_SHINSOU_REPOSITORY_BASE_URL?mirror=evil",
            "$OFFICIAL_SHINSOU_REPOSITORY_BASE_URL#evil",
        )
        rejected.forEach { candidate ->
            assertFailsWith<RepositoryTrustException.UntrustedRepository>(candidate) {
                policy.decisionFor(candidate)
            }
        }
    }

    @Test
    fun officialUnsignedCompatibilityUsesClientNormalizationWithoutBroadeningTrust() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond(unsignedIndex(), HttpStatusCode.OK)
        })
        clients += http
        val client = ExtensionRepositoryClient(
            client = http,
            cacheToken = { 1L },
            repositoryTrustPolicy = officialCompatibilityPolicy(),
        )

        val exact = client.fetchIndex("  $OFFICIAL_SHINSOU_REPOSITORY_BASE_URL///  ")
        assertEquals("pkg", assertIs<RepositoryIndex.Plugins>(exact).entries.single().id)
        assertEquals(1, requests)

        val untrustedVariants = listOf(
            "https://raw.githubusercontent.com.evil.example/aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com:443/aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/main",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master/extra",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/%6daster",
            OFFICIAL_SHINSOU_REPOSITORY_INDEX_URL,
        )
        untrustedVariants.forEach { candidate ->
            assertFailsWith<RepositoryTrustException.UntrustedRepository>(candidate) {
                client.fetchIndex(candidate)
            }
        }

        val structurallyInvalidVariants = listOf(
            "http://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master",
            "https://raw.githubusercontent.com@evil.example/aluo96078/shinsou_plugin/refs/heads/master",
            "$OFFICIAL_SHINSOU_REPOSITORY_BASE_URL?mirror=evil",
            "$OFFICIAL_SHINSOU_REPOSITORY_BASE_URL#evil",
        )
        structurallyInvalidVariants.forEach { candidate ->
            assertFailsWith<ExtensionRepositoryException.InvalidUrl>(candidate) {
                client.fetchIndex(candidate)
            }
        }
        val invalidEncodedPathVariants = listOf(
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master%2fextra",
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master/%2e%2e/evil",
        )
        invalidEncodedPathVariants.forEach { candidate ->
            assertFailsWith<IllegalArgumentException>(candidate) {
                client.fetchIndex(candidate)
            }
        }
        assertEquals(1, requests, "Rejected variants must fail before transport")
    }

    @Test
    fun configuredPinTakesPrecedenceOverOfficialUnsignedCompatibility() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val roots = KeyValueRepositoryTrustRootStore(InMemoryPluginKeyValueStore())
            roots.put(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, root(keys))
            val policy = ConfiguredRepositoryTrustPolicy(
                roots = roots,
                allowUnsignedDeveloperCompatibility = ::isCanonicalOfficialUnsignedRepository,
            )

            assertEquals(
                RepositoryTrustDecision.RequirePinnedSignature(root(keys)),
                policy.decisionFor(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL),
            )
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun unpinnedRepositoryIsRejectedBeforeAnyHttpTransport() = runTest {
        var ktorRequests = 0
        var pinnedTransportRequests = 0
        val http = HttpClient(MockEngine {
            ktorRequests++
            respond(unsignedIndex(), HttpStatusCode.OK)
        })
        clients += http
        val client = ExtensionRepositoryClient(
            http,
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
                    error("ordinary transport must not be called")
                }

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    pinnedTransportRequests++
                    return PluginHttpResponse(200, unsignedIndex().encodeToByteArray())
                }
            },
            repositoryHostResolver = PluginHostResolver { listOf("203.0.113.10") },
        )

        assertFailsWith<RepositoryTrustException.UntrustedRepository> { client.fetchIndex(REPOSITORY) }
        assertEquals(0, ktorRequests)
        assertEquals(0, pinnedTransportRequests)
    }

    @Test
    fun pinnedRepositoryWithoutDurableSecurityStateFailsBeforeTransport() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            var transportRequests = 0
            val payload = PluginJson.parseToJsonElement(unsignedIndex())
            val signed = signedEnvelope(keys, payload, sequence = 1)
            val http = HttpClient(MockEngine {
                error("ordinary transport must not be called")
            })
            clients += http
            val client = ExtensionRepositoryClient(
                http,
                repositoryTrustPolicy = policy(keys),
                repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
                repositoryTransport = object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                        error("unpinned execution must not be used")

                    override suspend fun executeResolved(
                        request: PluginHttpRequest,
                        resolution: PluginHostResolution,
                    ): PluginHttpResponse {
                        transportRequests++
                        return PluginHttpResponse(200, signed.encodeToByteArray())
                    }
                },
            )

            assertFailsWith<RepositoryTrustException.DurableSecurityStateRequired> {
                client.fetchIndex(REPOSITORY)
            }
            assertEquals(0, transportRequests)
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun explicitlyDisabledStateCannotBeCombinedWithPinnedTrust() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val client = clientFor(
                body = signedEnvelope(keys, PluginJson.parseToJsonElement(unsignedIndex()), sequence = 1),
                policy = policy(keys),
                state = RepositorySecurityStateStore.Disabled,
            )

            assertFailsWith<RepositoryTrustException.DurableSecurityStateRequired> {
                client.fetchIndex(REPOSITORY)
            }
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun unsignedDeveloperCompatibilityCanExplicitlyDisableReplayState() = runTest {
        val compatibility = clientFor(
            unsignedIndex(),
            policy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            state = RepositorySecurityStateStore.Disabled,
        )

        assertEquals(
            "pkg",
            assertIs<RepositoryIndex.Plugins>(compatibility.fetchIndex(REPOSITORY)).entries.single().id,
        )
    }

    @Test
    fun pinnedRepositoryValidatesEveryHopAndRejectsPrivateDnsBeforeTransport() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val signed = signedEnvelope(keys, PluginJson.parseToJsonElement(unsignedIndex()), 1)
            val answers = ArrayDeque(listOf(listOf("93.184.216.34"), listOf("127.0.0.1")))
            var transportCalls = 0
            val transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("unpinned execution must not be used")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    transportCalls++
                    assertEquals(listOf("93.184.216.34"), resolution.addresses)
                    return PluginHttpResponse(
                        status = 302,
                        body = ByteArray(0),
                        headers = mapOf("Location" to listOf("/extensions/next-index.json")),
                    )
                }
            }
            val client = clientFor(
                signed,
                policy(keys),
                repositoryResolver = PluginHostResolver { answers.removeFirst() },
                repositoryTransport = transport,
            )

            assertFailsWith<ExtensionRepositoryException.NetworkSecurity> { client.fetchIndex(REPOSITORY) }
            assertEquals(1, transportCalls)
            assertTrue(answers.isEmpty())
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun pinnedRepositoryPropagatesItsDocumentBudgetToEveryTransportHop() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val payload = PluginJson.parseToJsonElement(unsignedIndex())
            val signed = signedEnvelope(keys, payload, sequence = 1)
            val seenLimits = mutableListOf<Int>()
            var calls = 0
            val transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("pinned repository must use resolved transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    seenLimits += request.maxResponseBytes
                    calls++
                    return if (calls == 1) {
                        PluginHttpResponse(
                            302,
                            ByteArray(0),
                            mapOf("Location" to listOf("/extensions/next-index.json")),
                        )
                    } else {
                        PluginHttpResponse(200, signed.encodeToByteArray())
                    }
                }
            }
            val client = clientFor(
                signed,
                policy(keys),
                repositoryTransport = transport,
            )

            assertIs<RepositoryIndex.Plugins>(client.fetchIndex(REPOSITORY))
            assertEquals(listOf(2 * 1_024 * 1_024, 2 * 1_024 * 1_024), seenLimits)
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun validPinnedSignatureAdmitsCanonicalPayload() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val payload = PluginJson.parseToJsonElement(unsignedIndex())
            val client = clientFor(
                signedEnvelope(keys, payload, sequence = 7),
                policy = policy(keys),
                state = KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore()),
            )
            val result = assertIs<RepositoryIndex.Plugins>(client.fetchIndex(REPOSITORY))
            assertEquals("pkg", result.entries.single().id)
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun signedExecutableCannotFallBackToTofuArtifactMetadata() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val missingDigest = PluginJson.parseToJsonElement(
                unsignedIndex().replace(",\"sha256\":\"${"a".repeat(64)}\"", ""),
            )
            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(signedEnvelope(keys, missingDigest, 1), policy(keys)).fetchIndex(REPOSITORY)
            }

            val missingSize = PluginJson.parseToJsonElement(
                unsignedIndex().replace(",\"byteSize\":1", ""),
            )
            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(signedEnvelope(keys, missingSize, 2), policy(keys)).fetchIndex(REPOSITORY)
            }
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun strictArtifactAdmissionRequiresEntryFromCurrentAuthenticatedIndex() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val payload = PluginJson.parseToJsonElement(unsignedIndex())
            val state = KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore())
            val client = clientFor(signedEnvelope(keys, payload, 4), policy(keys), state)
            val snapshot = client.fetchIndexSnapshot(REPOSITORY)
            val entry = assertIs<RepositoryIndex.Plugins>(snapshot.index).entries.single()
            client.requireArtifactNotDowngraded(REPOSITORY, entry, snapshot.admission)
            assertFailsWith<RepositoryTrustException.UnauthenticatedArtifact> {
                client.requireArtifactNotDowngraded(
                    REPOSITORY,
                    entry.copy(versionCode = 99),
                    snapshot.admission,
                )
            }
            assertFailsWith<RepositoryTrustException.UnauthenticatedArtifact> {
                client.requireArtifactNotDowngraded(
                    REPOSITORY,
                    entry.copy(description = "authenticated row was modified after display"),
                    snapshot.admission,
                )
            }

            val freshClient = clientFor(signedEnvelope(keys, payload, 4), policy(keys), state)
            assertFailsWith<RepositoryTrustException.UnauthenticatedArtifact> {
                freshClient.requireArtifactNotDowngraded(REPOSITORY, entry, snapshot.admission)
            }
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun artifactCommitRejectsSelectionInvalidatedByIndexRefresh() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        val state = KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore())
        try {
            val oldEntry = PluginJson.decodeFromString<List<PluginIndexEntry>>(
                unsignedIndex(versionCode = 3),
            ).single()
            val newEntry = oldEntry.copy(versionCode = 4, sha256 = "b".repeat(64))
            val bodies = ArrayDeque(
                listOf(
                    signedEnvelope(keys, PluginJson.encodeToJsonElement(listOf(oldEntry)), sequence = 1),
                    signedEnvelope(keys, PluginJson.encodeToJsonElement(listOf(newEntry)), sequence = 2),
                ),
            )
            val http = HttpClient(MockEngine { error("Pinned transport must be used") })
            clients += http
            val client = ExtensionRepositoryClient(
                http,
                cacheToken = { 1L },
                repositoryTrustPolicy = policy(keys),
                repositorySecurityState = state,
                repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
                repositoryTransport = object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                        error("Pinned repository must use resolved transport")

                    override suspend fun executeResolved(
                        request: PluginHttpRequest,
                        resolution: PluginHostResolution,
                    ): PluginHttpResponse = PluginHttpResponse(
                        status = 200,
                        body = bodies.removeFirst().encodeToByteArray(),
                    )
                },
            )

            val selected = client.fetchIndexSnapshot(REPOSITORY)
            client.requireArtifactNotDowngraded(REPOSITORY, oldEntry, selected.admission)
            val refreshed = client.fetchIndexSnapshot(REPOSITORY)

            assertFailsWith<RepositoryTrustException.UnauthenticatedArtifact> {
                client.recordCommittedArtifact(
                    REPOSITORY,
                    oldEntry,
                    requireNotNull(oldEntry.sha256),
                    selected.admission,
                )
            }
            // Rejection happens before the durable watermark advances.
            state.requireArtifactNotDowngraded(
                REPOSITORY,
                oldEntry.id,
                oldEntry.versionCode,
                requireNotNull(oldEntry.sha256),
            )

            client.recordCommittedArtifact(
                REPOSITORY,
                newEntry,
                requireNotNull(newEntry.sha256),
                refreshed.admission,
            )
            assertFailsWith<RepositoryTrustException.Downgrade> {
                state.requireArtifactNotDowngraded(
                    REPOSITORY,
                    oldEntry.id,
                    oldEntry.versionCode,
                    requireNotNull(oldEntry.sha256),
                )
            }
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun payloadTamperingAndKeySubstitutionFailClosed() = runTest {
        val trusted = SodiumSyncPrimitives.generateEd25519KeyPair()
        val attacker = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val payload = PluginJson.parseToJsonElement(unsignedIndex())
            val signed = signedEnvelope(trusted, payload, sequence = 3)
            val tampered = PluginJson.parseToJsonElement(signed).let { root ->
                val envelope = root as JsonObject
                JsonObject(envelope + ("payload" to PluginJson.parseToJsonElement(unsignedIndex(versionCode = 4))))
            }.toString()
            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(tampered, policy = policy(trusted)).fetchIndex(REPOSITORY)
            }.also { assertIs<RepositoryTrustException.InvalidSignature>(it.cause) }

            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(
                    signedEnvelope(attacker, payload, sequence = 3),
                    policy = policy(trusted),
                ).fetchIndex(REPOSITORY)
            }.also { assertIs<RepositoryTrustException.KeySubstitution>(it.cause) }
        } finally {
            SodiumSyncPrimitives.destroy(trusted.privateKey)
            SodiumSyncPrimitives.destroy(attacker.privateKey)
        }
    }

    @Test
    fun signatureCannotBeMovedAcrossRepositoryOrDocumentPath() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val payload = PluginJson.parseToJsonElement(unsignedIndex())
            val signedForOriginal = signedEnvelope(keys, payload, sequence = 1)
            val otherRepository = "https://mirror.example/extensions"
            val otherPolicy = PinnedRepositoryTrustPolicy(mapOf(otherRepository to root(keys)))
            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(signedForOriginal, policy = otherPolicy, repository = otherRepository)
                    .fetchIndex(otherRepository)
            }.also { assertIs<RepositoryTrustException.InvalidSignature>(it.cause) }
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun persistedSequenceRejectsReplayAndSameSequenceEquivocation() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        val state = KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore())
        try {
            val first = clientFor(
                signedEnvelope(keys, PluginJson.parseToJsonElement(unsignedIndex(versionCode = 3)), sequence = 10),
                policy(keys),
                state,
            )
            first.fetchIndex(REPOSITORY)

            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(
                    signedEnvelope(keys, PluginJson.parseToJsonElement(unsignedIndex(versionCode = 2)), sequence = 9),
                    policy(keys),
                    state,
                ).fetchIndex(REPOSITORY)
            }.also { assertIs<RepositoryTrustException.Replay>(it.cause) }

            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(
                    signedEnvelope(keys, PluginJson.parseToJsonElement(unsignedIndex(versionCode = 4)), sequence = 10),
                    policy(keys),
                    state,
                ).fetchIndex(REPOSITORY)
            }.also { assertIs<RepositoryTrustException.Equivocation>(it.cause) }
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun invalidSignedPayloadDoesNotBurnSequence() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        val state = KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore())
        try {
            val malformedPayload = buildJsonObject { put("unexpected", true) }
            assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                clientFor(signedEnvelope(keys, malformedPayload, 20), policy(keys), state).fetchIndex(REPOSITORY)
            }
            val valid = clientFor(
                signedEnvelope(keys, PluginJson.parseToJsonElement(unsignedIndex()), 19),
                policy(keys),
                state,
            )
            assertEquals("pkg", assertIs<RepositoryIndex.Plugins>(valid.fetchIndex(REPOSITORY)).entries.single().id)
        } finally {
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun artifactWatermarkPersistsAndOnlyAdvancesAfterExplicitCommit() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val state = KeyValueRepositorySecurityStateStore(keyValues)
        val v5 = "a".repeat(64)
        val v4 = "b".repeat(64)
        state.requireArtifactNotDowngraded(REPOSITORY, "pkg", 5, v5)
        // A failed/cancelled install has not committed a watermark.
        state.requireArtifactNotDowngraded(REPOSITORY, "pkg", 4, v4)

        state.admitArtifact(REPOSITORY, "pkg", 5, v5)
        val afterRestart = KeyValueRepositorySecurityStateStore(keyValues)
        assertFailsWith<RepositoryTrustException.Downgrade> {
            afterRestart.requireArtifactNotDowngraded(REPOSITORY, "pkg", 4, v4)
        }
        assertFailsWith<RepositoryTrustException.Equivocation> {
            afterRestart.requireArtifactNotDowngraded(REPOSITORY, "pkg", 5, v4)
        }
        afterRestart.requireArtifactNotDowngraded(REPOSITORY, "pkg", 6, "c".repeat(64))
    }

    @Test
    fun newerAuthenticatedIndexClearsStaleArtifactCapabilityBeforeCancellableCommit() = runTest {
        val keys = SodiumSyncPrimitives.generateEd25519KeyPair()
        val backing = KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore())
        val state = BlockingDocumentAdmissionStateStore(backing, blockedSequence = 2)
        val oldEntry = PluginJson.decodeFromString<List<PluginIndexEntry>>(
            unsignedIndex(versionCode = 3),
        ).single()
        val newEntry = oldEntry.copy(versionCode = 4, sha256 = "b".repeat(64))
        val oldPayload = PluginJson.parseToJsonElement(
            PluginJson.encodeToString(listOf(oldEntry)),
        )
        val newPayload = PluginJson.parseToJsonElement(
            PluginJson.encodeToString(listOf(newEntry)),
        )
        val bodies = ArrayDeque(
            listOf(
                signedEnvelope(keys, oldPayload, sequence = 1),
                signedEnvelope(keys, newPayload, sequence = 2),
            ),
        )
        val http = HttpClient(MockEngine {
            respond(bodies.removeFirst(), HttpStatusCode.OK)
        })
        clients += http
        val client = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = policy(keys),
            repositorySecurityState = state,
            repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("pinned repository must use resolved transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse = PluginHttpResponse(
                    200,
                    bodies.removeFirst().encodeToByteArray(),
                )
            },
        )

        try {
            val first = assertIs<RepositoryIndex.Plugins>(client.fetchIndex(REPOSITORY)).entries.single()
            client.requireArtifactNotDowngraded(REPOSITORY, first)

            val refreshing = launch { client.fetchIndex(REPOSITORY) }
            state.entered.await()

            // commitAuthenticatedIndex removes the old process-local snapshot before entering
            // the durable watermark write. A stale row must therefore lose authorization even
            // while the newer signed commit is waiting on storage.
            assertFailsWith<RepositoryTrustException.UnauthenticatedArtifact> {
                client.requireArtifactNotDowngraded(REPOSITORY, oldEntry)
            }

            refreshing.cancel()
            state.release.complete(Unit)
            refreshing.cancelAndJoin()

            assertTrue(refreshing.isCancelled)
            client.requireArtifactNotDowngraded(REPOSITORY, newEntry)
            assertFailsWith<RepositoryTrustException.UnauthenticatedArtifact> {
                client.requireArtifactNotDowngraded(REPOSITORY, oldEntry)
            }
        } finally {
            state.release.complete(Unit)
            SodiumSyncPrimitives.destroy(keys.privateKey)
        }
    }

    @Test
    fun trustRootReplacementRequiresExplicitApprovalApi() = runTest {
        val store = KeyValueRepositoryTrustRootStore(InMemoryPluginKeyValueStore())
        val first = SodiumSyncPrimitives.generateEd25519KeyPair()
        val second = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            store.put(REPOSITORY, root(first))
            assertFailsWith<RepositoryTrustException.KeySubstitution> { store.put(REPOSITORY, root(second)) }
            store.replaceAfterExplicitApproval(REPOSITORY, root(second))
            assertEquals(root(second), store.get(REPOSITORY))
        } finally {
            SodiumSyncPrimitives.destroy(first.privateKey)
            SodiumSyncPrimitives.destroy(second.privateKey)
        }
    }

    private fun clientFor(
        body: String,
        policy: RepositoryTrustPolicy = RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN,
        state: RepositorySecurityStateStore = when (policy) {
            RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY -> RepositorySecurityStateStore.Disabled
            else -> KeyValueRepositorySecurityStateStore(InMemoryPluginKeyValueStore())
        },
        repository: String = REPOSITORY,
        repositoryResolver: PluginHostResolver = PluginHostResolver { listOf("93.184.216.34") },
        repositoryTransport: PluginHttpTransport? = null,
    ): ExtensionRepositoryClient {
        val http = HttpClient(MockEngine { request ->
            require(request.url.toString().startsWith(repository))
            respond(body, HttpStatusCode.OK)
        })
        clients += http
        val resolvedTransport = repositoryTransport ?: object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                error("pinned repository must use resolved transport")

            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse = PluginHttpResponse(200, body.encodeToByteArray())
        }
        return ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = policy,
            repositorySecurityState = state,
            repositoryHostResolver = repositoryResolver,
            repositoryTransport = resolvedTransport,
        )
    }

    private fun policy(keys: SodiumKeyPair): RepositoryTrustPolicy =
        PinnedRepositoryTrustPolicy(mapOf(REPOSITORY to root(keys)))

    private fun officialCompatibilityPolicy(): RepositoryTrustPolicy = ConfiguredRepositoryTrustPolicy(
        roots = KeyValueRepositoryTrustRootStore(InMemoryPluginKeyValueStore()),
        allowUnsignedDeveloperCompatibility = ::isCanonicalOfficialUnsignedRepository,
    )

    private fun root(keys: SodiumKeyPair): RepositoryTrustRoot {
        val public = SodiumSyncPrimitives.base64UrlEncode(keys.publicKey)
        val fingerprint = "sha256:${SodiumSyncPrimitives.base64UrlEncode(SodiumSyncPrimitives.sha256(keys.publicKey))}"
        return RepositoryTrustRoot(public, fingerprint)
    }

    private suspend fun signedEnvelope(
        keys: SodiumKeyPair,
        payload: JsonElement,
        sequence: Long,
    ): String {
        val root = root(keys)
        val signature = SodiumSyncPrimitives.signEd25519(
            RepositorySignedDocumentVerifier.signingMessage(
                REPOSITORY,
                RepositorySignedDocumentType.INDEX,
                "index.json",
                sequence,
                root.fingerprint,
                payload,
            ),
            keys.privateKey,
        )
        return buildJsonObject {
            put("format", RepositorySignedDocumentVerifier.ENVELOPE_FORMAT)
            put("algorithm", "Ed25519")
            put("keyFingerprint", root.fingerprint)
            put("publicKey", root.publicKeyBase64Url)
            put("payloadType", RepositorySignedDocumentType.INDEX.wireValue)
            put("sequence", sequence)
            put("payload", payload)
            put("signature", SodiumSyncPrimitives.base64UrlEncode(signature))
        }.toString()
    }

    private fun unsignedIndex(versionCode: Int = 3): String =
        """[{"id":"pkg","name":"Package","version":"1.0.0","versionCode":$versionCode,"lang":"all","nsfw":0,"scriptUrl":"pkg.js","sha256":"${"a".repeat(64)}","byteSize":1}]"""

    private companion object {
        const val REPOSITORY = "https://repo.example/extensions"
    }
}

private class BlockingDocumentAdmissionStateStore(
    private val delegate: RepositorySecurityStateStore,
    private val blockedSequence: Long,
) : RepositorySecurityStateStore {
    override val isDurable: Boolean get() = delegate.isDurable
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun requireDocumentNotReplayed(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    ) = delegate.requireDocumentNotReplayed(
        normalizedBaseUrl,
        type,
        documentId,
        sequence,
        authenticatedPayloadSha256,
    )

    override suspend fun admitDocument(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    ) {
        if (sequence == blockedSequence) {
            entered.complete(Unit)
            release.await()
        }
        delegate.admitDocument(
            normalizedBaseUrl,
            type,
            documentId,
            sequence,
            authenticatedPayloadSha256,
        )
    }

    override suspend fun requireArtifactNotDowngraded(
        normalizedBaseUrl: String,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    ) = delegate.requireArtifactNotDowngraded(normalizedBaseUrl, packageId, versionCode, artifactSha256)

    override suspend fun admitArtifact(
        normalizedBaseUrl: String,
        packageId: String,
        versionCode: Int,
        artifactSha256: String,
    ) = delegate.admitArtifact(normalizedBaseUrl, packageId, versionCode, artifactSha256)
}
