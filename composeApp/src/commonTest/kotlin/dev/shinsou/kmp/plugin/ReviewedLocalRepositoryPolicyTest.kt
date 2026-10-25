package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewedLocalRepositoryPolicyTest {
    @Test
    fun developmentPolicyAdmitsOnlyExactBaseAndBoundedRepositoryPaths() {
        val policy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081
        val base = REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL
        assertTrue(policy.admits(base))
        assertFalse(ReviewedLocalRepositoryPolicy.DISABLED.admits(base))
        listOf(
            "$base/", "$base/path", "http://localhost:18081", "http://127.0.0.1:18082",
            "http://127.0.0.2:18081", "http://127.1:18081", "http://2130706433:18081",
            "http://[::1]:18081", "http://192.168.1.1:18081", "https://127.0.0.1:18081",
            "http://127.0.0.1:018081", "http://user@127.0.0.1:18081",
        ).forEach { assertFalse(policy.admits(it), it) }
        listOf("repo.json", "index.json?_t=1", "plugins/zh.jinmantiantang.js?_t=123", "sidecars/zh.jinmantiantang.json").forEach {
            assertTrue(policy.admitsRequestUrl("$base/$it"), it)
            assertFalse(ReviewedLocalRepositoryPolicy.DISABLED.admitsRequestUrl("$base/$it"), it)
        }
        listOf(
            ".git/config", "plugins/../repo.json", "plugins/%2e%2e/repo.json", "plugins/a.js?redirect=1",
            "index.json?_t=1&_t=2", "index.json#fragment", "index.json?_t=-1", "//index.json",
            "merged-shuyue/index.json", "plugins/a.js/extra", "sidecars/a.json%00",
        ).forEach { assertFalse(policy.admitsRequestUrl("$base/$it"), it) }
    }

    @Test
    fun mobileDebugLanPoliciesAdmitOnlyTheirExactAuthorityAndRoutes() {
        for (policy in listOf(
            ReviewedLocalRepositoryPolicy.EXACT_IOS_LAN_192_168_50_193_18081,
            ReviewedLocalRepositoryPolicy.EXACT_ANDROID_LAN_192_168_50_193_18081,
        )) {
            val base = REVIEWED_IOS_LAN_SHINSOU_REPOSITORY_BASE_URL
            assertTrue(policy.admits(base))
            assertFalse(policy.admits(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL))
            assertFalse(ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081.admits(base))
            listOf(
                "http://192.168.50.193", "http://192.168.50.193:18080",
                "http://192.168.50.194:18081", "http://0192.168.50.193:18081",
                "http://user@192.168.50.193:18081", "https://192.168.50.193:18081",
                "$base/", "$base/path", "$base?x=1",
            ).forEach { assertFalse(policy.admits(it), it) }
            listOf(
                "repo.json", "index.json?_t=1", "index.min.json?_t=123",
                "plugins/zh.jinmantiantang.js", "sidecars/zh.jinmantiantang.json?_t=9",
            ).forEach { assertTrue(policy.admitsRequestUrl("$base/$it"), it) }
            listOf(
                ".git/config", "plugins/../repo.json", "plugins/%2e%2e/repo.json",
                "plugins/a.js?redirect=1", "index.json?_t=1&_t=2", "index.json#fragment",
                "index.json?_t=-1", "//index.json", "plugins/a.js/extra", "sidecars/a.json%00",
            ).forEach { assertFalse(policy.admitsRequestUrl("$base/$it"), it) }
            assertFalse(policy.admitsRequestUrl("$REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL/index.json"))
        }
    }

    @Test
    fun defaultCompositionRejectsLoopbackBeforeAnyTransport() = runTest {
        val http = HttpClient(MockEngine { error("Default client must never send local HTTP") })
        try {
            val client = ExtensionRepositoryClient(http)
            assertFailsWith<ExtensionRepositoryException.InvalidUrl> {
                client.fetchRepository(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL)
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun enabledPolicyCannotFallBackToGenericHttpWhenLocalTransportIsAbsent() = runTest {
        var genericRequests = 0
        val http = HttpClient(MockEngine {
            genericRequests++
            error("Missing dedicated transport must not use generic HTTP")
        })
        try {
            val client = ExtensionRepositoryClient(
                http, reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
            )
            assertFailsWith<Exception> { client.fetchRepository(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL) }
            assertEquals(0, genericRequests)
        } finally {
            http.close()
        }
    }

    @Test
    fun enabledClientStillRejectsOtherHttpAuthoritiesAndBasePathsBeforeIo() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; error("Unexpected generic HTTP request") })
        try {
            val client = ExtensionRepositoryClient(
                http, reviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
                reviewedLocalRepositoryTransport = PluginHttpTransport {
                    requests++
                    error("Unexpected local HTTP request")
                },
            )
            listOf(
                "http://localhost:18081", "http://127.0.0.1:18082", "http://[::1]:18081",
                "http://192.168.1.1:18081", "http://127.0.0.1:18081/plugins",
                "http://user@127.0.0.1:18081", "http://127.0.0.1:18081?x=1",
            ).forEach { url ->
                assertFailsWith<ExtensionRepositoryException.InvalidUrl>(url) { client.fetchRepository(url) }
            }
            assertEquals(0, requests)
        } finally {
            http.close()
        }
    }
}
