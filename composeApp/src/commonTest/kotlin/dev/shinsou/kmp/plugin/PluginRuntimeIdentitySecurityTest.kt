package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.DiagnosticMessageV1
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginDiagnosticSeverity
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
import dev.shinsou.kmp.plugin.events.PluginEventGrantKey
import dev.shinsou.kmp.plugin.events.PluginEventOutcome
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventCodec
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemEventHandlerRegistry
import dev.shinsou.kmp.plugin.events.PluginSystemEventKind
import dev.shinsou.kmp.plugin.events.PluginSystemEventLane
import dev.shinsou.kmp.plugin.events.PluginSystemEventLimits
import dev.shinsou.kmp.plugin.events.PluginSystemEventNames
import dev.shinsou.kmp.plugin.events.TypedPluginSystemEventHandler
import dev.shinsou.kmp.plugin.events.stablePluginRuntimeInstanceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginRuntimeIdentitySecurityTest {
    @Test
    fun directRuntimeEnvironmentHasNoImplicitHostCapabilities() {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val environment = ScriptPluginEnvironment(
            network = PluginNetworkClient(
                PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                storage,
            ),
            storage = storage,
        )

        assertTrue(environment.runtimePermissions.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            environment.requireRuntimePermission(PluginRuntimePermission.NETWORK)
        }
    }

    @Test
    fun sessionOwnerKeyIsBoundToArtifactAndSourceIdentity() {
        val first = ExactPluginSourceTarget(
            artifactIdentity = PluginArtifactIdentity("pkg", "1.0.0", 1, "a".repeat(64)),
            sourceKey = SourceKey(2, "pkg", "source", 77L),
        )
        val replacement = first.copy(
            artifactIdentity = first.artifactIdentity.copy(version = "2.0.0", versionCode = 2),
        )
        val otherSource = first.copy(sourceKey = SourceKey(2, "pkg", "other", 77L))
        assertEquals("plugin.events.sessionOwner.v2.${ExactPluginSessionOwnership.targetKey(first)}", ExactPluginSessionOwnership.ownerKey(first))
        assertTrue(ExactPluginSessionOwnership.ownerKey(first) != ExactPluginSessionOwnership.ownerKey(replacement))
        assertTrue(ExactPluginSessionOwnership.ownerKey(first) != ExactPluginSessionOwnership.ownerKey(otherSource))
        assertTrue(ExactPluginSessionOwnership.authorizesCleanup(ExactPluginSessionOwnership.targetKey(first), first))
        assertFalse(ExactPluginSessionOwnership.authorizesCleanup(ExactPluginSessionOwnership.targetKey(first), replacement))
    }

    @Test
    fun logicalRuntimeIdentityIsStableButIsolatesArtifactsAndOpaqueSources() {
        val artifact = PluginArtifactIdentity("pkg", "1.0.0", 1, "a".repeat(64))
        val source = SourceKey(2, "pkg", "source", 77L)
        val sameLogicalSource = source.copy(legacyLongId = 88L)
        val replacement = artifact.copy(version = "2.0.0", versionCode = 2)
        val otherSource = source.copy(sourceId = "other")

        val identity = stablePluginRuntimeInstanceId(artifact, source)
        assertEquals(identity, stablePluginRuntimeInstanceId(artifact, sameLogicalSource))
        assertTrue(identity != stablePluginRuntimeInstanceId(replacement, source))
        assertTrue(identity != stablePluginRuntimeInstanceId(artifact, otherSource))
        assertTrue(identity.matches(Regex("^runtime-[0-9a-f]{64}$")))
    }

    @Test
    fun collidingManifestIsRejectedBeforeRuntimeFactoryExecutes() = runTest {
        val factory = CountingRuntimeFactory()
        val manager = manager(factory)
        manager.install(REPOSITORY, entry("package.first", 41L))
        assertEquals(1, factory.createCount)

        assertFailsWith<IllegalArgumentException> {
            manager.install(REPOSITORY, entry("package.attacker", 41L))
        }

        assertEquals(1, factory.createCount, "a colliding package must never evaluate its script")
        assertEquals(listOf("package.first"), manager.installedPlugins().map { it.manifest.id })
    }

    @Test
    fun samePackageUpdateKeepsItsReservationAndCanReplaceTheRuntime() = runTest {
        val factory = CountingRuntimeFactory()
        val manager = manager(factory)
        val first = entry("package.same", 42L)
        manager.install(REPOSITORY, first)

        manager.update(REPOSITORY, first.copy(version = "2.0.0", versionCode = 2))

        assertEquals(2, factory.createCount)
        assertEquals("2.0.0", manager.installedPlugins().single().manifest.version)
        assertEquals(42L, manager.catalogueSources().single().id)
    }

    @Test
    fun storedCollisionIsRejectedBeforeEitherRuntimeExecutes() = runTest {
        val bytes = SCRIPT.encodeToByteArray()
        val hash = Sha256.hex(bytes)
        val store = InMemoryPluginPackageStore()
        listOf("package.stored-one", "package.stored-two").forEach { packageId ->
            val manifest = PluginManifest(
                id = packageId,
                name = packageId,
                version = "1.0.0",
                versionCode = 1,
                lang = "all",
                script = "$packageId.js",
                signature = hash,
                sources = listOf(SourceIndexEntry(packageId, "all", 81L, "https://source.example")),
            )
            store.put(StoredPlugin(InstalledPluginMetadata(manifest, installedSha256 = hash, legacyTrustOnInstall = true), bytes))
        }
        val factory = CountingRuntimeFactory()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val manager = PluginManager(
            ExtensionRepositoryClient(HttpClient(MockEngine { respond(SCRIPT) })),
            store,
            PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            factory,
            ScriptPluginEnvironment(
                PluginNetworkClient(PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage),
                storage,
            ),
        )

        assertFailsWith<IllegalArgumentException> { manager.loadInstalled() }
        assertEquals(0, factory.createCount)
    }

    @Test
    fun failedFactoryRollsBackReservationForANewOwner() = runTest {
        val factory = CountingRuntimeFactory(failPackage = "package.failed")
        val manager = manager(factory)

        assertFailsWith<IllegalStateException> {
            manager.install(REPOSITORY, entry("package.failed", 43L))
        }
        manager.install(REPOSITORY, entry("package.recovery", 43L))

        assertEquals(2, factory.createCount)
        assertEquals("package.recovery", manager.installedPlugins().single().manifest.id)
    }

    @Test
    fun cancellationDuringFactoryCreationRollsBackReservation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = CountingRuntimeFactory(pausePackage = "package.cancelled", entered = entered, release = release)
        val manager = manager(factory)
        val installing = launch { manager.install(REPOSITORY, entry("package.cancelled", 44L)) }
        entered.await()

        installing.cancelAndJoin()
        factory.pausePackage = null
        manager.install(REPOSITORY, entry("package.after-cancel", 44L))

        assertTrue(installing.isCancelled)
        assertEquals("package.after-cancel", manager.installedPlugins().single().manifest.id)
    }

    @Test
    fun explicitRuntimePermissionsArePassedToFactoryAndStorageFailsClosed() = runTest {
        val factory = CountingRuntimeFactory()
        val manager = manager(factory)

        manager.install(
            REPOSITORY,
            entry("package.permissions", 45L).copy(
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            ),
        )

        val environment = requireNotNull(factory.lastEnvironment)
        assertEquals(setOf(PluginRuntimePermission.EXECUTE_SCRIPT), environment.runtimePermissions)
        assertNull(environment.storage.getCredential(45L))
        assertTrue(environment.storage.getCookies(45L).isEmpty())
    }

    @Test
    fun managerReloadsReuseLogicalIdentityAndCurrentGenerationSurvivesTheGatewayBound() = runTest {
        val sourceKey = SourceKey.fromLegacy("package.reload", 46L)
        val scriptBytes = SCRIPT.encodeToByteArray()
        val digest = Sha256.hex(scriptBytes)
        val artifact = PluginArtifactIdentity("package.reload", "1.0.0", 1, digest)
        val manifest = PluginManifest(
            id = artifact.packageId,
            name = "Reload fixture",
            version = artifact.version,
            versionCode = artifact.versionCode,
            lang = "all",
            script = "package.reload.js",
            signature = digest,
            sources = listOf(SourceIndexEntry("Reload fixture", "all", 46L, "https://source.example")),
            systemEvents = PluginSystemEventDeclaration(
                minVersion = 1,
                maxVersion = 1,
                optional = setOf(PluginSystemEventNames.DIAGNOSTIC_CAPABILITY),
            ),
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )
        val authorizer = MutablePluginSystemEventAuthorizer().also {
            it.grant(
                PluginEventGrantKey(artifact, sourceKey),
                setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
            )
        }
        val codec = PluginSystemEventCodec(PluginSystemEventLimits(maxTrackedRuntimes = 1))
        val handlers = PluginSystemEventHandlerRegistry().also { registry ->
            registry.register(
                TypedPluginSystemEventHandler<DiagnosticMessageV1>(
                    name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
                    kind = PluginSystemEventKind.EVENT,
                    payloadVersion = 1,
                    lane = PluginSystemEventLane.TRANSIENT,
                    requiredPermission = PluginHostPermission.REPORT_DIAGNOSTIC,
                    decode = { codec.decodePayload(it, DiagnosticMessageV1.serializer()) },
                    execute = { _, _ -> PluginEventOutcome.Succeeded },
                ),
            )
        }
        val gateway = PluginSystemEventGateway(
            registry = handlers,
            authorizer = authorizer,
            codec = codec,
            dispatcherScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        val packageStore = InMemoryPluginPackageStore().also { store ->
            store.put(
                StoredPlugin(
                    InstalledPluginMetadata(
                        manifest = manifest,
                        installedSha256 = digest,
                        legacyTrustOnInstall = true,
                    ),
                    scriptBytes,
                ),
            )
        }
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val factory = CountingRuntimeFactory()
        val manager = PluginManager(
            repositoryClient = ExtensionRepositoryClient(HttpClient(MockEngine { error("unused") })),
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = factory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage,
                ),
                storage = storage,
                systemEventSink = gateway,
            ),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )

        try {
            repeat(3) { manager.loadInstalled() }
            val scopes = factory.environments.map { requireNotNull(it.boundPluginScope) }
            assertEquals(3, scopes.size)
            assertEquals(setOf(stablePluginRuntimeInstanceId(artifact, sourceKey)), scopes.map { it.runtimeInstanceId }.toSet())
            assertEquals(scopes.map { it.runtimeGeneration }.sorted(), scopes.map { it.runtimeGeneration })
            assertEquals(3, scopes.map { it.runtimeGeneration }.distinct().size)

            fun diagnostic(id: String): ByteArray = codec.encodePayload(
                kind = PluginSystemEventKind.EVENT,
                name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
                id = id,
                payload = DiagnosticMessageV1(
                    code = "reload.identity",
                    severity = PluginDiagnosticSeverity.WARNING,
                    fallbackMessage = "Reload identity fixture",
                ),
                serializer = DiagnosticMessageV1.serializer(),
            )
            assertEquals(
                PluginEventDisposition.ACCEPTED,
                gateway.submit(scopes.last(), diagnostic("reload-current")).disposition,
            )
            assertEquals(
                PluginEventDisposition.RUNTIME_CLOSED,
                gateway.submit(scopes.first(), diagnostic("reload-stale")).disposition,
            )
            advanceUntilIdle()
        } finally {
            manager.close()
            gateway.close()
        }
    }

    private fun manager(factory: ScriptPluginRuntimeFactory): PluginManager {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        return PluginManager(
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond(SCRIPT) }),
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = factory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                ),
                storage = storage,
            ),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
    }

    private fun entry(packageId: String, sourceId: Long): PluginIndexEntry = PluginIndexEntry(
        id = packageId,
        name = packageId,
        version = "1.0.0",
        versionCode = 1,
        lang = "all",
        scriptUrl = "$packageId.js",
        sources = listOf(SourceIndexEntry(packageId, "all", sourceId, "https://source.example")),
        runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
    )

    private companion object {
        const val SCRIPT = "var source = {};"
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")
    }
}

