package dev.shinsou.kmp.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaScriptCoreScriptPluginRuntimeTest {
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
    fun loginRequestsCarrySourcePayloadAndDefaultRequesterIsANoOp() = runTest {
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
            assertEquals("true", runtime.getPopularManga(0).mangas.single().title)
            assertEquals("true", runtime.getPopularManga(1).mangas.single().title)
            assertEquals(
                listOf(
                    Triple(993L, "Login Source", "Members only"),
                    Triple(993L, "Login Source", null),
                ),
                requests,
            )
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
  login:function(username,password){if(password!=='secret')return false;bridge.setPreference('name',username);return true;},
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
