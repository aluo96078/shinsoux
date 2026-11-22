package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.ExactPluginSourceTarget
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.shinsou.kmp.plugin.manager.PluginLoginRequestCoordinator
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.network.PluginHttpResponse
import dev.shinsou.kmp.plugin.network.PluginHttpTransport
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.repository.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.repository.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.runtime.NoopScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.runtime.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.storage.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.storage.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.storage.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.trust.PluginVerifier
import dev.shinsou.kmp.plugin.trust.RepositoryTrustPolicies

class PluginUiAdapterLifecycleTest {
    @Test
    fun backgroundTransitionKeepsPresentedLoginEventAndDropsLaterEvents() = runTest {
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val trust = KeyValuePluginTrustStore(keys)
        val http = HttpClient(MockEngine { error("Network access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(trust),
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
        val loginRequests = PluginLoginRequestCoordinator()
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keys),
            pluginStorage = storage,
            keyValueStore = keys,
            trustStore = trust,
            loginRequestCoordinator = loginRequests,
            packageSecurity = null,
        )

        try {
            assertTrue(loginRequests.requestEvent("op-1", target("pkg.one", 7), 7, "One", null))
            assertTrue(loginRequests.requestEvent("op-2", target("pkg.two", 8), 8, "Two", null))

            browse.setPluginUiAvailable(false)

            assertEquals(listOf("op-1"), browse.loginRequests.value.map { it.eventId })
        } finally {
            manager.close()
            http.close()
        }
    }

    private fun target(packageId: String, sourceId: Long) = ExactPluginSourceTarget(
        PluginArtifactIdentity(packageId, "1.0.0", 1, "a".repeat(64)),
        SourceKey(packageId = packageId, sourceId = sourceId.toString(), legacyLongId = sourceId),
    )
}
