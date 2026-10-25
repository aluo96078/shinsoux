package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManagerCancellationTest {
    @Test
    fun cancellationAfterPackageCommitStillPublishesTheLiveRuntime() = runTest {
        val packageStore = ControllablePackageStore().apply { pausePutAfterCommit = OperationGate() }
        val factory = CancellationRuntimeFactory()
        val manager = manager(packageStore, factory)

        val install = launch { manager.install(REPOSITORY, ENTRY) }
        val gate = requireNotNull(packageStore.pausePutAfterCommit)
        gate.reached.await()
        install.cancel()
        gate.release.complete(Unit)
        install.join()

        assertTrue(install.isCancelled)
        assertEquals(PLUGIN_ID, packageStore.list().single().manifest.id)
        assertEquals(SOURCE_ID, manager.source(SOURCE_ID)?.id)
        assertFalse(factory.created.single().closed)
    }

    @Test
    fun cancelledFailingCommitStillClosesTheCandidateRuntime() = runTest {
        val packageStore = ControllablePackageStore().apply { failPut = OperationGate() }
        val factory = CancellationRuntimeFactory()
        val manager = manager(packageStore, factory)

        val install = launch {
            runCatching { manager.install(REPOSITORY, ENTRY) }
        }
        val gate = requireNotNull(packageStore.failPut)
        gate.reached.await()
        install.cancel()
        gate.release.complete(Unit)
        install.join()

        assertTrue(factory.created.single().closed)
        assertTrue(packageStore.list().isEmpty())
        assertNull(manager.source(SOURCE_ID))
    }

    @Test
    fun cancellationDuringUninstallCannotLeavePackageWithoutItsRuntime() = runTest {
        val packageStore = ControllablePackageStore()
        val factory = CancellationRuntimeFactory()
        val manager = manager(packageStore, factory)
        manager.install(REPOSITORY, ENTRY)
        packageStore.pauseRemoveBeforeCommit = OperationGate()

        val uninstall = launch { manager.uninstall(PLUGIN_ID) }
        val gate = requireNotNull(packageStore.pauseRemoveBeforeCommit)
        gate.reached.await()
        uninstall.cancel()
        gate.release.complete(Unit)
        uninstall.join()

        assertTrue(uninstall.isCancelled)
        assertTrue(packageStore.list().isEmpty())
        assertNull(manager.source(SOURCE_ID))
        assertTrue(factory.created.single().closed)
    }

    @Test
    fun failedPackageRemovalLeavesRetainedPackageInertAndCanBeRetried() = runTest {
        val packageStore = ControllablePackageStore()
        val values = InMemoryPluginKeyValueStore()
        val factory = CancellationRuntimeFactory()
        val manager = manager(
            packageStore,
            factory,
            values = values,
            enableEventGrantAdmission = true,
        )
        manager.install(REPOSITORY, ENTRY)
        manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        packageStore.failRemove = OperationGate()

        val uninstall = async { runCatching { manager.uninstall(PLUGIN_ID) } }
        val gate = requireNotNull(packageStore.failRemove)
        gate.reached.await()
        assertNull(manager.source(SOURCE_ID))
        assertTrue(factory.created.single().closed)
        gate.release.complete(Unit)

        val failure = uninstall.await().exceptionOrNull()
        assertEquals("Injected package removal failure", failure?.message)
        val retained = packageStore.list().single()
        assertEquals(PLUGIN_ID, retained.manifest.id)
        assertFalse(retained.metadata.legacyTrustOnInstall)
        assertNull(manager.source(SOURCE_ID))

        // Neither the current process nor a fresh manager may silently revive retained bytes.
        assertTrue(manager.loadInstalled().isEmpty())
        val restartedFactory = CancellationRuntimeFactory()
        val restartedManager = manager(
            packageStore,
            restartedFactory,
            values = values,
            enableEventGrantAdmission = true,
        )
        assertTrue(restartedManager.loadInstalled().isEmpty())
        assertTrue(restartedFactory.created.isEmpty())

        // A later explicit retry can finish durable deletion without reconstructing a runtime.
        packageStore.failRemove = null
        manager.uninstall(PLUGIN_ID)
        assertTrue(packageStore.list().isEmpty())
        assertNull(manager.source(SOURCE_ID))
    }

    @Test
    fun packageRemovalFailureRemainsPrimaryWhenOtherRevocationAlsoFails() = runTest {
        val packageStore = ControllablePackageStore().apply { failRemove = OperationGate() }
        val trustStore = FailingRevokeTrustStore()
        val manager = manager(
            packageStore,
            CancellationRuntimeFactory(),
            providedTrustStore = trustStore,
        )
        manager.install(REPOSITORY, ENTRY)
        val uninstall = async { runCatching { manager.uninstall(PLUGIN_ID) } }
        val gate = requireNotNull(packageStore.failRemove)
        gate.reached.await()
        gate.release.complete(Unit)

        val failure = requireNotNull(uninstall.await().exceptionOrNull())
        assertEquals("Injected package removal failure", failure.message)
        assertEquals(1, trustStore.revokeAllCalls)
        assertEquals(1, failure.suppressedExceptions.size)
        assertNull(manager.source(SOURCE_ID))
        assertTrue(packageStore.list().isNotEmpty())
    }

    @Test
    fun failedLegacyTrustRestoresThePreviousDurableAndLivePackage() = runTest {
        val oldManifest = PluginManifest(
            id = PLUGIN_ID,
            name = "Previous",
            version = "0.9.0",
            versionCode = 9,
            lang = "all",
            script = "$PLUGIN_ID.js",
            signature = "",
            sources = listOf(SourceIndexEntry("Previous", "all", OLD_SOURCE_ID, "https://old.example")),
        )
        val packageStore = InMemoryPluginPackageStore().apply {
            put(
                StoredPlugin(
                    InstalledPluginMetadata(
                        manifest = oldManifest,
                        installedSha256 = "",
                        legacyTrustOnInstall = true,
                    ),
                    ByteArray(0),
                ),
            )
        }
        val manager = manager(packageStore, CancellationRuntimeFactory(), FailingTrustStore)
        assertEquals(OLD_SOURCE_ID, manager.loadInstalled().single().id)

        assertFailsWith<IllegalStateException> {
            manager.installLegacy(REPOSITORY, LEGACY_ENTRY)
        }

        assertEquals("0.9.0", packageStore.list().single().manifest.version)
        assertEquals(OLD_SOURCE_ID, manager.source(OLD_SOURCE_ID)?.id)
        assertNull(manager.source(SOURCE_ID))
    }

    @Test
    fun failedDurableRevocationStillUnloadsTheRuntimeFailClosed() = runTest {
        val packageStore = InMemoryPluginPackageStore().apply { put(executableLegacyPackage()) }
        val factory = CancellationRuntimeFactory()
        val manager = manager(packageStore, factory, FailingRevokeTrustStore())
        manager.loadInstalled()
        requireNotNull(manager.source(SOURCE_ID))

        assertFailsWith<IllegalStateException> {
            manager.setPluginTrusted(PLUGIN_ID, false)
        }

        assertNull(manager.source(SOURCE_ID))
        assertTrue(factory.created.single().closed)
        assertFalse(packageStore.list().single().metadata.legacyTrustOnInstall)

        // The failed durable token must not allow an in-process reload to revive the runtime.
        assertTrue(manager.loadInstalled().isEmpty())
    }

    @Test
    fun metadataTrustFailureLeavesExistingLiveStubsUntouched() = runTest {
        val packageStore = InMemoryPluginPackageStore().apply { put(metadataOnlyLegacyPackage()) }
        val manager = manager(packageStore, CancellationRuntimeFactory(), FailingTrustStore)
        manager.loadInstalled()
        val liveBefore = requireNotNull(manager.source(SOURCE_ID))

        assertFailsWith<IllegalStateException> {
            manager.setPluginTrusted(PLUGIN_ID, true)
        }

        assertTrue(manager.source(SOURCE_ID) === liveBefore)
        assertEquals(SOURCE_ID, manager.catalogueSources().single().id)
    }

    @Test
    fun cancellationDuringManagerCloseStillReleasesRuntimeResources() = runTest {
        val factory = CancellationRuntimeFactory()
        val manager = manager(InMemoryPluginPackageStore(), factory)
        manager.install(REPOSITORY, ENTRY)
        val runtime = factory.created.single()
        runtime.closeGate = OperationGate()

        val close = launch { manager.close() }
        val gate = requireNotNull(runtime.closeGate)
        gate.reached.await()
        close.cancel()
        gate.release.complete(Unit)
        close.join()

        assertTrue(close.isCancelled)
        assertTrue(runtime.closed)
        assertTrue(manager.catalogueSources().isEmpty())
    }

    @Test
    fun managerCloseRunsEvenWhenItsCallerIsAlreadyCancelled() = runTest {
        val factory = CancellationRuntimeFactory()
        val manager = manager(InMemoryPluginPackageStore(), factory)
        manager.install(REPOSITORY, ENTRY)
        val runtime = factory.created.single()

        val close = launch {
            cancel()
            manager.close()
        }
        close.join()

        assertTrue(close.isCancelled)
        assertTrue(runtime.closed)
        assertTrue(manager.catalogueSources().isEmpty())
    }

    private fun manager(
        packageStore: PluginPackageStore,
        factory: ScriptPluginRuntimeFactory,
        providedTrustStore: PluginTrustStore? = null,
        values: InMemoryPluginKeyValueStore = InMemoryPluginKeyValueStore(),
        enableEventGrantAdmission: Boolean = false,
    ): PluginManager {
        val storage = KeyValuePluginStorage(values)
        return PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond(SCRIPT) }),
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore = packageStore,
            verifier = PluginVerifier(providedTrustStore ?: KeyValuePluginTrustStore(values)),
            runtimeFactory = factory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage = storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                ),
                storage = storage,
            ),
            eventGrantAdmission = if (enableEventGrantAdmission) {
                KeyValuePluginEventGrantAdmission(
                    values,
                    MutablePluginSystemEventAuthorizer(),
                )
            } else {
                null
            },
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
    }

    private fun executableLegacyPackage(): StoredPlugin {
        val bytes = SCRIPT.encodeToByteArray()
        val hash = Sha256.hex(bytes)
        val manifest = PluginManifest(
            id = PLUGIN_ID,
            name = "Cancellation",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "$PLUGIN_ID.js",
            signature = hash,
            sources = ENTRY.sources,
            // This fixture models an executable pre-V2 package. Missing runtime permissions are
            // intentionally inert now and are covered by PluginManagerExecutionAdmissionTest.
            runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
        )
        return StoredPlugin(
            InstalledPluginMetadata(
                manifest = manifest,
                installedSha256 = hash,
                legacyTrustOnInstall = true,
            ),
            bytes,
        )
    }

    private fun metadataOnlyLegacyPackage(): StoredPlugin {
        val manifest = PluginManifest(
            id = PLUGIN_ID,
            name = "Cancellation",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "$PLUGIN_ID.js",
            signature = "",
            sources = ENTRY.sources,
        )
        return StoredPlugin(
            InstalledPluginMetadata(
                manifest = manifest,
                installedSha256 = "",
                legacyTrustOnInstall = true,
            ),
            ByteArray(0),
        )
    }

    private companion object {
        const val PLUGIN_ID = "all.cancellation"
        const val SOURCE_ID = 7_001L
        const val OLD_SOURCE_ID = 7_000L
        const val SCRIPT = "var source = {};"
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")
        val ENTRY = PluginIndexEntry(
            id = PLUGIN_ID,
            name = "Cancellation",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            nsfw = 0,
            scriptUrl = "plugins/$PLUGIN_ID.js",
            sources = listOf(
                SourceIndexEntry("Cancellation", "all", SOURCE_ID, "https://source.example"),
            ),
            runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
        )
        val LEGACY_ENTRY = LegacyExtensionIndexEntry(
            name = "Cancellation",
            pkg = PLUGIN_ID,
            apk = "cancellation.apk",
            lang = "all",
            code = 10,
            version = "1.0.0",
            nsfw = 0,
            sources = listOf(
                SourceIndexEntry("Cancellation", "all", SOURCE_ID, "https://source.example"),
            ),
        )
    }
}

