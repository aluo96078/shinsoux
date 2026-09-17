package dev.shinsou.kmp.ui.challenge

import java.io.File
import kotlin.test.Test
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
        val build = File(root, "composeApp/build.gradle.kts").readText()

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

    private companion object {
        const val ANDROID_SOURCE =
            "composeApp/src/androidMain/kotlin/dev/shinsou/kmp/ui/challenge/" +
                "PlatformWebChallengeView.android.kt"
    }
}
