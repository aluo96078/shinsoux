package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.contract.AccountPackageDirectory
import dev.shinsou.kmp.crypto.SodiumPrimitives
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.model.ExtensionRepository
import dev.shinsou.kmp.plugin.model.PluginIndexEntry
import dev.shinsou.kmp.plugin.model.PluginInstallKeys
import dev.shinsou.kmp.plugin.model.PluginManifest
import dev.shinsou.kmp.plugin.model.PluginRuntimePermission
import dev.shinsou.kmp.plugin.model.SourceIndexEntry
import dev.shinsou.kmp.plugin.network.PluginHttpResponse
import dev.shinsou.kmp.plugin.network.PluginHttpTransport
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.repository.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.repository.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.repository.RepositoryIndex
import dev.shinsou.kmp.plugin.repository.RepositoryIndexAdmission
import dev.shinsou.kmp.plugin.repository.RepositoryIndexSnapshot
import dev.shinsou.kmp.plugin.runtime.NoopScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.runtime.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.runtime.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.runtime.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.storage.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.storage.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.storage.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.trust.ExecutionTierResolver
import dev.shinsou.kmp.plugin.trust.KeyValueRepositorySecurityStateStore
import dev.shinsou.kmp.plugin.trust.LocalPluginBlocklist
import dev.shinsou.kmp.plugin.trust.PluginDeveloperModeStore
import dev.shinsou.kmp.plugin.trust.PluginPackageSecurity
import dev.shinsou.kmp.plugin.trust.PluginVerifier
import dev.shinsou.kmp.plugin.trust.PublisherExecutionTierGate
import dev.shinsou.kmp.plugin.trust.PublisherRevocationStore
import dev.shinsou.kmp.plugin.trust.PublisherTrustStore
import dev.shinsou.kmp.plugin.trust.RepositoryTrustPolicies
import dev.shinsou.kmp.plugin.trust.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.trust.publisherRoot
import dev.shinsou.kmp.plugin.trust.signedRevocations

/** Installing the sync account's packages without approving them: only from a trusted, added repository. */
class PluginPreinstallTest {
    @BeforeTest
    fun initializeCrypto() = runTest { SodiumPrimitives.initialize() }

    private val keys = mutableListOf<dev.shinsou.kmp.crypto.SodiumKeyPair>()

    @AfterTest
    fun destroyKeys() = keys.forEach { SodiumPrimitives.destroy(it.privateKey) }

    private class Engine : ScriptPluginRuntimeFactory {
        val created = mutableListOf<String>()
        override suspend fun create(script: String, manifest: PluginManifest, environment: ScriptPluginEnvironment): ScriptPluginRuntime {
            created += manifest.id
            return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
        }
    }

    private class Directory(initial: List<AccountPackage> = emptyList()) : AccountPackageDirectory {
        val flow = MutableStateFlow(initial)
        override val packages: StateFlow<List<AccountPackage>> = flow
    }

