package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.sync.crypto.SodiumKeyPair
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0002 S5 acceptance (§12): publisher trust, both modes, rotation, revocation, expiry and the block list. */
class PublisherTrustTest {
    private val keys = mutableListOf<SodiumKeyPair>()
    private val clients = mutableListOf<HttpClient>()

    @BeforeTest
    fun initializeCrypto() = runTest { SodiumSyncPrimitives.initialize() }

    @AfterTest
    fun cleanUp() {
        keys.forEach { SodiumSyncPrimitives.destroy(it.privateKey) }
        clients.forEach(HttpClient::close)
    }

    private inner class Host(
        val kv: PluginKeyValueStore = InMemoryPluginKeyValueStore(),
        officialPublisherId: String? = null,
        val state: RepositorySecurityStateStore = KeyValueRepositorySecurityStateStore(kv),
    ) {
        var now = 1_000_000L
        var developerMode = false
        val publishers = PublisherTrustStore(kv, state)
        val revocations = PublisherRevocationStore(kv, publishers, state)
        val blocklist = LocalPluginBlocklist(kv)
        val resolver = ExecutionTierResolver(
            publishers, revocations, blocklist, officialPublisherId, developerMode = { developerMode }, nowEpochSeconds = { now },
        )
        val policy = PublisherRepositoryTrustPolicy(publishers, blocklist, RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN)
    }

    @Test
    fun firstUseTrustsTheKeyAndAMirrorIsTheSamePublisher() = runTest {
        val host = Host()
        val key = newKey()
        val first = host.publishers.trustRepository(REPO, root(key))
        assertEquals(PublisherVerification.TOFU, first.verification)
        assertEquals(root(key).fingerprint, first.publisherId)
        val mirror = host.publishers.trustRepository(MIRROR, root(key))
        assertEquals(first.publisherId, mirror.publisherId)
        assertEquals(1, host.publishers.publishers().size)
        // The same URL presenting another key is a substitution, not a new first use.
        assertFailsWith<RepositoryTrustException.KeySubstitution> { host.publishers.trustRepository(REPO, root(newKey())) }
        val decision = assertIs<RepositoryTrustDecision.RequirePinnedSignature>(host.policy.decisionFor(MIRROR))
        assertEquals(root(key), decision.root)
        assertEquals("publisher:${first.publisherId}", decision.stateScope)
    }

    @Test
    fun modeBNeverTrustsWithoutTheFullFingerprint() = runTest {
        val host = Host()
        val key = newKey()
        host.publishers.setGlobalMode(PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH)
        assertFailsWith<PublisherTrustException.FingerprintRequired> { host.publishers.trustRepository(REPO, root(key)) }
        assertFailsWith<PublisherTrustException.FingerprintMismatch> {
            host.publishers.trustRepository(REPO, root(key), expectedFingerprint = root(newKey()).fingerprint)
        }
        // The 128-bit display form is not enough to match.
        assertFailsWith<PublisherTrustException.FingerprintMismatch> {
            host.publishers.trustRepository(REPO, root(key), expectedFingerprint = PublisherFingerprint.display(root(key).fingerprint))
        }
        assertNull(host.publishers.publisherForRepository(REPO))
        val trusted = host.publishers.trustRepository(REPO, root(key), expectedFingerprint = hex(key))
        assertEquals(PublisherVerification.FINGERPRINT_MATCHED, trusted.verification)
        assertEquals(PublisherTrustStatus.TRUSTED, trusted.status(host.publishers.globalMode()))
    }

    @Test
    fun aRepositoryCanBeStricterThanTheGlobalMode() = runTest {
        val host = Host()
        val key = newKey()
        assertFailsWith<PublisherTrustException.FingerprintRequired> {
            host.publishers.trustRepository(REPO, root(key), requireFingerprintMatch = true)
        }
        val tofu = host.publishers.trustRepository(REPO, root(key))
        val strict = host.publishers.requireFingerprintMatch(tofu.publisherId)
        assertEquals(PublisherTrustStatus.PENDING_FINGERPRINT_MATCH, strict.status(PublisherTrustMode.TOFU_WITH_OPTIONAL_MATCH))
    }

