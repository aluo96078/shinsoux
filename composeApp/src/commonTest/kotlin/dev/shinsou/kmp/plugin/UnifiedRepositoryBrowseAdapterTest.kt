package dev.shinsou.kmp.plugin

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
    fun addingV2IndexSkipsOpaqueReferenceOnlyShinsouPackage() = runTest {
        val indexUrl = "http://127.0.0.1:18081/index.json"
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
                }
                ,{
                  "id":"zh.wenku8.api","name":"Retired ShuYue package","version":"1.0.4","versionCode":5,
                  "lang":"zh","nsfw":false,"contract":"shuyue",
                  "runtime":"reviewed-shuyue-adapter-v2","contentType":"novel",
                  "scriptUrl":"plugins/zh.wenku8.api.js","sha256":"${"2".repeat(64)}","byteSize":1,
                  "sidecarUrl":"sidecars/zh.wenku8.api.json",
                  "sources":[{"sourceId":"zh.wenku8.api","name":"Retired","lang":"zh",
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
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
        )

        try {
            val added = requireNotNull(adapter.addRepository(indexUrl))
            val extensions = adapter.state.value.extensions
            assertTrue(extensions.any { it.id == "manga.v2" })
            // ShuYue-contract rows are skipped, not turned into an error or a browse row.
            assertTrue(extensions.none { it.id == "zh.wenku8.api" })
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
    fun productionIndexKeepsAllGenericPackagesAndSkipsShuYueRows() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val body = productionLikeV2Index()
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
        val store = KeyValueExtensionRepositoryStore(keyValues)
        val adapter = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = store,
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
        )

        try {
            store.put(ExtensionRepository("https://repo.example", "Shinsou Community Plugins"))
            adapter.refresh()
            // Retired ShuYue-contract rows never become browse extensions; every generic package does.
            assertEquals(14, adapter.state.value.extensions.size)
            assertTrue(adapter.state.value.extensions.none { it.id.startsWith("zh.") })
            assertEquals(null, keyValues.getString("plugin.shuyue.v2.repository-urls"))
            val added = requireNotNull(adapter.addRepository(indexUrl))
            assertEquals("https://repo.example", added.id)
            assertEquals("Shinsou Community Plugins", added.name)
            assertEquals(1, adapter.state.value.repositories.size)
            assertEquals(14, adapter.state.value.extensions.size)
            assertEquals(listOf("https://repo.example"), store.list().map { it.baseUrl })

            adapter.refresh()
            assertEquals(14, adapter.state.value.extensions.size)

            // A fresh adapter instance reconstructs the package list from durable repository state.
            val restarted = PluginBrowseAdapter(
                manager = manager,
                repositoryClient = repositoryClient,
                repositoryStore = store,
                pluginStorage = storage,
                keyValueStore = keyValues,
                trustStore = KeyValuePluginTrustStore(keyValues),
            )
            restarted.refresh()
            assertEquals(1, restarted.state.value.repositories.size)
            assertEquals(14, restarted.state.value.extensions.size)

            adapter.removeRepository(added.id)
            assertTrue(adapter.state.value.repositories.isEmpty())
            assertTrue(store.list().isEmpty())
        } finally {
            manager.close()
            http.close()
        }
    }

    private fun productionLikeV2Index(): String = buildString {
        append("""
            {"format":"shinsou-extension-v2","contractVersion":2,"packages":[
        """.trimIndent())
        repeat(14) { index ->
            if (index > 0) append(',')
            append("""
                {"id":"fixture.shinsou.$index","name":"Fixture $index","version":"1.0.0",
                 "versionCode":1,"lang":"zh","nsfw":false,"contract":"shinsou",
                 "runtime":"legacy-shinsou-adapter-v2",
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