private class OperationGate {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun pause() {
        reached.complete(Unit)
        release.await()
    }
}

private class ControllablePackageStore : PluginPackageStore {
    private val mutex = Mutex()
    private val plugins = mutableMapOf<String, StoredPlugin>()

    var pausePutAfterCommit: OperationGate? = null
    var failPut: OperationGate? = null
    var pauseRemoveBeforeCommit: OperationGate? = null
    var failRemove: OperationGate? = null

    override suspend fun list(): List<StoredPlugin> = mutex.withLock {
        plugins.values.map(::copyPlugin)
    }

    override suspend fun get(pluginId: String): StoredPlugin? = mutex.withLock {
        plugins[pluginId]?.let(::copyPlugin)
    }

    override suspend fun put(plugin: StoredPlugin) {
        failPut?.let { gate ->
            gate.pause()
            error("Injected package commit failure")
        }
        mutex.withLock { plugins[plugin.manifest.id] = copyPlugin(plugin) }
        pausePutAfterCommit?.pause()
    }

    override suspend fun remove(pluginId: String) {
        failRemove?.let { gate ->
            gate.pause()
            error("Injected package removal failure")
        }
        pauseRemoveBeforeCommit?.pause()
        mutex.withLock { plugins.remove(pluginId) }
    }

    private fun copyPlugin(plugin: StoredPlugin): StoredPlugin =
        plugin.copy(scriptBytes = plugin.scriptBytes.copyOf())
}

