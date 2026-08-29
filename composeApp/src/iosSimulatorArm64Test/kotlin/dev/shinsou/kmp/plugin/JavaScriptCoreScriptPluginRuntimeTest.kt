package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.LoginRequestV1
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventContextRegistry
import dev.shinsou.kmp.plugin.events.PluginEventGrantKey
import dev.shinsou.kmp.plugin.events.PluginEventOutcome
import dev.shinsou.kmp.plugin.events.PluginEventRuntimeStatus
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginRuntimeLifecycle
import dev.shinsou.kmp.plugin.events.PluginSystemEventCodec
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemEventHandlerRegistry
import dev.shinsou.kmp.plugin.events.PluginSystemEventKind
import dev.shinsou.kmp.plugin.events.PluginSystemEventLane
import dev.shinsou.kmp.plugin.events.PluginSystemEventNames
import dev.shinsou.kmp.plugin.events.SourceRefreshRequestV1
import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaScriptCoreScriptPluginRuntimeTest {
    @Test
    fun multiSourcePackageSelectsExactExportedSourceInsteadOfListPosition() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("No network request expected") },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val manifest = PluginManifest(
            "multi.ios",
            "Multi iOS",
            "2.0.0",
            2,
            "all",
            script = "multi.ios.js",
            signature = "",
            sources = listOf(
                SourceIndexEntry("One", "en", 301L, "https://one.example"),
                SourceIndexEntry("Two", "en", 302L, "https://two.example"),
            ),
        )
        val factory = JavaScriptCoreScriptPluginRuntimeFactory()
        assertFailsWith<IllegalArgumentException> {
            factory.create(IOS_MULTI_SOURCE_PLUGIN, manifest, ScriptPluginEnvironment(network, storage))
        }
        val one = factory.createForSource(
            IOS_MULTI_SOURCE_PLUGIN,
            manifest,
            manifest.sources.orEmpty()[0],
            ScriptPluginEnvironment(network, storage),
        )
        val two = factory.createForSource(
            IOS_MULTI_SOURCE_PLUGIN,
            manifest,
            manifest.sources.orEmpty()[1],
            ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals("one|https://one.example", one.getPopularManga(0).mangas.single().title)
            assertEquals("two|https://two.example", two.getPopularManga(0).mangas.single().title)
        } finally {
            one.close()
            two.close()
        }
    }

    @Test
    fun cancellingSearchCancelsHttpBridgeAndFreesTheEngineWorker() = runBlocking {
        val slowRequestStarted = CompletableDeferred<Unit>()
        val slowRequestCancelled = CompletableDeferred<Unit>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                if (request.url.endsWith("/slow")) {
                    slowRequestStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        slowRequestCancelled.complete(Unit)
                    }
                }
                PluginHttpResponse(200, "fast-response".encodeToByteArray())
            },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_CANCELLABLE_HTTP_PLUGIN,
            manifest = cancellableManifest(),
            environment = ScriptPluginEnvironment(network, storage),
        )
        try {
            val obsoleteSearch = launch { runtime.getSearchManga(1, "slow", emptyList()) }
            slowRequestStarted.await()

            withTimeout(5_000) {
                obsoleteSearch.cancelAndJoin()
                slowRequestCancelled.await()
                assertEquals(
                    "fast|fast-response",
                    runtime.getSearchManga(1, "fast", emptyList()).mangas.single().title,
                )
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun closingRuntimeCancelsAnInFlightHttpBridge() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport {
                requestStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    requestCancelled.complete(Unit)
                }
            },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_CANCELLABLE_HTTP_PLUGIN,
            manifest = cancellableManifest(),
            environment = ScriptPluginEnvironment(network, storage),
        )
        val invocation = launch { runtime.getSearchManga(1, "slow", emptyList()) }
        requestStarted.await()

        withTimeout(5_000) {
            runtime.close()
            requestCancelled.await()
            invocation.join()
        }
        assertTrue(invocation.isCancelled)
    }

    @Test
    fun directRuntimeCannotUseLegacyLoginRequesterWithoutExactHostAdmission() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("No network request expected") },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val manifest = PluginManifest(
            "zh.login-request",
            "Login Request",
            "1.0.0",
            1,
            "zh",
            script = "zh.login-request.js",
            signature = "",
            sources = listOf(SourceIndexEntry("Login Source", "zh", 993, "https://source.example")),
        )
        val requests = mutableListOf<Triple<Long, String, String?>>()
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_LOGIN_REQUEST_PLUGIN,
            manifest = manifest,
            environment = ScriptPluginEnvironment(
                network = network,
                storage = storage,
                loginRequester = PluginLoginRequester { sourceId, sourceName, reason ->
                    requests += Triple(sourceId, sourceName, reason)
                    true
                },
            ),
        )
        try {
            assertEquals("false", runtime.getPopularManga(0).mangas.single().title)
            assertEquals("false", runtime.getPopularManga(1).mangas.single().title)
            assertEquals(emptyList(), requests)
        } finally {
            runtime.close()
        }

        val noOpRuntime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_LOGIN_REQUEST_PLUGIN,
            manifest = manifest,
            environment = ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals("false", noOpRuntime.getPopularManga(0).mangas.single().title)
        } finally {
            noOpRuntime.close()
        }
    }

    @Test
    fun missingFilterHookFallsBackToEmptyListAndSearchStillRuns() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("No network request expected") },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_NO_FILTER_PLUGIN,
            manifest = PluginManifest(
                "zh.no-filter",
                "No Filter",
                "1.0.0",
                1,
                "zh",
                script = "zh.no-filter.js",
                signature = "",
                sources = listOf(SourceIndexEntry("No Filter", "zh", 992, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(network, storage),
        )
        try {
            assertTrue(runtime.getFilterList().isEmpty())
            assertEquals("needle|2", runtime.getSearchManga(2, "needle", emptyList()).mangas.single().title)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun systemEventReceiptAndCapabilitiesMatchTheJvmTransportContract() = runTest {
        val fixture = javascriptCoreSystemEventFixture()
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_SYSTEM_EVENT_PLUGIN,
            manifest = fixture.manifest,
            environment = fixture.environment,
        )
        try {
            assertEquals(
                "true|1|command.auth.login.request|accepted",
                runtime.getPopularManga(0).mangas.single().title,
            )
            assertTrue(fixture.gateway.awaitIdle())
            assertEquals(listOf<String?>("Members only"), fixture.loginReasons)
        } finally {
            runtime.close()
            fixture.gateway.close()
        }
    }

    @Test
    fun activeContextRefreshUsesHostIssuedHandleOnlyDuringInvocation() = runTest {
        val fixture = javascriptCoreSystemEventFixture(
            declaration = PluginSystemEventDeclaration(
                minVersion = 1,
                maxVersion = 1,
                required = setOf(PluginSystemEventNames.REFRESH_CAPABILITY),
            ),
            permissions = setOf(PluginHostPermission.REQUEST_SOURCE_REFRESH),
            sourceCapabilities = setOf("CATALOGUE"),
        )
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_ACTIVE_CONTEXT_PLUGIN,
            manifest = fixture.manifest,
            environment = fixture.environment,
        )
        try {
            val active = fixture.contextRegistry.withInvocation(fixture.scope) {
                runtime.getPopularManga(0).mangas.single().title
            }
            assertEquals("true|accepted", active)
            assertEquals("false|denied", runtime.getPopularManga(1).mangas.single().title)
        } finally {
            runtime.close()
            fixture.gateway.close()
        }
    }

    @Test
    fun httpPostBatchBridgeReturnsOrderedJsonResponses() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                PluginHttpResponse(200, request.url.substringAfterLast('/').encodeToByteArray())
            },
            storage = storage,
            requestGate = PerHostRequestGate(
                PluginRateLimitProvider { PluginRateLimit(32, 0) },
            ),
        )
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_BATCH_PLUGIN,
            manifest = PluginManifest(
                "zh.batch.ios",
                "Batch iOS",
                "1.0.0",
                1,
                "zh",
                script = "zh.batch.ios.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Batch", "zh", 994, "https://batch.example")),
            ),
            environment = ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals("one|two|three", runtime.getPopularManga(0).mangas.single().title)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun synchronousPluginContractRunsInsideJavaScriptCore() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                requests += request
                PluginHttpResponse(
                    200,
                    """<script>var payload = "<fake-node>";</script>
                        <style>.card::after { content: "<fake-style>"; }</style>
                        <div class="card"><a data-image="cover.jpg" href="/m/one"><span>One</span></a></div>
                        <div class="card disabled"><a href="/m/two">Two</a></div>""".encodeToByteArray(),
                    emptyMap(),
                )
            },
            storage = storage,
            requestBuilder = PluginRequestBuilder(storage, PluginUserAgentProvider { "ios-test-agent" }),
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val runtime = JavaScriptCoreScriptPluginRuntimeFactory().create(
            script = IOS_CONTRACT_PLUGIN,
            manifest = PluginManifest(
                "all.ios-test",
                "iOS Test",
                "1.0.0",
                1,
                "all",
                script = "all.ios-test.js",
                signature = "",
                sources = listOf(SourceIndexEntry("iOS Source", "all", 991, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals(991, runtime.id)
            assertEquals("iOS Source", runtime.name)
            assertEquals("https://source.example", runtime.baseUrl)
            assertTrue(runtime.supportsLatest)
            assertTrue(runtime.supportsLogin)

            val failedLogin = runtime.loginResult("alice", "wrong")
            assertFalse(failedLogin.loggedIn)
            assertEquals("帳號或密碼錯誤", failedLogin.errorMessage)
            assertEquals(null, storage.getCredential(991))
            assertTrue(runtime.login("alice", "secret"))
            val popular = runtime.getPopularManga(0)
            assertEquals("One|alice", popular.mangas.single().title)
            assertEquals("/m/one", popular.mangas.single().url)
            assertEquals("ios-test-agent", requests.single().headers["User-Agent"])
            assertEquals("yes", requests.single().headers["X-iOS"])

            assertEquals("term-2", runtime.getSearchManga(2, "term", emptyList()).mangas.single().title)
            assertEquals("latest-3", runtime.getLatestUpdates(3).mangas.single().title)
            val details = runtime.getMangaDetails(SManga("/m/one", "One"))
            assertEquals("Detailed", details.title)
            val chapter = runtime.getChapterList(details).single()
            assertEquals(1.25, chapter.chapterNumber)
            assertEquals("https://img.example/1.jpg", runtime.getPageList(chapter).single().imageUrl)
            assertTrue(runtime.getFilterList().single() is Filter.Select)
            val preferences = runtime.getPreferenceDefinitions()
            assertEquals(2, preferences.size)
            assertTrue(assertIs<SourcePreference.Toggle>(preferences[0]).defaultValue)
            assertEquals(listOf("zh-TW", "en"), assertIs<SourcePreference.Select>(preferences[1]).entryValues)

            runtime.logout()
            assertFalse(storage.getCredential(991)?.username?.isNotEmpty() == true)
        } finally {
            runtime.close()
        }
    }
}

private const val IOS_MULTI_SOURCE_PLUGIN: String = """
var sources={
  '301':{id:'301',getPopularManga:function(page){var manga=SManga.create();manga.url='/one';manga.title='one|'+baseUrl;return new MangasPage([manga],false);}},
  '302':{id:'302',getPopularManga:function(page){var manga=SManga.create();manga.url='/two';manga.title='two|'+baseUrl;return new MangasPage([manga],false);}}
};
"""

private const val IOS_BATCH_PLUGIN: String = """
var source={
  id:'994',
  baseUrl:'https://batch.example',
  getPopularManga:function(page){
    var raw=bridge.httpPostBatch(
      [this.baseUrl+'/one',this.baseUrl+'/two',this.baseUrl+'/three'],
      ['body-one','body-two','body-three'],
      {'Accept':'text/plain'}
    );
    var values=JSON.parse(raw||'[]');
    var manga=SManga.create();
    manga.url='/batch';manga.title=values.join('|');
    return new MangasPage([manga],false);
  }
};
"""

private fun cancellableManifest(): PluginManifest = PluginManifest(
    "all.cancel-test",
    "Cancellation Test",
    "1.0.0",
    1,
    "all",
    script = "all.cancel-test.js",
    signature = "",
    sources = listOf(SourceIndexEntry("Cancellation Source", "all", 994, "https://source.example")),
)

private const val IOS_CANCELLABLE_HTTP_PLUGIN: String = """
var source={
  baseUrl:'https://source.example',
  getSearchManga:function(page,query){
    var response=bridge.httpGet(this.baseUrl+'/'+query);
    var manga=SManga.create();manga.url='/'+query;manga.title=query+'|'+response;
    return new MangasPage([manga],false);
  }
};
"""

private const val IOS_LOGIN_REQUEST_PLUGIN: String = """
var source={
  baseUrl:'https://source.example',supportsLogin:true,
  getPopularManga:function(page){
    var accepted=page===0?bridge.requestLogin('Members only'):bridge.requestLogin();
    var manga=SManga.create();manga.url='/login';manga.title=String(accepted);
    return new MangasPage([manga],false);
  }
};
"""

private const val IOS_NO_FILTER_PLUGIN: String = """
var source={
  baseUrl:'https://source.example',
  getSearchManga:function(page,query){
    var manga=SManga.create();manga.url='/search';manga.title=query+'|'+page;
    return new MangasPage([manga],false);
  }
};
"""

private const val IOS_CONTRACT_PLUGIN: String = """
var source={
  baseUrl:'https://source.example',supportsLatest:true,supportsLogin:true,
  headers:{'X-iOS':'yes'},
  login:function(username,password){if(password!=='secret')return {loggedIn:false,errorMessage:'帳號或密碼錯誤'};bridge.setPreference('name',username);return {loggedIn:true};},
  logout:function(){bridge.clearCookies();},
  getPopularManga:function(page){
    var doc=Jsoup.parse(bridge.httpGet(this.baseUrl+'/popular'),'https://source.example');
    var script=doc.selectFirst('script');
    if(!script||script.html().indexOf('<fake-node>')<0)throw new Error('raw script content was not preserved');
    var link=doc.selectFirst('div.card:has(a):not(.disabled) > a');
    if(doc.selectFirst('a[data-image\x24=".jpg"]')!==link)throw new Error('attribute suffix selector failed');
    var manga=SManga.create();manga.url=link.attr('href');manga.title=link.text()+'|'+bridge.getPreference('name');
    return new MangasPage([manga],false);
  },
  getSearchManga:function(page,query){var manga=SManga.create();manga.url='/search';manga.title=query+'-'+page;return new MangasPage([manga],false);},
  getLatestUpdates:function(page){var manga=SManga.create();manga.url='/latest';manga.title='latest-'+page;return new MangasPage([manga],false);},
  getMangaDetails:function(manga){manga.title='Detailed';manga.status=SManga.ONGOING;return manga;},
  getChapterList:function(manga){var chapter=SChapter.create();chapter.url='/c/1';chapter.name='Chapter';chapter.chapterNumber=1.25;return [chapter];},
  getPageList:function(chapter){return [new Page(0,'','https://img.example/1.jpg')];},
  getFilterList:function(){return [{type:'select',name:'Genre',values:['All','Action'],state:1}];},
  getPreferenceDefinitions:function(){return [
    {type:'toggle',key:'enabled',title:'Enabled',summary:'Use feature',defaultValue:true},
    {type:'select',key:'language',title:'Language',entries:['繁中','English'],entryValues:['zh-TW','en'],defaultValue:'zh-TW'}
  ];}
};
"""

private const val IOS_SYSTEM_EVENT_PLUGIN: String = """
var source={
  baseUrl:'https://source.example',supportsLogin:true,
  getPopularManga:function(page){
    var capabilities=bridge.getHostEventCapabilities();
    var receipt=bridge.system.requestLogin('Members only');
    var manga=SManga.create();manga.url='/events';
    manga.title=String(capabilities.enabled)+'|'+String(capabilities.version)+'|'+
      capabilities.grantedCapabilities.join(',')+'|'+receipt.disposition;
    return new MangasPage([manga],false);
  }
};
"""

private const val IOS_ACTIVE_CONTEXT_PLUGIN: String = """
var source={
  baseUrl:'https://source.example',
  getPopularManga:function(page){
    var contextPresent=bridge.getHostEventContext()!==null;
    var receipt=bridge.system.requestRefresh('ACTIVE_CONTEXT');
    var manga=SManga.create();manga.url='/active-context';
    manga.title=String(contextPresent)+'|'+receipt.disposition;
    return new MangasPage([manga],false);
  }
};
"""

private data class JavaScriptCoreSystemEventFixture(
    val gateway: PluginSystemEventGateway,
    val environment: ScriptPluginEnvironment,
    val manifest: PluginManifest,
    val loginReasons: MutableList<String?>,
    val contextRegistry: PluginEventContextRegistry,
    val scope: dev.shinsou.kmp.plugin.events.BoundPluginScope,
)

private fun javascriptCoreSystemEventFixture(
    declaration: PluginSystemEventDeclaration = PluginSystemEventDeclaration(
        minVersion = 1,
        maxVersion = 1,
        required = setOf(PluginSystemEventNames.LOGIN_CAPABILITY),
        optional = setOf(PluginSystemEventNames.DIAGNOSTIC_CAPABILITY),
    ),
    permissions: Set<PluginHostPermission> = setOf(PluginHostPermission.REQUEST_LOGIN_UI),
    sourceCapabilities: Set<String> = setOf("LOGIN"),
): JavaScriptCoreSystemEventFixture {
    val source = SourceKey(2, "jsc.events", "778")
    val artifact = PluginArtifactIdentity(
        packageId = source.packageId,
        version = "1.0.0",
        versionCode = 1,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
    val scope = BoundPluginScopeFactory().bind(
        artifactIdentity = artifact,
        sourceKey = source,
        runtimeInstanceId = "jsc-events-runtime",
        runtimeGeneration = 1,
    )
    val authorizer = MutablePluginSystemEventAuthorizer()
    authorizer.grant(
        PluginEventGrantKey(artifact, source),
        permissions,
    )
    authorizer.setRuntimeStatus(
        scope,
        PluginEventRuntimeStatus(
            lifecycle = PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED,
            hasUserInteractionContext = true,
            sourceCapabilities = sourceCapabilities,
        ),
    )
    val loginReasons = mutableListOf<String?>()
    val codec = PluginSystemEventCodec()
    val registry = PluginSystemEventHandlerRegistry().also { handlers ->
        handlers.register(
            dev.shinsou.kmp.plugin.events.TypedPluginSystemEventHandler<LoginRequestV1>(
                name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
                kind = PluginSystemEventKind.COMMAND,
                payloadVersion = 1,
                lane = PluginSystemEventLane.MODAL,
                requiredPermission = PluginHostPermission.REQUEST_LOGIN_UI,
                requiredSourceCapability = "LOGIN",
                decode = { codec.decodePayload(it, LoginRequestV1.serializer()) },
                execute = { _, payload ->
                    loginReasons += payload.fallbackMessage
                    PluginEventOutcome.Succeeded
                },
            ),
        )
        handlers.register(
            dev.shinsou.kmp.plugin.events.TypedPluginSystemEventHandler<SourceRefreshRequestV1>(
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
    val contextRegistry = PluginEventContextRegistry(handleFactory = { "ctx-jsc-test" })
    val gateway = PluginSystemEventGateway(
        registry = registry,
        authorizer = authorizer,
        codec = codec,
        contextRegistry = contextRegistry,
        dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
    val network = PluginNetworkClient(
        transport = PluginHttpTransport { error("No network request expected") },
        storage = storage,
    )
    val sourceEntry = SourceIndexEntry("JSC events", "all", 778, "https://source.example")
    return JavaScriptCoreSystemEventFixture(
        gateway = gateway,
        environment = ScriptPluginEnvironment(
            network = network,
            storage = storage,
            systemEventSink = gateway,
            boundPluginScope = scope,
            systemEventContextRegistry = contextRegistry,
            systemEventDeclaration = declaration,
        ),
        manifest = PluginManifest(
            id = source.packageId,
            name = "JSC events",
            version = artifact.version,
            versionCode = artifact.versionCode,
            lang = "all",
            script = "jsc.events.js",
            signature = artifact.sha256,
            sources = listOf(sourceEntry),
            systemEvents = declaration,
        ),
        loginReasons = loginReasons,
        contextRegistry = contextRegistry,
        scope = scope,
    )
}
