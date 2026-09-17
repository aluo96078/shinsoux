package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Locks the admission contract to the currently published Shinsou corpus.
 *
 * This is a small, in-repository snapshot of the 14 installable `contract: shinsou` entries in
 * shinsou_plugin/index.json and their matching sidecars. The published sidecars intentionally
 * omit the package-level runtime (they predate that field); the index still binds every package
 * to legacy-shinsou-adapter-v2. Keeping this snapshot in the app repository makes the regression
 * test runnable in CI without depending on a sibling checkout or live GitHub availability.
 */
class OfficialShinsouCorpusAdmissionTest {
    @Test
    fun manhuarenPublishedSourceUsesExactCanonicalBaseUrl() {
        assertEquals(
            "https://www.manhuaren.com",
            CORPUS.single { it.id == "zh.manhuaren" }.baseUrl,
        )
    }

    @Test
    fun publishedFourteenShinsouPackagesFetchAndVerifyWithRuntimeOmittedFromSidecars() = runTest {
        val requests = mutableListOf<String>()
        val indexBody = corpusIndexJson()
        val sidecars = CORPUS.associate { packageInfo ->
            "sidecars/${packageInfo.id}.json" to packageInfo.sidecarJson()
        }
        assertEquals(14, CORPUS.size)
        assertTrue(sidecars.values.all {
            "runtime" !in PluginJson.parseToJsonElement(it).jsonObject
        })
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("refs/heads/master/")
            requests += path
            when {
                path == "index.json" -> respond(indexBody, HttpStatusCode.OK)
                path in sidecars -> respond(sidecars.getValue(path), HttpStatusCode.OK)
                else -> error("Unexpected corpus request: $path")
            }
        })
        try {
            val client = ExtensionRepositoryClient(
                http,
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            )
            val index = assertIs<RepositoryIndex.Combined>(
                client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL),
            )
            assertEquals(CORPUS.map { it.id }, index.plugins.map { it.id })
            assertTrue(index.shuyue.isEmpty())

            index.plugins.forEach { entry ->
                // verifyPluginV2Sidecar performs the complete package/source parity check:
                // digest, size, source identity, content, events, host permissions, and runtime
                // permissions. The official compatibility exception applies only to the omitted
                // sidecar runtime; an explicit wrong/null/blank value remains rejected elsewhere.
                client.verifyPluginV2Sidecar(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, entry)
                assertEquals("legacy-shinsou-adapter-v2", entry.runtime, entry.id)
                assertEquals("shinsou", entry.contract, entry.id)
            }

            assertEquals(
                listOf("index.json") + CORPUS.map { "sidecars/${it.id}.json" },
                requests,
            )
        } finally {
            http.close()
        }
    }

    private fun corpusIndexJson(): String = buildString {
        append("""
            {
              "format":"shinsou-extension-v2",
              "contractVersion":2,
              "packages":[
        """.trimIndent())
        CORPUS.forEachIndexed { index, packageInfo ->
            if (index != 0) append(',')
            append(packageInfo.indexJson())
        }
        append("]}")
    }

    private data class CorpusPackage(
        val id: String,
        val name: String,
        val version: String,
        val versionCode: Int,
        val lang: String,
        val nsfw: Boolean,
        val sourceId: String,
        val baseUrl: String,
        val sha256: String,
        val byteSize: Int,
        val capabilities: List<String>,
        val runtimePermissions: List<String>,
        val hostPermissions: List<String> = emptyList(),
        val requiredEvents: List<String> = emptyList(),
        val optionalEvents: List<String> = emptyList(),
        val browserSessionOrigins: List<String> = emptyList(),
    ) {
        private val scriptUrl get() = "plugins/$id.js"
        private val sidecarUrl get() = "sidecars/$id.json"

        fun indexJson(): String = """
            {
              "id":"$id","name":"$name","version":"$version","versionCode":$versionCode,
              "lang":"$lang","nsfw":$nsfw,"contract":"shinsou",
              "runtime":"legacy-shinsou-adapter-v2","contentType":"manga",
              "contentKinds":["IMAGE_SEQUENCE"],"capabilities":${capabilities.jsonArray()},
              "runtimePermissions":${runtimePermissions.jsonArray()},"scriptUrl":"$scriptUrl",
              "sidecarUrl":"$sidecarUrl","sha256":"$sha256","byteSize":$byteSize,
              "requestedHostPermissions":${hostPermissions.jsonArray()},
              "systemEvents":${eventsJson()},
              "sources":[{"sourceId":"$sourceId","legacyLongId":"$sourceId",
                "name":"$name","lang":"$lang","baseUrl":"$baseUrl"${
                    if (browserSessionOrigins.isEmpty()) "" else
                        ",\"browserSessionOrigins\":${browserSessionOrigins.jsonArray()}"
                }}]
            }
        """.trimIndent()

        fun sidecarJson(): String = """
            {
              "format":"shinsou-extension-sidecar-v2","contractVersion":2,
              "packageId":"$id","name":"$name","version":"$version","versionCode":$versionCode,
              "contract":"shinsou","lang":"$lang","nsfw":$nsfw,"installable":true,
              "artifact":{"scriptUrl":"$scriptUrl","sha256":"$sha256","byteSize":$byteSize},
              "content":{"contract":"extension-content-v2","contractVersion":2,
                "type":"manga","kinds":["IMAGE_SEQUENCE"]},
              "capabilities":${capabilities.jsonArray()},"systemEvents":${eventsJson()},
              "requestedHostPermissions":${hostPermissions.jsonArray()},
              "runtimePermissions":${runtimePermissions.jsonArray()},
              "sources":[{"sourceKey":{"contractVersion":2,"packageId":"$id",
                "sourceId":"$sourceId","legacyLongId":"$sourceId"},
                "sourceId":"$sourceId","name":"$name","lang":"$lang","baseUrl":"$baseUrl",
                "capabilities":${capabilities.jsonArray()},"contentKinds":["IMAGE_SEQUENCE"],
                "systemEvents":${eventsJson()},
                "requestedHostPermissions":${hostPermissions.jsonArray()},
                "runtimePermissions":${runtimePermissions.jsonArray()}${
                    if (browserSessionOrigins.isEmpty()) "" else
                        ",\"browserSessionOrigins\":${browserSessionOrigins.jsonArray()}"
                }}]
            }
        """.trimIndent()

        private fun eventsJson(): String =
            "{\"protocol\":\"dev.shinsou.system\",\"minVersion\":1,\"maxVersion\":1," +
                "\"required\":${requiredEvents.jsonArray()},\"optional\":${optionalEvents.jsonArray()}}"
    }

    private companion object {
        fun List<String>.jsonArray(): String = if (isEmpty()) {
            "[]"
        } else {
            joinToString("\",\"", "[\"", "\"]")
        }

        val COMMON_CAPABILITIES = listOf(
            "CATALOGUE", "LATEST", "BROWSE", "METADATA", "UNITS", "CONTENT", "SEARCH",
        )
        val COMMON_PERMISSIONS = listOf("EXECUTE_SCRIPT", "NETWORK")
        val LOGIN_CAPABILITIES = COMMON_CAPABILITIES + "LOGIN"
        val LOGIN_PERMISSIONS = COMMON_PERMISSIONS + listOf(
            "COOKIE_STORAGE", "CREDENTIAL_ACCESS", "BROWSER_CHALLENGE",
        )

        val CORPUS = listOf(
            CorpusPackage("eh.ehentai", "E-Hentai", "1.1.9", 11, "all", true, "6912170", "https://e-hentai.org", "564695e574d630d1336941adf2e5da11785ff83001c05229ab71e11a54bda485", 32531, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("all.nhentai", "NHentai", "2.0.1", 3, "all", true, "7309872", "https://nhentai.net", "193d6d89e51fa252ad695f839fe54ef0ab0c154d3fb75718dae67fceaa874a8f", 19220, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.jinmantiantang", "禁漫天堂", "1.0.7", 8, "zh", true, "1817081", "https://18comic.vip", "f45a1485ac720d9a1963c7cecde874926c3e727f209c5534e04b49b544ebfb27", 21381, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.baozimh", "包子漫画", "1.0.6", 7, "zh", false, "4502917", "https://www.baozimh.com", "6dbafe5e7bc81fdf90a565e8a7f6dfd396de833cadfea6009530b95726e880e6", 19793, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.bika", "哔咔漫画", "1.0.12", 13, "zh", true, "8123456", "https://manhuabika.com", "cd09553432d85389de169017aeeadd776f539183f13598c2259b4fec738cbdf1", 35431, LOGIN_CAPABILITIES, LOGIN_PERMISSIONS, listOf("REQUEST_LOGIN_UI"), optionalEvents = listOf("command.auth.login.request"), browserSessionOrigins = listOf("https://picaapi.go2778.com")),
            CorpusPackage("zh.manhuagui", "漫画柜", "1.1.6", 8, "zh", true, "6301748", "https://tw.manhuagui.com", "a984f5e3594b57a821f56137d3817f69487eb35ddde53f8175fc648b8a626e53", 28351, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.komiic", "Komiic", "1.2.0", 4, "zh", true, "8104923", "https://komiic.com", "5a450bf1480675f9be5993188221e49fb350338217511eb8e61fe90e8b178731", 10676, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.wnacg", "紳士漫畫", "1.3.3", 7, "zh", true, "5209831", "https://www.wnacg.com", "a6710148c297694ce8f2c2c2345eb68b3cb758aadc22334ab067c8bd06af0765", 13788, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.mycomic", "MyComic", "1.0.0", 1, "zh", true, "9119537447562549661", "https://mycomic.com", "7bc6502b5b418c0d640f84adc1fd44a83f38fa533603591300798fe45d798ad0", 21121, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.dm5", "動漫屋", "1.4.0", 5, "zh", true, "3947628", "https://www.dm5.com", "947a6a2e75f3415432a45ac84ea50a50ff70ed26b0eb993419a56bad84850642", 21448, LOGIN_CAPABILITIES, LOGIN_PERMISSIONS, listOf("REQUEST_LOGIN_UI")),
            CorpusPackage("zh.manhuaren", "漫画人", "1.0.0", 1, "zh", false, "3616827811449702173", "https://www.manhuaren.com", "3d16db2fee1044211b83d11d3d9ab9daae4b52e0d98817527bc99028fb07baa1", 14587, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.mangacopy", "拷貝漫畫", "1.0.0", 1, "zh", true, "6696312508930833206", "https://www.mangacopy.com", "8da992d2eab8c5b1030b0c9c617e1447645ce8ac3442145760b63f28f62cd8cb", 41927, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("all.mangadex", "MangaDex", "1.2.0", 3, "all", true, "2499283", "https://mangadex.org", "320efd108ed7656cb00dd5270f4e54c67f08fc9e3c2af0c362f57af987c2e1f3", 25025, COMMON_CAPABILITIES, COMMON_PERMISSIONS),
            CorpusPackage("zh.bilimanga.manga", "嗶哩漫畫（BiliManga）", "1.0.0", 1, "zh", true, "7289707411592168382", "https://www.bilimanga.net", "849db61372e4c7cf9d7ca689fbc427c1440dabd10c017a548c242175c651bbdd", 29211, LOGIN_CAPABILITIES, LOGIN_PERMISSIONS, listOf("REQUEST_LOGIN_UI"), requiredEvents = listOf("command.auth.login.request")),
        )
    }
}
