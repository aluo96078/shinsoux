package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryIndexLoader
import dev.shinsou.kmp.plugin.shuyue.KtorShuYueRepositoryTransport
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
import kotlin.test.assertFails
import kotlin.test.assertTrue

class UnifiedRepositoryBrowseAdapterTest {
    @Test
    fun unifiedShinsouPartitionSurvivesUnavailablePinnedShuYueTransport() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val baseUrl = "https://repo.example"
        val body = """
            {"format":"shinsou-unified-v1","shinsou":[
              {"id":"manga","name":"Manga","version":"1.0.0","versionCode":1,"lang":"zh",
               "scriptUrl":"manga.js","sources":[{"name":"Manga","lang":"zh","id":1,"baseUrl":"https://manga.example"}]}
            ],"shuyue":[
              {"id":"zh.wenku8.api","name":"輕小說文庫","version":"1.0.5","versionCode":6,"lang":"zh",
               "scriptUrl":"wenku8-api.js","type":"novel",
               "sources":[{"id":"zh.wenku8.api","name":"輕小說文庫","lang":"zh","baseUrl":"https://wenku8-relay.mewx.org/","type":"novel"}]}
            ]}
        """.trimIndent()
        val keyValues = InMemoryPluginKeyValueStore().also {
            it.putString("plugin.shuyue.v2.repository-urls", indexUrl)
        }
        val repositoryStore = KeyValueExtensionRepositoryStore(keyValues).also {
            it.put(ExtensionRepository(baseUrl, "Unified"))
            it.select(baseUrl)
        }
        val storage = KeyValuePluginStorage(keyValues)
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/index.json") -> respond(body, HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"Unified"}}""",
                    HttpStatusCode.OK,
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val repositoryClient = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage = storage,
                ),
                storage = storage,
            ),
        )
        val loader = ShuYueRepositoryIndexLoader(
            transport = KtorShuYueRepositoryTransport(
                client = HttpClient(MockEngine { error("Unbound HTTP must not be used") }),
            ),
            limits = ShuYueRepositoryLimits(allowedArtifactOrigins = setOf(baseUrl)),
        )
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = repositoryStore,
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            reviewedShuYueRepositoryLoaderV2 = loader,
            reviewedShuYueRepositoryLocationV2 = ShuYueRepositoryLocation.IndexUrl(indexUrl),
        )

        try {
            adapter.refresh()
            assertEquals(null, adapter.state.value.errorMessage)
            assertTrue(adapter.state.value.extensions.any { it.id == "manga" && !it.reviewedShuYueV2 })
            assertTrue(adapter.state.value.extensions.none { it.reviewedShuYueV2 })
            assertEquals(1, adapter.state.value.repositories.size)
        } finally {
            manager.close()
            http.close()
        }
    }

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
        val repositoryClient = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
                  "runtime":"legacy-shinsou-adapter-v2","contentKinds":["IMAGE_SEQUENCE"],
                  "capabilities":["CATALOGUE","CONTENT"],"runtimePermissions":["EXECUTE_SCRIPT"],
                  "scriptUrl":"plugins/manga.v2.js","sha256":"${"0".repeat(64)}","byteSize":1,
                  "sidecarUrl":"sidecars/manga.v2.json",
                  "requestedHostPermissions":[],
                  "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
                  "sources":[{"sourceId":"123456","legacyLongId":"123456","name":"Manga V2","lang":"zh","baseUrl":"https://manga.example","contentKinds":["IMAGE_SEQUENCE"],"requestOrigins":[],"credentialOrigins":[],"contentOrigins":[],"browserSessionOrigins":[]}]
                },
                {
                  "id":"example.login","name":"Example Login","version":"0.1.0","versionCode":1,
                  "lang":"all","nsfw":false,"contract":"shinsou",
                  "runtime":"legacy-shinsou-adapter-v2","contentType":"manga","contentKinds":["IMAGE_SEQUENCE"],
                  "capabilities":["CATALOGUE","CONTENT"],"runtimePermissions":["EXECUTE_SCRIPT"],
                  "scriptUrl":"plugins/example.login.js","sha256":"${"1".repeat(64)}","byteSize":1,
                  "sidecarUrl":"sidecars/example.login.json","installable":false,"referenceOnly":true,
                  "requestedHostPermissions":[],
                  "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
                  "sources":[{"sourceId":"example.login","legacyLongId":null,"name":"Example Login","lang":"all","baseUrl":"https://example.com","contentKinds":["IMAGE_SEQUENCE"],"requestOrigins":[],"credentialOrigins":[],"contentOrigins":[],"browserSessionOrigins":[]}]
                },
                {
                  "id":"zh.wenku8.api","name":"輕小說文庫","version":"1.0.4","versionCode":5,
                  "lang":"zh","nsfw":false,"contract":"shuyue",
                  "runtime":"reviewed-shuyue-adapter-v2","contentType":"novel",
                  "scriptUrl":"plugins/zh.wenku8.api.js","sha256":"$reviewedDigest","byteSize":1,
                  "sidecarUrl":"sidecars/zh.wenku8.api.json",
                  "capabilities":["BROWSE","SEARCH","LATEST","METADATA","UNITS","CONTENT","LOGIN","FAVORITE"],
                  "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                    "required":["command.auth.login.request"],"optional":[]},
                  "requestedHostPermissions":["REQUEST_LOGIN_UI"],
                  "sources":[{"sourceId":"zh.wenku8.api","name":"輕小說文庫","lang":"zh",
                    "baseUrl":"https://wenku8-relay.mewx.org/"}]
                },
                {
                  "id":"zh.bilimanga","name":"嗶哩輕小說／漫畫（Linovelib + BiliManga）",
                  "version":"1.5.2","versionCode":8,"lang":"zh","nsfw":true,
                  "contract":"shuyue","runtime":"reviewed-shuyue-adapter-v2","contentType":"both",
                  "scriptUrl":"plugins/zh.bilimanga.js",
                  "sha256":"961c4ee367bba45035825f38dc64fefad5431ddfbc548afd04025c4adaf40a99",
                  "byteSize":44415,"sidecarUrl":"sidecars/zh.bilimanga.json",
                  "capabilities":["CATALOGUE","LATEST","BROWSE","METADATA","UNITS","CONTENT","SEARCH","LOGIN"],
                  "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                    "required":["command.auth.login.request"],"optional":[]},
                  "requestedHostPermissions":["REQUEST_LOGIN_UI"],"installable":true,
                  "sources":[
                    {"sourceId":"zh.bilimanga.novel","name":"嗶哩輕小說（Linovelib）","lang":"zh",
                     "baseUrl":"https://tw.linovelib.com","contentType":"novel"},
                    {"sourceId":"zh.bilimanga.manga","name":"嗶哩漫畫（BiliManga）","lang":"zh",
                     "baseUrl":"https://www.bilimanga.net","contentType":"manga"}
                  ]
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
        val repositoryClient = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            allowInsecureDeveloperHttp = true,
        )
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
            assertTrue(extensions.any { it.id == "zh.bilimanga" && it.reviewedShuYueV2 })
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

    @Test
    fun productionIndexKeepsAllGenericPackagesAndCommitsUnifiedRepositoryAtomically() = runTest {
        val indexUrl = "https://repo.example/index.json"
        var body = productionLikeV2Index()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"Shinsou Community Plugins"}}""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/index.json") -> respond(body, HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val repositoryClient = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
            limits = ShuYueRepositoryLimits(allowedArtifactOrigins = setOf("https://repo.example")),
        )
        val store = KeyValueExtensionRepositoryStore(keyValues)
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = store,
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            reviewedShuYueRepositoryLoaderV2 = loader,
            reviewedShuYueRepositoryLocationV2 = ShuYueRepositoryLocation.IndexUrl(indexUrl),
        )

        try {
            // A user-configured generic record also discovers its reviewed partition.
            store.put(ExtensionRepository("https://repo.example", "Shinsou Community Plugins"))
            adapter.refresh()
            assertEquals(3, adapter.state.value.extensions.count { it.reviewedShuYueV2 })
            assertEquals(null, keyValues.getString("plugin.shuyue.v2.repository-urls"))
            val added = requireNotNull(adapter.addRepository(indexUrl))
            assertEquals("https://repo.example", added.id)
            assertEquals("Shinsou Community Plugins", added.name)
            assertEquals(1, adapter.state.value.repositories.size)
            assertEquals(14, adapter.state.value.extensions.count { !it.reviewedShuYueV2 })
            assertEquals(
                setOf("zh.bilimanga", "zh.wenku8.api", "zh.biquge.tw"),
                adapter.state.value.extensions.filter { it.reviewedShuYueV2 }.map { it.id }.toSet(),
            )
            assertEquals(listOf("https://repo.example"), store.list().map { it.baseUrl })

            adapter.refresh()
            assertEquals(14, adapter.state.value.extensions.count { !it.reviewedShuYueV2 })
            assertEquals(3, adapter.state.value.extensions.count { it.reviewedShuYueV2 })

            // A fresh adapter instance must reconstruct both halves from durable repository and
            // reviewed-URL state; the generic package list must not depend on this instance's
            // in-memory descriptor cache.
            keyValues.remove("plugin.shuyue.v2.repository-urls")
            val restarted = PluginBrowseAdapter(
                manager = manager,
                repositoryClient = repositoryClient,
                repositoryStore = store,
                pluginStorage = storage,
                keyValueStore = keyValues,
                trustStore = KeyValuePluginTrustStore(keyValues),
                reviewedShuYueRepositoryLoaderV2 = loader,
                reviewedShuYueRepositoryLocationV2 = ShuYueRepositoryLocation.IndexUrl(indexUrl),
            )
            restarted.refresh()
            assertEquals(1, restarted.state.value.repositories.size)
            assertEquals(14, restarted.state.value.extensions.count { !it.reviewedShuYueV2 })
            assertEquals(3, restarted.state.value.extensions.count { it.reviewedShuYueV2 })

            adapter.removeRepository(added.id)
            assertTrue(adapter.state.value.repositories.isEmpty())
            assertTrue(store.list().isEmpty())

            // A repository with a valid reviewed half but a malformed generic half must not be
            // committed as a ShuYue-only source. A plain ShuYue repository (without any shinsou
            // package) remains a valid input and is covered by the loader's existing tests.
            body = productionLikeV2Index(malformedGeneric = true)
            assertFails { adapter.addRepository(indexUrl) }
            assertTrue(store.list().isEmpty())
            assertTrue(adapter.state.value.repositories.isEmpty())
            assertEquals(null, keyValues.getString("plugin.shuyue.v2.repository-urls"))
        } finally {
            manager.close()
            http.close()
        }
    }

    private fun productionLikeV2Index(malformedGeneric: Boolean = false): String = buildString {
        append("""
            {"format":"shinsou-extension-v2","contractVersion":2,"packages":[
        """.trimIndent())
        repeat(14) { index ->
            if (index > 0) append(',')
            append("""
                {"id":"fixture.shinsou.$index","name":"Fixture $index","version":"1.0.0",
                 "versionCode":1,"lang":"zh","nsfw":false,"contract":"shinsou",
                 ${if (malformedGeneric && index == 0) "" else "\"runtime\":\"legacy-shinsou-adapter-v2\","}
                 "contentKinds":["IMAGE_SEQUENCE"],"capabilities":["CATALOGUE","CONTENT"],
                 "runtimePermissions":["EXECUTE_SCRIPT"],"scriptUrl":"plugins/fixture-$index.js",
                 "sha256":"${"0".repeat(64)}","byteSize":1,"sidecarUrl":"sidecars/fixture-$index.json",
                 "requestedHostPermissions":[],"systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
                 "sources":[{"sourceId":"${100L + index}","legacyLongId":"${100L + index}","name":"Fixture source $index","lang":"zh","baseUrl":"https://source-$index.example"}]}
            """.trimIndent())
        }
        append(',')
        append("""
            {"id":"zh.bilimanga","name":"嗶哩輕小說（Linovelib）","version":"1.6.0","versionCode":12,"lang":"zh","nsfw":true,
             "contract":"shuyue","runtime":"reviewed-shuyue-adapter-v2","contentType":"novel","contentKinds":["PLAIN_TEXT"],
             "scriptUrl":"plugins/zh.bilimanga.js","sha256":"f740eb9fb1da98774f32aa8a555829a828ea799fc6987781ad594a4a6eceb0f5","byteSize":29546,
             "sidecarUrl":"sidecars/zh.bilimanga.json",
             "capabilities":["CATALOGUE","LATEST","BROWSE","METADATA","UNITS","CONTENT","SEARCH","LOGIN"],"runtimePermissions":["EXECUTE_SCRIPT","NETWORK","COOKIE_STORAGE","CREDENTIAL_ACCESS","LOGIN_PROMPT","BROWSER_CHALLENGE"],
             "requestedHostPermissions":["REQUEST_LOGIN_UI"],"systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":["command.auth.login.request"],"optional":[]},
             "sources":[{"sourceId":"zh.bilimanga.novel","name":"嗶哩輕小說（Linovelib）","lang":"zh","baseUrl":"https://tw.linovelib.com","contentType":"novel"}]}
        """.trimIndent())
        append(',')
        append("""
            {"id":"zh.wenku8.api","name":"輕小說文庫","version":"1.0.5","versionCode":6,"lang":"zh","nsfw":false,
             "contract":"shuyue","runtime":"reviewed-shuyue-adapter-v2","contentType":"novel","contentKinds":["PLAIN_TEXT"],
             "scriptUrl":"plugins/zh.wenku8.api.js","sha256":"75e67a5937b9a93956f71e1f97f8738fbdabce6b7e7090c90779479e32cae56c","byteSize":33589,
             "sidecarUrl":"sidecars/zh.wenku8.api.json",
             "capabilities":["BROWSE","SEARCH","LATEST","METADATA","UNITS","CONTENT","LOGIN"],"runtimePermissions":["EXECUTE_SCRIPT","NETWORK","COOKIE_STORAGE","CREDENTIAL_ACCESS","LOGIN_PROMPT"],
             "requestedHostPermissions":["REQUEST_LOGIN_UI"],"systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":["command.auth.login.request"],"optional":[]},
             "sources":[{"sourceId":"zh.wenku8.api","name":"輕小說文庫","lang":"zh","baseUrl":"https://wenku8-relay.mewx.org/"}]}
        """.trimIndent())
        append(',')
        append("""
            {"id":"zh.biquge.tw","name":"筆趣閣","version":"1.0.3","versionCode":4,"lang":"zh","nsfw":false,
             "contract":"shuyue","runtime":"reviewed-shuyue-adapter-v2","contentType":"novel","contentKinds":["PLAIN_TEXT"],
             "scriptUrl":"plugins/zh.biquge.tw.js","sha256":"74a961995aae9bef40444a819011e3b7702fcce6ce179fbd8e1ff6c733468303","byteSize":23631,
             "sidecarUrl":"sidecars/zh.biquge.tw.json",
             "capabilities":["BROWSE","SEARCH","LATEST","METADATA","UNITS","CONTENT"],"runtimePermissions":["EXECUTE_SCRIPT","NETWORK","BROWSER_CHALLENGE"],
             "requestedHostPermissions":[],"systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
             "sources":[{"sourceId":"zh.biquge.tw","name":"筆趣閣","lang":"zh","baseUrl":"https://www.biquge.tw"}]}
        """.trimIndent())
        append("]}")
    }
}
