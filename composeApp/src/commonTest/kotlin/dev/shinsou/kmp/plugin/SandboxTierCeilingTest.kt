package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.network.PublicSuffixList
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxTierCeilingTest {
    @Test
    fun theBundledPublicSuffixListKnowsRegistriesAndPrivateSuffixes() {
        listOf("com", "co.uk", "co.jp", "com.tw", "github.io", "blogspot.com", "localhost", "test").forEach {
            assertTrue(PublicSuffixList.isPublicSuffix(it), it)
        }
        listOf("example.com", "example.co.uk", "user.github.io", "example.test").forEach {
            assertFalse(PublicSuffixList.isPublicSuffix(it), it)
        }
        assertEquals("example.co.uk", PublicSuffixList.registrableDomain("a.b.example.co.uk"))
        assertEquals("user.github.io", PublicSuffixList.registrableDomain("cdn.user.github.io"))
        assertNull(PublicSuffixList.registrableDomain("co.uk"))
        // Wildcard and exception rules: `*.ck` and `!www.ck`.
        assertTrue(PublicSuffixList.isPublicSuffix("anything.ck"))
        assertFalse(PublicSuffixList.isPublicSuffix("www.ck"))
        assertEquals("www.ck", PublicSuffixList.registrableDomain("a.www.ck"))
        // Internationalized rules are matched in punycode; raw Unicode fails closed.
        assertTrue(PublicSuffixList.isPublicSuffix("xn--55qx5d.cn")) // 公司.cn
        assertTrue(PublicSuffixList.isPublicSuffix("公司.cn"))
        // Case and a trailing dot do not matter.
        assertTrue(PublicSuffixList.isPublicSuffix("CO.UK."))
    }

    @Test
    fun cookiesCanNeverCoverAPublicSuffix() {
        assertTrue(isPluginCookiePublicSuffix("com"))
        assertTrue(isPluginCookiePublicSuffix("github.io"))
        assertFalse(isPluginCookiePublicSuffix("example.com"))
    }

    @Test
    fun onlyRegistrableHostNamesCanBeContentHostSuffixes() {
        listOf("example.com", "cdn.example.com", "user.github.io", "img.example.co.jp").forEach {
            assertTrue(isGrantableContentHostSuffix(it), it)
        }
        listOf("com", "co.jp", "github.io", "1.2.3.4", "Example.com", ".example.com", "example.com.", "exa mple.com", "-a.example.com")
            .forEach { assertFalse(isGrantableContentHostSuffix(it), it) }
        assertFailsWith<IllegalArgumentException> {
            SourceIndexEntry(name = "S", lang = "en", id = 1, originPolicyVersion = 2, contentHostSuffixes = setOf("github.io"))
        }
        assertFailsWith<IllegalArgumentException> {
            SourceIndexEntry(
                name = "S", lang = "en", id = 1, originPolicyVersion = 2,
                contentHostSuffixes = (1..MAX_CONTENT_HOST_SUFFIXES + 1).mapTo(linkedSetOf()) { "cdn$it.example.com" },
            )
        }
    }

    @Test
    fun aGrantedSuffixWidensOnlyTheContentPlaneOnPort443() = runTest {
        val source = SourceIndexEntry(
            name = "S", lang = "en", id = 1, baseUrl = "https://www.example.test",
            requestOrigins = setOf("https://www.example.test"),
            contentHostSuffixes = setOf("cdn.example.net"),
            originPolicyVersion = 2,
        )
        val policy = source.networkPolicy()
        val resolver = PluginHostResolver { listOf("93.184.216.34") }
        // The executable bridge cannot reach the CDN family.
        assertFailsWith<IllegalArgumentException> { policy.validate(Url("https://img1.cdn.example.net/a.jpg"), resolver) }
        // The credential-free content scope can, for the suffix itself and its subdomains on 443.
        val content = policy.contentScope()
        content.validate(Url("https://img1.cdn.example.net/a.jpg"), resolver)
        content.validate(Url("https://cdn.example.net/a.jpg"), resolver)
        assertFailsWith<IllegalArgumentException> { content.validate(Url("https://img1.cdn.example.net:8443/a.jpg"), resolver) }
        assertFailsWith<IllegalArgumentException> { content.validate(Url("https://evilcdn.example.net/a.jpg"), resolver) }
        assertFailsWith<IllegalArgumentException> { content.validate(Url("http://img1.cdn.example.net/a.jpg"), resolver) }
    }

    @Test
    fun aPackageAskingForAHostPermissionAboveTheCeilingIsRefused() {
        val entry = PluginIndexEntry(
            id = "example.plugin",
            name = "Example",
            version = "1.0.0",
            versionCode = 1,
            lang = "en",
            scriptUrl = "plugins/example.plugin.js",
            requestedHostPermissions = setOf(PluginHostPermission.REPORT_USER_MESSAGE),
        )
        assertFailsWith<IllegalArgumentException> { SandboxTierCeiling.requireWithinCeiling(entry) }
        SandboxTierCeiling.requireWithinCeiling(entry.copy(requestedHostPermissions = setOf(PluginHostPermission.REPORT_DIAGNOSTIC)))
        assertTrue(PluginRuntimePermission.COOKIE_READ in SandboxTierCeiling.SENSITIVE_RUNTIME_PERMISSIONS)
        assertFalse(PluginRuntimePermission.NETWORK in SandboxTierCeiling.SENSITIVE_RUNTIME_PERMISSIONS)
    }
}