    @Test
    fun switchingToModeBLeavesTofuPublishersPendingUntilMatched() = runTest {
        val host = Host()
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        val installed = candidate(publisher)
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(installed))
        host.publishers.setGlobalMode(PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH)
        assertEquals(PublisherTrustStatus.PENDING_FINGERPRINT_MATCH, host.publishers.publisher(publisher)!!.status(host.publishers.globalMode()))
        // Installed packages keep running, with a warning; nothing new installs.
        assertEquals(ExecutionTier.Sandboxed(warnings = setOf(ExecutionTierWarning.FINGERPRINT_NOT_MATCHED)), host.resolver.resolve(installed))
        assertFailsWith<PublisherTrustException.NotInstallable> { host.resolver.requireInstallable("pkg", publisher, ARTIFACT) }
        assertFailsWith<PublisherTrustException.FingerprintMismatch> { host.publishers.confirmFingerprint(publisher, root(newKey()).fingerprint) }
        host.publishers.confirmFingerprint(publisher, root(key).fingerprint)
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(installed))
        host.revocations.applyRevocations(REPO, revocations(key, publisher, emptyList(), expiresAt = host.now + DAY, sequence = 1))
        host.resolver.requireInstallable("pkg", publisher, ARTIFACT)
    }

    @Test
    fun nothingInstallsWithoutACurrentRevocationList() = runTest {
        val host = Host()
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        // A repository withholding the list must not keep installs open forever (freeze attack).
        assertFailsWith<PublisherTrustException.NotInstallable> { host.resolver.requireInstallable("pkg", publisher, ARTIFACT) }
        host.revocations.applyRevocations(REPO, revocations(key, publisher, emptyList(), expiresAt = host.now + DAY, sequence = 1))
        host.resolver.requireInstallable("pkg", publisher, ARTIFACT)
        // Installed packages never depended on it.
        assertEquals(ExecutionTier.Sandboxed(), Host().also { it.publishers.trustRepository(REPO, root(key)) }.resolver.resolve(candidate(publisher)))
    }

    @Test
    fun rotationNeedsBothSignaturesAndKeepsThePublisher() = runTest {
        val host = Host()
        val old = newKey()
        val next = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(old)).publisherId

        // Signed by the old key, but the new key never agreed: rejected, nothing changes.
        val unagreed = rotation(old, next, signedByNew = newKey(), publisher = publisher, effective = 1, sequence = 1)
        assertFailsWith<RepositoryTrustException.InvalidSignature> { host.publishers.applyRotation(REPO, unagreed) }
        // Proposed by someone else's key.
        val foreign = newKey()
        assertFailsWith<RepositoryTrustException.KeySubstitution> {
            host.publishers.applyRotation(REPO, rotation(foreign, next, signedByNew = next, publisher = publisher, effective = 1, sequence = 1))
        }
        assertEquals(root(old).fingerprint, host.publishers.publisher(publisher)!!.currentFingerprint)

        val rotated = host.publishers.applyRotation(REPO, rotation(old, next, signedByNew = next, publisher = publisher, effective = 1, sequence = 2))
        assertEquals(publisher, rotated.publisherId)
        assertEquals(root(next).fingerprint, rotated.currentFingerprint)
        assertEquals(listOf(root(old).fingerprint), rotated.retiredFingerprints)
        assertEquals(root(next), assertIs<RepositoryTrustDecision.RequirePinnedSignature>(host.policy.decisionFor(REPO)).root)
        // Replaying the same rotation does not apply twice; the retired key cannot be trusted again.
        assertFailsWith<RepositoryTrustException> {
            host.publishers.applyRotation(REPO, rotation(old, next, signedByNew = next, publisher = publisher, effective = 1, sequence = 2))
        }
        assertFailsWith<RepositoryTrustException.KeySubstitution> { host.publishers.trustRepository(MIRROR, root(old)) }
    }

    @Test
    fun aMirrorCannotReplayWhatAnotherMirrorSuperseded() = runTest {
        val host = Host()
        val key = newKey()
        host.publishers.trustRepository(REPO, root(key))
        host.publishers.trustRepository(MIRROR, root(key))
        val bodies = mutableMapOf(
            REPO to envelope(key, REPO, RepositorySignedDocumentType.INDEX, "index.json", 5, index()),
            MIRROR to envelope(key, MIRROR, RepositorySignedDocumentType.INDEX, "index.json", 4, index()),
        )
        val client = client(host, bodies)
        assertIs<RepositoryIndex.Plugins>(client.fetchIndex(REPO))
        assertFailsWith<ExtensionRepositoryException.InvalidDocument> { client.fetchIndex(MIRROR) }
            .also { assertIs<RepositoryTrustException.Replay>(it.cause) }
        bodies[MIRROR] = envelope(key, MIRROR, RepositorySignedDocumentType.INDEX, "index.json", 6, index())
        assertIs<RepositoryIndex.Plugins>(client.fetchIndex(MIRROR))
    }

    @Test
    fun withdrawnArtifactsGoInertAndExpiryOnlyWarns() = runTest {
        val host = Host()
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        host.revocations.applyRevocations(REPO, revocations(key, publisher, listOf(ARTIFACT), expiresAt = host.now + 30 * DAY, sequence = 1))
        assertEquals(ExecutionTier.Inert(InertReason.ARTIFACT_REVOKED), host.resolver.resolve(candidate(publisher)))
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(candidate(publisher, artifact = OTHER_ARTIFACT)))
        assertFailsWith<PublisherTrustException.NotInstallable> { host.resolver.requireInstallable("pkg", publisher, ARTIFACT) }

        // An older revocation list cannot be replayed to un-withdraw the artifact.
        assertFailsWith<RepositoryTrustException.Replay> {
            host.revocations.applyRevocations(REPO, revocations(key, publisher, emptyList(), expiresAt = host.now + 60 * DAY, sequence = 0))
        }

        // Past expiresAt: installed packages keep running with a warning, however long ago (D8).
        host.now += 365 * DAY
        assertEquals(
            ExecutionTier.Sandboxed(warnings = setOf(ExecutionTierWarning.PUBLISHER_DOCUMENTS_EXPIRED)),
            host.resolver.resolve(candidate(publisher, artifact = OTHER_ARTIFACT)),
        )
        assertFailsWith<PublisherTrustException.NotInstallable> { host.resolver.requireInstallable("pkg", publisher, OTHER_ARTIFACT) }
    }

    @Test
    fun aRevokedKeyMakesItsPublisherInert() = runTest {
        val host = Host()
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        val stranger = newKey()
        assertFailsWith<RepositoryTrustException.KeySubstitution> {
            host.publishers.applyKeyRevocation(REPO, keyRevoked(stranger, publisher))
        }
        host.publishers.applyKeyRevocation(REPO, keyRevoked(key, publisher))
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REVOKED), host.resolver.resolve(candidate(publisher)))
        assertFailsWith<PublisherTrustException.KeyRevoked> { host.policy.decisionFor(REPO) }
        assertFailsWith<PublisherTrustException.KeyRevoked> { host.publishers.trustRepository(MIRROR, root(key)) }
    }

    @Test
    fun aRetiredKeyRevokingItselfDoesNotStopThePublisher() = runTest {
        val host = Host()
        val old = newKey()
        val next = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(old)).publisherId
        host.publishers.applyRotation(REPO, rotation(old, next, signedByNew = next, publisher = publisher, effective = 1, sequence = 1))
        val record = host.publishers.applyKeyRevocation(REPO, keyRevoked(old, publisher))
        assertEquals(listOf(root(old).fingerprint), record.revokedFingerprints)
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(candidate(publisher)))
    }

    @Test
    fun replacingALostKeyNeedsTheOldPublishersPackagesAuthorizedAgain() = runTest {
        val host = Host()
        val lost = newKey()
        val replacement = newKey()
        val old = host.publishers.trustRepository(REPO, root(lost)).publisherId
        val new = host.publishers.replaceAfterExplicitApproval(REPO, root(replacement))
        assertEquals(PublisherVerification.TOFU, new.verification)
        assertEquals(new.publisherId, host.publishers.publisherForRepository(REPO)!!.publisherId)
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REPLACED), host.resolver.resolve(candidate(old)))
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(candidate(new.publisherId)))
    }

    @Test
    fun blockingIsImmediateFirstAndSurvivesARestart() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val host = Host(kv)
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        val official = ExecutionTierCandidate("official", OTHER_ARTIFACT, null, officialReviewedCatalogMatch = true, fromCanonicalOfficialUnsignedRepository = true)
        assertEquals(ExecutionTier.ReviewedInProcess, host.resolver.resolve(official))

        val entries = listOf(
            PluginBlockEntry.Package("pkg", publisher),
            PluginBlockEntry.Artifact(ARTIFACT),
            PluginBlockEntry.Publisher(publisher),
        )
        for (entry in entries) {
            host.blocklist.block(entry)
            val restarted = Host(kv) // a fresh process over the same device storage
            assertEquals(ExecutionTier.Inert(InertReason.BLOCKED_BY_USER), restarted.resolver.resolve(candidate(publisher)))
            assertFailsWith<PublisherTrustException.Blocked> { restarted.resolver.requireInstallable("pkg", publisher, ARTIFACT) }
            restarted.blocklist.unblock(entry)
            assertEquals(ExecutionTier.Sandboxed(), Host(kv).resolver.resolve(candidate(publisher)))
        }
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        assertFailsWith<PublisherTrustException.Blocked> { host.policy.decisionFor(REPO) }
        // Official packages can be blocked too.
        host.blocklist.block(PluginBlockEntry.Artifact(OTHER_ARTIFACT))
        assertEquals(ExecutionTier.Inert(InertReason.BLOCKED_BY_USER), host.resolver.resolve(official))
    }

    @Test
    fun theTierComesFromLocalStateOnly() = runTest {
        val host = Host()
        val publisher = host.publishers.trustRepository(REPO, root(newKey())).publisherId
        assertEquals(ExecutionTier.Inert(InertReason.UNSIGNED), host.resolver.resolve(ExecutionTierCandidate("dev", ARTIFACT, null, developerLocalSource = true)))
        host.developerMode = true
        assertEquals(ExecutionTier.Sandboxed(development = true), host.resolver.resolve(ExecutionTierCandidate("dev", ARTIFACT, null, developerLocalSource = true)))
        assertEquals(ExecutionTier.Inert(InertReason.UNSIGNED), host.resolver.resolve(ExecutionTierCandidate("x", ARTIFACT, null)))
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_NOT_TRUSTED), host.resolver.resolve(candidate(root(newKey()).fingerprint)))
        assertEquals(ExecutionTier.Inert(InertReason.NOT_AUTHORIZED), host.resolver.resolve(candidate(publisher).copy(userAuthorized = false)))
        // A catalogue match from anyone but the official publisher is not reviewed.
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(candidate(publisher).copy(officialReviewedCatalogMatch = true)))
    }

    @Test
    fun fingerprintsAndAddRepositoryLinks() = runTest {
        val key = newKey()
        val fingerprint = root(key).fingerprint
        assertEquals(fingerprint, PublisherFingerprint.parse(hex(key)))
        assertEquals(fingerprint, PublisherFingerprint.parse(hex(key).uppercase().chunked(4).joinToString(":")))
        assertEquals(fingerprint, PublisherFingerprint.parse(fingerprint))
        assertNull(PublisherFingerprint.parse(PublisherFingerprint.display(fingerprint)))
        assertEquals(8, PublisherFingerprint.display(fingerprint).split(' ').size)

        val link = AddRepositoryLink.parse("shinsou://add-repository?url=https%3A%2F%2Frepo.example%2Fx&fp=${hex(key)}")
        assertEquals(AddRepositoryLink("https://repo.example/x", fingerprint), link)
        assertEquals(AddRepositoryLink("https://repo.example/x", null), AddRepositoryLink.parse("shinsou://add-repository?url=https://repo.example/x"))
        assertNull(AddRepositoryLink.parse("shinsou://add-repository?url=http://repo.example/x"))
        assertNull(AddRepositoryLink.parse("shinsou://add-repository?url=https://repo.example/x&fp=nonsense"))
        assertNull(AddRepositoryLink.parse("https://add-repository?url=https://repo.example/x"))
    }

    @Test
    fun replacingAKeyKeepsThePublishersStrictMode() = runTest {
        val host = Host()
        val lost = newKey()
        val replacement = newKey()
        val old = host.publishers.trustRepository(REPO, root(lost)).publisherId
        host.publishers.requireFingerprintMatch(old)
        assertFailsWith<PublisherTrustException.FingerprintRequired> { host.publishers.replaceAfterExplicitApproval(REPO, root(replacement)) }
        assertEquals(old, host.publishers.publisherForRepository(REPO)!!.publisherId)
        val new = host.publishers.replaceAfterExplicitApproval(REPO, root(replacement), expectedFingerprint = hex(replacement))
        assertEquals(true, new.requireFingerprintMatch)
        assertEquals(PublisherVerification.FINGERPRINT_MATCHED, new.verification)
    }

    @Test
    fun anInterruptedReplacementFailsClosedAndCanBeCompleted() = runTest {
        val kv = FailingKeyValueStore()
        val host = Host(kv)
        val lost = newKey()
        val replacement = newKey()
        val old = host.publishers.trustRepository(REPO, root(lost)).publisherId
        kv.failWritesStartingWith = "plugin.publisher.trust.v1.url."
        assertFailsWith<IllegalStateException> { host.publishers.replaceAfterExplicitApproval(REPO, root(replacement)) }
        // The old publisher is already retired, so nothing of it runs.
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REPLACED), host.resolver.resolve(candidate(old)))
        kv.failWritesStartingWith = null
        val new = host.publishers.replaceAfterExplicitApproval(REPO, root(replacement))
        assertEquals(new.publisherId, host.publishers.publisherForRepository(REPO)!!.publisherId)
        // Resuming is limited to the replacement the old publisher names.
        assertFailsWith<RepositoryTrustException.KeySubstitution> { host.publishers.replaceAfterExplicitApproval(MIRROR, root(replacement)) }
    }

    @Test
    fun aRetiredKeyCannotBlockTheCurrentKeysRevocation() = runTest {
        val host = Host()
        val old = newKey()
        val next = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(old)).publisherId
        host.publishers.applyRotation(REPO, rotation(old, next, signedByNew = next, publisher = publisher, effective = 1, sequence = 1))
        host.publishers.applyKeyRevocation(REPO, keyRevoked(old, publisher, sequence = (1L shl 53) - 1))
        host.publishers.applyKeyRevocation(REPO, keyRevoked(next, publisher, sequence = 1))
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REVOKED), host.resolver.resolve(candidate(publisher)))
    }

    @Test
    fun aWithdrawalIsEnforcedEvenIfItsWatermarkWasNotRecorded() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val state = FailingAdmissionStateStore(KeyValueRepositorySecurityStateStore(kv))
        val host = Host(kv, state = state)
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        state.failAdmission = true
        assertFailsWith<IllegalStateException> {
            host.revocations.applyRevocations(REPO, revocations(key, publisher, listOf(ARTIFACT), expiresAt = host.now + DAY, sequence = 3))
        }
        assertEquals(ExecutionTier.Inert(InertReason.ARTIFACT_REVOKED), host.resolver.resolve(candidate(publisher)))
        state.failAdmission = false
        // The stored list itself refuses to go back, whatever the watermark says.
        assertFailsWith<RepositoryTrustException.Replay> {
            host.revocations.applyRevocations(REPO, revocations(key, publisher, emptyList(), expiresAt = host.now + DAY, sequence = 2))
        }
        host.revocations.applyRevocations(REPO, revocations(key, publisher, listOf(ARTIFACT), expiresAt = host.now + DAY, sequence = 3))
    }

    @Test
    fun theOfficialTierStillHonoursRevocationAndWithdrawal() = runTest {
        val official = newKey()
        val host = Host(officialPublisherId = root(official).fingerprint)
        val publisher = host.publishers.trustBuiltIn(REPO, root(official)).publisherId
        val catalogued = ExecutionTierCandidate("official", ARTIFACT, publisher, officialReviewedCatalogMatch = true)
        assertEquals(ExecutionTier.ReviewedInProcess, host.resolver.resolve(catalogued))
        // A signed package cannot claim the unsigned official exception.
        val other = host.publishers.trustRepository(MIRROR, root(newKey())).publisherId
        assertEquals(
            ExecutionTier.Inert(InertReason.NOT_AUTHORIZED),
            host.resolver.resolve(ExecutionTierCandidate("x", ARTIFACT, other, officialReviewedCatalogMatch = true, fromCanonicalOfficialUnsignedRepository = true)),
        )
        host.revocations.applyRevocations(REPO, revocations(official, publisher, listOf(ARTIFACT), expiresAt = host.now + DAY, sequence = 1))
        assertEquals(ExecutionTier.Inert(InertReason.ARTIFACT_REVOKED), host.resolver.resolve(catalogued))
        host.publishers.applyKeyRevocation(REPO, keyRevoked(official, publisher))
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REVOKED), host.resolver.resolve(catalogued.copy(artifactSha256 = OTHER_ARTIFACT)))
    }

    @Test
    fun aRepositoryIsInspectedBeforeAnythingIsTrusted() = runTest {
        val host = Host()
        val key = newKey()
        val repoJson = buildJsonObject {
            put("meta", buildJsonObject {
                put("name", "Example Plugins")
                put("signingKeyFingerprint", root(key).fingerprint)
            })
        }
        val client = client(host, mapOf(REPO to envelope(key, REPO, RepositorySignedDocumentType.REPOSITORY, "repo.json", 1, repoJson)))
        // Untrusted: the normal path refuses before any request; inspection shows the key instead.
        assertFailsWith<RepositoryTrustException.UntrustedRepository> { client.fetchRepository(REPO) }
        val presented = client.inspectRepositoryKey(REPO)
        assertEquals(REPO, presented.normalizedBaseUrl)
        assertEquals("Example Plugins", presented.name)
        assertEquals(root(key), presented.key)
        assertNull(host.publishers.publisherForRepository(REPO), "inspection trusts nothing")

        host.publishers.trustRepository(REPO, presented.key)
        assertEquals("Example Plugins", client.fetchRepository(REPO).name)

        // A repo.json whose signature does not verify under the key it presents is rejected.
        val forged = PluginJson.parseToJsonElement(envelope(key, REPO, RepositorySignedDocumentType.REPOSITORY, "repo.json", 1, repoJson)) as JsonObject
        val tampered = JsonObject(forged + ("publicKey" to JsonPrimitive(root(newKey()).publicKeyBase64Url)))
        assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
            client(host, mapOf(MIRROR to tampered.toString())).inspectRepositoryKey(MIRROR)
        }
    }

    @Test
    fun publisherDocumentsAreSyncedFromTheRepository() = runTest {
        val host = Host()
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        val withdrawn = revocations(key, publisher, listOf(ARTIFACT), expiresAt = host.now + DAY, sequence = 1)
        val documents = mutableMapOf<String, String>(REPO + "/" + PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID to withdrawn.toString())
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        clients += http
        val client = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = host.policy,
            repositorySecurityState = host.state,
            repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved transport only")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                    val body = documents[request.url.substringBefore('?')] ?: return PluginHttpResponse(404, ByteArray(0))
                    return PluginHttpResponse(200, body.encodeToByteArray())
                }
            },
        )
        val security = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, client)
        assertEquals(ExecutionTier.Sandboxed(), host.resolver.resolve(candidate(publisher)))
        assertTrue(security.syncPublisherDocuments(REPO))
        assertEquals(ExecutionTier.Inert(InertReason.ARTIFACT_REVOKED), host.resolver.resolve(candidate(publisher)))
        // Nothing new: no change reported.
        assertEquals(false, security.syncPublisherDocuments(REPO))
        // The key revokes itself: the publisher goes inert, and later syncs cannot undo it.
        documents[REPO + "/" + PublisherTrustStore.KEY_REVOKED_DOCUMENT_ID] = keyRevoked(key, publisher).toString()
        assertTrue(security.syncPublisherDocuments(REPO))
        assertEquals(ExecutionTier.Inert(InertReason.PUBLISHER_KEY_REVOKED), host.resolver.resolve(candidate(publisher, OTHER_ARTIFACT)))
        assertEquals(false, security.syncPublisherDocuments(REPO))
    }

    @Test
    fun aStoredWithdrawalIsEnforcedEvenWhenTheSyncIsCancelled() = runTest {
        val host = Host()
        val key = newKey()
        val publisher = host.publishers.trustRepository(REPO, root(key)).publisherId
        val withdrawn = revocations(key, publisher, listOf(ARTIFACT), expiresAt = host.now + DAY, sequence = 1).toString()
        val stall = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        clients += http
        val client = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = host.policy,
            repositorySecurityState = host.state,
            repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved transport only")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse = when {
                    PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID in request.url -> PluginHttpResponse(200, withdrawn.encodeToByteArray())
                    PublisherTrustStore.KEY_REVOKED_DOCUMENT_ID in request.url -> { stall.await(); PluginHttpResponse(404, ByteArray(0)) }
                    else -> PluginHttpResponse(404, ByteArray(0))
                }
            },
        )
        val security = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, client)
        var reevaluations = 0
        val sync = launch { security.syncPublisherDocuments(REPO) { reevaluations++ } }
        testScheduler.advanceUntilIdle()
        sync.cancel() // the refresh timed out on the last document
        sync.join()
        assertEquals(1, reevaluations, "the stored withdrawal was enforced at once")
        assertEquals(ExecutionTier.Inert(InertReason.ARTIFACT_REVOKED), host.resolver.resolve(candidate(publisher)))
    }

    // ---------------------------------------------------------------- helpers

    private fun newKey(): SodiumKeyPair = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }

    private fun root(key: SodiumKeyPair) = RepositoryTrustRoot(
        SodiumSyncPrimitives.base64UrlEncode(key.publicKey),
        PublisherFingerprint.of(key.publicKey),
    )

    private fun hex(key: SodiumKeyPair): String =
        SodiumSyncPrimitives.sha256(key.publicKey).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun candidate(publisher: String, artifact: String = ARTIFACT) =
        ExecutionTierCandidate("pkg", artifact, publisher, userAuthorized = true)

    private suspend fun envelopeObject(
        key: SodiumKeyPair,
        repository: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        payload: JsonElement,
    ): JsonObject {
        val root = root(key)
        val signature = SodiumSyncPrimitives.signEd25519(
            RepositorySignedDocumentVerifier.signingMessage(repository, type, documentId, sequence, root.fingerprint, payload),
            key.privateKey,
        )
        return buildJsonObject {
            put("format", RepositorySignedDocumentVerifier.ENVELOPE_FORMAT)
            put("algorithm", "Ed25519")
            put("keyFingerprint", root.fingerprint)
            put("publicKey", root.publicKeyBase64Url)
            put("payloadType", type.wireValue)
            put("sequence", sequence)
            put("payload", payload)
            put("signature", SodiumSyncPrimitives.base64UrlEncode(signature))
        }
    }

    private suspend fun envelope(
        key: SodiumKeyPair,
        repository: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        payload: JsonElement,
    ): String = envelopeObject(key, repository, type, documentId, sequence, payload).toString()

    private suspend fun rotation(
        signedByOld: SodiumKeyPair,
        next: SodiumKeyPair,
        signedByNew: SodiumKeyPair,
        publisher: String,
        effective: Long,
        sequence: Long,
    ): JsonObject {
        val statement = PublisherTrustStore.rotationStatement(publisher, root(signedByOld).fingerprint, root(next), effective)
        val agreement = SodiumSyncPrimitives.base64UrlEncode(SodiumSyncPrimitives.signEd25519(statement, signedByNew.privateKey))
        val payload = buildJsonObject {
            put("publisherId", publisher)
            put("newPublicKey", root(next).publicKeyBase64Url)
            put("newKeyFingerprint", root(next).fingerprint)
            put("effectiveSequence", effective)
            put("newKeySignature", agreement)
        }
        return envelopeObject(signedByOld, REPO, RepositorySignedDocumentType.PUBLISHER_KEY_ROTATION, PublisherTrustStore.ROTATION_DOCUMENT_ID, sequence, payload)
    }

    private suspend fun revocations(key: SodiumKeyPair, publisher: String, artifacts: List<String>, expiresAt: Long, sequence: Long): JsonObject {
        val payload = buildJsonObject {
            put("publisherId", publisher)
            put("revokedArtifacts", JsonArray(artifacts.map(::JsonPrimitive)))
            put("expiresAt", expiresAt)
        }
        return envelopeObject(key, REPO, RepositorySignedDocumentType.REVOCATIONS, PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID, sequence, payload)
    }

    private suspend fun keyRevoked(key: SodiumKeyPair, publisher: String, sequence: Long = 1): JsonObject {
        val payload = buildJsonObject {
            put("publisherId", publisher)
            put("revokedKeyFingerprint", root(key).fingerprint)
        }
        return envelopeObject(key, REPO, RepositorySignedDocumentType.KEY_REVOKED, PublisherTrustStore.KEY_REVOKED_DOCUMENT_ID, sequence, payload)
    }

    private fun index(): JsonElement = PluginJson.parseToJsonElement(
        """[{"id":"pkg","name":"Package","version":"1.0.0","versionCode":3,"lang":"all","nsfw":0,"scriptUrl":"pkg.js","sha256":"$ARTIFACT","byteSize":1}]""",
    )

    private fun client(host: Host, bodies: Map<String, String>): ExtensionRepositoryClient {
        fun bodyFor(url: String) = bodies.entries.first { url.startsWith(it.key + "/") }.value
        val http = HttpClient(MockEngine { request -> respond(bodyFor(request.url.toString()), HttpStatusCode.OK) })
        clients += http
        return ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = host.policy,
            repositorySecurityState = host.state,
            repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("pinned repository must use resolved transport")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse =
                    PluginHttpResponse(200, bodyFor(request.url).encodeToByteArray())
            },
        )
    }

    private companion object {
        const val REPO = "https://repo.example/extensions"
        const val MIRROR = "https://mirror.example/extensions"
        val ARTIFACT = "a".repeat(64)
        val OTHER_ARTIFACT = "b".repeat(64)
        const val DAY = 86_400L
    }
}

private class FailingKeyValueStore(private val delegate: PluginKeyValueStore = InMemoryPluginKeyValueStore()) : PluginKeyValueStore {
    var failWritesStartingWith: String? = null
    override suspend fun getString(key: String): String? = delegate.getString(key)
    override suspend fun putString(key: String, value: String) {
        failWritesStartingWith?.let { check(!key.startsWith(it)) { "simulated write failure" } }
        delegate.putString(key, value)
    }
    override suspend fun remove(key: String) = delegate.remove(key)
}

private class FailingAdmissionStateStore(private val delegate: RepositorySecurityStateStore) : RepositorySecurityStateStore by delegate {
    var failAdmission = false
    override val isDurable: Boolean get() = delegate.isDurable
    override suspend fun admitDocument(
        normalizedBaseUrl: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        sequence: Long,
        authenticatedPayloadSha256: String,
    ) {
        check(!failAdmission) { "simulated watermark failure" }
        delegate.admitDocument(normalizedBaseUrl, type, documentId, sequence, authenticatedPayloadSha256)
    }
}
