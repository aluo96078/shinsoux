package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginNetworkConfigurationTest {
    @Test
    fun sourceProxyOverrideStorageIsStableAndInvalidValuesFallBackToGlobal() {
        assertEquals("global", SourceNetworkOverride.GLOBAL.storedValue)
        assertEquals("on", SourceNetworkOverride.ON.storedValue)
        assertEquals("off", SourceNetworkOverride.OFF.storedValue)

        assertEquals(
            SourceNetworkOverride.GLOBAL,
            SourceNetworkOverride.fromStored("unknown", SourceNetworkOverride.GLOBAL),
        )
        assertEquals(SourceNetworkOverride.ON, SourceNetworkOverride.fromStored(" ON ", SourceNetworkOverride.GLOBAL))
        assertTrue(SourceNetworkOverride.GLOBAL.resolve(globalEnabled = true))
        assertFalse(SourceNetworkOverride.GLOBAL.resolve(globalEnabled = false))
        assertTrue(SourceNetworkOverride.ON.resolve(globalEnabled = false))
        assertFalse(SourceNetworkOverride.OFF.resolve(globalEnabled = true))
    }

    @Test
    fun sourceProxyDefaultsToGlobalAndExplicitOverridesRemainAuthoritative() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        var current = PluginNetworkConfiguration(
            proxyEnabled = true,
            proxyWorkerUrl = "https://proxy.example",
        )
        val resolver = ConfiguredPluginProxyResolver(storage) { current }

        assertNotNull(resolver.route(1, TARGET_URL), "An unset source must follow global on")

        current = current.copy(proxyEnabled = false)
        assertNull(resolver.route(1, TARGET_URL), "An unset source must follow global off")

        storage.setPreference(1, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "global")
        assertNull(resolver.route(1, TARGET_URL))

        storage.setPreference(1, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "on")
        assertNotNull(resolver.route(1, TARGET_URL), "Source on must override global off")
        current = current.copy(proxyEnabled = true)
        storage.setPreference(1, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "off")
        assertNull(resolver.route(1, TARGET_URL), "Source off must override global on")

        storage.setPreference(1, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "invalid")
        assertNotNull(resolver.route(1, TARGET_URL), "Invalid legacy values must safely follow global")
    }

    @Test
    fun proxyWorkerHostnameWithoutSchemeUsesHttps() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val resolver = ConfiguredPluginProxyResolver(storage) {
            PluginNetworkConfiguration(
                proxyEnabled = true,
                proxyWorkerUrl = "  shinsou.example.workers.dev/  ",
            )
        }

        val route = assertNotNull(resolver.route(1, TARGET_URL))
        val proxyUrl = Url(route.url)
        assertEquals("https", proxyUrl.protocol.name)
        assertEquals("shinsou.example.workers.dev", proxyUrl.host)
        assertEquals(TARGET_URL, proxyUrl.parameters["url"])
    }

    @Test
    fun proxyTargetIsOnePercentEncodedQueryValueAndCarriesApiKey() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setPreference(7, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "on")
        val resolver = ConfiguredPluginProxyResolver(storage) {
            PluginNetworkConfiguration(
                proxyWorkerUrl = "  https://proxy.example/worker/  ",
                proxyApiKey = "secret key",
            )
        }
        val target = "https://manga.example/read?q=one%20two&next=%2Fx%3Fy%3D1#page=2"

        val route = assertNotNull(resolver.route(7, target))
        val proxyUrl = Url(route.url)
        assertEquals("/worker/", proxyUrl.encodedPath)
        assertEquals(target, proxyUrl.parameters["url"])
        assertEquals("secret key", route.headers[ConfiguredPluginProxyResolver.PROXY_KEY_HEADER])
        assertTrue("%26" in route.url, "Nested ampersands must not become Worker query delimiters")
        assertTrue("%23" in route.url, "The target fragment must stay inside the url parameter")
        assertTrue("%25" in route.url, "Existing target escapes must be encoded exactly once as query data")
        assertFalse("&next=" in route.url)
    }

    @Test
    fun configuredUserAgentIsDynamicWhilePluginHeaderKeepsPriority() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        var current = PluginNetworkConfiguration(customUserAgent = "  Shinsou Custom/1.0  ")
        val userAgents = ConfiguredPluginUserAgentProvider(
            configuration = PluginNetworkConfigurationProvider { current },
            fallback = PluginUserAgentProvider { "sticky-fallback" },
        )
        val builder = PluginRequestBuilder(storage, userAgents = userAgents)

        val configured = builder.build(1, PluginHttpRequest("GET", TARGET_URL))
        assertEquals("Shinsou Custom/1.0", configured.transportRequest.headers["User-Agent"])

        val plugin = builder.build(
            sourceId = 1,
            request = PluginHttpRequest("GET", TARGET_URL),
            sourceHeaders = mapOf("user-agent" to "Plugin Specific/2.0"),
        )
        assertEquals(
            "Plugin Specific/2.0",
            plugin.transportRequest.headers.entries.single { it.key.equals("User-Agent", true) }.value,
        )

        current = current.copy(customUserAgent = "")
        val fallback = builder.build(1, PluginHttpRequest("GET", TARGET_URL))
        assertEquals("sticky-fallback", fallback.transportRequest.headers["User-Agent"])
    }

    @Test
    fun proxiedRequestUsesDeviceBrowserUserAgentInsteadOfSourceHint() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val configuration = PluginNetworkConfigurationProvider {
            PluginNetworkConfiguration(
                proxyEnabled = true,
                proxyWorkerUrl = "proxy.example.workers.dev",
            )
        }
        val builder = PluginRequestBuilder(
            storage = storage,
            userAgents = ConfiguredPluginUserAgentProvider(
                configuration = configuration,
                fallback = PluginUserAgentProvider { "Device Browser/1.0" },
            ),
            proxyResolver = ConfiguredPluginProxyResolver(storage, configuration),
        )

        val built = builder.build(
            sourceId = 1,
            request = PluginHttpRequest("GET", TARGET_URL),
            sourceHeaders = mapOf("User-Agent" to "Source iPhone Hint/17"),
        )

        assertEquals("Device Browser/1.0", built.transportRequest.headers["User-Agent"])
        assertTrue(built.transportRequest.url.startsWith("https://proxy.example.workers.dev/"))
    }

    @Test
    fun cloudflareSessionUserAgentStillWinsForProxiedRequests() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setWebChallengeUserAgent(1, "Cloudflare Browser/2.0")
        val configuration = PluginNetworkConfigurationProvider {
            PluginNetworkConfiguration(
                proxyEnabled = true,
                proxyWorkerUrl = "https://proxy.example",
            )
        }
        val builder = PluginRequestBuilder(
            storage = storage,
            userAgents = PluginUserAgentProvider { "Device Browser/1.0" },
            proxyResolver = ConfiguredPluginProxyResolver(storage, configuration),
        )

        val built = builder.build(
            sourceId = 1,
            request = PluginHttpRequest("GET", TARGET_URL),
            sourceHeaders = mapOf("User-Agent" to "Source Hint"),
        )

        assertEquals("Cloudflare Browser/2.0", built.transportRequest.headers["User-Agent"])
    }

    private companion object {
        const val TARGET_URL = "https://manga.example/chapter"
    }
}
