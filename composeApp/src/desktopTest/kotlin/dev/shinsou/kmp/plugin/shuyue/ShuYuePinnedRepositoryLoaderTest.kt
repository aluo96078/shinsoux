package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses pinned repository bytes and an inert transport without evaluating JavaScript. */
class ShuYuePinnedRepositoryLoaderTest {
    @Test
    fun pinnedIndexHasExactOpaqueIdsQueryVersionsAndFinalDirectoryResolution() = runTest {
        val indexUrl = "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/index.json"
        val indexBytes = requireNotNull(javaClass.classLoader.getResourceAsStream("shuyue-plugin/index.json"))
            .use { it.readBytes() }
        assertEquals("b6088b13548d2ec1f59e4a620fbf308ee67e97cf2b8734b2291c6a93725a7631", Sha256.hex(indexBytes))
        val scriptUrls = listOf(
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8.js?v=1.6.12",
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8-api.js?v=1.0.2",
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/biquge-tw.js?v=1.0.1",
        )
        val scriptResponses = scriptUrls.associateWith { url ->
            val resource = "shuyue-plugin/${url.substringAfterLast('/').substringBefore('?')}"
            val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)).use { it.readBytes() }
            ShuYueRepositoryResponse(status = 200, body = bytes, finalUrl = url)
        }
        val transport = LocalFixtureTransport(
            indexUrl,
            ShuYueRepositoryResponse(
                status = 200,
                body = indexBytes,
                finalUrl = indexUrl,
            ),
            scriptResponses,
        )
        val loader = ShuYueRepositoryIndexLoader(
            transport = transport,
            limits = ShuYueRepositoryLimits(
                allowedArtifactOrigins = setOf("https://raw.githubusercontent.com"),
            ),
        )

        val loaded = loader.load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        assertEquals(
            listOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw"),
            loaded.entries.map { it.id },
        )
        assertEquals(
            listOf("wenku8.js?v=1.6.12", "wenku8-api.js?v=1.0.2", "biquge-tw.js?v=1.0.1"),
            loaded.entries.map { it.scriptUrl },
        )
        assertEquals(
            listOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw"),
            loaded.entries.flatMap { it.sources }.map { it.id },
        )
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8.js?v=1.6.12",
                "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8-api.js?v=1.0.2",
                "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/biquge-tw.js?v=1.0.1",
            ),
            loaded.entries.map { it.resolvedScriptUrl },
        )
        assertEquals(
            listOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw"),
            loaded.entries.flatMap { it.sourceKeys }.map { it.sourceId },
        )
        assertEquals(
            loaded.entries.map { it.id },
            loaded.entries.flatMap { entry -> entry.sourceKeys.map { it.packageId } },
        )
        assertTrue(loaded.entries.flatMap { it.sourceKeys }.all { it.contractVersion == 2 })
        assertTrue(transport.requests.first().url == indexUrl)
        assertEquals(ShuYueRepositoryLimits.DEFAULT_MAX_INDEX_BYTES, transport.requests.first().maxBytes)
        assertTrue(transport.requests.all { it.allowedArtifactOrigins == setOf(ShuYueOrigin.parse("https://raw.githubusercontent.com")) })

        val expectedScriptDigests = mapOf(
            "wenku8.js" to "a77c9b81e4adcd86d6cb3b1126922345a1e69fd56658639188e6cf0e925655c3",
            "wenku8-api.js" to "89a9d3236dd7ea1655cad57ddeaca100cc1a8ff2b99acdcd06b8fd69cf13d7ce",
            "biquge-tw.js" to "2dd28789d8be4d3d5ac88926bc1e143e5b46198b3fa579f46baf510f2591de78",
        )
        loaded.entries.forEach { entry ->
            val path = "shuyue-plugin/${entry.scriptUrl.substringBefore('?')}"
            val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(path)).use { it.readBytes() }
            assertTrue(bytes.isNotEmpty(), path)
            assertEquals(expectedScriptDigests.getValue(path.substringAfterLast('/')), Sha256.hex(bytes), path)
            val artifact = loader.downloadScript(loaded, entry)
            assertEquals(expectedScriptDigests.getValue(path.substringAfterLast('/')), artifact.sha256, path)
            assertEquals(entry.id, artifact.metadata.packageId)
            assertEquals(entry.version, artifact.metadata.version)
            assertEquals(entry.versionCode, artifact.metadata.versionCode)
            assertEquals(entry.scriptUrl, artifact.metadata.scriptUrl)
            assertEquals(entry.resolvedScriptUrl, artifact.metadata.resolvedUrl)
            assertEquals(entry.resolvedScriptUrl, artifact.metadata.downloadedFinalUrl)
        }
        assertEquals(4, transport.requests.size)
    }

    private class LocalFixtureTransport(
        private val expectedUrl: String,
        private val response: ShuYueRepositoryResponse,
        private val scriptResponses: Map<String, ShuYueRepositoryResponse>,
    ) : ShuYueRepositoryTransport {
        val requests = mutableListOf<ShuYueRepositoryRequest>()

        override suspend fun execute(request: ShuYueRepositoryRequest): ShuYueRepositoryResponse {
            requests += request
            assertTrue(request.allowedArtifactOrigins == setOf(ShuYueOrigin.parse("https://raw.githubusercontent.com")))
            return if (request.url == expectedUrl) response else requireNotNull(scriptResponses[request.url])
        }
    }
}
