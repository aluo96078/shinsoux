package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShuYueRepositoryIndexLoaderTest {
    @Test
    fun loadsV2PackagesByContractAndNormalizesReviewedShuYueMetadata() = runTest {
        val indexUrl = "https://repo.example/v2/index.json"
        val scriptUrl = "https://repo.example/v2/plugins/novel.js"
        val sidecarUrl = "https://repo.example/v2/sidecars/novel.json"
        val script = "reviewed v2 script".encodeToByteArray()
        val digest = Sha256.hex(script)
        val body = """
            {
              "format":"shinsou-extension-v2",
              "contractVersion":2,
              "packages":[
                {"contract":"shinsou","id":"manga.v2","name":"Manga","version":"1.0.0",
                 "versionCode":1,"lang":"en","nsfw":true,"scriptUrl":"plugins/manga.js",
                 "sources":[{"sourceId":9223372036854775807,"name":"Manga","lang":"en","baseUrl":"https://manga.example"}]},
                {"contract":"shuyue","id":"novel.v2","name":"Novel","version":"2.0.0",
                 "versionCode":2,"lang":"zh","nsfw":false,"scriptUrl":"plugins/novel.js",
                 "contentType":"novel","sha256":"$digest","byteSize":${script.size},
                 "sidecarUrl":"sidecars/novel.json","capabilities":["BROWSE","LATEST","LOGIN","FAVORITE"],
                 "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                   "required":["command.auth.login.request"],"optional":["event.diagnostic.message.report"]},
                 "requestedHostPermissions":["REQUEST_LOGIN_UI"],"installable":true,
                 "referenceOnly":false,"legacyCompatibilityOnly":false,
                 "unknownV2Key":{"ignored":true},
                 "sources":[{"sourceId":"opaque:source/9119537447562549661","name":"Novel","lang":"zh",
                   "baseUrl":"https://novel.example","unknownSourceKey":[1,2,3]}]}
              ]
            }
        """.trimIndent().encodeToByteArray()
        val sidecar = """
            {
              "format":"shinsou-extension-sidecar-v2","contractVersion":2,
              "packageId":"novel.v2","name":"Novel","version":"2.0.0","versionCode":2,
              "contract":"shuyue","lang":"zh","nsfw":false,"installable":true,
              "referenceOnly":false,"legacyCompatibilityOnly":false,
              "artifact":{"scriptUrl":"plugins/novel.js","sha256":"$digest","byteSize":${script.size}},
              "content":{"contract":"extension-content-v2","contractVersion":2,"type":"novel","kinds":["PLAIN_TEXT"]},
              "capabilities":["BROWSE","LATEST","LOGIN","FAVORITE"],
              "systemEvents":{"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                "required":["command.auth.login.request"],"optional":["event.diagnostic.message.report"]},
              "requestedHostPermissions":["REQUEST_LOGIN_UI"],
              "sources":[{"sourceKey":{"contractVersion":2,"packageId":"novel.v2","sourceId":"opaque:source/9119537447562549661"},
                "sourceId":"opaque:source/9119537447562549661","name":"Novel","lang":"zh","baseUrl":"https://novel.example"}]
            }
        """.trimIndent().encodeToByteArray()
        val transport = FixtureTransport(
            mapOf(
                indexUrl to ok(body, indexUrl),
                sidecarUrl to ok(sidecar, sidecarUrl),
                scriptUrl to ok(script, scriptUrl),
            ),
        )

        val loader = ShuYueRepositoryIndexLoader(
            transport,
            ShuYueRepositoryLimits(allowedArtifactOrigins = setOf("https://repo.example")),
        )
        val loaded = loader.load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        val entry = loaded.entries.single()
        assertEquals("novel.v2", entry.id)
        assertEquals("opaque:source/9119537447562549661", entry.sources.single().id)
        assertEquals(dev.shinsou.kmp.plugin.PluginContentType.NOVEL, entry.resolvedContentType)
        assertTrue(entry.sources.single().supportsLogin)
        assertTrue(entry.sources.single().supportsLatest)
        assertTrue(entry.sources.single().supportsFavorites)
        assertEquals(digest, entry.sha256)
        assertEquals(script.size, entry.byteSize)
        assertEquals("sidecars/novel.json", entry.sidecarUrl)
        assertEquals(
            setOf("command.auth.login.request"),
            entry.systemEvents?.required,
        )
        assertEquals(setOf("REQUEST_LOGIN_UI"), entry.requestedHostPermissions.map { it.name }.toSet())
        assertTrue(entry.installable)
        assertFalse(entry.referenceOnly)
        assertFalse(entry.legacyCompatibilityOnly)

        val artifact = loader.downloadScript(loaded, entry)
        assertEquals(digest, artifact.sha256)
        assertEquals("sidecars/novel.json", artifact.metadata.sidecarUrl)
        assertEquals(sidecarUrl, artifact.metadata.resolvedSidecarUrl)
        assertEquals(sidecarUrl, artifact.metadata.sidecarDownloadedFinalUrl)
        assertEquals(Sha256.hex(sidecar), artifact.metadata.sidecarSha256)
        assertEquals(sidecar.size, artifact.metadata.sidecarByteSize)
    }

    @Test
    fun v2SidecarMismatchesFailClosedWithoutFetchingScript() = runTest {
        val mismatches = listOf(
            "digest" to v2SidecarJson(digest = "f".repeat(64)),
            "byte size" to v2SidecarJson(byteSize = V2_SCRIPT.size + 1),
            "source key package" to v2SidecarJson(sourcePackageId = "other.package"),
            "source key id" to v2SidecarJson(sourceId = "other-source"),
            "system events" to v2SidecarJson(
                events = """
                    {"protocol":"dev.shinsou.system","minVersion":1,"maxVersion":1,
                     "required":["command.source.refresh.request"],"optional":[]}
                """.trimIndent(),
            ),
            "requested host permissions" to v2SidecarJson(permissions = listOf("REQUEST_SOURCE_REFRESH")),
        )

        mismatches.forEach { (label, sidecar) ->
            val requests = mutableListOf<String>()
            val transport = v2FixtureTransport(sidecar, requests)
            val mismatchLoader = loader(transport)
            val index = mismatchLoader.load(ShuYueRepositoryLocation.IndexUrl(V2_INDEX_URL))

            expectFailure<ShuYueRepositoryException.InvalidMetadata>(label) {
                mismatchLoader.downloadScript(index, index.entries.single())
            }

            assertEquals(listOf(V2_INDEX_URL, V2_SIDECAR_URL), requests, label)
            assertFalse(V2_SCRIPT_URL in requests, label)
        }
    }

    @Test
    fun v2HappyPathFetchesIndexThenSidecarThenScript() = runTest {
        val requests = mutableListOf<String>()
        val transport = v2FixtureTransport(v2SidecarJson(), requests)
        val v2Loader = loader(transport)
        val index = v2Loader.load(ShuYueRepositoryLocation.IndexUrl(V2_INDEX_URL))

        val artifact = v2Loader.downloadScript(index, index.entries.single())

        assertEquals(V2_SCRIPT.toList(), artifact.copyBytes().toList())
        assertEquals(
            listOf(V2_INDEX_URL, V2_SIDECAR_URL, V2_SCRIPT_URL),
            requests,
        )
    }

    @Test
    fun readsShuyueHalfFromUnifiedEnvelopeWithoutUsingTheUrlName() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val body = """
            {"format":"shinsou-unified-v1","shinsou":[
              {"id":"manga","name":"Manga","version":"1.0.0","versionCode":1,"lang":"zh",
               "nsfw":0,"scriptUrl":"manga.js","sources":[{"id":1,"name":"Manga","lang":"zh","baseUrl":"https://manga.example"}]}
            ],"shuyue":[
              {"id":"novel","name":"Novel","version":"1.0.0","versionCode":1,"lang":"zh",
               "nsfw":0,"scriptUrl":"novel.js","type":"novel",
               "sources":[{"id":"novel-source","name":"Novel","lang":"zh","baseUrl":"https://novel.example","type":"novel"}]}
            ]}
        """.trimIndent().encodeToByteArray()

        val loaded = loader(FixtureTransport(mapOf(indexUrl to ok(body, indexUrl))))
            .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        assertEquals(listOf("novel"), loaded.entries.map { it.id })
        assertEquals(dev.shinsou.kmp.plugin.PluginContentType.NOVEL, loaded.entries.single().resolvedContentType)
    }

    @Test
    fun acceptsOptionalRepositoryIconMetadata() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val body = """
            [{"id":"package","name":"Package","version":"1.0.0","versionCode":1,"lang":"zh",
              "nsfw":0,"scriptUrl":"package.js","iconUrl":null,
              "sources":[{"id":"source","name":"Source","lang":"zh","baseUrl":"https://source.example"}]}]
        """.trimIndent().encodeToByteArray()

        val loaded = loader(FixtureTransport(mapOf(indexUrl to ok(body, indexUrl))))
            .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        assertEquals(null, loaded.entries.single().iconUrl)
    }

    @Test
    fun acceptsLegacyNumericSourceIdsWithoutLosingOpaqueDigits() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val largeId = "9119537447562549661"
        val body = """
            [{"id":"package","name":"Package","version":"1.0.0","versionCode":1,"lang":"zh",
              "nsfw":0,"scriptUrl":"package.js","iconUrl":null,
              "sources":[{"id":6912170,"name":"Source","lang":"zh","baseUrl":"https://source.example"},
                         {"id":$largeId,"name":"Large","lang":"zh","baseUrl":"https://large.example"}]}]
        """.trimIndent().encodeToByteArray()

        val loaded = loader(FixtureTransport(mapOf(indexUrl to ok(body, indexUrl))))
            .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        assertEquals(listOf("6912170", largeId), loaded.entries.single().sources.map { it.id })
    }

    @Test
    fun indexUrlIsRequestedAsIsAndScriptsUseTheRedirectedIndexDirectory() = runTest {
        val requestUrl = "https://repo.example/custom/index.json?token=keep"
        val finalUrl = "https://cdn.example/releases/index.json?cache=7"
        val transport = FixtureTransport(
            mapOf(
                requestUrl to ShuYueRepositoryResponse(
                    200,
                    indexJson(scriptUrl = "wenku8.js?v=1.2.3"),
                    finalUrl,
                    redirectChain = listOf(finalUrl),
                ),
            ),
        )
        val loader = loader(
            transport,
            allowedOrigins = setOf("https://repo.example", "https://cdn.example"),
        )

        val index = loader.load(ShuYueRepositoryLocation.IndexUrl(requestUrl))

        assertEquals(1, transport.requests.size)
        assertEquals(requestUrl, transport.requests.single().url)
        assertEquals(ShuYueRepositoryLimits.DEFAULT_MAX_INDEX_BYTES, transport.requests.single().maxBytes)
        assertEquals(
            setOf(ShuYueOrigin.parse("https://repo.example"), ShuYueOrigin.parse("https://cdn.example")),
            transport.requests.single().allowedArtifactOrigins,
        )
        assertEquals(requestUrl, index.requestedIndexUrl)
        assertEquals(finalUrl, index.finalIndexUrl)
        assertEquals("https://cdn.example/releases/wenku8.js?v=1.2.3", index.entries.single().resolvedScriptUrl)
    }

    @Test
    fun baseUrlResolvesExactlyOneIndexJsonAndUsesFinalUrlForScripts() = runTest {
        val baseUrl = "https://repo.example/extensions/"
        val indexRequest = "https://repo.example/extensions/index.json"
        val finalUrl = "https://cdn.example/published/v4/index.json"
        val transport = FixtureTransport(
            mapOf(
                indexRequest to ShuYueRepositoryResponse(
                    200,
                    indexJson(scriptUrl = "scripts/source.js?v=9"),
                    finalUrl,
                    redirectChain = listOf(finalUrl),
                ),
            ),
        )
        val index = loader(
            transport,
            allowedOrigins = setOf("https://repo.example", "https://cdn.example"),
        ).load(ShuYueRepositoryLocation.BaseUrl(baseUrl))

        assertEquals(indexRequest, transport.requests.single().url)
        assertFalse(transport.requests.single().url.endsWith("index.json/index.json"))
        assertEquals("https://cdn.example/published/v4/scripts/source.js?v=9", index.entries.single().resolvedScriptUrl)
    }

    @Test
    fun scriptDownloadRetainsBytesDigestAndQuarantineMetadataWithoutEvaluation() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val scriptUrl = "https://repo.example/wenku8.js?v=1"
        val scriptBytes = "var source = {};".encodeToByteArray()
        val transport = FixtureTransport(
            mapOf(
                indexUrl to ShuYueRepositoryResponse(200, indexJson(), indexUrl),
                scriptUrl to ShuYueRepositoryResponse(200, scriptBytes, scriptUrl),
            ),
        )
        val loader = loader(transport)
        val index = loader.load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        val artifact = loader.downloadScript(index, index.entries.single())

        assertEquals(scriptBytes.toList(), artifact.copyBytes().toList())
        assertEquals("d29edda660fab38d92b2a517ac67484e5bf5756110431c9c2cfb8b7176c1dc3b", artifact.sha256)
        assertEquals(artifact.sha256, Sha256.hex(scriptBytes))
        assertEquals("zh.example", artifact.metadata.packageId)
        assertEquals(listOf("opaque-source"), artifact.metadata.sourceIds)
        assertEquals("wenku8.js?v=1", artifact.metadata.scriptUrl)
        assertEquals(scriptUrl, artifact.metadata.resolvedUrl)
        assertEquals(scriptUrl, artifact.metadata.downloadedFinalUrl)
        assertEquals(listOf(indexUrl, scriptUrl), transport.requests.map { it.url })
        assertEquals(ShuYueRepositoryLimits.DEFAULT_MAX_SCRIPT_BYTES, transport.requests[1].maxBytes)
    }

    @Test
    fun unsafeLocationsAndScriptReferencesFailClosed() = runTest {
        val unsafeLocations = listOf(
            ShuYueRepositoryLocation.IndexUrl("https://user:password@repo.example/index.json"),
            ShuYueRepositoryLocation.IndexUrl("https://repo.example/index.json#fragment"),
            ShuYueRepositoryLocation.BaseUrl("https://repo.example/base?query=not-a-directory"),
        )
        unsafeLocations.forEach { location ->
            expectFailure<ShuYueRepositoryException.InvalidUrl> {
                loader(FixtureTransport(emptyMap())).load(location)
            }
        }

        listOf(
            "https://evil.example/script.js",
            "//evil.example/script.js",
            "/script.js",
            "../script.js",
            "nested/%2e%2e/script.js",
            "nested%2fscript.js",
            "nested%00script.js",
            "nested/%252e%252e/script.js",
            "script.js#fragment",
        ).forEach { scriptUrl ->
            expectFailure<ShuYueRepositoryException.InvalidUrl>(scriptUrl) {
                loader(
                    FixtureTransport(
                        mapOf(
                            "https://repo.example/index.json" to ShuYueRepositoryResponse(
                                200,
                                indexJson(scriptUrl = scriptUrl),
                                "https://repo.example/index.json",
                            ),
                        ),
                    ),
                ).load(ShuYueRepositoryLocation.IndexUrl("https://repo.example/index.json"))
            }
        }
    }

    @Test
    fun duplicateExactPackageAndSourceIdsAreRejected() = runTest {
        val duplicatePackage = """
            [
              ${entryJson("same-package", "source-a")},
              ${entryJson("same-package", "source-b")}
            ]
        """.trimIndent().encodeToByteArray()
        expectFailure<ShuYueRepositoryException.DuplicateIdentity> {
            loader(
                FixtureTransport(mapOf("https://repo.example/index.json" to ok(duplicatePackage))),
            ).load(ShuYueRepositoryLocation.IndexUrl("https://repo.example/index.json"))
        }

        val duplicateSource = """
            [${entryJson("package", "same-source", secondSource = "same-source")}]
        """.trimIndent().encodeToByteArray()
        expectFailure<ShuYueRepositoryException.DuplicateIdentity> {
            loader(
                FixtureTransport(mapOf("https://repo.example/index.json" to ok(duplicateSource))),
            ).load(ShuYueRepositoryLocation.IndexUrl("https://repo.example/index.json"))
        }
    }

    @Test
    fun countAndByteBoundsAreEnforcedBeforeAcceptingTheIndex() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val transport = FixtureTransport(
            mapOf(indexUrl to ok("[${entryJson("package", "source")} ]".encodeToByteArray())),
        )
        expectFailure<ShuYueRepositoryException.BodyTooLarge> {
            ShuYueRepositoryIndexLoader(
                transport,
                ShuYueRepositoryLimits(maxIndexBytes = 8),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }

        val tooMany = listOf(
            entryJson("one", "source-one"),
            entryJson("two", "source-two"),
        ).joinToString(prefix = "[", postfix = "]")
        expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(tooMany.encodeToByteArray()))),
                ShuYueRepositoryLimits(maxPackages = 1),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }

        val tooManySources = "[${entryJson("package", "source-one", secondSource = "source-two")}]"
        expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(tooManySources.encodeToByteArray()))),
                ShuYueRepositoryLimits(maxSourcesPerPackage = 1),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }

        // The second source object is deliberately not materializable. Reaching the aggregate
        // count error proves the token scanner rejects it before kotlinx.serialization does.
        val aggregateTimingFixture = """
            [
              ${entryJson("one", "source-one")},
              {"id":"two","name":"Package","version":"1.0.0","versionCode":1,"lang":"zh","nsfw":0,"scriptUrl":"two.js","sources":[{}]}
            ]
        """.trimIndent().encodeToByteArray()
        val totalSourcesFailure = expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(aggregateTimingFixture))),
                ShuYueRepositoryLimits(maxTotalSources = 1),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        assertEquals("sources", totalSourcesFailure.resource)
        assertEquals(2L, totalSourcesFailure.actual)
        assertEquals(1L, totalSourcesFailure.max)
    }

    @Test
    fun scriptResponseByteBoundIsCheckedAndPassedToTheTransport() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val scriptUrl = "https://repo.example/wenku8.js?v=1"
        val transport = FixtureTransport(
            mapOf(
                indexUrl to ok(indexJson(), indexUrl),
                scriptUrl to ok(ByteArray(5), scriptUrl),
            ),
        )
        val boundedLoader = ShuYueRepositoryIndexLoader(
            transport,
            ShuYueRepositoryLimits(maxScriptBytes = 4),
        )
        val index = boundedLoader.load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        val failure = expectFailure<ShuYueRepositoryException.BodyTooLarge> {
            boundedLoader.downloadScript(index, index.entries.single())
        }

        assertEquals(scriptUrl, failure.url)
        assertEquals(5L, failure.actualBytes)
        assertEquals(4L, failure.maxBytes)
        assertEquals(4L, transport.requests.last().maxBytes)
    }

    @Test
    fun artifactOriginsAreAllowlistedButDeclaredRuntimeOriginsAreOnlyValidated() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val redirected = "https://evil.example/index.json"
        expectFailure<ShuYueRepositoryException.OriginNotAllowed> {
            loader(
                FixtureTransport(mapOf(indexUrl to ShuYueRepositoryResponse(200, indexJson(), redirected, redirectChain = listOf(redirected)))),
                allowedOrigins = setOf("https://repo.example"),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }

        val sourceOrigin = "https://source.example"
        val sourceIndex = indexJson(sourceBaseUrl = sourceOrigin)
        val loaded = loader(
            FixtureTransport(mapOf(indexUrl to ok(sourceIndex, indexUrl))),
            allowedOrigins = setOf("https://repo.example"),
        ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        assertEquals(sourceOrigin, loaded.entries.single().sources.single().runtimeOrigin.value)
    }

    @Test
    fun localArtifactPolicyAllowsExplicitLoopbackAndPrivateOriginsOnly() = runTest {
        val localIndex = "http://192.168.50.134:18080/index.json"
        val transport = FixtureTransport(mapOf(localIndex to ok(indexJson(), localIndex)))
        val loaded = ShuYueRepositoryIndexLoader(
            transport,
            ShuYueRepositoryLimits(
                allowedArtifactOrigins = setOf("https://raw.githubusercontent.com"),
                allowLocalArtifactOrigins = true,
            ),
        ).load(ShuYueRepositoryLocation.IndexUrl(localIndex))

        assertEquals("zh.example", loaded.entries.single().id)
        assertEquals(
            setOf(
                ShuYueOrigin.parse("https://raw.githubusercontent.com"),
                ShuYueOrigin.parse("http://192.168.50.134:18080"),
            ),
            transport.requests.single().allowedArtifactOrigins,
        )

        val publicIndex = "http://public.example/index.json"
        expectFailure<ShuYueRepositoryException.OriginNotAllowed> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(publicIndex to ok(indexJson(), publicIndex))),
                ShuYueRepositoryLimits(
                    allowedArtifactOrigins = setOf("https://raw.githubusercontent.com"),
                    allowLocalArtifactOrigins = true,
                ),
            ).load(ShuYueRepositoryLocation.IndexUrl(publicIndex))
        }
    }

    @Test
    fun artifactAuthorityParserRejectsAmbiguousAndNonCanonicalAuthorities() = runTest {
        val invalid = listOf(
            "https://repo.example:0",
            "https://repo.example:",
            "https://repo.example:+443",
            "https://repo.example:443evil",
            "https://repo.example:65536",
            "https://%65xample.example",
            "https://user:password@repo.example",
            "https://[::1]evil",
            "https://[::1",
            "https://[:::1]",
            "https://[2001:db8::1]:0",
            "https://repo.example/path",
            "https://repo.example/",
        )
        invalid.forEach { value ->
            expectFailure<ShuYueRepositoryException.InvalidUrl>(value) {
                ShuYueOrigin.parse(value)
            }
        }
        assertEquals("https://repo.example", ShuYueOrigin.parse("HTTPS://REPO.EXAMPLE:443").value)
        assertEquals("[::1]", ShuYueAuthority.parse("[::1]").value)
    }

    @Test
    fun sourceAuthorityIsValidatedWithoutBecomingAnArtifactGrant() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val maliciousSources = listOf(
            "https://[::1]evil/",
            "https://repo.example:0/",
            "https://repo.example:%34/",
            "https://%65vil.example/",
        )
        maliciousSources.forEach { sourceBaseUrl ->
            expectFailure<ShuYueRepositoryException.InvalidUrl>(sourceBaseUrl) {
                loader(FixtureTransport(mapOf(indexUrl to ok(indexJson(sourceBaseUrl = sourceBaseUrl), indexUrl))))
                    .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
            }
        }
    }

    @Test
    fun redirectTraceIsRequiredAndCheckedBeforeAcceptingArtifact() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val finalUrl = "https://repo.example/releases/index.json"
        expectFailure<ShuYueRepositoryException.InvalidMetadata> {
            loader(FixtureTransport(mapOf(indexUrl to ShuYueRepositoryResponse(200, indexJson(), finalUrl))))
                .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        expectFailure<ShuYueRepositoryException.OriginNotAllowed> {
            loader(FixtureTransport(mapOf(indexUrl to ShuYueRepositoryResponse(
                200,
                indexJson(),
                indexUrl,
                redirectChain = listOf("https://evil.example/hop"),
            ))), allowedOrigins = setOf("https://repo.example"))
                .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(FixtureTransport(mapOf(indexUrl to ShuYueRepositoryResponse(
                200,
                indexJson(),
                indexUrl,
                redirectChain = listOf(
                    "https://repo.example/hop-1",
                    "https://repo.example/hop-2",
                ),
            ))), ShuYueRepositoryLimits(maxRedirects = 1))
                .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        expectFailure<ShuYueRepositoryException.InvalidMetadata> {
            loader(FixtureTransport(mapOf(indexUrl to ShuYueRepositoryResponse(
                200,
                indexJson(),
                finalUrl,
                redirectChain = listOf("https://repo.example/not-the-final-target"),
            )))).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        expectFailure<ShuYueRepositoryException.InvalidMetadata> {
            loader(FixtureTransport(mapOf(indexUrl to ShuYueRepositoryResponse(
                200,
                indexJson(),
                finalUrl,
                redirectChain = listOf(
                    "https://repo.example/hop-1",
                    "https://repo.example/hop-2",
                    "https://repo.example/hop-1",
                    finalUrl,
                ),
            )))).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
    }

    @Test
    fun preflightRejectsDuplicateKeysInvalidUtf8OversizeStringsAndDeepNesting() = runTest {
        val indexUrl = "https://repo.example/index.json"
        // This is otherwise a valid entry. Without the preflight duplicate-key check, the JSON
        // decoder's last value would win and the loader would accept the document.
        val duplicate = "[${entryJson("one", "source").replaceFirst("\"id\":\"one\"", "\"id\":\"one\",\"id\":\"two\"")}]"
            .encodeToByteArray()
        val duplicateFailure = expectFailure<ShuYueRepositoryException.DuplicateJsonKey> {
            loader(FixtureTransport(mapOf(indexUrl to ok(duplicate, indexUrl))))
                .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        assertEquals("id", duplicateFailure.key)
        val invalidUtf8 = byteArrayOf('['.code.toByte(), '{'.code.toByte(), '}'.code.toByte(), ']'.code.toByte(), 0xC3.toByte(), 0x28)
        expectFailure<ShuYueRepositoryException.InvalidDocument> {
            loader(FixtureTransport(mapOf(indexUrl to ok(invalidUtf8, indexUrl))))
                .load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        val oversizeName = indexJson().decodeToString().replace("\"Package\"", "\"123456789\"").encodeToByteArray()
        val fieldFailure = expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(oversizeName, indexUrl))),
                ShuYueRepositoryLimits(maxPackageNameBytes = 4),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        assertEquals("string:name", fieldFailure.resource)
        assertEquals(5L, fieldFailure.actual)
        assertEquals(4L, fieldFailure.max)
        val deeplyNested = "[{\"id\":\"p\",\"name\":\"n\",\"version\":\"1\",\"versionCode\":1,\"lang\":\"zh\",\"scriptUrl\":\"a.js\",\"sources\":[{\"id\":\"s\",\"name\":\"n\",\"lang\":\"zh\",\"baseUrl\":\"https://source.example\",\"x\":${"[".repeat(8)}null${"]".repeat(8)}}]}]".encodeToByteArray()
        expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(deeplyNested, indexUrl))),
                ShuYueRepositoryLimits(maxJsonNesting = 6),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
    }

    @Test
    fun preflightCapsMembersAndElementsInsideUnknownJsonSubtrees() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val valid = indexJson().decodeToString()
        val objectFixture = valid.replace(
            "\"sources\":",
            "\"unknownObject\":{\"a\":0,\"b\":0,\"c\":0,\"d\":0,\"e\":0,\"f\":0,\"g\":0,\"h\":0,\"i\":0,\"j\":0},\"sources\":",
        ).encodeToByteArray()
        val objectFailure = expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(objectFixture, indexUrl))),
                ShuYueRepositoryLimits(maxJsonObjectMembers = 9),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        assertEquals("JSON object members", objectFailure.resource)
        assertEquals(10L, objectFailure.actual)
        assertEquals(9L, objectFailure.max)

        val arrayFixture = valid.replace(
            "\"sources\":",
            "\"unknownArray\":[0,1,2],\"sources\":",
        ).encodeToByteArray()
        val arrayFailure = expectFailure<ShuYueRepositoryException.LimitExceeded> {
            ShuYueRepositoryIndexLoader(
                FixtureTransport(mapOf(indexUrl to ok(arrayFixture, indexUrl))),
                ShuYueRepositoryLimits(maxJsonArrayElements = 2),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        assertEquals("JSON array elements", arrayFailure.resource)
        assertEquals(3L, arrayFailure.actual)
        assertEquals(2L, arrayFailure.max)
    }

    @Test
    fun downloadedBytesAreDefensiveCopiesAndDigestRemainsBound() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val scriptUrl = "https://repo.example/wenku8.js?v=1"
        val original = "immutable script".encodeToByteArray()
        val expected = original.copyOf()
        val transport = FixtureTransport(
            mapOf(
                indexUrl to ok(indexJson(), indexUrl),
                scriptUrl to ok(original, scriptUrl),
            ),
        )
        val boundedLoader = loader(transport)
        val index = boundedLoader.load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        val artifact = boundedLoader.downloadScript(index, index.entries.single())
        original[0] = 'Z'.code.toByte()
        // The public APIs are copies; mutation cannot alter either backing bytes or digest.
        val propertyCopy = artifact.bytes
        propertyCopy[0] = 'X'.code.toByte()
        val methodCopy = artifact.copyBytes()
        methodCopy[1] = 'Y'.code.toByte()
        assertEquals(expected.toList(), artifact.bytes.toList())
        assertEquals(Sha256.hex(expected), artifact.sha256)
    }

    @Test
    fun loadedCapabilitiesRejectHostileCastsForgedEntriesAndCrossLoaderUse() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val body = listOf(
            entryJson("package-one", "source-one"),
            entryJson("package-two", "source-two"),
        ).joinToString(prefix = "[", postfix = "]").encodeToByteArray()
        val transport = FixtureTransport(mapOf(indexUrl to ok(body, indexUrl)))
        val boundedLoader = loader(
            transport,
            allowedOrigins = setOf("https://repo.example", "https://cdn.example"),
        )
        val loaded = boundedLoader.load(ShuYueRepositoryLocation.IndexUrl(indexUrl))

        assertFalse(loaded.entries is MutableList<*>)
        val forged = loaded.entries.first().copy(
            id = "forged-package",
            resolvedScriptUrl = "https://repo.example/forged.js",
        )
        val inserted = try {
            @Suppress("UNCHECKED_CAST")
            (loaded.entries as MutableList<ShuYueRepositoryEntry>).add(forged)
        } catch (_: ClassCastException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
        assertFalse(inserted)
        expectFailure<ShuYueRepositoryException.InvalidMetadata> {
            boundedLoader.downloadScript(loaded, forged)
        }
        expectFailure<ShuYueRepositoryException.InvalidMetadata> {
            loader(FixtureTransport(emptyMap())).downloadScript(loaded, loaded.entries.first())
        }
    }

    @Test
    fun transportCannotMutateRequestOriginSnapshotOrExpandPrivatePolicy() = runTest {
        val indexUrl = "https://repo.example/index.json"
        val evilUrl = "https://evil.example/index.json"
        val evilOrigin = ShuYueOrigin.parse("https://evil.example")
        var exposedAsMutable = false
        var mutationSucceeded = false
        val transport = ShuYueRepositoryTransport { request ->
            exposedAsMutable = request.allowedArtifactOrigins is MutableSet<*>
            mutationSucceeded = try {
                @Suppress("UNCHECKED_CAST")
                (request.allowedArtifactOrigins as MutableSet<ShuYueOrigin>).add(evilOrigin)
            } catch (_: ClassCastException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
            ShuYueRepositoryResponse(200, indexJson(), evilUrl, redirectChain = listOf(evilUrl))
        }

        expectFailure<ShuYueRepositoryException.OriginNotAllowed> {
            loader(
                transport,
                allowedOrigins = setOf("https://repo.example", "https://cdn.example"),
            ).load(ShuYueRepositoryLocation.IndexUrl(indexUrl))
        }
        assertFalse(exposedAsMutable)
        assertFalse(mutationSucceeded)
    }

    private fun loader(
        transport: ShuYueRepositoryTransport,
        allowedOrigins: Set<String> = emptySet(),
    ): ShuYueRepositoryIndexLoader = ShuYueRepositoryIndexLoader(
        transport = transport,
        limits = ShuYueRepositoryLimits(allowedOrigins = allowedOrigins),
    )

    private fun indexJson(
        scriptUrl: String = "wenku8.js?v=1",
        sourceBaseUrl: String = "https://source.example",
    ): ByteArray = """
        [${entryJson("zh.example", "opaque-source", scriptUrl, sourceBaseUrl)}]
    """.trimIndent().encodeToByteArray()

    private fun v2FixtureTransport(
        sidecar: String,
        requests: MutableList<String>,
    ): ShuYueRepositoryTransport = object : ShuYueRepositoryTransport {
        override suspend fun execute(request: ShuYueRepositoryRequest): ShuYueRepositoryResponse {
            requests += request.url
            return when (request.url) {
                V2_INDEX_URL -> ok(v2IndexJson(), V2_INDEX_URL)
                V2_SIDECAR_URL -> ok(sidecar.encodeToByteArray(), V2_SIDECAR_URL)
                V2_SCRIPT_URL -> ok(V2_SCRIPT, V2_SCRIPT_URL)
                else -> error("Unexpected V2 fixture request ${request.url}")
            }
        }
    }

    private fun v2IndexJson(): ByteArray = """
        {
          "format":"shinsou-extension-v2",
          "contractVersion":2,
          "packages":[
            {"contract":"shuyue","id":"novel.v2","name":"Novel","version":"2.0.0",
             "versionCode":2,"lang":"zh","nsfw":false,"scriptUrl":"plugins/novel.js",
             "sources":[{"sourceId":"opaque-source","name":"Novel","lang":"zh",
               "baseUrl":"https://novel.example"}],
             "contentType":"novel","sha256":"$V2_DIGEST","byteSize":${V2_SCRIPT.size},
             "sidecarUrl":"sidecars/novel.json","capabilities":["BROWSE","LATEST","LOGIN","FAVORITE"],
             "systemEvents":$V2_EVENTS_JSON,
             "requestedHostPermissions":["REQUEST_LOGIN_UI"]}
          ]
        }
    """.trimIndent().encodeToByteArray()

    private fun v2SidecarJson(
        packageId: String = "novel.v2",
        version: String = "2.0.0",
        versionCode: Int = 2,
        digest: String = V2_DIGEST,
        byteSize: Int = V2_SCRIPT.size,
        sourcePackageId: String = "novel.v2",
        sourceId: String = "opaque-source",
        events: String = V2_EVENTS_JSON,
        permissions: List<String> = listOf("REQUEST_LOGIN_UI"),
    ): String = """
        {
          "format":"shinsou-extension-sidecar-v2","contractVersion":2,
          "packageId":"$packageId","version":"$version","versionCode":$versionCode,
          "contract":"shuyue",
          "artifact":{"scriptUrl":"plugins/novel.js","sha256":"$digest","byteSize":$byteSize},
          "content":{"contractVersion":2,"type":"novel"},
          "capabilities":["BROWSE","LATEST","LOGIN","FAVORITE"],
          "systemEvents":$events,
          "requestedHostPermissions":${permissions.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},
          "sources":[{"sourceId":"$sourceId","sourceKey":{"contractVersion":2,
            "packageId":"$sourcePackageId","sourceId":"$sourceId"},
            "name":"Novel","lang":"zh","baseUrl":"https://novel.example"}]
        }
    """.trimIndent()

    private fun entryJson(
        packageId: String,
        sourceId: String,
        scriptUrl: String = "wenku8.js?v=1",
        sourceBaseUrl: String = "https://source.example",
        secondSource: String? = null,
    ): String {
        val second = secondSource?.let {
            ", {\"id\":\"$it\",\"name\":\"Source 2\",\"lang\":\"zh\",\"baseUrl\":\"$sourceBaseUrl\"}"
        }.orEmpty()
        return """
            {"id":"$packageId","name":"Package","version":"1.0.0","versionCode":1,"lang":"zh","nsfw":0,"scriptUrl":"$scriptUrl","sources":[{"id":"$sourceId","name":"Source","lang":"zh","baseUrl":"$sourceBaseUrl"}$second]}
        """.trimIndent()
    }

    private fun ok(body: ByteArray, finalUrl: String = "https://repo.example/index.json"): ShuYueRepositoryResponse =
        ShuYueRepositoryResponse(200, body, finalUrl)

    private suspend inline fun <reified T : Throwable> expectFailure(
        message: String? = null,
        crossinline block: suspend () -> Unit,
    ): T {
        val thrown = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }
        return assertIs<T>(thrown, message ?: "Expected the bounded loader to reject the fixture; got $thrown")
    }

    private class FixtureTransport(
        private val responses: Map<String, ShuYueRepositoryResponse>,
    ) : ShuYueRepositoryTransport {
        val requests = mutableListOf<ShuYueRepositoryRequest>()

        override suspend fun execute(request: ShuYueRepositoryRequest): ShuYueRepositoryResponse {
            requests += request
            return requireNotNull(responses[request.url]) { "Unexpected fixture request: ${request.url}" }
        }
    }

    private companion object {
        const val V2_INDEX_URL: String = "https://repo.example/v2/index.json"
        const val V2_SIDECAR_URL: String = "https://repo.example/v2/sidecars/novel.json"
        const val V2_SCRIPT_URL: String = "https://repo.example/v2/plugins/novel.js"
        val V2_SCRIPT: ByteArray = "reviewed v2 script".encodeToByteArray()
        val V2_DIGEST: String = Sha256.hex(V2_SCRIPT)
        const val V2_EVENTS_JSON: String =
            "{\"protocol\":\"dev.shinsou.system\",\"minVersion\":1,\"maxVersion\":1," +
                "\"required\":[\"command.auth.login.request\"]," +
                "\"optional\":[\"event.diagnostic.message.report\"]}"
    }
}
