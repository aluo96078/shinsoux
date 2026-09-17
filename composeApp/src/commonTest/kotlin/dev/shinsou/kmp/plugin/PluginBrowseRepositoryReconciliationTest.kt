package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.ExtensionRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginBrowseRepositoryReconciliationTest {
    @Test
    fun freshInstallAndRefreshNeverSeedOrContactARepository() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val requested = CompletableDeferred<Unit>()
        val harness = createHarness(kv, store, portable, indexRequestStarted = requested)
        try {
            repeat(2) { harness.adapter.refresh() }
            assertTrue(portable.currentSnapshot.extensionRepositories.isEmpty())
            assertTrue(store.list().isEmpty())
            assertTrue(harness.adapter.state.value.repositories.isEmpty())
            assertFalse(requested.isCompleted)
        } finally { harness.client.close() }
    }

    @Test
    fun removalSkipsBrokenReviewedMigrationAndUnrelatedRemoteRefresh() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val requested = CompletableDeferred<Unit>()
        store.put(ExtensionRepository("https://remove.example", "Remove"))
        store.put(ExtensionRepository("https://offline.example", "Offline"))
        // This previously prevented deletion during the pre-delete reconciliation.
        kv.putString("plugin.shuyue.v2.repository-urls", "https://remove.example/index.json\nmalformed")
        val harness = createHarness(kv, store, portable, indexRequestStarted = requested)
        try {
            harness.adapter.removeRepository("https://remove.example")
            assertEquals(listOf("https://offline.example"), store.list().map { it.baseUrl })
            assertEquals(listOf("https://offline.example"), portable.currentSnapshot.extensionRepositories.map { it.baseUrl })
            assertEquals("malformed", kv.getString("plugin.shuyue.v2.repository-urls"))
            assertFalse(requested.isCompleted)
            assertEquals(null, harness.adapter.state.value.errorMessage)
            harness.adapter.removeRepository("https://offline.example")
            assertTrue(store.list().isEmpty())
        } finally { harness.client.close() }
    }

    @Test
    fun deletionAfterRefreshFailureDoesNotRetryNetworkAndSurvivesRestart() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        store.put(ExtensionRepository("https://broken.example", "Broken"))
        var calls = 0
        val harness = createHarness(kv, store, portable, onIndexRequest = {
            calls++
            error("Repository unavailable")
        })
        try {
            runCatching { harness.adapter.refresh() }
            val before = calls
            assertTrue(before > 0)
            harness.adapter.removeRepository("https://broken.example")
            assertEquals(before, calls)
            assertTrue(harness.adapter.state.value.repositories.isEmpty())
            assertEquals(null, harness.adapter.state.value.errorMessage)
            val restarted = createHarness(kv, store, portable, onIndexRequest = { error("Must stay offline") })
            try {
                restarted.adapter.refresh()
                assertTrue(restarted.adapter.state.value.repositories.isEmpty())
            } finally { restarted.client.close() }
        } finally { harness.client.close() }
    }

    @Test
    fun addAndRemoveRepositoriesMutatePortableSnapshotAndKvMirror() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val harness = createHarness(kv, store, portable)

        try {
            harness.adapter.addRepository("https://added.example/index.json")

            val added = ExtensionRepo(
                baseUrl = "https://added.example",
                name = "added.example repository",
                website = "https://added.example/site",
            )
            assertEquals(listOf(added), portable.currentSnapshot.extensionRepositories)
            assertEquals(listOf(added.baseUrl), store.list().map { it.baseUrl })
            assertEquals(listOf(added.baseUrl), harness.adapter.state.value.repositories.map { it.id })

            harness.adapter.removeRepository(added.baseUrl)
            harness.adapter.refresh()

            assertTrue(portable.currentSnapshot.extensionRepositories.isEmpty())
            assertTrue(store.list().isEmpty())
            assertTrue(harness.adapter.state.value.repositories.isEmpty())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun restoredSnapshotReplacesStaleKvAfterOneTimeLegacyMigration() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val stale = ExtensionRepository(
            baseUrl = "https://stale.example",
            name = "Legacy repository",
            website = "https://stale.example/site",
        )
        store.put(stale)
        store.select(stale.baseUrl)
        val portable = ShinsouRepository()
        val harness = createHarness(kv, store, portable)

        try {
            harness.adapter.refresh()
            assertEquals(
                listOf(stale.baseUrl),
                portable.currentSnapshot.extensionRepositories.map { it.baseUrl },
            )

            val restored = ExtensionRepo(
                baseUrl = "https://restored.example",
                name = "Restored repository",
                shortName = "Restored",
                website = "https://restored.example/site",
                signingKeyFingerprint = "restored-fingerprint",
            )
            portable.replaceSnapshot(
                portable.currentSnapshot.copy(extensionRepositories = listOf(restored)),
            )

            harness.adapter.refresh()

            assertEquals(listOf(restored), portable.currentSnapshot.extensionRepositories)
            assertEquals(
                listOf(
                    ExtensionRepository(
                        baseUrl = restored.baseUrl,
                        name = restored.name,
                        shortName = restored.shortName,
                        website = restored.website,
                        signingKeyFingerprint = restored.signingKeyFingerprint,
                    ),
                ),
                store.list(),
            )
            assertEquals(listOf(restored.baseUrl), harness.adapter.state.value.repositories.map { it.id })
            assertEquals(null, store.selected())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun cancelledRefreshClearsRefreshingFlag() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val indexRequestStarted = CompletableDeferred<Unit>()
        val indexResponseGate = CompletableDeferred<Unit>()
        val configuredStore = KeyValueExtensionRepositoryStore(kv).also {
            it.put(ExtensionRepository("https://default.example", "Configured"))
        }
        val harness = createHarness(
            kv = kv,
            store = configuredStore,
            portable = ShinsouRepository(),
            indexRequestStarted = indexRequestStarted,
            indexResponseGate = indexResponseGate,
        )

        try {
            val refresh = launch { harness.adapter.refresh() }
            indexRequestStarted.await()
            assertTrue(harness.adapter.state.value.isRefreshing)

            refresh.cancelAndJoin()

            assertFalse(harness.adapter.state.value.isRefreshing)
        } finally {
            harness.client.close()
        }
    }

    private fun createHarness(
        kv: PluginKeyValueStore,
        store: ExtensionRepositoryStore,
        portable: ShinsouRepository,
        indexRequestStarted: CompletableDeferred<Unit>? = null,
        indexResponseGate: CompletableDeferred<Unit>? = null,
        onIndexRequest: () -> Unit = {},
    ): Harness {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/repo.json") -> respond(
                        content = """{"meta":{"name":"${request.url.host} repository","website":"https://${request.url.host}/site"}}""",
                        status = HttpStatusCode.OK,
                    )

                    request.url.encodedPath.endsWith("/index.json") -> {
                        onIndexRequest()
                        indexRequestStarted?.complete(Unit)
                        indexResponseGate?.await()
                        respond("[]", HttpStatusCode.OK)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound)
                }
            },
        )
        val repositoryClient = ExtensionRepositoryClient(
            client,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val storage = KeyValuePluginStorage(kv)
        val trustStore = KeyValuePluginTrustStore(kv)
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = KeyValuePluginPackageStore(kv),
            verifier = PluginVerifier(trustStore),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage = storage,
                ),
                storage = storage,
            ),
        )
        return Harness(
            client = client,
            adapter = PluginBrowseAdapter(
                manager = manager,
                repositoryClient = repositoryClient,
                repositoryStore = store,
                pluginStorage = storage,
                keyValueStore = kv,
                trustStore = trustStore,
                portableRepository = portable,
            ),
        )
    }

    private data class Harness(
        val client: HttpClient,
        val adapter: PluginBrowseAdapter,
    )
}
