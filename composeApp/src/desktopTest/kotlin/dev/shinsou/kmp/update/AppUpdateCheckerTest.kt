package dev.shinsou.kmp.update

import dev.shinsou.kmp.domain.model.UpdateSettings
import dev.shinsou.kmp.ui.AppUpdateState
import dev.shinsou.kmp.ui.UpdateCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateCheckerTest {
    private fun release(tag: String, prerelease: Boolean, draft: Boolean = false) =
        """{"tag_name":"$tag","name":"Shinsou X $tag","html_url":"https://github.com/aluo96078/shinsoux/releases/tag/$tag","body":"notes of $tag","draft":$draft,"prerelease":$prerelease,"published_at":"2026-11-15T00:00:00Z","assets":[]}"""

    private val listing = listOf(
        release("manga-models-v1", prerelease = false),
        release("v1.0.3-beta.1", prerelease = true, draft = true),
        release("v1.0.2-beta.2", prerelease = true),
        release("v1.0.2-beta.1", prerelease = true),
        release("v1.0.1", prerelease = false),
        // a beta tag published as a release is not trusted as either
        release("v1.0.2-beta.3", prerelease = false),
    ).joinToString(",", "[", "]")

    private var requests = mutableListOf<String>()
    private fun http(status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(MockEngine { request ->
        requests += request.url.toString()
        respond(listing, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test
    fun onlyTheAppsPublishedReleasesAreConsidered() = runBlocking {
        val checker = AppUpdateChecker(http(), "1.0.0")
        assertEquals(listOf("v1.0.2-beta.2", "v1.0.2-beta.1", "v1.0.1"), checker.releases().map { it.tag })
        assertTrue(requests.single().startsWith("https://api.github.com/repos/aluo96078/shinsoux/releases"))
        assertEquals("v1.0.1", checker.check(UpdateChannel.STABLE)?.tag)
        assertEquals("v1.0.2-beta.2", checker.check(UpdateChannel.BETA)?.tag)
        assertNull(AppUpdateChecker(http(), "1.0.2-beta.2").check(UpdateChannel.BETA))
        assertNull(AppUpdateChecker(http(), "not-a-version").check(UpdateChannel.BETA))
    }

    @Test
    fun aSkippedVersionIsOnlyShownWhenTheUserAsks() = runBlocking {
        val state = AppUpdateState(AppUpdateChecker(http(), "1.0.1"))
        val settings = UpdateSettings(checkForUpdates = true, includeBeta = true, skippedVersion = "1.0.2-beta.2")
        assertTrue(state.check(settings) is UpdateCheckResult.Newer)
        assertNull(state.prompt, "the skipped version is not offered again by itself")
        state.check(settings, interactive = true)
        assertEquals("v1.0.2-beta.2", state.prompt?.tag)

        val failing = AppUpdateState(AppUpdateChecker(http(HttpStatusCode.Forbidden), "1.0.1"))
        assertEquals(UpdateCheckResult.Failed, failing.check(settings))
        assertNull(failing.prompt)
    }

    /** Against GitHub itself: SHINSOU_UPDATE_LIVE=1. */
    @Test
    fun theLiveReleaseListReads() = runBlocking {
        if (System.getenv("SHINSOU_UPDATE_LIVE") != "1") return@runBlocking
        val checker = AppUpdateChecker(HttpClient(io.ktor.client.engine.okhttp.OkHttp), "1.0.0")
        val releases = checker.releases()
        println("UPDATE live: ${releases.size} app releases, newest ${releases.maxByOrNull { it.version }?.tag}, stable ${checker.check(UpdateChannel.STABLE)?.tag}, beta ${checker.check(UpdateChannel.BETA)?.tag}")
        assertTrue(releases.none { it.tag.startsWith("manga") })
        assertTrue(releases.isNotEmpty())
    }
}
