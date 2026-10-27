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

/** ADR-0002 §10.1 inside [PluginManager]: the publisher-trust tier decides which engine, if any, runs a package. */
class PluginManagerExecutionTierTest {
    @BeforeTest
    fun initializeCrypto() = runTest { SodiumSyncPrimitives.initialize() }

    private class Engine(private val name: String) : ScriptPluginRuntimeFactory {
        val created = mutableListOf<String>()
        override suspend fun create(script: String, manifest: PluginManifest, environment: ScriptPluginEnvironment): ScriptPluginRuntime {
            if (name == "in-process" && environment.inProcessScriptProvenance == null) {
                throw ScriptRuntimeUnavailableException("not reviewed")
            }
            created += manifest.id
            return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
        }
    }

    private class Host(
        val keyValues: PluginKeyValueStore = InMemoryPluginKeyValueStore(),
        val packages: PluginPackageStore = InMemoryPluginPackageStore(),
        withSandbox: Boolean = true,
    ) {
        /** An engine that would run anything: the tiered factory alone must keep third parties out of it. */
        val inProcess = Engine("permissive")
        val sandbox = Engine("sandbox")
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
            runtimeFactory = TieredScriptPluginRuntimeFactory(inProcess, sandbox.takeIf { withSandbox }),
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

    @kotlin.test.AfterTest
    fun destroyKeys() = keys.forEach { SodiumSyncPrimitives.destroy(it.privateKey) }

    /** Trusts a fresh publisher for [REPOSITORY], with the current (empty) revocation list installs require. */
    private suspend fun Host.trustPublisher(): String {
        val key = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val publisher = publishers.trustRepository(REPOSITORY.baseUrl, publisherRoot(key)).publisherId
        revocations.applyRevocations(REPOSITORY.baseUrl, signedRevocations(key, REPOSITORY.baseUrl, publisher, expiresAt = 10_000))
        return publisher
    }

    @Test
    fun aTrustedPublishersApprovedPackageRunsInTheSandbox() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertEquals(publisher, host.packages.get(PLUGIN_ID)!!.metadata.publisherId)
        assertTrue(host.sandbox.created.isEmpty(), "nothing runs before the user approves")
        host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        assertEquals(listOf(PLUGIN_ID), host.sandbox.created)
        assertTrue(host.inProcess.created.isEmpty())
        assertNotNull(host.manager.source(SOURCE_ID))
    }

    @Test
    fun anUnsignedPackageStaysInertEvenWhenApproved() = runTest {
        val host = Host()
        host.manager.install(REPOSITORY, entry())
        assertFailsWith<ExtensionArtifactNotReviewedException> { host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet()) }
        assertTrue(host.sandbox.created.isEmpty())
        assertNull(host.manager.source(SOURCE_ID))
    }

    @Test
    fun blockingTakesEffectAtTheNextLoadAndUnblockingRestores() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        host.manager.loadInstalled()
        assertNull(host.manager.source(SOURCE_ID))

        // A later process (same device storage) still keeps it inert; unblocking brings it back.
        val restarted = Host(host.keyValues, host.packages)
        restarted.manager.loadInstalled()
        assertNull(restarted.manager.source(SOURCE_ID))
        restarted.blocklist.unblock(PluginBlockEntry.Publisher(publisher))
        val again = Host(host.keyValues, host.packages)
        again.manager.loadInstalled()
        assertNotNull(again.manager.source(SOURCE_ID))
    }

    @Test
    fun aBlockedPublisherInstallsNothingNew() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        assertFailsWith<PublisherTrustException.Blocked> { host.manager.install(REPOSITORY, entry()) }
        assertNull(host.packages.get(PLUGIN_ID))
    }

    @Test
    fun aPendingPublisherKeepsItsInstalledPackageButInstallsNoUpdate() = runTest {
        val host = Host()
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        host.publishers.setGlobalMode(PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH)
        host.manager.loadInstalled()
        assertNotNull(host.manager.source(SOURCE_ID))
        assertFailsWith<PublisherTrustException.NotInstallable> { host.manager.install(REPOSITORY, entry(version = "1.1.0", versionCode = 2)) }
    }

    @Test
    fun aBlockCancelledAfterItWasStoredStillTakesTheRuntimeDown() = runTest {
        val keyValues = StallingKeyValueStore()
        val host = Host(keyValues)
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        assertNotNull(host.manager.source(SOURCE_ID))
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
        assertNull(host.manager.source(SOURCE_ID))
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
            assertNull(host.packages.get(PLUGIN_ID)!!.metadata.publisherId, spelling)
            runCatching { host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet()) }
            assertNull(host.manager.source(SOURCE_ID), "$spelling runs without a revocation list")
            assertTrue(host.sandbox.created.isEmpty() && host.inProcess.created.isEmpty(), spelling)
        }
    }

    @Test
    fun withoutASandboxATrustedPackageNeverReachesTheInProcessEngine() = runTest {
        val host = Host(withSandbox = false)
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertFailsWith<ExtensionArtifactNotReviewedException> { host.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet()) }
        assertTrue(host.inProcess.created.isEmpty())
        assertNull(host.manager.source(SOURCE_ID))
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
