package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.ExtensionRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
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
    fun removalSkipsUnrelatedRemoteRefresh() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val requested = CompletableDeferred<Unit>()
        store.put(ExtensionRepository("https://remove.example", "Remove"))
        store.put(ExtensionRepository("https://offline.example", "Offline"))
        val harness = createHarness(kv, store, portable, indexRequestStarted = requested)
        try {
            harness.adapter.removeRepository("https://remove.example")
            assertEquals(listOf("https://offline.example"), store.list().map { it.baseUrl })
            assertEquals(listOf("https://offline.example"), portable.currentSnapshot.extensionRepositories.map { it.baseUrl })
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

    @Test
    fun accountSuggestionsExcludeLocalAndDismissedRepositoriesAndFollowTheAccount() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val directory = FakeDirectory(
            listOf(
                AccountRepository("https://one.example", "One"),
                AccountRepository("https://two.example", "Two", "ab12"),
                AccountRepository("https://three.example", "Three"),
            ),
        )
        val harness = createHarness(kv, store, portable, accountRepositories = directory, suggestionScope = backgroundScope)

        try {
            runCurrent()
            assertEquals(3, harness.adapter.state.value.repositorySuggestions.size)

            // Already on this device: not a suggestion (also when typed with an index.json suffix).
            harness.adapter.addRepository("https://one.example/index.json")
            assertEquals(listOf("https://two.example", "https://three.example"), harness.adapter.state.value.repositorySuggestions.map { it.url })
            assertEquals("ab12", harness.adapter.state.value.repositorySuggestions.first().signingFingerprint)

            harness.adapter.dismissRepositorySuggestion("https://two.example")
            assertEquals(listOf("https://three.example"), harness.adapter.state.value.repositorySuggestions.map { it.url })
            harness.adapter.refresh()
            assertEquals(listOf("https://three.example"), harness.adapter.state.value.repositorySuggestions.map { it.url })

            // A new account repository shows up without a refresh.
            directory.flow.value = directory.flow.value + AccountRepository("https://four.example", "Four")
            runCurrent()
            assertEquals(
                listOf("https://three.example", "https://four.example"),
                harness.adapter.state.value.repositorySuggestions.map { it.url },
            )

            // Deleting a repository here dismisses it; adding it on purpose lifts the dismissal.
            harness.adapter.removeRepository("https://one.example")
            assertEquals(
                listOf("https://three.example", "https://four.example"),
                harness.adapter.state.value.repositorySuggestions.map { it.url },
            )
            assertTrue(directory.forgotten.isEmpty(), "a plain delete stays on this device")
            harness.adapter.addRepository("https://two.example")
            harness.adapter.removeRepository("https://two.example")
            harness.adapter.addRepository("https://one.example")
            harness.adapter.removeRepository("https://one.example")
            harness.adapter.addRepository("https://one.example")
            assertFalse("https://one.example" in harness.adapter.state.value.repositorySuggestions.map { it.url })
            harness.adapter.removeRepository("https://one.example")
            assertFalse("https://one.example" in harness.adapter.state.value.repositorySuggestions.map { it.url })
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun removingFromTheAccountForgetsTheRepository() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val store = KeyValueExtensionRepositoryStore(kv)
        val portable = ShinsouRepository()
        val directory = FakeDirectory(emptyList())
        val harness = createHarness(kv, store, portable, accountRepositories = directory)

        try {
            harness.adapter.addRepository("https://added.example")
            harness.adapter.removeRepository("https://added.example", fromAccount = true)
            assertEquals(listOf("https://added.example"), directory.forgotten)
            assertTrue(portable.currentSnapshot.extensionRepositories.isEmpty())
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
        accountRepositories: AccountRepositoryDirectory? = null,
        suggestionScope: CoroutineScope? = null,
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
                accountRepositories = accountRepositories,
                suggestionScope = suggestionScope,
            ),
        )
    }

    private class FakeDirectory(initial: List<AccountRepository>) : AccountRepositoryDirectory {
        val flow = MutableStateFlow(initial)
        val forgotten = mutableListOf<String>()
        override val repositories: StateFlow<List<AccountRepository>> = flow

        override suspend fun forget(url: String) {
            forgotten += url
        }
    }

    private data class Harness(
        val client: HttpClient,
        val adapter: PluginBrowseAdapter,
    )
}
