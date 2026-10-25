package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