private class CountingRuntimeFactory(
    private val failPackage: String? = null,
    var pausePackage: String? = null,
    private val entered: CompletableDeferred<Unit>? = null,
    private val release: CompletableDeferred<Unit>? = null,
) : ScriptPluginRuntimeFactory {
    var createCount: Int = 0
    var lastEnvironment: ScriptPluginEnvironment? = null
    val environments: MutableList<ScriptPluginEnvironment> = mutableListOf()

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        createCount += 1
        lastEnvironment = environment
        environments += environment
        if (manifest.id == pausePackage) {
            entered?.complete(Unit)
            release?.await()
        }
        if (manifest.id == failPackage) error("injected runtime failure")
        return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
    }
}

class BoundPluginStorageSecurityTest {
    @Test
    fun legacyPreferenceNamesCannotAddressCredentialsCookiesOrOwnerState() = runTest {
        val raw = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        raw.setCredential(70L, PluginCredential("owner", "secret"))
        raw.setCookie(70L, PluginCookie("session", "cookie-secret", "source.example"))

        listOf(
            "credential.username",
            "credential.password",
            "cookies",
            "webChallenge.userAgent",
            "v2.owner",
            "preferences.v2",
            "preferences.v2.metadata",
            "preferences.v2.value.616263",
            "preferences.v2.secret.value.616263",
        ).forEach { reserved ->
            assertFailsWith<IllegalArgumentException> { raw.getPreference(70L, reserved) }
            assertFailsWith<IllegalArgumentException> { raw.setPreference(70L, reserved, "forged") }
        }

        assertEquals(PluginCredential("owner", "secret"), raw.getCredential(70L))
        assertEquals("cookie-secret", raw.getCookies(70L).single().value)
    }

