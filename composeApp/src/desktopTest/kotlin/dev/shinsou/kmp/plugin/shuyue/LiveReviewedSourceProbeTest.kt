@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.PluginBrowseAdapter
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginHostResolution
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginVerifier
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.createPlatformPinnedPluginHttpTransport
import dev.shinsou.kmp.plugin.createPlatformPluginHostResolver
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.UnitContentPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in end-to-end probe of reviewed ShuYue browse metadata and the native cover fetch plane. */
class LiveReviewedSourceProbeTest {
    @Test
    fun publicBrowseCoversLoadThroughExactReviewedSourceScope() {
        if (System.getenv("SHINSOU_LIVE_REVIEWED_SOURCE_PROBE") != "1") return
        runBlocking {
            val selected = System.getenv("SHINSOU_LIVE_REVIEWED_SOURCE_IDS")
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                ?: TARGETS.map { it.sourceId }.toSet()
            val failures = TARGETS.filter { it.sourceId in selected }.mapNotNull { target ->
                runCatching { probe(target) }.exceptionOrNull()?.let { target.sourceId to it }
            }
            check(failures.isEmpty()) {
                failures.joinToString("\n") { (id, error) -> "$id: ${error.stackTraceToString()}" }
            }
        }
    }

    private suspend fun probe(target: Target) {
        val scriptFile = reviewedPluginDirectory().resolve(target.scriptName)
        val bytes = scriptFile.readBytes()
        val digest = Sha256.hex(bytes)
        val profile = ShuYueReviewedPluginCatalogV2.profiles.singleOrNull {
            it.identity.packageId == target.packageId && it.identity.sha256 == digest &&
                target.sourceId in it.sourceIds
        } ?: error("Official script has no exact reviewed profile: $scriptFile sha256=$digest")
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val real = requireNotNull(createPlatformPinnedPluginHttpTransport())
        val requests = CopyOnWriteArrayList<PluginHttpRequest>()
        val recording = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                error("Pinned transport is required")
            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse {
                requests += request
                return real.executeResolved(request, resolution)
            }
        }
        val network = PluginNetworkClient(recording, storage, hostResolver = createPlatformPluginHostResolver())
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val repositoryClient = ExtensionRepositoryClient(http)
        val manager = PluginManager(
            repositoryClient, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keys)),
            RhinoScriptPluginRuntimeFactory(), ScriptPluginEnvironment(network, storage),
        )
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val admission = productionShuYueReviewedAdmissionV2(
            InMemoryShuYueScriptQuarantineStoreV2(), approvals, approvals,
            RhinoScriptPluginRuntimeFactory(), ScriptPluginEnvironment(network, storage),
        )
        try {
            val staged = admission.quarantine(
                ShuYueScriptCandidateV2(
                    profile.identity.packageId, profile.identity.version, profile.identity.versionCode,
                    profile.sourceIds.toList(), bytes, ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY, digest,
                ),
            )
            assertEquals(ShuYueReviewStatusV2.REVIEWED, staged.reviewStatus)
            approvals.trust(profile.identity)
            approvals.grant(profile.identity, profile.requiredPermissions)
            manager.installReviewedShuYueRuntimeV2(admission, staged.quarantineId)
            manager.setPluginUiAvailable(true)
            val sourceKey = SourceKey(2, target.packageId, target.sourceId)
            val exactScope = assertNotNull(manager.contentNetworkScopeForSource(sourceKey))
            val adapter = PluginBrowseAdapter(
                manager, repositoryClient, KeyValueExtensionRepositoryStore(keys), storage, keys,
                KeyValuePluginTrustStore(keys),
            )
            val page = withTimeout(30_000) {
                adapter.browseSourceV2(sourceKey, BrowseOptionsV2(target.options), 0)
            }
            val publication = page.items.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
                ?: error("${target.sourceId} public browse returned no cover (${page.items.size} items)")
            val coverUrl = requireNotNull(publication.thumbnailUrl)
            val before = requests.size
            val body = withTimeout(30_000) {
                adapter.loadPluginThumbnail(sourceKey, coverUrl, emptyMap())
            }
            assertNotNull(body, "${target.sourceId} cover response was not decoder-compatible: $coverUrl")
            assertNotNull(ImageIO.read(ByteArrayInputStream(body)), "${target.sourceId} cover bytes did not decode: $coverUrl")
            val coverRequests = requests.drop(before)
            assertTrue(coverRequests.isNotEmpty())
            coverRequests.forEach { request ->
                assertTrue(request.headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })
            }
            val finalRequest = coverRequests.last()
            assertEquals(Url(coverUrl).host, Url(finalRequest.url).host)
            assertTrue(finalRequest.headers.keys.any { it.equals("User-Agent", true) })
            assertTrue(finalRequest.headers.keys.any { it.equals("Referer", true) })

            val details = withTimeout(30_000) {
                adapter.extensionDetailsV2(sourceKey, publication.remoteId)
            }
            assertEquals(publication.remoteId, details.remoteId)
            assertTrue(details.title.isNotBlank())
            val units = withTimeout(30_000) {
                adapter.extensionUnitsV2(sourceKey, publication.remoteId, 0)
            }
            val firstUnit = units.items.firstOrNull()
                ?: error("${target.sourceId} public publication returned no units")
            val content = withTimeout(30_000) {
                adapter.extensionContentV2(sourceKey, publication.remoteId, firstUnit.remoteId)
            }
            assertTrue(content.representations.any(::hasPublicPayload),
                "${target.sourceId} first public unit returned no usable text/pages payload")

            // Seed only after all public network operations. This is solely a credential-leak
            // regression for the subsequent exact content-plane request.
            storage.setCredential(exactScope.sourceId, PluginCredential("probe-user", "probe-secret"))
            val requestCount = requests.size
            assertEquals(
                null,
                adapter.loadPluginThumbnail(
                    SourceKey(2, target.packageId, "${target.sourceId}.stale"),
                    coverUrl,
                    emptyMap(),
                ),
            )
            assertEquals(requestCount, requests.size, "stale source key must not receive content authority")
            println(
                "REVIEWED PUBLIC OK ${target.sourceId} coverHost=${Url(coverUrl).host} " +
                    "coverBytes=${body.size} units=${units.items.size} representations=${content.representations.size} " +
                    "scope=${exactScope.sourceId}",
            )
        } finally {
            manager.close()
            http.close()
        }
    }

    private data class Target(
        val packageId: String,
        val sourceId: String,
        val scriptName: String,
        val options: Map<String, String>,
    )

    private fun hasPublicPayload(payload: UnitContentPayload): Boolean = when (payload) {
        is UnitContentPayload.InlineTextPayload -> payload.source.text.isNotBlank()
        is UnitContentPayload.ChunkedTextPayload -> payload.source.streamId.isNotBlank()
        is UnitContentPayload.HostFetchTextPayload -> {
            val request = payload.source.body.resource.request
            !request.url.isNullOrBlank() || !request.baseUri.isNullOrBlank()
        }
        is UnitContentPayload.ImageSequence -> payload.pages.isNotEmpty()
        is UnitContentPayload.EpubSpine -> payload.documents.isNotEmpty()
    }

    private companion object {
        val TARGETS = listOf(
            Target("zh.wenku8.api", "zh.wenku8.api", "zh.wenku8.api.js", mapOf("option" to "rank:lastupdate")),
            Target("zh.biquge.tw", "zh.biquge.tw", "zh.biquge.tw.js", mapOf("option" to "top:lastupdate")),
            Target("zh.bilimanga", "zh.bilimanga.novel", "zh.bilimanga.js", emptyMap()),
        )

        fun reviewedPluginDirectory(): File {
            System.getenv("SHINSOU_REVIEWED_PLUGIN_DIR")?.takeIf(String::isNotBlank)?.let(::File)?.let { return it }
            return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { it.resolve("shinsou_plugin/plugins") }
                .firstOrNull { it.isDirectory }
                ?: error("Set SHINSOU_REVIEWED_PLUGIN_DIR to the shinsou_plugin/plugins directory")
        }
    }
}