    /** [onRequest] sees every URL the repository client asks for, before it is answered. */
    private inner class Harness(directory: Directory, onRequest: suspend (String) -> Unit = {}) {
        val keyValues = InMemoryPluginKeyValueStore()
        val packages = InMemoryPluginPackageStore()
        val sandbox = Engine()
        val state = KeyValueRepositorySecurityStateStore(keyValues)
        val publishers = PublisherTrustStore(keyValues, state)
        val revocations = PublisherRevocationStore(keyValues, publishers, state)
        val blocklist = LocalPluginBlocklist(keyValues)
        val storage = KeyValuePluginStorage(keyValues)
        val developer = PluginDeveloperModeStore(keyValues)
        val repositories = KeyValueExtensionRepositoryStore(keyValues)
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { request -> onRequest(request.url.toString()); respond(SCRIPT) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            developerRepository = developer::admitsRepository,
        )
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packages,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(sandbox),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage = storage),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(keyValues, MutablePluginSystemEventAuthorizer()),
            executionTierGate = PublisherExecutionTierGate(
                publishers,
                ExecutionTierResolver(
                    publishers, revocations, blocklist,
                    developerMode = { developer.state.value.enabled },
                    nowEpochSeconds = { 1_000L },
                ),
            ),
            developerRepository = developer::admitsRepository,
        )
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = client,
            repositoryStore = repositories,
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            packageSecurity = PluginPackageSecurity(publishers, revocations, blocklist, client),
            accountPackages = directory,
        )

        /** What the repository publishes; signed (carrying an admission) unless [signed] is false. */
        var signed = true

        init {
            adapter.indexSnapshotFetcher = { _ ->
                RepositoryIndexSnapshot(
                    RepositoryIndex.Plugins(listOf(entry())),
                    if (signed) RepositoryIndexAdmission() else null,
                )
            }
        }

        suspend fun trustPublisher(repository: ExtensionRepository = REPOSITORY): String {
            val key = SodiumPrimitives.generateEd25519KeyPair().also { keys += it }
            val publisher = publishers.trustRepository(repository.baseUrl, publisherRoot(key)).publisherId
            revocations.applyRevocations(repository.baseUrl, signedRevocations(key, repository.baseUrl, publisher, expiresAt = 10_000))
            return publisher
        }

        fun packageFor(publisher: String, pluginId: String = PLUGIN_ID) = AccountPackage(
            packageId = PluginInstallKeys.installKey(pluginId, publisher, fromLegacyCommunityRepository = false),
            repositoryUrl = REPOSITORY.baseUrl,
            name = "Tier fixture",
            version = "1.0.0",
            versionCode = 1,
        )
    }

    @Test
    fun aTrustedAddedRepositoryPreinstallsThePackageWithoutApprovingIt() = runTest {
        val h = Harness(Directory())
        val publisher = h.trustPublisher()
        val wanted = h.packageFor(publisher)
        h.repositories.put(REPOSITORY)
        val directory = Directory(listOf(wanted))
        val adapter = h.withDirectory(directory)
        adapter.preinstallAccountPackages()

        val stored = assertNotNull(h.packages.get(wanted.packageId))
        assertEquals(publisher, stored.metadata.publisherId, "installed from the trusted publisher, never unsigned")
        assertTrue(h.sandbox.created.isEmpty(), "nothing runs before the user approves")
        assertNotNull(h.manager.pendingEventGrantReview(wanted.packageId), "it waits for the permission review")
        assertTrue(h.keyValues.getString("plugin.packages.preinstalled.v1").orEmpty().contains(wanted.packageId))

        // Titles of a package without a runtime can still be created: its stored manifest names the sources.
        assertNull(h.manager.extensionSourceV2(dev.shinsou.kmp.domain.model.SourceKey(packageId = wanted.packageId, sourceId = SOURCE_ID.toString())))
        val key = assertNotNull(h.manager.installedSourceKey(packageId = wanted.packageId, sourceId = SOURCE_ID.toString()))
        assertEquals(wanted.packageId, key.packageId)
        assertNull(h.manager.installedSourceKey(wanted.packageId, "no-such-source"))
        assertNull(h.manager.installedSourceKey("zh.not-installed", SOURCE_ID.toString()))
    }

    @Test
    fun aRepositoryTheDeviceNeverAddedInstallsNothing() = runTest {
        val h = Harness(Directory())
        val publisher = h.trustPublisher()
        val adapter = h.withDirectory(Directory(listOf(h.packageFor(publisher))))
        adapter.preinstallAccountPackages()
        assertTrue(h.packages.list().isEmpty())

        // Adding and trusting it is what allows the install.
        h.repositories.put(REPOSITORY)
        adapter.preinstallAccountPackages()
        assertEquals(1, h.packages.list().size)
    }

    @Test
    fun anUnsignedOrUntrustedRepositoryInstallsNothing() = runTest {
        val h = Harness(Directory())
        h.repositories.put(REPOSITORY)
        val unknownPublisher = "a".repeat(64)
        val adapter = h.withDirectory(Directory(listOf(h.packageFor(unknownPublisher))))
        adapter.preinstallAccountPackages()
        assertTrue(h.packages.list().isEmpty(), "no publisher is trusted for this repository")

        val publisher = h.trustPublisher()
        h.signed = false
        h.withDirectory(Directory(listOf(h.packageFor(publisher)))).preinstallAccountPackages()
        assertTrue(h.packages.list().isEmpty(), "an unsigned index would make an unsigned install")
    }

    @Test
    fun aPackageTheUserRemovedIsNeverPreinstalledAgain() = runTest {
        val h = Harness(Directory())
        val publisher = h.trustPublisher()
        h.repositories.put(REPOSITORY)
        val wanted = h.packageFor(publisher)
        val adapter = h.withDirectory(Directory(listOf(wanted)))

        h.keyValues.putString("plugin.packages.declined-preinstall.v1", "[\"${wanted.packageId}\"]")
        adapter.preinstallAccountPackages()
        assertTrue(h.packages.list().isEmpty(), "declined")

        h.keyValues.remove("plugin.packages.declined-preinstall.v1")
        adapter.preinstallAccountPackages()
        assertEquals(1, h.packages.list().size)

        // Uninstalling it by hand declines it for good.
        adapter.uninstallExtension(wanted.packageId)
        assertTrue(h.packages.list().isEmpty())
        adapter.preinstallAccountPackages()
        assertTrue(h.packages.list().isEmpty(), "removed on purpose")
        assertTrue(h.keyValues.getString("plugin.packages.declined-preinstall.v1").orEmpty().contains(wanted.packageId))
    }

    @Test
    fun repositoryManagementWaitsForAtMostOnePreinstalledPackage() = runTest {
        val firstDownloading = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val switchRefreshed = CompletableDeferred<Unit>()
        val secondAfterSwitch = CompletableDeferred<Boolean>()
        val h = Harness(Directory()) { url ->
            when {
                "plugins/$PLUGIN_ID.js" in url -> {
                    firstDownloading.complete(Unit)
                    releaseFirst.await()
                }
                "plugins/$SECOND_PLUGIN_ID.js" in url -> secondAfterSwitch.complete(switchRefreshed.isCompleted)
                "repo.example/index" in url -> switchRefreshed.complete(Unit)
            }
        }
        val publisher = h.trustPublisher()
        h.repositories.put(REPOSITORY)
        val adapter = h.withDirectory(
            Directory(listOf(h.packageFor(publisher), h.packageFor(publisher, SECOND_PLUGIN_ID))),
        )
        adapter.indexSnapshotFetcher = { _ ->
            RepositoryIndexSnapshot(
                RepositoryIndex.Plugins(listOf(entry(), entry(SECOND_PLUGIN_ID, SECOND_SOURCE_ID))),
                RepositoryIndexAdmission(),
            )
        }
        withContext(Dispatchers.Default) {
            val preinstall = launch { adapter.preinstallAccountPackages() }
            firstDownloading.await()
            // Reading a new repository's key (the trust dialog) does not wait at all.
            withTimeout(10_000) { runCatching { adapter.inspectRepositoryTrust("https://other.example") } }
            // Switching repositories waits for this one install only, not for the whole list.
            val switch = launch { runCatching { adapter.selectRepository(null) } }
            releaseFirst.complete(Unit)
            withTimeout(10_000) {
                switch.join()
                preinstall.join()
            }
        }
        assertTrue(secondAfterSwitch.await(), "the switch ran before the next package was installed")
        assertEquals(2, h.packages.list().size, "and every package still installed afterwards")
    }

    @Test
    fun aPackageThatFailedToPreinstallWaitsBeforeTryingAgain() = runTest {
        val h = Harness(Directory())
        val publisher = h.trustPublisher()
        h.repositories.put(REPOSITORY)
        val adapter = h.withDirectory(Directory(listOf(h.packageFor(publisher))))
        val clock = TestTimeSource()
        adapter.preinstallClock = clock
        adapter.preinstallRetryDelay = 10.minutes
        var fetches = 0
        var offline = true
        adapter.indexSnapshotFetcher = { _ ->
            fetches++
            if (offline) error("repository offline")
            RepositoryIndexSnapshot(RepositoryIndex.Plugins(listOf(entry())), RepositoryIndexAdmission())
        }

        adapter.preinstallAccountPackages()
        assertEquals(1, fetches)
        assertTrue(h.packages.list().isEmpty())

        offline = false
        adapter.preinstallAccountPackages()
        assertEquals(1, fetches, "a package that just failed is not tried again on every refresh")

        clock += 11.minutes
        adapter.preinstallAccountPackages()
        assertEquals(2, fetches)
        assertEquals(1, h.packages.list().size, "tried again once the wait is over")
    }

    /** The harness adapter with its directory replaced (the directory is a constructor argument). */
    private fun Harness.withDirectory(directory: Directory): PluginBrowseAdapter = PluginBrowseAdapter(
        manager = manager,
        repositoryClient = client,
        repositoryStore = repositories,
        pluginStorage = storage,
        keyValueStore = keyValues,
        trustStore = KeyValuePluginTrustStore(keyValues),
        packageSecurity = PluginPackageSecurity(publishers, revocations, blocklist, client),
        accountPackages = directory,
    ).also { adapter ->
        adapter.indexSnapshotFetcher = { _ ->
            RepositoryIndexSnapshot(RepositoryIndex.Plugins(listOf(entry())), if (signed) RepositoryIndexAdmission() else null)
        }
    }

    private companion object {
        const val PLUGIN_ID = "all.preinstall-fixture"
        const val SOURCE_ID = 8_201L
        const val SECOND_PLUGIN_ID = "all.preinstall-second"
        const val SECOND_SOURCE_ID = 8_202L
        const val SCRIPT = "var source = { version: 1 };"
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")

        fun entry(id: String = PLUGIN_ID, sourceId: Long = SOURCE_ID) = PluginIndexEntry(
            id = id,
            name = "Tier fixture",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            scriptUrl = "plugins/$id.js",
            sources = listOf(SourceIndexEntry(name = "Tier fixture", lang = "all", id = sourceId, baseUrl = "https://source.example", originPolicyVersion = 1)),
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )
    }
}
