package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryIndexLoader
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLimits
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLocation
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryResponse
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedRepositoryBrowseAdapterTest {
    @Test
    fun addingUnifiedIndexPublishesLegacyAndReviewedPackages() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val builtInUrl = "https://built-in.example/index.json"
        val body = """
            {"format":"shinsou-unified-v1","shinsou":[
              {"id":"manga","name":"Manga","version":"1.0.0","versionCode":1,"lang":"zh",
               "scriptUrl":"manga.js","sources":[{"name":"Manga","lang":"zh","id":1,"baseUrl":"https://manga.example"}]}
            ],"shuyue":[
              {"id":"zh.wenku8.api","name":"輕小說文庫","version":"1.0.4","versionCode":5,"lang":"zh",
               "scriptUrl":"wenku8-api.js","type":"novel",
               "sources":[{"id":"zh.wenku8.api","name":"輕小說文庫","lang":"zh","baseUrl":"https://wenku8-relay.mewx.org/","type":"novel"}]},
              {"id":"zh.biquge.tw","name":"筆趣閣","version":"1.0.3","versionCode":4,"lang":"zh",
               "scriptUrl":"biquge-tw.js","type":"novel",
               "sources":[{"id":"zh.biquge.tw","name":"筆趣閣","lang":"zh","baseUrl":"https://www.biquge.tw","type":"novel"}]}
            ]}
        """.trimIndent()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"Unified","website":"https://repo.example/"}}""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/index.json") -> respond(body, HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val repositoryClient = ExtensionRepositoryClient(http, cacheToken = { 1L })
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage = storage,
                ),
                storage = storage,
            ),
        )
        val loader = ShuYueRepositoryIndexLoader(
            transport = ShuYueRepositoryTransport { request ->
                assertTrue(request.url == indexUrl || request.url == builtInUrl)
                // The maintained GitHub source may be unavailable while a user LAN source is
                // healthy. Refreshing the latter must still publish its reviewed packages.
                if (request.url == builtInUrl) error("built-in repository unavailable")
                ShuYueRepositoryResponse(200, body.encodeToByteArray(), request.url)
            },
            limits = ShuYueRepositoryLimits(
                allowedArtifactOrigins = setOf("https://repo.example", "https://built-in.example"),
            ),
        )
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            reviewedShuYueRepositoryLoaderV2 = loader,
            reviewedShuYueRepositoryLocationV2 = ShuYueRepositoryLocation.IndexUrl(builtInUrl),
        )

        try {
            adapter.addRepository(indexUrl)
            assertTrue(adapter.state.value.extensions.any { it.id == "manga" && !it.reviewedShuYueV2 })
            assertEquals(
                listOf("zh.biquge.tw", "zh.wenku8.api"),
                adapter.state.value.extensions.filter { it.reviewedShuYueV2 }.map { it.id }.sorted(),
            )

            // Simulate an upgrade from the old reviewed-only build: its URL survives, but the
            // legacy repository row was never persisted. A normal refresh must repair that state
            // without requiring the user to delete and re-add the source.
            val store = KeyValueExtensionRepositoryStore(keyValues)
            store.remove("https://repo.example")
            store.select(null)
            // A previous build recorded this failed one-shot probe. The V2 parser repair uses a
            // new migration generation and must retry instead of preserving reviewed-only state.
            keyValues.putString("plugin.repositories.unified-reviewed-migration.v1", indexUrl)
            adapter.refresh()
            assertTrue(
                adapter.state.value.extensions.any { it.id == "manga" && !it.reviewedShuYueV2 },
                "repaired extensions=${adapter.state.value.extensions.map { it.id to it.reviewedShuYueV2 }} repositories=${adapter.state.value.repositories.map { it.id }} error=${adapter.state.value.errorMessage}",
            )
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun addingV2IndexSkipsOpaqueReferenceOnlyShinsouPackage() = runTest {
        val indexUrl = "http://127.0.0.1:18081/index.json"
        val reviewedDigest = "5a9d1ac0d8263629e82332a88b2a7ed4eb6efb857804a8ae6ae946b2eb23b627"
        val body = """
            {
              "format":"shinsou-extension-v2","contractVersion":2,
              "packages":[
                {
                  "id":"manga.v2","name":"Manga V2","version":"1.0.0","versionCode":1,
                  "lang":"zh","nsfw":false,"contract":"shinsou",
                  "scriptUrl":"plugins/manga.v2.js","sha256":"${"0".repeat(64)}","byteSize":1,
                  "sidecarUrl":"sidecars/manga.v2.json",
                  "sources":[{"sourceId":"123456","name":"Manga V2","lang":"zh","baseUrl":"https://manga.example"}]
                },
                {
                  "id":"example.login","name":"Example Login","version":"0.1.0","versionCode":1,
                  "lang":"all","nsfw":false,"contract":"shinsou",
                  "scriptUrl":"plugins/example.login.js","sha256":"${"1".repeat(64)}","byteSize":1,
                  "sidecarUrl":"sidecars/example.login.json","installable":false,"referenceOnly":true,
                  "sources":[{"sourceId":"example.login","name":"Example Login","lang":"all","baseUrl":"https://example.com"}]
                },
                {
                  "id":"zh.wenku8.api","name":"輕小說文庫","version":"1.0.4","versionCode":5,
                  "lang":"zh","nsfw":false,"contract":"shuyue","contentType":"novel",
                  "scriptUrl":"plugins/zh.wenku8.api.js","sha256":"$reviewedDigest","byteSize":1,
                  "sidecarUrl":"sidecars/zh.wenku8.api.json",
                  "capabilities":["BROWSE","SEARCH","LATEST","METADATA","UNITS","CONTENT","LOGIN","FAVORITE"],
                  "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                    "required":["command.auth.login.request"],"optional":[]},
                  "requestedHostPermissions":["REQUEST_LOGIN_UI"],
                  "sources":[{"sourceId":"zh.wenku8.api","name":"輕小說文庫","lang":"zh",
                    "baseUrl":"https://wenku8-relay.mewx.org/"}]
                }
              ]
            }
        """.trimIndent()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/index.json") -> respond(body, HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"V2 mixed"}}""",
                    HttpStatusCode.OK,
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val repositoryClient = ExtensionRepositoryClient(http, cacheToken = { 1L })
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage = storage,
                ),
                storage = storage,
            ),
        )
        val loader = ShuYueRepositoryIndexLoader(
            transport = ShuYueRepositoryTransport { request ->
                assertEquals(indexUrl, request.url)
                ShuYueRepositoryResponse(200, body.encodeToByteArray(), request.url)
            },
            limits = ShuYueRepositoryLimits(
                allowedArtifactOrigins = setOf("http://127.0.0.1:18081"),
            ),
        )
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            reviewedShuYueRepositoryLoaderV2 = loader,
            reviewedShuYueRepositoryLocationV2 = ShuYueRepositoryLocation.IndexUrl(indexUrl),
        )

        try {
            val added = requireNotNull(adapter.addRepository(indexUrl))
            val extensions = adapter.state.value.extensions
            assertTrue(extensions.any { it.id == "manga.v2" && !it.reviewedShuYueV2 })
            assertTrue(extensions.any { it.id == "zh.wenku8.api" && it.reviewedShuYueV2 })
            assertTrue(extensions.none { it.id == "example.login" })
            assertEquals("V2 mixed", added.name)
            assertEquals(added.id, adapter.state.value.selectedRepositoryId)

            adapter.selectRepository(null)
            adapter.selectRepository(added.id)
            assertEquals(added.id, adapter.state.value.selectedRepositoryId)
        } finally {
            manager.close()
            http.close()
        }
    }
}
