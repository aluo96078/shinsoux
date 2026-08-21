package dev.shinsou.kmp.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RhinoScriptPluginRuntimeTest {
    @Test
    fun multiSourcePackageSelectsExactExportedSourceInsteadOfListPosition() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("No network request expected") },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val manifest = PluginManifest(
            id = "multi.rhino",
            name = "Multi Rhino",
            version = "2.0.0",
            versionCode = 2,
            lang = "all",
            script = "multi.rhino.js",
            signature = "",
            sources = listOf(
                SourceIndexEntry("One", "en", 101L, "https://one.example"),
                SourceIndexEntry("Two", "en", 202L, "https://two.example"),
            ),
        )
        val factory = RhinoScriptPluginRuntimeFactory()
        assertFailsWith<IllegalArgumentException> {
            factory.create(RHINO_MULTI_SOURCE_FIXTURE, manifest, ScriptPluginEnvironment(network, storage))
        }
        val one = factory.createForSource(
            RHINO_MULTI_SOURCE_FIXTURE,
            manifest,
            manifest.sources.orEmpty()[0],
            ScriptPluginEnvironment(network, storage),
        )
        val two = factory.createForSource(
            RHINO_MULTI_SOURCE_FIXTURE,
            manifest,
            manifest.sources.orEmpty()[1],
            ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals(101L, one.id)
            assertEquals("one|https://one.example", one.getPopularManga(0).mangas.single().title)
            assertEquals(202L, two.id)
            assertEquals("two|https://two.example", two.getPopularManga(0).mangas.single().title)
        } finally {
            one.close()
            two.close()
        }
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
            id = "zh.login-request",
            name = "Login Request",
            version = "1.0.0",
            versionCode = 1,
            lang = "zh",
            script = "zh.login-request.js",
            signature = "",
            sources = listOf(SourceIndexEntry("Login Source", "zh", 779, "https://source.example")),
        )
        val requests = mutableListOf<Triple<Long, String, String?>>()
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_LOGIN_REQUEST_FIXTURE,
            manifest,
            ScriptPluginEnvironment(
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
                    Triple(779L, "Login Source", "Members only"),
                    Triple(779L, "Login Source", null),
                ),
                requests,
            )
        } finally {
            runtime.close()
        }

        val noOpRuntime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_LOGIN_REQUEST_FIXTURE,
            manifest,
            ScriptPluginEnvironment(network, storage),
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
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_NO_FILTER_FIXTURE,
            PluginManifest(
                id = "zh.no-filter",
                name = "No Filter",
                version = "1.0.0",
                versionCode = 1,
                lang = "zh",
                script = "zh.no-filter.js",
                signature = "",
                sources = listOf(SourceIndexEntry("No Filter", "zh", 778, "https://source.example")),
            ),
            ScriptPluginEnvironment(network, storage),
        )
        try {
            assertTrue(runtime.getFilterList().isEmpty())
            assertEquals("needle|2", runtime.getSearchManga(2, "needle", emptyList()).mangas.single().title)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun runtimeSupportsDomSourceLifecycleFiltersStorageAndSerializedCalls() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val transport = PluginHttpTransport { request ->
            requests += request
            PluginHttpResponse(
                status = 200,
                body = DOM_FIXTURE.encodeToByteArray(),
                headers = emptyMap(),
            )
        }
        val network = PluginNetworkClient(
            transport = transport,
            storage = storage,
            requestBuilder = PluginRequestBuilder(storage, PluginUserAgentProvider { "rhino-agent" }),
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val manifest = PluginManifest(
            id = "all.rhino-test",
            name = "Rhino Test",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "all.rhino-test.js",
            signature = "",
            sources = listOf(SourceIndexEntry("Rhino Test", "all", 777, "https://source.example")),
        )
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_FIXTURE,
            manifest,
            ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals("https://source.example", runtime.baseUrl)
            assertTrue(runtime.supportsLatest)
            assertTrue(runtime.supportsLogin)
            assertEquals("yes", runtime.headers["X-Source"])

            assertTrue(runtime.login("alice", "secret"))
            assertEquals(PluginCredential("alice", "secret"), storage.getCredential(777))
            val popular = runtime.getPopularManga(0).mangas.single()
            val titleParts = popular.title.split('|')
            assertEquals("/m/1", popular.url)
            assertEquals("Lead Alpha", titleParts[0])
            assertEquals("Lead", titleParts[1])
            assertEquals("https://source.example/m/1", titleParts[2])
            assertEquals("root", titleParts[3])
            assertEquals("two", titleParts[4])
            assertEquals("ok", titleParts[5])
            assertEquals("true", titleParts[6])
            assertEquals("alice", popular.author)
            assertTrue(popular.description.orEmpty().contains("span"))
            assertEquals("session=abc", requests.single().headers["Cookie"])
            assertEquals("yes", requests.single().headers["X-Source"])
            assertEquals("rhino-agent", requests.single().headers["User-Agent"])
            assertEquals("https://source.example/", requests.single().headers["Referer"])

            val filters = runtime.getFilterList()
            assertEquals(9, filters.size)
            assertIs<Filter.Header>(filters[0])
            assertEquals(Filter.Separator, filters[1])
            assertIs<Filter.Select>(filters[2])
            assertIs<Filter.Text>(filters[3])
            assertIs<Filter.CheckBox>(filters[4])
            assertIs<Filter.TriState>(filters[5])
            assertIs<Filter.Group>(filters[6])
            assertIs<Filter.Sort>(filters[7])
            assertEquals("尾端", assertIs<Filter.Header>(filters[8]).name)

            val preferences = runtime.getPreferenceDefinitions()
            assertEquals(4, preferences.size)
            assertEquals("high", assertIs<SourcePreference.TextField>(preferences[0]).defaultValue)
            assertTrue(assertIs<SourcePreference.Toggle>(preferences[1]).defaultValue)
            assertEquals(listOf("zh-TW", "en"), assertIs<SourcePreference.Select>(preferences[2]).entryValues)
            assertEquals(setOf("safe"), assertIs<SourcePreference.MultiSelect>(preferences[3]).defaultValues)

            val search = runtime.getSearchManga(
                3,
                "needle",
                listOf(
                    Filter.Select("Genre", listOf("All", "Action"), 1),
                    Filter.Group("Nested", listOf(Filter.CheckBox("Only", true))),
                    Filter.Sort("Order", listOf("Date"), SortSelection(0, false)),
                ),
            )
            assertEquals("needle|3|1|true|false", search.mangas.single().title)

            val details = runtime.getMangaDetails(SManga(url = "/m/1", title = "Before"))
            assertEquals("Detailed", details.title)
            assertEquals(MangaStatus.ONGOING, details.status)
            val chapter = runtime.getChapterList(details).single()
            assertEquals("Chapter 1", chapter.name)
            assertEquals(1.5, chapter.chapterNumber)
            assertEquals(1234, chapter.dateUpload)
            val page = runtime.getPageList(chapter).single()
            assertEquals(4, page.index)
            assertEquals("https://images.example/1.jpg#Referer=x", page.imageUrl)

            val counters = coroutineScope {
                (1..12).map { async { runtime.getLatestUpdates(0).mangas.single().title.toInt() } }.awaitAll()
            }
            assertEquals((1..12).toList(), counters.sorted())

            runtime.logout()
            assertNull(storage.getCredential(777))
            assertTrue(storage.getCookies(777).isEmpty())
            assertFalse(runtime.login("alice", "wrong"))
        } finally {
            runtime.close()
        }
    }
}

