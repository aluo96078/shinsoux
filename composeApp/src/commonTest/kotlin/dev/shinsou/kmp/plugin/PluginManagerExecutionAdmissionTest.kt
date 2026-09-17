package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManagerExecutionAdmissionTest {
    @Test
    fun installIsInertUntilTheExactArtifactReviewIsApproved() = runTest {
        val fixture = Fixture()

        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))

        assertTrue(fixture.factory.created.isEmpty())
        assertNull(fixture.manager.source(SOURCE_ID))
        val pending = requireNotNull(fixture.manager.pendingEventGrantReview(PLUGIN_ID))
        assertEquals(setOf(PluginRuntimePermission.EXECUTE_SCRIPT), pending.requestedRuntimePermissions)
        assertFalse(fixture.manager.hasExecutionApproval(PLUGIN_ID))

        fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())

        assertEquals(1, fixture.factory.created.size)
        assertEquals(SOURCE_ID, fixture.manager.source(SOURCE_ID)?.id)
        assertTrue(fixture.manager.hasExecutionApproval(PLUGIN_ID))
    }

    @Test
    fun staleDisplayedReviewCannotApproveAReplacementArtifact() = runTest {
        val fixture = Fixture()
        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))
        val displayed = requireNotNull(fixture.manager.pendingEventGrantReview(PLUGIN_ID))

        fixture.script = UPDATED_SCRIPT
        fixture.manager.update(REPOSITORY, fixture.entry(version = "2.0.0", versionCode = 2))
        val replacement = requireNotNull(fixture.manager.pendingEventGrantReview(PLUGIN_ID))
        assertFalse(displayed.artifact == replacement.artifact)

        assertFailsWith<IllegalArgumentException> {
            fixture.manager.approveEventGrantReview(PLUGIN_ID, displayed, emptySet())
        }

        assertFalse(fixture.manager.hasExecutionApproval(PLUGIN_ID))
        assertTrue(fixture.factory.created.isEmpty())
        assertEquals(replacement, fixture.manager.pendingEventGrantReview(PLUGIN_ID))
    }

    @Test
    fun failedUpdateKeepsThePreviousExactGrantAndRuntimeLive() = runTest {
        val packageStore = FailNextPutPackageStore()
        val fixture = Fixture(packageStore)
        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))
        fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        val original = requireNotNull(fixture.manager.source(SOURCE_ID))
        val originalRuntime = fixture.factory.created.single()

        fixture.script = UPDATED_SCRIPT
        packageStore.failNextPut = true
        assertFailsWith<IllegalStateException> {
            fixture.manager.update(REPOSITORY, fixture.entry(version = "2.0.0", versionCode = 2))
        }

        assertTrue(fixture.manager.source(SOURCE_ID) === original)
        assertFalse(originalRuntime.closed)
        assertEquals("1.0.0", fixture.manager.installedPlugins().single().manifest.version)
        assertTrue(fixture.manager.hasExecutionApproval(PLUGIN_ID))
    }

    @Test
    fun legacyGrantCleanupFailureStillCommitsRevocationAndUnloadsRuntime() = runTest {
        val keyValues = FailingRemoveKeyValueStore()
        val fixture = Fixture(keyValues = keyValues)
        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))
        fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        val runtime = fixture.factory.created.single()

        keyValues.failRemoves = true
        fixture.manager.revokeEventGrants(PLUGIN_ID)

        assertTrue(runtime.closed)
        assertNull(fixture.manager.source(SOURCE_ID))
        assertFalse(fixture.manager.hasExecutionApproval(PLUGIN_ID))
        assertTrue(fixture.manager.loadInstalled().isEmpty())
    }

    @Test
    fun locallyRevokedRuntimeCannotSurviveAReenableAttempt() = runTest {
        val keyValues = FailingRemoveKeyValueStore()
        val fixture = Fixture(keyValues = keyValues)
        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))
        fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        val runtime = fixture.factory.created.single()

        keyValues.failRemoves = true
        fixture.manager.revokeEventGrants(PLUGIN_ID)
        fixture.manager.setEventSourceEnabled(
            dev.shinsou.kmp.domain.model.SourceKey.fromLegacy(PLUGIN_ID, SOURCE_ID),
            enabled = true,
        )

        assertTrue(runtime.closed)
        assertNull(fixture.manager.source(SOURCE_ID))
        assertEquals(1, fixture.factory.created.size)
    }

    @Test
    fun storedExecutableWithNullRuntimePermissionsStaysInertDespiteExactGrant() = runTest {
        val fixture = Fixture()
        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))
        fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        val originalRuntime = fixture.factory.created.single()
        val grantedArtifact = fixture.packageStore.list().single()

        fixture.packageStore.put(
            grantedArtifact.copy(
                metadata = grantedArtifact.metadata.copy(
                    manifest = grantedArtifact.manifest.copy(runtimePermissions = null),
                ),
            ),
        )

        assertTrue(fixture.manager.loadInstalled().isEmpty())
        assertTrue(originalRuntime.closed)
        assertEquals(1, fixture.factory.created.size)
        assertNull(fixture.manager.source(SOURCE_ID))
        assertFalse(fixture.manager.hasExecutionApproval(PLUGIN_ID))
        assertFailsWith<IllegalArgumentException> {
            fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        }
        assertTrue(fixture.manager.loadInstalled().isEmpty())
        assertEquals(1, fixture.factory.created.size)
    }

    @Test
    fun genericInstallRejectsForgedV2ContractRuntimePairsBeforeDownload() = runTest {
        val fixture = Fixture()
        val forgedEntries = listOf(
            fixture.entry("1.0.0", 1).copy(
                contract = "shuyue",
                runtime = "reviewed-shuyue-adapter-v2",
            ),
            fixture.entry("1.0.0", 1).copy(
                contract = "shinsou",
                runtime = "attacker-runtime-v2",
            ),
            fixture.entry("1.0.0", 1).copy(sidecarUrl = "sidecars/forged.json"),
        )

        forgedEntries.forEach { entry ->
            assertFailsWith<IllegalArgumentException> {
                fixture.manager.install(REPOSITORY, entry)
            }
        }
        assertTrue(fixture.packageStore.list().isEmpty())
        assertTrue(fixture.factory.created.isEmpty())
    }

    @Test
    fun canonicalOfficialUrlDoesNotReviewAnUnknownArtifact() = runTest {
        val packageStore = InMemoryPluginPackageStore()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val manager = PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond(ORIGINAL_SCRIPT) }),
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = CapturingEnvironmentRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage = storage,
                ),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                keyValues,
                MutablePluginSystemEventAuthorizer(),
            ),
        )
        val entry = PluginIndexEntry(
            id = "unknown.official-lookalike",
            name = "Unknown",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            scriptUrl = "plugins/unknown.official-lookalike.js",
            sources = listOf(SourceIndexEntry(
                name = "Unknown",
                lang = "all",
                id = 99L,
                baseUrl = "https://source.example",
                originPolicyVersion = 2,
                canonicalSourceId = "99",
            )),
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )

        manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
        manager.approveCurrentEventGrantReview(entry.id, emptySet())
        assertNull(manager.source(99L))
        assertFalse(manager.hasExecutionApproval(entry.id))
        assertTrue(manager.pendingEventGrantReview(entry.id) != null)
    }

    @Test
    fun unavailableInProcessEngineKeepsApprovedGenericPackageInstalledAndInert() = runTest {
        val fixture = Fixture(runtimeFactory = UnavailableRuntimeFactory())

        // Download and integrity verification must succeed even when this host cannot safely
        // execute a generic in-process script. Approval must not turn that capability mismatch
        // into a failed installation or leave a durable executable grant behind.
        fixture.manager.install(REPOSITORY, fixture.entry(version = "1.0.0", versionCode = 1))
        fixture.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())

        assertTrue(fixture.packageStore.list().single().scriptBytes.isNotEmpty())
        assertTrue(fixture.manager.catalogueSources().isEmpty())
        assertFalse(fixture.manager.hasExecutionApproval(PLUGIN_ID))
        assertTrue(fixture.manager.pendingEventGrantReview(PLUGIN_ID) != null)
        assertTrue(fixture.manager.loadInstalled().isEmpty())
    }

    @Test
    fun startupQuarantinesAStoredGrantWhenTheCurrentHostCannotIsolateItsRuntime() = runTest {
        val packageStore = InMemoryPluginPackageStore()
        val keyValues = InMemoryPluginKeyValueStore()
        val approvingHost = Fixture(packageStore = packageStore, keyValues = keyValues)
        approvingHost.manager.install(
            REPOSITORY,
            approvingHost.entry(version = "1.0.0", versionCode = 1),
        )
        approvingHost.manager.approveCurrentEventGrantReview(PLUGIN_ID, emptySet())
        assertTrue(approvingHost.manager.hasExecutionApproval(PLUGIN_ID))
        approvingHost.manager.close()

        val unavailableFactory = UnavailableRuntimeFactory()
        val unavailableHost = Fixture(
            packageStore = packageStore,
            keyValues = keyValues,
            runtimeFactory = unavailableFactory,
        )

        assertTrue(unavailableHost.manager.loadInstalled().isEmpty())
        assertEquals(1, unavailableFactory.attempts)
        assertFalse(unavailableHost.manager.hasExecutionApproval(PLUGIN_ID))
        assertTrue(unavailableHost.manager.pendingEventGrantReview(PLUGIN_ID) != null)
        assertTrue(unavailableHost.manager.loadInstalled().isEmpty())
        assertEquals(1, unavailableFactory.attempts)
    }

    private class Fixture(
        val packageStore: PluginPackageStore = InMemoryPluginPackageStore(),
        val keyValues: PluginKeyValueStore = InMemoryPluginKeyValueStore(),
        runtimeFactory: ScriptPluginRuntimeFactory = TrackingRuntimeFactory(),
    ) {
        var script: String = ORIGINAL_SCRIPT
        val factory = runtimeFactory as TrackingRuntimeFactory
        private val storage = KeyValuePluginStorage(keyValues)
        private val admission = KeyValuePluginEventGrantAdmission(
            keyValues,
            MutablePluginSystemEventAuthorizer(),
        )
        val manager = PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond(script) }),
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = runtimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage = storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                ),
                storage = storage,
            ),
            eventGrantAdmission = admission,
        )

        fun entry(version: String, versionCode: Int): PluginIndexEntry = PluginIndexEntry(
            id = PLUGIN_ID,
            name = "Admission fixture",
            version = version,
            versionCode = versionCode,
            lang = "all",
            scriptUrl = "plugins/$PLUGIN_ID.js",
            sources = listOf(
                SourceIndexEntry(
                    name = "Admission fixture",
                    lang = "all",
                    id = SOURCE_ID,
                    baseUrl = "https://source.example",
                    originPolicyVersion = 1,
                ),
            ),
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )
    }

    private companion object {
        const val PLUGIN_ID = "all.execution-admission"
        const val SOURCE_ID = 8_001L
        const val ORIGINAL_SCRIPT = "var source = { version: 1 };"
        const val UPDATED_SCRIPT = "var source = { version: 2 };"
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")
    }
}

