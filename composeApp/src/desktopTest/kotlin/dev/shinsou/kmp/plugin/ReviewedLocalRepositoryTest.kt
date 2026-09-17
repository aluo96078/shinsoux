package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Offline integration coverage for the explicitly enabled, app-reviewed local repository. */
class ReviewedLocalRepositoryTest {
    @Test
    fun configuredLanRepositoryDiscoversAndDownloadsAllThreeReviewedNovelSources() = runTest {
        val repository = requireRepository()
        val policy = ReviewedLocalRepositoryPolicy.EXACT_ANDROID_LAN_192_168_50_193_18081
        val base = REVIEWED_IOS_LAN_SHINSOU_REPOSITORY_BASE_URL
        val http = HttpClient(MockEngine { error("Generic HTTP must not be used") })
        val transport = PluginHttpTransport { request ->
            check(policy.admitsRequestUrl(request.url))
            val path = request.url.removePrefix("$base/").substringBefore('?')
            PluginHttpResponse(200, Files.readAllBytes(repository.resolve(path)))
        }
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val client = ExtensionRepositoryClient(
            http, cacheToken = { 1L }, reviewedLocalRepositoryPolicy = policy,
            reviewedLocalRepositoryTransport = transport,
        )
        val manager = PluginManager(
            client, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keys)),
            NoopScriptPluginRuntimeFactory, ScriptPluginEnvironment(
                PluginNetworkClient(PluginHttpTransport { error("No source requests") }, storage), storage,
            ),
            reviewedLocalRepositoryPolicy = policy,
        )
        val loader = dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryIndexLoader(
            dev.shinsou.kmp.plugin.shuyue.KtorShuYueRepositoryTransport(
                http, reviewedLocalRepositoryPolicy = policy, reviewedLocalRepositoryTransport = transport,
            ),
            dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLimits(allowedArtifactOrigins = setOf(base)),
        )
        val adapter = PluginBrowseAdapter(
            manager, client, KeyValueExtensionRepositoryStore(keys).also {
                it.put(ExtensionRepository(base, "Local"))
            }, storage, keys,
            KeyValuePluginTrustStore(keys),
            reviewedShuYueRepositoryLoaderV2 = loader,
        )
        try {
            adapter.refresh()
            assertNull(adapter.state.value.errorMessage)
            // Updated content profiles alone are insufficient for a LAN install: the exact
            // digest and byte size must also be admitted for index, sidecar and script reads.
            val repaired = setOf("zh.komiic", "zh.manhuagui", "zh.bilimanga.manga")
            assertEquals(repaired, adapter.state.value.extensions.filter { it.id in repaired }.map { it.id }.toSet())
            val genericIndex = client.fetchIndex(base)
            val genericEntries = when (genericIndex) {
                is RepositoryIndex.Combined -> genericIndex.plugins
                is RepositoryIndex.Plugins -> genericIndex.entries
                else -> error("Expected V2 packages")
            }
            for (entry in genericEntries.filter { it.id in repaired }) {
                client.verifyPluginV2Sidecar(base, entry)
                val bytes = client.downloadPluginScript(base, entry.scriptUrl)
                assertEquals(entry.sha256, Sha256.hex(bytes))
                assertNotNull(OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(entry, policy, base))
                assertNull(OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(entry.copy(byteSize = requireNotNull(entry.byteSize) + 1), policy, base))
            }
            val expected = setOf("zh.bilimanga", "zh.wenku8.api", "zh.biquge.tw")
            assertEquals(expected, adapter.state.value.extensions.filter { it.reviewedShuYueV2 }.map { it.id }.toSet())
            val index = loader.load(dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLocation.IndexUrl("$base/index.json"))
            for (entry in index.entries.filter { it.id in expected }) {
                val download = loader.downloadScript(index, entry)
                assertEquals(entry.sha256, Sha256.hex(download.bytes))
            }
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun localIndexAndExactInstallRequireApprovalAndSurviveGatedRestart() = runTest {
        val repository = requireRepository()
        val files = FixtureFiles()
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { error("Local fetch must not reach generic HTTP") })
        val keys = InMemoryPluginKeyValueStore()
        val packageStore = KeyValuePluginPackageStore(keys)
        val storage = KeyValuePluginStorage(keys)
        var tamperScript = false
        var tamperSidecar = false
        val client = ExtensionRepositoryClient(
            client = http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN,
            reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
            reviewedLocalRepositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
                    val path = request.url.substringAfter("127.0.0.1:18081/").substringBefore('?')
                    check(path in files.allowedPaths) { "Unexpected local transport path: $path" }
                    requests += path
                    val bytes = Files.readAllBytes(repository.resolve(path))
                    return PluginHttpResponse(200, when {
                        tamperScript && path.endsWith(".js") -> bytes + byteArrayOf(0)
                        tamperSidecar && path.startsWith("sidecars/") -> bytes.decodeToString()
                            .replace("1.0.9", "1.0.99").encodeToByteArray()
                        else -> bytes
                    })
                }
            },
        )
        fun manager(policy: ReviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081): PluginManager = PluginManager(
            repositoryClient = client,
            packageStore = KeyValuePluginPackageStore(keys),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keys)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { error("Source network is outside this offline test") },
                    storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(keys, MutablePluginSystemEventAuthorizer()),
            reviewedLocalRepositoryPolicy = policy,
        )
        val first = manager()
        try {
            val index = when (
                val fetched = client.fetchIndex(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            ) {
                is RepositoryIndex.Combined -> fetched
                else -> error("Expected the reviewed local V2 index")
            }
            assertEquals(14, index.plugins.size)
            assertEquals(14, index.plugins.map { it.id }.distinct().size)
            assertTrue(index.shuyue.isEmpty())
            assertTrue(index.plugins.none { it.id.startsWith("example.") })
            assertTrue(index.plugins.none { it.contract != "shinsou" })
            assertTrue(index.plugins.none {
                it.id in setOf("zh.bilimanga", "zh.wenku8", "zh.wenku8.api", "zh.biquge.tw")
            })

            val entry = index.plugins.single { it.id == JM_ID }
            assertEquals("1.0.9", entry.version)
            assertEquals(10, entry.versionCode)
            assertEquals(JM_DIGEST, entry.sha256)
            assertEquals(24_107, entry.byteSize)

            val localRepo = ExtensionRepository(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL, "Local")
            val requestsBefore = requests.size
            listOf(
                entry.copy(version = "1.0.99"),
                entry.copy(sha256 = "0".repeat(64)),
                entry.copy(byteSize = 1),
                entry.copy(runtimePermissions = emptySet()),
                entry.copy(sources = entry.sources!!.map { it.copy(baseUrl = "https://other.example") }),
                entry.copy(scriptUrl = "plugins/unknown.js"),
                entry.copy(sidecarUrl = null),
            ).forEach { forged ->
                assertFailsWith<IllegalArgumentException> { first.install(localRepo, forged) }
            }
            assertEquals(requestsBefore, requests.size, "Forged entries must fail before any artifact fetch")
            assertNull(KeyValuePluginPackageStore(keys).get(JM_ID))
            tamperSidecar = true
            assertFailsWith<ExtensionRepositoryException.InvalidDocument> { first.install(localRepo, entry) }
            tamperSidecar = false
            tamperScript = true
            assertFailsWith<IllegalArgumentException> { first.install(localRepo, entry) }
            tamperScript = false
            assertNull(KeyValuePluginPackageStore(keys).get(JM_ID))
            first.install(localRepo, entry)
            val installed = packageStore.get(JM_ID)
            assertNotNull(installed)
            assertEquals(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL, installed.metadata.repositoryBaseUrl)
            assertEquals("1.0.9", installed.manifest.version)
            assertEquals(10, installed.manifest.versionCode)
            assertEquals(JM_DIGEST, installed.metadata.installedSha256)
            assertEquals(JM_DIGEST, Sha256.hex(installed.scriptBytes))
            assertNull(first.source(JM_SOURCE_ID), "A local artifact must be inert before explicit approval")
            assertFalse(first.hasExecutionApproval(JM_ID))
            assertNotNull(first.pendingEventGrantReview(JM_ID))

            first.approveCurrentEventGrantReview(JM_ID, emptySet())
            assertTrue(first.hasExecutionApproval(JM_ID))
            assertNotNull(first.source(JM_SOURCE_ID))
            val sourceKey = assertNotNull(first.exactSourceKeyForLegacyId(JM_SOURCE_ID))
            assertEquals(JM_ID, sourceKey.packageId)
            assertTrue(first.authorizeWebChallenge(sourceKey))
        } finally {
            first.close()
        }

        // A disabled process cannot resurrect the persisted local install or its review.
        val disabled = manager(ReviewedLocalRepositoryPolicy.DISABLED)
        try {
            assertTrue(disabled.loadInstalled().isEmpty())
            assertNull(disabled.source(JM_SOURCE_ID))
            assertFalse(disabled.hasExecutionApproval(JM_ID))
        } finally {
            disabled.close()
        }
        val restarted = manager()
        try {
            assertEquals(listOf(JM_SOURCE_ID), restarted.loadInstalled().map { it.id })
            assertTrue(restarted.hasExecutionApproval(JM_ID))
            val key = assertNotNull(restarted.exactSourceKeyForLegacyId(JM_SOURCE_ID))
            assertTrue(restarted.authorizeWebChallenge(key))
            restarted.revokeEventGrants(JM_ID)
            assertFalse(restarted.hasExecutionApproval(JM_ID))
            assertFalse(restarted.authorizeWebChallenge(key))
        } finally {
            restarted.close()
            http.close()
        }
    }

    private fun requireRepository(): Path {
        val found = listOfNotNull(
            Path.of("../shinsou_plugin"),
            Path.of("../../shinsou_plugin"),
            System.getProperty("shinsou.pluginRepo")?.let(Path::of),
        ).map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }
        assumeTrue("The sibling shinsou_plugin fixture is required", found != null)
        return requireNotNull(found)
    }

    private class FixtureFiles {
        val allowedPaths: Set<String> = setOf(
            "repo.json", "index.json", "plugins/$JM_ID.js", "sidecars/$JM_ID.json",
        )
    }

    private companion object {
        const val JM_ID = "zh.jinmantiantang"
        const val JM_SOURCE_ID = 1_817_081L
        const val JM_DIGEST = "1cee1cf231775b8b622b855dc3d9e3269e52550201c88408c895537fec6880a8"
    }
}
