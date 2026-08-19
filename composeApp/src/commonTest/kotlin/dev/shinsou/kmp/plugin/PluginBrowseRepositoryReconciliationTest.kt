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
    fun defaultRepositoryIsPublishedToPortableSnapshot() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val harness = createHarness(
            kv = kv,
            store = store,
            portable = portable,
            defaultRepositoryUrl = "https://default.example",
        )

        try {
            harness.adapter.refresh()

            assertEquals(
                listOf("https://default.example"),
                portable.currentSnapshot.extensionRepositories.map { it.baseUrl },
            )
            assertEquals(
                portable.currentSnapshot.extensionRepositories.map { it.baseUrl },
                store.list().map { it.baseUrl },
            )
        } finally {
            harness.client.close()
        }
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
        val harness = createHarness(
            kv = kv,
            store = KeyValueExtensionRepositoryStore(kv),
            portable = ShinsouRepository(),
            defaultRepositoryUrl = "https://default.example",
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
        defaultRepositoryUrl: String = "",
        indexRequestStarted: CompletableDeferred<Unit>? = null,
        indexResponseGate: CompletableDeferred<Unit>? = null,
    ): Harness {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/repo.json") -> respond(
                        content = """{"meta":{"name":"${request.url.host} repository","website":"https://${request.url.host}/site"}}""",
                        status = HttpStatusCode.OK,
                    )

                    request.url.encodedPath.endsWith("/index.json") -> {
                        indexRequestStarted?.complete(Unit)
                        indexResponseGate?.await()
                        respond("[]", HttpStatusCode.OK)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound)
                }
            },
        )
        val repositoryClient = ExtensionRepositoryClient(client, cacheToken = { 1L })
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
                defaultRepositoryUrl = defaultRepositoryUrl,
            ),
        )
    }

    private data class Harness(
        val client: HttpClient,
        val adapter: PluginBrowseAdapter,
    )
}
