@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginManagerMultiSourceTest {
    @Test
    fun installAndReloadCreateEveryDeclaredSourceWithoutImplicitExecutionChoice() = runTest {
        val engine = MockEngine { respond("var source={};", HttpStatusCode.OK) }
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val packageStore = KeyValuePluginPackageStore(keyValues)
        val verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues))
        val manager = PluginManager(
            ExtensionRepositoryClient(
                HttpClient(engine),
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore,
            verifier,
            NoopScriptPluginRuntimeFactory,
            environment(storage),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        val entry = PluginIndexEntry(
            id = "multi.fixture",
            name = "Multi fixture",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            scriptUrl = "multi.js",
            sources = listOf(
                SourceIndexEntry(
                    "Second",
                    "en",
                    202L,
                    "https://second.example",
                    canonicalSourceId = "source.second/exact",
                    contentKindsDeclared = false,
                ),
                SourceIndexEntry(
                    "First",
                    "en",
                    101L,
                    "https://first.example",
                    canonicalSourceId = "source.first/exact",
                    contentKindsDeclared = false,
                ),
                SourceIndexEntry(
                    "No V2 content",
                    "en",
                    303L,
                    "https://empty.example",
                    contentKinds = emptySet(),
                    contentKindsDeclared = true,
                    canonicalSourceId = "source.empty/exact",
                ),
            ),
            contentKinds = setOf("IMAGE_SEQUENCE"),
            runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
        )

        val compatibilityHandle = manager.install(ExtensionRepository("https://repo.example", "Repo"), entry)

        assertEquals(listOf(101L, 202L, 303L), manager.catalogueSources().map { it.id }.sorted())
        assertEquals("First", requireNotNull(manager.source(101)).name)
        assertEquals("Second", requireNotNull(manager.source(202)).name)
        assertFailsWith<ScriptRuntimeUnavailableException> { compatibilityHandle.getPopularManga(0) }
        val facade = requireNotNull(manager.extensionFacadeV2(entry.id))
        assertEquals(2, requireNotNull(manager.extensionPackageRuntimeV2(entry.id)).descriptor.sources.size)
        val firstKey = SourceKey(2, entry.id, "source.first/exact", 101L)
        val secondKey = SourceKey(2, entry.id, "source.second/exact", 202L)
        val emptyKey = SourceKey(2, entry.id, "source.empty/exact", 303L)
        assertEquals("First", requireNotNull(facade.source(firstKey)).descriptor.displayName)
        assertEquals("Second", requireNotNull(facade.source(secondKey)).descriptor.displayName)
        assertEquals(null, facade.source(emptyKey))
        assertEquals(null, facade.source(SourceKey.fromLegacy(entry.id, 101)))
        assertEquals(
            null,
            manager.contentNetworkScopeForSource(
                SourceKey(contractVersion = 2, packageId = entry.id, sourceId = "forged", legacyLongId = 101L),
            ),
        )

        val restarted = PluginManager(
            ExtensionRepositoryClient(
                HttpClient(engine),
                cacheToken = { 2L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            KeyValuePluginPackageStore(keyValues),
            verifier,
            NoopScriptPluginRuntimeFactory,
            environment(KeyValuePluginStorage(keyValues)),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        assertEquals(listOf(101L, 202L, 303L), restarted.loadInstalled().map { it.id }.sorted())
        val restartedFacade = requireNotNull(restarted.extensionFacadeV2(entry.id))
        assertEquals("First", requireNotNull(restartedFacade.source(firstKey)).descriptor.displayName)
        assertEquals("Second", requireNotNull(restartedFacade.source(secondKey)).descriptor.displayName)
        assertEquals(null, restartedFacade.source(emptyKey))
        assertEquals(null, restartedFacade.source(SourceKey.fromLegacy(entry.id, 101)))

        restarted.setPluginTrusted(entry.id, false)
        assertTrue(restarted.catalogueSources().isEmpty())
    }

    @Test
    fun legacyFactoryEntryRejectsAmbiguousMultiSourceManifest() = runTest {
        val manifest = PluginManifest(
            id = "multi.ambiguous",
            name = "Ambiguous",
            version = "1",
            versionCode = 1,
            lang = "all",
            script = "multi.js",
            signature = "",
            sources = listOf(
                SourceIndexEntry("One", "en", 1, "https://one.example"),
                SourceIndexEntry("Two", "en", 2, "https://two.example"),
            ),
        )
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())

        assertFailsWith<IllegalArgumentException> {
            NoopScriptPluginRuntimeFactory.create("", manifest, environment(storage))
        }
        val exact = NoopScriptPluginRuntimeFactory.createForSource(
            "",
            manifest,
            manifest.sources.orEmpty().last(),
            environment(storage),
        )
        assertEquals(2L, exact.id)
    }

    private fun environment(storage: PluginStorage): ScriptPluginEnvironment {
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        return ScriptPluginEnvironment(network, storage)
    }
}