    @Test
    fun exactPreferenceNamespaceSafelyEncodesReservedLookingNamesAndBoundsValues() = runTest {
        val exact = BoundPluginStorage(
            KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            SourceKey.fromLegacy("package.exact", 70L),
        )

        exact.setPreference(70L, "credential.password", "ordinary-preference")
        assertEquals("ordinary-preference", exact.getPreference(70L, "credential.password"))
        assertFailsWith<IllegalArgumentException> {
            exact.setPreference(70L, "large", "x".repeat(MAX_PLUGIN_PREFERENCE_VALUE_BYTES + 1))
        }
    }

    @Test
    fun preferencesEnforceDurablePerSourceKeyAndByteQuotas() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val first = KeyValuePluginStorage(keyValues)

        repeat(MAX_PLUGIN_PREFERENCES_PER_SOURCE) { index ->
            first.setPreference(700L, "key-$index", "v")
        }
        assertFailsWith<IllegalArgumentException> {
            first.setPreference(700L, "one-too-many", "v")
        }

        val reopened = KeyValuePluginStorage(keyValues)
        assertFailsWith<IllegalArgumentException> {
            reopened.setPreference(700L, "still-too-many", "v")
        }
        reopened.removePreference(700L, "key-0")
        reopened.setPreference(700L, "replacement", "v")
        assertEquals("v", reopened.getPreference(700L, "replacement"))

