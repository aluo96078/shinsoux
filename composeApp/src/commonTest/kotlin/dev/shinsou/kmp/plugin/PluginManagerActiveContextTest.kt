package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventContextRegistry
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
import dev.shinsou.kmp.plugin.events.PluginEventGrantKey
import dev.shinsou.kmp.plugin.events.PluginEventOutcome
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemEventCodec
import dev.shinsou.kmp.plugin.events.PluginSystemEventHandlerRegistry
import dev.shinsou.kmp.plugin.events.PluginSystemEventKind
import dev.shinsou.kmp.plugin.events.PluginSystemEventLane
import dev.shinsou.kmp.plugin.events.PluginSystemEventNames
import dev.shinsou.kmp.plugin.events.SourceRefreshRequestV1
import dev.shinsou.kmp.plugin.events.TypedPluginSystemEventHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies the real manager-owned v1 -> v2 adapter issues ACTIVE_CONTEXT handles. */
@OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)
class PluginManagerActiveContextTest {
    @Test
    fun legacyBackedV2InvocationGetsAndThenRevokesHostContext() = runTest {
        val sourceKey = SourceKey.fromLegacy("pkg.context", 101L)
        val scriptBytes = "context-fixture".encodeToByteArray()
        val digest = Sha256.hex(scriptBytes)
        val artifact = PluginArtifactIdentity("pkg.context", "1.0.0", 1, digest)
        val manifest = PluginManifest(
            id = artifact.packageId,
            name = "Context fixture",
            version = artifact.version,
            versionCode = artifact.versionCode,
            lang = "all",
            script = "pkg.context.js",
            signature = digest,
            sources = listOf(SourceIndexEntry("Context source", "all", 101L, "https://source.example")),
            systemEvents = PluginSystemEventDeclaration(
                minVersion = 1,
                maxVersion = 1,
                required = setOf(PluginSystemEventNames.REFRESH_CAPABILITY),
            ),
        )
        val authorizer = MutablePluginSystemEventAuthorizer()
        authorizer.grant(
            PluginEventGrantKey(artifact, sourceKey),
            setOf(PluginHostPermission.REQUEST_SOURCE_REFRESH),
        )
        var contextOrdinal = 0
        val contextRegistry = PluginEventContextRegistry(
            handleFactory = { "ctx-manager-${++contextOrdinal}" },
        )
        val codec = PluginSystemEventCodec()
        val handlers = PluginSystemEventHandlerRegistry().also { registry ->
            registry.register(
                TypedPluginSystemEventHandler<SourceRefreshRequestV1>(
                    name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                    kind = PluginSystemEventKind.COMMAND,
                    payloadVersion = 1,
                    lane = PluginSystemEventLane.REFRESH,
                    requiredPermission = PluginHostPermission.REQUEST_SOURCE_REFRESH,
                    decode = { codec.decodePayload(it, SourceRefreshRequestV1.serializer()) },
                    execute = { _, _ -> PluginEventOutcome.Succeeded },
                ),
            )
        }
        val gateway = PluginSystemEventGateway(
            registry = handlers,
            authorizer = authorizer,
            contextRegistry = contextRegistry,
        )
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trustStore = KeyValuePluginTrustStore(keyValues)
        trustStore.trust(artifact.packageId, artifact.versionCode, digest)
        val runtimeFactory = ContextRuntimeFactory(codec)
        val manager = PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { error("repository access is not expected") }),
            ),
            packageStore = InMemoryPluginPackageStore().also { store ->
                store.put(
                    StoredPlugin(
                        InstalledPluginMetadata(manifest, null, digest),
                        scriptBytes,
                    ),
                )
            },
            verifier = PluginVerifier(trustStore),
            runtimeFactory = runtimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage = storage,
                ),
                storage = storage,
                systemEventSink = gateway,
                systemEventContextRegistry = contextRegistry,
            ),
        )
        try {
            manager.loadInstalled()
            manager.setPluginUiAvailable(true)
            val source = assertNotNull(manager.extensionSourceV2(sourceKey))

            manager.withUserInteractionContext(sourceKey) {
                manager.withVisibleEventContext(sourceKey, "publication") {
                    source.details("publication")
                }
            }

            val runtime = assertNotNull(runtimeFactory.runtime)
            assertNotNull(runtime.contextObservedDuringInvocation)
            assertEquals(PluginEventDisposition.ACCEPTED, runtime.receipt?.disposition)
            assertNull(contextRegistry.current(runtime.boundScope))
        } finally {
            manager.close()
            gateway.close()
        }
    }
}

private class ContextRuntimeFactory(
    private val codec: PluginSystemEventCodec,
) : ScriptPluginRuntimeFactory {
    var runtime: ContextRuntime? = null

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = ContextRuntime(manifest, environment, codec).also { runtime = it }
}

private class ContextRuntime(
    manifest: PluginManifest,
    private val environment: ScriptPluginEnvironment,
    private val codec: PluginSystemEventCodec,
) : ScriptPluginRuntime {
    private val source = requireNotNull(manifest.sources).single()
    val boundScope: dev.shinsou.kmp.plugin.events.BoundPluginScope
        get() = requireNotNull(environment.boundPluginScope)
    var contextObservedDuringInvocation: String? = null
    var receipt: dev.shinsou.kmp.plugin.events.PluginEventReceipt? = null

    override val pluginId: String = manifest.id
    override val id: Long = source.id
    override val name: String = source.name
    override val lang: String = source.lang
    override val baseUrl: String = source.baseUrl.orEmpty()
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getFilterList(): FilterList = emptyList()

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val scope = boundScope
        val contextRef = environment.systemEventContextRegistry?.current(scope)
        contextObservedDuringInvocation = contextRef
        receipt = environment.systemEventSink?.submit(
            scope,
            codec.encodePayload(
                kind = PluginSystemEventKind.COMMAND,
                name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                id = "context-fixture",
                contextRef = contextRef,
                payload = SourceRefreshRequestV1(
                    dev.shinsou.kmp.plugin.events.SourceRefreshScope.ACTIVE_CONTEXT,
                ),
                serializer = SourceRefreshRequestV1.serializer(),
            ),
        )
        return manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    override suspend fun login(username: String, password: String): Boolean = false
    override suspend fun logout(): Unit = Unit
    override suspend fun close(): Unit = Unit
}