private val RHINO_MULTI_SOURCE_FIXTURE = """
    var sources = {
      '101': {
        id: '101', baseUrl: 'https://script-one.invalid',
        getPopularManga: function(page) {
          var manga = SManga.create(); manga.url = '/one'; manga.title = 'one|' + baseUrl;
          return new MangasPage([manga], false);
        }
      },
      '202': {
        id: '202', baseUrl: 'https://script-two.invalid',
        getPopularManga: function(page) {
          var manga = SManga.create(); manga.url = '/two'; manga.title = 'two|' + baseUrl;
          return new MangasPage([manga], false);
        }
      }
    };
""".trimIndent()

private val RHINO_LOGIN_REQUEST_FIXTURE = """
    var source = {
      baseUrl: 'https://source.example',
      supportsLogin: true,
      getPopularManga: function(page) {
        var accepted = page === 0 ? bridge.requestLogin('Members only') : bridge.requestLogin();
        var manga = SManga.create();
        manga.url = '/login';
        manga.title = String(accepted);
        return new MangasPage([manga], false);
      }
    };
""".trimIndent()

private val RHINO_NO_FILTER_FIXTURE = """
    var source = {
      baseUrl: 'https://source.example',
      getSearchManga: function(page, query) {
        var manga = SManga.create();
        manga.url = '/search';
        manga.title = query + '|' + page;
        return new MangasPage([manga], false);
      }
    };
""".trimIndent()