        val exact = BoundPluginStorage(
            KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            SourceKey.fromLegacy("package.quota", 701L),
        )
        exact.setPreference(
            701L,
            "large",
            "x".repeat((MAX_PLUGIN_PREFERENCE_TOTAL_BYTES_PER_SOURCE - "large".length).toInt()),
        )
        assertFailsWith<IllegalArgumentException> {
            exact.setPreference(701L, "overflow", "x")
        }
    }

    @Test
    fun sensitivePreferenceValuesRetainProtectedPhysicalStorageRouting() = runTest {
        val keyValues = RoutingRecordingPluginKeyValueStore()
        val raw = KeyValuePluginStorage(keyValues)

        raw.setPreference(702L, "authToken", "top-secret")
        raw.setPreference(702L, "theme", "dark")

        val secretRecord = keyValues.values.entries.single { it.value == "top-secret" }
        val ordinaryRecord = keyValues.values.entries.single { it.value == "dark" }
        assertTrue(isSensitivePluginKey(secretRecord.key))
        assertFalse(isSensitivePluginKey(ordinaryRecord.key))
        assertTrue(keyValues.values.entries.none { (key, value) ->
            !isSensitivePluginKey(key) && value.contains("top-secret")
        })
    }

    @Test
    fun credentialsAreBoundedForLegacyAndExactStorage() = runTest {
        val raw = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        assertFailsWith<IllegalArgumentException> {
            raw.setCredential(
                703L,
                PluginCredential("u".repeat(MAX_PLUGIN_CREDENTIAL_USERNAME_BYTES + 1), "password"),
            )
        }
        val exact = BoundPluginStorage(raw, SourceKey.fromLegacy("package.credential", 703L))
        assertFailsWith<IllegalArgumentException> {
            exact.setCredential(
                703L,
                PluginCredential("username", "p".repeat(MAX_PLUGIN_CREDENTIAL_PASSWORD_BYTES + 1)),
            )
        }
    }

    @Test
    fun exactStorageRejectsOversizedIdentityBeforeCreatingPhysicalKeys() {
        val raw = KeyValuePluginStorage(InMemoryPluginKeyValueStore())

        assertFailsWith<IllegalArgumentException> {
            BoundPluginStorage(
                raw,
                SourceKey.fromLegacy("p".repeat(MAX_PLUGIN_SOURCE_PACKAGE_ID_BYTES + 1), 704L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BoundPluginStorage(
                raw,
                SourceKey(
                    packageId = "package.valid",
                    sourceId = "來".repeat(MAX_PLUGIN_SOURCE_ID_BYTES / 3 + 1),
                    legacyLongId = 704L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BoundPluginStorage(
                raw,
                SourceKey(
                    packageId = "p".repeat(MAX_PLUGIN_SOURCE_PACKAGE_ID_BYTES),
                    sourceId = "s".repeat(MAX_PLUGIN_SOURCE_ID_BYTES),
                    legacyLongId = 704L,
                ),
            )
        }

        // Exact limits are UTF-8 byte limits, and ordinary reviewed/built-in-shaped ids remain valid.
        BoundPluginStorage(
            raw,
            SourceKey.fromLegacy("zh.bilimanga", BILIMANGA_MANGA_SOURCE_ID),
        )
    }

    @Test
    fun cookieJarRejectsAggregateOverflowBeforePersistenceAndAfterReopen() = runTest {
        val keyValues = RoutingRecordingPluginKeyValueStore()
        val raw = KeyValuePluginStorage(keyValues)
        val sourceId = 705L
        var index = 0

        while (true) {
            val before = keyValues.values["source.$sourceId.cookies"]
            val failure = runCatching {
                raw.setCookie(
                    sourceId,
                    PluginCookie(
                        name = "cookie-$index",
                        value = "x".repeat(MAX_COOKIE_BYTES - 64),
                        domain = "source.example",
                    ),
                )
            }.exceptionOrNull()
            if (failure != null) {
                assertTrue(failure is IllegalArgumentException)
                assertEquals(before, keyValues.values["source.$sourceId.cookies"])
                break
            }
            index += 1
        }
        assertTrue(index in 1 until MAX_COOKIES_PER_SOURCE)

        keyValues.values["source.706.cookies"] = "x".repeat(MAX_PLUGIN_COOKIE_JAR_ENCODED_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            KeyValuePluginStorage(keyValues).getCookies(706L)
        }
    }

    @Test
    fun exactCookieJarUsesTheSameDurableAggregateByteBound() = runTest {
        val keyValues = RoutingRecordingPluginKeyValueStore()
        val exact = BoundPluginStorage(
            KeyValuePluginStorage(keyValues),
            SourceKey.fromLegacy("package.cookies", 707L),
        )
        var index = 0

        while (true) {
            val cookieRecord = keyValues.values.entries.singleOrNull { it.key.endsWith(".cookies") }
            val before = cookieRecord?.value
            val failure = runCatching {
                exact.setCookie(
                    707L,
                    PluginCookie(
                        name = "cookie-$index",
                        value = "x".repeat(MAX_COOKIE_BYTES - 64),
                        domain = "source.example",
                    ),
                )
            }.exceptionOrNull()
            if (failure != null) {
                assertTrue(failure is IllegalArgumentException)
                assertEquals(before, keyValues.values.entries.single { it.key.endsWith(".cookies") }.value)
                break
            }
            index += 1
        }
        assertTrue(index in 1 until MAX_COOKIES_PER_SOURCE)
    }

    @Test
    fun credentialUpdateHasOneSensitiveCommitPointAndSurvivesCleanupFailure() = runTest {
        val keyValues = FailingCredentialCleanupKeyValueStore()
        val raw = KeyValuePluginStorage(keyValues)
        raw.setCredential(708L, PluginCredential("old-user", "old-password"))
        keyValues.failLegacyCleanup = true

        assertFailsWith<IllegalStateException> {
            raw.setCredential(708L, PluginCredential("new-user", "new-password"))
        }

        assertEquals(
            PluginCredential("new-user", "new-password"),
            KeyValuePluginStorage(keyValues).getCredential(708L),
        )
        val authoritative = keyValues.values.entries.single { it.key.endsWith(".credential.record.v2") }
        assertTrue(isSensitivePluginKey(authoritative.key))
        assertFalse(authoritative.value.contains("old-password"))
    }

    @Test
    fun incompleteLegacyCredentialFailsClosedAndClearTombstonePreventsResurrection() = runTest {
        val keyValues = RoutingRecordingPluginKeyValueStore()
        keyValues.values["source.709.credential.username"] = "orphan-user"
        assertFailsWith<IllegalArgumentException> {
            KeyValuePluginStorage(keyValues).getCredential(709L)
        }

        keyValues.values["source.710.credential.username"] = "legacy-user"
        keyValues.values["source.710.credential.password"] = "legacy-password"
        val raw = KeyValuePluginStorage(keyValues)
        assertEquals(PluginCredential("legacy-user", "legacy-password"), raw.getCredential(710L))
        raw.clearCredential(710L)

        // Even if an interrupted/old writer leaves both legacy keys behind, the authoritative
        // cleared record wins and they can never be combined or resurrected.
        keyValues.values["source.710.credential.username"] = "stale-user"
        keyValues.values["source.710.credential.password"] = "stale-password"
        assertNull(KeyValuePluginStorage(keyValues).getCredential(710L))
    }

    @Test
    fun differentPackageReusingLongCannotReadExactState() = runTest {
        val raw = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val first = BoundPluginStorage(raw, SourceKey.fromLegacy("package.first", 71L))
        first.setCredential(71L, PluginCredential("owner", "secret"))
        first.setPreference(71L, "mode", "private")

        val attacker = BoundPluginStorage(raw, SourceKey.fromLegacy("package.attacker", 71L))

        assertNull(attacker.getCredential(71L))
        assertNull(attacker.getPreference(71L, "mode"))
    }

    @Test
    fun verifiedExistingOwnerMigratesLegacyStateOnce() = runTest {
        val raw = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        raw.setCredential(72L, PluginCredential("legacy", "secret"))
        raw.setCookie(72L, PluginCookie("session", "cookie-secret", "source.example"))
        val existing = BoundPluginStorage(
            raw,
            SourceKey.fromLegacy("package.existing", 72L),
            allowLegacyMigration = true,
        )

        assertEquals(PluginCredential("legacy", "secret"), existing.getCredential(72L))
        assertEquals("cookie-secret", existing.getCookies(72L).single().value)

        raw.clearCredential(72L)
        raw.clearCookies(72L)
        val update = BoundPluginStorage(raw, SourceKey.fromLegacy("package.existing", 72L))
        assertEquals(PluginCredential("legacy", "secret"), update.getCredential(72L))
        assertEquals("cookie-secret", update.getCookies(72L).single().value)

        val recycled = BoundPluginStorage(raw, SourceKey.fromLegacy("package.recycled", 72L))
        assertNull(recycled.getCredential(72L))
        assertTrue(recycled.getCookies(72L).isEmpty())
    }

    @Test
    fun runtimeCannotSelectAnotherNumericScope() = runTest {
        val storage = BoundPluginStorage(
            KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            SourceKey.fromLegacy("package.bound", 73L),
        )

        assertFailsWith<IllegalArgumentException> { storage.getCredential(74L) }
        assertFailsWith<IllegalArgumentException> { storage.setPreference(74L, "key", "value") }
    }
}

private class RoutingRecordingPluginKeyValueStore : PluginKeyValueStore {
    val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FailingCredentialCleanupKeyValueStore : PluginKeyValueStore {
    val values = mutableMapOf<String, String>()
    var failLegacyCleanup: Boolean = false

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        if (failLegacyCleanup && key.endsWith(".credential.username")) {
            error("injected obsolete credential cleanup failure")
        }
        values.remove(key)
    }
}
