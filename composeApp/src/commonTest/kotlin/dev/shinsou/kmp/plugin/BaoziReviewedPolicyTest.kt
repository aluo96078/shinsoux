package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BaoziReviewedPolicyTest {
    @Test
    fun exactReaderRedirectStripsSyntheticSourceCredentials() = runTest {
        val policy = assertNotNull(match(entry())).networkPolicy
        val requests = mutableListOf<PluginHttpRequest>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(1, PluginCookie("sid", "synthetic", "www.baozimh.com"))
        val network = PluginNetworkClient(
            object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("Pin required")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                    requests += request
                    return if (requests.size == 1) PluginHttpResponse(
                        302, ByteArray(0), mapOf("Location" to listOf("https://www.twmanga.com/comic/chapter/test/0_1.html")),
                    ) else PluginHttpResponse(200, "reader".encodeToByteArray())
                }
            }, storage, policy = policy,
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        )
        network.get(1, "https://www.baozimh.com/user/page_direct?comic_id=test", emptyMap(),
            sourceHeaders = mapOf("Authorization" to "Bearer synthetic"))
        assertEquals(2, requests.size)
        assertEquals("sid=synthetic", requests.first().headers["Cookie"])
        assertEquals("Bearer synthetic", requests.first().headers["Authorization"])
        assertFalse(requests.last().headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) })
    }

    @Test
    fun publicReaderRedirectIsPermittedWithoutCredentialsOrNewBrowserAuthority() = runTest {
        val review = assertNotNull(match(entry()))
        val policy = review.networkPolicy
        assertEquals(
            setOf("https://www.baozimh.com", "https://app.baozimh.com", "https://appgb.baozimh.com"),
            policy.credentialOrigins,
        )
        assertEquals(policy.credentialOrigins + "https://www.twmanga.com", policy.requestOrigins)
        assertFalse(policy.maySendCredentials(Url("https://www.twmanga.com/comic/chapter/test/0_1.html")))
        assertEquals(emptySet(), review.webChallengeOrigins)
        assertEquals(emptySet(), policy.browserSessionOrigins)
        val resolver = PluginHostResolver { listOf("93.184.216.34") }
        policy.validate(Url("https://www.twmanga.com/comic/chapter/test/0_1.html"), resolver)
        for (url in listOf(
            "https://twmanga.com/", "https://www.twmanga.com.evil.example/",
            "http://www.twmanga.com/", "https://s1.baozicdn.com/scomic/a.jpg",
        )) {
            assertFailsWith<IllegalArgumentException> { policy.validate(Url(url), resolver) }
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(Url("https://www.twmanga.com/"), PluginHostResolver { listOf("127.0.0.1") })
        }
    }

    @Test
    fun exactLocalRepairCannotBeSubstitutedOrExpanded() {
        val valid = entry()
        assertNotNull(match(valid))
        listOf(
            valid.copy(version = "1.0.6", versionCode = 7),
            valid.copy(sha256 = "0".repeat(64)),
            valid.copy(byteSize = valid.byteSize!! + 1),
            valid.copy(runtimePermissions = valid.runtimePermissions!! + PluginRuntimePermission.BROWSER_CHALLENGE),
            valid.copy(sources = valid.sources!!.map { it.copy(baseUrl = "https://www.twmanga.com") }),
        ).forEach { assertNull(match(it)) }
    }

    private fun match(entry: PluginIndexEntry) = OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
        entry, ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081, REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL,
    )

    private fun entry() = PluginIndexEntry(
        id = "zh.baozimh", name = "包子漫画", version = "1.0.7", versionCode = 8, lang = "zh",
        scriptUrl = "plugins/zh.baozimh.js", sidecarUrl = "sidecars/zh.baozimh.json",
        sha256 = "79010b5571ca4079be744df68bb984df339b691c92a6bee0c61b3a2ba6372290", byteSize = 23_300,
        contentType = "manga", contract = "shinsou", runtime = "legacy-shinsou-adapter-v2",
        contentKinds = setOf("IMAGE_SEQUENCE"),
        capabilities = setOf("CATALOGUE", "LATEST", "BROWSE", "METADATA", "UNITS", "CONTENT", "SEARCH"),
        systemEvents = PluginSystemEventDeclaration(1, 1),
        runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
        sources = listOf(SourceIndexEntry(
            "包子漫画", "zh", 4_502_917L, "https://www.baozimh.com",
            contentKindsDeclared = false, originPolicyVersion = 2,
            legacyLongId = "4502917", canonicalSourceId = "4502917",
        )),
    )
}
