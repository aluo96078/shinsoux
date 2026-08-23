package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import dev.shinsou.kmp.plugin.events.LoginRequestV1
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
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
import dev.shinsou.kmp.plugin.events.TypedPluginSystemEventHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
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
    fun directRuntimeCannotUseLegacyLoginRequesterWithoutExactHostAdmission() = runTest {
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
            assertEquals("false", runtime.getPopularManga(0).mangas.single().title)
            assertEquals("false", runtime.getPopularManga(1).mangas.single().title)
            assertEquals(emptyList(), requests)
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
    fun systemEventReceiptAndCapabilitiesStayBoundedAndSourceScoped() = runTest {
        val fixture = rhinoSystemEventFixture()
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_SYSTEM_EVENT_FIXTURE,
            fixture.manifest,
            fixture.environment,
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
        val fixture = rhinoSystemEventFixture(
            declaration = PluginSystemEventDeclaration(
                minVersion = 1,
                maxVersion = 1,
                required = setOf(PluginSystemEventNames.REFRESH_CAPABILITY),
            ),
            permissions = setOf(PluginHostPermission.REQUEST_SOURCE_REFRESH),
            sourceCapabilities = setOf("CATALOGUE"),
        )
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_ACTIVE_CONTEXT_FIXTURE,
            fixture.manifest,
            fixture.environment,
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
    fun classShutterDeniesPackagesJavaRuntimeAndReflectionWithoutBreakingBridge() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("No network request expected") },
            storage = storage,
        )
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_SANDBOX_ESCAPE_FIXTURE,
            PluginManifest(
                id = "rhino.sandbox.escape",
                name = "Rhino sandbox escape",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "rhino.sandbox.escape.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Sandbox", "all", 778, "https://source.example")),
            ),
            ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals("true|true|true|true|true|true", runtime.getPopularManga(0).mangas.single().title)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun httpPostBatchBridgeReturnsOrderedJsonResponses() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val requests = CopyOnWriteArrayList<PluginHttpRequest>()
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                requests += request
                PluginHttpResponse(200, request.url.substringAfterLast('/').encodeToByteArray())
            },
            storage = storage,
            requestGate = PerHostRequestGate(
                PluginRateLimitProvider { PluginRateLimit(32, 0) },
            ),
        )
        val runtime = RhinoScriptPluginRuntimeFactory().create(
            RHINO_BATCH_FIXTURE,
            PluginManifest(
                id = "zh.batch",
                name = "Batch",
                version = "1.0.0",
                versionCode = 1,
                lang = "zh",
                script = "zh.batch.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Batch", "zh", 779, "https://batch.example")),
            ),
            ScriptPluginEnvironment(network, storage),
        )
        try {
            assertEquals("one|two|three", runtime.getPopularManga(0).mangas.single().title)
            assertEquals(3, requests.size)
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

private val RHINO_BATCH_FIXTURE = """
    var source = {
      id: '779', baseUrl: 'https://batch.example',
      getPopularManga: function(page) {
        var raw = bridge.httpPostBatch(
          [this.baseUrl + '/one', this.baseUrl + '/two', this.baseUrl + '/three'],
          ['body-one', 'body-two', 'body-three'],
          {'Accept':'text/plain'}
        );
        var values = JSON.parse(raw || '[]');
        var manga = SManga.create(); manga.url = '/batch'; manga.title = values.join('|');
        return new MangasPage([manga], false);
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

private val RHINO_SYSTEM_EVENT_FIXTURE = """
    var source = {
      baseUrl: 'https://source.example',
      supportsLogin: true,
      getPopularManga: function(page) {
        var capabilities = bridge.getHostEventCapabilities();
        var receipt = bridge.system.requestLogin('Members only');
        var manga = SManga.create();
        manga.url = '/events';
        manga.title = [String(capabilities.enabled), String(capabilities.version),
          capabilities.grantedCapabilities.join(','), receipt.disposition].join('|');
        return new MangasPage([manga], false);
      }
    };
""".trimIndent()

private val RHINO_ACTIVE_CONTEXT_FIXTURE = """
    var source = {
      baseUrl: 'https://source.example',
      getPopularManga: function(page) {
        var contextPresent = bridge.getHostEventContext() !== null;
        var receipt = bridge.system.requestRefresh('ACTIVE_CONTEXT');
        var manga = SManga.create();
        manga.url = '/active-context';
        manga.title = [String(contextPresent), receipt.disposition].join('|');
        return new MangasPage([manga], false);
      }
    };
""".trimIndent()

private val RHINO_SANDBOX_ESCAPE_FIXTURE = """
    var source = {
      baseUrl: 'https://source.example',
      getPopularManga: function(page) {
        var packagesDenied = false, javaDenied = false, runtimeDenied = false;
        var getClassDenied = false, forNameDenied = false, bridgeAlive = false;
        try { packagesDenied = Packages.java.lang.System === undefined; } catch (e) { packagesDenied = true; }
        try { javaDenied = java.lang.System === undefined; } catch (e) { javaDenied = true; }
        try { runtimeDenied = java.lang.Runtime === undefined; } catch (e) { runtimeDenied = true; }
        try { getClassDenied = bridge.getClass() === undefined; } catch (e) { getClassDenied = true; }
        try {
          var klass = bridge.getClass();
          forNameDenied = klass === undefined || klass.forName('java.lang.Runtime') === undefined;
        } catch (e) { forNameDenied = true; }
        try { bridgeAlive = bridge.getPreference('missing') === null; } catch (e) { bridgeAlive = false; }
        var manga = SManga.create();
        manga.url = '/sandbox';
        manga.title = [packagesDenied, javaDenied, runtimeDenied, getClassDenied, forNameDenied, bridgeAlive].join('|');
        return new MangasPage([manga], false);
      }
    };
""".trimIndent()

private data class RhinoSystemEventFixture(
    val gateway: PluginSystemEventGateway,
    val environment: ScriptPluginEnvironment,
    val manifest: PluginManifest,
    val loginReasons: MutableList<String?>,
    val contextRegistry: PluginEventContextRegistry,
    val scope: BoundPluginScope,
)

private fun rhinoSystemEventFixture(
    declaration: PluginSystemEventDeclaration = PluginSystemEventDeclaration(
        minVersion = 1,
        maxVersion = 1,
        required = setOf(PluginSystemEventNames.LOGIN_CAPABILITY),
        optional = setOf(PluginSystemEventNames.DIAGNOSTIC_CAPABILITY),
    ),
    permissions: Set<PluginHostPermission> = setOf(PluginHostPermission.REQUEST_LOGIN_UI),
    sourceCapabilities: Set<String> = setOf("LOGIN"),
): RhinoSystemEventFixture {
    val source = SourceKey(2, "rhino.events", "778")
    val artifact = PluginArtifactIdentity(
        packageId = source.packageId,
        version = "1.0.0",
        versionCode = 1,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
    val scope = BoundPluginScopeFactory().bind(
        artifactIdentity = artifact,
        sourceKey = source,
        runtimeInstanceId = "rhino-events-runtime",
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
            TypedPluginSystemEventHandler<LoginRequestV1>(
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
    val contextRegistry = PluginEventContextRegistry(handleFactory = { "ctx-rhino-test" })
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
    val sourceEntry = SourceIndexEntry("Rhino events", "all", 778, "https://source.example")
    return RhinoSystemEventFixture(
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
            name = "Rhino events",
            version = artifact.version,
            versionCode = artifact.versionCode,
            lang = "all",
            script = "rhino.events.js",
            signature = artifact.sha256,
            sources = listOf(sourceEntry),
            systemEvents = declaration,
        ),
        loginReasons = loginReasons,
        contextRegistry = contextRegistry,
        scope = scope,
    )
}

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
