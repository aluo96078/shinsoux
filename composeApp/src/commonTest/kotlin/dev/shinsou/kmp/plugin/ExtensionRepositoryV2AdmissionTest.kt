package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtensionRepositoryV2AdmissionTest {
    @Test
    fun repositoryHttpRequiresExplicitLocalDeveloperCompatibility() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond(v2IndexJson(), HttpStatusCode.OK)
        })
        val insecureUrl = "http://127.0.0.1:18081"
        assertFailsWith<ExtensionRepositoryException.InvalidUrl> {
            ExtensionRepositoryClient(http).fetchIndex(insecureUrl)
        }
        assertEquals(emptyList(), requests)

        val developer = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            allowInsecureDeveloperHttp = true,
        )
        assertIs<RepositoryIndex.Combined>(developer.fetchIndex(insecureUrl))
        assertEquals(1, requests.size)
        assertFailsWith<ExtensionRepositoryException.InvalidUrl> {
            developer.fetchIndex("http://public.example")
        }
    }

    @Test
    fun admissionFingerprintCoversEveryExecutableFieldAndNormalizesSets() {
        val original = entry()
        val sameSetsDifferentOrder = original.copy(
            capabilities = original.capabilities.reversed().toCollection(linkedSetOf()),
            requestedHostPermissions = original.requestedHostPermissions.reversed().toCollection(linkedSetOf()),
        )
        assertEquals(original.admissionFingerprint(), sameSetsDifferentOrder.admissionFingerprint())

        val mutations = listOf(
            original.copy(description = "changed"),
            original.copy(sha256 = "f".repeat(64)),
            original.copy(runtime = "other-runtime"),
            original.copy(contentKinds = setOf("PLAIN_TEXT")),
            original.copy(capabilities = original.capabilities + "LOGIN"),
            original.copy(runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT)),
            original.copy(installable = false),
            original.copy(sources = original.sources!!.map { it.copy(requestOrigins = setOf("https://api.example")) }),
            original.copy(sources = original.sources!!.map { it.copy(contentKindsDeclared = false) }),
        )
        mutations.forEach { changed ->
            kotlin.test.assertNotEquals(original.admissionFingerprint(), changed.admissionFingerprint())
        }
    }

    @Test
    fun v2IndexSeparatesContractsAndPreservesLosslessIdsAndAdmissionMetadata() = runTest {
        val client = clientFor(
            index = v2IndexJson(),
            sidecar = sidecarJson(),
        )

        val index = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        val shinsou = index.plugins.single()
        val shuyue = index.shuyue.single()

        assertEquals(SHINSOU_ID, shinsou.id)
        assertEquals(9_223_372_036_854_775_807L, shinsou.sources!!.single().id)
        assertEquals(SHA256, shinsou.sha256)
        assertEquals(SCRIPT_BYTES.size, shinsou.byteSize)
        assertEquals(SIDECAR_PATH, shinsou.sidecarUrl)
        assertEquals(EVENTS, shinsou.systemEvents)
        assertEquals(PERMISSIONS, shinsou.requestedHostPermissions)
        assertEquals(BROWSER_ORIGINS, shinsou.sources!!.single().browserSessionOrigins)

        assertEquals(SHUYUE_ID, shuyue.id)
        assertEquals(SHUYUE_OPAQUE_SOURCE_ID, shuyue.sources.single().id)
        assertEquals(SHUYUE_OPAQUE_SOURCE_ID, shuyue.sourceKeys.single().sourceId)
        assertEquals(SHUYUE_RUNTIME, shuyue.runtime)
        assertEquals(SHA256, shuyue.sha256)
        assertEquals(SIDECAR_PATH, shuyue.sidecarUrl)
        assertEquals(EVENTS, shuyue.systemEvents)
        assertEquals(PERMISSIONS, shuyue.requestedHostPermissions)
    }

    @Test
    fun v2IndexKeepsAllShinsouPackagesWhenSourcePolicyFieldsAreOmitted() = runTest {
        // This mirrors the maintained shinsou_plugin index: installable Shinsou sources omit
        // the newer V2 content/origin declarations. Omission is a compatibility input, not a
        // reason to drop the complete Shinsou half while parsing a mixed repository.
        val body = buildString {
            append("""
                {
                  "format":"shinsou-extension-v2","contractVersion":2,"packages":[
            """.trimIndent())
            repeat(14) { index ->
                if (index > 0) append(',')
                append("""
                    {
                      "id":"fixture.shinsou.$index","name":"Fixture $index","version":"1.0.0",
                      "versionCode":1,"lang":"zh","nsfw":false,"contract":"shinsou",
                      "runtime":"legacy-shinsou-adapter-v2","contentKinds":["IMAGE_SEQUENCE"],
                      "capabilities":["CATALOGUE","CONTENT"],"runtimePermissions":["EXECUTE_SCRIPT"],
                      "scriptUrl":"plugins/fixture-$index.js","sha256":"${"0".repeat(64)}",
                      "byteSize":1,"sidecarUrl":"sidecars/fixture-$index.json",
                      "requestedHostPermissions":[],
                      "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
                      "sources":[{"sourceId":"${100L + index}","legacyLongId":"${100L + index}","name":"Fixture source $index","lang":"zh","baseUrl":"https://source-$index.example"}]
                    }
                """.trimIndent())
            }
            append("]}")
        }
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )

        val combined = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        assertEquals(14, combined.plugins.size)
        assertEquals((100L..113L).toList(), combined.plugins.flatMap { it.sources.orEmpty() }.map { it.id })
        combined.plugins.flatMap { it.sources.orEmpty() }.forEach { source ->
            assertEquals(2, source.originPolicyVersion)
            assertEquals(emptySet(), source.contentKinds)
            assertEquals(false, source.contentKindsDeclared)
            assertEquals(emptySet(), source.requestOrigins)
            assertEquals(emptySet(), source.credentialOrigins)
            assertEquals(emptySet(), source.contentOrigins)
            assertEquals(emptySet(), source.browserSessionOrigins)
            // V2 omission must fail closed; the source base URL is metadata, not a network grant.
            assertEquals(emptySet(), source.networkPolicy().requestOrigins)
            assertEquals(emptySet(), source.networkPolicy().contentOrigins)
        }
    }

    @Test
    fun officialMixedV2IndexKeepsInstallableShinsouAndShuYuePartitions() = runTest {
        // Keep this fixture in-tree instead of reading the sibling repository at test time. It
        // mirrors the maintained mixed index's important shape: 14 executable Shinsou packages,
        // two Shinsou reference packages, and four reviewed ShuYue packages (one compatibility
        // package). The contract field, rather than package names or URL hints, is the partition
        // boundary that the repository/UI pipeline must preserve.
        val shinsouIds = listOf(
            "eh.ehentai", "all.nhentai", "zh.jinmantiantang", "zh.baozimh", "zh.bika",
            "zh.manhuagui", "zh.komiic", "zh.wnacg", "zh.mycomic", "zh.dm5",
            "zh.manhuaren", "zh.mangacopy", "all.mangadex", "zh.bilimanga.manga",
        )
        val referenceIds = listOf("example.login", "example.dual")
        val shuyueIds = listOf("zh.bilimanga", "zh.wenku8", "zh.wenku8.api", "zh.biquge.tw")
        val body = buildString {
            append("{\"format\":\"shinsou-extension-v2\",\"contractVersion\":2,\"packages\":[")
            var first = true
            fun separator() {
                if (!first) append(',')
                first = false
            }
            shinsouIds.forEachIndexed { index, id ->
                separator()
                append(officialShinsouPackageJson(id, 10_000L + index))
            }
            referenceIds.forEachIndexed { index, id ->
                separator()
                append(officialReferencePackageJson(id, index))
            }
            shuyueIds.forEachIndexed { index, id ->
                separator()
                append(officialShuYuePackageJson(id, index, compatibilityOnly = id == "zh.wenku8"))
            }
            append("]}")
        }
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )

        val combined = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        assertEquals(shinsouIds.toSet(), combined.plugins.map { it.id }.toSet())
        assertEquals(14, combined.plugins.count { it.installable && !it.referenceOnly && !it.legacyCompatibilityOnly })
        // Reference-only Shinsou records are intentionally validated but omitted from the
        // executable partition; they must not become installable descriptors.
        assertTrue(combined.plugins.none { it.id in referenceIds })
        assertEquals(shuyueIds.toSet(), combined.shuyue.map { it.id }.toSet())
        assertEquals(4, combined.shuyue.size)
        assertEquals(setOf("zh.wenku8"), combined.shuyue.filter { it.legacyCompatibilityOnly }.map { it.id }.toSet())
        assertTrue(combined.plugins.none { it.id in shuyueIds })
        assertTrue(combined.shuyue.none { it.id in shinsouIds })
    }

    @Test
    fun v2SidecarMismatchesFailClosedBeforeAnArtifactCanBeAdmitted() = runTest {
        val client = clientFor(
            index = v2IndexJson(),
            sidecar = sidecarJson(),
        )
        val index = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        val entry = index.plugins.single()

        val mismatches = listOf(
            "package" to sidecarJson(packageId = "other.package"),
            "version" to sidecarJson(version = "2.0.1"),
            "version code" to sidecarJson(versionCode = 8),
            "digest" to sidecarJson(digest = "f".repeat(64)),
            "size" to sidecarJson(byteSize = SCRIPT_BYTES.size + 1),
            "source key package" to sidecarJson(sourcePackageId = "other.package"),
            "source key id" to sidecarJson(sourceId = "source/other"),
            "content" to sidecarJson(contentType = "novel"),
            "events" to sidecarJson(
                events = """
                    {"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                     "required":["command.source.refresh.request"],"optional":[]}
                """.trimIndent(),
            ),
            "permissions" to sidecarJson(permissions = listOf("REQUEST_SOURCE_REFRESH")),
            "browser-session origins" to sidecarJson(browserOrigins = listOf("https://other-api.example")),
            "required package field" to sidecarJson().replace("\"runtime\":\"$RUNTIME\",", ""),
            "runtime null" to sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":null,"),
            "runtime wrong type" to sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":7,"),
            "runtime blank" to sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":\" \","),
            "runtime mismatch" to sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":\"other-runtime\",") ,
            "invalid optional source field" to sidecarJson().replace(
                "\"requestOrigins\":[],",
                "\"requestOrigins\":{},",
            ),
        )

        mismatches.forEach { (label, sidecar) ->
            val mismatchClient = clientFor(index = v2IndexJson(), sidecar = sidecar)
            val mismatchIndex = assertIs<RepositoryIndex.Combined>(mismatchClient.fetchIndex(REPOSITORY_URL))
            val mismatchEntry = mismatchIndex.plugins.single()
            assertFailsWith<ExtensionRepositoryException.InvalidDocument>(label) {
                mismatchClient.verifyPluginV2Sidecar(REPOSITORY_URL, mismatchEntry)
            }
        }
    }

    @Test
    fun sidecarSourceContentTypeMayInheritPackageTypeWhenIndexSourceOmitsIt() = runTest {
        val client = clientFor(
            index = v2IndexJson(),
            sidecar = sidecarJson().replace(
                "\"baseUrl\":\"https://source.example\",\"contentType\":\"manga\",",
                "\"baseUrl\":\"https://source.example\",",
            ),
        )
        val index = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        client.verifyPluginV2Sidecar(REPOSITORY_URL, index.plugins.single())
    }

    @Test
    fun explicitSidecarSourceContentTypeMismatchIsRejected() = runTest {
        val client = clientFor(
            index = v2IndexJson(),
            sidecar = sidecarJson().replace(
                "\"baseUrl\":\"https://source.example\",\"contentType\":\"manga\",",
                "\"baseUrl\":\"https://source.example\",\"contentType\":\"novel\",",
            ),
        )
        val index = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
            client.verifyPluginV2Sidecar(REPOSITORY_URL, index.plugins.single())
        }
    }

    @Test
    fun omittedSourceContentTypeCannotInheritMixedPackageType() = runTest {
        val client = clientFor(
            index = v2IndexJson().replace("\"contentType\":\"manga\"", "\"contentType\":\"both\""),
            sidecar = sidecarJson().replace("\"type\":\"manga\"", "\"type\":\"both\""),
        )
        val index = assertIs<RepositoryIndex.Combined>(client.fetchIndex(REPOSITORY_URL))
        assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
            client.verifyPluginV2Sidecar(REPOSITORY_URL, index.plugins.single())
        }
    }

    @Test
    fun officialShinsouSidecarMayOmitRuntimeAndInheritsTheIndexRuntime() = runTest {
        val sidecar = sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "")
        val http = HttpClient(MockEngine { respond(sidecar, HttpStatusCode.OK) })
        try {
            developerClient(http).verifyPluginV2Sidecar(
                OFFICIAL_SHINSOU_REPOSITORY_BASE_URL,
                entry(),
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun v2RuntimeMustMatchTheContractAllowlist() = runTest {
        val invalidIndexes = listOf(
            "Shinsou with ShuYue runtime" to v2IndexJson(
                shinsouRuntime = SHUYUE_RUNTIME,
            ),
            "Shinsou with unknown runtime" to v2IndexJson(
                shinsouRuntime = "unknown-runtime",
            ),
            "ShuYue with Shinsou runtime" to v2IndexJson(
                shuyueRuntime = RUNTIME,
            ),
            "ShuYue with unknown runtime" to v2IndexJson(
                shuyueRuntime = "unknown-runtime",
            ),
        )
        invalidIndexes.forEach { (label, invalid) ->
            val http = HttpClient(MockEngine { respond(invalid, HttpStatusCode.OK) })
            try {
                assertFailsWith<ExtensionRepositoryException.InvalidDocument>(label) {
                    developerClient(http).fetchIndex(REPOSITORY_URL)
                }
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun sourceContentKindOmissionInheritsPackageButExplicitEmptyDoesNot() = runTest {
        val omittedIndex = v2IndexJson().replace(
            "\"contentKinds\":[\"IMAGE_SEQUENCE\"],\"requestOrigins\":[]",
            "\"requestOrigins\":[]",
        )
        val omittedClient = clientFor(omittedIndex, sidecarJson())
        val omittedEntry = assertIs<RepositoryIndex.Combined>(
            omittedClient.fetchIndex(REPOSITORY_URL),
        ).plugins.single()
        assertEquals(false, omittedEntry.sources!!.single().contentKindsDeclared)
        omittedClient.verifyPluginV2Sidecar(REPOSITORY_URL, omittedEntry)

        val emptyIndex = v2IndexJson().replace(
            "\"contentKinds\":[\"IMAGE_SEQUENCE\"],\"requestOrigins\":[]",
            "\"contentKinds\":[],\"requestOrigins\":[]",
        )
        val emptyClient = clientFor(emptyIndex, sidecarJson())
        val emptyEntry = assertIs<RepositoryIndex.Combined>(
            emptyClient.fetchIndex(REPOSITORY_URL),
        ).plugins.single()
        assertEquals(true, emptyEntry.sources!!.single().contentKindsDeclared)
        assertEquals(emptySet(), emptyEntry.sources!!.single().contentKinds)
        assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
            emptyClient.verifyPluginV2Sidecar(REPOSITORY_URL, emptyEntry)
        }
    }

    @Test
    fun sidecarFailureNeverFetchesScriptAndEverySidecarIsFetchedBeforeScript() = runTest {
        val mismatches = listOf(
            sidecarJson(packageId = "other.package"),
            sidecarJson(version = "2.0.1"),
            sidecarJson(versionCode = 8),
            sidecarJson(digest = "f".repeat(64)),
            sidecarJson(byteSize = SCRIPT_BYTES.size + 1),
            sidecarJson(sourcePackageId = "other.package"),
            sidecarJson(sourceId = "source/other"),
            sidecarJson(contentType = "novel"),
            sidecarJson(
                events = """
                    {"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                     "required":["command.source.refresh.request"],"optional":[]}
                """.trimIndent(),
            ),
            sidecarJson(permissions = listOf("REQUEST_SOURCE_REFRESH")),
            sidecarJson(browserOrigins = listOf("https://other-api.example")),
            sidecarJson().replace("\"runtime\":\"$RUNTIME\",", ""),
            sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":null,"),
            sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":7,"),
            sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":\" \","),
            sidecarJson().replace("\"runtime\":\"$RUNTIME\",", "\"runtime\":\"other-runtime\",") ,
            sidecarJson().replace("\"requestOrigins\":[],", "\"requestOrigins\":{},"),
        )

        mismatches.forEach { sidecar ->
            val requests = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                requests += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("/$SIDECAR_PATH") ->
                        respond(sidecar, HttpStatusCode.OK)
                    request.url.encodedPath.endsWith("/$SCRIPT_PATH") ->
                        error("script fetch must not follow a rejected sidecar")
                    else -> respond("not found", HttpStatusCode.NotFound)
                }
            })
            val keyValues = InMemoryPluginKeyValueStore()
            val storage = KeyValuePluginStorage(keyValues)
            val manager = PluginManager(
                repositoryClient = developerClient(http),
                packageStore = InMemoryPluginPackageStore(),
                verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
                runtimeFactory = NoopScriptPluginRuntimeFactory,
                environment = ScriptPluginEnvironment(
                    network = PluginNetworkClient(
                        transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                        storage = storage,
                    ),
                    storage = storage,
                ),
            )
            try {
                assertFailsWith<ExtensionRepositoryException.InvalidDocument> {
                    manager.install(REPOSITORY, entry())
                }
                assertEquals(listOf("/$SIDECAR_PATH"), requests)
            } finally {
                manager.close()
                http.close()
            }
        }
    }

    @Test
    fun nonInstallableReferenceAndLegacyCompatibilityEntriesAreRejectedBeforeFetch() = runTest {
        val rejected = listOf(
            "installable=false" to entry().copy(installable = false),
            "reference-only" to entry().copy(referenceOnly = true),
            "legacy-compatibility-only" to entry().copy(legacyCompatibilityOnly = true),
        )

        rejected.forEach { (label, candidate) ->
            val requests = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                requests += request.url.encodedPath
                respond(sidecarJson(), HttpStatusCode.OK)
            })
            val keyValues = InMemoryPluginKeyValueStore()
            val storage = KeyValuePluginStorage(keyValues)
            val packageStore = InMemoryPluginPackageStore()
            val manager = PluginManager(
                repositoryClient = developerClient(http),
                packageStore = packageStore,
                verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
                runtimeFactory = NoopScriptPluginRuntimeFactory,
                environment = ScriptPluginEnvironment(
                    network = PluginNetworkClient(
                        transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                        storage = storage,
                    ),
                    storage = storage,
                ),
            )
            try {
                assertFailsWith<IllegalArgumentException>(label) {
                    manager.install(REPOSITORY, candidate)
                }
                assertEquals(emptyList(), requests)
                assertEquals(null, packageStore.get(candidate.id))
            } finally {
                manager.close()
                http.close()
            }
        }
    }

    @Test
    fun admittedV2EntryRetainsDigestEventsAndPermissionsInInstalledManifest() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/$SIDECAR_PATH") ->
                    respond(sidecarJson(), HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/$SCRIPT_PATH") ->
                    respond(SCRIPT_BYTES, HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val packageStore = InMemoryPluginPackageStore()
        val manager = PluginManager(
            repositoryClient = developerClient(http),
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage = storage,
                ),
                storage = storage,
            ),
        )

        try {
            manager.install(REPOSITORY, entry())
            val installed = requireNotNull(packageStore.get(SHINSOU_ID))
            assertEquals(SHA256, installed.metadata.installedSha256)
            assertEquals(EVENTS, installed.manifest.systemEvents)
            assertEquals(PERMISSIONS, installed.manifest.requestedHostPermissions)
            assertEquals(BROWSER_ORIGINS, installed.manifest.sources!!.single().browserSessionOrigins)
            assertEquals(SCRIPT_BYTES.toList(), installed.scriptBytes.toList())
            assertEquals(listOf("/$SIDECAR_PATH", "/$SCRIPT_PATH"), requests)
        } finally {
            manager.close()
            http.close()
        }
    }

    private fun clientFor(index: String, sidecar: String): ExtensionRepositoryClient {
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/index.json") -> respond(index, HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/$SIDECAR_PATH") -> respond(sidecar, HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        return developerClient(http)
    }

    private fun developerClient(http: HttpClient): ExtensionRepositoryClient = ExtensionRepositoryClient(
        http,
        cacheToken = { 1L },
        repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
    )

    private fun entry(): PluginIndexEntry = PluginIndexEntry(
        id = SHINSOU_ID,
        name = "V2 Shinsou",
        version = "2.0.0",
        versionCode = 7,
        lang = "en",
        scriptUrl = SCRIPT_PATH,
        sources = listOf(
            SourceIndexEntry(
                name = "V2 source",
                lang = "en",
                id = 9_223_372_036_854_775_807L,
                baseUrl = "https://source.example",
                contentKinds = CONTENT_KINDS,
                contentKindsDeclared = true,
                browserSessionOrigins = BROWSER_ORIGINS,
                legacyLongId = "9223372036854775807",
                canonicalSourceId = "9223372036854775807",
            ),
        ),
        sha256 = SHA256,
        byteSize = SCRIPT_BYTES.size,
        contentType = "manga",
        contract = "shinsou",
        runtime = RUNTIME,
        contentKinds = CONTENT_KINDS,
        capabilities = CAPABILITIES,
        sidecarUrl = SIDECAR_PATH,
        systemEvents = EVENTS,
        requestedHostPermissions = PERMISSIONS,
        runtimePermissions = RUNTIME_PERMISSIONS,
    )

    private fun officialShinsouPackageJson(id: String, sourceId: Long): String = """
        {"contract":"shinsou","id":"$id","name":"$id","version":"1.0.0","versionCode":1,
         "lang":"zh","nsfw":false,"runtime":"legacy-shinsou-adapter-v2",
         "scriptUrl":"plugins/$id.js","contentKinds":["IMAGE_SEQUENCE"],
         "capabilities":["CATALOGUE","CONTENT"],"runtimePermissions":["EXECUTE_SCRIPT"],
         "sources":[{"sourceId":"$sourceId","legacyLongId":"$sourceId","name":"$id","lang":"zh","baseUrl":"https://$id.example"}],
         "sha256":"${"0".repeat(64)}","byteSize":1,"sidecarUrl":"sidecars/$id.json",
         "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
         "requestedHostPermissions":[],"installable":true}
    """.trimIndent()

    private fun officialReferencePackageJson(id: String, offset: Int): String = """
        {"contract":"shinsou","id":"$id","name":"$id","version":"1.0.0","versionCode":1,
         "lang":"all","nsfw":false,"runtime":"legacy-shinsou-adapter-v2",
         "scriptUrl":"plugins/$id.js","contentType":"manga","contentKinds":["IMAGE_SEQUENCE"],
         "capabilities":["CATALOGUE","CONTENT"],"runtimePermissions":["EXECUTE_SCRIPT"],
         "sources":[{"sourceId":"$id","legacyLongId":null,"name":"$id","lang":"all","baseUrl":"https://reference-$offset.example"}],
         "sha256":"${"1".repeat(64)}","byteSize":1,"sidecarUrl":"sidecars/$id.json",
         "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
         "requestedHostPermissions":[],"installable":true,"referenceOnly":true}
    """.trimIndent()

    private fun officialShuYuePackageJson(id: String, offset: Int, compatibilityOnly: Boolean): String = """
        {"contract":"shuyue","id":"$id","name":"$id","version":"1.0.0","versionCode":1,
         "lang":"zh","nsfw":false,"runtime":"reviewed-shuyue-adapter-v2",
         "scriptUrl":"plugins/$id.js","contentType":"novel","contentKinds":["PLAIN_TEXT"],
         "capabilities":["BROWSE","SEARCH","METADATA","UNITS","CONTENT"],
         "runtimePermissions":["EXECUTE_SCRIPT"],"sources":[{"sourceId":"$id","name":"$id","lang":"zh","baseUrl":"https://shuyue-$offset.example"}],
         "sha256":"${"2".repeat(64)}","byteSize":1,"sidecarUrl":"sidecars/$id.json",
         "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,"required":[],"optional":[]},
         "requestedHostPermissions":[],"installable":true,"legacyCompatibilityOnly":$compatibilityOnly}
    """.trimIndent()

    private fun v2IndexJson(
        shinsouRuntime: String = RUNTIME,
        shuyueRuntime: String = SHUYUE_RUNTIME,
    ): String = """
        {
          "format":"shinsou-extension-v2",
          "contractVersion":2,
          "packages":[
            {
              "contract":"shinsou",
              "id":"$SHINSOU_ID",
              "name":"V2 Shinsou",
              "version":"2.0.0",
              "versionCode":7,
              "lang":"en",
              "nsfw":false,
              "runtime":"$shinsouRuntime",
              "scriptUrl":"$SCRIPT_PATH",
              "contentKinds":["IMAGE_SEQUENCE"],
              "capabilities":["CATALOGUE","CONTENT"],
              "runtimePermissions":["EXECUTE_SCRIPT","NETWORK"],
              "sources":[{"sourceId":"9223372036854775807","legacyLongId":"9223372036854775807","name":"V2 source","lang":"en","baseUrl":"https://source.example","contentKinds":["IMAGE_SEQUENCE"],"requestOrigins":[],"credentialOrigins":[],"contentOrigins":[],"browserSessionOrigins":["https://api.example"]}],
              "sha256":"$SHA256",
              "byteSize":${SCRIPT_BYTES.size},
              "contentType":"manga",
              "sidecarUrl":"$SIDECAR_PATH",
              "systemEvents":${eventsJson()},
              "requestedHostPermissions":["REQUEST_LOGIN_UI","REPORT_DIAGNOSTIC"]
            },
            {
              "contract":"shuyue",
              "id":"$SHUYUE_ID",
              "name":"V2 ShuYue",
              "version":"2.0.0",
              "versionCode":8,
              "lang":"zh",
              "nsfw":false,
              "runtime":"$shuyueRuntime",
              "scriptUrl":"shuyue.js",
              "sources":[{"sourceId":"$SHUYUE_OPAQUE_SOURCE_ID","name":"Opaque source","lang":"zh","baseUrl":"https://shuyue.example"}],
              "sha256":"$SHA256",
              "byteSize":${SCRIPT_BYTES.size},
              "sidecarUrl":"$SIDECAR_PATH",
              "contentType":"novel",
              "capabilities":["LOGIN"],
              "runtimePermissions":["EXECUTE_SCRIPT"],
              "systemEvents":${eventsJson()},
              "requestedHostPermissions":["REQUEST_LOGIN_UI","REPORT_DIAGNOSTIC"]
            }
          ]
        }
    """.trimIndent()

    private fun sidecarJson(
        packageId: String = SHINSOU_ID,
        version: String = "2.0.0",
        versionCode: Int = 7,
        digest: String = SHA256,
        byteSize: Int = SCRIPT_BYTES.size,
        sourcePackageId: String = SHINSOU_ID,
        sourceId: String = "9223372036854775807",
        contentType: String = "manga",
        events: String = eventsJson(),
        permissions: List<String> = listOf("REQUEST_LOGIN_UI", "REPORT_DIAGNOSTIC"),
        browserOrigins: List<String> = listOf("https://api.example"),
    ): String = """
        {
          "format":"shinsou-extension-sidecar-v2",
          "contractVersion":2,
          "packageId":"$packageId",
          "name":"V2 Shinsou",
          "version":"$version",
          "versionCode":$versionCode,
          "contract":"shinsou",
          "runtime":"$RUNTIME",
          "lang":"en",
          "nsfw":false,
          "installable":true,
          "referenceOnly":false,
          "legacyCompatibilityOnly":false,
          "artifact":{"scriptUrl":"$SCRIPT_PATH","sha256":"$digest","byteSize":$byteSize},
          "content":{"contract":"extension-content-v2","contractVersion":2,"type":"$contentType","kinds":["IMAGE_SEQUENCE"]},
          "capabilities":["CATALOGUE","CONTENT"],
              "sources":[{"sourceId":"$sourceId","name":"V2 source","lang":"en","baseUrl":"https://source.example","contentType":"$contentType","contentKinds":["IMAGE_SEQUENCE"],"requestOrigins":[],"credentialOrigins":[],"contentOrigins":[],"browserSessionOrigins":${browserOrigins.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},"capabilities":["CATALOGUE","CONTENT"],"systemEvents":$events,"requestedHostPermissions":${permissions.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},"runtimePermissions":["EXECUTE_SCRIPT","NETWORK"],"sourceKey":{"contractVersion":2,"packageId":"$sourcePackageId","sourceId":"$sourceId","legacyLongId":"9223372036854775807"}}],
          "systemEvents":$events,
          "requestedHostPermissions":${permissions.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},
          "runtimePermissions":["EXECUTE_SCRIPT","NETWORK"]
        }
    """.trimIndent()

    private fun eventsJson(): String = """
        {"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
         "required":["command.auth.login.request"],"optional":["event.diagnostic.message.report"]}
    """.trimIndent()

    private companion object {
        const val REPOSITORY_URL = "https://repo.example"
        val REPOSITORY = ExtensionRepository(REPOSITORY_URL, "V2 repository")
        const val SHINSOU_ID = "manga.v2"
        const val SHUYUE_ID = "novel.v2"
        const val SHUYUE_OPAQUE_SOURCE_ID = "source/opaque:9119537447562549661"
        const val SCRIPT_PATH = "scripts/manga.js"
        const val SIDECAR_PATH = "metadata/manga.sidecar.json"
        const val SHA256 = "d29edda660fab38d92b2a517ac67484e5bf5756110431c9c2cfb8b7176c1dc3b"
        val SCRIPT_BYTES = "var source = {};".encodeToByteArray()
        val EVENTS = PluginSystemEventDeclaration(
            minVersion = 1,
            maxVersion = 1,
            required = setOf("command.auth.login.request"),
            optional = setOf("event.diagnostic.message.report"),
        )
        val PERMISSIONS = setOf(
            PluginHostPermission.REQUEST_LOGIN_UI,
            PluginHostPermission.REPORT_DIAGNOSTIC,
        )
        val BROWSER_ORIGINS = setOf("https://api.example")
        const val RUNTIME = "legacy-shinsou-adapter-v2"
        const val SHUYUE_RUNTIME = "reviewed-shuyue-adapter-v2"
        val CONTENT_KINDS = setOf("IMAGE_SEQUENCE")
        val CAPABILITIES = setOf("CATALOGUE", "CONTENT")
        val RUNTIME_PERMISSIONS = setOf(
            PluginRuntimePermission.EXECUTE_SCRIPT,
            PluginRuntimePermission.NETWORK,
        )
    }
}
