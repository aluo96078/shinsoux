package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0002 §10.1 inside [PluginManager]: the publisher-trust tier decides whether a package runs; it only ever runs in the sandbox. */
class PluginManagerExecutionTierTest {
    @BeforeTest
    fun initializeCrypto() = runTest { SodiumSyncPrimitives.initialize() }

    private class Engine : ScriptPluginRuntimeFactory {
        val created = mutableListOf<String>()
        val permissions = mutableListOf<Set<PluginRuntimePermission>>()
        override suspend fun create(script: String, manifest: PluginManifest, environment: ScriptPluginEnvironment): ScriptPluginRuntime {
            created += manifest.id
            permissions += environment.runtimePermissions
            return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
        }
    }

    private class Host(
        val keyValues: PluginKeyValueStore = InMemoryPluginKeyValueStore(),
        val packages: PluginPackageStore = InMemoryPluginPackageStore(),
        withSandbox: Boolean = true,
        trustPolicy: RepositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
    ) {
        val sandbox = Engine()
        val state = KeyValueRepositorySecurityStateStore(keyValues)
        val publishers = PublisherTrustStore(keyValues, state)
        val revocations = PublisherRevocationStore(keyValues, publishers, state)
        val blocklist = LocalPluginBlocklist(keyValues)
        val storage = KeyValuePluginStorage(keyValues)
        val developer = PluginDeveloperModeStore(keyValues)
        val admission = KeyValuePluginEventGrantAdmission(keyValues, MutablePluginSystemEventAuthorizer())
        var script = SCRIPT
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond(script) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = trustPolicy,
            developerRepository = developer::admitsRepository,
        )
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packages,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(sandbox.takeIf { withSandbox }),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage = storage),
                storage = storage,
            ),
            eventGrantAdmission = admission,
            executionTierGate = PublisherExecutionTierGate(
                publishers,
                ExecutionTierResolver(
                    publishers, revocations, blocklist,
                    developerMode = { developer.state.value.enabled },
                    nowEpochSeconds = { 1_000L },
                ),
            ),
            developerRepository = developer::admitsRepository,
        )
    }

    private val keys = mutableListOf<dev.shinsou.kmp.sync.crypto.SodiumKeyPair>()

    /** The local install key of the single installed fixture (publisher-scoped when signed, D6). */
    private suspend fun Host.key(): String = manager.installedPlugins().singleOrNull()?.manifest?.id ?: PLUGIN_ID

    /** The numeric id the fixture's source runs under on this device. */
    private suspend fun Host.sourceId(): Long =
        manager.installedPlugins().singleOrNull()?.manifest?.sources?.singleOrNull()?.id ?: SOURCE_ID

    @kotlin.test.AfterTest
    fun destroyKeys() = keys.forEach { SodiumSyncPrimitives.destroy(it.privateKey) }

    /** Trusts a fresh publisher for [REPOSITORY], with the current (empty) revocation list installs require. */
    private suspend fun Host.trustPublisher(repository: ExtensionRepository = REPOSITORY): String {
        val key = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val publisher = publishers.trustRepository(repository.baseUrl, publisherRoot(key)).publisherId
        revocations.applyRevocations(repository.baseUrl, signedRevocations(key, repository.baseUrl, publisher, expiresAt = 10_000))
        return publisher
    }

    @Test
    fun aTrustedPublishersApprovedPackageRunsInTheSandbox() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertEquals(publisher, host.packages.get(host.key())!!.metadata.publisherId)
        assertTrue(host.sandbox.created.isEmpty(), "nothing runs before the user approves")
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        assertEquals(listOf(host.key()), host.sandbox.created)
        assertNotNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun onlyGrantedItemsReachTheRuntimeAndAnUpdateAskingNothingNewKeepsThem() = runTest {
        val host = Host()
        host.trustPublisher()
        val first = entry().copy(
            runtimePermissions = setOf(
                PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK,
                PluginRuntimePermission.COOKIE_STORAGE, PluginRuntimePermission.COOKIE_READ,
            ),
        )
        host.manager.install(REPOSITORY, first)
        val review = assertNotNull(host.manager.pendingEventGrantReview(host.key()))
        // The user turns COOKIE_READ off.
        val selection = dev.shinsou.kmp.plugin.events.PluginGrantSelection(
            hostPermissions = emptySet(),
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK, PluginRuntimePermission.COOKIE_STORAGE),
            contentHostSuffixes = emptySet(),
        )
        host.manager.approveEventGrantReview(host.key(), review, emptySet(), selection)
        assertEquals(selection.runtimePermissions, host.sandbox.permissions.last())

        // D4: same publisher, nothing new requested — it runs at once and the choice is kept.
        assertNull(host.manager.updateReviewPreview(REPOSITORY, first.copy(version = "1.1.0", versionCode = 2)))
        host.manager.install(REPOSITORY, first.copy(version = "1.1.0", versionCode = 2))
        assertNull(host.manager.pendingEventGrantReview(host.key()))
        assertEquals(selection, host.manager.grantedSelection(host.key()))
        assertEquals(selection.runtimePermissions, host.sandbox.permissions.last())
        assertNotNull(host.manager.source(host.sourceId()))

        // Settings withdraw an item: the runtime is rebuilt without it.
        val narrower = selection.copy(runtimePermissions = selection.runtimePermissions - PluginRuntimePermission.COOKIE_STORAGE)
        host.manager.narrowGrant(host.key(), narrower)
        assertEquals(narrower.runtimePermissions, host.sandbox.permissions.last())

        // An update asking for something new is previewed first: until accepted, nothing changes.
        val asking = first.copy(version = "1.2.0", versionCode = 3, runtimePermissions = first.runtimePermissions!! + PluginRuntimePermission.CREDENTIAL_ACCESS)
        // Without a digest the preview could not name the artifact: no preview (regular review).
        assertNull(host.manager.updateReviewPreview(REPOSITORY, asking))
        val digest = Sha256.hex(SCRIPT.encodeToByteArray())
        val preview = assertNotNull(host.manager.updateReviewPreview(REPOSITORY, asking.copy(sha256 = digest)))
        assertEquals(digest, preview.review.artifact.sha256)
        assertTrue(PluginRuntimePermission.CREDENTIAL_ACCESS in preview.review.requestedRuntimePermissions)
        assertEquals("1.2.0", preview.review.artifact.version)
        assertNotNull(host.manager.source(host.sourceId()))
        // Installed without approving it, it waits for a fresh review and does not run.
        host.manager.install(
            REPOSITORY,
            first.copy(version = "1.2.0", versionCode = 3, runtimePermissions = first.runtimePermissions!! + PluginRuntimePermission.CREDENTIAL_ACCESS),
        )
        assertNotNull(host.manager.pendingEventGrantReview(host.key()))
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun aDeclinedCdnFamilyIsUnreachableFromTheReaderAndDownloadClientsToo() = runTest {
        val host = Host()
        host.trustPublisher()
        val withCdn = entry().copy(
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
            sources = listOf(
                SourceIndexEntry(
                    name = "Tier fixture", lang = "all", id = SOURCE_ID, baseUrl = "https://source.example",
                    contentHostSuffixes = setOf("cdn.example.net"), originPolicyVersion = 2,
                ),
            ),
        )
        host.manager.install(REPOSITORY, withCdn)
        val review = assertNotNull(host.manager.pendingEventGrantReview(host.key()))
        assertEquals(setOf("cdn.example.net"), review.requestedContentHostSuffixes)
        // Approved without the CDN family.
        host.manager.approveEventGrantReview(
            host.key(), review, emptySet(),
            dev.shinsou.kmp.plugin.events.PluginGrantSelection(emptySet(), review.requestedRuntimePermissions, emptySet()),
        )
        val content = assertNotNull(host.manager.contentNetworkForSource(host.sourceId()))
        // A library entry may store the key without its numeric projection (string source ids);
        // inline reader assets must still get the same scoped client.
        val stored = host.manager.installedPlugins().single()
        val declared = stored.manifest.sources.orEmpty().single()
        val exactKey = dev.shinsou.kmp.domain.model.SourceKey(
            packageId = stored.manifest.id,
            sourceId = declared.canonicalSourceId ?: declared.id.toString(),
            legacyLongId = declared.id,
        )
        assertNotNull(host.manager.contentNetworkForSource(exactKey))
        assertNotNull(host.manager.contentNetworkForSource(exactKey.copy(legacyLongId = null)))
        val refused = assertFailsWith<IllegalArgumentException> {
            content.execute(host.sourceId(), PluginHttpRequest("GET", "https://img1.cdn.example.net/a.jpg", ByteArray(0), emptyMap()))
        }
        assertTrue(refused.message.orEmpty().contains("not declared"), refused.message)
    }

    @Test
    fun theSamePackageIdFromTwoPublishersInstallsSideBySide() = runTest {
        val host = Host()
        val first = host.trustPublisher(REPOSITORY)
        val second = host.trustPublisher(OTHER_REPOSITORY)
        host.manager.install(REPOSITORY, entry())
        host.manager.install(OTHER_REPOSITORY, entry())
        val installed = host.manager.installedPlugins()
        assertEquals(2, installed.size)
        val byPublisher = installed.associateBy { it.metadata.publisherId }
        val a = assertNotNull(byPublisher[first])
        val b = assertNotNull(byPublisher[second])
        // Each has its own local identity and its own projected source id, the same on every device.
        assertTrue(a.manifest.id.startsWith("$PLUGIN_ID~") && b.manifest.id.startsWith("$PLUGIN_ID~"))
        assertTrue(a.manifest.id != b.manifest.id)
        val aSource = a.manifest.sources!!.single()
        val bSource = b.manifest.sources!!.single()
        assertTrue(aSource.id != bSource.id)
        assertEquals(PluginInstallKeys.projectedSourceId(a.manifest.id, SOURCE_ID.toString()), aSource.id)
        assertEquals(SOURCE_ID.toString(), aSource.canonicalSourceId)

        host.manager.approveCurrentEventGrantReview(a.manifest.id, emptySet())
        host.manager.approveCurrentEventGrantReview(b.manifest.id, emptySet())
        assertNotNull(host.manager.source(aSource.id))
        assertNotNull(host.manager.source(bSource.id))

        // Removing one leaves the other running, with its grant.
        host.manager.uninstall(a.manifest.id)
        assertNull(host.manager.source(aSource.id))
        assertNotNull(host.manager.source(bSource.id))
        assertNotNull(host.manager.grantedSelection(b.manifest.id))
    }

    @Test
    fun anUnsignedPackageStaysInertEvenWhenApproved() = runTest {
        val host = Host()
        host.manager.install(REPOSITORY, entry())
        assertFailsWith<ExtensionArtifactNotReviewedException> { host.manager.approveCurrentEventGrantReview(host.key(), emptySet()) }
        assertTrue(host.sandbox.created.isEmpty())
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun blockingTakesEffectAtTheNextLoadAndUnblockingRestores() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        host.manager.loadInstalled()
        assertNull(host.manager.source(host.sourceId()))

        // A later process (same device storage) still keeps it inert; unblocking brings it back.
        val restarted = Host(host.keyValues, host.packages)
        restarted.manager.loadInstalled()
        assertNull(restarted.manager.source(restarted.sourceId()))
        restarted.blocklist.unblock(PluginBlockEntry.Publisher(publisher))
        val again = Host(host.keyValues, host.packages)
        again.manager.loadInstalled()
        assertNotNull(again.manager.source(again.sourceId()))
    }

    @Test
    fun aBlockedPublisherInstallsNothingNew() = runTest {
        val host = Host()
        val publisher = host.trustPublisher()
        host.blocklist.block(PluginBlockEntry.Publisher(publisher))
        assertFailsWith<PublisherTrustException.Blocked> { host.manager.install(REPOSITORY, entry()) }
        assertTrue(host.manager.installedPlugins().isEmpty())
    }

    @Test
    fun aPendingPublisherKeepsItsInstalledPackageButInstallsNoUpdate() = runTest {
        val host = Host()
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        host.publishers.setGlobalMode(PublisherTrustMode.REQUIRE_FINGERPRINT_MATCH)
        host.manager.loadInstalled()
        assertNotNull(host.manager.source(host.sourceId()))
        assertFailsWith<PublisherTrustException.NotInstallable> { host.manager.install(REPOSITORY, entry(version = "1.1.0", versionCode = 2)) }
    }

    @Test
    fun aBlockCancelledAfterItWasStoredStillTakesTheRuntimeDown() = runTest {
        val keyValues = StallingKeyValueStore()
        val host = Host(keyValues)
        val publisher = host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        host.manager.approveCurrentEventGrantReview(host.key(), emptySet())
        assertNotNull(host.manager.source(host.sourceId()))
        val adapter = PluginBrowseAdapter(
            manager = host.manager,
            repositoryClient = host.client,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = host.storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            packageSecurity = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, host.client),
        )
        keyValues.stallAfterWriting = "plugin.blocklist.v1"
        val blocking = launch { adapter.setPublisherBlocked(publisher, blocked = true) }
        keyValues.stalled.await() // the block is on disk; the screen goes away before the call returns
        blocking.cancel()
        blocking.join()
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun noRepositorySpellingTurnsAnUncheckedInstallIntoARunningPackage() = runTest {
        val key = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        // Trusted, but without the revocation list installs require.
        for (spelling in listOf("https://repo.example/", "https://repo.example//", "https://REPO.example", "https://repo.example:443", "HTTPS://Repo.Example/")) {
            val host = Host()
            host.publishers.trustRepository(REPOSITORY.baseUrl, publisherRoot(key))
            val installed = runCatching { host.manager.install(ExtensionRepository(spelling, "Repo"), entry()) }
            if (installed.isFailure) continue // the same publisher: refused by the install gate
            // A spelling the publisher is not pinned under installs as unsigned: it can never run.
            assertNull(host.packages.get(host.key())!!.metadata.publisherId, spelling)
            runCatching { host.manager.approveCurrentEventGrantReview(host.key(), emptySet()) }
            assertNull(host.manager.source(host.sourceId()), "$spelling runs without a revocation list")
            assertTrue(host.sandbox.created.isEmpty(), spelling)
        }
    }

    @Test
    fun withoutASandboxATrustedPackageDoesNotRun() = runTest {
        val host = Host(withSandbox = false)
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertFailsWith<ExtensionArtifactNotReviewedException> { host.manager.approveCurrentEventGrantReview(host.key(), emptySet()) }
        assertNull(host.manager.source(host.sourceId()))
    }

    @Test
    fun aTrustPromptOnlyEverTrustsTheKeyItShowed() = runTest {
        val first = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val second = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        val repository = "https://signed.example/repo"
        var presenting = first
        suspend fun repoJson(key: dev.shinsou.kmp.sync.crypto.SodiumKeyPair, at: String) = signedPublisherEnvelope(
            key, at, RepositorySignedDocumentType.REPOSITORY, "repo.json", 1,
            kotlinx.serialization.json.buildJsonObject {
                put("meta", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("Signed"))
                    put("signingKeyFingerprint", kotlinx.serialization.json.JsonPrimitive(publisherRoot(key).fingerprint))
                })
            },
        ).toString()
        val host = Host()
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond("", io.ktor.http.HttpStatusCode.NotFound) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = PublisherRepositoryTrustPolicy(host.publishers, host.blocklist, RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN),
            repositorySecurityState = host.state,
            repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
            repositoryTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved transport only")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse =
                    if (request.url.substringBefore('?').endsWith("/repo.json")) {
                        PluginHttpResponse(200, repoJson(presenting, request.url.substringBefore('?').removeSuffix("/repo.json")).encodeToByteArray())
                    }
                    else PluginHttpResponse(404, ByteArray(0))
            },
        )
        val adapter = PluginBrowseAdapter(
            manager = host.manager,
            repositoryClient = client,
            repositoryStore = KeyValueExtensionRepositoryStore(host.keyValues),
            pluginStorage = host.storage,
            keyValueStore = host.keyValues,
            trustStore = KeyValuePluginTrustStore(host.keyValues),
            packageSecurity = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, client),
        )
        val shownA = assertNotNull(adapter.inspectRepositoryTrust(repository))
        assertEquals(publisherRoot(first).fingerprint, shownA.keyFingerprint)
        // The repository now presents another key (another inspection, e.g. from a link).
        presenting = second
        val shownB = assertNotNull(adapter.inspectRepositoryTrust(repository))
        assertFailsWith<IllegalStateException> { adapter.trustAndAddRepository(shownA, null) }
        assertNull(host.publishers.publisherForRepository(repository), "the key shown first was not the one inspected last")
        runCatching { adapter.trustAndAddRepository(shownB, null) } // adding may fail later (no index); trust is what counts
        assertEquals(publisherRoot(second).fingerprint, host.publishers.publisherForRepository(repository)!!.currentFingerprint)
        assertTrue(assertNotNull(adapter.inspectRepositoryTrust(repository)).alreadyTrusted)

        // The repository is configured: another key there is still a key substitution.
        val third = SodiumSyncPrimitives.generateEd25519KeyPair().also { keys += it }
        presenting = third
        val substituted = assertNotNull(adapter.inspectRepositoryTrust(repository))
        assertFalse(substituted.alreadyTrusted)
        assertFailsWith<RepositoryTrustException.KeySubstitution> { adapter.trustAndAddRepository(substituted, null) }
        assertEquals(publisherRoot(second).fingerprint, host.publishers.publisherForRepository(repository)!!.currentFingerprint)
        // Removing it releases the URL (the publisher stays), so the user can confirm the new key.
        adapter.removeRepository(repository)
        assertNull(host.publishers.publisherForRepository(repository))
        assertNotNull(host.publishers.publisher(publisherRoot(second).fingerprint))
        runCatching { adapter.trustAndAddRepository(assertNotNull(adapter.inspectRepositoryTrust(repository)), null) }
        assertEquals(publisherRoot(third).fingerprint, host.publishers.publisherForRepository(repository)!!.currentFingerprint)

        // A mapping left behind by a repository removed before removal released it (not configured):
        // the new key is confirmed afresh instead of failing as a substitution.
        val stale = "https://signed.example/stale"
        host.publishers.trustRepository(stale, publisherRoot(first))
        presenting = second
        val restored = assertNotNull(adapter.inspectRepositoryTrust(stale))
        assertFalse(restored.alreadyTrusted)
        runCatching { adapter.trustAndAddRepository(restored, null) }
        assertEquals(publisherRoot(second).fingerprint, host.publishers.publisherForRepository(stale)!!.currentFingerprint)
        assertNotNull(host.publishers.publisher(publisherRoot(first).fingerprint))

        // A link's fingerprint is enforced even when the dialog sends none or another one.
        val other = "https://signed.example/other"
        presenting = first
        val linked = assertNotNull(adapter.inspectRepositoryTrust(other)).copy(linkedFingerprint = publisherRoot(second).fingerprint)
        assertFailsWith<PublisherTrustException.FingerprintMismatch> { adapter.trustAndAddRepository(linked, null) }
        assertFailsWith<PublisherTrustException.FingerprintMismatch> { adapter.trustAndAddRepository(linked, publisherRoot(first).fingerprint) }
        assertNull(host.publishers.publisherForRepository(other))

        // Unreachable is not "unsigned": a link must stay pending rather than be dropped.
        val unreachable = PluginBrowseAdapter(
            manager = host.manager,
            repositoryClient = ExtensionRepositoryClient(
                HttpClient(MockEngine { respond("", io.ktor.http.HttpStatusCode.NotFound) }),
                cacheToken = { 1L },
                repositoryHostResolver = PluginHostResolver { listOf("93.184.216.34") },
                repositoryTransport = object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved transport only")
                    override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse =
                        PluginHttpResponse(503, ByteArray(0))
                },
            ),
            repositoryStore = KeyValueExtensionRepositoryStore(host.keyValues),
            pluginStorage = host.storage,
            keyValueStore = host.keyValues,
            trustStore = KeyValuePluginTrustStore(host.keyValues),
            packageSecurity = PluginPackageSecurity(host.publishers, host.revocations, host.blocklist, client),
        )
        assertFailsWith<ExtensionRepositoryException.Http> { unreachable.inspectRepositoryTrust(other) }
    }

    @Test
    fun developerModeLoadsAnUnsignedDevBuildUnderItsOwnKeyAndOnlyWhileOn() = runTest {
        val host = Host()
        // Off: the development server is not a repository at all.
        assertFails { host.manager.install(DEV_REPOSITORY, entry()) }
        host.developer.setEnabled(true)
        host.manager.install(DEV_REPOSITORY, entry())
        val devKey = "$PLUGIN_ID~dev"
        val dev = assertNotNull(host.packages.get(devKey))
        assertNull(dev.metadata.publisherId)
        host.manager.approveCurrentEventGrantReview(devKey, emptySet())
        assertEquals(listOf(devKey), host.sandbox.created)
        val devSource = dev.manifest.sources!!.single().id
        assertNotNull(host.manager.source(devSource))

        // A signed install of the same id lives beside it.
        host.trustPublisher()
        host.manager.install(REPOSITORY, entry())
        assertEquals(2, host.manager.installedPlugins().size)

        // A rebuild asking for nothing new keeps its grant (D4), even without a version bump.
        host.script = "var source = { version: 2 };"
        host.manager.install(DEV_REPOSITORY, entry())
        assertNull(host.manager.pendingEventGrantReview(devKey))
        assertNotNull(host.manager.source(devSource))

        // Off again: the dev build stops; it is not deleted.
        host.manager.unloadDeveloperRuntimes()
        assertNull(host.manager.source(devSource))
        host.developer.setEnabled(false)
        host.manager.loadInstalled()
        assertNull(host.manager.source(devSource))
        assertNotNull(host.packages.get(devKey))
    }

    @Test
    fun aThirdPartySourceOpensItsWebChallengeOnlyWhenGranted() = runTest {
        suspend fun hostGranting(challenge: Boolean): Pair<Host, dev.shinsou.kmp.domain.model.SourceKey> {
            val host = Host()
            host.trustPublisher()
            host.manager.install(
                REPOSITORY,
                entry().copy(
                    runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK, PluginRuntimePermission.BROWSER_CHALLENGE),
                    sources = listOf(
                        SourceIndexEntry(
                            name = "Tier fixture", lang = "all", id = SOURCE_ID, baseUrl = "https://source.example",
                            browserSessionOrigins = setOf("https://source.example"), originPolicyVersion = 2,
                        ),
                    ),
                ),
            )
            val review = assertNotNull(host.manager.pendingEventGrantReview(host.key()))
            val runtime = if (challenge) review.requestedRuntimePermissions else review.requestedRuntimePermissions - PluginRuntimePermission.BROWSER_CHALLENGE
            host.manager.approveEventGrantReview(host.key(), review, emptySet(), dev.shinsou.kmp.plugin.events.PluginGrantSelection(emptySet(), runtime, emptySet()))
            val stored = host.manager.installedPlugins().single()
            val source = stored.manifest.sources!!.single()
            return host to dev.shinsou.kmp.domain.model.SourceKey(packageId = stored.manifest.id, sourceId = source.canonicalSourceId ?: source.id.toString(), legacyLongId = source.id)
        }
        val (declined, declinedKey) = hostGranting(challenge = false)
        assertTrue(!declined.manager.authorizeWebChallenge(declinedKey))
        assertFails { declined.manager.issueWebChallenge(declinedKey) }

        val (granted, grantedKey) = hostGranting(challenge = true)
        assertTrue(granted.manager.authorizeWebChallenge(grantedKey))
        val authorization = assertNotNull(granted.manager.issueWebChallenge(grantedKey))
        // A stored key without its numeric projection still resolves (no crash on the storage id).
        assertNotNull(granted.manager.issueWebChallenge(grantedKey.copy(legacyLongId = null)))
        assertEquals(setOf("https://source.example"), authorization.allowedSubresourceOrigins)
    }

    @Test
    fun aLegacyCommunityInstallKeepsItsGrantOnItsFirstSignedUpdate() = runTest {
        val host = Host()
        val withCookies = entry().copy(
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK, PluginRuntimePermission.COOKIE_STORAGE),
        )
        // Installed while the community repository was unsigned: bare key, no publisher.
        host.manager.install(COMMUNITY_REPOSITORY, withCookies)
        assertNull(host.packages.get(PLUGIN_ID)!!.metadata.publisherId)
        val review = assertNotNull(host.manager.pendingEventGrantReview(PLUGIN_ID))
        val selection = dev.shinsou.kmp.plugin.events.PluginGrantSelection(emptySet(), review.requestedRuntimePermissions, emptySet())
        // As the user approved it then (it ran in the reviewed tier, which this fixture is not in).
        host.admission.approve(review.artifact, emptySet(), selection)

        // The user trusts the publisher that now signs the URL; its update asks for nothing new.
        val publisher = host.trustPublisher(COMMUNITY_REPOSITORY)
        host.script = "var source = { version: 2 };"
        host.manager.install(COMMUNITY_REPOSITORY, withCookies.copy(version = "1.1.0", versionCode = 2))
        val signed = host.packages.get(PLUGIN_ID)!!
        assertEquals(publisher, signed.metadata.publisherId)
        assertNull(host.manager.pendingEventGrantReview(PLUGIN_ID))
        assertEquals(selection, host.manager.grantedSelection(PLUGIN_ID))
        assertNotNull(host.manager.source(SOURCE_ID))
    }

    @Test
    fun anUnsignedPackageFromAnotherUrlDoesNotContinueIntoAPublisher() = runTest {
        val host = Host()
        host.manager.install(REPOSITORY, entry())
        host.admission.approve(assertNotNull(host.manager.pendingEventGrantReview(PLUGIN_ID)).artifact, emptySet())
        host.trustPublisher(REPOSITORY)
        host.manager.install(REPOSITORY, entry(version = "1.1.0", versionCode = 2))
        // A different (publisher-scoped) identity, waiting for its own review.
        assertNotNull(host.manager.pendingEventGrantReview(host.manager.installedPlugins().first { it.metadata.publisherId != null }.manifest.id))
    }

    @Test
    fun aRepositoryThatNeedsPublisherTrustDoesNotFailTheRefresh() = runTest {
        val host = Host(trustPolicy = RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN)
        val result = host.manager.refreshWithAdmissions(listOf(REPOSITORY))
        assertEquals(setOf(REPOSITORY.baseUrl), result.repositoriesNeedingTrust)
    }

    @Test
    fun developerModeAdmitsOnlyThisDeviceAndEnteredPrivateAddresses() = runTest {
        val developer = PluginDeveloperModeStore(InMemoryPluginKeyValueStore())
        assertTrue(!developer.admitsRepository("http://127.0.0.1:8787"))
        developer.setEnabled(true)
        assertTrue(developer.admitsRepository("http://127.0.0.1:8787"))
        assertTrue(developer.admitsRepository("http://localhost:8787/repo"))
        assertTrue(developer.admitsRepository("http://[::1]:8787"))
        assertTrue(!developer.admitsRepository("http://192.168.1.20:8787"))
        developer.setLanHosts(listOf(" 192.168.1.20 "))
        assertTrue(developer.admitsRepository("http://192.168.1.20:8787"))
        assertTrue(!developer.admitsRepository("http://192.168.1.21:8787"))
        assertTrue(!developer.admitsRepository("http://user@192.168.1.20:8787"))
        assertTrue(!developer.admitsRepository("https://example.com"))
        for (public in listOf("8.8.8.8", "example.com", "mac-mini.local", "172.32.0.1", "127.0.0.1x")) {
            assertFails { developer.setLanHosts(listOf(public)) }
        }
        val reloaded = PluginDeveloperModeStore(InMemoryPluginKeyValueStore())
        assertTrue(!reloaded.load().enabled)
    }

    private fun entry(version: String = "1.0.0", versionCode: Int = 1) = PluginIndexEntry(
        id = PLUGIN_ID,
        name = "Tier fixture",
        version = version,
        versionCode = versionCode,
        lang = "all",
        scriptUrl = "plugins/$PLUGIN_ID.js",
        sources = listOf(SourceIndexEntry(name = "Tier fixture", lang = "all", id = SOURCE_ID, baseUrl = "https://source.example", originPolicyVersion = 1)),
        runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
    )

    private companion object {
        const val PLUGIN_ID = "all.execution-tier"
        const val SOURCE_ID = 8_101L
        const val SCRIPT = "var source = { version: 1 };"
        val REPOSITORY = ExtensionRepository("https://repo.example", "Repo")
        val OTHER_REPOSITORY = ExtensionRepository("https://other-repo.example", "Other repo")
        val DEV_REPOSITORY = ExtensionRepository("http://127.0.0.1:8787", "Dev server")
        val COMMUNITY_REPOSITORY = ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Community")
    }
}

private class StallingKeyValueStore(private val delegate: PluginKeyValueStore = InMemoryPluginKeyValueStore()) : PluginKeyValueStore {
    var stallAfterWriting: String? = null
    val stalled = kotlinx.coroutines.CompletableDeferred<Unit>()
    override suspend fun getString(key: String): String? = delegate.getString(key)
    override suspend fun putString(key: String, value: String) {
        delegate.putString(key, value)
        if (key == stallAfterWriting) {
            stalled.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
    }
    override suspend fun remove(key: String) = delegate.remove(key)
}
