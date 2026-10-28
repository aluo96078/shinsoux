package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.ExecutionTierResolver
import dev.shinsou.kmp.plugin.ExtensionRepository
import dev.shinsou.kmp.plugin.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.KeyValueRepositorySecurityStateStore
import dev.shinsou.kmp.plugin.LocalPluginBlocklist
import dev.shinsou.kmp.plugin.PluginBlockEntry
import dev.shinsou.kmp.plugin.PluginHostResolver
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginIndexEntry
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.PluginVerifier
import dev.shinsou.kmp.plugin.PublisherExecutionTierGate
import dev.shinsou.kmp.plugin.PublisherRevocationStore
import dev.shinsou.kmp.plugin.PublisherTrustStore
import dev.shinsou.kmp.plugin.RepositoryTrustPolicies
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.TieredScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.approveCurrentEventGrantReview
import dev.shinsou.kmp.plugin.publisherRoot
import dev.shinsou.kmp.plugin.signedRevocations
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's composition end to end on this desktop: a trusted publisher's package, approved by the
 * user, is built by [PluginManager] through the tiered factory and runs its hooks in the real
 * OS-confined `shinsou-sandbox` helper; blocking the publisher takes it down at the next load.
 */
class SandboxAppIntegrationTest {
    private val binary = sandboxHelperBinary()

    private val script = """
        var source = {
          name: 'Integrated', baseUrl: 'https://www.example.test',
          getPopularManga: function (page) {
            var doc = Jsoup.parse(bridge.httpGet(this.baseUrl + '/list?p=' + page), this.baseUrl);
            var links = doc.select('li a');
            var mangas = [];
            for (var i = 0; i < links.length; i++) mangas.push({ url: links[i].attr('href'), title: links[i].text() });
            return { mangas: mangas, hasNextPage: false };
          }
        };
    """.trimIndent()

    @Test
    fun aTrustedPublishersPackageRunsInTheConfinedHelper() = runBlocking<Unit> {
        val bin = binary ?: run { println("SKIPPED: shinsou-sandbox not built (see SandboxNativeInteropTest)"); return@runBlocking }
        SodiumSyncPrimitives.initialize()
        val scope = CoroutineScope(SupervisorJob())
        val keyValues = InMemoryPluginKeyValueStore()
        val state = KeyValueRepositorySecurityStateStore(keyValues)
        val publishers = PublisherTrustStore(keyValues, state)
        val blocklist = LocalPluginBlocklist(keyValues)
        val revocations = PublisherRevocationStore(keyValues, publishers, state)
        val resolver = ExecutionTierResolver(publishers, revocations, blocklist, nowEpochSeconds = { 0L })
        val storage = KeyValuePluginStorage(keyValues)
        val requests = ArrayList<String>()
        val sites = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest) = respond(request)
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: dev.shinsou.kmp.plugin.PluginHostResolution) = respond(request)
            private fun respond(request: PluginHttpRequest): PluginHttpResponse {
                synchronized(requests) { requests += request.url }
                return PluginHttpResponse(200, "<ul><li><a href='/m/1'>沙箱一</a><li><a href='/m/2'>Two</a></ul>".encodeToByteArray())
            }
        }
        val manager = PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond(script) }),
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = TieredScriptPluginRuntimeFactory(
                inProcess = RhinoScriptPluginRuntimeFactory(),
                sandbox = SandboxRuntimeFactory(
                    transports = DesktopSandboxTransportProvider { bin.toPath() },
                    lifecycle = desktopSandboxLifecycle(scope),
                ),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(transport = sites, storage = storage, hostResolver = PluginHostResolver { listOf("93.184.216.34") }),
                storage = storage,
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(keyValues, MutablePluginSystemEventAuthorizer()),
            executionTierGate = PublisherExecutionTierGate(publishers, resolver),
        )
        try {
            val key = SodiumSyncPrimitives.generateEd25519KeyPair()
            val publisher = publishers.trustRepository(REPOSITORY.baseUrl, publisherRoot(key)).publisherId
            // What the publisher's repository serves and the app syncs before anything installs.
            revocations.applyRevocations(REPOSITORY.baseUrl, signedRevocations(key, REPOSITORY.baseUrl, publisher, expiresAt = 10_000))
            SodiumSyncPrimitives.destroy(key.privateKey)
            manager.install(REPOSITORY, entry)
            manager.approveCurrentEventGrantReview(entry.id, emptySet())
            val page = manager.source(SOURCE_ID)!!.getPopularManga(1)
            println("integration: requests=$requests logs=${(manager.source(SOURCE_ID) as dev.shinsou.kmp.plugin.ScriptPluginRuntime).recentLogs}")
            assertEquals(listOf("沙箱一", "Two"), page.mangas.map { it.title })
            assertTrue(manager.source(SOURCE_ID) is dev.shinsou.kmp.plugin.ScriptPluginRuntime)
            println("integration: ${page.mangas.size} titles from the confined helper")

            blocklist.block(PluginBlockEntry.Publisher(publisher))
            manager.loadInstalled()
            assertNull(manager.source(SOURCE_ID))
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    private val entry = PluginIndexEntry(
        id = "dev.example.integrated",
        name = "Integrated",
        version = "1.0.0",
        versionCode = 1,
        lang = "all",
        scriptUrl = "plugins/dev.example.integrated.js",
        sources = listOf(
            SourceIndexEntry(
                name = "Integrated",
                lang = "all",
                id = SOURCE_ID,
                baseUrl = "https://www.example.test",
                originPolicyVersion = 2,
                requestOrigins = setOf("https://www.example.test"),
            ),
        ),
        runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
    )

    private companion object {
        const val SOURCE_ID = 9_101L
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")
    }
}
