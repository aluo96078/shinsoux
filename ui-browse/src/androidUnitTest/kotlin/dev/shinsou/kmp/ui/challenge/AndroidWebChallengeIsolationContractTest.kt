package dev.shinsou.kmp.ui.challenge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidWebChallengeIsolationContractTest {
    @Test
    fun embeddedModeRequiresBothWebViewIsolationFeatures() {
        assertFalse(androidWebChallengeSupportsIsolation(false, false))
        assertFalse(androidWebChallengeSupportsIsolation(true, false))
        assertFalse(androidWebChallengeSupportsIsolation(false, true))
        assertTrue(androidWebChallengeSupportsIsolation(true, true))
    }

    @Test
    fun androidHostUsesDedicatedProfileAndCompleteDeletionOnly() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, ANDROID_SOURCE).isFile }
        val source = File(root, ANDROID_SOURCE).readText()
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        val build = File(root, "ui/build.gradle.kts").readText()

        assertFalse(source.contains("CookieManager." + "getInstance"))
        assertFalse(source.contains("removeAll" + "Cookies"))
        assertTrue(source.contains("WebViewFeature.MULTI_PROFILE"))
        assertTrue(source.contains("WebViewFeature.DELETE_BROWSING_DATA"))
        assertTrue(source.contains("dedicatedProfile.serviceWorkerController"))
        assertTrue(source.contains("blockNetworkLoads = true"))
        assertTrue(source.contains("platformWebChallengeMode != PlatformWebChallengeMode.Embedded"))
        assertTrue(source.contains("ProfileStore.getInstance()"))
        assertTrue(source.contains("WebViewCompat.setProfile"))
        assertTrue(source.contains("WebStorageCompat.deleteBrowsingData"))
        assertTrue(source.contains("dedicatedProfile.cookieManager"))
        assertTrue(
            source.indexOf("AndroidChallengeProfileLease.acquire()") <
                source.indexOf("bindDedicatedProfile(webViewClient)"),
        )
        assertTrue(source.indexOf("WebViewCompat.setProfile") < source.indexOf("createdWebView.settings"))
        assertTrue(source.indexOf("WebViewCompat.setProfile") < source.indexOf("activeWebView.loadUrl"))
        val closing = source.substringAfter("private suspend fun finishAndReleaseLease()")
        assertTrue(closing.indexOf("activeWebView.destroy()") < closing.indexOf("clearProfileData()"))
        assertTrue(closing.indexOf("clearProfileData()") < closing.indexOf("AndroidChallengeProfileLease.release()"))
        assertTrue(catalog.contains("webkit = \"1.14.0\""))
        assertTrue(catalog.contains("androidx.webkit:webkit"))
        assertTrue(build.contains("implementation(libs.androidx.webkit)"))
    }

    @Test
    fun androidWebChallengeStripsWebViewIdentityMarkersAndGatesAutomaticLogin() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, ANDROID_SOURCE).isFile }
        val androidSource = File(root, ANDROID_SOURCE).readText()
        val iosSource = File(root, IOS_SOURCE).readText()

        assertEquals("Google Chrome", sanitizeAndroidWebChallengeBrandName("Android WebView"))
        assertEquals("Chromium", sanitizeAndroidWebChallengeBrandName("Chromium"))
        assertEquals("Not?A_Brand", sanitizeAndroidWebChallengeBrandName("Not?A_Brand"))

        assertFalse(androidSource.contains("userAgentString = request.userAgent"))
        assertTrue(androidSource.contains("configureAndroidWebChallengeBrowserIdentity"))
        assertTrue(androidSource.contains("WebViewFeature.USER_AGENT_METADATA"))
        assertTrue(androidSource.contains("WebSettingsCompat.setUserAgentMetadata"))
        assertTrue(androidSource.contains("if (request.allowsWebChallengeCapture(url))"))

        assertFalse(iosSource.contains("customUserAgent = request.userAgent"))
        assertTrue(iosSource.contains("customUserAgent = null"))
        assertTrue(iosSource.contains("request.hasThirdPartyLoginOrigins()"))
        assertTrue(iosSource.contains("sanitizeIosWebChallengeUserAgent"))
        assertTrue(iosSource.contains("isIgnoredAppleWebChallengeNavigationError"))
        assertTrue(iosSource.contains("request.allowsWebChallengeCapture(loadedWebView.URL?.absoluteString)"))
    }

    private companion object {
        const val ANDROID_SOURCE =
            "ui-browse/src/androidMain/kotlin/dev/shinsou/kmp/ui/challenge/" +
                "PlatformWebChallengeView.android.kt"
        const val IOS_SOURCE =
            "ui-browse/src/iosMain/kotlin/dev/shinsou/kmp/ui/challenge/" +
                "PlatformWebChallengeView.ios.kt"
    }
}
