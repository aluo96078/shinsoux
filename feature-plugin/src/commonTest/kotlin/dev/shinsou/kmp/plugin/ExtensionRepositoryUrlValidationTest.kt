package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the repository URL grammar that [ExtensionRepositoryClient] applies to base URLs and to
 * relative asset paths, through the public asset-URL resolver.
 */
class ExtensionRepositoryUrlValidationTest {
    private fun client(allowInsecureDeveloperHttp: Boolean = false) = ExtensionRepositoryClient(
        HttpClient(MockEngine { respond("") }),
        allowInsecureDeveloperHttp = allowInsecureDeveloperHttp,
    )

    private fun assertInvalidBase(client: ExtensionRepositoryClient, baseUrl: String) {
        assertFailsWith<ExtensionRepositoryException.InvalidUrl>(baseUrl) {
            client.resolveAssetUrl(baseUrl, "icon.png")
        }
    }

    @Test
    fun resolvesAWellFormedHttpsBaseAndRelativePath() {
        assertEquals(
            "https://example.com:8443/repo/a/b.png",
            client().resolveAssetUrl("https://example.com:8443/repo/", "a/b.png"),
        )
    }

    @Test
    fun rejectsAbsoluteTraversingAndMalformedRelativePaths() {
        val client = client()
        listOf("", " ", "/abs.png", "http://evil.test/x", "../x", "a/../b", "./x", "a\\b", "a b")
            .forEach { path ->
                assertFailsWith<ExtensionRepositoryException.InvalidUrl>("'$path'") {
                    client.resolveAssetUrl("https://example.com", path)
                }
            }
    }

    @Test
    fun rejectsMalformedPercentEscapesInRelativePaths() {
        val client = client()
        listOf("a/%2e/b", "a%2", "a%zz", "a%2fb").forEach { path ->
            assertFailsWith<IllegalArgumentException>("'$path'") {
                client.resolveAssetUrl("https://example.com", path)
            }
        }
    }

    @Test
    fun rejectsMalformedHostsAndPorts() {
        val client = client()
        listOf(
            "https://exa_mple.com",
            "https://example.com:0",
            "https://example.com:65536",
            "https://example.com:80a",
            "https://example.com:80:90",
            "https://user@example.com",
            "https://[::1",
            "ftp://example.com",
        ).forEach { assertInvalidBase(client, it) }
    }

    @Test
    fun plainHttpIsOnlyAdmittedForPrivateHostsInDeveloperMode() {
        val production = client()
        assertInvalidBase(production, "http://192.168.1.5")
        val developer = client(allowInsecureDeveloperHttp = true)
        listOf(
            "http://localhost",
            "http://printer.local",
            "http://0.0.0.0",
            "http://10.1.2.3:8080",
            "http://127.0.0.1",
            "http://169.254.9.9",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://192.168.1.5",
            "http://[fd00::1]",
        ).forEach { developer.resolveAssetUrl(it, "icon.png") }
        listOf(
            "http://8.8.8.8",
            "http://172.15.0.1",
            "http://172.32.0.1",
            "http://169.253.0.1",
            "http://192.169.0.1",
            "http://example.com",
            "http://[2001:db8::1]",
        ).forEach { assertInvalidBase(developer, it) }
    }
}