private val DOM_FIXTURE = """
    <html><body>
      <div id="root">
        <a class="item allowed" href="/m/1">Lead <span>Alpha</span></a>
        <a class="item" data-skip="1" href="/m/2"><span>Skip</span></a>
        <p class="marker">Marker</p><p class="adjacent" data-value="ok">Next</p>
        <ul><li>one</li><li class="chosen">two</li></ul>
        <div class="remove"><b>Remove me</b></div>
      </div>
    </body></html>
""".trimIndent()

private val RHINO_FIXTURE = """
    var active = 0;
    var latestCounter = 0;
    var source = {
      baseUrl: 'https://source.example',
      supportsLatest: true,
      supportsLogin: true,
      headers: {'X-Source':'yes', 'Referer':'https://source.example/'},
      login: function(username, password) {
        if (password !== 'secret') return false;
        bridge.setPreference('display-name', username);
        bridge.setCookie('session', 'abc', '.source.example', '/', 0);
        return true;
      },
      logout: function() { bridge.clearCookies(); },
      getPopularManga: function(page) {
        var html = bridge.httpGet(this.baseUrl + '/popular?page=' + page);
        var doc = Jsoup.parse(html, this.baseUrl);
        var root = doc.selectFirst('#root');
        var link = root.selectFirst('a.item:has(span:contains(Alpha)):not([data-skip])');
        var nth = root.selectFirst('ul > li:nth-child(2)');
        var adjacent = root.selectFirst('.marker + .adjacent');
        var removable = root.selectFirst('.remove');
        removable.remove();
        var removed = root.select('.remove').isEmpty();
        var manga = SManga.create();
        manga.url = link.attr('href');
        manga.title = [link.text(), link.ownText().trim(), link.absUrl('href'),
          link.parent().id(), nth.text(), adjacent.attr('data-value'), String(removed)].join('|');
        manga.author = bridge.getPreference('display-name');
        manga.description = link.html();
        return new MangasPage([manga], false);
      },
      getLatestUpdates: function(page) {
        active++;
        if (active > 1) throw new Error('runtime was not serialized');
        var before = latestCounter;
        for (var i=0;i<10000;i++) { Math.sqrt(i); }
        latestCounter = before + 1;
        active--;
        var manga = SManga.create(); manga.url='/latest'; manga.title=String(latestCounter);
        return new MangasPage([manga], latestCounter < 12);
      },
      getSearchManga: function(page, query, filters) {
        var manga = SManga.create(); manga.url='/search';
        manga.title = [query, page, filters[0].state, filters[1].filters[0].state,
          filters[2].selection.ascending].join('|');
        return new MangasPage([manga], true);
      },
      getMangaDetails: function(manga) {
        manga.title='Detailed'; manga.status=SManga.ONGOING; manga.initialized=true; return manga;
      },
      getChapterList: function(manga) {
        var chapter=SChapter.create(); chapter.url='/chapter/1'; chapter.name='Chapter 1';
        chapter.dateUpload=1234; chapter.chapterNumber=1.5; return [chapter];
      },
      getPageList: function(chapter) { return [new Page(4, '', 'https://images.example/1.jpg#Referer=x')]; },
      getFilterList: function() { return [
        {type:'header',name:'篩選'}, {type:'separator'},
        {type:'select',name:'Genre',values:['All','Action'],state:1},
        {type:'text',name:'Keyword',state:'abc'},
        {type:'checkBox',name:'Only',state:true},
        {type:'triState',name:'Included',state:2},
        {type:'group',name:'Nested',filters:[{type:'checkBox',name:'Child',state:false}]},
        {type:'sort',name:'Order',values:['Date','Title'],selection:{index:1,ascending:false}},
        {type:'header',name:'尾端'}
      ]; },
      getPreferenceDefinitions: function() { return [
        {type:'textField',key:'quality',title:'Quality',summary:'Image quality',defaultValue:'high'},
        {type:'toggle',key:'enabled',title:'Enabled',summary:'Use feature',defaultValue:true},
        {type:'select',key:'language',title:'Language',entries:['繁中','English'],entryValues:['zh-TW','en'],defaultValue:'zh-TW'},
        {type:'multiSelect',key:'content',title:'Content',entries:['Safe','Adult'],entryValues:['safe','adult'],defaultValues:['safe']}
      ]; }
    };
""".trimIndent()