private object FailingTrustStore : PluginTrustStore {
    override suspend fun isTrusted(pluginId: String, versionCode: Int, sha256: String): Boolean = false
    override suspend fun trust(pluginId: String, versionCode: Int, sha256: String): Unit =
        error("Injected trust-store failure")
    override suspend fun revoke(pluginId: String, versionCode: Int, sha256: String) = Unit
    override suspend fun revokeAll(pluginId: String) = Unit
}

private class FailingRevokeTrustStore : PluginTrustStore {
    var revokeAllCalls: Int = 0
        private set

    override suspend fun isTrusted(pluginId: String, versionCode: Int, sha256: String): Boolean = true
    override suspend fun trust(pluginId: String, versionCode: Int, sha256: String) = Unit
    override suspend fun revoke(pluginId: String, versionCode: Int, sha256: String) = Unit
    override suspend fun revokeAll(pluginId: String) {
        revokeAllCalls += 1
        error("Injected revocation failure")
    }
}

private class CancellationRuntimeFactory : ScriptPluginRuntimeFactory {
    val created = mutableListOf<CancellationRuntime>()

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = CancellationRuntime(manifest).also(created::add)
}

private class CancellationRuntime(manifest: PluginManifest) : ScriptPluginRuntime {
    private val source = requireNotNull(manifest.sources?.firstOrNull())

    var closeGate: OperationGate? = null
    var closed: Boolean = false

    override val pluginId: String = manifest.id
    override val id: Long = source.id
    override val name: String = source.name
    override val lang: String = source.lang
    override val baseUrl: String = source.baseUrl.orEmpty()
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getFilterList(): FilterList = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    override suspend fun login(username: String, password: String): Boolean = false
    override suspend fun logout() = Unit

    override suspend fun close() {
        closeGate?.pause()
        // This makes cleanup observably fail when it is attempted in an already-cancelled context.
        yield()
        closed = true
    }
}
