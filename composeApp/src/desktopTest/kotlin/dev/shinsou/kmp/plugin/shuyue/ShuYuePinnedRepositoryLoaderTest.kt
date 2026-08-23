@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

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
        assertEquals("b095b72ce4c59eed722be2dfa91a7729851634aae62a5b3881356c92f84a1090", Sha256.hex(indexBytes))
        val scriptUrls = listOf(
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8.js?v=1.6.14",
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8-api.js?v=1.0.4",
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/biquge-tw.js?v=1.0.3",
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
            listOf("wenku8.js?v=1.6.14", "wenku8-api.js?v=1.0.4", "biquge-tw.js?v=1.0.3"),
            loaded.entries.map { it.scriptUrl },
        )
        assertEquals(
            listOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw"),
            loaded.entries.flatMap { it.sources }.map { it.id },
        )
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8.js?v=1.6.14",
                "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/wenku8-api.js?v=1.0.4",
                "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/biquge-tw.js?v=1.0.3",
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
            "wenku8.js" to "5536b392476d59000770a15e2f759c3fb5f5d51b551c03ae42182c7eb5610b9e",
            "wenku8-api.js" to "aaa7875360a52dd3393288bbb4f1e85d38ddd6a42041a0e489d7585db8bb5996",
            "biquge-tw.js" to "74a961995aae9bef40444a819011e3b7702fcce6ce179fbd8e1ff6c733468303",
        )
        assertEquals(
            mapOf(
                "zh.wenku8" to expectedScriptDigests.getValue("wenku8.js"),
                "zh.wenku8.api" to expectedScriptDigests.getValue("wenku8-api.js"),
                "zh.biquge.tw" to expectedScriptDigests.getValue("biquge-tw.js"),
            ),
            ShuYueReviewedPluginCatalogV2.profiles
                .filterNot { it.v2IndexOnly }
                .associate { it.identity.packageId to it.identity.sha256 },
        )
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val admission = ShuYueReviewedPluginAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = ShuYueReviewedRuntimeFactoryV2 {
                error("Quarantine must not create a JavaScript runtime")
            },
        )
        loaded.entries.forEach { entry ->
            val path = "shuyue-plugin/${entry.scriptUrl.substringBefore('?')}"
            val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(path)).use { it.readBytes() }
            assertTrue(bytes.isNotEmpty(), path)
            assertEquals(expectedScriptDigests.getValue(path.substringAfterLast('/')), Sha256.hex(bytes), path)
            val artifact = loader.downloadScript(loaded, entry)
            assertEquals(ShuYueReviewStatusV2.REVIEWED, admission.quarantine(artifact).reviewStatus, path)
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
