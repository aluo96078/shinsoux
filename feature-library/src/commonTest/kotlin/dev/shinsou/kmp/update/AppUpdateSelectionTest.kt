package dev.shinsou.kmp.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateSelectionTest {
    private fun v(text: String) = AppVersion.parse(text)!!
    private fun release(tag: String) = AppRelease(v(tag), tag, tag, "https://example.test/$tag", "", null)

    @Test
    fun versionsParseFromTagsAndNamesAndOrderWithReleasesAfterTheirBetas() {
        assertEquals(AppVersion(1, 0, 2, 3), v("v1.0.2-beta.3"))
        assertEquals(AppVersion(1, 0, 2), v("1.0.2"))
        assertNull(AppVersion.parse("manga-models-v1"))
        assertNull(AppVersion.parse("v1.0"))
        assertNull(AppVersion.parse("v1.0.2-rc.1"))
        assertTrue(v("1.0.2") > v("1.0.2-beta.25"))
        assertTrue(v("1.0.2-beta.10") > v("1.0.2-beta.9"))
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("1.1.0-beta.1") > v("1.0.9"))
        assertEquals("1.0.2-beta.3", v("v1.0.2-beta.3").toString())
    }

    @Test
    fun releasesOnlyOfferTheNewestReleaseAndBetasTheNewestOfAll() {
        val releases = listOf("v1.0.1", "v1.0.2-beta.1", "v1.0.2-beta.2", "v1.0.0").map(::release)
        // releases only: the newest release, when newer
        assertEquals("v1.0.1", selectUpdate(v("1.0.0"), releases, UpdateChannel.STABLE)?.tag)
        assertNull(selectUpdate(v("1.0.1"), releases, UpdateChannel.STABLE))
        // with betas: the newest beta…
        assertEquals("v1.0.2-beta.2", selectUpdate(v("1.0.1"), releases, UpdateChannel.BETA)?.tag)
        assertNull(selectUpdate(v("1.0.2-beta.2"), releases, UpdateChannel.BETA))
        // …unless a release came last
        val released = releases + release("v1.0.2")
        assertEquals("v1.0.2", selectUpdate(v("1.0.2-beta.2"), released, UpdateChannel.BETA)?.tag)
        assertEquals("v1.0.2", selectUpdate(v("1.0.1"), released, UpdateChannel.BETA)?.tag)
        // a beta user on releases only waits for the release after the beta
        assertNull(selectUpdate(v("1.0.2-beta.1"), releases, UpdateChannel.STABLE))
        assertEquals("v1.0.2", selectUpdate(v("1.0.2-beta.1"), released, UpdateChannel.STABLE)?.tag)
        assertNull(selectUpdate(v("1.0.0"), emptyList(), UpdateChannel.BETA))
    }
}
