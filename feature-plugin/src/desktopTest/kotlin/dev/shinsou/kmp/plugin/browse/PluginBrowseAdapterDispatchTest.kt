package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.crypto.Sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.model.InstalledPluginMetadata
import dev.shinsou.kmp.plugin.model.PluginManifest
import dev.shinsou.kmp.plugin.model.PluginRuntimePermission
import dev.shinsou.kmp.plugin.model.StoredPlugin
import dev.shinsou.kmp.plugin.network.PluginHttpResponse
import dev.shinsou.kmp.plugin.network.PluginHttpTransport
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.repository.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.repository.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.runtime.NoopScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.runtime.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.storage.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.storage.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.storage.PluginPackageStore
import dev.shinsou.kmp.plugin.trust.PluginVerifier
import dev.shinsou.kmp.plugin.trust.RepositoryTrustPolicies

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class PluginBrowseAdapterDispatchTest {
    @Test
    fun pendingGrantReviewReadsPackageStoreOffCallerDispatcher() = runTest {
        val script = "var source = {};".encodeToByteArray()
        val digest = Sha256.hex(script)
        val stored = StoredPlugin(
            metadata = InstalledPluginMetadata(
                manifest = PluginManifest(
                    id = "dispatch.test",
                    name = "Dispatch test",
                    lang = "en",
                    version = "1.0.0",
                    script = "dispatch.test.js",
                    signature = digest,
                    runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                ),
                repositoryBaseUrl = "https://repo.example",
                installedSha256 = digest,
            ),
            scriptBytes = script,
        )
        val packageStore = ThreadRecordingPackageStore(stored)
        val keyValues = InMemoryPluginKeyValueStore()
        val trustStore = KeyValuePluginTrustStore(keyValues)
        val storage = KeyValuePluginStorage(keyValues)
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine { error("Network access is not expected") }),
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = packageStore,
            verifier = PluginVerifier(trustStore),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage,
                ),
                storage = storage,
            ),
            executionTierGate = null,
        )
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = trustStore,
            packageSecurity = null,
        )

        newSingleThreadContext("adapter-caller").use { callerDispatcher ->
            withContext(callerDispatcher) {
                val caller = Thread.currentThread().name
                adapter.pendingPluginEventGrantReview("dispatch.test")
                assertNotNull(packageStore.getThreadName)
                assertNotEquals(caller, packageStore.getThreadName)
            }
        }
    }
}

private class ThreadRecordingPackageStore(
    private val stored: StoredPlugin,
) : PluginPackageStore {
    @Volatile
    var getThreadName: String? = null
        private set

    override suspend fun list(): List<StoredPlugin> = listOf(stored)

    override suspend fun get(pluginId: String): StoredPlugin? {
        getThreadName = Thread.currentThread().name
        return stored.takeIf { it.manifest.id == pluginId }
    }

    override suspend fun put(plugin: StoredPlugin) = Unit

    override suspend fun remove(pluginId: String) = Unit
}
