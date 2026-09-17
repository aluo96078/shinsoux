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
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RhinoScriptPluginRuntimeTest {
    @Test
    fun sourceFailureMarkerRetainsRecognizableAnchoredRhinoWrapper() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        for (marker in listOf("SHINSOU_SOURCE_HTTP_FORBIDDEN", "SHINSOU_SOURCE_QUOTA_EXCEEDED")) {
            val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
                script = "var source={baseUrl:'https://source.example',getPopularManga:function(){throw new Error('$marker');}};",
                manifest = PluginManifest(
                    "failure.rhino", "Failure Rhino", "1.0.0", 1, "all",
                    script = "failure.rhino.js", signature = "",
                    sources = listOf(SourceIndexEntry("Failure", "all", 116L, "https://source.example")),
                ),
                environment = ScriptPluginEnvironment(
                    PluginNetworkClient(
                        PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage,
                        policy = rhinoTestNetworkPolicy("https://source.example"),
                        hostResolver = RHINO_TEST_HOST_RESOLVER,
                    ),
                    storage,
                    runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                ),
            )
            try {
                val error = assertFailsWith<Throwable> { runtime.getPopularManga(1) }
                assertNotNull(error.localizedSourceFailure(dev.shinsou.kmp.ui.i18n.shinsouStringsFor("en")))
            } finally {
                runtime.close()
            }
        }
    }
    @Test
    fun productionFactoryRejectsRepositoryScriptWithoutHostReviewedProvenance() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val source = SourceIndexEntry("Unreviewed", "all", 991L, "https://source.example")
        val manifest = PluginManifest(
            id = "unreviewed.rhino",
            name = "Unreviewed Rhino",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "unreviewed.rhino.js",
            signature = "0".repeat(64),
            sources = listOf(source),
        )

        val failure = assertFailsWith<ScriptRuntimeUnavailableException> {
            RhinoScriptPluginRuntimeFactory().createForSource(
                "var source = {};",
                manifest,
                source,
                ScriptPluginEnvironment(
                    network = PluginNetworkClient(
                        PluginHttpTransport { error("No network request expected") },
                        storage,
                    ),
                    storage = storage,
                    runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("per-runtime heap isolation"))
    }

    @Test
    fun productionFactoryRejectsBytesThatDoNotMatchReviewedProvenance() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val source = SourceIndexEntry(
            name = "Reviewed",
            lang = "all",
            id = 992L,
            baseUrl = "https://source.example",
            canonicalSourceId = "reviewed-source",
        )
        val artifact = PluginArtifactIdentity(
            packageId = "reviewed.rhino",
            version = "1.0.0",
            versionCode = 1,
            sha256 = "1".repeat(64),
        )
        val manifest = PluginManifest(
            id = artifact.packageId,
            name = "Reviewed Rhino",
            version = artifact.version,
            versionCode = artifact.versionCode,
            lang = "all",
            script = "reviewed.rhino.js",
            signature = artifact.sha256,
            sources = listOf(source),
        )
        val reviewedScript = "var source = { id: 'reviewed-source' };"
        val environment = ScriptPluginEnvironment(
            network = PluginNetworkClient(
                PluginHttpTransport { error("No network request expected") },
                storage,
            ),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            inProcessScriptProvenance = InProcessScriptProvenance.reviewedArtifact(
                artifact = artifact,
                sourceKey = SourceKey(
                    packageId = artifact.packageId,
                    sourceId = "reviewed-source",
                    legacyLongId = source.id,
                ),
                evaluatedScript = reviewedScript,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            RhinoScriptPluginRuntimeFactory().createForSource(
                reviewedScript + " ",
                manifest,
                source,
                environment,
            )
        }
    }

    @Test
    fun cancellingSearchCancelsHttpGetAndReleasesEngineWorker() = runBlocking {
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
            policy = rhinoTestNetworkPolicy("https://source.example"),
            hostResolver = RHINO_TEST_HOST_RESOLVER,
        )
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = """
                var source={
                  baseUrl:'https://source.example',
                  getSearchManga:function(page,query){
                    var body=bridge.httpGet(this.baseUrl+'/'+query);
                    var manga=SManga.create();manga.url='/'+query;manga.title=query+'|'+body;
                    return new MangasPage([manga],false);
                  }
                };
            """.trimIndent(),
            manifest = PluginManifest(
                "cancel.rhino", "Cancellation Rhino", "1.0.0", 1, "all",
                script = "cancel.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Cancellation", "all", 115L, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.NETWORK,
                ),
            ),
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
    fun bridgeEnforcesRuntimePermissionsWhileNetworkKeepsCookieSideEffectsOptional() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = """
                var source = {
                  baseUrl: 'https://source.example',
                  getPopularManga: function() {
                    var response = bridge.httpGet(this.baseUrl + '/blocked');
                    var cookie = bridge.getCookie('sid', this.baseUrl + '/');
                    var manga = SManga.create();
                    manga.url = '/permissions';
                    manga.title = String(response.error) + '|' + String(cookie);
                    return new MangasPage([manga], false);
                  }
                };
            """.trimIndent(),
            manifest = PluginManifest(
                id = "permissions.rhino",
                name = "Permissions Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "permissions.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Permissions", "all", 123L, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(PluginHttpTransport { error("network must be blocked") }, storage),
                storage = storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
        )
        try {
            assertEquals(
                "Plugin runtime lacks NETWORK permission|null",
                runtime.getPopularManga(1).mangas.single().title,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun webChallengeStorageDeclarationCrossesRhinoRuntimeMetadata() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = RHINO_WEB_CHALLENGE_STORAGE_FIXTURE,
            manifest = PluginManifest(
                id = "challenge.rhino",
                name = "Challenge Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "challenge.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Challenge", "all", 101L, "https://example.test")),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { error("No network request expected") },
                    storage,
                ),
                storage = storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
        )
        try {
            assertEquals("https://example.test/", runtime.webChallengeUrl)
            assertEquals(setOf("token", "nonce"), runtime.webChallengeLocalStorageKeys)
            assertEquals(setOf("token"), runtime.requiredWebChallengeLocalStorageKeys)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun instructionBudgetTerminatesAndPoisonsAnInfiniteLoop() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = """
                var source = {
                  baseUrl: 'https://source.example',
                  getPopularManga: function() { while (true) {} }
                };
            """.trimIndent(),
            manifest = PluginManifest(
                id = "limit.rhino",
                name = "Limit Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "limit.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Limit", "all", 111L, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(PluginHttpTransport { error("No network expected") }, storage),
                storage = storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                executionLimits = PluginExecutionLimits(
                    invocationWallTimeMillis = 1_000,
                    invocationInstructionCount = 50_000,
                    instructionObserverThreshold = 1_000,
                ),
            ),
        )
        try {
            assertFailsWith<PluginResourceLimitException> { runtime.getPopularManga(1) }
            assertFailsWith<PluginResourceLimitException> { runtime.getPopularManga(1) }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun bridgeAndLogLimitsAreBounded() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = """
                var source = {
                  baseUrl: 'https://source.example',
                  getPopularManga: function() {
                    for (var i=0; i<20; i++) bridge.log('0123456789abcdef');
                    return new MangasPage([], false);
                  }
                };
            """.trimIndent(),
            manifest = PluginManifest(
                id = "log-limit.rhino",
                name = "Log limit Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "log-limit.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Limit", "all", 112L, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(PluginHttpTransport { error("No network expected") }, storage),
                storage = storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                executionLimits = PluginExecutionLimits(
                    maxBridgeCallsPerInvocation = 64,
                    maxLogEntries = 3,
                    maxLogBytes = 24,
                    maxLogEntryBytes = 8,
                ),
            ),
        )
        try {
            runtime.getPopularManga(1)
            assertEquals(3, runtime.recentLogs.size)
            assertTrue(runtime.recentLogs.all { it.encodeToByteArray().size <= 8 })
            assertTrue(runtime.recentLogs.sumOf { it.encodeToByteArray().size } <= 24)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun oversizedResultStringPoisonsRuntimeBeforeDomainMapping() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = """
                var source = {
                  baseUrl: 'https://source.example',
                  getPopularManga: function() {
                    var manga = SManga.create();
                    manga.url = '/large'; manga.title = new Array(257).join('x');
                    return new MangasPage([manga], false);
                  }
                };
            """.trimIndent(),
            manifest = PluginManifest(
                id = "result-string-limit.rhino",
                name = "Result limit Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "result-limit.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Limit", "all", 113L, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(PluginHttpTransport { error("No network expected") }, storage),
                storage = storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                executionLimits = PluginExecutionLimits(
                    maxResultStringBytes = 128,
                    maxResultBytes = 2_048,
                ),
            ),
        )
        try {
            assertFailsWith<PluginResourceLimitException> { runtime.getPopularManga(1) }
            assertFailsWith<PluginResourceLimitException> { runtime.getPopularManga(1) }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun oversizedResultArrayPoisonsRuntimeBeforeAllocation() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script = """
                var source = {
                  baseUrl: 'https://source.example',
                  getChapterList: function() { return new Array(65); }
                };
            """.trimIndent(),
            manifest = PluginManifest(
                id = "result-array-limit.rhino",
                name = "Array limit Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "array-limit.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("Limit", "all", 114L, "https://source.example")),
            ),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(PluginHttpTransport { error("No network expected") }, storage),
                storage = storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                executionLimits = PluginExecutionLimits(maxResultArrayElements = 64),
            ),
        )
        try {
            assertFailsWith<PluginResourceLimitException> {
                runtime.getChapterList(SManga(url = "/manga", title = "Manga"))
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun multiSourceLoginFailuresPreserveMessagesThroughRhino() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { error("No network request expected") },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val manifest = PluginManifest(
            id = "zh.bilimanga",
            name = "Bili probe",
            version = "1.5.5",
            versionCode = 11,
            lang = "zh",
            script = "zh.bilimanga.js",
            signature = "",
            sources = listOf(
                SourceIndexEntry(
                    name = "Novel probe",
                    lang = "zh",
                    id = -9_110_000_000_000_004L,
                    baseUrl = "https://tw.linovelib.com",
                    canonicalSourceId = "zh.bilimanga.novel",
                ),
                SourceIndexEntry(
                    name = "Manga probe",
                    lang = "zh",
                    id = -9_110_000_000_000_005L,
                    baseUrl = "https://www.bilimanga.net",
                    canonicalSourceId = "zh.bilimanga.manga",
                ),
            ),
        )
        for (source in manifest.sources.orEmpty()) {
            val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().createForSource(
                RHINO_MULTI_SOURCE_LOGIN_FAILURE_FIXTURE,
                manifest,
                source,
                ScriptPluginEnvironment(
                    network,
                    storage,
                    runtimePermissions = setOf(
                        PluginRuntimePermission.EXECUTE_SCRIPT,
                        PluginRuntimePermission.CREDENTIAL_ACCESS,
                    ),
                ),
            )
            try {
                val result = runtime.loginResult("fixture-user", "fixture-password")
                assertFalse(result.loggedIn)
                assertEquals(
                    "${source.canonicalSourceId} login requires a browser challenge",
                    result.errorMessage,
                )
            } finally {
                runtime.close()
            }
        }
    }

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
        val factory = RhinoScriptPluginRuntimeFactory.unsafeForTests()
        assertFailsWith<IllegalArgumentException> {
            factory.create(
                RHINO_MULTI_SOURCE_FIXTURE,
                manifest,
                ScriptPluginEnvironment(
                    network,
                    storage,
                    runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
                ),
            )
        }
        val one = factory.createForSource(
            RHINO_MULTI_SOURCE_FIXTURE,
            manifest,
            manifest.sources.orEmpty()[0],
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
        )
        val two = factory.createForSource(
            RHINO_MULTI_SOURCE_FIXTURE,
            manifest,
            manifest.sources.orEmpty()[1],
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
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
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            RHINO_LOGIN_REQUEST_FIXTURE,
            manifest,
            ScriptPluginEnvironment(
                network = network,
                storage = storage,
                loginRequester = PluginLoginRequester { sourceId, sourceName, reason ->
                    requests += Triple(sourceId, sourceName, reason)
                    true
                },
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
        )
        try {
            assertEquals("false", runtime.getPopularManga(0).mangas.single().title)
            assertEquals("false", runtime.getPopularManga(1).mangas.single().title)
            assertEquals(emptyList(), requests)
        } finally {
            runtime.close()
        }

        val noOpRuntime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            RHINO_LOGIN_REQUEST_FIXTURE,
            manifest,
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
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
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
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
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
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
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
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
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
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
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
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
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
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
            policy = rhinoTestNetworkPolicy("https://batch.example"),
            hostResolver = RHINO_TEST_HOST_RESOLVER,
        )
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
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
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.NETWORK,
                ),
            ),
        )
        try {
            assertEquals("one|two|three", runtime.getPopularManga(0).mangas.single().title)
            assertEquals(3, requests.size)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun httpPostResponseBridgePreservesNonSuccessStatusAndBodyForLogin() = runTest {
        suspend fun loginError(response: suspend () -> PluginHttpResponse): String? {
            val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
            val network = PluginNetworkClient(
                transport = PluginHttpTransport { response() },
                storage = storage,
                requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                policy = rhinoTestNetworkPolicy("https://api.example"),
                hostResolver = RHINO_TEST_HOST_RESOLVER,
            )
            val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
                RHINO_HTTP_LOGIN_ERROR_FIXTURE,
                PluginManifest(
                    id = "zh.login-error.rhino",
                    name = "Login Error Rhino",
                    version = "1.0.0",
                    versionCode = 1,
                    lang = "zh",
                    script = "zh.login-error.rhino.js",
                    signature = "",
                    sources = listOf(SourceIndexEntry("Login Error", "zh", 780, "https://api.example")),
                ),
                ScriptPluginEnvironment(
                    network,
                    storage,
                    runtimePermissions = setOf(
                        PluginRuntimePermission.EXECUTE_SCRIPT,
                        PluginRuntimePermission.NETWORK,
                        PluginRuntimePermission.CREDENTIAL_ACCESS,
                    ),
                ),
            )
            return try {
                runtime.loginResult("alice", "wrong").errorMessage
            } finally {
                runtime.close()
            }
        }

        assertEquals(
            "error code: 502",
            loginError { PluginHttpResponse(502, "error code: 502".encodeToByteArray()) },
        )
        assertEquals(
            "too many requests",
            loginError {
                PluginHttpResponse(
                    400,
                    """{"code":400,"message":"too many requests"}""".encodeToByteArray(),
                )
            },
        )
    }

    @Test
    fun httpGetResponseBridgePreservesNonSuccessStatusAndBody() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            RHINO_HTTP_GET_RESPONSE_FIXTURE,
            PluginManifest(
                id = "zh.get-response.rhino",
                name = "GET Response Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "zh",
                script = "zh.get-response.rhino.js",
                signature = "",
                sources = listOf(SourceIndexEntry("GET Response", "zh", 781, "https://api.example")),
            ),
            ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport {
                        PluginHttpResponse(
                            429,
                            """{"code":400,"message":"too many requests"}""".encodeToByteArray(),
                        )
                    },
                    storage = storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                    policy = rhinoTestNetworkPolicy("https://api.example"),
                    hostResolver = RHINO_TEST_HOST_RESOLVER,
                ),
                storage = storage,
                runtimePermissions = setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.NETWORK,
                ),
            ),
        )
        try {
            assertEquals("429|too many requests", runtime.getPopularManga(0).mangas.single().title)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun browserSessionBridgeUsesOnlyManifestDeclaredOrigin() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val captured = mutableListOf<PluginHttpRequest>()
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            RHINO_BROWSER_SESSION_FIXTURE,
            PluginManifest(
                id = "zh.browser-session.rhino",
                name = "Browser Session Rhino",
                version = "1.0.0",
                versionCode = 1,
                lang = "zh",
                script = "zh.browser-session.rhino.js",
                signature = "",
                sources = listOf(
                    SourceIndexEntry(
                        "Browser Session",
                        "zh",
                        782,
                        "https://source.example",
                        browserSessionOrigins = setOf("https://api.example"),
                    ),
                ),
            ),
            ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { error("Native transport must not be used") },
                    storage,
                ),
                storage = storage,
                browserSessionTransport = object : PluginBrowserSessionTransport {
                    override suspend fun execute(
                        sourceId: Long,
                        sourceOrigin: String,
                        allowedOrigins: Set<String>,
                        request: PluginHttpRequest,
                    ): PluginHttpResponse {
                        assertEquals(782, sourceId)
                        assertEquals("https://source.example", sourceOrigin)
                        assertEquals(setOf("https://api.example"), allowedOrigins)
                        captured += request
                        return PluginHttpResponse(429, "limited".encodeToByteArray())
                    }
                },
                hostResolver = RHINO_TEST_HOST_RESOLVER,
                allowDeveloperUnpinnedBrowserSession = true,
                runtimePermissions = setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.BROWSER_CHALLENGE,
                ),
            ),
        )
        try {
            assertEquals("429|limited", runtime.getPopularManga(0).mangas.single().title)
            assertEquals("https://api.example/catalogue", captured.single().url)
            assertEquals("signed", captured.single().headers["Authorization"])
            assertFalse(captured.single().headers.keys.any { it.equals("Cookie", true) })
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
            policy = rhinoTestNetworkPolicy("https://source.example"),
            hostResolver = RHINO_TEST_HOST_RESOLVER,
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
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            RHINO_FIXTURE,
            manifest,
            ScriptPluginEnvironment(
                network,
                storage,
                runtimePermissions = setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.NETWORK,
                    PluginRuntimePermission.COOKIE_STORAGE,
                    PluginRuntimePermission.CREDENTIAL_ACCESS,
                ),
            ),
        )
        try {
            assertEquals("https://source.example", runtime.baseUrl)
            assertTrue(runtime.supportsLatest)
            assertTrue(runtime.supportsLogin)
            assertEquals("yes", runtime.headers["X-Source"])

            val failedLogin = runtime.loginResult("alice", "wrong")
            assertFalse(failedLogin.loggedIn)
            assertEquals("帳號或密碼錯誤", failedLogin.errorMessage)
            assertNull(storage.getCredential(777))
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

private val RHINO_TEST_HOST_RESOLVER = PluginHostResolver { listOf("93.184.216.34") }

private fun rhinoTestNetworkPolicy(origin: String): PluginNetworkPolicy = PluginNetworkPolicy(
    requestOrigins = setOf(origin),
    credentialOrigins = setOf(origin),
    allowDeveloperUnpinnedTransport = true,
)

private val RHINO_WEB_CHALLENGE_STORAGE_FIXTURE = """
    var source = {
      baseUrl: 'https://example.test',
      webChallengeUrl: 'https://example.test/',
      webChallengeLocalStorageKeys: ['token', 'nonce'],
      requiredWebChallengeLocalStorageKeys: ['token'],
      getPopularManga: function() { return new MangasPage([], false); },
      getLatestUpdates: function() { return new MangasPage([], false); },
      getSearchManga: function() { return new MangasPage([], false); },
      getMangaDetails: function(manga) { return manga; },
      getChapterList: function() { return []; },
      getPageList: function() { return []; },
      getFilterList: function() { return []; }
    };
""".trimIndent()

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

private val RHINO_MULTI_SOURCE_LOGIN_FAILURE_FIXTURE = """
    var sources = {
      'zh.bilimanga.novel': {
        id: 'zh.bilimanga.novel',
        supportsLogin: true,
        login: function() {
          return {
            loggedIn: false,
            errorMessage: 'zh.bilimanga.novel login requires a browser challenge'
          };
        }
      },
      'zh.bilimanga.manga': {
        id: 'zh.bilimanga.manga',
        supportsLogin: true,
        login: function() {
          return {
            loggedIn: false,
            errorMessage: 'zh.bilimanga.manga login requires a browser challenge'
          };
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

private val RHINO_HTTP_LOGIN_ERROR_FIXTURE = """
    var source = {
      baseUrl: 'https://api.example', supportsLogin: true,
      login: function(username, password) {
        var raw = bridge.httpPostResponse(
          this.baseUrl + '/auth/sign-in',
          JSON.stringify({email: username, password: password}),
          {}
        );
        if (raw && typeof raw === 'object' && raw.error) {
          return {loggedIn: false, errorMessage: String(raw.error)};
        }
        var text = typeof raw === 'string' ? raw :
          (raw && raw.body != null ? String(raw.body) : '');
        try {
          var response = JSON.parse(text);
          return {
            loggedIn: false,
            errorMessage: String(response.message || response.detail || response.error || 'Login failed')
          };
        } catch (e) {
          return {loggedIn: false, errorMessage: text || 'No response'};
        }
      }
    };
""".trimIndent()

private val RHINO_HTTP_GET_RESPONSE_FIXTURE = """
    var source = {
      baseUrl: 'https://api.example',
      getPopularManga: function() {
        var raw = bridge.httpGetResponse(this.baseUrl + '/comics', {});
        var parsed = JSON.parse(String(raw.body || '{}'));
        var manga = SManga.create();
        manga.url = '/diagnostic';
        manga.title = String(raw.status) + '|' + String(parsed.message || '');
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
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
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
        if (password !== 'secret') return {loggedIn:false,errorMessage:'帳號或密碼錯誤'};
        bridge.setPreference('display-name', username);
        bridge.setCookie('session', 'abc', '.source.example', '/', 0);
        return {loggedIn:true};
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

private val RHINO_BROWSER_SESSION_FIXTURE = """
var source={
  getPopularManga:function(){
    var response=bridge.browserSessionRequest('https://api.example/catalogue','GET','',{
      Authorization:'signed',Cookie:'forged=1'
    });
    var manga=SManga.create();manga.url='/result';manga.title=String(response.status)+'|'+String(response.body);
    return new MangasPage([manga],false);
  },
  getLatestUpdates:function(){return new MangasPage([],false);},
  getSearchManga:function(){return new MangasPage([],false);},
  getMangaDetails:function(manga){return manga;},getChapterList:function(){return[];},getPageList:function(){return[];}
};
""".trimIndent()