private class UnavailableRuntimeFactory : TrackingRuntimeFactory() {
    var attempts: Int = 0

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        attempts += 1
        throw ScriptRuntimeUnavailableException("per-runtime heap isolation is unavailable")
    }
}

private class FailNextPutPackageStore : PluginPackageStore {
    private val delegate = InMemoryPluginPackageStore()
    var failNextPut: Boolean = false

    override suspend fun list(): List<StoredPlugin> = delegate.list()
    override suspend fun get(pluginId: String): StoredPlugin? = delegate.get(pluginId)

    override suspend fun put(plugin: StoredPlugin) {
        if (failNextPut) {
            failNextPut = false
            error("Injected package update failure")
        }
        delegate.put(plugin)
    }

    override suspend fun remove(pluginId: String) = delegate.remove(pluginId)
}

private class FailingRemoveKeyValueStore : PluginKeyValueStore {
    private val delegate = InMemoryPluginKeyValueStore()
    var failRemoves: Boolean = false

    override suspend fun getString(key: String): String? = delegate.getString(key)
    override suspend fun putString(key: String, value: String) = delegate.putString(key, value)

    override suspend fun remove(key: String) {
        if (failRemoves && (key.contains("plugin.events") || key.contains("plugin.runtime-permissions"))) {
            error("Injected event-grant removal failure")
        }
        delegate.remove(key)
    }
}

private open class TrackingRuntimeFactory : ScriptPluginRuntimeFactory {
    val created = mutableListOf<TrackingRuntime>()

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = TrackingRuntime(
        NoopScriptPluginRuntimeFactory.create(script, manifest, environment),
    ).also(created::add)
}

private class CapturingEnvironmentRuntimeFactory : ScriptPluginRuntimeFactory {
    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        if (environment.inProcessScriptProvenance == null) {
            throw ScriptRuntimeUnavailableException("Artifact is not host-reviewed")
        }
        return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
    }
}

private class TrackingRuntime(
    private val delegate: ScriptPluginRuntime,
) : ScriptPluginRuntime by delegate {
    var closed: Boolean = false

    override suspend fun close() {
        delegate.close()
        closed = true
    }
}
