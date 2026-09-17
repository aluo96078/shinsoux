package dev.shinsou.kmp.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** The desktop local transport is credential-free and admits only exact repository requests. */
class DesktopReviewedLocalRepositoryTransportTest {
    @Test
    fun startupOptInRequiresExactTrue() {
        assertTrue(isReviewedLocalRepositoryEnabled("true"))
        listOf(null, "", "false", "1", "TRUE", " true ").forEach {
            assertFalse(isReviewedLocalRepositoryEnabled(it))
        }
    }

    @Test
    fun rejectsNonGetBodyHeadersAndEveryNearMissBeforeNetwork() = runTest {
        val transport = DesktopReviewedLocalRepositoryTransport()
        try {
            assertFailsWith<IllegalArgumentException> {
                transport.execute(PluginHttpRequest("POST", "$BASE/repo.json"))
            }
            assertFailsWith<IllegalArgumentException> {
                transport.execute(PluginHttpRequest("GET", "$BASE/repo.json", body = byteArrayOf(1)))
            }
            assertFailsWith<IllegalArgumentException> {
                transport.execute(PluginHttpRequest("GET", "$BASE/repo.json", headers = mapOf("Authorization" to "x")))
            }
            listOf(
                "http://localhost:18081/repo.json",
                "http://[::1]:18081/repo.json",
                "http://127.0.0.1:18082/repo.json",
                "http://192.168.1.1:18081/repo.json",
                "$BASE/extra/repo.json",
                "$BASE/plugins/../repo.json",
                "$BASE/plugins/%2e%2e/repo.json",
                "$BASE/index.json?x=1",
                "$BASE/index.json?_t=1&_t=2",
                "$BASE/plugins/zh.jinmantiantang.js/extra",
                "$BASE/plugins/zh.jinmantiantang.txt",
                "$BASE/${"x".repeat(1_025)}",
            ).forEach { url ->
                assertFailsWith<IllegalArgumentException>(url) {
                    transport.execute(PluginHttpRequest("GET", url))
                }
            }
            assertTrue(ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081.admits(BASE))
        } finally {
            transport.close()
        }
    }

    private companion object {
        const val BASE = REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL
    }
}
