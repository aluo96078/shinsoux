package dev.shinsou.kmp.plugin.sandbox

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import dev.shinsou.kmp.plugin.PluginHostResolution
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
import dev.shinsou.kmp.plugin.PublisherFingerprint
import dev.shinsou.kmp.plugin.PublisherRevocationStore
import dev.shinsou.kmp.plugin.PublisherTrustStore
import dev.shinsou.kmp.plugin.RepositorySignedDocumentType
import dev.shinsou.kmp.plugin.RepositorySignedDocumentVerifier
import dev.shinsou.kmp.plugin.RepositoryTrustPolicies
import dev.shinsou.kmp.plugin.RepositoryTrustRoot
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.TieredScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The app's composition end to end on a device: a trusted publisher's package, approved by the
 * user, is built by [PluginManager] through the tiered factory and runs in its own isolated process;
 * blocking the publisher takes it down at the next load.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxAppIntegrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

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
    fun aTrustedPublishersPackageRunsInAnIsolatedProcess() = runBlocking<Unit> {
        SodiumSyncPrimitives.initialize()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val keyValues = InMemoryPluginKeyValueStore()
        val state = KeyValueRepositorySecurityStateStore(keyValues)
        val publishers = PublisherTrustStore(keyValues, state)
        val revocations = PublisherRevocationStore(keyValues, publishers, state)
        val blocklist = LocalPluginBlocklist(keyValues)
        val storage = KeyValuePluginStorage(keyValues)
        // The host's network layer, reached only through the bridge with the source's policy and a
        // pinned resolution: an unresolved request or another origin fails the test.
        val requests = java.util.Collections.synchronizedList(ArrayList<String>())
        val sites = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                kotlin.test.fail("unpinned request to ${request.url}")
            override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                requests += request.url
                return PluginHttpResponse(200, "<ul><li><a href='/m/1'>沙箱一</a><li><a href='/m/2'>Two</a></ul>".encodeToByteArray())
            }
        }
        // Every sandbox the app opens, to check where the code actually ran.
        val opened = java.util.Collections.synchronizedList(ArrayList<AndroidSandboxTransport>())
        val androidTransports = AndroidSandboxTransportProvider(context)
        val transports = SandboxTransportProvider { packageId ->
            (androidTransports.open(packageId) as AndroidSandboxTransport).also { opened += it }
        }
        var inProcessCalls = 0
        val rhino = RhinoScriptPluginRuntimeFactory()
        val inProcess = object : dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory {
            override suspend fun create(script: String, manifest: dev.shinsou.kmp.plugin.PluginManifest, environment: ScriptPluginEnvironment) =
                rhino.create(script, manifest, environment).also { inProcessCalls++ }
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
                inProcess = inProcess,
                sandbox = SandboxRuntimeFactory(transports, androidSandboxLifecycle(context, scope)),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(transport = sites, storage = storage, hostResolver = PluginHostResolver { listOf("93.184.216.34") }),
                storage = storage,
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(keyValues, MutablePluginSystemEventAuthorizer()),
            executionTierGate = PublisherExecutionTierGate(
                publishers,
                ExecutionTierResolver(publishers, revocations, blocklist, nowEpochSeconds = { 0L }),
            ),
        )
        val key = SodiumSyncPrimitives.generateEd25519KeyPair()
        try {
            val root = RepositoryTrustRoot(SodiumSyncPrimitives.base64UrlEncode(key.publicKey), PublisherFingerprint.of(key.publicKey))
            val publisher = publishers.trustRepository(REPOSITORY.baseUrl, root).publisherId
            revocations.applyRevocations(REPOSITORY.baseUrl, emptyRevocations(key, root, publisher))
            manager.install(REPOSITORY, entry)
            val review = checkNotNull(manager.pendingEventGrantReview(entry.id))
            manager.approveEventGrantReview(entry.id, review, review.requestedPermissions)
            val page = manager.source(SOURCE_ID)!!.getPopularManga(1)
            assertEquals(listOf("沙箱一", "Two"), page.mangas.map { it.title })
            // It ran in a separate isolated process, never in the app's own engine.
            assertEquals(0, inProcessCalls)
            val sandbox = opened.single()
            kotlin.test.assertNotEquals(android.os.Process.myPid(), sandbox.pid)
            val user = userOf(sandbox.pid)
            kotlin.test.assertTrue(user.startsWith("u0_i"), "sandbox ${sandbox.pid} runs as $user, not an isolated uid")
            assertEquals(listOf("https://www.example.test/list?p=1"), requests.toList())
            Log.i("SandboxIntegration", "${page.mangas.size} titles from isolated pid ${sandbox.pid}")

            blocklist.block(PluginBlockEntry.Publisher(publisher))
            manager.loadInstalled()
            assertNull(manager.source(SOURCE_ID))
        } finally {
            SodiumSyncPrimitives.destroy(key.privateKey)
            manager.close()
            scope.cancel()
        }
    }

    /** The user a process of this device runs as (`u0_i…` for isolated processes). */
    private fun userOf(pid: Int): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("ps -A -o PID,USER")
        val table = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        return table.lines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it[0] == pid.toString() }?.get(1) ?: "missing"
    }

    private suspend fun emptyRevocations(key: dev.shinsou.kmp.sync.crypto.SodiumKeyPair, root: RepositoryTrustRoot, publisher: String): JsonObject {
        val payload = buildJsonObject {
            put("publisherId", publisher)
            put("revokedArtifacts", JsonArray(emptyList()))
            put("expiresAt", 10_000L)
        }
        val signature = SodiumSyncPrimitives.signEd25519(
            RepositorySignedDocumentVerifier.signingMessage(
                REPOSITORY.baseUrl, RepositorySignedDocumentType.REVOCATIONS, PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID, 1, root.fingerprint, payload,
            ),
            key.privateKey,
        )
        return buildJsonObject {
            put("format", RepositorySignedDocumentVerifier.ENVELOPE_FORMAT)
            put("algorithm", "Ed25519")
            put("keyFingerprint", root.fingerprint)
            put("publicKey", root.publicKeyBase64Url)
            put("payloadType", RepositorySignedDocumentType.REVOCATIONS.wireValue)
            put("sequence", 1)
            put("payload", payload)
            put("signature", SodiumSyncPrimitives.base64UrlEncode(signature))
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
