package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0002 §10.1 inside [PluginManager]: the publisher-trust tier decides whether a package runs; it only ever runs in the sandbox. */
class PluginManagerExecutionTierTest {
    @BeforeTest
    fun initializeCrypto() = runTest { SodiumSyncPrimitives.initialize() }

    private class Engine : ScriptPluginRuntimeFactory {
        val created = mutableListOf<String>()
        val permissions = mutableListOf<Set<PluginRuntimePermission>>()
        override suspend fun create(script: String, manifest: PluginManifest, environment: ScriptPluginEnvironment): ScriptPluginRuntime {
            created += manifest.id
            permissions += environment.runtimePermissions
            return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
        }
    }

    private class Host(
        val keyValues: PluginKeyValueStore = InMemoryPluginKeyValueStore(),
        val packages: PluginPackageStore = InMemoryPluginPackageStore(),
        withSandbox: Boolean = true,
    ) {
        val sandbox = Engine()
        val state = KeyValueRepositorySecurityStateStore(keyValues)
        val publishers = PublisherTrustStore(keyValues, state)
        val revocations = PublisherRevocationStore(keyValues, publishers, state)
        val blocklist = LocalPluginBlocklist(keyValues)
        val storage = KeyValuePluginStorage(keyValues)
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond(SCRIPT) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packages,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(sandbox.takeIf { withSandbox }),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage = storage),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(keyValues, MutablePluginSystemEventAuthorizer()),
            executionTierGate = PublisherExecutionTierGate(
                publishers,
                ExecutionTierResolver(publishers, revocations, blocklist, nowEpochSeconds = { 1_000L }),
            ),
        )
    }

    private val keys = mutableListOf<dev.shinsou.kmp.sync.crypto.SodiumKeyPair>()

    /** The local install key of the single installed fixture (publisher-scoped when signed, D6). */
    private suspend fun Host.key(): String = manager.installedPlugins().singleOrNull()?.manifest?.id ?: PLUGIN_ID

    /** The numeric id the fixture's source runs under on this device. */
    private suspend fun Host.sourceId(): Long =
        manager.installedPlugins().singleOrNull()?.manifest?.sources?.singleOrNull()?.id ?: SOURCE_ID

    @kotlin.test.AfterTest
    fun destroyKeys() = keys.forEach { SodiumSyncPrimitives.destroy(it.privateKey) }

    /** Trusts a fresh publisher for [REPOSITORY], with the current (empty) revocation list installs require. */
    private suspend fun Host.trustPublisher(repository: ExtensionRepository = REPOSITORY): String {
        val key = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val publisher = publishers.trustRepository(repository.baseUrl, publisherRoot(key)).publisherId
        revocations.applyRevocations(repository.baseUrl, signedRevocations(key, repository.baseUrl, publisher, expiresAt = 10_000))
        return publisher
    }

    @Test
    fun aTrustedPublishersApprovedPackageRunsInTheSandbox() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertEquals(publisher, host.packages.get(host.key())!!.metadata.publisherId)
        assertTrue(host.sandbox.created.isEmpty(), "nothing runs before the user approves")
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        assertEquals(listOf(host.key()), host.sandbox.created)
        assertNotNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun onlyGrantedItemsReachTheRuntimeAndAnUpdateAskingNothingNewKeepsThem() = runTest {
        val host = Host()
        host.trustPublisher()
        val first = entry().copy(
            runtimePermissions = setOf(
                PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK,
                PluginRuntimePermission.COOKIE_STORAGE, PluginRuntimePermission.COOKIE_READ,
            ),
        )
        host.manager.install(REPOSITORY, first)
        val review = assertNotNull(host.manager.pendingEventGrantReview(host.key()))
        // The user turns COOKIE_READ off.
        val selection = dev.shinsou.kmp.plugin.events.PluginGrantSelection(
            hostPermissions = emptySet(),
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK, PluginRuntimePermission.COOKIE_STORAGE),
            contentHostSuffixes = emptySet(),
        )
        host.manager.approveEventGrantReview(host.key(), review, emptySet(), selection)
        assertEquals(selection.runtimePermissions, host.sandbox.permissions.last())

        // D4: same publisher, nothing new requested — it runs at once and the choice is kept.
        host.manager.install(REPOSITORY, first.copy(version = "1.1.0", versionCode = 2))
        assertNull(host.manager.pendingEventGrantReview(host.key()))
        assertEquals(selection, host.manager.grantedSelection(host.key()))
        assertEquals(selection.runtimePermissions, host.sandbox.permissions.last())
        assertNotNull(host.manager.source(host.sourceId()))

        // Settings withdraw an item: the runtime is rebuilt without it.
        val narrower = selection.copy(runtimePermissions = selection.runtimePermissions - PluginRuntimePermission.COOKIE_STORAGE)
        host.manager.narrowGrant(host.key(), narrower)
        assertEquals(narrower.runtimePermissions, host.sandbox.permissions.last())

        // An update asking for something new waits for a fresh review and does not run.
        host.manager.install(
            REPOSITORY,
            first.copy(version = "1.2.0", versionCode = 3, runtimePermissions = first.runtimePermissions!! + PluginRuntimePermission.CREDENTIAL_ACCESS),
        )
        assertNotNull(host.manager.pendingEventGrantReview(host.key()))
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun aDeclinedCdnFamilyIsUnreachableFromTheReaderAndDownloadClientsToo() = runTest {
        val host = Host()
        host.trustPublisher()
        val withCdn = entry().copy(
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
            sources = listOf(
                SourceIndexEntry(
                    name = "Tier fixture", lang = "all", id = SOURCE_ID, baseUrl = "https://source.example",
                    contentHostSuffixes = setOf("cdn.example.net"), originPolicyVersion = 2,
                ),
            ),
        )
        host.manager.install(REPOSITORY, withCdn)
        val review = assertNotNull(host.manager.pendingEventGrantReview(host.key()))
        assertEquals(setOf("cdn.example.net"), review.requestedContentHostSuffixes)
        // Approved without the CDN family.
        host.manager.approveEventGrantReview(
            host.key(), review, emptySet(),
            dev.shinsou.kmp.plugin.events.PluginGrantSelection(emptySet(), review.requestedRuntimePermissions, emptySet()),
        )
        val content = assertNotNull(host.manager.contentNetworkForSource(host.sourceId()))
        val refused = assertFailsWith<IllegalArgumentException> {
            content.execute(host.sourceId(), PluginHttpRequest("GET", "https://img1.cdn.example.net/a.jpg", ByteArray(0), emptyMap()))
        }
        assertTrue(refused.message.orEmpty().contains("not declared"), refused.message)
    }

    @Test
    fun theSamePackageIdFromTwoPublishersInstallsSideBySide() = runTest {
        val host = Host()
        val first = host.trustPublisher(REPOSITORY)
        val second = host.trustPublisher(OTHER_REPOSITORY)
        host.manager.install(REPOSITORY, entry())
        host.manager.install(OTHER_REPOSITORY, entry())
        val installed = host.manager.installedPlugins()
        assertEquals(2, installed.size)
        val byPublisher = installed.associateBy { it.metadata.publisherId }
        val a = assertNotNull(byPublisher[first])
        val b = assertNotNull(byPublisher[second])
        // Each has its own local identity and its own projected source id, the same on every device.
        assertTrue(a.manifest.id.startsWith("$PLUGIN_ID~") && b.manifest.id.startsWith("$PLUGIN_ID~"))
        assertTrue(a.manifest.id != b.manifest.id)
        val aSource = a.manifest.sources!!.single()
        val bSource = b.manifest.sources!!.single()
        assertTrue(aSource.id != bSource.id)
        assertEquals(PluginInstallKeys.projectedSourceId(a.manifest.id, SOURCE_ID.toString()), aSource.id)
        assertEquals(SOURCE_ID.toString(), aSource.canonicalSourceId)

        host.manager.approveCurrentEventGrantReview(a.manifest.id, emptySet())
        host.manager.approveCurrentEventGrantReview(b.manifest.id, emptySet())
        assertNotNull(host.manager.source(aSource.id))
        assertNotNull(host.manager.source(bSource.id))

        // Removing one leaves the other running, with its grant.
        host.manager.uninstall(a.manifest.id)
        assertNull(host.manager.source(aSource.id))
        assertNotNull(host.manager.source(bSource.id))
        assertNotNull(host.manager.grantedSelection(b.manifest.id))
    }

    @Test
    fun anUnsignedPackageStaysInertEvenWhenApproved() = runTest {
        val host = Host()
        host.manager.install(REPOSITORY, entry())
        assertFailsWith<ExtensionArtifactNotReviewedException> { host.manager.approveCurrentEventGrantReview(host.key(), emptySet()) }
        assertTrue(host.sandbox.created.isEmpty())
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun blockingTakesEffectAtTheNextLoadAndUnblockingRestores() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        host.manager.loadInstalled()
        assertNull(host.manager.source(host.sourceId()))

        // A later process (same device storage) still keeps it inert; unblocking brings it back.
        val restarted = Host(host.keyValues, host.packages)
        restarted.manager.loadInstalled()
        assertNull(restarted.manager.source(restarted.sourceId()))
        restarted.blocklist.unblock(PluginBlockEntry.Publisher(publisher))
        val again = Host(host.keyValues, host.packages)
        again.manager.loadInstalled()
        assertNotNull(again.manager.source(again.sourceId()))
    }

    @Test
    fun aBlockedPublisherInstallsNothingNew() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        assertFailsWith<PublisherTrustException.Blocked> { host.manager.install(REPOSITORY, entry()) }
        assertTrue(host.manager.installedPlugins().isEmpty())
    }

    @Test
    fun aPendingPublisherKeepsItsInstalledPackageButInstallsNoUpdate() = runTest {
        val host = Host()
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        host.publishers.setGlobalMode(PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH)
        host.manager.loadInstalled()
        assertNotNull(host.manager.source(host.sourceId()))
        assertFailsWith<PublisherTrustException.NotInstallable> { host.manager.install(REPOSITORY, entry(version = "1.1.0", versionCode = 2)) }
    }

    @Test
    fun aBlockCancelledAfterItWasStoredStillTakesTheRuntimeDown() = runTest {
        val keyValues = StallingKeyValueStore()
        val host = Host(keyValues)
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        assertNotNull(host.manager.source(host.sourceId()))
        val adapter = PluginBrowseAdapter(
            manager = host.manager,
            repositoryClient = host.client,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = host.storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            packageSecurity = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, host.client),
        )
        keyValues.stallAfterWriting = "plugin.blocklist.v1"
        val blocking = launch { adapter.setPublisherBlocked(publisher, blocked = true) }
        keyValues.stalled.await() // the block is on disk; the screen goes away before the call returns
        blocking.cancel()
        blocking.join()
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun noRepositorySpellingTurnsAnUncheckedInstallIntoARunningPackage() = runTest {
        val key = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        // Trusted, but without the revocation list installs require.
        for (spelling in listOf("https://repo.example/", "https://repo.example//", "https://REPO.example", "https://repo.example:443", "HTTPS://Repo.Example/")) {
            val host = Host()
            host.publishers.trustRepository(REPOSITORY.baseUrl, publisherRoot(key))
            val installed = runCatching { host.manager.install(ExtensionRepository(spelling, "Repo"), entry()) }
            if (installed.isFailure) continue // the same publisher: refused by the install gate
            // A spelling the publisher is not pinned under installs as unsigned: it can never run.
            assertNull(host.packages.get(host.key())!!.metadata.publisherId, spelling)
            runCatching { host.manager.approveCurrentEventGrantReview(host.key(), emptySet()) }
            assertNull(host.manager.source(host.sourceId()), "$spelling runs without a revocation list")
            assertTrue(host.sandbox.created.isEmpty(), spelling)
        }
    }

    @Test
    fun withoutASandboxATrustedPackageDoesNotRun() = runTest {
        val host = Host(withSandbox = false)
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertFailsWith<ExtensionArtifactNotReviewedException> { host.manager.approveCurrentEventGrantReview(host.key(), emptySet()) }
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun aTrustPromptOnlyEverTrustsTheKeyItShowed() = runTest {
        val first = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val second = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val repository = "https://signed.example/repo"
        var presenting = first
        suspend fun repoJson(key: dev.shinsou.kmp.sync.crypto.SodiumKeyPair, at: String) = signedPublisherEnvelope(
            key, at, RepositorySignedDocumentType.REPOSITORY, "repo.json", 1,
            kotlinx.serialization.json.buildJsonObject {
                put("meta", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("Signed"))
                    put("signingKeyFingerprint", kotlinx.serialization.json.JsonPrimitive(publisherRoot(key).fingerprint))
                })
            },
        ).toString()
        val host = Host()
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond("", io.ktor.http.HttpStatusCode.NotFound) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = PublisherRepositoryTrustPolicy(host.publishers, host.blocklist, RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN),
            repositorySecurityState = host.state,
            repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved transport only")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse =
                    if (request.url.substringBefore('?').endsWith("/repo.json")) {
                        PluginHttpResponse(200, repoJson(presenting, request.url.substringBefore('?').removeSuffix("/repo.json")).encodeToByteArray())
                    }
                    else PluginHttpResponse(404, ByteArray(0))
            },
        )
        val adapter = PluginBrowseAdapter(
            manager = host.manager,
            repositoryClient = client,
            repositoryStore = KeyValueExtensionRepositoryStore(host.keyValues),
            pluginStorage = host.storage,
            keyValueStore = host.keyValues,
            trustStore = KeyValuePluginTrustStore(host.keyValues),
            packageSecurity = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, client),
        )
        val shownA = assertNotNull(adapter.inspectRepositoryTrust(repository))
        assertEquals(publisherRoot(first).fingerprint, shownA.keyFingerprint)
        // The repository now presents another key (another inspection, e.g. from a link).
        presenting = second
        val shownB = assertNotNull(adapter.inspectRepositoryTrust(repository))
        assertFailsWith<IllegalStateException> { adapter.trustAndAddRepository(shownA, null) }
        assertNull(host.publishers.publisherForRepository(repository), "the key shown first was not the one inspected last")
        runCatching { adapter.trustAndAddRepository(shownB, null) } // adding may fail later (no index); trust is what counts
        assertEquals(publisherRoot(second).fingerprint, host.publishers.publisherForRepository(repository)!!.currentFingerprint)
        assertTrue(assertNotNull(adapter.inspectRepositoryTrust(repository)).alreadyTrusted)

        // A link's fingerprint is enforced even when the dialog sends none or another one.
        val other = "https://signed.example/other"
        presenting = first
        val linked = assertNotNull(adapter.inspectRepositoryTrust(other)).copy(linkedFingerprint = publisherRoot(second).fingerprint)
        assertFailsWith<PublisherTrustException.FingerprintMismatch> { adapter.trustAndAddRepository(linked, null) }
        assertFailsWith<PublisherTrustException.FingerprintMismatch> { adapter.trustAndAddRepository(linked, publisherRoot(first).fingerprint) }
        assertNull(host.publishers.publisherForRepository(other))

        // Unreachable is not "unsigned": a link must stay pending rather than be dropped.
        val unreachable = PluginBrowseAdapter(
            manager = host.manager,
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond("", io.ktor.http.HttpStatusCode.NotFound) }),
                cacheToken = { 1L },
                repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
                repositoryTransport = object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved transport only")
                    override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse =
                        PluginHttpResponse(503, ByteArray(0))
                },
            ),
            repositoryStore = KeyValueExtensionRepositoryStore(host.keyValues),
            pluginStorage = host.storage,
            keyValueStore = host.keyValues,
            trustStore = KeyValuePluginTrustStore(host.keyValues),
            packageSecurity = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, client),
        )
        assertFailsWith<ExtensionRepositoryException.Http> { unreachable.inspectRepositoryTrust(other) }
    }

    private fun entry(version: String = "1.0.0", versionCode: Int = 1) = PluginIndexEntry(
        id = PLUGIN_ID,
        name = "Tier fixture",
        version = version,
        versionCode = versionCode,
        lang = "all",
        scriptUrl = "plugins/$PLUGIN_ID.js",
        sources = listOf(SourceIndexEntry(name = "Tier fixture", lang = "all", id = SOURCE_ID, baseUrl = "https://source.example", originPolicyVersion = 1)),
        runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
    )

    private companion object {
        const val PLUGIN_ID = "all.execution-tier"
        const val SOURCE_ID = 8_101L
        const val SCRIPT = "var source = { version: 1 };"
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")
        val OTHER_REPOSITORY = ExtensionRepository("https://other-repo.example", "Other repo")
    }
}

private class StallingKeyValueStore(private val delegate: PluginKeyValueStore = InMemoryPluginKeyValueStore()) : PluginKeyValueStore {
    var stallAfterWriting: String? = null
    val stalled = kotlinx.coroutines.CompletableDeferred<Unit>()
    override suspend fun getString(key: String): String? = delegate.getString(key)
    override suspend fun putString(key: String, value: String) {
        delegate.putString(key, value)
        if (key == stallAfterWriting) {
            stalled.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
    }
    override suspend fun remove(key: String) = delegate.remove(key)
}
