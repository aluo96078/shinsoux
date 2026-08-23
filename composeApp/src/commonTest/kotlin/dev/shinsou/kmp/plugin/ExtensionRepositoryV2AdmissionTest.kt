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

class ExtensionRepositoryV2AdmissionTest {
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

        assertEquals(SHUYUE_ID, shuyue.id)
        assertEquals(SHUYUE_OPAQUE_SOURCE_ID, shuyue.sources.single().id)
        assertEquals(SHUYUE_OPAQUE_SOURCE_ID, shuyue.sourceKeys.single().sourceId)
        assertEquals(SHA256, shuyue.sha256)
        assertEquals(SIDECAR_PATH, shuyue.sidecarUrl)
        assertEquals(EVENTS, shuyue.systemEvents)
        assertEquals(PERMISSIONS, shuyue.requestedHostPermissions)
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
                repositoryClient = ExtensionRepositoryClient(http, cacheToken = { 1L }),
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
                repositoryClient = ExtensionRepositoryClient(http, cacheToken = { 1L }),
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
            repositoryClient = ExtensionRepositoryClient(http, cacheToken = { 1L }),
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
        return ExtensionRepositoryClient(http, cacheToken = { 1L })
    }

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
            ),
        ),
        sha256 = SHA256,
        byteSize = SCRIPT_BYTES.size,
        contentType = "manga",
        contract = "shinsou",
        sidecarUrl = SIDECAR_PATH,
        systemEvents = EVENTS,
        requestedHostPermissions = PERMISSIONS,
    )

    private fun v2IndexJson(): String = """
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
              "scriptUrl":"$SCRIPT_PATH",
              "sources":[{"sourceId":"9223372036854775807","name":"V2 source","lang":"en","baseUrl":"https://source.example"}],
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
              "scriptUrl":"shuyue.js",
              "sources":[{"sourceId":"$SHUYUE_OPAQUE_SOURCE_ID","name":"Opaque source","lang":"zh","baseUrl":"https://shuyue.example"}],
              "sha256":"$SHA256",
              "sidecarUrl":"$SIDECAR_PATH",
              "contentType":"novel",
              "capabilities":["LOGIN"],
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
    ): String = """
        {
          "format":"shinsou-extension-sidecar-v2",
          "contractVersion":2,
          "packageId":"$packageId",
          "version":"$version",
          "versionCode":$versionCode,
          "artifact":{"scriptUrl":"$SCRIPT_PATH","sha256":"$digest","byteSize":$byteSize},
          "content":{"contractVersion":2,"type":"$contentType"},
          "sources":[{"sourceId":"$sourceId","sourceKey":{"contractVersion":2,"packageId":"$sourcePackageId","sourceId":"$sourceId"}}],
          "systemEvents":$events,
          "requestedHostPermissions":${permissions.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")}
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
    }
}
