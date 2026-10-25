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

/** Portable admission tests, independent of the sibling plugin checkout. */
class MyComicReviewedPolicyTest {
    @Test
    fun challengeResourcesDoNotBecomeScriptOrCredentialOrigins() = runTest {
        val review = assertNotNull(match(entry()))
        assertEquals(setOf("https://mycomic.com"), review.webChallengeOrigins)
        assertEquals(
            setOf("https://mycomic.com", "https://challenges.cloudflare.com"),
            review.webChallengeSubresourceOrigins,
        )
        assertEquals("cf_clearance", review.requiredWebChallengeCookieName)
        assertEquals(setOf("https://mycomic.com"), review.networkPolicy.requestOrigins)
        assertEquals(setOf("https://mycomic.com"), review.networkPolicy.credentialOrigins)
        assertEquals(emptySet(), review.networkPolicy.browserSessionOrigins)
        assertEquals(setOf("https://mycomic.com", "https://biccam.com"), review.networkPolicy.contentOrigins)
        assertFalse(review.networkPolicy.maySendCredentials(Url("https://biccam.com/image.jpg")))
        val resolver = PluginHostResolver { listOf("93.184.216.34") }
        review.networkPolicy.validate(Url("https://mycomic.com/comics"), resolver)
        for (url in listOf(
            "https://challenges.cloudflare.com/", "https://www.mycomic.com/", "https://biccam.com/image.jpg",
            "https://mycomic.com.evil.example/", "http://mycomic.com/", "https://127.0.0.1/",
        )) {
            assertFailsWith<IllegalArgumentException> {
                review.networkPolicy.validate(Url(url), resolver)
            }
        }
        assertFalse(review.networkPolicy.maySendCredentials(Url("https://challenges.cloudflare.com/")))
        assertFailsWith<IllegalArgumentException> {
            review.networkPolicy.validate(
                Url("https://mycomic.com/comics"), PluginHostResolver { listOf("127.0.0.1") },
            )
        }
    }

    @Test
    fun exactVersionDigestAndPermissionsRemainRequired() {
        val valid = entry()
        assertNotNull(match(valid))
        listOf(
            valid.copy(version = "1.0.0", versionCode = 1),
            valid.copy(version = "1.0.1", versionCode = 2),
            valid.copy(sha256 = "0".repeat(64)),
            valid.copy(byteSize = valid.byteSize!! + 1),
            valid.copy(runtimePermissions = valid.runtimePermissions!! + PluginRuntimePermission.CREDENTIAL_ACCESS),
            valid.copy(runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK)),
            valid.copy(sources = valid.sources!!.map {
                it.copy(browserSessionOrigins = setOf("https://mycomic.com"))
            }),
        ).forEach { assertNull(match(it)) }
        assertNull(OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
            valid, ReviewedLocalRepositoryPolicy.DISABLED, REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL,
        ))
    }

    private fun match(entry: PluginIndexEntry) = OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
        entry, ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081, REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL,
    )

    private fun entry() = PluginIndexEntry(
        id = "zh.mycomic", name = "MyComic", version = "1.0.2", versionCode = 3, lang = "zh", nsfw = 1,
        scriptUrl = "plugins/zh.mycomic.js", sidecarUrl = "sidecars/zh.mycomic.json",
        sha256 = "d79fbcd04e3398f57d73af021691fa368ae3d7702dc3ef62d00cc67144fd7eba", byteSize = 23_990,
        contentType = "manga", contract = "shinsou", runtime = "legacy-shinsou-adapter-v2",
        contentKinds = setOf("IMAGE_SEQUENCE"),
        capabilities = setOf("CATALOGUE", "LATEST", "BROWSE", "METADATA", "UNITS", "CONTENT", "SEARCH"),
        systemEvents = PluginSystemEventDeclaration(1, 1),
        runtimePermissions = setOf(
            PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK,
            PluginRuntimePermission.COOKIE_STORAGE, PluginRuntimePermission.BROWSER_CHALLENGE,
        ),
        sources = listOf(SourceIndexEntry(
            "MyComic", "zh", 9_119_537_447_562_549_661L, "https://mycomic.com",
            contentKindsDeclared = false, originPolicyVersion = 2,
            legacyLongId = "9119537447562549661", canonicalSourceId = "9119537447562549661",
        )),
    )
}
